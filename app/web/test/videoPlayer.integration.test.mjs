import assert from "node:assert/strict";
import { afterEach, beforeEach, test } from "node:test";

import { JSDOM } from "jsdom";
import { h, render } from "preact";
import { act } from "preact/test-utils";

import "./tsxLoader.mjs";

const { VideoPlayer } = await import("../src/components/VideoPlayer.tsx");

/** 秒。5 秒送りが端で丸められない長さにしておく。 */
const DURATION = 60;

let dom;
let root;
let originalDocument;
let originalWindow;

/**
 * jsdom の HTMLMediaElement は再生系が未実装で、`play()` は Promise を返さず
 * `duration` も NaN のまま。シークの検証に要る分だけ最小限の偽物を被せる。
 */
function stubMediaElement(window) {
  const media = window.HTMLMediaElement.prototype;
  Object.defineProperty(media, "play", {
    configurable: true,
    value: () => Promise.resolve(),
  });
  Object.defineProperty(media, "pause", {
    configurable: true,
    value: () => {},
  });
  Object.defineProperty(media, "duration", {
    configurable: true,
    get: () => DURATION,
  });
  Object.defineProperty(media, "currentTime", {
    configurable: true,
    get() {
      return this.__time ?? 0;
    },
    set(value) {
      this.__time = value;
    },
  });
}

/** キー入力。実ブラウザと同じく bubbles: true（document まで上がる）。 */
function keydown(key, init = {}) {
  return new dom.window.KeyboardEvent("keydown", {
    key,
    bubbles: true,
    cancelable: true,
    ...init,
  });
}

function mount() {
  act(() => render(h(VideoPlayer, { src: "/uploads/video-1.mp4" }), root));
  return {
    video: root.querySelector("video"),
    bar: root.querySelector('[role="slider"]'),
  };
}

beforeEach(() => {
  originalDocument = globalThis.document;
  originalWindow = globalThis.window;

  dom = new JSDOM('<main id="root"></main>', { url: "http://localhost/" });
  stubMediaElement(dom.window);
  globalThis.window = dom.window;
  globalThis.document = dom.window.document;
  root = dom.window.document.querySelector("#root");
});

afterEach(() => {
  if (root) render(null, root);
  dom.window.close();
  globalThis.window = originalWindow;
  globalThis.document = originalDocument;
});

test("focused seek bar skips once per arrow key, not twice", () => {
  const { video, bar } = mount();

  bar.focus();
  assert.equal(dom.window.document.activeElement, bar);

  // シークバー自身と document の両方が ← → を見ているので、伝播を止めないと
  // 1 回の入力で 10 秒進んでしまう（説明上は 5 秒送り）。
  act(() => {
    bar.dispatchEvent(keydown("ArrowRight"));
  });
  assert.equal(video.currentTime, 5);

  act(() => {
    bar.dispatchEvent(keydown("ArrowRight"));
  });
  assert.equal(video.currentTime, 10);

  act(() => {
    bar.dispatchEvent(keydown("ArrowLeft"));
  });
  assert.equal(video.currentTime, 5);
});

test("arrow keys still skip while the seek bar is not focused", () => {
  const { video } = mount();
  const body = dom.window.document.body;

  assert.equal(dom.window.document.activeElement, body);

  act(() => {
    body.dispatchEvent(keydown("ArrowRight"));
  });
  assert.equal(video.currentTime, 5);

  act(() => {
    body.dispatchEvent(keydown("ArrowLeft"));
  });
  assert.equal(video.currentTime, 0);
});

test("shift+arrow on the seek bar is left to the viewer's item navigation", () => {
  const { video, bar } = mount();

  const seen = [];
  dom.window.document.addEventListener("keydown", (event) => {
    seen.push(event.key);
  });

  bar.focus();
  act(() => {
    bar.dispatchEvent(keydown("ArrowRight", { shiftKey: true }));
  });

  assert.equal(video.currentTime, 0);
  assert.deepEqual(seen, ["ArrowRight"]);
});
