import assert from "node:assert/strict";
import { readdir } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { extractDocument } from "./documents.js";
import {
  buildRagContext,
  createChunkId,
  formatSourceLabel,
  splitTextIntoChunks,
} from "./rag.js";

test("splits text into overlapping word chunks", () => {
  const words = Array.from({ length: 15 }, (_, index) => `word${index + 1}`);
  const chunks = splitTextIntoChunks(words.join(" "), 8, 2);

  assert.equal(chunks.length, 3);
  assert.deepEqual(chunks[0].split(" ").slice(-2), ["word7", "word8"]);
  assert.deepEqual(chunks[1].split(" ").slice(0, 2), ["word7", "word8"]);
  assert.deepEqual(chunks.map((chunk) => chunk.split(" ").length), [8, 8, 3]);
});

test("creates stable point IDs for repeat ingestion", () => {
  const first = createChunkId("biology.pdf", 2, 0, "Mitochondria release energy.");
  assert.equal(first, createChunkId("biology.pdf", 2, 0, "Mitochondria release energy."));
  assert.notEqual(first, createChunkId("biology.pdf", 3, 0, "Mitochondria release energy."));
  assert.match(first, /^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u);
});

test("builds grounded context with the original file and page", () => {
  const context = buildRagContext([{
    id: "chunk-1",
    source: "biology.pdf",
    page: 4,
    chunkIndex: 0,
    content: "Mitochondria release usable energy during cellular respiration.",
    score: 0.82,
  }]);

  assert.match(context, /\[1\] Source: biology\.pdf, p\. 4/u);
  assert.match(context, /Mitochondria release usable energy/u);
  assert.equal(formatSourceLabel("lesson.docx", null), "lesson.docx (page not available)");
});

test("extracts searchable text and page numbers from the five sample PDFs", async () => {
  const docsDirectory = path.resolve("docs");
  const pdfFiles = (await readdir(docsDirectory)).filter((name) => name.endsWith(".pdf"));
  assert.equal(pdfFiles.length, 5);

  const exampleQuestions = new Map([
    ["01-algebra-foundations.pdf", {
      question: "How do you solve 3x + 5 = 20?",
      evidence: "subtracting 5",
    }],
    ["02-physics-motion-and-forces.pdf", {
      question: "What is Newton's second law?",
      evidence: "F = ma",
    }],
    ["03-biology-cells-and-energy.pdf", {
      question: "What do mitochondria do?",
      evidence: "release usable energy",
    }],
    ["04-chemistry-matter-and-reactions.pdf", {
      question: "Why must chemical equations be balanced?",
      evidence: "atoms are conserved",
    }],
    ["05-english-grammar-basics.pdf", {
      question: "What does a topic sentence do?",
      evidence: "introduces that idea",
    }],
  ]);
  for (const fileName of pdfFiles) {
    const pages = await extractDocument(path.join(docsDirectory, fileName));
    assert.equal(pages[0]?.page, 1, `${fileName} should retain its PDF page number`);
    assert.ok((pages[0]?.text.length ?? 0) > 100, `${fileName} should contain extractable text`);
    assert.ok(
      pages[0]?.text.toLowerCase().includes(exampleQuestions.get(fileName)?.evidence.toLowerCase() ?? ""),
      `The source should support the sample question: ${exampleQuestions.get(fileName)?.question}`
    );
  }
});
