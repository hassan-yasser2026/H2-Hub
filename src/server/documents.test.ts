import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import ExcelJS from "exceljs";
import { extractDocumentBuffer } from "./documents.js";

test("extracts plain text documents", async () => {
  const pages = await extractDocumentBuffer("lesson.txt", Buffer.from("\uFEFFدرس في العلوم"));
  assert.equal(pages.length, 1);
  assert.equal(pages[0]?.text, "درس في العلوم");
});

test("extracts text from a real course PDF", async () => {
  const pdf = await readFile(new URL("../../docs/01-algebra-foundations.pdf", import.meta.url));
  const pages = await extractDocumentBuffer("algebra.pdf", pdf);
  assert.ok(pages.length > 0);
  assert.ok(pages.some((page) => page.text.trim().length > 0));
});

test("extracts worksheet names and cell values from XLSX", async () => {
  const workbook = new ExcelJS.Workbook();
  const worksheet = workbook.addWorksheet("درجات");
  worksheet.addRow(["الطالب", "الدرجة"]);
  worksheet.addRow(["سارة", 95]);
  const buffer = Buffer.from(await workbook.xlsx.writeBuffer());

  const pages = await extractDocumentBuffer("grades.xlsx", buffer);
  assert.match(pages[0]?.text ?? "", /ورقة العمل: درجات/);
  assert.match(pages[0]?.text ?? "", /سارة\t95/);
});

test("rejects unsupported, empty, and legacy Word files", async () => {
  await assert.rejects(
    extractDocumentBuffer("notes.csv", Buffer.from("content")),
    /صيغة الملف غير مدعومة/
  );
  await assert.rejects(
    extractDocumentBuffer("notes.txt", Buffer.alloc(0)),
    /الملف المرفوع فارغ/
  );
  await assert.rejects(
    extractDocumentBuffer("notes.doc", Buffer.from("content")),
    /DOC غير مدعومة/
  );
});
