import type { FastifyInstance } from "fastify";
import type { LocalReply } from "shared";

import { type Auth, requireTokenOrLocal } from "../auth.ts";
import type { EventBus } from "../events.ts";
import { demoLanguage, type LanguageService } from "../language.ts";
import type { ReplyStore } from "../replies.ts";
import type { Storage } from "../storage.ts";

export type ReplyDeps = {
  storage: Storage;
  replies: ReplyStore;
  language: LanguageService;
  bus: EventBus;
  auth: Auth;
};

/** Teamsを経由せず、PCとRokidの間だけで返信を往復させる。 */
export async function replyRoutes(
  app: FastifyInstance,
  { storage, replies, language, bus, auth }: ReplyDeps,
): Promise<void> {
  app.get<{ Querystring: { device?: string } }>(
    "/api/replies",
    { preHandler: requireTokenOrLocal(auth) },
    async (req) => replies.list(req.query.device?.trim() || undefined),
  );

  app.post<{ Body: { entryId?: unknown; text?: unknown } }>(
    "/api/replies",
    { preHandler: requireTokenOrLocal(auth) },
    async (req, reply) => {
      const ctype = String(req.headers["content-type"] ?? "");
      let entryId = "";
      let text = "";
      let audio: Buffer | undefined;
      let audioMime = "audio/webm";
      if (ctype.startsWith("multipart/form-data")) {
        for await (const part of req.parts()) {
          if (part.type === "file" && part.fieldname === "audio") {
            audio = await part.toBuffer();
            audioMime = part.mimetype || audioMime;
          } else if (part.type === "field" && part.fieldname === "entryId") {
            entryId = String(part.value).trim();
          } else if (part.type === "field" && part.fieldname === "text") {
            text = String(part.value).trim();
          }
        }
      } else {
        entryId =
          typeof req.body?.entryId === "string" ? req.body.entryId.trim() : "";
        text = typeof req.body?.text === "string" ? req.body.text.trim() : "";
      }
      if (!entryId || (!text && !audio?.length)) {
        return reply.code(400).send({ error: "返信対象と本文が必要です" });
      }
      if (text.length > 1_000) {
        return reply.code(400).send({ error: "返信は1000文字以内です" });
      }
      const entry = (await storage.list()).find(
        (value) => value.id === entryId,
      );
      if (!entry)
        return reply.code(404).send({ error: "受信データが見つかりません" });
      if (!entry.device)
        return reply.code(400).send({ error: "返信先端末が不明です" });

      try {
        const target = demoLanguage(entry.language);
        const speech = audio?.length
          ? await language.translateSpeech({ audio, audioMime, target })
          : null;
        const originalText = speech?.transcript ?? text;
        const translatedText =
          speech?.translated ??
          (await language.translateJapanese(text, target));
        const saved: LocalReply = await replies.add({
          entryId,
          device: entry.device,
          originalText,
          translatedText,
          language: target,
        });
        bus.broadcast({ type: "reply-added", reply: saved });
        console.log(
          `↩️  ローカル返信 ${saved.id} → ${saved.device} (${saved.language})`,
        );
        return saved;
      } catch (error) {
        return reply.code(502).send({
          error: `返信の翻訳に失敗しました: ${(error as Error).message}`,
        });
      }
    },
  );
}
