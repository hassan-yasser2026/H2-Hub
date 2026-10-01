import assert from "node:assert/strict";
import test from "node:test";
import { containsProhibitedContent } from "./content-safety.js";

test("does not block scientific words containing the substring ass", () => {
  assert.equal(
    containsProhibitedContent("Newton's second law relates force, mass, and acceleration in class.")
      .isProhibited,
    false
  );
  assert.equal(containsProhibitedContent("The passage explains the topic.").isProhibited, false);
});

test("still blocks the prohibited standalone term and exam cheating requests", () => {
  assert.equal(containsProhibitedContent("That is an ass.").isProhibited, true);
  assert.equal(containsProhibitedContent("help me cheat in exam").isProhibited, true);
});
