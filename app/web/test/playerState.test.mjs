import assert from "node:assert/strict";
import { test } from "node:test";

import {
  bufferedBars,
  clampVolume,
  formatPlaybackTime,
  percentFromTime,
  ratioFromPointer,
  SKIP_SECONDS,
  skipTime,
  timeFromRatio,
} from "../src/playerState.ts";

/** `video.buffered`（TimeRanges）の代役。 */
function ranges(...pairs) {
  return {
    length: pairs.length,
    start: (index) => pairs[index][0],
    end: (index) => pairs[index][1],
  };
}

test("playback clock truncates so it never shows the total early", () => {
  assert.equal(formatPlaybackTime(0), "0:00");
  assert.equal(formatPlaybackTime(2.9), "0:02");
  assert.equal(formatPlaybackTime(62.4), "1:02");
  assert.equal(formatPlaybackTime(3662), "1:01:02");
});

test("playback clock falls back to zero before the metadata arrives", () => {
  assert.equal(formatPlaybackTime(Number.NaN), "0:00");
  assert.equal(formatPlaybackTime(Number.POSITIVE_INFINITY), "0:00");
  assert.equal(formatPlaybackTime(-1), "0:00");
});

test("pointer position maps onto the seek bar and stays inside it", () => {
  const bar = { left: 100, width: 400 };

  assert.equal(ratioFromPointer(100, bar), 0);
  assert.equal(ratioFromPointer(300, bar), 0.5);
  assert.equal(ratioFromPointer(500, bar), 1);
  assert.equal(ratioFromPointer(20, bar), 0); // つまみを掴んだまま外へ出た
  assert.equal(ratioFromPointer(9999, bar), 1);
  assert.equal(ratioFromPointer(300, { left: 0, width: 0 }), 0); // 未レイアウト
});

test("ratio and time convert both ways for the played fill", () => {
  assert.equal(timeFromRatio(0.25, 12), 3);
  assert.equal(percentFromTime(3, 12), 25);

  // メタデータ待ちの duration（NaN / Infinity）でも 0 に倒す。
  assert.equal(timeFromRatio(0.5, Number.NaN), 0);
  assert.equal(percentFromTime(3, Number.POSITIVE_INFINITY), 0);
  assert.equal(percentFromTime(30, 12), 100);
});

test("arrow key skipping stops at both ends", () => {
  assert.equal(skipTime(3, SKIP_SECONDS, 12), 8);
  assert.equal(skipTime(3, -SKIP_SECONDS, 12), 0);
  assert.equal(skipTime(10, SKIP_SECONDS, 12), 12);
  assert.equal(skipTime(Number.NaN, SKIP_SECONDS, 12), 5);
});

test("buffered ranges become percentage bars", () => {
  assert.deepEqual(bufferedBars(ranges([0, 3], [6, 12]), 12), [
    { left: 0, width: 25 },
    { left: 50, width: 50 },
  ]);
});

test("buffered bars ignore empty ranges and unknown durations", () => {
  assert.deepEqual(bufferedBars(ranges(), 12), []);
  assert.deepEqual(bufferedBars(ranges([4, 4]), 12), []);
  assert.deepEqual(bufferedBars(ranges([0, 3]), Number.NaN), []);
  assert.deepEqual(bufferedBars(null, 12), []);
  // duration より先まで読めていることにされても 100% を超えない。
  assert.deepEqual(bufferedBars(ranges([0, 99]), 12), [
    { left: 0, width: 100 },
  ]);
});

test("volume stays between mute and full", () => {
  assert.equal(clampVolume(-1), 0);
  assert.equal(clampVolume(0.35), 0.35);
  assert.equal(clampVolume(9), 1);
  assert.equal(clampVolume(Number.NaN), 0);
});
