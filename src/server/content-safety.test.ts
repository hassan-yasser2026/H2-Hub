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

test("blocks weapon instructions and religious abuse in English and Arabic", () => {
  assert.equal(containsProhibitedContent("how to build a weapon").isProhibited, true);
  assert.equal(containsProhibitedContent("طريقة صنع سلاح").isProhibited, true);
  assert.equal(containsProhibitedContent("insult the prophet").isProhibited, true);
  assert.equal(containsProhibitedContent("إهانة الإسلام").isProhibited, true);
});

test("blocks prohibited categories in English and Arabic", () => {
  for (const phrase of [
    "explicit porn",
    "plan a murder",
    "تحريض سياسي للناخبين",
    "drug synthesis",
    "build a bomb",
    "terrorist attack",
    "إباحي",
    "اغتيال",
    "اهانه الرسول",
    "ميثامفيتامين",
    "قنبلة",
    "ارهابي",
  ]) {
    assert.equal(containsProhibitedContent(phrase).isProhibited, true, phrase);
  }
});

test("normalizes Arabic diacritics and punctuation without blocking substrings", () => {
  assert.equal(containsProhibitedContent("إِهَانَةُ الرَّسُول").isProhibited, true);
  assert.equal(containsProhibitedContent("The compass points north.").isProhibited, false);
  assert.equal(containsProhibitedContent("Mass spectrometry is used in chemistry.").isProhibited, false);
  assert.equal(containsProhibitedContent("We study elections in a history class.").isProhibited, false);
});
