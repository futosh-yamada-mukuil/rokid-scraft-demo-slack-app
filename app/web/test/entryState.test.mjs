import assert from "node:assert/strict";
import { test } from "node:test";

import { applyChange, neighborId } from "../src/entryState.ts";

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

test("added prepends a new entry", () => {
  assert.deepEqual(applyChange([photo], { type: "added", entry: video }), [
    video,
    photo,
  ]);
});

test("added replaces an existing ID without duplication", () => {
  const updated = { ...photo, comment: "確認済み" };

  assert.deepEqual(
    applyChange([video, photo], { type: "added", entry: updated }),
    [updated, video],
  );
});

test("deleted removes only the selected ID", () => {
  assert.deepEqual(
    applyChange([video, photo], { type: "deleted", id: video.id }),
    [photo],
  );
});

test("cleared removes every entry", () => {
  assert.deepEqual(applyChange([video, photo], { type: "cleared" }), []);
});

test("neighbor moves through the list in display order", () => {
  assert.equal(neighborId([video, photo], video.id, 1), photo.id);
  assert.equal(neighborId([video, photo], photo.id, -1), video.id);
});

test("neighbor stops at both ends of the list", () => {
  assert.equal(neighborId([video, photo], video.id, -1), null);
  assert.equal(neighborId([video, photo], photo.id, 1), null);
});

test("neighbor of an entry that vanished is null", () => {
  // 表示中に SSE の deleted / cleared が来た場合。ビューアはこれで閉じる。
  assert.equal(neighborId([], video.id, 1), null);
  assert.equal(neighborId([photo], video.id, -1), null);
});
