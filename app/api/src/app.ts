import { mkdir } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import fastifyMultipart from "@fastify/multipart";
import Fastify, { type FastifyInstance } from "fastify";
import { createAuth } from "./auth.ts";
import { KINDS, MAX_BODY } from "./config.ts";
import { createEventBus } from "./events.ts";
import { type LanguageService, noLanguageService } from "./language.ts";
import { createReplyStore } from "./replies.ts";
import { entryRoutes } from "./routes/entries.ts";
import { healthRoutes } from "./routes/health.ts";
import { replyRoutes } from "./routes/replies.ts";
import { staticRoutes } from "./routes/static.ts";
import { streamRoutes } from "./routes/stream.ts";
import { teamsRoutes } from "./routes/teams.ts";
import { tokenRoutes } from "./routes/token.ts";
import { uploadRoutes } from "./routes/upload.ts";
import { SlackClient, type SlackConfig } from "./slack.ts";
import { SlackRelay, type SlackRelayTiming } from "./slack-relay.ts";
import { createStorage } from "./storage.ts";
import { createTeams, type TeamsConfig } from "./teams.ts";

const HERE = dirname(fileURLToPath(import.meta.url));

export type AppOptions = {
  /**
   * 受信データの置き場。省略時は `UPLOAD_DIR` 環境変数、無ければ `app/api/uploads`。
   * テストは一時ディレクトリを渡す（引数でも環境変数でもよい）。
   */
  uploadDir?: string;
  /** 論理削除の置き場。省略時は uploads の兄弟（`../trash`）。 */
  trashDir?: string;
  /** モニタ(app/web)のビルド成果物。無ければ静的配信をスキップする。 */
  webDist?: string;
  /** 共有トークンの置き場。既定は uploads の隣。テストは一時ディレクトリを指す。 */
  tokenPath?: string;
  /** /api/health が返すポート。モニタが宛先 URL を組むのに使う。 */
  port?: number;
  /** Teams への復旧動線の設定。未設定なら関連エンドポイントは 400 を返す。 */
  teams?: TeamsConfig;
  /** Slack relay: credentials stay on the PC. A fetcher is injectable for offline contract tests. */
  slack?: SlackConfig;
  slackFetch?: typeof fetch;
  /** Slack polling cadence. Tests shorten it; production uses the relay defaults. */
  slackTiming?: Partial<SlackRelayTiming>;
  /** 音声解析と翻訳。テストではフェイクを渡し、未指定時は外部通信しない。 */
  language?: LanguageService;
  /** PC→Rokid返信の保存先。省略時は uploads の兄弟（`../replies`）。 */
  replyDir?: string;
  /** 静的配信を登録するか。テストは false にして無関係な I/O を避ける。既定 true。 */
  serveStatic?: boolean;
};

const NO_TEAMS: TeamsConfig = {
  tenantId: "",
  clientId: "",
  teamId: "",
  channelId: "",
  tokenPath: "",
};

/**
 * Fastify アプリを組み立てて返す。**listen はしない。**
 *
 * 起動（listen）と組み立てを分けているのは、テストがポートを開かずに
 * `app.inject()` で叩けるようにするため。設定は引数で受け、モジュールに
 * グローバル状態を持たせない（複数インスタンスを並行に立てられる）。
 * 引数を省略した場合だけ環境変数と既定値にフォールバックする。
 */
export async function createApp(
  opts: AppOptions = {},
): Promise<FastifyInstance> {
  const app = Fastify({ bodyLimit: MAX_BODY, logger: false });

  const uploadDir =
    opts.uploadDir ?? process.env.UPLOAD_DIR ?? resolve(HERE, "..", "uploads");
  const port = opts.port ?? Number(process.env.PORT ?? 8787);

  const storage = createStorage(uploadDir, opts.trashDir);
  // 同一 LAN の第三者に投稿・削除させないための共有トークン。設定QR でグラスへ渡す。
  const auth = createAuth(
    opts.tokenPath ?? resolve(uploadDir, "..", ".upload-token"),
  );
  const bus = createEventBus();
  const teams = createTeams(opts.teams ?? NO_TEAMS);
  const language = opts.language ?? noLanguageService;
  const replies = createReplyStore(
    opts.replyDir ?? resolve(uploadDir, "..", "replies"),
  );
  await mkdir(storage.dir, { recursive: true });

  // 生バイト（image/jpeg / video/mp4）をそのまま Buffer で受ける。
  // ルートより先に、かつルート側の encapsulation の外で登録する必要がある。
  for (const ct of Object.keys(KINDS)) {
    app.addContentTypeParser(
      ct,
      { parseAs: "buffer", bodyLimit: MAX_BODY },
      (_req, body, done) => done(null, body),
    );
  }
  await app.register(fastifyMultipart, { limits: { fileSize: MAX_BODY } });

  await app.register(healthRoutes, { port });
  await app.register(tokenRoutes, { auth });
  await app.register(uploadRoutes, { storage, bus, auth, language });
  await app.register(entryRoutes, { storage, bus, auth });
  await app.register(replyRoutes, { storage, replies, language, bus, auth });
  await app.register(teamsRoutes, { storage, teams, bus, auth });
  if (opts.slack) {
    const relay = new SlackRelay(
      new SlackClient(opts.slack, opts.slackFetch),
      opts.slackTiming,
    );
    await app.register(async (instance) => relay.register(instance));
  }
  await app.register(streamRoutes, { bus });
  if (opts.serveStatic !== false) {
    await app.register(staticRoutes, {
      uploadDir: storage.dir,
      webDist: opts.webDist ?? resolve(HERE, "..", "..", "web", "dist"),
    });
  }

  return app;
}
