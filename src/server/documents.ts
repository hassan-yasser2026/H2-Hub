import { readFile } from "node:fs/promises";
import path from "node:path";
import mammoth from "mammoth";
import { PDFParse } from "pdf-parse";

export type PageText = { page: number | null; text: string };

async function extractPdfPages(buffer: Buffer): Promise<PageText[]> {
  const parser = new PDFParse({ data: buffer });
  try {
    const result = await parser.getText();
    return result.pages.map(({ num, text }) => ({ page: num, text: text.trim() }));
  } finally {
    await parser.destroy();
  }
}

export async function extractDocument(filePath: string): Promise<PageText[]> {
  const extension = path.extname(filePath).toLowerCase();
  if (extension === ".pdf") return extractPdfPages(await readFile(filePath));
  if (extension === ".docx") {
    const result = await mammoth.extractRawText({ path: filePath });
    return [{ page: null, text: result.value.trim() }];
  }
  if (extension === ".txt") {
    return [{ page: null, text: (await readFile(filePath, "utf8")).trim() }];
  }
  if (extension === ".doc") {
    throw new Error(`Legacy Word file "${path.basename(filePath)}" is not supported; save it as .docx first.`);
  }
  return [];
}
