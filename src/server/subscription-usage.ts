import type { Express, NextFunction, Request, Response } from "express";
import type { Pool, QueryResultRow } from "pg";
import { getDatabasePool } from "./rag.js";
import { ensureSubscriptionTables } from "./subscriptions.js";
import {
  RequestIdentityError,
  verifyRequestIdentity,
} from "./firebase-request-identity.js";

export const FREE_MESSAGE_LIMIT = 20;

export const MESSAGE_COSTS = {
  chat: 1,
  webSearch: 2,
  thinking: 2,
  searchThinking: 3,
  imageGeneration: 20,
  vision: 3,
} as const;

export const FREE_USAGE_LIMITS = {
  chat: FREE_MESSAGE_LIMIT,
  coder: FREE_MESSAGE_LIMIT,
  quran: 0,
  vision: FREE_MESSAGE_LIMIT,
  rag: FREE_MESSAGE_LIMIT,
} as const;

export type UsageFeature = keyof typeof FREE_USAGE_LIMITS;
type UsageRoute = (req: Request, res: Response, next: NextFunction) => void;
export interface QuotaDatabase {
  query<T extends QueryResultRow = QueryResultRow>(
    queryText: string,
    values?: unknown[]
  ): Promise<{ rows: T[]; rowCount: number | null }>;
}

type SubscriptionRow = QueryResultRow & {
  id: string;
  user_id: string;
  plan_name: string;
  name_ar: string;
  messages_total: number;
  messages_used: number;
  expires_at: Date;
  features: Record<string, boolean>;
};

type ReservationResult = QueryResultRow & {
  messages_used: number;
  messages_total?: number;
  remaining: number;
  reset_at: Date;
};

export type QuotaReservation = {
  allowed: boolean;
  unlimited: boolean;
  legacy: boolean;
  paid: boolean;
  feature: string;
  used: number;
  limit: number;
  remaining: number;
  resetAt: string;
  plan?: string;
  planName?: string;
  featureDisabled?: boolean;
  subscriptionId?: string;
};

export class QuotaIdentityConflictError extends Error {
  constructor() {
    super("This device is already linked to a different Firebase account.");
    this.name = "QuotaIdentityConflictError";
  }
}

let usageTableReady: Promise<void> | undefined;

export function isUsageFeature(value: unknown): value is UsageFeature {
  return typeof value === "string" && Object.hasOwn(FREE_USAGE_LIMITS, value);
}

export function messageCostForFeature(feature: UsageFeature): number {
  switch (feature) {
    case "chat":
    case "coder":
      return MESSAGE_COSTS.chat;
    case "vision":
      return MESSAGE_COSTS.vision;
    case "quran":
      return 0;
    case "rag":
      return MESSAGE_COSTS.chat;
  }
}

export async function ensureUsageTrackingTable(): Promise<void> {
  if (!usageTableReady) {
    usageTableReady = getDatabasePool().query(`
      CREATE TABLE IF NOT EXISTS usage_tracking (
        device_id VARCHAR(255) NOT NULL,
        feature VARCHAR(50) NOT NULL,
        count INTEGER NOT NULL DEFAULT 0 CHECK (count >= 0),
        date DATE NOT NULL DEFAULT CURRENT_DATE,
        PRIMARY KEY (device_id, feature, date)
      )
    `).then(async () => {
      await getDatabasePool().query(`
        CREATE TABLE IF NOT EXISTS subscription_service_usage (
          service_name VARCHAR(60) PRIMARY KEY,
          operations_count BIGINT NOT NULL DEFAULT 0 CHECK (operations_count >= 0),
          updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )
      `);
      await getDatabasePool().query(`
        CREATE TABLE IF NOT EXISTS quota_device_links (
          device_id VARCHAR(255) PRIMARY KEY,
          firebase_uid VARCHAR(128) NOT NULL,
          linked_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )
      `);
      await getDatabasePool().query(
        `INSERT INTO free_user_daily_usage (device_id, date, messages_used)
         SELECT device_id, date,
                LEAST($1, SUM(CASE
                  WHEN feature = 'chat' THEN count
                  WHEN feature = 'coder' THEN count
                  WHEN feature = 'vision' THEN count * $2
                  WHEN feature = 'rag' THEN count
                  ELSE 0
                END)::integer)
         FROM usage_tracking
         WHERE date = (now() AT TIME ZONE 'Africa/Cairo')::date
         GROUP BY device_id, date
         HAVING SUM(CASE
           WHEN feature = 'chat' THEN count
           WHEN feature = 'coder' THEN count
           WHEN feature = 'vision' THEN count * $2
           WHEN feature = 'rag' THEN count
           ELSE 0
         END) > 0
         ON CONFLICT (device_id, date) DO NOTHING`,
        [FREE_MESSAGE_LIMIT, MESSAGE_COSTS.vision]
      );
    }).catch((error: unknown) => {
      usageTableReady = undefined;
      throw error;
    });
  }
  await usageTableReady;
}

export async function assertAnonymousDeviceIsUnlinked(deviceId: string): Promise<void> {
  await prepareQuotaTables();
  const result = await getDatabasePool().query<QueryResultRow & { linked: boolean }>(
    "SELECT EXISTS (SELECT 1 FROM quota_device_links WHERE device_id = $1) AS linked",
    [deviceId]
  );
  if (result.rows[0]?.linked) throw new QuotaIdentityConflictError();
}

async function recordServiceUsage(db: QuotaDatabase, serviceName: string): Promise<void> {
  try {
    await db.query(
      `INSERT INTO subscription_service_usage (service_name, operations_count, updated_at)
       VALUES ($1, 1, now())
       ON CONFLICT (service_name) DO UPDATE
       SET operations_count = subscription_service_usage.operations_count + 1,
           updated_at = now()`,
      [serviceName]
    );
  } catch (error: unknown) {
    console.error(
      "Unable to record subscription service usage:",
      error instanceof Error ? error.name : "UnknownError"
    );
  }
}

function deviceIdFromRequest(req: Request): string | null {
  const candidate = req.body?.deviceId ?? req.get("x-h2-device-id");
  return typeof candidate === "string" &&
    candidate.length >= 1 &&
    candidate.length <= 255 &&
    !/[\u0000-\u001f\u007f]/u.test(candidate)
    ? candidate
    : null;
}

async function prepareQuotaTables(): Promise<void> {
  await ensureSubscriptionTables();
  await ensureUsageTrackingTable();
}

export async function reserveMessageQuota(
  deviceId: string,
  cost: number,
  feature: string,
  getDatabase: () => QuotaDatabase = getDatabasePool,
  prepare: () => Promise<void> = prepareQuotaTables,
  serviceName: string = feature,
  firebaseUid?: string
): Promise<QuotaReservation> {
  if (!Number.isInteger(cost) || cost < 0) {
    throw new RangeError("Quota cost must be a non-negative integer.");
  }
  if (cost === 0) {
    return {
      allowed: true,
      unlimited: true,
      legacy: false,
      paid: false,
      feature,
      used: 0,
      limit: 0,
      remaining: 0,
      resetAt: "",
    };
  }

  await prepare();
  const db = getDatabase();
  const quotaKey = firebaseUid ? `uid:${firebaseUid}` : deviceId;
  const accountFilter = firebaseUid
    ? "(au.device_id = 'uid:' || $2 OR au.firebase_uid = $2)"
    : "au.device_id = $1";
  const accountValues = firebaseUid ? [deviceId, firebaseUid] : [deviceId];

  await db.query(
    `UPDATE app_users
     SET is_legacy = false
     WHERE ${firebaseUid ? "(device_id = 'uid:' || $2 OR firebase_uid = $2)" : "device_id = $1"}
       AND is_legacy = true
       AND (subscription_end_date IS NULL OR subscription_end_date <= now())`,
    accountValues
  );

  const legacyResult = firebaseUid
    ? await db.query<QueryResultRow & { active: boolean }>(
      `SELECT EXISTS (
         SELECT 1 FROM app_users au
         WHERE ${accountFilter}
           AND au.is_legacy = true AND au.subscription_end_date > now()
       ) AS active`,
      accountValues
    )
    : await db.query<QueryResultRow & { active: boolean }>(
      `SELECT is_legacy AND subscription_end_date > now() AS active
       FROM app_users WHERE device_id = $1`,
      [deviceId]
    );
  if (legacyResult.rows[0]?.active === true) {
    await recordServiceUsage(db, serviceName);
    return {
      allowed: true,
      unlimited: true,
      legacy: true,
      paid: true,
      feature,
      used: 0,
      limit: 0,
      remaining: 0,
      resetAt: "",
    };
  }

  const subscriptionResult = await db.query<SubscriptionRow>(
    `SELECT us.id, us.user_id, sp.name AS plan_name, sp.name_ar,
            us.messages_total, us.messages_used, us.expires_at, sp.features
     FROM user_subscriptions us
     JOIN app_users au ON au.id = us.user_id
     JOIN subscription_plans sp ON sp.id = us.plan_id
     WHERE ${accountFilter} AND us.status = 'active'
       AND us.starts_at <= now() AND us.expires_at > now()
     ORDER BY us.starts_at DESC, us.created_at DESC
     LIMIT 1`,
    accountValues
  );
  const activeSubscription = subscriptionResult.rows[0];

  if (activeSubscription) {
    const featureAllowed = feature !== "image-generation" ||
      activeSubscription.features.images === true;
    if (!featureAllowed) {
      return {
        allowed: false,
        unlimited: false,
        legacy: false,
        paid: true,
        feature,
        used: activeSubscription.messages_used,
        limit: activeSubscription.messages_total,
        remaining: Math.max(0, activeSubscription.messages_total - activeSubscription.messages_used),
        resetAt: activeSubscription.expires_at.toISOString(),
        plan: activeSubscription.plan_name,
        planName: activeSubscription.name_ar,
        subscriptionId: activeSubscription.id,
        featureDisabled: true,
      };
    }

    const charged = await db.query<ReservationResult>(
      `UPDATE user_subscriptions
       SET messages_used = messages_used + $2
       WHERE id = $1 AND status = 'active'
         AND starts_at <= now() AND expires_at > now()
         AND messages_total - messages_used >= $2
       RETURNING messages_used, messages_total,
                 messages_total - messages_used AS remaining,
                 expires_at AS reset_at`,
      [activeSubscription.id, cost]
    );
    if (charged.rows[0]) {
      await recordServiceUsage(db, serviceName);
      return {
        allowed: true,
        unlimited: false,
        legacy: false,
        paid: true,
        feature,
        used: charged.rows[0].messages_used,
        limit: charged.rows[0].messages_total ?? activeSubscription.messages_total,
        remaining: charged.rows[0].remaining,
        resetAt: charged.rows[0].reset_at.toISOString(),
        plan: activeSubscription.plan_name,
        planName: activeSubscription.name_ar,
        subscriptionId: activeSubscription.id,
      };
    }
    const latest = await db.query<ReservationResult>(
      `SELECT messages_used, messages_total,
              GREATEST(messages_total - messages_used, 0) AS remaining,
              expires_at AS reset_at
      FROM user_subscriptions
      WHERE id = $1 AND status = 'active' AND expires_at > now()`,
      [activeSubscription.id]
    );
    const exhausted = latest.rows[0];
    if (exhausted) {
      return {
       allowed: false,
       unlimited: false,
       legacy: false,
       paid: true,
       feature,
       used: exhausted.messages_used,
       limit: exhausted.messages_total,
       remaining: exhausted.remaining,
       resetAt: exhausted.reset_at.toISOString(),
       plan: activeSubscription.plan_name,
       planName: activeSubscription.name_ar,
       subscriptionId: activeSubscription.id,
      };
    }
  }

  if (feature === "image-generation") {
    return {
      allowed: false,
      unlimited: false,
      legacy: false,
      paid: false,
      feature,
      used: 0,
      limit: FREE_MESSAGE_LIMIT,
      remaining: FREE_MESSAGE_LIMIT,
      resetAt: "",
      featureDisabled: true,
    };
  }

  const freeResult = await db.query<ReservationResult>(
    `INSERT INTO free_user_daily_usage (device_id, date, messages_used)
     VALUES ($1, (now() AT TIME ZONE 'Africa/Cairo')::date, $2)
     ON CONFLICT (device_id, date)
     DO UPDATE SET messages_used = free_user_daily_usage.messages_used + EXCLUDED.messages_used
     WHERE free_user_daily_usage.messages_used + EXCLUDED.messages_used <= $3
     RETURNING messages_used, $3::integer AS messages_total,
               $3 - messages_used AS remaining,
               (((now() AT TIME ZONE 'Africa/Cairo')::date + 1)::timestamp
                 AT TIME ZONE 'Africa/Cairo') AS reset_at`,
    [quotaKey, cost, FREE_MESSAGE_LIMIT]
  );
  if (freeResult.rows[0]) {
    await recordServiceUsage(db, serviceName);
    return {
      allowed: true,
      unlimited: false,
      legacy: false,
      paid: false,
      feature,
      used: freeResult.rows[0].messages_used,
      limit: FREE_MESSAGE_LIMIT,
      remaining: freeResult.rows[0].remaining,
      resetAt: freeResult.rows[0].reset_at.toISOString(),
    };
  }

  const current = await db.query<QueryResultRow & { messages_used: number; reset_at: Date }>(
    `SELECT messages_used,
            (((now() AT TIME ZONE 'Africa/Cairo')::date + 1)::timestamp
              AT TIME ZONE 'Africa/Cairo') AS reset_at
     FROM free_user_daily_usage
     WHERE device_id = $1 AND date = (now() AT TIME ZONE 'Africa/Cairo')::date`,
    [quotaKey]
  );
  const used = current.rows[0]?.messages_used ?? FREE_MESSAGE_LIMIT;
  return {
    allowed: false,
    unlimited: false,
    legacy: false,
    paid: false,
    feature,
    used,
    limit: FREE_MESSAGE_LIMIT,
    remaining: Math.max(0, FREE_MESSAGE_LIMIT - used),
    resetAt: current.rows[0]?.reset_at.toISOString() ?? "",
  };
}

export async function refundMessageQuota(
  quotaKey: string,
  reservation: QuotaReservation,
  cost: number
): Promise<void> {
  if (!reservation.allowed || reservation.unlimited || reservation.legacy || cost === 0) return;
  if (reservation.paid) {
    if (!reservation.subscriptionId) return;
    await getDatabasePool().query(
      `UPDATE user_subscriptions us
       SET messages_used = GREATEST(messages_used - $2, 0)
       WHERE us.id = $1 AND us.status = 'active'
         AND us.expires_at > now() AND us.messages_used >= $2`,
      [reservation.subscriptionId, cost]
    );
    return;
  }
  await getDatabasePool().query(
    `UPDATE free_user_daily_usage
     SET messages_used = GREATEST(messages_used - $3, 0)
     WHERE device_id = $1
       AND date = (now() AT TIME ZONE 'Africa/Cairo')::date
       AND messages_used >= $2`,
    [quotaKey, cost]
  );
}

function attachFailureRefund(
  res: Response,
  quotaKey: string,
  reservation: QuotaReservation,
  cost: number
): void {
  res.once("finish", () => {
    if (res.statusCode < 400) return;
    void refundMessageQuota(quotaKey, reservation, cost).catch((error: unknown) => {
      console.error(
        "Unable to refund a failed quota reservation:",
        error instanceof Error ? error.name : "UnknownError"
      );
    });
  });
}

export function sendQuotaExceeded(
  res: Response,
  reservation: QuotaReservation,
  cost: number
): void {
  if (reservation.featureDisabled) {
    res.status(403).json({
      error: "ميزة توليد الصور غير متاحة في خطتك الحالية. اختر خطة أساسي أو أعلى.",
      feature: reservation.feature,
      required: cost,
      remaining: reservation.remaining,
    });
    return;
  }
  const isFree = !reservation.paid;
  const message = isFree
    ? `استهلكت رسائلك اليومية (${FREE_MESSAGE_LIMIT}). تتجدد عند منتصف الليل بتوقيت مصر. اشترك للحصول على رسائل أكثر.`
    : `الرصيد لا يكفي. تحتاج ${cost} رسالة، والمتبقي ${reservation.remaining}. جدّد اشتراكك أو اختر خطة أعلى.`;
  res.status(429).json({
    error: message,
    feature: reservation.feature,
    used: reservation.used,
    limit: reservation.limit,
    required: cost,
    remaining: reservation.remaining,
    paid: reservation.paid,
    plan: reservation.plan,
    resetAt: reservation.resetAt,
  });
}

export function freeUsageQuota(
  feature: UsageFeature | ((req: Request) => UsageFeature),
  cost: number | ((req: Request) => number) = (req) => messageCostForFeature(
    typeof feature === "function" ? feature(req) : feature
  ),
  serviceName: string | ((req: Request) => string) = (req) =>
    typeof feature === "function" ? feature(req) : feature
): UsageRoute {
  return (req, res, next) => {
    void (async () => {
      const selectedFeature = typeof feature === "function" ? feature(req) : feature;
      const selectedCost = typeof cost === "function" ? cost(req) : cost;
      const selectedServiceName = typeof serviceName === "function" ? serviceName(req) : serviceName;
      if (!Number.isInteger(selectedCost) || selectedCost < 0) {
        res.status(500).json({ error: "تعذر تحديد تكلفة العملية." });
        return;
      }
      if (!selectedServiceName || selectedServiceName.length > 60) {
        res.status(500).json({ error: "تعذر تحديد الخدمة للاستخدام." });
        return;
      }
      let identity: Awaited<ReturnType<typeof verifyRequestIdentity>>;
      try {
        identity = await verifyRequestIdentity(req);
      } catch (error: unknown) {
        if (error instanceof RequestIdentityError) {
          res.status(401).json({ error: "يلزم تحديث التطبيق أو تسجيل الدخول مجدداً للتحقق من أمان الاتصال." });
          return;
        }
        throw error;
      }
      if (selectedCost === 0) {
        next();
        return;
      }
      const deviceId = deviceIdFromRequest(req);
      if (!deviceId) {
        res.status(400).json({ error: "تعذر تحديد جهازك. حدّث التطبيق وحاول مرة أخرى." });
        return;
      }
      if (!identity.uid) await assertAnonymousDeviceIsUnlinked(deviceId);
      const quotaKey = identity.uid ? `uid:${identity.uid}` : deviceId;
      const reservation = await reserveMessageQuota(
        deviceId,
        selectedCost,
        selectedFeature,
        getDatabasePool,
        prepareQuotaTables,
        selectedServiceName,
        identity.uid ?? undefined
      );
      if (!reservation.allowed) {
        sendQuotaExceeded(res, reservation, selectedCost);
        return;
      }
      attachFailureRefund(res, quotaKey, reservation, selectedCost);
      next();
    })().catch((error: unknown) => {
      if (error instanceof QuotaIdentityConflictError) {
        res.status(409).json({ error: "هذا الجهاز مرتبط بحساب آخر. سجّل الدخول بالحساب المرتبط به." });
        return;
      }
      console.error(
        "Unable to enforce message quota:",
        error instanceof Error ? error.name : "UnknownError"
      );
      if (!res.headersSent) {
        res.status(503).json({ error: "تعذر التحقق من رصيد الرسائل الآن. حاول مرة أخرى لاحقاً." });
      } else {
        next(error);
      }
    });
  };
}

export async function getDeviceUsage(
  deviceId: string,
  firebaseUid?: string,
  getDatabase: () => QuotaDatabase = getDatabasePool,
  prepare: () => Promise<void> = prepareQuotaTables
): Promise<{
  paid: boolean;
  legacy: boolean;
  subscription: null | {
    plan: string;
    nameAr: string;
    used: number;
    limit: number;
    remaining: number;
    expiresAt: string;
    features: Record<string, boolean>;
  };
  usage: Record<UsageFeature, { used: number; limit: number }>;
}> {
  await prepare();
  const db = getDatabase();
  const quotaKey = firebaseUid ? `uid:${firebaseUid}` : deviceId;
  const accountFilter = firebaseUid
    ? "(au.device_id = 'uid:' || $2 OR au.firebase_uid = $2)"
    : "au.device_id = $1";
  const accountValues = firebaseUid ? [deviceId, firebaseUid] : [deviceId];
  await db.query(
    `UPDATE app_users
     SET is_legacy = false
     WHERE ${firebaseUid ? "(device_id = 'uid:' || $2 OR firebase_uid = $2)" : "device_id = $1"}
       AND is_legacy = true
       AND (subscription_end_date IS NULL OR subscription_end_date <= now())`,
    accountValues
  );
  const [legacyResult, planResult, freeResult] = await Promise.all([
    db.query<QueryResultRow & { active: boolean }>(
      `SELECT EXISTS (
         SELECT 1 FROM app_users au
        WHERE ${accountFilter}
           AND au.is_legacy = true AND au.subscription_end_date > now()
       ) AS active`,
      accountValues
    ),
    db.query<QueryResultRow & {
      plan_name: string;
      name_ar: string;
      messages_total: number;
      messages_used: number;
      expires_at: Date;
      features: Record<string, boolean>;
    }>(
      `SELECT sp.name AS plan_name, sp.name_ar, us.messages_total, us.messages_used,
              us.expires_at, sp.features
       FROM user_subscriptions us
       JOIN app_users au ON au.id = us.user_id
       JOIN subscription_plans sp ON sp.id = us.plan_id
       WHERE ${accountFilter} AND us.status = 'active'
         AND us.starts_at <= now() AND us.expires_at > now()
       ORDER BY us.starts_at DESC, us.created_at DESC
       LIMIT 1`,
      accountValues
    ),
    db.query<QueryResultRow & { messages_used: number; reset_at: Date }>(
      `SELECT messages_used,
              (((now() AT TIME ZONE 'Africa/Cairo')::date + 1)::timestamp
                AT TIME ZONE 'Africa/Cairo') AS reset_at
       FROM free_user_daily_usage
       WHERE device_id = $1 AND date = (now() AT TIME ZONE 'Africa/Cairo')::date`,
      [quotaKey]
    ),
  ]);

  const legacy = legacyResult.rows[0]?.active === true;
  const plan = planResult.rows[0];
  const freeUsed = freeResult.rows[0]?.messages_used ?? 0;
  const used = legacy ? 0 : plan?.messages_used ?? freeUsed;
  const limit = legacy ? 0 : plan?.messages_total ?? FREE_MESSAGE_LIMIT;
  const remaining = legacy ? 0 : Math.max(0, limit - used);
  const defaultResetAt = await db.query<QueryResultRow & { reset_at: Date }>(
    `SELECT (((now() AT TIME ZONE 'Africa/Cairo')::date + 1)::timestamp
              AT TIME ZONE 'Africa/Cairo') AS reset_at`
  );
  const resetAt = plan?.expires_at ?? freeResult.rows[0]?.reset_at ?? defaultResetAt.rows[0].reset_at;
  const featureUsage = { used, limit };
  return {
    paid: legacy || plan !== undefined,
    legacy,
    subscription: plan ? {
      plan: plan.plan_name,
      nameAr: plan.name_ar,
      used: plan.messages_used,
      limit: plan.messages_total,
      remaining: Math.max(0, plan.messages_total - plan.messages_used),
      expiresAt: plan.expires_at.toISOString(),
      features: plan.features,
    } : null,
    usage: {
      chat: featureUsage,
      coder: featureUsage,
      quran: { used: 0, limit: 0 },
      vision: featureUsage,
      rag: featureUsage,
    },
    ...(resetAt ? { resetAt: resetAt.toISOString() } : {}),
  };
}

export function registerFeatureQuota(app: Express): void {
  app.get(
    "/api/subscription/usage",
    (req: Request, res: Response, next: NextFunction) => {
      const deviceId = typeof req.query.deviceId === "string"
        ? req.query.deviceId
        : req.get("x-h2-device-id");
      if (
        typeof deviceId !== "string" ||
        deviceId.length < 1 ||
        deviceId.length > 255 ||
        /[\u0000-\u001f\u007f]/u.test(deviceId)
      ) {
        res.status(400).json({ error: "معرّف الجهاز غير صالح." });
        return;
      }
      void (async () => {
        const identity = await verifyRequestIdentity(req);
        if (!identity.uid) await assertAnonymousDeviceIsUnlinked(deviceId);
        const usage = await getDeviceUsage(deviceId, identity.uid ?? undefined);
        res.json(usage);
      })().catch((error: unknown) => {
        if (error instanceof RequestIdentityError) {
          res.status(401).json({ error: "يلزم تحديث التطبيق أو تسجيل الدخول مجدداً للتحقق من أمان الاتصال." });
          return;
        }
        if (error instanceof QuotaIdentityConflictError) {
          res.status(409).json({ error: "هذا الجهاز مرتبط بحساب آخر. سجّل الدخول بالحساب المرتبط به." });
          return;
        }
        console.error(
          "Unable to load device subscription usage:",
          error instanceof Error ? error.name : "UnknownError"
        );
        if (!res.headersSent) {
          res.status(503).json({ error: "تعذر تحميل رصيد الرسائل الآن." });
        } else {
          next(error);
        }
      });
    }
  );
}
