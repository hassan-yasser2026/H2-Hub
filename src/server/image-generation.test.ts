import assert from "node:assert/strict";
import test from "node:test";
import {
  isImageGenerationEnabled,
  buildImageScenePrompt,
  classifyGeneratedImageModeration,
  containsArabicText,
  detectImageModel,
  extractDesignTexts,
  describeReplicateImageOutput,
  getReplicateImageOutputUrl,
  getTrustedReplicateImageUrl,
  identifyGeneratedImage,
  IMAGE_GENERATION_MODEL,
  isClearlySafeImageDescription,
  inspectReplicateImageOutput,
  readReplicateImageOutputBlob,
} from "./image-generation.js";

test("uses the requested Replicate image generation model", () => {
  assert.equal(IMAGE_GENERATION_MODEL, "black-forest-labs/flux-schnell");
});

test("image generation is disabled unless its feature flag is explicitly true", () => {
  assert.equal(isImageGenerationEnabled(undefined), false);
  assert.equal(isImageGenerationEnabled("false"), false);
  assert.equal(isImageGenerationEnabled("TRUE"), true);
});

test("detects Arabic prompts for faithful translation before image generation", () => {
  assert.equal(containsArabicText("اعملي صورة فيل"), true);
  assert.equal(containsArabicText("قطة سوداء"), true);
  assert.equal(containsArabicText("A black cat"), false);
});

test("routes text-heavy design prompts to Ideogram and ordinary imagery to FLUX", () => {
  for (const prompt of [
    "صمّم إعلان تعليمي",
    "اعمل بوستر للمدرسة",
    "اكتب عبارة على لافتة",
    "صمّم شعار ذاكر صح",
    "A poster with the words STUDY SMART",
    "Create an advertisement banner",
    "Design a logo",
    "Write a phrase in the image",
  ]) {
    assert.equal(detectImageModel(prompt), "ideogram", prompt);
  }
  for (const prompt of [
    "قطة زرقاء",
    "A mountain landscape at sunset",
    "رسم فيل في الغابة",
  ]) {
    assert.equal(detectImageModel(prompt), "flux", prompt);
  }
  assert.equal(detectImageModel("A catalog of old books"), "flux");
});

test("preserves quoted Arabic and English lettering in Ideogram prompts", () => {
  const prompt = "إعلان لمنصة اسمها «ذاكر صح» (STUDY SMART)";
  assert.deepEqual(extractDesignTexts(prompt), ["ذاكر صح", "STUDY SMART"]);
  assert.deepEqual(extractDesignTexts("A poster (soft blue colors)"), []);
});

test("extracts exact requested design text and asks image models to leave it out", () => {
  const prompt = "إعلان منصة اسمها «ذاكر صح» (STUDY SMART)، لطالب يذاكر ليلاً";
  assert.deepEqual(extractDesignTexts(prompt), ["ذاكر صح", "STUDY SMART"]);
  const scenePrompt = buildImageScenePrompt(prompt);
  assert.ok(scenePrompt.includes("لطالب يذاكر ليلاً"));
  assert.ok(scenePrompt.includes("Do not render visible typography"));
  assert.ok(!scenePrompt.includes("(STUDY SMART)"));
  assert.ok(!scenePrompt.includes("ذاكر صح"));
  assert.ok(!scenePrompt.includes("STUDY SMART"));
  assert.ok(buildImageScenePrompt("A poster (soft blue colors)").includes("(soft blue colors)"));
  assert.deepEqual(extractDesignTexts("صورة طالب يذاكر دون نص"), []);
});

test("keeps requested Egyptian high-school students contemporary, unless history is explicit", () => {
  const arabicPrompt = buildImageScenePrompt(
    "إعلان لطالب مصري في المرحلة الثانوية يذاكر على مكتبه"
  );
  assert.ok(arabicPrompt.includes("present-day Egyptian teenager"));
  assert.ok(arabicPrompt.includes("Do not depict a pharaoh"));

  const historicalPrompt = buildImageScenePrompt(
    "إعلان عن فرعون مصري من عصر الدولة القديمة"
  );
  assert.ok(!historicalPrompt.includes("Do not depict a pharaoh"));
});

test("accepts only HTTPS Replicate image output URLs", () => {
  assert.equal(getTrustedReplicateImageUrl("https://replicate.delivery/output.png")?.hostname, "replicate.delivery");
  assert.equal(getTrustedReplicateImageUrl("https://pbxt.replicate.delivery/output.png")?.hostname, "pbxt.replicate.delivery");
  assert.equal(getTrustedReplicateImageUrl("http://replicate.delivery/output.png"), null);
  assert.equal(getTrustedReplicateImageUrl("https://example.com/output.png"), null);
  assert.equal(getTrustedReplicateImageUrl("https://replicate.delivery.example.com/output.png"), null);
  assert.equal(getTrustedReplicateImageUrl("not-a-url"), null);
});

test("extracts trusted URLs from supported Replicate output shapes", () => {
  const trustedUrl = "https://pbxt.replicate.delivery/output.png";
  assert.equal(getReplicateImageOutputUrl([trustedUrl])?.href, trustedUrl);
  assert.equal(getReplicateImageOutputUrl({ url: trustedUrl })?.href, trustedUrl);
  assert.equal(
    getReplicateImageOutputUrl({ url: () => new URL(trustedUrl) })?.href,
    trustedUrl
  );
  assert.equal(getReplicateImageOutputUrl({ url: new URL(trustedUrl) })?.href, trustedUrl);
  assert.equal(
    getReplicateImageOutputUrl({ result: { images: [{ href: trustedUrl }] } })?.href,
    trustedUrl
  );
  assert.equal(getReplicateImageOutputUrl({ url: "https://example.com/output.png" }), null);
  assert.equal(describeReplicateImageOutput("data:image/png;base64,hidden"), "data-uri");
  assert.equal(
    describeReplicateImageOutput({ url: "https://example.com/private-token" }),
    "shape-v3:object-keys:url:prototype:Object"
  );
});

test("reads SDK FileOutput blobs without exposing output URLs", async () => {
  const imageBlob = new Blob(["image-bytes"], { type: "image/png" });
  assert.equal(
    await readReplicateImageOutputBlob([{ blob: async () => imageBlob }]),
    imageBlob
  );
  assert.equal(await readReplicateImageOutputBlob(["https://replicate.delivery/output.png"]), null);
  assert.equal(
    await (await readReplicateImageOutputBlob(new ReadableStream({
      start(controller) {
        controller.enqueue(new TextEncoder().encode("stream-bytes"));
        controller.close();
      },
    })))?.text(),
    "stream-bytes"
  );
});

test("logs safe Replicate FileOutput metadata and redacts signed URL queries", () => {
  class FileOutput extends ReadableStream<Uint8Array> {
    url() {
      return new URL("https://replicate.delivery/output.png?signature=secret");
    }

    blob() {
      return Promise.resolve(new Blob(["image"]));
    }
  }
  const details = inspectReplicateImageOutput([new FileOutput()]) as {
    typeof: string;
    constructorName: string;
    ownKeys: string[];
    prototypeKeys: string[];
    url: string;
    fileOutput: { isReadableStream: boolean; blobMethod: string };
  };
  assert.equal(details.typeof, "object");
  assert.equal(details.constructorName, "FileOutput");
  assert.ok(Array.isArray(details.ownKeys));
  assert.ok(details.prototypeKeys.includes("blob"));
  assert.equal(details.url, "https://replicate.delivery/output.png?[redacted]");
  assert.equal(details.fileOutput.isReadableStream, true);
  assert.equal(details.fileOutput.blobMethod, "function");
  assert.equal(inspectReplicateImageOutput(null).isNull, true);
});

test("identifies supported image file signatures rather than trusting headers", () => {
  assert.deepEqual(
    identifyGeneratedImage(Uint8Array.from([0xff, 0xd8, 0xff, 0x00])),
    { mimeType: "image/jpeg", extension: "jpg" }
  );
  assert.deepEqual(
    identifyGeneratedImage(Uint8Array.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])),
    { mimeType: "image/png", extension: "png" }
  );
  assert.deepEqual(
    identifyGeneratedImage(Uint8Array.from([0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50])),
    { mimeType: "image/webp", extension: "webp" }
  );
  assert.equal(identifyGeneratedImage(Uint8Array.from([1, 2, 3])), null);
});

test("accepts only explicit SAFE moderation verdicts", () => {
  assert.equal(classifyGeneratedImageModeration("SAFE"), "safe");
  assert.equal(classifyGeneratedImageModeration("SAFE."), "safe");
  assert.equal(classifyGeneratedImageModeration("The image is SAFE."), "safe");
  assert.equal(classifyGeneratedImageModeration("UNSAFE: graphic violence"), "unsafe");
  assert.equal(classifyGeneratedImageModeration("UNSAFE"), "unsafe");
  assert.equal(classifyGeneratedImageModeration("SAFE content, but UNSAFE violence"), "unsafe");
  assert.equal(classifyGeneratedImageModeration('{"verdict":"SAFE","reason":""}'), "safe");
  assert.equal(
    classifyGeneratedImageModeration('{"verdict":"UNSAFE","reason":"graphic violence"}'),
    "unsafe"
  );
  assert.equal(classifyGeneratedImageModeration("A red apple on a white background."), "unknown");
  assert.equal(classifyGeneratedImageModeration(undefined), "unknown");
});

test("recognizes ordinary benign image descriptions but not uncertain or unsafe replies", () => {
  assert.equal(isClearlySafeImageDescription("A red apple on a white background."), true);
  assert.equal(isClearlySafeImageDescription("The image shows a black cat sitting on grass."), true);
  assert.equal(isClearlySafeImageDescription("تُظهر الصورة قطة سوداء على العشب."), true);
  assert.equal(isClearlySafeImageDescription("I cannot determine whether the image is safe."), false);
  assert.equal(isClearlySafeImageDescription("A gun on a table."), false);
  assert.equal(isClearlySafeImageDescription("UNSAFE: graphic violence"), false);
  assert.equal(isClearlySafeImageDescription(""), false);
});
