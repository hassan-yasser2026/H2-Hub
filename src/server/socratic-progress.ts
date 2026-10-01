export type SocraticAssessment = "start" | "correct" | "incorrect" | "unclear" | "complete";

export type SocraticProgressState = {
  step: number;
  totalSteps: number;
  correctStreak: number;
  wrongStreak: number;
  difficultyLevel: number;
  awaitingAnswer: boolean;
  complete: boolean;
};

export function normalizeSocraticProgress(raw: unknown): SocraticProgressState {
  const value = typeof raw === "object" && raw !== null
    ? raw as Partial<SocraticProgressState>
    : {};
  const totalSteps = typeof value.totalSteps === "number" && Number.isFinite(value.totalSteps)
    ? Math.max(3, Math.min(8, Math.trunc(value.totalSteps)))
    : 5;
  return {
    step: typeof value.step === "number" && Number.isFinite(value.step)
      ? Math.max(0, Math.min(totalSteps, Math.trunc(value.step)))
      : 0,
    totalSteps,
    correctStreak: typeof value.correctStreak === "number" && Number.isFinite(value.correctStreak)
      ? Math.max(0, Math.min(2, Math.trunc(value.correctStreak)))
      : 0,
    wrongStreak: typeof value.wrongStreak === "number" && Number.isFinite(value.wrongStreak)
      ? Math.max(0, Math.min(1, Math.trunc(value.wrongStreak)))
      : 0,
    difficultyLevel: typeof value.difficultyLevel === "number" && Number.isFinite(value.difficultyLevel)
      ? Math.max(1, Math.min(5, Math.trunc(value.difficultyLevel)))
      : 2,
    awaitingAnswer: value.awaitingAnswer === true,
    complete: value.complete === true,
  };
}

export function advanceSocraticProgress(
  previous: SocraticProgressState,
  assessment: SocraticAssessment,
): SocraticProgressState {
  if (previous.complete) return previous;
  if (!previous.awaitingAnswer || assessment === "start") {
    return { ...previous, step: Math.max(1, previous.step), awaitingAnswer: true };
  }
  if (assessment === "complete") {
    return { ...previous, step: previous.totalSteps, awaitingAnswer: false, complete: true };
  }
  if (assessment === "correct") {
    const step = Math.min(previous.totalSteps, previous.step + 1);
    const streak = previous.correctStreak + 1;
    const masteredThree = streak >= 3;
    const complete = step >= previous.totalSteps;
    return {
      ...previous,
      step,
      correctStreak: masteredThree ? 0 : streak,
      wrongStreak: 0,
      difficultyLevel: masteredThree
        ? 2
        : Math.min(5, previous.difficultyLevel + 1),
      awaitingAnswer: !complete,
      complete,
    };
  }
  if (assessment === "incorrect") {
    const wrongStreak = previous.wrongStreak + 1;
    const needsSimplerQuestion = wrongStreak >= 2;
    return {
      ...previous,
      correctStreak: 0,
      wrongStreak: needsSimplerQuestion ? 0 : wrongStreak,
      difficultyLevel: needsSimplerQuestion ? Math.max(1, previous.difficultyLevel - 1) : previous.difficultyLevel,
      awaitingAnswer: true,
    };
  }
  return { ...previous, awaitingAnswer: true };
}
