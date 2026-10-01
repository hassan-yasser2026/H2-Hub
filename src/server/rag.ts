import { GoogleGenAI } from "@google/genai";
import { QdrantClient } from "@qdrant/js-client-rest";
import { createHash } from "node:crypto";

export const EMBEDDING_MODEL = "gemini-embedding-001";
export const EMBEDDING_DIMENSIONS = 768;
export const DEFAULT_COLLECTION = "h2_hub_knowledge";
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
let qdrantClient: QdrantClient | undefined;
let collectionReady: Promise<void> | undefined;

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

export function getQdrantClient(): QdrantClient {
  const url = process.env.QDRANT_URL?.trim();
  const apiKey = process.env.QDRANT_API_KEY?.trim();
  if (!url || !apiKey) {
    throw new Error("QDRANT_URL and QDRANT_API_KEY must be configured on the server.");
  }
  qdrantClient ??= new QdrantClient({ url, apiKey, timeout: 20 });
  return qdrantClient;
}

export function getCollectionName(): string {
  return process.env.QDRANT_COLLECTION?.trim() || DEFAULT_COLLECTION;
}

export async function ensureKnowledgeCollection(): Promise<void> {
  if (!collectionReady) {
    collectionReady = (async () => {
      const client = getQdrantClient();
      const collectionName = getCollectionName();
      const collections = await client.getCollections();
      if (!collections.collections.some((collection) => collection.name === collectionName)) {
        await client.createCollection(collectionName, {
          vectors: { size: EMBEDDING_DIMENSIONS, distance: "Cosine" },
        });
      }
    })();
  }
  try {
    await collectionReady;
  } catch (error) {
    collectionReady = undefined;
    throw error;
  }
}

export async function searchKnowledge(question: string, limit = RAG_TOP_K): Promise<RetrievedChunk[]> {
  await ensureKnowledgeCollection();
  const [vector] = await embedTexts([question], "RETRIEVAL_QUERY");
  const response = await getQdrantClient().query(getCollectionName(), {
    query: vector,
    limit,
    score_threshold: RAG_SCORE_THRESHOLD,
    with_payload: true,
  });
  return response.points.flatMap((point) => {
    const payload = point.payload;
    const content = payload?.content;
    const source = payload?.source;
    if (typeof content !== "string" || typeof source !== "string") return [];
    const pageValue = payload?.page;
    const chunkIndexValue = payload?.chunkIndex;
    return [{
      id: String(point.id),
      source,
      page: typeof pageValue === "number" ? pageValue : null,
      chunkIndex: typeof chunkIndexValue === "number" ? chunkIndexValue : 0,
      content,
      score: point.score,
    }];
  });
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
