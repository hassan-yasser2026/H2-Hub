import type { QueryResultRow } from "pg";
import type { QuotaReservation } from "./subscription-usage.js";

export type IdempotentAsyncChatPayload = {
  idempotencyKey: string;
  sessionId: string;
  deviceToken: string;
  message: string;
  history: Array<{ role: "user" | "model"; text: string }>;
  systemInstruction: string;
};

export interface AsyncChatTransactionClient {
  query<T extends QueryResultRow = QueryResultRow>(
    queryText: string,
    values?: unknown[]
  ): Promise<{ rows: T[]; rowCount: number | null }>;
  release(): void;
}

type ExistingJob = QueryResultRow & {
  id: string;
  session_id: string;
  device_token: string;
  message: string;
  history: Array<{ role: string; text: string }>;
  system_instruction: string;
  status: string;
};

export type IdempotentAsyncChatResult = {
  jobId: string;
  status: string;
  replayed: boolean;
  reservation?: QuotaReservation;
};

export class IdempotencyConflictError extends Error {
  constructor() {
    super("The idempotency key is already used by a different chat request.");
    this.name = "IdempotencyConflictError";
  }
}

function matchesExistingJob(job: ExistingJob, payload: IdempotentAsyncChatPayload): boolean {
  const history = Array.isArray(job.history)
    ? job.history.map(({ role, text }) => ({ role, text }))
    : [];
  return job.session_id === payload.sessionId &&
    job.device_token === payload.deviceToken &&
    job.message === payload.message &&
    JSON.stringify(history) === JSON.stringify(payload.history) &&
    job.system_instruction === payload.systemInstruction;
}

export async function enqueueAsyncChatIdempotently(
  client: AsyncChatTransactionClient,
  payload: IdempotentAsyncChatPayload,
  reserveQuota: (client: AsyncChatTransactionClient) => Promise<QuotaReservation>
): Promise<IdempotentAsyncChatResult> {
  await client.query("BEGIN");
  try {
    await client.query(
      "SELECT pg_advisory_xact_lock(hashtext($1))",
      [payload.idempotencyKey]
    );
    const existingResult = await client.query<ExistingJob>(
      `SELECT id, session_id, device_token, message, history, system_instruction, status
       FROM h2_async_chat_jobs
       WHERE id = $1
       FOR UPDATE`,
      [payload.idempotencyKey]
    );
    const existing = existingResult.rows[0];
    if (existing) {
      if (!matchesExistingJob(existing, payload)) throw new IdempotencyConflictError();
      await client.query("COMMIT");
      return {
        jobId: existing.id,
        status: "queued",
        replayed: true,
      };
    }

    const reservation = await reserveQuota(client);
    if (!reservation.allowed) {
      await client.query("ROLLBACK");
      return {
        jobId: payload.idempotencyKey,
        status: "quota_exceeded",
        replayed: false,
        reservation,
      };
    }

    await client.query(
      `INSERT INTO h2_async_chat_jobs
         (id, session_id, device_token, message, history, system_instruction)
       VALUES ($1, $2, $3, $4, $5::jsonb, $6)`,
      [
        payload.idempotencyKey,
        payload.sessionId,
        payload.deviceToken,
        payload.message,
        JSON.stringify(payload.history),
        payload.systemInstruction,
      ]
    );
    await client.query("COMMIT");
    return {
      jobId: payload.idempotencyKey,
      status: "queued",
      replayed: false,
      reservation,
    };
  } catch (error) {
    await client.query("ROLLBACK").catch(() => undefined);
    throw error;
  }
}
