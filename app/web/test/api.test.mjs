import assert from "node:assert/strict";
import { afterEach, beforeEach, test } from "node:test";

import {
  clearEntries,
  fetchHealth,
  fetchList,
  fetchTeamsMessages,
  removeEntry,
  sendLocalReply,
} from "../src/api.ts";

let originalFetch;

beforeEach(() => {
  originalFetch = globalThis.fetch;
});

afterEach(() => {
  globalThis.fetch = originalFetch;
});

test("list and health use uncached API reads", async () => {
  const requests = [];
  globalThis.fetch = async (url, init) => {
    requests.push({ url, init });
    if (url === "/api/list") return Response.json([{ id: "entry-1" }]);
    return Response.json({ ok: true, ip: "192.168.1.10", port: 8787 });
  };

  assert.deepEqual(await fetchList(), [{ id: "entry-1" }]);
  assert.deepEqual(await fetchHealth(), {
    ok: true,
    ip: "192.168.1.10",
    port: 8787,
  });
  assert.deepEqual(requests, [
    { url: "/api/list", init: { cache: "no-store" } },
    { url: "/api/health", init: { cache: "no-store" } },
  ]);
});

test("remove and clear use the expected DELETE endpoints", async () => {
  const requests = [];
  globalThis.fetch = async (url, init) => {
    requests.push({ url, init });
    return new Response(null, { status: 204 });
  };

  await removeEntry("20260814-161512-abcd");
  await clearEntries();

  assert.deepEqual(requests, [
    {
      url: "/api/entries/20260814-161512-abcd",
      init: { method: "DELETE" },
    },
    { url: "/api/entries", init: { method: "DELETE" } },
  ]);
});

test("API helpers reject non-success responses with the status", async () => {
  globalThis.fetch = async () => new Response(null, { status: 503 });

  await assert.rejects(fetchList(), /list failed: 503/);
  await assert.rejects(fetchHealth(), /health failed: 503/);
  await assert.rejects(removeEntry("entry-1"), /delete failed: 503/);
  await assert.rejects(clearEntries(), /clear failed: 503/);
});

test("local replies fetch the current PC token without reading QR settings", async () => {
  const requests = [];
  globalThis.fetch = async (url, init) => {
    requests.push({ url, init });
    if (url === "/api/token") return Response.json({ token: "current-token" });
    return Response.json({ id: "reply-1" });
  };

  assert.deepEqual(await sendLocalReply("entry-1", "確認しました"), {
    id: "reply-1",
  });
  assert.deepEqual(requests, [
    { url: "/api/token", init: { cache: "no-store" } },
    {
      url: "/api/replies",
      init: {
        method: "POST",
        headers: {
          "x-auth-token": "current-token",
          "content-type": "application/json",
        },
        body: JSON.stringify({ entryId: "entry-1", text: "確認しました" }),
      },
    },
  ]);
});

test("Teams reads also use the current PC token", async () => {
  const requests = [];
  globalThis.fetch = async (url, init) => {
    requests.push({ url, init });
    if (url === "/api/token") return Response.json({ token: "current-token" });
    return Response.json([]);
  };

  assert.deepEqual(await fetchTeamsMessages(), []);
  assert.deepEqual(requests[1], {
    url: "/api/teams/channel/messages",
    init: { cache: "no-store", headers: { "x-auth-token": "current-token" } },
  });
});

test("token failures prevent authenticated requests", async () => {
  for (const response of [
    new Response(null, { status: 403 }),
    new Response(null, { status: 503 }),
    Response.json({ token: "" }),
    Response.json({ token: 123 }),
  ]) {
    const requests = [];
    globalThis.fetch = async (url) => {
      requests.push(url);
      return response;
    };
    await assert.rejects(sendLocalReply("entry-1", "test"));
    assert.deepEqual(requests, ["/api/token"]);
  }
});
