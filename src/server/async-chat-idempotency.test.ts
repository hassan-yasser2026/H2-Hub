import assert from "node:assert/strict";
import test from "node:test";
import type { QueryResultRow } from "pg";
import {
  enqueueAsyncChatIdempotently,
  IdempotencyConflictError,
  type AsyncChatTransactionClient,
  type IdempotentAsyncChatPayload,
} from "./async-chat-idempotency.js";
import type { QuotaReservation } from "./subscription-usage.js";

class FakeAsyncChatClient implements AsyncChatTransactionClient {
  job: Record<string, unknown> | undefined;

  async query<T extends QueryResultRow = QueryResultRow>(
    queryText: string,
    values: unknown[] = []
  ): Promise<{ rows: T[]; rowCount: number | null }> {
    if (queryText.includes("FROM h2_async_chat_jobs")) {
      return { rows: (this.job ? [this.job] : []) as T[], rowCount: this.job ? 1 : 0 };
    }
    if (queryText.includes("INSERT INTO h2_async_chat_jobs")) {
      this.job = {
        id: values[0],
        session_id: values[1],
        device_token: values[2],
        message: values[3],
        history: JSON.parse(String(values[4])),
        system_instruction: values[5],
        status: "queued",
      };
      return { rows: [], rowCount: 1 };
    }
    return { rows: [], rowCount: null };
  }

  release(): void {}
}

const payload: IdempotentAsyncChatPayload = {
  idempotencyKey: "9c233bf4-1d38-4e30-8ee8-e02c90297975",
  sessionId: "session-1",
  deviceToken: "push-token-12345678901234567890",
  message: "اشرح قانون نيوتن",
  history: [{ role: "user", text: "ما هو التسارع؟" }],
  systemInstruction: "أجب بالعربية.",
};

const allowedReservation: QuotaReservation = {
  allowed: true,
  unlimited: false,
  legacy: false,
  paid: false,
  feature: "chat",
  used: 1,
  limit: 20,
  remaining: 19,
  resetAt: "2026-10-07T00:00:00.000Z",
};

test("replayed async chat returns the original job without reserving quota twice", async () => {
  const client = new FakeAsyncChatClient();
  let reservations = 0;
  const reserveQuota = async () => {
    reservations += 1;
    return allowedReservation;
  };

  const first = await enqueueAsyncChatIdempotently(client, payload, reserveQuota);
  const replay = await enqueueAsyncChatIdempotently(client, payload, reserveQuota);

  assert.equal(first.jobId, payload.idempotencyKey);
  assert.equal(first.replayed, false);
  assert.equal(replay.jobId, first.jobId);
  assert.equal(replay.replayed, true);
  assert.equal(reservations, 1);
});

test("reusing an async chat key for different content is rejected before quota reservation", async () => {
  const client = new FakeAsyncChatClient();
  let reservations = 0;
  const reserveQuota = async () => {
    reservations += 1;
    return allowedReservation;
  };
  await enqueueAsyncChatIdempotently(client, payload, reserveQuota);

  await assert.rejects(
    enqueueAsyncChatIdempotently(client, { ...payload, message: "رسالة مختلفة" }, reserveQuota),
    IdempotencyConflictError
  );
  assert.equal(reservations, 1);
});
