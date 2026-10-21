import assert from "node:assert/strict";
import test from "node:test";
import {
  isAppCheckEnforced,
  RequestIdentityError,
  verifyRequestIdentity,
} from "./firebase-request-identity.js";

test("App Check rollout mode defaults to unenforced and rejects unknown modes", () => {
  assert.equal(isAppCheckEnforced(undefined), false);
  assert.equal(isAppCheckEnforced("unenforced"), false);
  assert.equal(isAppCheckEnforced("enforced"), true);
  assert.throws(() => isAppCheckEnforced("monitoring"), /FIREBASE_APP_CHECK_MODE/u);
});

function request(headers: Record<string, string>) {
  return {
    get(name: string) {
      return headers[name.toLowerCase()];
    },
  };
}

test("request identity requires and verifies Firebase App Check", async () => {
  await assert.rejects(
    verifyRequestIdentity(request({}), async () => ({ appId: "app" }), undefined, true),
    (error: unknown) => error instanceof RequestIdentityError &&
      error.reason === "APP_CHECK_REQUIRED"
  );

  const identity = await verifyRequestIdentity(
    request({ "x-firebase-appcheck": "valid-app-token" }),
    async (token) => {
      assert.equal(token, "valid-app-token");
      return { appId: "expected-app" };
    },
    async () => ({ uid: "unused" })
  );
  assert.deepEqual(identity, { uid: null, appId: "expected-app" });
});

test("unenforced mode accepts missing App Check but still rejects invalid tokens", async () => {
  const identity = await verifyRequestIdentity(
    request({}),
    async () => { throw new Error("Missing App Check token must not be verified."); },
    undefined,
    false
  );
  assert.deepEqual(identity, { uid: null, appId: null });

  await assert.rejects(
    verifyRequestIdentity(
      request({ "x-firebase-appcheck": "invalid-app-token" }),
      async () => { throw new Error("Invalid App Check token."); },
      undefined,
      false
    ),
    (error: unknown) => error instanceof RequestIdentityError &&
      error.reason === "APP_CHECK_INVALID"
  );
});

test("request identity returns only verified Firebase UIDs", async () => {
  const identity = await verifyRequestIdentity(
    request({
      "x-firebase-appcheck": "valid-app-token",
      authorization: "Bearer valid-user-token",
    }),
    async () => ({ appId: "expected-app" }),
    async (token) => {
      assert.equal(token, "valid-user-token");
      return { uid: "verified-user" };
    }
  );
  assert.deepEqual(identity, { uid: "verified-user", appId: "expected-app" });
});

test("request identity rejects malformed and invalid Firebase ID tokens", async () => {
  await assert.rejects(
    verifyRequestIdentity(
      request({
        "x-firebase-appcheck": "valid-app-token",
        authorization: "Basic invalid",
      }),
      async () => ({ appId: "expected-app" })
    ),
    (error: unknown) => error instanceof RequestIdentityError &&
      error.reason === "AUTH_TOKEN_INVALID"
  );
  await assert.rejects(
    verifyRequestIdentity(
      request({
        "x-firebase-appcheck": "valid-app-token",
        authorization: "Bearer invalid",
      }),
      async () => ({ appId: "expected-app" }),
      async () => { throw new Error("bad token"); }
    ),
    (error: unknown) => error instanceof RequestIdentityError &&
      error.reason === "AUTH_TOKEN_INVALID"
  );
});
