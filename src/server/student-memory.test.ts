import assert from "node:assert/strict";
import test from "node:test";
import { formatStudentMemoryContext } from "./student-memory.js";

test("limits student memory to three summaries and 500 words", () => {
  const summary = Array.from({ length: 220 }, (_, index) => `w${index}`).join(" ");
  const context = formatStudentMemoryContext({
    summaries: [summary, summary, summary, "must not be included"],
    weakTopics: [],
  });
  const summarySection = context.split("Repeatedly difficult topics:")[0] ?? "";
  const wordCount = summarySection.match(/\bw\d+\b/g)?.length ?? 0;

  assert.equal(wordCount, 500);
  assert.equal(context.includes("must not be included"), false);
});

test("limits weaknesses and treats memory as reference data", () => {
  const context = formatStudentMemoryContext({
    summaries: [],
    weakTopics: Array.from({ length: 12 }, (_, index) => `topic ${index}`),
  });

  assert.equal((context.match(/^- topic /gm) ?? []).length, 10);
  assert.match(context, /untrusted reference data/);
  assert.equal(context.includes("topic 10"), false);
});
