/**
 * Starts the real relay (app/api) against an in-memory Slack and prints where it listens.
 * Used by scripts/verify-slack-relay-e2e.sh; never talks to a real Slack workspace.
 *
 *   node --experimental-strip-types scripts/slack-relay-e2e-server.ts
 *
 * Environment:
 *   SLACK_E2E_LATENCY_MS  simulated round trip per Slack call (default 150)
 *   SLACK_E2E_TIMING      JSON for the relay's polling cadence, e.g. {"freshMs":3000}
 *   SLACK_E2E_API_SRC     directory holding app.ts, to measure another revision of the relay
 *
 * Control endpoints (second port, loopback only):
 *   POST /approve?code=ABCD1234   approve a device code, as the PC pairing page does
 *   POST /reply?text=...          a person replies in the newest photo thread
 *   GET  /stats                   Slack calls made so far, by method
 */
import { mkdtempSync } from "node:fs";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { FAKE_SLACK, FakeSlack } from "../app/api/test/support/fake-slack.ts";

const root = mkdtempSync(join(tmpdir(), "slack-relay-e2e-"));
const slack = new FakeSlack(Number(process.env.SLACK_E2E_LATENCY_MS ?? 150));
const source = resolve(
  process.env.SLACK_E2E_API_SRC ??
    join(import.meta.dirname, "..", "app", "api", "src"),
);
const { createApp } = await import(pathToFileURL(join(source, "app.ts")).href);
const app = await createApp({
  uploadDir: join(root, "uploads"),
  serveStatic: false,
  slack: {
    botToken: FAKE_SLACK.botToken,
    workspaceId: FAKE_SLACK.workspaceId,
    channelId: FAKE_SLACK.channelId,
    statePath: join(root, "state.json"),
  },
  slackFetch: slack.fetch,
  slackTiming: process.env.SLACK_E2E_TIMING
    ? JSON.parse(process.env.SLACK_E2E_TIMING)
    : undefined,
});
const relay = await app.listen({ host: "127.0.0.1", port: 0 });

const control = createServer(async (req, res) => {
  const url = new URL(req.url ?? "/", "http://127.0.0.1");
  const send = (code: number, body: unknown) => {
    res.writeHead(code, { "content-type": "application/json" });
    res.end(JSON.stringify(body));
  };
  try {
    if (req.method === "POST" && url.pathname === "/approve") {
      const page = await app.inject({ url: "/slack/pair" });
      const csrf = /name="csrf" value="([^"]+)"/.exec(page.body)?.[1] ?? "";
      const approved = await app.inject({
        method: "POST",
        url: "/slack/pair",
        headers: { "content-type": "application/x-www-form-urlencoded" },
        payload: new URLSearchParams({
          code: url.searchParams.get("code") ?? "",
          csrf,
        }).toString(),
      });
      return send(approved.statusCode, { ok: approved.statusCode === 200 });
    }
    if (req.method === "POST" && url.pathname === "/reply") {
      const thread = url.searchParams.get("thread") ?? slack.photoThreads()[0];
      if (!thread) return send(409, { error: "no photo has been posted yet" });
      const ts = slack.addReply(thread, url.searchParams.get("text") ?? "");
      return send(200, { ts, thread });
    }
    if (req.method === "GET" && url.pathname === "/stats")
      return send(200, slack.calls);
    send(404, { error: "unknown control endpoint" });
  } catch (error) {
    send(500, { error: error instanceof Error ? error.message : "failed" });
  }
});
control.listen(0, "127.0.0.1", () => {
  const address = control.address();
  const port = typeof address === "object" && address ? address.port : 0;
  console.log(JSON.stringify({ relay, control: `http://127.0.0.1:${port}` }));
});

for (const signal of ["SIGINT", "SIGTERM"] as const)
  process.on(signal, () => {
    control.close();
    void app.close().finally(() => process.exit(0));
  });
