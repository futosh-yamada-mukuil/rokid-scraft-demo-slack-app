/**
 * 受信サーバの実行入口。
 * 組み立ては app.ts（createApp）に分離してあるので、ここは listen と案内表示だけ。
 */
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { parseEnv } from "node:util";

import { createApp } from "./app.ts";
import { lanIp } from "./lan-ip.ts";
import { createLanguageService } from "./language.ts";

const here = dirname(fileURLToPath(import.meta.url));
// worktree 等からでも既存の受信データを明示できるよう、createApp と同じ
// UPLOAD_DIR の優先順位にする。従来はここで固定値を渡して環境変数を潰していた。
const uploadDir = resolve(
  process.env.UPLOAD_DIR ?? resolve(here, "..", "uploads"),
);
const webDist = resolve(here, "..", "..", "web", "dist");
const port = Number(process.env.PORT ?? 8787);

/**
 * リポジトリ直下の .env を読む（gitignore 済み）。引用符付きの値にも対応し、
 * Slack の Bot トークンなどの値はログに出さない。
 */
function readEnv(): Record<string, string | undefined> {
  try {
    return parseEnv(
      readFileSync(resolve(here, "..", "..", "..", ".env"), "utf8"),
    );
  } catch {
    console.log("（.env を読めません。Slack接続の設定を確認してください）");
    return {};
  }
}

const env = readEnv();
const language = createLanguageService({
  apiKey:
    process.env.GOOGLE_STT_TTS_TRANSLATE_API_KEY ??
    env.GOOGLE_STT_TTS_TRANSLATE_API_KEY ??
    "",
});

const app = await createApp({
  uploadDir,
  webDist,
  port,
  language,
  teams: {
    tenantId: env.MS_TENANT_ID ?? "",
    clientId: env.MS_CLIENT_ID ?? "",
    teamId: env.MS_TEAM_ID ?? "",
    channelId: env.MS_CHANNEL_ID ?? "",
    tokenPath: resolve(here, "..", ".teams-token.json"),
  },
  slack: {
    botToken: process.env.SLACK_BOT_TOKEN ?? env.SLACK_BOT_TOKEN ?? "",
    workspaceId: process.env.SLACK_WORKSPACE_ID ?? env.SLACK_WORKSPACE_ID ?? "",
    channelId: process.env.SLACK_CHANNEL_ID ?? env.SLACK_CHANNEL_ID ?? "",
    statePath:
      process.env.SLACK_STATE_PATH ??
      resolve(here, "..", "..", "..", ".local", "slack", "relay-state.json"),
    pairingUrl:
      process.env.SLACK_PAIRING_URL ??
      env.SLACK_PAIRING_URL ??
      `http://localhost:${port}/slack/pair`,
  },
});
await app.listen({ host: process.env.HOST ?? "0.0.0.0", port });

const ip = lanIp();
const line = "─".repeat(62);
console.log(line);
console.log("  SCRAFT 現場レポート — 受信サーバ");
console.log(`  Slack接続    : http://localhost:${port}/slack`);
console.log(line);
if (process.env.HOST === "127.0.0.1") {
  console.log(`  AVD中継      : http://127.0.0.1:${port}（adb reverse経由）`);
} else {
  console.log("  モニタ       : http://localhost:5514      (pnpm dev の Vite)");
  console.log(`  PC受信API    : http://${ip}:${port}/upload`);
  console.log(`  保存先       : ${uploadDir}`);
}
console.log(line);
if (process.env.HOST === "127.0.0.1") {
  console.log("  AVD起動      : 別ターミナルで bash run.sh slack");
  console.log("  初回の端末登録は http://localhost:8787/slack/pair で行います");
} else {
  console.log("  ※ PCモニター用の送信は同一LANから行います");
}
console.log();
