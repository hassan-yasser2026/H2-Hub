import { GlobalFonts, createCanvas, type SKRSContext2D } from "@napi-rs/canvas";
import path from "node:path";
import sharp from "sharp";

const FONT_PATH = path.resolve(process.cwd(), "assets", "fonts", "Cairo.ttf");
const FONT_FAMILY = "Cairo";
const MAX_IMAGE_DIMENSION = 4096;

if (!GlobalFonts.registerFromPath(FONT_PATH, FONT_FAMILY)) {
  throw new Error(`Unable to load the Arabic text-overlay font at ${FONT_PATH}.`);
}

export type OverlayText = {
  text: string;
  fontSize: number;
  color: string;
  position: "top" | "bottom";
};

function splitIntoLines(
  context: SKRSContext2D,
  text: string,
  maxWidth: number
): string[] {
  const words = text.trim().split(/\s+/u);
  const lines: string[] = [];
  let line = "";
  for (const word of words) {
    const candidate = line ? `${line} ${word}` : word;
    if (line && context.measureText(candidate).width > maxWidth) {
      lines.push(line);
      line = word;
    } else {
      line = candidate;
    }
  }
  if (line) lines.push(line);
  return lines;
}

export async function addTextOverlay(
  imageBuffer: Buffer,
  texts: OverlayText[]
): Promise<Buffer> {
  if (texts.length === 0) return imageBuffer;
  const metadata = await sharp(imageBuffer, { failOn: "error" }).metadata();
  const width = metadata.width;
  const height = metadata.height;
  if (
    !width || !height ||
    width > MAX_IMAGE_DIMENSION ||
    height > MAX_IMAGE_DIMENSION
  ) {
    throw new Error("Generated image has unsupported dimensions for text overlay.");
  }

  const canvas = createCanvas(width, height);
  const context = canvas.getContext("2d");
  const scale = Math.min(width, height) / 1024;
  const horizontalPadding = Math.max(24, width * 0.06);
  const maxTextWidth = width - horizontalPadding * 2;
  const topTexts = texts.filter((text) => text.position === "top");
  const bottomTexts = texts.filter((text) => text.position === "bottom");

  for (const [position, group] of [["top", topTexts], ["bottom", bottomTexts]] as const) {
    if (group.length === 0) continue;
    const prepared = group.map((item) => {
      const text = item.text.trim();
      if (!text || text.length > 100) {
        throw new Error("Text overlay items must contain between 1 and 100 characters.");
      }
      let fontSize = Math.max(16, Math.round(item.fontSize * scale));
      context.font = `700 ${fontSize}px "${FONT_FAMILY}"`;
      while (fontSize > 16 && context.measureText(text).width > maxTextWidth) {
        fontSize -= 2;
        context.font = `700 ${fontSize}px "${FONT_FAMILY}"`;
      }
      const lines = splitIntoLines(context, text, maxTextWidth);
      const lineHeight = Math.round(fontSize * 1.28);
      return { item, fontSize, lines, lineHeight };
    });

    const groupHeight = prepared.reduce((total, item) => total + item.lines.length * item.lineHeight, 0);
    const gap = Math.max(8, Math.round(12 * scale));
    const paddingY = Math.max(12, Math.round(18 * scale));
    const panelHeight = groupHeight + paddingY * 2 + gap * (prepared.length - 1);
    const panelY = position === "top"
      ? Math.max(16, Math.round(height * 0.08))
      : Math.max(16, height - Math.round(height * 0.08) - panelHeight);
    const panelWidth = Math.min(width - horizontalPadding, width * 0.9);
    const panelX = (width - panelWidth) / 2;
    const radius = Math.min(24 * scale, panelHeight / 2);

    context.save();
    context.fillStyle = "rgba(9, 13, 27, 0.96)";
    context.shadowColor = "rgba(0, 0, 0, 0.32)";
    context.shadowBlur = Math.max(8, 18 * scale);
    context.beginPath();
    context.roundRect(panelX, panelY, panelWidth, panelHeight, radius);
    context.fill();
    context.restore();

    context.save();
    let cursorY = panelY + paddingY;
    for (const [index, { item, fontSize, lines, lineHeight }] of prepared.entries()) {
      context.font = `700 ${fontSize}px "${FONT_FAMILY}"`;
      context.fillStyle = item.color;
      context.textAlign = "center";
      context.textBaseline = "middle";
      context.direction = /[\u0600-\u06ff]/u.test(item.text) ? "rtl" : "ltr";
      context.shadowColor = "rgba(0, 0, 0, 0.35)";
      context.shadowBlur = Math.max(2, 4 * scale);
      for (const line of lines) {
        context.fillText(line, width / 2, cursorY + lineHeight / 2, maxTextWidth);
        cursorY += lineHeight;
      }
      if (index < prepared.length - 1) cursorY += gap;
    }
    context.restore();
  }

  return sharp(imageBuffer, { failOn: "error" })
    .composite([{ input: canvas.toBuffer("image/png"), top: 0, left: 0 }])
    .toBuffer();
}
