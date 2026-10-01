import assert from "node:assert/strict";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import {
  getFeedbackStats,
  isFeedbackRating,
  isValidAdminPassword,
  saveFeedback,
  toFeedbackCsv,
  type FeedbackRecord,
} from "./feedback-store.js";

test("persists and replaces a response rating without duplicate records", async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "h2-feedback-"));
  const originalPath = process.env.FEEDBACK_STORE_PATH;
  const storePath = path.join(directory, "feedback.json");
  process.env.FEEDBACK_STORE_PATH = storePath;
  try {
    const first = await saveFeedback({
      responseId: "response-1",
      question: "ما ناتج 2+2؟",
      reply: "4",
      rating: "negative",
      note: "وضح الخطوات",
    });
    const updated = await saveFeedback({
      responseId: "response-1",
      question: "ما ناتج 2+2؟",
      reply: "4",
      rating: "positive",
      note: "",
    });
    const stored = JSON.parse(await readFile(storePath, "utf8")) as FeedbackRecord[];

    assert.equal(first.responseId, "response-1");
    assert.equal(updated.rating, "positive");
    assert.equal(stored.length, 1);
    assert.equal(stored[0].rating, "positive");
  } finally {
    if (originalPath === undefined) delete process.env.FEEDBACK_STORE_PATH;
    else process.env.FEEDBACK_STORE_PATH = originalPath;
    await rm(directory, { recursive: true, force: true });
  }
});

test("flags a repeated answer after five negative ratings and computes good rate", () => {
  const records: FeedbackRecord[] = Array.from({ length: 5 }, (_, index) => ({
    responseId: `response-${index}`,
    question: "question",
    reply: "same answer",
    rating: "incorrect",
    note: "",
    createdAt: new Date().toISOString(),
    answerFingerprint: "same-answer",
  }));
  records.push({
    responseId: "good",
    question: "question",
    reply: "helpful answer",
    rating: "positive",
    note: "",
    createdAt: new Date().toISOString(),
    answerFingerprint: "good-answer",
  });
  const stats = getFeedbackStats(records);

  assert.equal(stats.goodPercent, 17);
  assert.deepEqual(stats.flaggedFingerprints, ["same-answer"]);
});

test("validates rating values, compares admin password, and protects CSV formulas", () => {
  assert.equal(isFeedbackRating("positive"), true);
  assert.equal(isFeedbackRating("unknown"), false);
  assert.equal(isValidAdminPassword("admin-secret", "admin-secret"), true);
  assert.equal(isValidAdminPassword("wrong", "admin-secret"), false);
  assert.equal(isValidAdminPassword("admin-secret", undefined), false);
  assert.match(toFeedbackCsv([{
    responseId: "1",
    question: "=HYPERLINK(\"bad\")",
    reply: "answer",
    rating: "positive",
    note: "",
    createdAt: "2026-10-01",
    answerFingerprint: "hash",
  }]), /"'=HYPERLINK\(""bad""\)"/);
});
