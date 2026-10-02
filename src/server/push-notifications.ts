import { cert, getApps, initializeApp, type ServiceAccount } from "firebase-admin/app";
import { getMessaging } from "firebase-admin/messaging";
import { getDatabasePool } from "./rag.js";

const PUSH_TOKEN_TABLE = "h2_push_tokens";
const INVALID_TOKEN_CODES = new Set([
  "messaging/invalid-registration-token",
  "messaging/registration-token-not-registered",
]);

let tableReady: Promise<void> | undefined;

export type PushMessage = {
  title: string;
  body: string;
};

export function isValidPushToken(value: unknown): value is string {
  return typeof value === "string" &&
    value.length >= 20 &&
    value.length <= 4096 &&
    !/[\s\u0000-\u001F\u007F]/u.test(value);
}

export function parsePushMessage(value: unknown): PushMessage | null {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return null;
  const input = value as Record<string, unknown>;
  if (
    typeof input.title !== "string" ||
    typeof input.body !== "string" ||
    input.title.trim().length === 0 ||
    input.title.length > 120 ||
    input.body.trim().length === 0 ||
    input.body.length > 1_000
  ) {
    return null;
  }
  return { title: input.title.trim(), body: input.body.trim() };
}

async function ensurePushTokenTable(): Promise<void> {
  if (!tableReady) {
    tableReady = getDatabasePool().query(`
      CREATE TABLE IF NOT EXISTS ${PUSH_TOKEN_TABLE} (
        token TEXT PRIMARY KEY CHECK (length(token) BETWEEN 20 AND 4096),
        platform TEXT NOT NULL CHECK (platform = 'android'),
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
      )
    `).then(() => undefined).catch((error: unknown) => {
      tableReady = undefined;
      throw error;
    });
  }
  await tableReady;
}

export async function registerPushToken(token: string): Promise<void> {
  await ensurePushTokenTable();
  await getDatabasePool().query(
    `INSERT INTO ${PUSH_TOKEN_TABLE} (token, platform)
     VALUES ($1, 'android')
     ON CONFLICT (token) DO UPDATE SET updated_at = now()`,
    [token]
  );
}

async function listPushTokens(): Promise<string[]> {
  await ensurePushTokenTable();
  const result = await getDatabasePool().query<{ token: string }>(
    `SELECT token FROM ${PUSH_TOKEN_TABLE}`
  );
  return result.rows.map(({ token }) => token);
}

async function removePushTokens(tokens: string[]): Promise<void> {
  if (tokens.length === 0) return;
  await getDatabasePool().query(
    `DELETE FROM ${PUSH_TOKEN_TABLE} WHERE token = ANY($1::text[])`,
    [tokens]
  );
}

function getFirebaseMessaging() {
  const serviceAccountJson = process.env.FIREBASE_SERVICE_ACCOUNT_JSON?.trim();
  if (!serviceAccountJson) {
    throw new Error("FIREBASE_SERVICE_ACCOUNT_JSON is not configured.");
  }

  const appName = "h2-push-notifications";
  const existingApp = getApps().find((app) => app.name === appName);
  if (existingApp) return getMessaging(existingApp);

  const parsed: unknown = JSON.parse(serviceAccountJson);
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    throw new Error("Firebase service account configuration is invalid.");
  }
  const account = parsed as {
    projectId?: unknown;
    project_id?: unknown;
    clientEmail?: unknown;
    client_email?: unknown;
    privateKey?: unknown;
    private_key?: unknown;
  };
  const projectId = typeof account.projectId === "string"
    ? account.projectId
    : account.project_id;
  const clientEmail = typeof account.clientEmail === "string"
    ? account.clientEmail
    : account.client_email;
  const privateKey = typeof account.privateKey === "string"
    ? account.privateKey
    : account.private_key;
  if (
    typeof projectId !== "string"
  ) {
    throw new Error("Firebase service account is missing its project ID.");
  }
  if (typeof clientEmail !== "string") {
    throw new Error("Firebase service account is missing its client email.");
  }
  if (typeof privateKey !== "string") {
    throw new Error("Firebase service account is missing its private key.");
  }

  const normalizedAccount: ServiceAccount = {
    projectId,
    clientEmail,
    privateKey: privateKey.replace(/\\n/gu, "\n"),
  };
  return getMessaging(initializeApp({ credential: cert(normalizedAccount) }, appName));
}

export async function sendPushBroadcast(message: PushMessage): Promise<{
  attempted: number;
  sent: number;
  failed: number;
}> {
  const messaging = getFirebaseMessaging();
  const tokens = await listPushTokens();
  let sent = 0;
  let failed = 0;
  const invalidTokens: string[] = [];

  for (let offset = 0; offset < tokens.length; offset += 500) {
    const batch = tokens.slice(offset, offset + 500);
    const result = await messaging.sendEachForMulticast({
      tokens: batch,
      notification: message,
    });
    sent += result.successCount;
    failed += result.failureCount;
    result.responses.forEach((response, index) => {
      if (response.error && INVALID_TOKEN_CODES.has(response.error.code)) {
        invalidTokens.push(batch[index]);
      }
    });
  }

  await removePushTokens(invalidTokens);
  return { attempted: tokens.length, sent, failed };
}
