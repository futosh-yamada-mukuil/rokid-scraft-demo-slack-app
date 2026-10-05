import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, test } from "node:test";

import { createTeams, type TeamsConfig } from "../src/teams.ts";

let rootDir: string;
let tokenPath: string;
let originalFetch: typeof globalThis.fetch;

beforeEach(async () => {
  rootDir = await mkdtemp(join(tmpdir(), "scraft-teams-test-"));
  tokenPath = join(rootDir, "token.json");
  originalFetch = globalThis.fetch;
});

afterEach(async () => {
  globalThis.fetch = originalFetch;
  await rm(rootDir, { recursive: true, force: true });
});

test("configured requires every Microsoft identifier", () => {
  assert.equal(createTeams(config()).configured, true);
  assert.equal(createTeams(config({ channelId: "" })).configured, false);
});

test("status uses a fresh saved access token without a network request", async () => {
  const expiresAt = Date.now() + 3_600_000;
  await writeFile(
    tokenPath,
    JSON.stringify({ access_token: "fresh-token", expires_at: expiresAt }),
    "utf8",
  );
  globalThis.fetch = async () => {
    assert.fail("fresh token must not make a network request");
  };

  const status = await createTeams(
    config({ channelId: "channel/with spaces" }),
  ).status();

  assert.equal(status.signedIn, true);
  assert.equal(status.expiresAt, new Date(expiresAt).toISOString());
  assert.match(status.channelUrl, /channel%2Fwith%20spaces/);
});

test("an expired token is refreshed and saved", async () => {
  await writeFile(
    tokenPath,
    JSON.stringify({
      access_token: "expired-token",
      refresh_token: "refresh-token",
      expires_at: Date.now() - 1,
    }),
    "utf8",
  );
  let requestBody = "";
  globalThis.fetch = async (_input, init) => {
    requestBody = String(init?.body);
    return Response.json({
      access_token: "renewed-token",
      refresh_token: "next-refresh-token",
      expires_in: 7200,
    });
  };

  const status = await createTeams(config()).status();
  const saved = JSON.parse(await readFile(tokenPath, "utf8"));

  assert.equal(status.signedIn, true);
  assert.match(requestBody, /grant_type=refresh_token/);
  assert.match(requestBody, /refresh_token=refresh-token/);
  assert.equal(saved.access_token, "renewed-token");
  assert.equal(saved.refresh_token, "next-refresh-token");
});

test("post embeds an image and uses the bearer token", async () => {
  await writeFile(
    tokenPath,
    JSON.stringify({
      access_token: "post-token",
      expires_at: Date.now() + 3_600_000,
    }),
    "utf8",
  );
  let requestUrl = "";
  let requestInit: RequestInit | undefined;
  globalThis.fetch = async (input, init) => {
    requestUrl = String(input);
    requestInit = init;
    return Response.json(
      { id: "message-42", webUrl: "https://teams/message-42" },
      { status: 201 },
    );
  };

  const posted = await createTeams(
    config({ channelId: "channel/with spaces" }),
  ).post({
    comment: "配管を確認",
    blob: Buffer.from([1, 2, 3]),
    mime: "image/jpeg",
  });

  assert.match(
    requestUrl,
    /teams\/team-1\/channels\/channel%2Fwith%20spaces\/messages$/,
  );
  assert.equal(
    new Headers(requestInit?.headers).get("authorization"),
    "Bearer post-token",
  );
  const payload = JSON.parse(String(requestInit?.body));
  assert.match(payload.body.content, /配管を確認/);
  assert.equal(payload.hostedContents[0].contentBytes, "AQID");
  assert.equal(payload.hostedContents[0].contentType, "image/jpeg");
  assert.equal(posted.messageId, "message-42");
});

test("post fails before Graph access when no token is saved", async () => {
  globalThis.fetch = async () => {
    assert.fail("missing token must not make a network request");
  };

  await assert.rejects(
    createTeams(config()).post({ comment: "コメント" }),
    /サインインしていません/,
  );
});

test("blank comments send only image HTML without a placeholder or empty div", async () => {
  await writeFile(
    tokenPath,
    JSON.stringify({
      access_token: "post-token",
      expires_at: Date.now() + 3_600_000,
    }),
    "utf8",
  );
  const payloads: {
    body: { content: string };
    hostedContents: { contentBytes: string }[];
  }[] = [];
  globalThis.fetch = async (_input, init) => {
    payloads.push(JSON.parse(String(init?.body)));
    return Response.json({ id: "image-only" }, { status: 201 });
  };

  for (const comment of ["", " \n\t "]) {
    await createTeams(config()).post({ comment, blob: Buffer.from([9]) });
  }

  assert.equal(payloads.length, 2);
  for (const payload of payloads) {
    assert.equal(
      payload.body.content,
      '<div><img src="../hostedContents/1/$value" style="max-width:600px" alt="添付画像"></img></div>',
    );
    assert.equal(payload.hostedContents[0].contentBytes, "CQ==");
  }
});

test("empty posts fail locally before authentication or Graph requests", async () => {
  globalThis.fetch = async () => {
    assert.fail("empty posts must not authenticate or call Graph");
  };
  for (const blob of [undefined, Buffer.alloc(0)]) {
    for (const comment of ["", " \n\t "]) {
      await assert.rejects(
        createTeams(config()).post({ comment, blob }),
        /送信する本文または画像がありません/,
      );
    }
  }
});

test("post trims and escapes text and omits empty image data", async () => {
  await writeFile(
    tokenPath,
    JSON.stringify({
      access_token: "post-token",
      expires_at: Date.now() + 3_600_000,
    }),
    "utf8",
  );
  let requestBody = "";
  globalThis.fetch = async (_input, init) => {
    requestBody = String(init?.body);
    return Response.json({ id: "text-only" }, { status: 201 });
  };

  await createTeams(config()).post({
    comment: `  <確認> & "引用" '文字'  `,
    blob: Buffer.alloc(0),
  });

  const payload = JSON.parse(requestBody);
  assert.equal(
    payload.body.content,
    "<div>&lt;確認&gt; &amp; &quot;引用&quot; &#39;文字&#39;</div>",
  );
  assert.equal("hostedContents" in payload, false);
});

test("channel messages are read as plain text from the configured channel", async () => {
  await writeFile(
    tokenPath,
    JSON.stringify({
      access_token: "chat-token",
      expires_at: Date.now() + 3_600_000,
    }),
    "utf8",
  );
  let requestUrl = "";
  globalThis.fetch = async (input, init) => {
    requestUrl = String(input);
    assert.equal(
      new Headers(init?.headers).get("authorization"),
      "Bearer chat-token",
    );
    return Response.json({
      value: [
        {
          id: "message-1",
          createdDateTime: "2026-09-04T01:02:03Z",
          from: { user: { displayName: "田中" } },
          body: { content: "<div>確認&amp;連絡<br>お願いします</div>" },
        },
      ],
    });
  };

  const messages = await createTeams(
    config({ channelId: "19:channel/with spaces@thread.tacv2" }),
  ).listChannelMessages();

  assert.match(
    requestUrl,
    /teams\/team-1\/channels\/19%3Achannel%2Fwith%20spaces%40thread.tacv2\/messages/,
  );
  assert.deepEqual(messages, [
    {
      id: "message-1",
      author: "田中",
      text: "確認&連絡\nお願いします",
      createdAt: "2026-09-04T01:02:03Z",
    },
  ]);
});

test("channel send escapes HTML and targets the configured channel", async () => {
  await writeFile(
    tokenPath,
    JSON.stringify({
      access_token: "chat-token",
      expires_at: Date.now() + 3_600_000,
    }),
    "utf8",
  );
  let requestUrl = "";
  let requestBody = "";
  globalThis.fetch = async (input, init) => {
    requestUrl = String(input);
    requestBody = String(init?.body);
    return Response.json(
      {
        id: "sent-1",
        createdDateTime: "2026-09-04T01:02:03Z",
        from: { user: { displayName: "送信者" } },
        body: { content: "<div>&lt;確認&gt; &amp; 連絡</div>" },
      },
      { status: 201 },
    );
  };

  const sent = await createTeams(
    config({ channelId: "configured-channel" }),
  ).sendChannelMessage("<確認> & 連絡");

  assert.match(
    requestUrl,
    /teams\/team-1\/channels\/configured-channel\/messages$/,
  );
  assert.ok(!requestBody.includes("<確認>"));
  assert.match(requestBody, /&lt;確認&gt; &amp; 連絡/);
  assert.equal(sent.text, "<確認> & 連絡");
});

test("channel UI requires the configured team and channel", async () => {
  await writeFile(
    tokenPath,
    JSON.stringify({
      access_token: "channel-token",
      expires_at: Date.now() + 3_600_000,
    }),
    "utf8",
  );
  globalThis.fetch = async () => {
    assert.fail("missing chat ID must not access Graph");
  };
  const teams = createTeams(config({ channelId: "" }));

  await assert.rejects(
    teams.listChannelMessages(),
    /投稿先チャネルが設定されていません/,
  );
  await assert.rejects(
    teams.sendChannelMessage("確認しました"),
    /投稿先チャネルが設定されていません/,
  );
});

test("device-code errors are exposed through progress", async () => {
  globalThis.fetch = async () =>
    Response.json({
      error: "invalid_client",
      error_description: "client is invalid",
    });
  const teams = createTeams(config());

  await assert.rejects(teams.startSignIn(), /client is invalid/);
  assert.deepEqual(teams.signInProgress(), {
    state: "error",
    message: "client is invalid",
  });
});

test("expanded and paged thread replies are read, deduplicated and sorted with parent posts", async () => {
  await writeFile(
    tokenPath,
    JSON.stringify({
      access_token: "thread-token",
      expires_at: Date.now() + 3_600_000,
    }),
  );
  const base =
    "https://graph.microsoft.com/v1.0/teams/team-1/channels/channel-1/messages";
  const requests: string[] = [];
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    requests.push(url);
    assert.equal(
      new Headers(init?.headers).get("authorization"),
      "Bearer thread-token",
    );
    if (requests.length === 1) {
      assert.equal(url, `${base}?$top=5&$expand=replies`);
      return Response.json({
        value: [
          {
            id: "parent",
            messageType: "message",
            createdDateTime: "2026-09-01T10:00:00Z",
            body: { content: "親投稿" },
            replies: [
              {
                id: "one",
                createdDateTime: "2026-09-01T10:01:00Z",
                body: { content: '<img src="photo">' },
              },
            ],
            "replies@odata.nextLink": `${base}/parent/replies?$skiptoken=next`,
          },
        ],
      });
    }
    assert.equal(url, `${base}/parent/replies?$skiptoken=next`);
    return Response.json({
      value: [
        {
          id: "one",
          createdDateTime: "2026-09-01T10:01:00Z",
          body: { content: '<img src="photo">' },
        },
        {
          id: "two",
          createdDateTime: "2026-09-01T10:02:00Z",
          body: { content: "<div>新しい返信</div>" },
        },
        { id: "deleted", deletedDateTime: "2026-09-01T11:00:00Z" },
        { id: "event", messageType: "systemEventMessage" },
      ],
    });
  };
  const messages = await createTeams(config()).listChannelMessages();
  assert.deepEqual(
    messages.map((message) => message.id),
    ["reply:parent:two", "reply:parent:one", "parent"],
  );
  assert.equal(messages[1].text, "[画像]");
  assert.equal(requests.length, 2);
});

test("thread retrieval keeps five parents and the five latest replies with thread-specific IDs", async () => {
  await writeFile(
    tokenPath,
    JSON.stringify({
      access_token: "thread-token",
      expires_at: Date.now() + 3_600_000,
    }),
  );
  globalThis.fetch = async () =>
    Response.json({
      value: Array.from({ length: 5 }, (_, parent) => ({
        id: `parent-${parent}`,
        createdDateTime: "2026-09-01T10:00:00Z",
        replies: Array.from({ length: 3 }, (_, reply) => ({
          id: String(reply),
          createdDateTime: `2026-09-01T10:${String(parent * 3 + reply + 1).padStart(2, "0")}:00Z`,
        })),
      })),
    });
  const messages = await createTeams(config()).listChannelMessages();
  assert.equal(
    messages.filter((message) => !message.id.startsWith("reply:")).length,
    5,
  );
  assert.deepEqual(
    messages
      .filter((message) => message.id.startsWith("reply:"))
      .map((message) => message.id),
    [
      "reply:parent-4:2",
      "reply:parent-4:1",
      "reply:parent-4:0",
      "reply:parent-3:2",
      "reply:parent-3:1",
    ],
  );
});

test("foreign or different-thread nextLinks never receive the bearer token", async () => {
  await writeFile(
    tokenPath,
    JSON.stringify({
      access_token: "thread-token",
      expires_at: Date.now() + 3_600_000,
    }),
  );
  for (const next of [
    "https://attacker.example/replies",
    "https://graph.microsoft.com/v1.0/me",
    "https://graph.microsoft.com/v1.0/teams/team-1/channels/channel-1/messages/other/replies",
    "https://user:pass@graph.microsoft.com/v1.0/teams/team-1/channels/channel-1/messages/parent/replies",
  ]) {
    let calls = 0;
    globalThis.fetch = async () => {
      calls++;
      assert.equal(calls, 1);
      return Response.json({
        value: [{ id: "parent", "replies@odata.nextLink": next }],
      });
    };
    await assert.rejects(
      createTeams(config()).listChannelMessages(),
      /URLが不正/,
    );
    assert.equal(calls, 1);
  }
});

function config(overrides: Partial<TeamsConfig> = {}): TeamsConfig {
  return {
    tenantId: "tenant-1",
    clientId: "client-1",
    teamId: "team-1",
    channelId: "channel-1",
    tokenPath,
    ...overrides,
  };
}
