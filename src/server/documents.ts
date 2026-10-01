import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import path from "node:path";
import mammoth from "mammoth";
import { getDocument } from "pdfjs-dist/legacy/build/pdf.mjs";

export type PageText = { page: number | null; text: string };

const projectRequire = createRequire(path.join(process.cwd(), "package.json"));
const pdfjsBuildPath = projectRequire.resolve("pdfjs-dist/legacy/build/pdf.mjs");
const standardFontDataPath = path.resolve(
  path.dirname(pdfjsBuildPath),
  "../../standard_fonts"
) + path.sep;

async function extractPdfPages(buffer: Buffer): Promise<PageText[]> {
  const document = await getDocument({
    data: new Uint8Array(buffer),
    standardFontDataUrl: standardFontDataPath,
  }).promise;
  const pages: PageText[] = [];
  try {
    for (let pageNumber = 1; pageNumber <= document.numPages; pageNumber++) {
      const page = await document.getPage(pageNumber);
      const content = await page.getTextContent();
      const text = content.items
        .map((item) => ("str" in item ? item.str : ""))
        .join(" ")
        .trim();
      if (text) pages.push({ page: pageNumber, text });
    }
  } finally {
    await document.destroy();
  }
  return pages;
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
