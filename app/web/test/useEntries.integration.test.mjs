import assert from "node:assert/strict";
import { afterEach, beforeEach, test } from "node:test";

import { JSDOM } from "jsdom";
import { h, render } from "preact";
import { act } from "preact/test-utils";

import { useEntries } from "../src/useEntries.ts";

const photo = {
  id: "photo-1",
  time: "08/14 10:00:00",
  kind: "photo",
  comment: "配管を確認",
  device: "rokid-1",
  file: "photo-1.jpg",
  sizeKb: 12,
};

const video = {
  id: "video-1",
  time: "08/14 10:01:00",
  kind: "video",
  comment: "作業状況",
  device: "rokid-1",
  file: "video-1.mp4",
  sizeKb: 42,
};

let dom;
let root;
let latestState;
let source;
let requests;
let originalDocument;
let originalEventSource;
let originalFetch;
let originalWindow;

class FakeEventSource {
  constructor(url) {
    this.url = url;
    this.closed = false;
    source = this;
  }

  close() {
    this.closed = true;
  }
}

function Harness() {
  latestState = useEntries();
  return h("output", null, `${latestState.items.length}:${latestState.live}`);
}

beforeEach(() => {
  originalDocument = globalThis.document;
  originalEventSource = globalThis.EventSource;
  originalFetch = globalThis.fetch;
  originalWindow = globalThis.window;

  dom = new JSDOM('<main id="root"></main>', {
    url: "http://localhost/",
  });
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  globalThis.EventSource = FakeEventSource;
  root = dom.window.document.querySelector("#root");
  requests = [];
  globalThis.fetch = async (url, init) => {
    requests.push({ url, init });
    if (url === "/api/list") return Response.json([photo]);
    return new Response(null, { status: 204 });
  };
});

afterEach(() => {
  if (root) render(null, root);
  dom.window.close();
  globalThis.window = originalWindow;
  globalThis.document = originalDocument;
  globalThis.EventSource = originalEventSource;
  globalThis.fetch = originalFetch;
});

test("loads entries, applies SSE changes, and performs optimistic mutations", async () => {
  act(() => render(h(Harness), root));
  await waitFor(() => latestState.items.length === 1);

  assert.deepEqual(latestState.items, [photo]);
  assert.equal(source.url, "/api/events");

  act(() => source.onopen());
  assert.equal(latestState.live, true);

  act(() =>
    source.onmessage({
      data: JSON.stringify({ type: "added", entry: video }),
    }),
  );
  assert.deepEqual(latestState.items, [video, photo]);

  await act(async () => latestState.remove(video.id));
  assert.deepEqual(latestState.items, [photo]);

  await act(async () => latestState.clear());
  assert.deepEqual(latestState.items, []);
  assert.deepEqual(
    requests.map(({ url, init }) => [url, init?.method ?? "GET"]),
    [
      ["/api/list", "GET"],
      ["/api/entries/video-1", "DELETE"],
      ["/api/entries", "DELETE"],
    ],
  );

  render(null, root);
  assert.equal(source.closed, true);
  root = null;
});

test("reports list failures and clears the error after photos and videos load", async () => {
  let available = false;
  globalThis.fetch = async (url) => {
    if (url !== "/api/list") return new Response(null, { status: 204 });
    if (!available) return new Response(null, { status: 503 });
    return Response.json([video, photo]);
  };

  act(() => render(h(Harness), root));
  await waitFor(() => latestState.loadError);

  assert.equal(latestState.items.length, 0);

  available = true;
  await act(async () => latestState.reload());

  assert.equal(latestState.loadError, false);
  assert.deepEqual(latestState.items, [video, photo]);
});

async function waitFor(predicate) {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    if (predicate()) return;
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 5));
    });
  }
  assert.fail("state did not settle before timeout");
}
