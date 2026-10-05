import assert from "node:assert/strict";
import { test } from "node:test";

import config from "../vite.config.ts";

test("dev and preview use the fixed monitor port", () => {
  assert.equal(config.server?.port, 5514);
  assert.equal(config.server?.strictPort, true);
  assert.equal(config.preview?.port, 5514);
  assert.equal(config.preview?.strictPort, true);
});
