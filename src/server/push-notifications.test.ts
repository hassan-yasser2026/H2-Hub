import assert from "node:assert/strict";
import test from "node:test";
import { isValidPushToken, parsePushMessage } from "./push-notifications.js";

test("accepts a registration token without whitespace", () => {
  assert.equal(isValidPushToken(`token:${"a".repeat(40)}_device-token`), true);
});

test("rejects malformed or oversized registration tokens", () => {
  assert.equal(isValidPushToken("too-short"), false);
  assert.equal(isValidPushToken(`token:${"a".repeat(4096)}`), false);
  assert.equal(isValidPushToken(`token:${"a".repeat(40)} token`), false);
  assert.equal(isValidPushToken(null), false);
});

test("accepts push messages within documented limits and trims whitespace", () => {
  assert.deepEqual(parsePushMessage({ title: "  تحديث  ", body: "  أخبار التطبيق  " }), {
    title: "تحديث",
    body: "أخبار التطبيق",
  });
});

test("rejects empty, oversized, and non-object push messages", () => {
  assert.equal(parsePushMessage({ title: "", body: "message" }), null);
  assert.equal(parsePushMessage({ title: "title", body: " ".repeat(1_001) }), null);
  assert.equal(parsePushMessage({ title: "x".repeat(121), body: "message" }), null);
  assert.equal(parsePushMessage(null), null);
  assert.equal(parsePushMessage([]), null);
});
