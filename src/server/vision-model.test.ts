import assert from "node:assert/strict";
import test from "node:test";
import {
  DEFAULT_VISION_MODEL,
  FALLBACK_VISION_MODEL,
  getVisionModel,
  shouldRetryUnavailableVisionModel,
} from "./vision-model.js";

test("uses Gemini 2.5 Flash by default and respects a configured model", () => {
  assert.equal(getVisionModel(undefined), DEFAULT_VISION_MODEL);
  assert.equal(getVisionModel(" gemini-custom "), "gemini-custom");
});

test("retries with the current Flash model when the configured model is unavailable", () => {
  const unavailableError = new Error(
    "models/gemini-2.5-flash is no longer available to new users (NOT_FOUND)"
  );
  assert.equal(shouldRetryUnavailableVisionModel(unavailableError, DEFAULT_VISION_MODEL), true);
  assert.equal(shouldRetryUnavailableVisionModel(new Error("quota exceeded"), DEFAULT_VISION_MODEL), false);
  assert.equal(shouldRetryUnavailableVisionModel(unavailableError, FALLBACK_VISION_MODEL), false);
});
