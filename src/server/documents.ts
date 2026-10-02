import { readFile } from "node:fs/promises";
import path from "node:path";
import mammoth from "mammoth";
import { PDFParse } from "pdf-parse";
import ExcelJS from "exceljs";

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

export async function extractDocumentBuffer(
  fileName: string,
  buffer: Buffer
): Promise<PageText[]> {
  if (buffer.length === 0) throw new Error("الملف المرفوع فارغ.");
  const extension = path.extname(fileName).toLowerCase();
  if (extension === ".pdf") return extractPdfPages(buffer);
  if (extension === ".docx") {
    const result = await mammoth.extractRawText({ buffer });
    return [{ page: null, text: result.value.trim() }];
  }
  if (extension === ".txt") {
    return [{ page: null, text: buffer.toString("utf8").replace(/^\uFEFF/, "").trim() }];
  }
  if (extension === ".xlsx") {
    const workbook = new ExcelJS.Workbook();
    await workbook.xlsx.load(buffer);
    const sheets = workbook.worksheets.flatMap((worksheet) => {
      const rows: string[] = [];
      worksheet.eachRow({ includeEmpty: false }, (row) => {
        const values: string[] = [];
        row.eachCell({ includeEmpty: false }, (cell) => {
          const value = cell.value;
          if (value === null || value === undefined) return;
          if (typeof value === "object" && "text" in value) {
            values.push(String(value.text ?? "").trim());
          } else if (typeof value === "object" && "result" in value) {
            values.push(String(value.result ?? "").trim());
          } else {
            values.push(String(value).trim());
          }
        });
        if (values.some(Boolean)) rows.push(values.join("\t"));
      });
      return rows.length > 0
        ? [`ورقة العمل: ${worksheet.name}`, ...rows]
        : [];
    });
    return [{ page: null, text: sheets.join("\n").trim() }];
  }
  if (extension === ".doc") {
    throw new Error("ملفات Word القديمة بصيغة DOC غير مدعومة؛ احفظ الملف بصيغة DOCX.");
  }
  throw new Error("صيغة الملف غير مدعومة. استخدم PDF أو DOCX أو XLSX أو TXT.");
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
