import bcrypt from "bcryptjs";
import jwt, { type JwtPayload } from "jsonwebtoken";

const TOKEN_ISSUER = "h2-smart-hub";
const TOKEN_AUDIENCE = "h2-admin";
const TOKEN_LIFETIME = "24h";
const BCRYPT_HASH_PATTERN = /^\$2[aby]\$\d{2}\$[./A-Za-z0-9]{53}$/u;

export type AdminAuthConfiguration = {
  email: string | undefined;
  passwordHash: string | undefined;
  jwtSecret: string | undefined;
};

export type AdminTokenPayload = JwtPayload & {
  role: "admin";
};

export function hasAdminAuthConfiguration(configuration: AdminAuthConfiguration): boolean {
  return Boolean(
    configuration.email?.trim() &&
    configuration.passwordHash &&
    BCRYPT_HASH_PATTERN.test(configuration.passwordHash) &&
    configuration.jwtSecret &&
    Buffer.byteLength(configuration.jwtSecret, "utf8") >= 32
  );
}

export async function verifyAdminCredentials(
  email: string,
  password: string,
  configuration: AdminAuthConfiguration
): Promise<boolean> {
  if (!hasAdminAuthConfiguration(configuration)) return false;
  if (email.trim().toLowerCase() !== configuration.email!.trim().toLowerCase()) {
    return false;
  }
  try {
    return await bcrypt.compare(password, configuration.passwordHash!);
  } catch {
    return false;
  }
}

export function createAdminToken(email: string, secret: string): string {
  if (Buffer.byteLength(secret, "utf8") < 32) {
    throw new Error("JWT_SECRET must contain at least 32 bytes.");
  }
  return jwt.sign(
    { role: "admin" },
    secret,
    {
      algorithm: "HS256",
      audience: TOKEN_AUDIENCE,
      expiresIn: TOKEN_LIFETIME,
      issuer: TOKEN_ISSUER,
      subject: email.trim().toLowerCase(),
    }
  );
}

export function verifyAdminToken(token: string, secret: string): AdminTokenPayload | null {
  if (Buffer.byteLength(secret, "utf8") < 32) return null;
  try {
    const payload = jwt.verify(token, secret, {
      algorithms: ["HS256"],
      audience: TOKEN_AUDIENCE,
      issuer: TOKEN_ISSUER,
    });
    if (
      typeof payload === "string" ||
      payload.role !== "admin" ||
      typeof payload.sub !== "string" ||
      !payload.sub
    ) {
      return null;
    }
    return payload as AdminTokenPayload;
  } catch {
    return null;
  }
}
