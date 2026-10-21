export const IMAGE_GENERATION_MODEL = "black-forest-labs/flux-schnell";
export const IDEOGRAM_IMAGE_GENERATION_MODEL = "ideogram-ai/ideogram-v2";

export type ImageGenerationModel = "ideogram" | "flux";

const textDesignKeywords =
  /(?:إعلان|اعلان|إعلاني|اعلاني|بوستر|لافتة|منشور|تصميم|شعار|اكتب|اكتبلي|نص|كلمة|عبارة|ذاكر\s+صح|study\s+smart)|(?:^|[^a-z])(?:advertisement|poster|banner|logo|design|write|text|word|phrase)(?:$|[^a-z])/iu;

export function detectImageModel(prompt: string): ImageGenerationModel {
  return textDesignKeywords.test(prompt) ? "ideogram" : "flux";
}

const exactTextPattern = /«([^»]{1,100})»|“([^”]{1,100})”|"([^"]{1,100})"|'([^']{1,100})'|\(([^)]{1,100})\)/gu;

export function extractDesignTexts(prompt: string): string[] {
  const captured = [...prompt.matchAll(exactTextPattern)]
    .map((match) => {
      const value = match.slice(1).find((part) => part !== undefined)?.trim();
      if (match[5] !== undefined && value && !/^[A-Z0-9][A-Z0-9 &'’-]{1,100}$/u.test(value)) {
        return undefined;
      }
      return value;
    })
    .filter((value): value is string => Boolean(value));
  return [...new Set(captured)].slice(0, 4);
}

export function buildImageScenePrompt(prompt: string): string {
  const scene = prompt.replace(exactTextPattern, (match, ...captures: unknown[]) => {
    const parentheticalText = captures[4];
    if (
      typeof parentheticalText === "string" &&
      !/^[A-Z0-9][A-Z0-9 &'’-]{1,100}$/u.test(parentheticalText.trim())
    ) {
      return match;
    }
    return " ";
  }).replace(/\s+/gu, " ").trim();
  const requestsModernEgyptianStudent =
    /(?:طالب.{0,30}مصري|مصري.{0,30}طالب).{0,50}(?:ثانوي|المرحلة الثانوية)|(?:Egyptian student).{0,50}(?:high[\s-]+school)|(?:high[\s-]+school).{0,50}(?:Egyptian student)/iu
      .test(prompt) &&
    !/(?:فرعون|فراعنة|pharaoh|ancient egypt)/iu.test(prompt);
  const modernStudentGuidance = requestsModernEgyptianStudent
    ? "Depict a present-day Egyptian teenager in contemporary everyday clothing, studying at a modern desk with school books and a desk lamp. Do not depict a pharaoh, ancient-Egyptian costume, temple, sarcophagus, or statue."
    : "";
  return `${scene}\n\n${modernStudentGuidance}\nDepict the requested scene literally; do not replace requested people or objects with symbolic, historical, or unrelated imagery. Do not render visible typography, letters, words, numerals, logos, or watermarks. Keep requested people and objects, and leave uncluttered negative space near the top and bottom for a precise text overlay added afterward.`;
}

export function containsArabicText(value: string): boolean {
  return /[\u0600-\u06ff]/u.test(value);
}

export function isImageGenerationEnabled(value: string | undefined): boolean {
  return value?.trim().toLowerCase() === "true";
}

export function getTrustedReplicateImageUrl(value: unknown): URL | null {
  if (typeof value !== "string") return null;
  try {
    const url = new URL(value);
    const trustedHost = url.hostname === "replicate.delivery" ||
      url.hostname.endsWith(".replicate.delivery") ||
      url.hostname === "replicate.com" ||
      url.hostname.endsWith(".replicate.com");
    return url.protocol === "https:" && trustedHost ? url : null;
  } catch {
    return null;
  }
}

export function getReplicateImageOutputUrl(output: unknown): URL | null {
  const visited = new Set<object>();
  const findUrl = (value: unknown, depth: number): URL | null => {
    if (depth > 6) return null;
    if (value instanceof URL) return getTrustedReplicateImageUrl(value.href);
    if (typeof value === "string") return getTrustedReplicateImageUrl(value);
    if (typeof value !== "object" || value === null || visited.has(value)) return null;
    visited.add(value);

    const urlMethod = (value as { url?: unknown }).url;
    if (typeof urlMethod === "function") {
      const url = findUrl(urlMethod.call(value), depth + 1);
      if (url) return url;
    }

    const children: unknown[] = Array.isArray(value)
      ? value.slice(0, 32)
      : Object.values(value).slice(0, 32);
    for (const child of children) {
      const url = findUrl(child, depth + 1);
      if (url) return url;
    }
    return null;
  };

  return findUrl(output, 0);
}

export async function readReplicateImageOutputBlob(output: unknown): Promise<Blob | null> {
  const visited = new Set<object>();
  const findBlob = async (value: unknown, depth: number): Promise<Blob | null> => {
    if (depth > 6) return null;
    if (value instanceof Blob) return value;
    if (typeof value !== "object" || value === null || visited.has(value)) return null;
    visited.add(value);

    const blobMethod = (value as { blob?: unknown }).blob;
    if (typeof blobMethod === "function") {
      const blob = await blobMethod.call(value);
      if (blob instanceof Blob) return blob;
    }
    const arrayBufferMethod = (value as { arrayBuffer?: unknown }).arrayBuffer;
    if (typeof arrayBufferMethod === "function") {
      const arrayBuffer = await arrayBufferMethod.call(value);
      if (arrayBuffer instanceof ArrayBuffer) return new Blob([arrayBuffer]);
    }
    if (value instanceof ReadableStream) {
      return new Response(value).blob();
    }

    const children: unknown[] = Array.isArray(value)
      ? value.slice(0, 32)
      : Object.values(value).slice(0, 32);
    for (const child of children) {
      const blob = await findBlob(child, depth + 1);
      if (blob) return blob;
    }
    return null;
  };

  return findBlob(output, 0);
}

export function inspectReplicateImageOutput(output: unknown): Record<string, unknown> {
  const item = Array.isArray(output) ? output[0] : output;
  if (typeof item !== "object" || item === null) {
    return {
      typeof: typeof item,
      isNull: item === null,
      isArray: Array.isArray(item),
      constructorName: item === null ? null : typeof item,
      ownKeys: [],
      value: typeof item === "string" && /^https?:/u.test(item)
        ? `${item.split("?")[0].slice(0, 200)}${item.includes("?") ? "?[redacted]" : ""}`
        : undefined,
    };
  }

  const prototype = Object.getPrototypeOf(item) as object | null;
  const prototypeKeys = prototype
    ? Reflect.ownKeys(prototype).filter((key): key is string => typeof key === "string")
    : [];
  const fileOutput = item as {
    url?: unknown;
    blob?: unknown;
    arrayBuffer?: unknown;
    locked?: unknown;
    size?: unknown;
    type?: unknown;
  };
  let safeUrl: string | undefined;
  try {
    const urlValue = typeof fileOutput.url === "function"
      ? fileOutput.url.call(item)
      : fileOutput.url;
    const rawUrl = urlValue instanceof URL ? urlValue.href : urlValue;
    if (typeof rawUrl === "string" && /^https?:/u.test(rawUrl)) {
      safeUrl = `${rawUrl.split("?")[0].slice(0, 200)}${rawUrl.includes("?") ? "?[redacted]" : ""}`;
    }
  } catch {
    safeUrl = "unavailable";
  }

  return {
    typeof: typeof item,
    isNull: false,
    constructorName: item.constructor?.name ?? "unknown",
    isArray: Array.isArray(item),
    arrayLength: Array.isArray(item) ? item.length : undefined,
    ownKeys: Reflect.ownKeys(item)
      .filter((key): key is string => typeof key === "string")
      .slice(0, 24),
    prototypeKeys: prototypeKeys.slice(0, 24),
    url: safeUrl,
    fileOutput: {
      isReadableStream: item instanceof ReadableStream,
      locked: fileOutput.locked,
      blobMethod: typeof fileOutput.blob,
      arrayBufferMethod: typeof fileOutput.arrayBuffer,
      urlMethod: typeof fileOutput.url,
      size: typeof fileOutput.size === "number" ? fileOutput.size : undefined,
      mimeType: typeof fileOutput.type === "string" ? fileOutput.type : undefined,
    },
  };
}

export function describeReplicateImageOutput(output: unknown): string {
  if (output instanceof URL) return `url:${output.origin}`;
  if (typeof output === "string") {
    if (output.startsWith("data:")) return "data-uri";
    try {
      return `url:${new URL(output).origin}`;
    } catch {
      return "string";
    }
  }
  if (Array.isArray(output)) return `array:${output.length}`;
  if (typeof output === "object" && output !== null) {
    const keys = Reflect.ownKeys(output)
      .filter((key): key is string => typeof key === "string")
      .slice(0, 12)
      .join(",");
    const prototypeName = Object.getPrototypeOf(output)?.constructor?.name ?? "unknown";
    return `shape-v3:object-keys:${keys}:prototype:${prototypeName}`;
  }
  return typeof output;
}

export function identifyGeneratedImage(
  bytes: Uint8Array
): { mimeType: "image/jpeg" | "image/png" | "image/webp"; extension: "jpg" | "png" | "webp" } | null {
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) {
    return { mimeType: "image/jpeg", extension: "jpg" };
  }
  if (
    bytes.length >= 8 &&
    bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47 &&
    bytes[4] === 0x0d && bytes[5] === 0x0a && bytes[6] === 0x1a && bytes[7] === 0x0a
  ) {
    return { mimeType: "image/png", extension: "png" };
  }
  if (
    bytes.length >= 12 &&
    Buffer.from(bytes.subarray(0, 4)).toString("ascii") === "RIFF" &&
    Buffer.from(bytes.subarray(8, 12)).toString("ascii") === "WEBP"
  ) {
    return { mimeType: "image/webp", extension: "webp" };
  }
  return null;
}

export function classifyGeneratedImageModeration(
  value: unknown
): "safe" | "unsafe" | "unknown" {
  if (typeof value !== "string") return "unknown";
  const response = value.trim();
  try {
    const parsed: unknown = JSON.parse(response);
    if (
      typeof parsed === "object" &&
      parsed !== null &&
      "verdict" in parsed &&
      typeof parsed.verdict === "string"
    ) {
      return classifyGeneratedImageModeration(parsed.verdict);
    }
  } catch {
    // Accept the plain-text SAFE/UNSAFE format for compatibility with Gemini responses.
  }
  if (/\bUNSAFE\b/iu.test(response)) return "unsafe";
  if (/\bSAFE\b/iu.test(response)) return "safe";
  return "unknown";
}

export function isClearlySafeImageDescription(value: unknown): boolean {
  if (typeof value !== "string") return false;
  const description = value.trim();
  if (!description || description.length > 500) return false;
  if (
    /\b(?:cannot|can't|unable|uncertain|unsure|unknown|not sure|could not|failed|error|unsafe|harmful|prohibited|explicit|weapon|gun|knife|drug|nude|sexual|violent|terror|political)\b/iu
      .test(description)
  ) {
    return false;
  }
  return /^(?:(?:a|an|the|this)\s+(?:(?:image|photo|photograph|picture|illustration|drawing|render)\s+)?|(?:the\s+)?image\s+(?:shows|depicts|contains|features)\s+|(?:a|an)\s+close-up\s+of\s+|(?:تُظهر|تظهر|يعرض|تعرض)\s+(?:الصورة\s+)?)/iu
    .test(description);
}
