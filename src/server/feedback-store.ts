import { createHash, randomUUID, timingSafeEqual } from "node:crypto";
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import path from "node:path";

export type FeedbackRating = "positive" | "negative" | "incorrect";

export type FeedbackRecord = {
  responseId: string;
  question: string;
  reply: string;
  rating: FeedbackRating;
  note: string;
  createdAt: string;
  answerFingerprint: string;
};

const MAX_RECORDS = 20_000;

export function isFeedbackRating(value: unknown): value is FeedbackRating {
  return value === "positive" || value === "negative" || value === "incorrect";
}

export function feedbackStorePath(): string {
  return process.env.FEEDBACK_STORE_PATH?.trim() ||
    path.join(process.cwd(), "data", "feedback.json");
}

async function readRecords(storePath: string): Promise<FeedbackRecord[]> {
  try {
    const content = await readFile(storePath, "utf8");
    const parsed: unknown = JSON.parse(content);
    if (!Array.isArray(parsed) || !parsed.every(isFeedbackRecord)) {
      throw new Error("Feedback store has an invalid data format.");
    }
    return parsed;
  } catch (error) {
    if (isNodeError(error) && error.code === "ENOENT") return [];
    throw error;
  }
}

function isFeedbackRecord(value: unknown): value is FeedbackRecord {
  if (typeof value !== "object" || value === null) return false;
  const record = value as Partial<FeedbackRecord>;
  return typeof record.responseId === "string" &&
    typeof record.question === "string" &&
    typeof record.reply === "string" &&
    isFeedbackRating(record.rating) &&
    typeof record.note === "string" &&
    typeof record.createdAt === "string" &&
    typeof record.answerFingerprint === "string";
}

function isNodeError(error: unknown): error is NodeJS.ErrnoException {
  return error instanceof Error && "code" in error;
}

let writeQueue: Promise<void> = Promise.resolve();

function enqueueWrite<T>(operation: () => Promise<T>): Promise<T> {
  const result = writeQueue.then(operation, operation);
  writeQueue = result.then(() => undefined, () => undefined);
  return result;
}

function makeAnswerFingerprint(reply: string): string {
  return createHash("sha256")
    .update(reply.toLocaleLowerCase().replace(/\s+/g, " ").trim())
    .digest("hex");
}

export async function saveFeedback(input: {
  responseId: string;
  question: string;
  reply: string;
  rating: FeedbackRating;
  note: string;
}): Promise<FeedbackRecord> {
  return enqueueWrite(async () => {
    const storePath = feedbackStorePath();
    const records = await readRecords(storePath);
    const record: FeedbackRecord = {
      ...input,
      createdAt: new Date().toISOString(),
      answerFingerprint: makeAnswerFingerprint(input.reply),
    };
    const existingIndex = records.findIndex(
      (existing) => existing.responseId === input.responseId
    );
    if (existingIndex >= 0) records[existingIndex] = record;
    else records.push(record);
    if (records.length > MAX_RECORDS) records.splice(0, records.length - MAX_RECORDS);

    await mkdir(path.dirname(storePath), { recursive: true });
    const temporaryPath = `${storePath}.${randomUUID()}.tmp`;
    try {
      await writeFile(temporaryPath, JSON.stringify(records), {
        encoding: "utf8",
        mode: 0o600,
      });
      await rename(temporaryPath, storePath);
    } catch (error) {
      await import("node:fs/promises").then(({ unlink }) =>
        unlink(temporaryPath).catch(() => undefined)
      );
      throw error;
    }
    return record;
  });
}

export async function listFeedback(): Promise<FeedbackRecord[]> {
  await writeQueue;
  return readRecords(feedbackStorePath());
}

export function isValidAdminPassword(candidate: string, configured: string | undefined): boolean {
  if (!configured) return false;
  const candidateBytes = Buffer.from(candidate);
  const configuredBytes = Buffer.from(configured);
  return candidateBytes.length === configuredBytes.length &&
    timingSafeEqual(candidateBytes, configuredBytes);
}

export function getFeedbackStats(records: FeedbackRecord[]) {
  const positive = records.filter((record) => record.rating === "positive").length;
  const negative = records.filter((record) => record.rating !== "positive").length;
  const total = records.length;
  const badCounts = new Map<string, number>();
  for (const record of records) {
    if (record.rating !== "positive") {
      badCounts.set(
        record.answerFingerprint,
        (badCounts.get(record.answerFingerprint) ?? 0) + 1
      );
    }
  }
  const flaggedFingerprints = [...badCounts.entries()]
    .filter(([, count]) => count >= 5)
    .map(([fingerprint]) => fingerprint);
  return {
    total,
    positive,
    negative,
    goodPercent: total === 0 ? 0 : Math.round((positive / total) * 100),
    flaggedFingerprints,
  };
}

export function toFeedbackCsv(records: FeedbackRecord[]): string {
  const header = ["createdAt", "responseId", "rating", "question", "reply", "note"];
  const rows = records.map((record) => [
    record.createdAt,
    record.responseId,
    record.rating,
    record.question,
    record.reply,
    record.note,
  ]);
  return [header, ...rows]
    .map((row) => row.map(csvCell).join(","))
    .join("\r\n");
}

function csvCell(value: string): string {
  const safeValue = /^[\s]*[=+\-@]/u.test(value) ? `'${value}` : value;
  return `"${safeValue.replace(/"/g, '""')}"`;
}
