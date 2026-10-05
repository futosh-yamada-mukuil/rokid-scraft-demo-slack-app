import assert from "node:assert/strict";
import { test } from "node:test";

import type { Change } from "shared";

import { createEventBus } from "../src/events.ts";

const cleared: Change = { type: "cleared" };

test("event bus broadcasts a change to every subscriber", () => {
  const bus = createEventBus();
  const first: Change[] = [];
  const second: Change[] = [];

  bus.subscribe((change) => first.push(change));
  bus.subscribe((change) => second.push(change));
  bus.broadcast(cleared);

  assert.deepEqual(first, [cleared]);
  assert.deepEqual(second, [cleared]);
  assert.equal(bus.size, 2);
});

test("unsubscribe stops future broadcasts", () => {
  const bus = createEventBus();
  const received: Change[] = [];
  const unsubscribe = bus.subscribe((change) => received.push(change));

  unsubscribe();
  bus.broadcast(cleared);

  assert.deepEqual(received, []);
  assert.equal(bus.size, 0);
});
