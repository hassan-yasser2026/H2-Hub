import { GoogleGenAI } from "@google/genai";
import { createHash } from "node:crypto";
import { Pool } from "pg";

export const EMBEDDING_MODEL = "gemini-embedding-001";
export const EMBEDDING_DIMENSIONS = 768;
export const KNOWLEDGE_TABLE = "h2_knowledge_chunks";
export const MAX_CHUNK_WORDS = 700;
export const CHUNK_OVERLAP_WORDS = 100;
export const RAG_TOP_K = 5;
export const RAG_SCORE_THRESHOLD = 0.35;

export type KnowledgeChunk = {
  id: string;
  source: string;
  page: number | null;
  chunkIndex: number;
  content: string;
};

export type RetrievedChunk = KnowledgeChunk & { score: number };

let embeddingClient: GoogleGenAI | undefined;
let databasePool: Pool | undefined;
let knowledgeStoreReady: Promise<void> | undefined;

export function splitTextIntoChunks(
  text: string,
  maxWords = MAX_CHUNK_WORDS,
  overlapWords = CHUNK_OVERLAP_WORDS
): string[] {
  if (!Number.isInteger(maxWords) || maxWords < 1) {
    throw new RangeError("maxWords must be a positive integer.");
  }
  if (!Number.isInteger(overlapWords) || overlapWords < 0 || overlapWords >= maxWords) {
    throw new RangeError("overlapWords must be a non-negative integer smaller than maxWords.");
  }

  const words = text.trim().split(/\s+/u).filter(Boolean);
  if (words.length === 0) return [];

  const chunks: string[] = [];
  const step = maxWords - overlapWords;
  for (let start = 0; start < words.length; start += step) {
    chunks.push(words.slice(start, start + maxWords).join(" "));
  }
  return chunks;
}

export function createChunkId(source: string, page: number | null, chunkIndex: number, content: string): string {
  const hash = createHash("sha256")
    .update(`${source}\0${page ?? "none"}\0${chunkIndex}\0${content}`)
    .digest("hex")
    .slice(0, 32)
    .split("");
  hash[12] = "5";
  hash[16] = ((parseInt(hash[16], 16) & 0x3) | 0x8).toString(16);
  const value = hash.join("");
  return `${value.slice(0, 8)}-${value.slice(8, 12)}-${value.slice(12, 16)}-${value.slice(16, 20)}-${value.slice(20)}`;
}

export async function embedTexts(
  texts: string[],
  taskType: "RETRIEVAL_QUERY" | "RETRIEVAL_DOCUMENT" = "RETRIEVAL_DOCUMENT"
): Promise<number[][]> {
  if (!process.env.GEMINI_API_KEY) {
    throw new Error("GEMINI_API_KEY is required for RAG embeddings.");
  }
  if (texts.length === 0) return [];

  embeddingClient ??= new GoogleGenAI({ apiKey: process.env.GEMINI_API_KEY });
  const response = await embeddingClient.models.embedContent({
    model: EMBEDDING_MODEL,
    contents: texts,
    config: { outputDimensionality: EMBEDDING_DIMENSIONS, taskType },
  });
  const embeddings = response.embeddings?.map((item) => item.values ?? []);
  if (
    !embeddings ||
    embeddings.length !== texts.length ||
    embeddings.some((vector) => vector.length !== EMBEDDING_DIMENSIONS)
  ) {
    throw new Error("Gemini returned an incomplete or invalid embedding response.");
  }
  return embeddings;
}

export function getDatabasePool(): Pool {
  const connectionString = process.env.DATABASE_URL?.trim();
  if (!connectionString) {
    throw new Error("DATABASE_URL must be configured on the server.");
  }
  databasePool ??= new Pool({
    connectionString,
    connectionTimeoutMillis: 10_000,
    idleTimeoutMillis: 30_000,
    max: 5,
  });
  return databasePool;
}

export function serializeEmbedding(vector: number[]): string {
  if (
    vector.length !== EMBEDDING_DIMENSIONS ||
    vector.some((value) => !Number.isFinite(value))
  ) {
    throw new Error(`Embedding vectors must contain ${EMBEDDING_DIMENSIONS} finite numbers.`);
  }
  return `[${vector.join(",")}]`;
}

export async function ensureKnowledgeStore(): Promise<void> {
  if (!knowledgeStoreReady) {
    knowledgeStoreReady = (async () => {
      const pool = getDatabasePool();
      const extension = await pool.query<{ extversion: string }>(
        "SELECT extversion FROM pg_extension WHERE extname = 'vector'"
      );
      if (extension.rowCount !== 1) {
        throw new Error("The pgvector extension is not enabled in the PostgreSQL database.");
      }
      await pool.query(`
        CREATE TABLE IF NOT EXISTS ${KNOWLEDGE_TABLE} (
          id text PRIMARY KEY,
          source text NOT NULL,
          page integer,
          chunk_index integer NOT NULL,
          content text NOT NULL,
          embedding vector(${EMBEDDING_DIMENSIONS}) NOT NULL
        )
      `);
      await pool.query(`
        CREATE INDEX IF NOT EXISTS h2_knowledge_chunks_embedding_hnsw_idx
        ON ${KNOWLEDGE_TABLE} USING hnsw (embedding vector_cosine_ops)
      `);
    })();
  }
  try {
    await knowledgeStoreReady;
  } catch (error) {
    knowledgeStoreReady = undefined;
    throw error;
  }
}

export async function searchKnowledge(question: string, limit = RAG_TOP_K): Promise<RetrievedChunk[]> {
  await ensureKnowledgeStore();
  const [vector] = await embedTexts([question], "RETRIEVAL_QUERY");
  const result = await getDatabasePool().query<RetrievedChunk>(
    `
      SELECT id, source, page, chunk_index AS "chunkIndex", content,
             1 - (embedding <=> $1::vector) AS score
      FROM ${KNOWLEDGE_TABLE}
      WHERE 1 - (embedding <=> $1::vector) >= $2
      ORDER BY embedding <=> $1::vector
      LIMIT $3
    `,
    [serializeEmbedding(vector), RAG_SCORE_THRESHOLD, Math.min(Math.max(limit, 1), RAG_TOP_K)]
  );
  return result.rows.map((row) => ({ ...row, score: Number(row.score) }));
}

export function formatSourceLabel(source: string, page: number | null): string {
  const fileName = source.replaceAll("\\", "/");
  return page == null ? `${fileName} (page not available)` : `${fileName}, p. ${page}`;
}

export function buildRagContext(chunks: RetrievedChunk[]): string {
  return chunks.map((chunk, index) =>
    `[${index + 1}] Source: ${formatSourceLabel(chunk.source, chunk.page)}\n${chunk.content}`
  ).join("\n\n");
}
