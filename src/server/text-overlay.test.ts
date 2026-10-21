import assert from "node:assert/strict";
import test from "node:test";
import sharp from "sharp";
import { addTextOverlay } from "./text-overlay.js";

test("adds Cairo Arabic and English lettering without changing the source dimensions", async () => {
  const source = await sharp({
    create: {
      width: 1024,
      height: 1024,
      channels: 3,
      background: { r: 28, g: 35, b: 64 },
    },
  }).png().toBuffer();
  const result = await addTextOverlay(source, [
    { text: "ذاكر صح", fontSize: 68, color: "#FFFFFF", position: "top" },
    { text: "STUDY SMART", fontSize: 48, color: "#F4C86A", position: "bottom" },
  ]);
  const sourceMetadata = await sharp(source).metadata();
  const resultMetadata = await sharp(result).metadata();

  assert.equal(resultMetadata.width, sourceMetadata.width);
  assert.equal(resultMetadata.height, sourceMetadata.height);
  assert.notDeepEqual(result, source);
});

test("returns the original buffer when no text overlay is requested", async () => {
  const source = Buffer.from("unchanged");
  assert.equal(await addTextOverlay(source, []), source);
});
