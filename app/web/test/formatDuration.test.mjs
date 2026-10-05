import assert from "node:assert/strict";
import { test } from "node:test";

import { formatDuration } from "../src/formatDuration.ts";

test("formats sub-minute video duration and rounds to the nearest second", () => {
  assert.equal(formatDuration(2.6), "0:03");
});

test("formats minute duration with zero-padded seconds", () => {
  assert.equal(formatDuration(62), "1:02");
});

test("formats hour duration with zero-padded minutes and seconds", () => {
  assert.equal(formatDuration(3662), "1:01:02");
});
