import { randomInt, randomUUID } from "node:crypto";
import type { Express, NextFunction, Request, Response } from "express";
import multer from "multer";
import rateLimit from "express-rate-limit";
import { getDatabasePool } from "./rag.js";
import {
  QuotaIdentityConflictError,
  assertAnonymousDeviceIsUnlinked,
} from "./subscription-usage.js";
import {
  RequestIdentityError,
  type VerifiedRequestIdentity,
  verifyRequestIdentity,
} from "./firebase-request-identity.js";

const PLAN_DETAILS = {
  monthly: { amount: 50, days: 30 },
  quarterly: { amount: 130, days: 90 },
  yearly: { amount: 400, days: 365 },
} as const;

type SubscriptionPlan = keyof typeof PLAN_DETAILS;
export const SUBSCRIPTION_PLAN_CATALOG = [
  {
    name: "trial",
    nameAr: "تجريبي",
    price: 100,
    durationDays: 30,
    messages: 500,
    features: { chat: true, search: true, thinking: true, vision: true, images: false, priority: false, support: false },
  },
  {
    name: "basic",
    nameAr: "أساسي",
    price: 1_000,
    durationDays: 30,
    messages: 5_000,
    features: { chat: true, search: true, thinking: true, vision: true, images: true, priority: false, support: false },
  },
  {
    name: "premium",
    nameAr: "مميز",
    price: 2_000,
    durationDays: 30,
    messages: 15_000,
    features: { chat: true, search: true, thinking: true, vision: true, images: true, priority: true, support: false },
  },
  {
    name: "annual",
    nameAr: "سنوي",
    price: 10_000,
    durationDays: 365,
    messages: 100_000,
    features: { chat: true, search: true, thinking: true, vision: true, images: true, priority: true, support: true },
  },
  {
    name: "annual_premium",
    nameAr: "سنوي مميز",
    price: 20_000,
    durationDays: 365,
    messages: 300_000,
    features: { chat: true, search: true, thinking: true, vision: true, images: true, priority: true, support: true, vip: true },
  },
] as const;

type AdminAuthorizer = (req: Request, res: Response) => string | null;

const CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const PHONE_PATTERN = /^01[0125]\d{8}$/u;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu;
const subscriptionIdentities = new WeakMap<Request, VerifiedRequestIdentity>();

export function subscriptionAccountId(firebaseUid: string): string {
  return `uid:${firebaseUid}`;
}
let schemaReady: Promise<void> | undefined;

const screenshotUpload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: 5 * 1024 * 1024, files: 1, fields: 3 },
});
const subscriptionRateLimit = rateLimit({
  windowMs: 15 * 60 * 1000,
  limit: 30,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: "تم تجاوز عدد محاولات الاشتراك. حاول مرة أخرى بعد 15 دقيقة." },
});

export function getSubscriptionPlan(plan: unknown): { plan: SubscriptionPlan; amount: number; days: number } | null {
  if (typeof plan !== "string" || !Object.hasOwn(PLAN_DETAILS, plan)) return null;
  const key = plan as SubscriptionPlan;
  return { plan: key, ...PLAN_DETAILS[key] };
}

type PaymentPlan = {
  plan: string;
  amount: number;
  days: number;
  messages: number | null;
  legacy: boolean;
};

function getPaymentPlan(plan: unknown): PaymentPlan | null {
  const legacy = getSubscriptionPlan(plan);
  if (legacy) return { ...legacy, messages: null, legacy: true };
  if (typeof plan !== "string") return null;
  const catalogPlan = SUBSCRIPTION_PLAN_CATALOG.find((item) => item.name === plan);
  if (!catalogPlan) return null;
  return {
    plan: catalogPlan.name,
    amount: catalogPlan.price,
    days: catalogPlan.durationDays,
    messages: catalogPlan.messages,
    legacy: false,
  };
}

export function normalizeEgyptianMobile(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const localizedDigits = value.replace(/[٠-٩]/gu, (digit) =>
    String(digit.charCodeAt(0) - "٠".charCodeAt(0))
  );
  if (!/^[\d\s()+-]+$/u.test(localizedDigits)) return null;
  let digits = localizedDigits.replace(/\D/gu, "");
  if (digits.startsWith("0020")) digits = digits.slice(4);
  else if (digits.startsWith("20")) digits = digits.slice(2);
  if (digits.length === 10 && digits.startsWith("1")) digits = `0${digits}`;
  return PHONE_PATTERN.test(digits) ? digits : null;
}

export function identifyScreenshot(bytes: Buffer): "image/jpeg" | "image/png" | "image/webp" | null {
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) {
    return "image/jpeg";
  }
  if (
    bytes.length >= 8 &&
    bytes.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))
  ) {
    return "image/png";
  }
  if (
    bytes.length >= 12 &&
    bytes.toString("ascii", 0, 4) === "RIFF" &&
    bytes.toString("ascii", 8, 12) === "WEBP"
  ) {
    return "image/webp";
  }
  return null;
}

function makeCode(length: number): string {
  return Array.from({ length }, () => CODE_ALPHABET[randomInt(CODE_ALPHABET.length)]).join("");
}

function makeUserCode(): string {
  const code = makeCode(8);
  return `H2U-${code.slice(0, 4)}-${code.slice(4)}`;
}

function makePaymentCode(): string {
  return `H2-${makeCode(4)}-${makeCode(4)}`;
}

function isValidDeviceId(value: unknown): value is string {
  return typeof value === "string" && value.length >= 1 && value.length <= 255 &&
    !/[\u0000-\u001f\u007f]/u.test(value);
}

export async function ensureSubscriptionTables(): Promise<void> {
  if (!schemaReady) {
    schemaReady = (async () => {
      const pool = getDatabasePool();
      await pool.query(`
        CREATE TABLE IF NOT EXISTS app_users (
          id UUID PRIMARY KEY,
          device_id VARCHAR(255) UNIQUE NOT NULL,
          user_code VARCHAR(13) UNIQUE NOT NULL,
          vodafone_number VARCHAR(20),
          display_name VARCHAR(100),
          subscription_start_date TIMESTAMPTZ,
          subscription_end_date TIMESTAMPTZ,
          subscription_plan VARCHAR(20)
            CHECK (subscription_plan IS NULL OR subscription_plan IN ('monthly', 'quarterly', 'yearly')),
          created_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )
      `);
      await pool.query(`
        ALTER TABLE app_users
        ADD COLUMN IF NOT EXISTS is_legacy BOOLEAN NOT NULL DEFAULT false
      `);
      await pool.query(`
        ALTER TABLE app_users
        ADD COLUMN IF NOT EXISTS firebase_uid VARCHAR(128)
      `);
      await pool.query(`
        CREATE INDEX IF NOT EXISTS app_users_firebase_uid_idx
        ON app_users (firebase_uid)
        WHERE firebase_uid IS NOT NULL
      `);
      await pool.query(`
        UPDATE app_users
        SET is_legacy = true
        WHERE subscription_end_date > now()
          AND subscription_plan IN ('monthly', 'quarterly', 'yearly')
          AND is_legacy = false
      `);
      await pool.query(`
        UPDATE app_users
        SET is_legacy = false
        WHERE is_legacy = true
          AND (subscription_end_date IS NULL OR subscription_end_date <= now())
      `);
      await pool.query(`
        CREATE TABLE IF NOT EXISTS subscription_requests (
          id UUID PRIMARY KEY,
          user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
          device_id VARCHAR(255) NOT NULL,
          plan VARCHAR(50) NOT NULL CHECK (plan IN (
            'monthly', 'quarterly', 'yearly',
            'trial', 'basic', 'premium', 'annual', 'annual_premium'
          )),
          amount INTEGER NOT NULL CHECK (amount > 0),
          unique_code VARCHAR(20) UNIQUE NOT NULL,
          vodafone_number VARCHAR(20),
          screenshot_data BYTEA,
          screenshot_mime_type VARCHAR(30),
          status VARCHAR(20) NOT NULL DEFAULT 'draft'
            CHECK (status IN ('draft', 'pending', 'approved', 'rejected')),
          rejection_reason TEXT,
          created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
          approved_at TIMESTAMPTZ
        )
      `);
      await pool.query(`
        DO $$
        BEGIN
          ALTER TABLE subscription_requests
          DROP CONSTRAINT IF EXISTS subscription_requests_plan_check;
          ALTER TABLE subscription_requests
          ADD CONSTRAINT subscription_requests_plan_check
          CHECK (plan IN (
            'monthly', 'quarterly', 'yearly',
            'trial', 'basic', 'premium', 'annual', 'annual_premium'
          ));
        END;
        $$
      `);
      await pool.query(`
        CREATE TABLE IF NOT EXISTS subscription_plans (
          id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
          name VARCHAR(50) NOT NULL UNIQUE,
          name_ar VARCHAR(100) NOT NULL,
          price INTEGER NOT NULL CHECK (price >= 0),
          duration_days INTEGER NOT NULL CHECK (duration_days > 0),
          messages INTEGER NOT NULL CHECK (messages >= 0),
          features JSONB NOT NULL DEFAULT '{}'::jsonb,
          is_active BOOLEAN NOT NULL DEFAULT true,
          created_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )
      `);
      await pool.query(`
        CREATE TABLE IF NOT EXISTS user_subscriptions (
          id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
          user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
          plan_id UUID NOT NULL REFERENCES subscription_plans(id),
          messages_total INTEGER NOT NULL CHECK (messages_total >= 0),
          messages_used INTEGER NOT NULL DEFAULT 0 CHECK (messages_used >= 0),
          starts_at TIMESTAMPTZ NOT NULL DEFAULT now(),
          expires_at TIMESTAMPTZ NOT NULL,
          status VARCHAR(20) NOT NULL DEFAULT 'active',
          created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
          CHECK (messages_used <= messages_total)
        )
      `);
      await pool.query(`
        CREATE INDEX IF NOT EXISTS user_subscriptions_user_active_idx
        ON user_subscriptions (user_id, expires_at DESC)
        WHERE status = 'active'
      `);
      await pool.query(`
        CREATE TABLE IF NOT EXISTS free_user_daily_usage (
          id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
          device_id VARCHAR(255) NOT NULL,
          date DATE NOT NULL DEFAULT CURRENT_DATE,
          messages_used INTEGER NOT NULL DEFAULT 0 CHECK (messages_used >= 0),
          UNIQUE (device_id, date)
        )
      `);
      for (const plan of SUBSCRIPTION_PLAN_CATALOG) {
        await pool.query(
          `INSERT INTO subscription_plans
             (name, name_ar, price, duration_days, messages, features, is_active)
           VALUES ($1, $2, $3, $4, $5, $6::jsonb, true)
           ON CONFLICT (name) DO UPDATE SET
             name_ar = EXCLUDED.name_ar,
             price = EXCLUDED.price,
             duration_days = EXCLUDED.duration_days,
             messages = EXCLUDED.messages,
             features = EXCLUDED.features,
             is_active = true`,
          [
            plan.name,
            plan.nameAr,
            plan.price,
            plan.durationDays,
            plan.messages,
            JSON.stringify(plan.features),
          ]
        );
      }
      await pool.query(`
        CREATE INDEX IF NOT EXISTS subscription_requests_status_created_idx
        ON subscription_requests (status, created_at DESC)
      `);
      await pool.query(`
        CREATE INDEX IF NOT EXISTS subscription_requests_user_created_idx
        ON subscription_requests (user_id, created_at DESC)
      `);
    })();
  }
  try {
    await schemaReady;
  } catch (error) {
    schemaReady = undefined;
    throw error;
  }
}

function sendDatabaseError(res: Response, action: string, error: unknown): void {
  console.error(`Subscription ${action} failed:`, error instanceof Error ? error.name : "UnknownError");
  res.status(503).json({ error: "تعذر إكمال طلب الاشتراك الآن. حاول مرة أخرى لاحقاً." });
}

function requireDeviceId(value: unknown, res: Response): string | null {
  if (!isValidDeviceId(value)) {
    res.status(400).json({ error: "معرّف الجهاز غير صالح." });
    return null;
  }
  return value;
}

function requireAccountId(
  req: Request,
  res: Response
): { accountId: string; firebaseUid: string | null } | null {
  const identity = subscriptionIdentities.get(req);
  if (identity?.uid) {
    return {
      accountId: subscriptionAccountId(identity.uid),
      firebaseUid: identity.uid,
    };
  }
  const candidate = req.body?.deviceId ??
    (typeof req.query.deviceId === "string" ? req.query.deviceId : undefined) ??
    req.get("x-h2-device-id");
  const accountId = requireDeviceId(candidate, res);
  return accountId ? { accountId, firebaseUid: null } : null;
}

function asyncRoute(
  action: string,
  handler: (req: Request, res: Response) => Promise<void>
): (req: Request, res: Response, next: NextFunction) => void {
  return (req, res, next) => {
    void handler(req, res).catch((error: unknown) => {
      if (res.headersSent) {
        next(error);
        return;
      }
      sendDatabaseError(res, action, error);
    });
  };
}

function handleScreenshotUpload(req: Request, res: Response, next: NextFunction): void {
  screenshotUpload.single("file")(req, res, (error: unknown) => {
    if (!error) {
      next();
      return;
    }
    if (error instanceof multer.MulterError) {
      const status = error.code === "LIMIT_FILE_SIZE" ? 413 : 400;
      res.status(status).json({
        error: error.code === "LIMIT_FILE_SIZE"
          ? "حجم صورة التحويل يجب ألا يتجاوز 5 ميجابايت."
          : "تعذر قراءة صورة التحويل. اختر صورة واحدة وحاول مرة أخرى.",
      });
      return;
    }
    console.error("Subscription screenshot upload failed:", error instanceof Error ? error.name : "UnknownError");
    res.status(400).json({ error: "تعذر قراءة صورة التحويل." });
  });
}

export function registerSubscriptionRoutes(app: Express, requireAdmin: AdminAuthorizer): void {
  app.use("/api/subscription", subscriptionRateLimit);
  app.use("/api/subscription", (req, res, next) => {
    void (async () => {
      const identity = await verifyRequestIdentity(req);
      subscriptionIdentities.set(req, identity);
      const candidate = req.body?.deviceId ??
        (typeof req.query.deviceId === "string" ? req.query.deviceId : undefined) ??
        req.get("x-h2-device-id");
      if (!identity.uid && isValidDeviceId(candidate)) {
        await assertAnonymousDeviceIsUnlinked(candidate);
      }
      next();
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
        "Subscription request verification failed:",
        error instanceof Error ? error.name : "UnknownError"
      );
      res.status(503).json({ error: "تعذر التحقق من أمان طلب الاشتراك الآن." });
    });
  });

  app.get("/api/subscription/plans", asyncRoute("plan listing", async (_req, res) => {
    await ensureSubscriptionTables();
    const result = await getDatabasePool().query<{
      name: string;
      name_ar: string;
      price: number;
      duration_days: number;
      messages: number;
      features: Record<string, boolean>;
    }>(
      `SELECT name, name_ar, price, duration_days, messages, features
       FROM subscription_plans
       WHERE is_active = true
       ORDER BY CASE name
         WHEN 'trial' THEN 1
         WHEN 'basic' THEN 2
         WHEN 'premium' THEN 3
         WHEN 'annual' THEN 4
         WHEN 'annual_premium' THEN 5
         ELSE 6
       END, name`
    );
    res.json({
      plans: result.rows.map((plan) => ({
        name: plan.name,
        nameAr: plan.name_ar,
        price: plan.price,
        currency: "EGP",
        durationDays: plan.duration_days,
        messages: plan.messages,
        features: plan.features,
      })),
    });
  }));

  app.post("/api/subscription/register", asyncRoute("registration", async (req, res) => {
    const account = requireAccountId(req, res);
    if (!account) return;
    const { accountId, firebaseUid } = account;
    const phone = normalizeEgyptianMobile(req.body?.vodafoneNumber);
    if (!phone) {
      res.status(400).json({ error: "أدخل رقم موبايل مصري صحيحاً (مثل 01012345678)." });
      return;
    }
    await ensureSubscriptionTables();
    const pool = getDatabasePool();
    const existing = await pool.query<{ id: string; user_code: string }>(
      `SELECT id, user_code FROM app_users
       WHERE device_id = $1 OR ($2::varchar IS NOT NULL AND firebase_uid = $2)
       ORDER BY CASE WHEN firebase_uid = $2 THEN 0 ELSE 1 END,
                subscription_end_date DESC NULLS LAST, created_at ASC
       LIMIT 1`,
      [accountId, firebaseUid]
    );
    if (existing.rowCount) {
      await pool.query(
        "UPDATE app_users SET vodafone_number = $2 WHERE id = $1",
        [existing.rows[0].id, phone]
      );
      res.json({ userCode: existing.rows[0].user_code });
      return;
    }
    for (let attempt = 0; attempt < 5; attempt += 1) {
      try {
        const result = await pool.query<{ user_code: string }>(
          `INSERT INTO app_users (id, device_id, user_code, vodafone_number, firebase_uid)
           VALUES ($1, $2, $3, $4, $5)
           ON CONFLICT (device_id) DO UPDATE SET vodafone_number = EXCLUDED.vodafone_number
           RETURNING user_code`,
          [randomUUID(), accountId, makeUserCode(), phone, firebaseUid]
        );
        res.status(201).json({ userCode: result.rows[0].user_code });
        return;
      } catch (error) {
        if (
          attempt < 4 &&
          error !== null &&
          typeof error === "object" &&
          "code" in error &&
          error.code === "23505"
        ) continue;
        throw error;
      }
    }
    throw new Error("Unable to generate a unique subscription user code.");
  }));

  app.get("/api/subscription/status", asyncRoute("status lookup", async (req, res) => {
    const account = requireAccountId(req, res);
    if (!account) return;
    const { accountId, firebaseUid } = account;
    await ensureSubscriptionTables();
    await getDatabasePool().query(
      `UPDATE app_users
       SET is_legacy = false
       WHERE (device_id = $1 OR ($2::varchar IS NOT NULL AND firebase_uid = $2))
         AND is_legacy = true
         AND (subscription_end_date IS NULL OR subscription_end_date <= now())`,
      [accountId, firebaseUid]
    );
    type AccountUserRow = {
      id: string;
      user_code: string;
      vodafone_number: string | null;
      display_name: string | null;
      subscription_plan: SubscriptionPlan | null;
      subscription_start_date: Date | null;
      subscription_end_date: Date | null;
      is_legacy: boolean;
    };
    let userResult = await getDatabasePool().query<AccountUserRow>(
      `SELECT id, user_code, vodafone_number, display_name, subscription_plan,
              subscription_start_date, subscription_end_date, is_legacy
       FROM app_users
       WHERE device_id = $1 OR ($2::varchar IS NOT NULL AND firebase_uid = $2)
       ORDER BY CASE WHEN firebase_uid = $2 THEN 0 ELSE 1 END,
                subscription_end_date DESC NULLS LAST, created_at ASC
       LIMIT 1`,
      [accountId, firebaseUid]
    );
    if (!userResult.rowCount && firebaseUid) {
       await getDatabasePool().query(
         `INSERT INTO app_users (id, device_id, user_code, firebase_uid)
          VALUES ($1, $2, $3, $4)
          ON CONFLICT (device_id) DO NOTHING`,
         [randomUUID(), accountId, makeUserCode(), firebaseUid]
       );
       userResult = await getDatabasePool().query<AccountUserRow>(
         `SELECT id, user_code, vodafone_number, display_name, subscription_plan,
                 subscription_start_date, subscription_end_date, is_legacy
          FROM app_users
          WHERE device_id = $1 OR ($2::varchar IS NOT NULL AND firebase_uid = $2)
          ORDER BY CASE WHEN firebase_uid = $2 THEN 0 ELSE 1 END,
                   subscription_end_date DESC NULLS LAST, created_at ASC
          LIMIT 1`,
         [accountId, firebaseUid]
       );
    }
    if (!userResult.rowCount) {
      res.status(404).json({ error: "سجّل رقم الموبايل أولاً لبدء طلب الاشتراك." });
      return;
    }
    const user = userResult.rows[0];
    const requestResult = await getDatabasePool().query<{
      id: string;
      plan: string;
      amount: number;
      unique_code: string;
      status: string;
      rejection_reason: string | null;
      created_at: Date;
    }>(
      `SELECT id, plan, amount, unique_code, status, rejection_reason, created_at
       FROM subscription_requests WHERE user_id = $1
       ORDER BY created_at DESC LIMIT 20`,
      [user.id]
    );
    const meteredResult = await getDatabasePool().query<{
      plan: string;
      name_ar: string;
      messages_total: number;
      messages_used: number;
      starts_at: Date;
      expires_at: Date;
      features: Record<string, boolean>;
    }>(
      `SELECT sp.name AS plan, sp.name_ar, us.messages_total, us.messages_used,
              us.starts_at, us.expires_at, sp.features
       FROM user_subscriptions us
       JOIN subscription_plans sp ON sp.id = us.plan_id
       WHERE us.user_id = $1 AND us.status = 'active'
         AND us.starts_at <= now() AND us.expires_at > now()
       ORDER BY us.starts_at DESC, us.created_at DESC
       LIMIT 1`,
      [user.id]
    );
    const meteredSubscription = meteredResult.rows[0];
    const now = Date.now();
    const endTime = user.subscription_end_date?.getTime() ?? null;
    const legacyActive = user.is_legacy && endTime !== null && endTime > now;
    const meteredActive = Boolean(meteredSubscription);
    const subscriptionStatus = legacyActive || meteredActive
      ? "paid"
      : endTime !== null && endTime <= now ? "expired" : "free";
    const subscriptionEndDate = meteredSubscription?.expires_at ??
      user.subscription_end_date;
    res.json({
      user: {
        userCode: user.user_code,
        vodafoneNumber: user.vodafone_number,
        displayName: user.display_name,
        subscriptionPlan: user.subscription_plan,
        subscriptionEndDate: subscriptionEndDate?.toISOString() ?? null,
        isLegacy: legacyActive,
      },
      subscription: {
        status: subscriptionStatus,
        plan: meteredSubscription?.plan ?? user.subscription_plan,
        planNameAr: meteredSubscription?.name_ar ?? null,
        startDate: (meteredSubscription?.starts_at ?? user.subscription_start_date)?.toISOString() ?? null,
        endDate: subscriptionEndDate?.toISOString() ?? null,
        daysRemaining: subscriptionEndDate && subscriptionEndDate.getTime() > now
          ? Math.ceil((subscriptionEndDate.getTime() - now) / 86_400_000)
          : 0,
        isLegacy: legacyActive,
        messagesTotal: meteredSubscription?.messages_total ?? null,
        messagesUsed: meteredSubscription?.messages_used ?? null,
        messagesRemaining: meteredSubscription
          ? Math.max(0, meteredSubscription.messages_total - meteredSubscription.messages_used)
          : null,
        features: meteredSubscription?.features ?? null,
      },
      requests: requestResult.rows.map((request) => ({
        id: request.id,
        plan: request.plan,
        amount: request.amount,
        uniqueCode: request.unique_code,
        status: request.status,
        rejectionReason: request.rejection_reason,
        createdAt: request.created_at.toISOString(),
      })),
    });
  }));

  app.post("/api/subscription/generate-code", asyncRoute("payment code generation", async (req, res) => {
    const account = requireAccountId(req, res);
    if (!account) return;
    const { accountId, firebaseUid } = account;
    const plan = getPaymentPlan(req.body?.plan);
    if (!plan) {
      res.status(400).json({ error: "اختر خطة اشتراك صحيحة." });
      return;
    }
    await ensureSubscriptionTables();
    const userResult = await getDatabasePool().query<{ id: string }>(
      `SELECT id FROM app_users
       WHERE device_id = $1 OR ($2::varchar IS NOT NULL AND firebase_uid = $2)
       ORDER BY CASE WHEN firebase_uid = $2 THEN 0 ELSE 1 END,
                subscription_end_date DESC NULLS LAST, created_at ASC
       LIMIT 1`,
      [accountId, firebaseUid]
    );
    if (!userResult.rowCount) {
      res.status(404).json({ error: "سجّل رقم الموبايل أولاً لبدء طلب الاشتراك." });
      return;
    }
    for (let attempt = 0; attempt < 5; attempt += 1) {
      try {
        const requestId = randomUUID();
        const uniqueCode = makePaymentCode();
        await getDatabasePool().query(
          `INSERT INTO subscription_requests (id, user_id, device_id, plan, amount, unique_code)
           VALUES ($1, $2, $3, $4, $5, $6)`,
          [requestId, userResult.rows[0].id, accountId, plan.plan, plan.amount, uniqueCode]
        );
        res.status(201).json({ requestId, uniqueCode, amount: plan.amount, plan: plan.plan });
        return;
      } catch (error) {
        if (
          attempt < 4 &&
          error !== null &&
          typeof error === "object" &&
          "code" in error &&
          error.code === "23505"
        ) continue;
        throw error;
      }
    }
    throw new Error("Unable to generate a unique subscription payment code.");
  }));

  app.post(
    "/api/subscription/upload-screenshot",
    handleScreenshotUpload,
    asyncRoute("screenshot storage", async (req, res) => {
      const account = requireAccountId(req, res);
      if (!account) return;
      const { accountId, firebaseUid } = account;
      if (typeof req.body?.requestId !== "string" || !UUID_PATTERN.test(req.body.requestId)) {
        res.status(400).json({ error: "معرّف طلب الاشتراك غير صالح." });
        return;
      }
      const imageType = req.file && identifyScreenshot(req.file.buffer);
      if (!req.file || !imageType) {
        res.status(400).json({ error: "ارفع صورة تحويل بصيغة JPG أو PNG أو WebP." });
        return;
      }
      await ensureSubscriptionTables();
      const result = await getDatabasePool().query(
        `UPDATE subscription_requests
         SET screenshot_data = $3, screenshot_mime_type = $4
        WHERE id = $1 AND status = 'draft'
          AND user_id IN (
            SELECT id FROM app_users
            WHERE device_id = $2 OR ($5::varchar IS NOT NULL AND firebase_uid = $5)
          )
        RETURNING id`,
        [req.body.requestId, accountId, req.file.buffer, imageType, firebaseUid]
      );
      if (!result.rowCount) {
        res.status(404).json({ error: "لم يتم العثور على طلب دفع صالح لرفع الصورة إليه." });
        return;
      }
      res.json({ uploaded: true });
    })
  );

  app.post("/api/subscription/request", asyncRoute("request submission", async (req, res) => {
    const account = requireAccountId(req, res);
    if (!account) return;
    const { accountId, firebaseUid } = account;
    const requestId = req.body?.requestId;
    if (typeof requestId !== "string" || !UUID_PATTERN.test(requestId)) {
      res.status(400).json({ error: "معرّف طلب الاشتراك غير صالح." });
      return;
    }
    const phone = normalizeEgyptianMobile(req.body?.vodafoneNumber);
    if (!phone) {
      res.status(400).json({ error: "أدخل رقم موبايل مصري صحيحاً (مثل 01012345678)." });
      return;
    }
    await ensureSubscriptionTables();
    const client = await getDatabasePool().connect();
    try {
      await client.query("BEGIN");
      const requestResult = await client.query<{ id: string; user_id: string; status: string; screenshot_data: Buffer | null }>(
        `SELECT id, user_id, status, screenshot_data FROM subscription_requests
         WHERE id = $1 AND user_id IN (
           SELECT id FROM app_users
           WHERE device_id = $2 OR ($3::varchar IS NOT NULL AND firebase_uid = $3)
         ) FOR UPDATE`,
        [requestId, accountId, firebaseUid]
      );
      const request = requestResult.rows[0];
      if (!request) {
        await client.query("ROLLBACK");
        res.status(404).json({ error: "لم يتم العثور على طلب الاشتراك." });
        return;
      }
      if (request.status !== "draft" || !request.screenshot_data) {
        await client.query("ROLLBACK");
        res.status(409).json({ error: "ارفع صورة التحويل قبل إرسال الطلب، أو أنشئ طلباً جديداً." });
        return;
      }
      await client.query(
        `UPDATE subscription_requests SET status = 'pending', vodafone_number = $2
         WHERE id = $1`,
        [requestId, phone]
      );
      await client.query(
        "UPDATE app_users SET vodafone_number = $2 WHERE id = $1",
        [request.user_id, phone]
      );
      await client.query("COMMIT");
      res.status(202).json({ requestId, status: "pending" });
    } catch (error) {
      try {
        await client.query("ROLLBACK");
      } catch (rollbackError) {
        console.error(
          "Subscription request transaction rollback failed:",
          rollbackError instanceof Error ? rollbackError.name : "UnknownError"
        );
      }
      throw error;
    } finally {
      client.release();
    }
  }));

  app.get("/api/admin/subscriptions", asyncRoute("admin request listing", async (req, res) => {
    if (!requireAdmin(req, res)) return;
    await ensureSubscriptionTables();
    const status = typeof req.query.status === "string" ? req.query.status : "all";
    if (status !== "all" && !["draft", "pending", "approved", "rejected"].includes(status)) {
      res.status(400).json({ error: "حالة التصفية غير صالحة." });
      return;
    }
    const search = typeof req.query.q === "string" ? req.query.q.trim().slice(0, 100) : "";
    const result = await getDatabasePool().query<{
      id: string;
      user_code: string;
      display_name: string | null;
      vodafone_number: string | null;
      plan: string;
      amount: number;
      unique_code: string;
      status: string;
      rejection_reason: string | null;
      created_at: Date;
      approved_at: Date | null;
      has_screenshot: boolean;
    }>(
      `SELECT r.id, u.user_code, u.display_name, r.vodafone_number, r.plan, r.amount,
              r.unique_code, r.status, r.rejection_reason, r.created_at, r.approved_at,
              (r.screenshot_data IS NOT NULL) AS has_screenshot
       FROM subscription_requests r
       JOIN app_users u ON u.id = r.user_id
       WHERE ($1 = 'all' OR r.status = $1)
         AND ($2 = '' OR u.user_code ILIKE '%' || $2 || '%'
              OR COALESCE(u.display_name, '') ILIKE '%' || $2 || '%'
              OR COALESCE(r.vodafone_number, '') ILIKE '%' || $2 || '%'
              OR r.unique_code ILIKE '%' || $2 || '%')
       ORDER BY r.created_at DESC LIMIT 250`,
      [status, search]
    );
    res.json({
      requests: result.rows.map((row) => ({
        id: row.id,
        userCode: row.user_code,
        displayName: row.display_name,
        vodafoneNumber: row.vodafone_number,
        plan: row.plan,
        amount: row.amount,
        uniqueCode: row.unique_code,
        status: row.status,
        rejectionReason: row.rejection_reason,
        createdAt: row.created_at.toISOString(),
        approvedAt: row.approved_at?.toISOString() ?? null,
        screenshotAvailable: row.has_screenshot,
      })),
    });
  }));

  app.get("/api/admin/subscriptions/:id/screenshot", asyncRoute("admin screenshot retrieval", async (req, res) => {
    if (!requireAdmin(req, res)) return;
    if (!UUID_PATTERN.test(req.params.id)) {
      res.status(400).json({ error: "معرّف الطلب غير صالح." });
      return;
    }
    await ensureSubscriptionTables();
    const result = await getDatabasePool().query<{ screenshot_data: Buffer; screenshot_mime_type: string }>(
      `SELECT screenshot_data, screenshot_mime_type FROM subscription_requests
       WHERE id = $1 AND screenshot_data IS NOT NULL`,
      [req.params.id]
    );
    if (!result.rowCount) {
      res.status(404).json({ error: "صورة التحويل غير موجودة." });
      return;
    }
    res.set({
      "Cache-Control": "private, no-store",
      "Content-Security-Policy": "default-src 'none'; sandbox",
      "Content-Disposition": "inline",
      "X-Content-Type-Options": "nosniff",
      "Content-Type": result.rows[0].screenshot_mime_type,
    });
    res.send(result.rows[0].screenshot_data);
  }));

  app.post("/api/admin/subscription/approve", asyncRoute("request approval", async (req, res) => {
    if (!requireAdmin(req, res)) return;
    const requestId = req.body?.requestId;
    if (typeof requestId !== "string" || !UUID_PATTERN.test(requestId)) {
      res.status(400).json({ error: "معرّف طلب الاشتراك غير صالح." });
      return;
    }
    await ensureSubscriptionTables();
    const client = await getDatabasePool().connect();
    try {
      await client.query("BEGIN");
      const result = await client.query<{
        user_id: string;
        plan: string;
        user_code: string;
      }>(
        `SELECT r.user_id, r.plan, u.user_code
         FROM subscription_requests r
         JOIN app_users u ON u.id = r.user_id
         WHERE r.id = $1 AND r.status = 'pending' FOR UPDATE OF r`,
        [requestId]
      );
      const request = result.rows[0];
      if (!request) {
        await client.query("ROLLBACK");
        res.status(409).json({ error: "الطلب غير موجود أو تمت معالجته بالفعل." });
        return;
      }
      const details = getPaymentPlan(request.plan);
      if (!details) {
        throw new Error("Approved subscription request contains an unsupported plan.");
      }
      await client.query(
        `UPDATE subscription_requests SET status = 'approved', approved_at = now()
         WHERE id = $1`,
        [requestId]
      );
      const userResult = await client.query<{ subscription_end_date: Date }>(
        `UPDATE app_users
         SET subscription_plan = CASE WHEN $2 THEN $3 ELSE NULL END,
             is_legacy = $2,
             subscription_start_date = now(),
             subscription_end_date = now() + ($4::integer * interval '1 day')
         WHERE id = $1
         RETURNING subscription_end_date`,
        [request.user_id, details.legacy, details.legacy ? request.plan : null, details.days]
      );
      if (!userResult.rowCount) {
        throw new Error("Approved subscription request has no matching app user.");
      }
      if (!details.legacy) {
        const planResult = await client.query<{ id: string }>(
          "SELECT id FROM subscription_plans WHERE name = $1 AND is_active = true",
          [request.plan]
        );
        const planId = planResult.rows[0]?.id;
        if (!planId || details.messages === null) {
          throw new Error("Approved metered plan is missing its message allowance.");
        }
        await client.query(
          `INSERT INTO user_subscriptions
             (user_id, plan_id, messages_total, messages_used, starts_at, expires_at, status)
           VALUES ($1, $2, $3, 0, now(), $4, 'active')`,
          [
            request.user_id,
            planId,
            details.messages,
            userResult.rows[0].subscription_end_date,
          ]
        );
      }
      await client.query("COMMIT");
      res.json({
        requestId,
        status: "approved",
        userCode: request.user_code,
        plan: request.plan,
        days: details.days,
        messages: details.messages,
        legacy: details.legacy,
        subscriptionEndDate: userResult.rows[0].subscription_end_date.toISOString(),
      });
    } catch (error) {
      try {
        await client.query("ROLLBACK");
      } catch (rollbackError) {
        console.error(
          "Subscription approval transaction rollback failed:",
          rollbackError instanceof Error ? rollbackError.name : "UnknownError"
        );
      }
      throw error;
    } finally {
      client.release();
    }
  }));

  app.post("/api/admin/subscription/reject", asyncRoute("request rejection", async (req, res) => {
    if (!requireAdmin(req, res)) return;
    const requestId = req.body?.requestId;
    const reason = typeof req.body?.reason === "string" ? req.body.reason.trim() : "";
    if (typeof requestId !== "string" || !UUID_PATTERN.test(requestId)) {
      res.status(400).json({ error: "معرّف طلب الاشتراك غير صالح." });
      return;
    }
    if (!reason || reason.length > 500) {
      res.status(400).json({ error: "اكتب سبب الرفض (بحد أقصى 500 حرف)." });
      return;
    }
    await ensureSubscriptionTables();
    const result = await getDatabasePool().query(
      `UPDATE subscription_requests SET status = 'rejected', rejection_reason = $2
       WHERE id = $1 AND status = 'pending' RETURNING id`,
      [requestId, reason]
    );
    if (!result.rowCount) {
      res.status(409).json({ error: "الطلب غير موجود أو تمت معالجته بالفعل." });
      return;
    }
    res.json({ requestId, status: "rejected" });
  }));
}
