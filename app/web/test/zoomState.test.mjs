import assert from "node:assert/strict";
import { test } from "node:test";

import {
  clampPan,
  clampScale,
  containSize,
  FIT,
  MAX_SCALE,
  panBy,
  toPercent,
  toTransform,
  wheelZoomFactor,
  zoomAt,
} from "../src/zoomState.ts";

// 横長の画面に縦長の写真（グラスの出力）を置いた前提。
const viewport = { width: 1600, height: 900 };
const portrait = { width: 960, height: 1280 };

test("contain fits a portrait photo to the viewport height", () => {
  assert.deepEqual(containSize(portrait, viewport), {
    width: 675,
    height: 900,
  });
});

test("contain returns zero while the size is still unknown", () => {
  assert.deepEqual(containSize({ width: 0, height: 0 }, viewport), {
    width: 0,
    height: 0,
  });
  assert.deepEqual(containSize(portrait, { width: 0, height: 0 }), {
    width: 0,
    height: 0,
  });
});

test("scale stays inside the usable range", () => {
  assert.equal(clampScale(0.2), 1);
  assert.equal(clampScale(2), 2);
  assert.equal(clampScale(99), MAX_SCALE);
  assert.equal(clampScale(Number.NaN), 1);
});

test("pan is pinned to the centre while the axis fits on screen", () => {
  const fitted = containSize(portrait, viewport);

  // 2倍でも幅 1350px なので横幅 1600px には収まる → x は動かない。
  // 高さは 1800px あるので上下には (1800-900)/2 = 450px まで動く。
  assert.deepEqual(clampPan({ scale: 2, x: 400, y: 900 }, fitted, viewport), {
    scale: 2,
    x: 0,
    y: 450,
  });
  assert.deepEqual(clampPan({ scale: 2, x: 0, y: -900 }, fitted, viewport), {
    scale: 2,
    x: 0,
    y: -450,
  });
});

test("zoom keeps the focal point under the cursor", () => {
  const fitted = containSize(portrait, viewport);
  const focal = { x: 0, y: -200 };

  const zoomed = zoomAt(FIT, 2, focal, fitted, viewport);

  assert.equal(zoomed.scale, 2);
  // 注視点は「中央から 200px 上」。2倍にすると 400px 上へ動くので、
  // 下へ 200px 戻せば元の位置に留まる。
  assert.equal(zoomed.y, 200);
  assert.equal(zoomed.x, 0);
});

test("zoom never leaves the image off screen", () => {
  const fitted = containSize(portrait, viewport);

  // 画面上端を注視して拡大すると、注視点維持だけなら余白が見えてしまう。
  // 可動域で丸めるので、画像の上辺が画面の上辺にぴったり張り付く。
  const zoomed = zoomAt(FIT, 1.2, { x: 0, y: -450 }, fitted, viewport);
  const maxY = (fitted.height * zoomed.scale - viewport.height) / 2;

  assert.ok(Math.abs(zoomed.y - maxY) < 1e-6, `${zoomed.y} != ${maxY}`);
});

test("zooming out from the maximum returns to the fit position", () => {
  const fitted = containSize(portrait, viewport);
  const zoomed = zoomAt(FIT, 4, { x: 0, y: -300 }, fitted, viewport);

  assert.deepEqual(zoomAt(zoomed, 1 / 99, { x: 0, y: 0 }, fitted, viewport), {
    scale: 1,
    x: 0,
    y: 0,
  });
});

test("drag moves the image and stops at the edge", () => {
  const fitted = containSize(portrait, viewport);
  const start = { scale: 2, x: 0, y: 0 };

  assert.deepEqual(panBy(start, 30, -100, fitted, viewport), {
    scale: 2,
    x: 0, // 横は収まっているので動かない
    y: -100,
  });
  assert.equal(panBy(start, 0, -5000, fitted, viewport).y, -450);
});

test("wheel direction maps to zoom in and out, and is capped per notch", () => {
  assert.ok(wheelZoomFactor(-100) > 1); // 手前に回す＝拡大
  assert.ok(wheelZoomFactor(100) < 1); // 奥に回す＝縮小
  assert.equal(wheelZoomFactor(0), 1);

  // トラックパッドの巨大な delta でも 1 ノッチの上限を超えない。
  assert.equal(wheelZoomFactor(9999), wheelZoomFactor(160));
  // 行/ページ指定でも px 相当に換算される。
  assert.equal(wheelZoomFactor(3, 1), wheelZoomFactor(48));
});

test("transform and percent are rendered from the same state", () => {
  assert.equal(toTransform(FIT), "translate(0px, 0px) scale(1)");
  assert.equal(
    toTransform({ scale: 2.5, x: 10.4567, y: -3 }),
    "translate(10.457px, -3px) scale(2.5)",
  );
  assert.equal(toPercent({ scale: 2.5, x: 0, y: 0 }), 250);
});
