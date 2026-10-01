import assert from "node:assert/strict";
import test from "node:test";
import {
  advanceSocraticProgress,
  normalizeSocraticProgress,
  type SocraticProgressState,
} from "./socratic-progress.js";

const initial: SocraticProgressState = {
  step: 1,
  totalSteps: 5,
  correctStreak: 0,
  wrongStreak: 0,
  difficultyLevel: 2,
  awaitingAnswer: true,
  complete: false,
};

test("each correct answer raises difficulty and three in a row reset it to normal", () => {
  const first = advanceSocraticProgress(initial, "correct");
  const second = advanceSocraticProgress(first, "correct");
  const third = advanceSocraticProgress(second, "correct");

  assert.equal(first.difficultyLevel, 3);
  assert.equal(second.difficultyLevel, 4);
  assert.equal(third.step, 4);
  assert.equal(third.difficultyLevel, 2);
  assert.equal(third.correctStreak, 0);
});

test("two incorrect answers lower difficulty without advancing the step", () => {
  const first = advanceSocraticProgress(initial, "incorrect");
  const second = advanceSocraticProgress(first, "incorrect");

  assert.equal(second.step, initial.step);
  assert.equal(second.difficultyLevel, 1);
  assert.equal(second.wrongStreak, 0);
});

test("normalizes untrusted progress into bounded values", () => {
  assert.deepEqual(normalizeSocraticProgress({
    step: 99,
    totalSteps: 99,
    correctStreak: -1,
    wrongStreak: 8,
    difficultyLevel: 0,
    awaitingAnswer: "yes",
  }), {
    step: 8,
    totalSteps: 8,
    correctStreak: 0,
    wrongStreak: 1,
    difficultyLevel: 1,
    awaitingAnswer: false,
    complete: false,
  });
});
