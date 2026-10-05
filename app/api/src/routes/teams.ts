import { readFile } from "node:fs/promises";
import { join } from "node:path";

import type { FastifyInstance } from "fastify";
import { type Auth, requireTokenOrLocal } from "../auth.ts";
import { ID_RE } from "../config.ts";
import type { EventBus } from "../events.ts";
import type { Storage } from "../storage.ts";
import type { Teams } from "../teams.ts";

export type TeamsDeps = {
  storage: Storage;
  teams: Teams;
  bus: EventBus;
  auth: Auth;
};

/**
 * Teams への復旧動線。
 *
 * 本来の経路はグラスが直接 Graph を叩くもので、これは**そこが失敗したときに
 * 受信モニタから人手で送り直すための口**。UI は app/web 側（次フェーズ）。
 *
 * 注意: Teams の hostedContents は画像専用なので、動画は投稿できない（400 で返す）。
 */
export async function teamsRoutes(
  app: FastifyInstance,
  { storage, teams, bus, auth }: TeamsDeps,
): Promise<void> {
  app.get("/api/teams/status", async () => {
    if (!teams.configured) {
      return {
        signedIn: false,
        expiresAt: null,
        channelUrl: "",
        configured: false,
      };
    }
    return { ...(await teams.status()), configured: true };
  });

  /** Device Code Flow を開始し、グラス／画面に出すコードを返す。 */
  app.post(
    "/api/teams/signin",
    { preHandler: requireTokenOrLocal(auth) },
    async (_req, reply) => {
      if (!teams.configured) {
        return reply
          .code(400)
          .send({ error: "Teams の設定（MS_* ）が .env にありません" });
      }
      try {
        return await teams.startSignIn();
      } catch (e) {
        return reply.code(502).send({ error: (e as Error).message });
      }
    },
  );

  /** ブラウザでの入力待ちの状態。web はこれをポーリングする。 */
  app.get("/api/teams/signin", async () => teams.signInProgress());

  /** Web モーダルと Rokid が共有する開発用チャネル。Graph のトークンはブラウザへ渡さない。 */
  app.get(
    "/api/teams/channel/messages",
    { preHandler: requireTokenOrLocal(auth) },
    async (_req, reply) => {
      try {
        return await teams.listChannelMessages();
      } catch (e) {
        return reply.code(502).send({ error: (e as Error).message });
      }
    },
  );

  app.post<{ Body: { text?: unknown } }>(
    "/api/teams/channel/messages",
    { preHandler: requireTokenOrLocal(auth) },
    async (req, reply) => {
      const text =
        typeof req.body?.text === "string" ? req.body.text.trim() : "";
      if (!text)
        return reply.code(400).send({ error: "メッセージを入力してください" });
      if (text.length > 4000) {
        return reply
          .code(400)
          .send({ error: "メッセージは4000文字以内にしてください" });
      }
      try {
        return await teams.sendChannelMessage(text);
      } catch (e) {
        return reply.code(502).send({ error: (e as Error).message });
      }
    },
  );

  /** 受信済みの1件を Teams へ投稿する。 */
  app.post<{ Params: { id: string } }>(
    "/api/teams/post/:id",
    { preHandler: requireTokenOrLocal(auth) },
    async (req, reply) => {
      const { id } = req.params;
      if (!ID_RE.test(id)) return reply.code(400).send({ error: "bad id" });

      const entry = (await storage.list()).find((e) => e.id === id);
      if (!entry) return reply.code(404).send({ error: "not found" });
      if (entry.kind === "video") {
        return reply.code(400).send({
          error: "動画は Teams へ送れません（hostedContents は画像専用）",
        });
      }

      let blob: Buffer | undefined;
      if (entry.file) blob = await readFile(join(storage.dir, entry.file));

      try {
        const posted = await teams.post({
          comment: entry.comment,
          blob,
          mime: "image/jpeg",
        });
        // 投稿済みの印を meta に書き戻し、画面へも配る。
        const updated = { ...entry, teams: posted };
        await storage.update(updated);
        bus.broadcast({ type: "added", entry: updated });
        console.log(`📤 Teams へ投稿 ${id} → ${posted.messageId}`);
        return { ok: true, teams: posted };
      } catch (e) {
        return reply.code(502).send({ error: (e as Error).message });
      }
    },
  );
}
