import assert from "node:assert/strict";
import test from "node:test";
import {
  FREE_MESSAGE_LIMIT,
  FREE_USAGE_LIMITS,
  getDeviceUsage,
  isUsageFeature,
  MESSAGE_COSTS,
  messageCostForFeature,
  reserveMessageQuota,
  type QuotaDatabase,
} from "./subscription-usage.js";

function fakeQuotaDatabase(options: {
  legacy?: boolean;
  paid?: { total: number; used: number; images?: boolean };
} = {}): {
  database: QuotaDatabase;
  getFreeUsed: (identity: string) => number;
  getPaidUsed: () => number;
  getServiceCount: (service: string) => number;
  getQuotaIdentities: () => string[];
} {
  const freeUsageByIdentity = new Map<string, number>();
  let paidUsed = options.paid?.used ?? 0;
  const serviceCounts = new Map<string, number>();
  const quotaIdentities = new Set<string>();
  const expiresAt = new Date("2027-01-01T00:00:00.000Z");
  const resetAt = new Date("2026-10-07T00:00:00.000Z");

  const database: QuotaDatabase = {
    async query<T extends import("pg").QueryResultRow>(
      queryText: string,
      values: unknown[] = []
    ): Promise<{ rows: T[]; rowCount: number }> {
      const rows: object[] = [];
      if (queryText.includes("INSERT INTO subscription_service_usage")) {
        const service = String(values[0]);
        serviceCounts.set(service, (serviceCounts.get(service) ?? 0) + 1);
      } else if (queryText.includes("SELECT is_legacy AND subscription_end_date")) {
        if (options.legacy) rows.push({ active: true });
      } else if (queryText.includes("SELECT EXISTS") && queryText.includes("FROM app_users")) {
        rows.push({ active: options.legacy ?? false });
      } else if (queryText.includes("SELECT us.id, us.user_id")) {
        if (options.paid) {
          rows.push({
            id: "sub-id",
            user_id: "user-id",
            plan_name: "trial",
            name_ar: "تجريبي",
            messages_total: options.paid.total,
            messages_used: paidUsed,
            expires_at: expiresAt,
            features: { images: options.paid.images ?? false },
          });
        }
      } else if (queryText.includes("SELECT sp.name AS plan_name")) {
        if (options.paid) {
          rows.push({
            plan_name: "trial",
            name_ar: "تجريبي",
            messages_total: options.paid.total,
            messages_used: paidUsed,
            expires_at: expiresAt,
            features: { images: options.paid.images ?? false },
          });
        }
      } else if (queryText.includes("UPDATE user_subscriptions")) {
        const cost = Number(values[1]);
        if (options.paid && paidUsed + cost <= options.paid.total) {
          paidUsed += cost;
          rows.push({
            messages_used: paidUsed,
            messages_total: options.paid.total,
            remaining: options.paid.total - paidUsed,
            reset_at: expiresAt,
          });
        }
      } else if (queryText.includes("SELECT messages_used, messages_total")) {
        if (options.paid) {
          rows.push({
            messages_used: paidUsed,
            messages_total: options.paid.total,
            remaining: Math.max(0, options.paid.total - paidUsed),
            reset_at: expiresAt,
          });
        }
      } else if (queryText.includes("INSERT INTO free_user_daily_usage")) {
        const identity = String(values[0]);
        quotaIdentities.add(identity);
        const freeUsed = freeUsageByIdentity.get(identity) ?? 0;
        const cost = Number(values[1]);
        if (freeUsed + cost <= FREE_MESSAGE_LIMIT) {
          const updatedUsage = freeUsed + cost;
          freeUsageByIdentity.set(identity, updatedUsage);
          rows.push({
            messages_used: updatedUsage,
            messages_total: FREE_MESSAGE_LIMIT,
            remaining: FREE_MESSAGE_LIMIT - updatedUsage,
            reset_at: resetAt,
          });
        }
      } else if (queryText.includes("SELECT messages_used,") &&
          queryText.includes("FROM free_user_daily_usage")) {
        rows.push({
          messages_used: freeUsageByIdentity.get(String(values[0])) ?? 0,
          reset_at: resetAt,
        });
      } else if (queryText.includes("AS reset_at")) {
        rows.push({ reset_at: resetAt });
      }
      return { rows: rows as T[], rowCount: rows.length };
    },
  };
  return {
    database,
    getFreeUsed: (identity) => freeUsageByIdentity.get(identity) ?? 0,
    getPaidUsed: () => paidUsed,
    getServiceCount: (service) => serviceCounts.get(service) ?? 0,
    getQuotaIdentities: () => [...quotaIdentities],
  };
}

test("free usage limits match the published daily quotas", () => {
  assert.deepEqual(FREE_USAGE_LIMITS, {
    chat: 20,
    coder: 20,
    quran: 0,
    vision: 20,
    rag: 20,
  });

  test("authenticated quota reservations use the verified Firebase UID identity", async () => {
    const fake = fakeQuotaDatabase();
    const reservation = await reserveMessageQuota(
      "device-id",
      1,
      "chat",
      () => fake.database,
      async () => {},
      "chat",
      "firebase-user"
    );
    assert.equal(reservation.allowed, true);
    assert.deepEqual(fake.getQuotaIdentities(), ["uid:firebase-user"]);
  });

  test("usage marks an active metered subscriber as paid", async () => {
    const fake = fakeQuotaDatabase({ paid: { total: 500, used: 12, images: true } });
    const usage = await getDeviceUsage("paid-device", "firebase-user", () => fake.database, async () => {});
    assert.equal(usage.paid, true);
    assert.equal(usage.subscription?.remaining, 488);
  });
});

test("different signed-in accounts on one device have independent free message balances", async () => {
  const fake = fakeQuotaDatabase();
  const prepare = async () => {};
  for (const uid of ["account-a", "account-b"]) {
    const reservation = await reserveMessageQuota(
      "same-phone",
      1,
      "chat",
      () => fake.database,
      prepare,
      "chat",
      uid
    );
    assert.equal(reservation.allowed, true);
    assert.equal(reservation.remaining, FREE_MESSAGE_LIMIT - 1);
    assert.equal(fake.getFreeUsed(`uid:${uid}`), 1);
  }
  assert.deepEqual(fake.getQuotaIdentities().sort(), ["uid:account-a", "uid:account-b"]);
});

test("a signed-in account shares its free message balance across devices", async () => {
  const fake = fakeQuotaDatabase();
  const prepare = async () => {};
  for (const deviceId of ["phone-a", "phone-b"]) {
    const reservation = await reserveMessageQuota(
      deviceId,
      1,
      "chat",
      () => fake.database,
      prepare,
      "chat",
      "account-a"
    );
    assert.equal(reservation.allowed, true);
  }
  assert.equal(fake.getFreeUsed("uid:account-a"), 2);
  assert.deepEqual(fake.getQuotaIdentities(), ["uid:account-a"]);
});

test("Firebase UID account queries bind a single typed parameter", async () => {
  const fake = fakeQuotaDatabase();
  const observedQueries: Array<{ queryText: string; values: unknown[] }> = [];
  const database: QuotaDatabase = {
    async query<T extends import("pg").QueryResultRow>(
      queryText: string,
      values: unknown[] = []
    ): Promise<{ rows: T[]; rowCount: number }> {
      observedQueries.push({ queryText, values });
      return fake.database.query<T>(queryText, values);
    },
  };
  const getDatabase = () => database;
  const prepare = async () => {};

  await reserveMessageQuota(
    "device-id",
    1,
    "chat",
    getDatabase,
    prepare,
    "chat",
    "firebase-user"
  );
  await getDeviceUsage("device-id", "firebase-user", getDatabase, prepare);

  const accountQueries = observedQueries.filter(({ queryText }) => queryText.includes("app_users"));
  assert.ok(accountQueries.length > 0);
  for (const { queryText, values } of accountQueries) {
    const placeholderNumbers = [...new Set(
      [...queryText.matchAll(/\$(\d+)/g)].map(([, number]) => Number(number))
    )].sort();
    assert.deepEqual(placeholderNumbers, [1]);
    assert.deepEqual(values, ["firebase-user"]);
  }
});

test("unknown feature names cannot select a quota", () => {
  for (const feature of Object.keys(FREE_USAGE_LIMITS)) {
    assert.equal(isUsageFeature(feature), true);
  }
  assert.equal(isUsageFeature("quiz"), false);
  assert.equal(isUsageFeature(undefined), false);
});

test("message operation costs match the subscription policy", () => {
  assert.deepEqual(MESSAGE_COSTS, {
    chat: 1,
    webSearch: 2,
    thinking: 2,
    searchThinking: 3,
    imageGeneration: 20,
    vision: 3,
  });
  assert.equal(messageCostForFeature("quran"), 0);
  assert.equal(messageCostForFeature("coder"), 1);
});

test("free users share a daily 20-message balance and cannot overspend", async () => {
  const fake = fakeQuotaDatabase();
  const prepare = async () => {};
  for (let index = 0; index < FREE_MESSAGE_LIMIT; index += 1) {
    const reservation = await reserveMessageQuota(
      "free-device",
      1,
      "chat",
      () => fake.database,
      prepare
    );
    assert.equal(reservation.allowed, true);
  }
  const exhausted = await reserveMessageQuota(
    "free-device",
    1,
    "chat",
    () => fake.database,
    prepare
  );
  assert.equal(exhausted.allowed, false);
  assert.equal(exhausted.used, 20);
  assert.equal(fake.getFreeUsed("free-device"), 20);
});

test("metered subscriptions deduct actual operation cost and reject insufficient balances", async () => {
  const fake = fakeQuotaDatabase({ paid: { total: 500, used: 0, images: false } });
  const prepare = async () => {};
  const search = await reserveMessageQuota(
    "paid-device",
    MESSAGE_COSTS.webSearch,
    "chat",
    () => fake.database,
    prepare,
    "web-search"
  );
  assert.equal(search.allowed, true);
  assert.equal(search.remaining, 498);
  assert.equal(fake.getServiceCount("web-search"), 1);

  const insufficient = await reserveMessageQuota(
    "paid-device",
    499,
    "chat",
    () => fake.database,
    prepare
  );
  assert.equal(insufficient.allowed, false);
  assert.equal(insufficient.remaining, 498);
  assert.equal(fake.getPaidUsed(), 2);
});

test("legacy subscribers stay unlimited and free users cannot generate images", async () => {
  const prepare = async () => {};
  const legacy = fakeQuotaDatabase({ legacy: true });
  const legacyChat = await reserveMessageQuota(
    "legacy-device",
    1,
    "chat",
    () => legacy.database,
    prepare
  );
  assert.equal(legacyChat.allowed, true);
  assert.equal(legacyChat.unlimited, true);
  assert.equal(legacyChat.legacy, true);

  const free = fakeQuotaDatabase();
  const image = await reserveMessageQuota(
    "free-device",
    MESSAGE_COSTS.imageGeneration,
    "image-generation",
    () => free.database,
    prepare
  );
  assert.equal(image.allowed, false);
  assert.equal(image.featureDisabled, true);
  assert.equal(free.getFreeUsed("free-device"), 0);
});
