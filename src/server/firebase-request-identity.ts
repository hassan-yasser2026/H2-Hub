import { cert, getApps, initializeApp } from "firebase-admin/app";
import type { App } from "firebase-admin/app";
import { getAppCheck } from "firebase-admin/app-check";
import { getAuth } from "firebase-admin/auth";
import type { NextFunction, Request, Response } from "express";

const APP_NAME = "h2-request-identity";
let adminApp: App | undefined;

export type VerifiedRequestIdentity = {
  uid: string | null;
  appId: string | null;
};

export class RequestIdentityError extends Error {
  constructor(
    readonly reason: "APP_CHECK_REQUIRED" | "APP_CHECK_INVALID" | "AUTH_TOKEN_INVALID"
  ) {
    super(reason);
    this.name = "RequestIdentityError";
  }
}

function getAdminApp(): App {
  if (adminApp) return adminApp;
  const value = process.env.FIREBASE_SERVICE_ACCOUNT_JSON?.trim();
  if (!value) throw new Error("FIREBASE_SERVICE_ACCOUNT_JSON is required for request verification.");
  const parsed: unknown = JSON.parse(value);
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
  const projectId = typeof account.projectId === "string" ? account.projectId : account.project_id;
  const clientEmail = typeof account.clientEmail === "string" ? account.clientEmail : account.client_email;
  const privateKey = typeof account.privateKey === "string" ? account.privateKey : account.private_key;
  if (
    typeof projectId !== "string" ||
    typeof clientEmail !== "string" ||
    typeof privateKey !== "string"
  ) {
    throw new Error("Firebase service account is missing required credentials.");
  }
  adminApp = getApps().find((candidate) => candidate.name === APP_NAME) ??
    initializeApp({
      credential: cert({
        projectId,
        clientEmail,
        privateKey: privateKey.replace(/\\n/gu, "\n"),
      }),
    }, APP_NAME);
  return adminApp;
}

export function isAppCheckEnforced(mode = process.env.FIREBASE_APP_CHECK_MODE): boolean {
  if (mode === undefined || mode === "unenforced") return false;
  if (mode === "enforced") return true;
  throw new Error("FIREBASE_APP_CHECK_MODE must be either 'unenforced' or 'enforced'.");
}

export async function verifyRequestIdentity(
  request: { get(name: string): unknown },
  verifyAppCheck: (token: string) => Promise<{ appId: string }> = (token) =>
    getAppCheck(getAdminApp()).verifyToken(token),
  verifyIdToken: (token: string) => Promise<{ uid: string }> = (token) =>
    getAuth(getAdminApp()).verifyIdToken(token),
  enforceAppCheck = isAppCheckEnforced()
): Promise<VerifiedRequestIdentity> {
  const appCheckHeader = request.get("x-firebase-appcheck");
  const appCheckToken = typeof appCheckHeader === "string" ? appCheckHeader : undefined;
  let appId: string | null = null;
  if (!appCheckToken) {
    if (enforceAppCheck) throw new RequestIdentityError("APP_CHECK_REQUIRED");
  } else {
    let appCheck: { appId: string };
    try {
      appCheck = await verifyAppCheck(appCheckToken);
    } catch {
      throw new RequestIdentityError("APP_CHECK_INVALID");
    }
    if (!appCheck.appId) throw new RequestIdentityError("APP_CHECK_INVALID");
    appId = appCheck.appId;
  }

  const authorizationHeader = request.get("authorization");
  const authorization = typeof authorizationHeader === "string" ? authorizationHeader : undefined;
  const match = authorization ? /^Bearer\s+(.+)$/iu.exec(authorization) : null;
  if (authorization && !match) throw new RequestIdentityError("AUTH_TOKEN_INVALID");
  if (!match) return { uid: null, appId };
  try {
    const decoded = await verifyIdToken(match[1]);
    if (!decoded.uid) throw new Error("Missing Firebase UID.");
    return { uid: decoded.uid, appId };
  } catch {
    throw new RequestIdentityError("AUTH_TOKEN_INVALID");
  }
}

export function requireAppAttestation(
  request: Request,
  response: Response,
  next: NextFunction
): void {
  void verifyRequestIdentity(request).then(() => next()).catch((error: unknown) => {
    if (error instanceof RequestIdentityError) {
      response.status(401).json({
        error: error.reason === "APP_CHECK_REQUIRED"
          ? "يلزم تحديث التطبيق للتحقق من أمان الاتصال."
          : "تعذر التحقق من أمان الاتصال أو الحساب.",
      });
      return;
    }
    console.error(
      "Firebase request verification is unavailable:",
      error instanceof Error ? error.name : "UnknownError"
    );
    response.status(503).json({ error: "تعذر التحقق من أمان الاتصال الآن." });
  });
}
