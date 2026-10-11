import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, test } from "node:test";
import type { FastifyInstance } from "fastify";
import { createApp } from "../src/app.ts";
import { SlackClient, type SlackMessage } from "../src/slack.ts";
import type { SlackRelayTiming } from "../src/slack-relay.ts";

let root: string;
let app: FastifyInstance;
let client: SlackClient;
let uploads: number;
let caption: string;
let history: SlackMessage[];
let replyCalls: string[];
let identityWorkspace: string;
let privateChannel: boolean;
let member: boolean;
let rateLimit: boolean;
let infoMissing: boolean;
/** Thread ts → single-page replies. Threads not listed keep the two-page default below. */
let threads: Record<string, SlackMessage[]>;
let historyCalls: number;
let transfers: number;
const config = () => ({
  botToken: "xoxb-test-secret",
  workspaceId: "T_MUKUIL",
  channelId: "C_PRIVATE",
  statePath: join(root, "state.json"),
});

const fetcher: typeof fetch = async (input, init) => {
  const method = String(input).split("/").at(-1);
  const fields = new URLSearchParams(String(init?.body));
  if (String(input).startsWith("https://files.slack.com/")) {
    assert.equal(init?.headers && "Authorization" in init.headers, false);
    transfers++;
    return new Response("OK");
  }
  assert.equal(
    (init?.headers as Record<string, string> | undefined)?.Authorization,
    "Bearer xoxb-test-secret",
  );
  if (rateLimit)
    return new Response("", { status: 429, headers: { "retry-after": "12" } });
  switch (method) {
    case "auth.test":
      return Response.json({
        ok: true,
        team_id: identityWorkspace,
        user_id: "U_BOT",
        bot_id: "B_BOT",
      });
    case "conversations.info":
      return Response.json({
        ok: true,
        channel: {
          id: "C_PRIVATE",
          name: "開発用",
          is_private: privateChannel,
          is_member: member,
          is_archived: false,
        },
      });
    case "files.getUploadURLExternal":
      return Response.json({
        ok: true,
        file_id: `F${uploads + 1}`,
        upload_url: "https://files.slack.com/upload/test",
      });
    case "files.completeUploadExternal": {
      assert.equal(fields.get("channel_id"), "C_PRIVATE");
      caption = fields.get("initial_comment") ?? "";
      uploads++;
      return Response.json({ ok: true });
    }
    case "files.info":
      return infoMissing
        ? Response.json({ ok: false, error: "file_not_found" })
        : Response.json({
            ok: true,
            file: {
              shares: {
                private: {
                  C_PRIVATE: [{ ts: `1000.${fields.get("file")?.slice(1)}` }],
                },
              },
            },
          });
    case "conversations.history":
      historyCalls++;
      return Response.json({ ok: true, messages: history });
    case "conversations.replies": {
      const ts = fields.get("ts") ?? "";
      replyCalls.push(ts);
      if (threads[ts])
        return Response.json({ ok: true, messages: threads[ts] });
      if (!fields.get("cursor"))
        return Response.json({
          ok: true,
          messages: [
            { ts, text: "写真", user: "U_BOT" },
            {
              ts: `${Number(ts) + 10}`,
              text: `返信-${ts}`,
              user: "U_HUMAN",
              thread_ts: ts,
            },
          ],
          has_more: true,
          response_metadata: { next_cursor: "page2" },
        });
      return Response.json({
        ok: true,
        messages: [
          {
            ts: `${Number(ts) + 20}`,
            text: "<https://example.com|図面> &amp; 確認",
            user: "U_HUMAN",
            thread_ts: ts,
          },
        ],
        has_more: false,
      });
    }
    case "users.info":
      return Response.json({
        ok: true,
        user: {
          name: "test",
          profile: {
            display_name: fields.get("user") === "U_BOT" ? "Rokid" : "PC担当",
          },
        },
      });
    default:
      throw new Error(`Unexpected Slack API ${method}`);
  }
};

beforeEach(async () => {
  root = await mkdtemp(join(tmpdir(), "slack-relay-"));
  uploads = 0;
  caption = "";
  history = [];
  replyCalls = [];
  identityWorkspace = "T_MUKUIL";
  privateChannel = true;
  member = true;
  rateLimit = false;
  infoMissing = false;
  threads = {};
  historyCalls = 0;
  transfers = 0;
  client = new SlackClient(config(), fetcher);
  app = await createApp({
    uploadDir: join(root, "uploads"),
    serveStatic: false,
    slack: config(),
    slackFetch: fetcher,
  });
});
afterEach(async () => {
  await app.close();
  await rm(root, { recursive: true, force: true });
});

/** Same PC state, different polling cadence: the relay is recreated the way a restart would. */
async function restart(slackTiming: Partial<SlackRelayTiming>): Promise<void> {
  await app.close();
  app = await createApp({
    uploadDir: join(root, "uploads"),
    serveStatic: false,
    slack: config(),
    slackFetch: fetcher,
    slackTiming,
  });
}

const feed = (token: string, query = "") =>
  app.inject({
    url: `/api/slack/feed${query}`,
    headers: { authorization: `Bearer ${token}` },
  });

/** A photo parent as channel history reports it, with the thread summary Slack attaches. */
const parent = (ts: string, replies: SlackMessage[] = []): SlackMessage => ({
  ts,
  text: "写真",
  user: "U_BOT",
  files: [{ id: `F${ts.split(".")[1]}`, mimetype: "image/jpeg" }],
  ...(replies.length
    ? {
        thread_ts: ts,
        reply_count: replies.length,
        latest_reply: replies.at(-1)?.ts,
      }
    : {}),
});
const reply = (ts: string, threadTs: string, text: string): SlackMessage => ({
  ts,
  text,
  user: "U_HUMAN",
  thread_ts: threadTs,
});
function thread(ts: string, replies: SlackMessage[]): void {
  threads[ts] = [parent(ts, replies), ...replies];
  history = history.map((m) => (m.ts === ts ? parent(ts, replies) : m));
}

async function pair(label: string): Promise<string> {
  const page = await app.inject({ url: "/slack/pair" });
  const csrf = /name="csrf" value="([^"]+)"/.exec(page.body)?.[1];
  assert.ok(csrf);
  const codeResponse = await app.inject({
    method: "POST",
    url: "/api/slack/device-code",
    payload: { label },
  });
  assert.equal(codeResponse.statusCode, 200);
  const code = codeResponse.json();
  const pending = await app.inject({
    method: "POST",
    url: "/api/slack/device-token",
    payload: { device_code: code.device_code },
  });
  assert.equal(pending.json().status, "pending");
  const approved = await app.inject({
    method: "POST",
    url: "/slack/pair",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    payload: new URLSearchParams({ code: code.user_code, csrf }).toString(),
  });
  assert.equal(approved.statusCode, 200);
  assert.ok(!approved.body.includes("xoxb-test-secret"));
  const connected = await app.inject({
    method: "POST",
    url: "/api/slack/device-token",
    payload: { device_code: code.device_code },
  });
  return connected.json().access_token;
}

function photo(
  token: string,
  requestId = "request-001",
  comment = "設備 <@U_ALL> & 確認",
) {
  const boundary = "test-photo-boundary";
  return app.inject({
    method: "POST",
    url: "/api/slack/photos",
    headers: {
      authorization: `Bearer ${token}`,
      "content-type": `multipart/form-data; boundary=${boundary}`,
    },
    payload: Buffer.concat([
      Buffer.from(
        `--${boundary}\r\nContent-Disposition: form-data; name="comment"\r\n\r\n${comment}\r\n--${boundary}\r\nContent-Disposition: form-data; name="requestId"\r\n\r\n${requestId}\r\n--${boundary}\r\nContent-Disposition: form-data; name="photo"; filename="photo.jpg"\r\nContent-Type: image/jpeg\r\n\r\n`,
      ),
      Buffer.from([0xff, 0xd8, 0xff, 0x01]),
      Buffer.from(`\r\n--${boundary}--\r\n`),
    ]),
  });
}

test("pairing requires localhost, a valid form token, and the browser's own origin", async () => {
  for (const [ip, host] of [
    ["192.168.1.4", "localhost"],
    ["127.0.0.1", "evil.example"],
  ]) {
    assert.equal(
      (
        await app.inject({
          url: "/slack/pair",
          remoteAddress: ip,
          headers: { host },
        })
      ).statusCode,
      403,
    );
  }
  assert.equal(
    (
      await app.inject({
        method: "POST",
        url: "/slack/pair",
        headers: {
          "content-type": "application/x-www-form-urlencoded",
          origin: "https://evil.example",
        },
        payload: "code=12345678",
      })
    ).statusCode,
    403,
  );
  assert.equal(
    (
      await app.inject({
        method: "POST",
        url: "/slack/pair",
        headers: { "content-type": "application/x-www-form-urlencoded" },
        payload: "code=12345678",
      })
    ).statusCode,
    403,
  );
  assert.equal(
    (
      await app.inject({
        method: "POST",
        url: "/api/slack/device-token",
        payload: { device_code: "unknown" },
      })
    ).statusCode,
    400,
  );
});

test("registered device credentials persist as hashes with private permissions", async () => {
  const token = await pair("Rokid_Test");
  const saved = await readFile(config().statePath, "utf8");
  assert.ok(!saved.includes(token));
  assert.ok(!saved.includes("xoxb-test-secret"));
  assert.equal((await stat(config().statePath)).mode & 0o777, 0o600);
  assert.equal(
    (
      await app.inject({
        url: "/api/slack/session",
        headers: { authorization: `Bearer ${token}` },
      })
    ).statusCode,
    200,
  );
  assert.equal((await app.inject({ url: "/api/slack/feed" })).statusCode, 401);
  await app.close();
  app = await createApp({
    uploadDir: join(root, "uploads"),
    serveStatic: false,
    slack: config(),
    slackFetch: fetcher,
  });
  assert.equal(
    (
      await app.inject({
        url: "/api/slack/session",
        headers: { authorization: `Bearer ${token}` },
      })
    ).statusCode,
    200,
  );
});

test("new upload API posts photo and escaped comment to the fixed private channel and deduplicates retries", async () => {
  const token = await pair("Rokid_Test");
  const result = await photo(token);
  assert.equal(result.statusCode, 200);
  assert.equal(result.json().fileId, "F1");
  assert.equal(caption, "【Rokid_Test】\n設備 &lt;@U_ALL&gt; &amp; 確認");
  assert.equal((await photo(token)).statusCode, 200);
  assert.equal(uploads, 1);
});

test("delayed file shares still count as successful send and are resolved by history", async () => {
  const token = await pair("Rokid_Test");
  infoMissing = true;
  assert.equal((await photo(token)).statusCode, 200);
  history = [
    {
      ts: "1000.1",
      text: "写真",
      user: "U_BOT",
      files: [{ id: "F1", mimetype: "image/jpeg" }],
    },
  ];
  assert.equal(
    (
      await app.inject({
        url: "/api/slack/feed",
        headers: { authorization: `Bearer ${token}` },
      })
    ).statusCode,
    200,
  );
  assert.ok(replyCalls.includes("1000.1"));
});

test("thread replies are paginated and routed only to their originating device, even outside channel history", async () => {
  const first = await pair("Rokid_A");
  const second = await pair("Rokid_B");
  await photo(first, "request-a");
  await photo(second, "request-b");
  const feedA = (
    await app.inject({
      url: "/api/slack/feed",
      headers: { authorization: `Bearer ${first}` },
    })
  ).json().posts;
  const feedB = (
    await app.inject({
      url: "/api/slack/feed",
      headers: { authorization: `Bearer ${second}` },
    })
  ).json().posts;
  assert.ok(feedA.some((p: { text: string }) => p.text === "返信-1000.1"));
  assert.ok(!feedA.some((p: { text: string }) => p.text === "返信-1000.2"));
  assert.ok(feedB.some((p: { text: string }) => p.text === "返信-1000.2"));
  assert.ok(
    feedA.some(
      (p: { text: string; author: string; isOwn: boolean }) =>
        p.text === "図面 & 確認" && p.author === "PC担当" && !p.isOwn,
    ),
  );
  assert.equal(
    replyCalls.length,
    4,
    "the shared cache prevents polling once per Android screen",
  );
});

test("workspace mismatch, public channels and missing membership prevent posting", async () => {
  identityWorkspace = "T_OTHER";
  await assert.rejects(client.ready(), /一致/);
  identityWorkspace = "T_MUKUIL";
  privateChannel = false;
  await assert.rejects(client.ready(), /プライベート/);
  privateChannel = true;
  member = false;
  await assert.rejects(client.ready(), /Botを追加/);
  assert.equal(uploads, 0);
});

test("revoked devices and tokens scoped to another channel cannot access messages", async () => {
  const token = await pair("Rokid_Test");
  const page = await app.inject({ url: "/slack" });
  const csrf = /name="csrf" value="([^"]+)"/.exec(page.body)?.[1] ?? "";
  const device = /name="device" value="([^"]+)"/.exec(page.body)?.[1] ?? "";
  assert.equal(
    (
      await app.inject({
        method: "POST",
        url: "/slack/revoke",
        headers: { "content-type": "application/x-www-form-urlencoded" },
        payload: new URLSearchParams({ csrf, device }).toString(),
      })
    ).statusCode,
    302,
  );
  assert.equal(
    (
      await app.inject({
        url: "/api/slack/feed",
        headers: { authorization: `Bearer ${token}` },
      })
    ).statusCode,
    401,
  );
});

test("Slack rate-limit is surfaced with retry-after and no credentials", async () => {
  rateLimit = true;
  const result = await app.inject({
    method: "POST",
    url: "/api/slack/device-code",
    payload: { label: "test" },
  });
  assert.equal(result.statusCode, 503);
  assert.equal(result.headers["retry-after"], "12");
  assert.ok(!result.body.includes("xoxb-test-secret"));
});

test("photo-only bot captions remain image placeholders rather than translation input", async () => {
  const token = await pair("Rokid_Test");
  history = [
    {
      ts: "1000.1",
      user: "U_BOT",
      text: "【Rokid_Test】\n(コメントなし)",
      files: [{ id: "F1", mimetype: "image/jpeg" }],
    },
  ];
  const feed = (
    await app.inject({
      url: "/api/slack/feed",
      headers: { authorization: `Bearer ${token}` },
    })
  ).json().posts;
  assert.equal(feed[0].text, "(コメントなし)");
  assert.equal(feed[0].imageCount, 1);
});

test("a new reply is found from channel history alone, re-reading only the thread that changed", async () => {
  const token = await pair("Rokid_Test");
  await photo(token, "request-a");
  await photo(token, "request-b");
  history = [parent("1000.2"), parent("1000.1")];
  thread("1000.1", []);
  thread("1000.2", []);
  // Every feed request asks Slack for history; threads are re-read only on change.
  await restart({ freshMs: 0, resyncMs: 60_000 });

  assert.equal((await feed(token)).statusCode, 200);
  assert.deepEqual(replyCalls.sort(), ["1000.1", "1000.2"]);
  await feed(token);
  await feed(token);
  assert.equal(replyCalls.length, 2, "unchanged threads cost no Slack call");
  assert.equal(historyCalls, 3);

  thread("1000.1", [reply("1000.5", "1000.1", "バルブを閉めてください")]);
  const posts = (await feed(token)).json().posts;
  assert.ok(
    posts.some((p: { text: string }) => p.text === "バルブを閉めてください"),
  );
  assert.deepEqual(replyCalls.slice(2), ["1000.1"]);

  // A deleted reply lowers the count even though no newer reply exists.
  thread("1000.1", []);
  const afterDelete = (await feed(token)).json().posts;
  assert.ok(
    !afterDelete.some(
      (p: { text: string }) => p.text === "バルブを閉めてください",
    ),
  );
  assert.deepEqual(replyCalls.slice(3), ["1000.1"]);
});

test("edited replies and threads outside channel history are still picked up by the periodic re-read", async () => {
  const token = await pair("Rokid_Test");
  await photo(token);
  // The parent has scrolled out of the newest 100 messages: history says nothing about it.
  history = [];
  threads["1000.1"] = [
    parent("1000.1"),
    reply("1000.5", "1000.1", "確認します"),
  ];
  await restart({ freshMs: 0, resyncMs: 40 });
  const text = async () =>
    (await feed(token))
      .json()
      .posts.map((p: { text: string }) => p.text)
      .join("|");

  assert.match(await text(), /確認します/);
  threads["1000.1"] = [
    parent("1000.1"),
    reply("1000.5", "1000.1", "確認しました"),
  ];
  assert.match(await text(), /確認します/, "within the re-read interval");
  await new Promise((done) => setTimeout(done, 60));
  assert.match(await text(), /確認しました/);
});

test("a waiting feed request answers as soon as a reply arrives, not at the next device poll", async () => {
  const token = await pair("Rokid_Test");
  await photo(token);
  history = [parent("1000.1")];
  thread("1000.1", []);
  await restart({ freshMs: 20, resyncMs: 60_000 });
  const first = (await feed(token)).json();
  assert.equal(typeof first.version, "string");

  const started = Date.now();
  const waiting = feed(token, `?since=${first.version}&wait=5`);
  await new Promise((done) => setTimeout(done, 80));
  thread("1000.1", [reply("1000.7", "1000.1", "追加で撮影してください")]);
  const changed = (await waiting).json();

  assert.ok(Date.now() - started < 2_000, "must not wait out the full 5s");
  assert.notEqual(changed.version, first.version);
  assert.ok(
    changed.posts.some(
      (p: { text: string }) => p.text === "追加で撮影してください",
    ),
  );
});

test("a waiting feed request returns the same version at its deadline and never waits without a version", async () => {
  const token = await pair("Rokid_Test");
  await restart({ freshMs: 20, resyncMs: 60_000 });
  const first = (await feed(token)).json();

  let started = Date.now();
  const unchanged = (
    await feed(token, `?since=${first.version}&wait=0.2`)
  ).json();
  assert.ok(Date.now() - started >= 150);
  assert.equal(unchanged.version, first.version);

  // A device that has not shown anything yet, or already holds an old version, is answered at once.
  for (const query of ["?wait=5", "?since=outdated&wait=5"]) {
    started = Date.now();
    assert.equal((await feed(token, query)).statusCode, 200);
    assert.ok(Date.now() - started < 1_000);
  }
});

test("a device revoked while its feed request is waiting receives no further channel content", async () => {
  const token = await pair("Rokid_Test");
  await restart({ freshMs: 20, resyncMs: 60_000 });
  const first = (await feed(token)).json();
  const waiting = feed(token, `?since=${first.version}&wait=5`);
  const page = await app.inject({ url: "/slack" });
  const csrf = /name="csrf" value="([^"]+)"/.exec(page.body)?.[1] ?? "";
  const device = /name="device" value="([^"]+)"/.exec(page.body)?.[1] ?? "";
  await app.inject({
    method: "POST",
    url: "/slack/revoke",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    payload: new URLSearchParams({ csrf, device }).toString(),
  });

  const result = await waiting;
  assert.equal(result.statusCode, 401);
  assert.equal(result.json().posts, undefined);
});

test("no photo bytes leave the PC when the destination check fails", async () => {
  const token = await pair("Rokid_Test");
  member = false;

  assert.equal((await photo(token)).statusCode, 503);
  assert.equal(transfers, 0);
  assert.equal(uploads, 0);

  member = true;
  assert.equal((await photo(token)).statusCode, 200);
  assert.equal(transfers, 1);
});

test("a waiting feed request abandoned by the device stops asking Slack", async () => {
  const token = await pair("Rokid_Test");
  await restart({ freshMs: 20, resyncMs: 60_000 });
  // A real socket: the glasses drop the connection when the screen turns off mid-wait.
  const address = await app.listen({ host: "127.0.0.1", port: 0 });
  const headers = { authorization: `Bearer ${token}` };
  const pause = (ms: number) => new Promise((done) => setTimeout(done, ms));
  const first = (await (
    await fetch(`${address}/api/slack/feed`, { headers })
  ).json()) as { version: string };

  const abandoned = new AbortController();
  const waiting = fetch(
    `${address}/api/slack/feed?since=${first.version}&wait=5`,
    { headers, signal: abandoned.signal },
  ).catch(() => undefined);
  await pause(100);
  assert.ok(historyCalls > 2, "Slack is checked while the device waits");
  abandoned.abort();
  await waiting;
  await pause(100);

  const settled = historyCalls;
  await pause(200);
  assert.equal(historyCalls, settled);
});

test("a thread is not re-read on every check when history and the replies API describe its parent differently", async () => {
  const token = await pair("Rokid_Test");
  await photo(token);
  const answer = reply("1000.5", "1000.1", "確認します");
  // History carries no thread summary for this parent; the replies API does.
  history = [parent("1000.1")];
  threads["1000.1"] = [parent("1000.1", [answer]), answer];
  await restart({ freshMs: 0, resyncMs: 60_000 });

  for (let i = 0; i < 4; i++) assert.equal((await feed(token)).statusCode, 200);

  // Otherwise 20 watched threads would be read 20 times a minute each and hit the rate limit.
  assert.deepEqual(replyCalls, ["1000.1"]);
});
