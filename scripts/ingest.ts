import { readdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import dotenv from "dotenv";
import type { PoolClient } from "pg";
import { extractDocument } from "../src/server/documents.js";
import {
  createChunkId,
  EMBEDDING_DIMENSIONS,
  EMBEDDING_MODEL,
  embedTexts,
  ensureKnowledgeStore,
  getDatabasePool,
  serializeEmbedding,
  splitTextIntoChunks,
  type KnowledgeChunk,
} from "../src/server/rag.js";

dotenv.config();

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const docsDirectory = path.join(projectRoot, "docs");
const EMBEDDING_BATCH_SIZE = 16;
const INSERT_BATCH_SIZE = 64;

async function listDocumentFiles(directory: string): Promise<string[]> {
  const entries = await readdir(directory, { withFileTypes: true });
  const files: string[] = [];
  for (const entry of entries) {
    const entryPath = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      files.push(...await listDocumentFiles(entryPath));
    } else if (
      entry.isFile() &&
      [".pdf", ".docx", ".txt"].includes(path.extname(entry.name).toLowerCase())
    ) {
      files.push(entryPath);
    }
  }
  return files.sort((left, right) => left.localeCompare(right));
}

async function collectChunks(): Promise<KnowledgeChunk[]> {
  let documents: string[];
  try {
    documents = await listDocumentFiles(docsDirectory);
  } catch (error) {
    throw new Error(`Cannot read knowledge folder "${docsDirectory}". Create docs/ and add files first.`, {
      cause: error,
    });
  }

  if (documents.length === 0) {
    throw new Error("No PDF, DOCX, DOC, or TXT documents found in docs/.");
  }

  const chunks: KnowledgeChunk[] = [];
  for (const filePath of documents) {
    const fileName = path.relative(docsDirectory, filePath).split(path.sep).join("/");
    const pages = await extractDocument(filePath);
    if (pages.every((page) => !page.text.trim())) {
      throw new Error(`No searchable text found in "${fileName}". Scanned PDFs must be OCRed first.`);
    }
    let chunkIndex = 0;
    for (const page of pages) {
      for (const content of splitTextIntoChunks(page.text)) {
        chunks.push({
          id: createChunkId(fileName, page.page, chunkIndex, content),
          source: fileName,
          page: page.page,
          chunkIndex,
          content,
        });
        chunkIndex++;
      }
    }
  }
  if (chunks.length === 0) {
    throw new Error("The supported files in docs/ did not contain any readable text.");
  }
  return chunks;
}

async function insertChunks(client: PoolClient, chunks: KnowledgeChunk[], vectors: number[][]): Promise<void> {
  for (let start = 0; start < chunks.length; start += INSERT_BATCH_SIZE) {
    const batch = chunks.slice(start, start + INSERT_BATCH_SIZE);
    const values: unknown[] = [];
    const rows = batch.map((chunk, index) => {
      const offset = index * 6;
      values.push(
        chunk.id,
        chunk.source,
        chunk.page,
        chunk.chunkIndex,
        chunk.content,
        serializeEmbedding(vectors[start + index])
      );
      return `($${offset + 1}, $${offset + 2}, $${offset + 3}, $${offset + 4}, $${offset + 5}, $${offset + 6}::vector)`;
    });
    await client.query(
      `
        INSERT INTO h2_knowledge_chunks (id, source, page, chunk_index, content, embedding)
        VALUES ${rows.join(", ")}
      `,
      values
    );
    console.log(`Stored ${Math.min(start + batch.length, chunks.length)}/${chunks.length} chunks.`);
  }
}

async function main(): Promise<void> {
  const chunks = await collectChunks();
  if (process.argv.includes("--dry-run")) {
    console.log(
      `Dry run: extracted ${chunks.length} chunks from ${new Set(chunks.map((chunk) => chunk.source)).size} documents; no API or database was contacted.`
    );
    return;
  }
  if (!process.env.GEMINI_API_KEY) {
    throw new Error("Set GEMINI_API_KEY in the server environment before ingesting documents.");
  }
  if (!process.env.DATABASE_URL?.trim()) {
    throw new Error("Set DATABASE_URL to the Railway PostgreSQL database before ingesting documents.");
  }

  console.log(
    `Embedding ${chunks.length} chunks with ${EMBEDDING_MODEL} (${EMBEDDING_DIMENSIONS} dimensions).`
  );
  const vectors: number[][] = [];
  for (let start = 0; start < chunks.length; start += EMBEDDING_BATCH_SIZE) {
    const batch = chunks.slice(start, start + EMBEDDING_BATCH_SIZE);
    vectors.push(...await embedTexts(batch.map((chunk) => chunk.content)));
    console.log(`Embedded ${Math.min(start + batch.length, chunks.length)}/${chunks.length} chunks.`);
  }

  await ensureKnowledgeStore();
  const client = await getDatabasePool().connect();
  try {
    await client.query("BEGIN");
    await client.query("DELETE FROM h2_knowledge_chunks");
    await insertChunks(client, chunks, vectors);
    await client.query("COMMIT");
  } catch (error) {
    try {
      await client.query("ROLLBACK");
    } catch (rollbackError) {
      console.error("Failed to roll back the knowledge ingestion transaction:", rollbackError);
    }
    throw error;
  } finally {
    client.release();
  }
  console.log(`Indexed ${chunks.length} chunks from ${new Set(chunks.map((chunk) => chunk.source)).size} documents.`);
}

main().catch((error: unknown) => {
  console.error("Knowledge ingestion failed:", error);
  process.exitCode = 1;
});
