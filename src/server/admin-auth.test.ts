import assert from "node:assert/strict";
import { test } from "node:test";
import bcrypt from "bcryptjs";
import {
  createAdminToken,
  hasAdminAuthConfiguration,
  verifyAdminCredentials,
  verifyAdminToken,
} from "./admin-auth.js";

const password = "test-only-password";
const secret = "test-only-jwt-secret-with-at-least-32-bytes";
const hash = bcrypt.hashSync(password, 4);
const configuration = {
  email: "admin@example.test",
  passwordHash: hash,
  jwtSecret: secret,
};

test("requires an email, bcrypt hash, and strong JWT secret", () => {
  assert.equal(hasAdminAuthConfiguration(configuration), true);
  assert.equal(hasAdminAuthConfiguration({ ...configuration, passwordHash: password }), false);
  assert.equal(hasAdminAuthConfiguration({ ...configuration, jwtSecret: "short" }), false);
  assert.equal(hasAdminAuthConfiguration({ ...configuration, email: undefined }), false);
});

test("authenticates only the configured email and bcrypt password", async () => {
  assert.equal(
    await verifyAdminCredentials("ADMIN@example.test", password, configuration),
    true
  );
  assert.equal(
    await verifyAdminCredentials("other@example.test", password, configuration),
    false
  );
  assert.equal(
    await verifyAdminCredentials("admin@example.test", "wrong-password", configuration),
    false
  );
});

test("signs a 24-hour admin token and rejects invalid tokens", () => {
  const token = createAdminToken(configuration.email, secret);
  const payload = verifyAdminToken(token, secret);
  assert.equal(payload?.sub, configuration.email);
  assert.equal(payload?.role, "admin");
  assert.equal(verifyAdminToken(token, "a-different-secret-of-at-least-32-bytes"), null);
  assert.equal(verifyAdminToken("not-a-token", secret), null);
  assert.throws(() => createAdminToken(configuration.email!, "short"));
});
