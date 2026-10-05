import assert from "node:assert/strict";
import { test } from "node:test";

import { JSDOM } from "jsdom";
import { h, render } from "preact";
import { act } from "preact/test-utils";

import "./tsxLoader.mjs";

const { Monitor } = await import("../src/Monitor.tsx");

test("monitor keeps developer tools but offers no Rokid configuration QR", async () => {
  const dom = new JSDOM('<main id="root"></main>', {
    url: "http://localhost/#/config",
  });
  const globals = [
    "window",
    "document",
    "localStorage",
    "fetch",
    "EventSource",
  ];
  const originals = globals.map((key) => globalThis[key]);
  const root = dom.window.document.querySelector("#root");
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  globalThis.localStorage = dom.window.localStorage;
  globalThis.EventSource = class {
    close() {}
  };
  globalThis.fetch = async (url) =>
    Response.json(url === "/api/list" ? [] : { ip: "127.0.0.1", port: 8787 });

  try {
    await act(async () => render(h(Monitor), root));
    assert.match(root.textContent, /SCRAFT 現場レポート/);
    assert.match(root.textContent, /ゴミ箱/);
    assert.doesNotMatch(root.textContent, /設定QR|チーム ID|チャネル ID/);
    assert.equal(root.querySelector('a[href="#/config"]'), null);
  } finally {
    render(null, root);
    dom.window.close();
    globals.forEach((key, index) => {
      globalThis[key] = originals[index];
    });
  }
});
