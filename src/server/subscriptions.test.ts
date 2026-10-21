import assert from "node:assert/strict";
import test from "node:test";
import {
  getSubscriptionPlan,
  identifyScreenshot,
  normalizeEgyptianMobile,
  subscriptionAccountId,
  SUBSCRIPTION_PLAN_CATALOG,
} from "./subscriptions.js";

test("subscription account identity follows the Firebase account rather than a device", () => {
  assert.equal(subscriptionAccountId("google-account-a"), "uid:google-account-a");
  assert.notEqual(subscriptionAccountId("google-account-a"), subscriptionAccountId("google-account-b"));
});

test("new subscription catalog has the requested prices, durations, messages, and features", () => {
  assert.deepEqual(
    SUBSCRIPTION_PLAN_CATALOG.map(({ name, price, durationDays, messages }) => ({
      name,
      price,
      durationDays,
      messages,
    })),
    [
      { name: "trial", price: 100, durationDays: 30, messages: 500 },
      { name: "basic", price: 1_000, durationDays: 30, messages: 5_000 },
      { name: "premium", price: 2_000, durationDays: 30, messages: 15_000 },
      { name: "annual", price: 10_000, durationDays: 365, messages: 100_000 },
      { name: "annual_premium", price: 20_000, durationDays: 365, messages: 300_000 },
    ]
  );
  assert.equal(SUBSCRIPTION_PLAN_CATALOG[0].features.images, false);
  assert.equal(SUBSCRIPTION_PLAN_CATALOG[1].features.images, true);
  assert.equal(SUBSCRIPTION_PLAN_CATALOG[2].features.priority, true);
  assert.equal(SUBSCRIPTION_PLAN_CATALOG[3].features.support, true);
  assert.equal(SUBSCRIPTION_PLAN_CATALOG[4].features.vip, true);
});

test("subscription plans have server-owned prices and durations", () => {
  assert.deepEqual(getSubscriptionPlan("monthly"), {
    plan: "monthly",
    amount: 50,
    days: 30,
  });
  assert.deepEqual(getSubscriptionPlan("quarterly"), {
    plan: "quarterly",
    amount: 130,
    days: 90,
  });
  assert.deepEqual(getSubscriptionPlan("yearly"), {
    plan: "yearly",
    amount: 400,
    days: 365,
  });
  assert.equal(getSubscriptionPlan("free"), null);
  assert.equal(getSubscriptionPlan({ plan: "monthly" }), null);
});

test("Egyptian mobile numbers normalize local and international formats", () => {
  assert.equal(normalizeEgyptianMobile("010 1234 5678"), "01012345678");
  assert.equal(normalizeEgyptianMobile("+20 1012345678"), "01012345678");
  assert.equal(normalizeEgyptianMobile("٠١٠١٢٣٤٥٦٧٨"), "01012345678");
  assert.equal(normalizeEgyptianMobile("not-a-number 01012345678"), null);
  assert.equal(normalizeEgyptianMobile("02012345678"), null);
  assert.equal(normalizeEgyptianMobile(undefined), null);
});

test("screenshot verification accepts JPEG, PNG, and WebP signatures only", () => {
  assert.equal(identifyScreenshot(Buffer.from([0xff, 0xd8, 0xff, 0x00])), "image/jpeg");
  assert.equal(
    identifyScreenshot(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])),
    "image/png"
  );
  assert.equal(
    identifyScreenshot(Buffer.from("RIFF1234WEBP", "ascii")),
    "image/webp"
  );
  assert.equal(identifyScreenshot(Buffer.from("GIF89a", "ascii")), null);
});
