import assert from "node:assert/strict";
import {
  mkdir,
  mkdtemp,
  readdir,
  readFile,
  rm,
  writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, test } from "node:test";

import type { FastifyInstance } from "fastify";
import type { DemoLanguage, Entry, LocalReply } from "shared";

import { createApp } from "../src/app.ts";
import {
  createLanguageService,
  type LanguageService,
} from "../src/language.ts";

let app: FastifyInstance;
let rootDir: string;
let uploadDir: string;
let webDist: string;
let tokenPath: string;
let authToken: string;

beforeEach(async () => {
  rootDir = await mkdtemp(join(tmpdir(), "scraft-api-test-"));
  uploadDir = join(rootDir, "uploads");
  webDist = join(rootDir, "web-dist");
  tokenPath = join(rootDir, "teams-token.json");
  await mkdir(webDist);
  await writeFile(join(webDist, "index.html"), "<p>monitor</p>", "utf8");
  app = await createApp({
    uploadDir,
    webDist,
    port: 9999,
    teams: teamsConfig(),
  });
  authToken = (await app.inject({ method: "GET", url: "/api/token" })).json<{
    token: string;
  }>().token;
});

afterEach(async () => {
  await app.close();
  await rm(rootDir, { recursive: true, force: true });
});

test("health returns the configured port", async () => {
  const response = await app.inject({ method: "GET", url: "/api/health" });

  assert.equal(response.statusCode, 200);
  assert.equal(response.json().ok, true);
  assert.equal(response.json().port, 9999);
  assert.equal(typeof response.json().ip, "string");
});

test("four-language uploads receive Google-translated replies in their device language", async () => {
  await app.close();
  const targets: string[] = [];
  app = await createApp({
    uploadDir,
    webDist,
    port: 9999,
    language: createLanguageService({
      apiKey: "dummy",
      fetcher: async (_url, init) => {
        const request = JSON.parse(String(init?.body));
        assert.equal(request.source, undefined);
        targets.push(request.target);
        return Response.json({
          data: {
            translations: [{ translatedText: `translated-${request.target}` }],
          },
        });
      },
    }),
  });
  const token = (await app.inject({ method: "GET", url: "/api/token" })).json<{
    token: string;
  }>().token;
  for (const language of ["ja", "zh-CN", "en", "ko"] as DemoLanguage[]) {
    const upload = await app.inject({
      method: "POST",
      url: "/upload",
      headers: {
        "content-type": "image/jpeg",
        "x-auth-token": token,
        "x-device": "test-device",
        "x-language": language,
        "x-skip-analysis": "true",
      },
      payload: Buffer.from([1, 2]),
    });
    assert.equal(upload.statusCode, 200);
    const entry = upload.json<Entry>();
    assert.equal(entry.language, language);
    const reply = await app.inject({
      method: "POST",
      url: "/api/replies",
      payload: { entryId: entry.id, text: "请检查照片" },
    });
    assert.equal(reply.statusCode, 200);
    assert.equal(reply.json<LocalReply>().language, language);
    assert.equal(
      reply.json<LocalReply>().translatedText,
      `translated-${language}`,
    );
  }
  assert.deepEqual(targets, ["ja", "zh-CN", "en", "ko"]);
});

test("JPEG upload stores the original bytes and decoded metadata", async () => {
  const bytes = Buffer.from([1, 2, 3, 4]);
  const response = await app.inject({
    method: "POST",
    url: "/upload",
    headers: {
      "x-auth-token": authToken,
      "content-type": "image/jpeg",
      "x-comment": encodeURIComponent("配管 異常"),
      "x-device": "rokid-test",
    },
    payload: bytes,
  });

  assert.equal(response.statusCode, 200);
  const entry = response.json<Entry>();
  assert.match(entry.id, /^\d{8}-\d{6}-[0-9a-f]{4}$/);
  assert.equal(entry.kind, "photo");
  assert.equal(entry.comment, "配管 異常");
  assert.equal(entry.device, "rokid-test");
  assert.ok(entry.file?.endsWith(".jpg"));
  if (!entry.file) assert.fail("uploaded entry should have a file");
  assert.deepEqual(await readFile(join(uploadDir, entry.file)), bytes);

  const metadata = JSON.parse(
    await readFile(join(uploadDir, `${entry.id}.json`), "utf8"),
  ) as Entry;
  assert.equal(metadata.id, entry.id);
});

test("MP4 upload is stored as video", async () => {
  const response = await app.inject({
    method: "POST",
    url: "/api/upload",
    headers: {
      "x-auth-token": authToken,
      "content-type": "video/mp4",
    },
    payload: Buffer.from([9, 8, 7, 6]),
  });

  assert.equal(response.statusCode, 200);
  const entry = response.json<Entry>();
  assert.equal(entry.kind, "video");
  assert.ok(entry.file?.endsWith(".mp4"));
});

test("multipart upload reads the media type and comment field", async () => {
  const boundary = "scraft-test-boundary";
  const bytes = Buffer.from([0, 1, 2, 3]);
  const payload = Buffer.concat([
    Buffer.from(
      `--${boundary}\r\nContent-Disposition: form-data; name="comment"\r\n\r\n現場 動画\r\n`,
    ),
    Buffer.from(
      `--${boundary}\r\nContent-Disposition: form-data; name="video"; filename="report.mp4"\r\nContent-Type: video/mp4\r\n\r\n`,
    ),
    bytes,
    Buffer.from(`\r\n--${boundary}--\r\n`),
  ]);
  const response = await app.inject({
    method: "POST",
    url: "/upload",
    headers: {
      "x-auth-token": authToken,
      "content-type": `multipart/form-data; boundary=${boundary}`,
      "x-device": "rokid-multipart",
    },
    payload,
  });

  assert.equal(response.statusCode, 200);
  const entry = response.json<Entry>();
  assert.equal(entry.kind, "video");
  assert.equal(entry.comment, "現場 動画");
  assert.equal(entry.device, "rokid-multipart");
  if (!entry.file) assert.fail("uploaded entry should have a file");
  assert.deepEqual(await readFile(join(uploadDir, entry.file)), bytes);
});

test("local demo stores voice translation and returns a translated PC reply without Teams", async () => {
  await app.close();
  const calls: string[] = [];
  const language: LanguageService = {
    configured: true,
    async analyzeReport({ audio, audioMime, language }) {
      calls.push(`report:${audioMime}:${audio?.toString("hex")}:${language}`);
      return {
        transcript: "Er lekt water uit de leiding",
        japanese: "配管から水が漏れています",
        language,
      };
    },
    async translateJapanese(text, target) {
      calls.push(`reply:${text}:${target}`);
      return "Ik kom eraan";
    },
    async translateSpeech() {
      throw new Error("not used");
    },
  };
  app = await createApp({
    uploadDir,
    webDist,
    port: 9999,
    teams: teamsConfig(),
    language,
  });
  authToken = (await app.inject({ method: "GET", url: "/api/token" })).json<{
    token: string;
  }>().token;

  const boundary = "local-demo-boundary";
  const payload = Buffer.from(
    `--${boundary}\r\nContent-Disposition: form-data; name="language"\r\n\r\nko\r\n` +
      `--${boundary}\r\nContent-Disposition: form-data; name="media"; filename="capture.jpg"\r\nContent-Type: image/jpeg\r\n\r\nIMG\r\n` +
      `--${boundary}\r\nContent-Disposition: form-data; name="audio"; filename="voice.wav"\r\nContent-Type: audio/wav\r\n\r\nWAV\r\n` +
      `--${boundary}--\r\n`,
  );
  const upload = await app.inject({
    method: "POST",
    url: "/upload",
    headers: {
      "x-auth-token": authToken,
      "x-device": "android-phone",
      "content-type": `multipart/form-data; boundary=${boundary}`,
    },
    payload,
  });
  assert.equal(upload.statusCode, 200);
  const entry = upload.json<Entry>();
  assert.equal(entry.comment, "Er lekt water uit de leiding");
  assert.equal(entry.translatedComment, "配管から水が漏れています");
  assert.equal(entry.language, "ko");
  assert.ok(entry.audioFile?.endsWith(".wav"));

  const response = await app.inject({
    method: "POST",
    url: "/api/replies",
    headers: { "content-type": "application/json" },
    payload: { entryId: entry.id, text: "今向かいます" },
  });
  assert.equal(response.statusCode, 200);
  const localReply = response.json<LocalReply>();
  assert.equal(localReply.device, "android-phone");
  assert.equal(localReply.translatedText, "Ik kom eraan");
  assert.equal(localReply.language, "ko");
  const queued = await app.inject({
    method: "GET",
    url: "/api/replies?device=android-phone",
    headers: { "x-auth-token": authToken },
  });
  assert.deepEqual(queued.json<LocalReply[]>(), [localReply]);
  assert.deepEqual(calls, [
    "report:audio/wav:574156:ko",
    "reply:今向かいます:ko",
  ]);
});

test("captured comment can be saved without server-side transcription or translation", async () => {
  await app.close();
  const language: LanguageService = {
    configured: true,
    async analyzeReport() {
      throw new Error("analysis must be skipped");
    },
    async translateJapanese() {
      throw new Error("not used");
    },
    async translateSpeech() {
      throw new Error("not used");
    },
  };
  app = await createApp({
    uploadDir,
    webDist,
    port: 9999,
    teams: teamsConfig(),
    language,
  });
  authToken = (await app.inject({ method: "GET", url: "/api/token" })).json<{
    token: string;
  }>().token;

  const response = await app.inject({
    method: "POST",
    url: "/upload",
    headers: {
      "x-auth-token": authToken,
      "x-device": "rokid-test",
      "x-comment": encodeURIComponent("配管を確認しました"),
      "x-skip-analysis": "true",
      "content-type": "image/jpeg",
    },
    payload: Buffer.from("photo"),
  });

  assert.equal(response.statusCode, 200);
  const entry = response.json<Entry>();
  assert.equal(entry.comment, "配管を確認しました");
  assert.equal(entry.translatedComment, "配管を確認しました");
});

test("unsupported and empty uploads are rejected", async () => {
  const unsupported = await app.inject({
    method: "POST",
    url: "/upload",
    headers: {
      "x-auth-token": authToken,
      "content-type": "text/plain",
    },
    payload: "not media",
  });
  const empty = await app.inject({
    method: "POST",
    url: "/upload",
    headers: {
      "x-auth-token": authToken,
      "content-type": "image/jpeg",
    },
    payload: Buffer.alloc(0),
  });

  assert.equal(unsupported.statusCode, 415);
  assert.equal(empty.statusCode, 400);
});

test("list returns saved entries and ignores corrupt metadata", async () => {
  const uploaded = await uploadPhoto();
  await writeFile(join(uploadDir, "corrupt.json"), "{not-json", "utf8");

  const response = await app.inject({ method: "GET", url: "/api/list" });

  assert.equal(response.statusCode, 200);
  assert.deepEqual(
    response.json<Entry[]>().map((entry) => entry.id),
    [uploaded.id],
  );
});

test("delete is logical: the entry moves to the trash", async () => {
  const uploaded = await uploadPhoto();

  const response = await app.inject({
    method: "DELETE",
    url: `/api/entries/${uploaded.id}`,
  });

  assert.equal(response.statusCode, 200);
  assert.deepEqual(await readdir(uploadDir), []);
});

test("delete validates IDs and reports missing entries", async () => {
  const invalid = await app.inject({
    method: "DELETE",
    url: "/api/entries/not-an-id",
  });
  const missing = await app.inject({
    method: "DELETE",
    url: "/api/entries/20260814-161512-abcd",
  });

  assert.equal(invalid.statusCode, 400);
  assert.equal(missing.statusCode, 404);
});

test("clear moves every entry to the trash", async () => {
  await uploadPhoto();
  await app.inject({
    method: "POST",
    url: "/upload",
    headers: {
      "x-auth-token": authToken,
      "content-type": "video/mp4",
    },
    payload: Buffer.from([5]),
  });

  const response = await app.inject({ method: "DELETE", url: "/api/entries" });

  assert.equal(response.statusCode, 200);
  // removed は「件数」。ファイル数（メタ＋本体で2倍）ではない。
  assert.equal(response.json().removed, 2);
  assert.deepEqual(await readdir(uploadDir), []);
  assert.equal(
    (await app.inject({ method: "GET", url: "/api/trash" })).json().length,
    2,
  );
});

test("a trashed entry can be restored with its media", async () => {
  const uploaded = await uploadPhoto();
  if (!uploaded.file) assert.fail("uploaded entry should have a file");

  await app.inject({ method: "DELETE", url: `/api/entries/${uploaded.id}` });
  assert.deepEqual(await readdir(uploadDir), []);

  const trash = await app.inject({ method: "GET", url: "/api/trash" });
  assert.deepEqual(
    trash.json<Entry[]>().map((e) => e.id),
    [uploaded.id],
  );

  const restored = await app.inject({
    method: "POST",
    url: `/api/trash/${uploaded.id}/restore`,
  });

  assert.equal(restored.statusCode, 200);
  assert.equal(
    (await app.inject({ method: "GET", url: "/api/list" })).json().length,
    1,
  );
  assert.equal(
    (await app.inject({ method: "GET", url: "/api/trash" })).json().length,
    0,
  );
  // 本体も戻っていること
  assert.ok((await readdir(uploadDir)).includes(uploaded.file));
});

test("emptying the trash is the only destructive operation", async () => {
  const uploaded = await uploadPhoto();
  await app.inject({ method: "DELETE", url: `/api/entries/${uploaded.id}` });

  const purged = await app.inject({ method: "DELETE", url: "/api/trash" });

  assert.equal(purged.statusCode, 200);
  assert.equal(purged.json().purged, 1);
  // 消えた後は復元できない
  const restore = await app.inject({
    method: "POST",
    url: `/api/trash/${uploaded.id}/restore`,
  });
  assert.equal(restore.statusCode, 404);
});

test("restore validates IDs", async () => {
  const invalid = await app.inject({
    method: "POST",
    url: "/api/trash/not-an-id/restore",
  });
  assert.equal(invalid.statusCode, 400);
});

test("stored video supports byte range responses", async () => {
  const bytes = Buffer.from([9, 8, 7, 6]);
  const upload = await app.inject({
    method: "POST",
    url: "/upload",
    headers: {
      "x-auth-token": authToken,
      "content-type": "video/mp4",
    },
    payload: bytes,
  });
  const entry = upload.json<Entry>();
  if (!entry.file) assert.fail("uploaded entry should have a file");

  const response = await app.inject({
    method: "GET",
    url: `/uploads/${entry.file}`,
    headers: { range: "bytes=1-2" },
  });

  assert.equal(response.statusCode, 206);
  assert.match(String(response.headers["content-type"]), /^video\/mp4/);
  assert.deepEqual(response.rawPayload, bytes.subarray(1, 3));
});

test("Teams endpoints report an unconfigured server", async () => {
  const status = await app.inject({
    method: "GET",
    url: "/api/teams/status",
  });
  const signIn = await app.inject({
    method: "POST",
    url: "/api/teams/signin",
  });

  assert.equal(status.statusCode, 200);
  assert.deepEqual(status.json(), {
    signedIn: false,
    expiresAt: null,
    channelUrl: "",
    configured: false,
  });
  assert.equal(signIn.statusCode, 400);
});

test("Teams post validates IDs, missing entries, and video entries", async () => {
  const invalid = await app.inject({
    method: "POST",
    url: "/api/teams/post/not-an-id",
  });
  const missing = await app.inject({
    method: "POST",
    url: "/api/teams/post/20260814-161512-abcd",
  });
  const upload = await app.inject({
    method: "POST",
    url: "/upload",
    headers: {
      "x-auth-token": authToken,
      "content-type": "video/mp4",
    },
    payload: Buffer.from([5]),
  });
  const video = upload.json<Entry>();
  const unsupported = await app.inject({
    method: "POST",
    url: `/api/teams/post/${video.id}`,
  });

  assert.equal(invalid.statusCode, 400);
  assert.equal(missing.statusCode, 404);
  assert.equal(unsupported.statusCode, 400);
  assert.match(unsupported.json().error, /動画/);
});

test("Teams post sends a stored photo and persists the result", async () => {
  await app.close();
  await writeFile(
    tokenPath,
    JSON.stringify({
      access_token: "access-token",
      expires_at: Date.now() + 3_600_000,
    }),
    "utf8",
  );
  app = await createApp({
    uploadDir,
    webDist,
    port: 9999,
    teams: teamsConfig({
      tenantId: "tenant-1",
      clientId: "client-1",
      teamId: "team-1",
      channelId: "channel-1",
    }),
  });
  const uploaded = await uploadPhoto("現場コメント");
  const originalFetch = globalThis.fetch;
  let graphUrl = "";
  let graphInit: RequestInit | undefined;
  globalThis.fetch = async (input, init) => {
    graphUrl = String(input);
    graphInit = init;
    return new Response(
      JSON.stringify({ id: "message-1", webUrl: "https://teams/message-1" }),
      { status: 201, headers: { "content-type": "application/json" } },
    );
  };

  try {
    const response = await app.inject({
      method: "POST",
      url: `/api/teams/post/${uploaded.id}`,
    });

    assert.equal(response.statusCode, 200);
    assert.match(graphUrl, /teams\/team-1\/channels\/channel-1\/messages$/);
    assert.equal(
      new Headers(graphInit?.headers).get("authorization"),
      "Bearer access-token",
    );
    const payload = JSON.parse(String(graphInit?.body));
    assert.match(payload.body.content, /現場コメント/);
    assert.equal(payload.hostedContents[0].contentBytes, "AQ==");

    const list = await app.inject({ method: "GET", url: "/api/list" });
    const stored = list.json<Entry[]>()[0];
    assert.equal(stored?.teams?.messageId, "message-1");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

async function uploadPhoto(comment = ""): Promise<Entry> {
  const response = await app.inject({
    method: "POST",
    url: "/upload",
    headers: {
      "content-type": "image/jpeg",
      "x-comment": encodeURIComponent(comment),
      "x-auth-token": authToken,
    },
    payload: Buffer.from([1]),
  });
  assert.equal(response.statusCode, 200);
  return response.json<Entry>();
}

function teamsConfig(
  values: Partial<{
    tenantId: string;
    clientId: string;
    teamId: string;
    channelId: string;
  }> = {},
) {
  return {
    tenantId: "",
    clientId: "",
    teamId: "",
    channelId: "",
    tokenPath,
    ...values,
  };
}

test("Teams post escapes HTML in the comment", async () => {
  await app.close();
  await writeFile(
    tokenPath,
    JSON.stringify({
      access_token: "access-token",
      expires_at: Date.now() + 3_600_000,
    }),
    "utf8",
  );
  app = await createApp({
    uploadDir,
    webDist,
    port: 9999,
    teams: teamsConfig({
      tenantId: "t",
      clientId: "c",
      teamId: "team-1",
      channelId: "channel-1",
    }),
  });
  const uploaded = await uploadPhoto('<script>alert("x")</script> & 現場');
  const originalFetch = globalThis.fetch;
  let sent = "";
  globalThis.fetch = async (_input, init) => {
    sent = String(init?.body ?? "");
    return new Response(
      JSON.stringify({ id: "m1", webUrl: "https://teams/m1" }),
      {
        status: 201,
        headers: { "content-type": "application/json" },
      },
    );
  };
  try {
    const response = await app.inject({
      method: "POST",
      url: `/api/teams/post/${uploaded.id}`,
    });
    assert.equal(response.statusCode, 200);
    // 生タグが本文に残っていないこと（残ると Teams の本文が壊れる）
    assert.ok(!sent.includes("<script>"));
    assert.ok(sent.includes("&lt;script&gt;"));
    assert.ok(sent.includes("&amp; 現場"));
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("upload accepts a malformed percent escape in X-Comment", async () => {
  const response = await app.inject({
    method: "POST",
    url: "/api/upload",
    headers: {
      "x-auth-token": authToken,
      "content-type": "image/jpeg",
      "x-filename": "broken.jpg",
      // 不正な % 単独。decodeURIComponent は例外を投げる ＝ 500 になってはいけない
      "x-comment": "100%OFF",
    },
    payload: Buffer.from([0xff, 0xd8, 0xff, 0xd9]),
  });
  assert.equal(response.statusCode, 200);
  assert.equal(response.json().comment, "100%OFF");
});

test("Teams status reports the refreshed expiry, not the stale one", async () => {
  await app.close();
  const stale = Date.now() + 10_000; // 期限切れ間近 → accessToken() が更新する
  await writeFile(
    tokenPath,
    JSON.stringify({
      access_token: "old",
      refresh_token: "r1",
      expires_at: stale,
    }),
    "utf8",
  );
  app = await createApp({
    uploadDir,
    webDist,
    port: 9999,
    teams: teamsConfig({
      tenantId: "t",
      clientId: "c",
      teamId: "team-1",
      channelId: "channel-1",
    }),
  });
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () =>
    new Response(
      JSON.stringify({
        access_token: "new",
        refresh_token: "r2",
        expires_in: 3600,
      }),
      { status: 200, headers: { "content-type": "application/json" } },
    );
  try {
    const response = await app.inject({
      method: "GET",
      url: "/api/teams/status",
    });
    assert.equal(response.statusCode, 200);
    const expiresAt = Date.parse(response.json().expiresAt);
    assert.ok(
      expiresAt > stale + 60_000,
      `更新後の期限を返すこと: ${response.json().expiresAt}`,
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("Teams channel endpoint sends text to the configured channel", async () => {
  await app.close();
  await writeFile(
    tokenPath,
    JSON.stringify({
      access_token: "channel-access-token",
      expires_at: Date.now() + 3_600_000,
    }),
    "utf8",
  );
  app = await createApp({
    uploadDir,
    webDist,
    port: 9999,
    teams: teamsConfig({
      tenantId: "t",
      clientId: "c",
      teamId: "team",
      channelId: "channel",
    }),
  });
  const originalFetch = globalThis.fetch;
  let graphUrl = "";
  let graphBody = "";
  globalThis.fetch = async (input, init) => {
    graphUrl = String(input);
    graphBody = String(init?.body);
    return Response.json(
      {
        id: "sent-1",
        from: { user: { displayName: "Web" } },
        body: { content: "<div>安全確認をお願いします</div>" },
      },
      { status: 201 },
    );
  };
  try {
    const response = await app.inject({
      method: "POST",
      url: "/api/teams/channel/messages",
      payload: { text: "安全確認をお願いします" },
    });
    assert.equal(response.statusCode, 200);
    assert.match(graphUrl, /teams\/team\/channels\/channel\/messages$/);
    assert.match(graphBody, /安全確認をお願いします/);
    assert.equal(response.json().id, "sent-1");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("upload without the token is rejected", async () => {
  const response = await app.inject({
    method: "POST",
    url: "/upload",
    headers: { "content-type": "image/jpeg" },
    payload: Buffer.from([1]),
  });
  assert.equal(response.statusCode, 401);
  // 拒否したものは保存もしない
  assert.equal(
    (await app.inject({ method: "GET", url: "/api/list" })).json().length,
    0,
  );
});

test("upload with a wrong token is rejected", async () => {
  const response = await app.inject({
    method: "POST",
    url: "/upload",
    headers: { "content-type": "image/jpeg", "x-auth-token": "not-the-token" },
    payload: Buffer.from([1]),
  });
  assert.equal(response.statusCode, 401);
});

test("the token endpoint is refused from outside the PC", async () => {
  const outside = await app.inject({
    method: "GET",
    url: "/api/token",
    remoteAddress: "192.168.1.50",
  });
  assert.equal(outside.statusCode, 403);
});

test("rotating the token invalidates the previous one", async () => {
  const rotated = (
    await app.inject({ method: "POST", url: "/api/token/rotate" })
  ).json<{ token: string }>().token;
  assert.notEqual(rotated, authToken);

  const withOld = await app.inject({
    method: "POST",
    url: "/upload",
    headers: { "content-type": "image/jpeg", "x-auth-token": authToken },
    payload: Buffer.from([1]),
  });
  assert.equal(withOld.statusCode, 401);

  const withNew = await app.inject({
    method: "POST",
    url: "/upload",
    headers: { "content-type": "image/jpeg", "x-auth-token": rotated },
    payload: Buffer.from([1]),
  });
  assert.equal(withNew.statusCode, 200);
});

test("deleting from another device on the LAN is refused", async () => {
  const uploaded = await uploadPhoto();
  const response = await app.inject({
    method: "DELETE",
    url: `/api/entries/${uploaded.id}`,
    remoteAddress: "192.168.1.50",
  });
  assert.equal(response.statusCode, 403);
  // 消えていないこと
  assert.equal(
    (await app.inject({ method: "GET", url: "/api/list" })).json().length,
    1,
  );
});

test("the glasses can delete with the token even from the LAN", async () => {
  const uploaded = await uploadPhoto();
  const response = await app.inject({
    method: "DELETE",
    url: `/api/entries/${uploaded.id}`,
    remoteAddress: "192.168.1.50",
    headers: { "x-auth-token": authToken },
  });
  assert.equal(response.statusCode, 200);
});

test("viewing the list stays open to the whole LAN", async () => {
  // モニタは会場のタブレットからも開ける。見るのは誰でも、消すのは持ち主だけ。
  const response = await app.inject({
    method: "GET",
    url: "/api/list",
    remoteAddress: "192.168.1.50",
  });
  assert.equal(response.statusCode, 200);
});
