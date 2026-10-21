export interface WebSource {
  title: string;
  url: string;
  snippet: string;
}

export interface ReferencedWebSource extends WebSource {
  reference: number;
}

export interface TavilySearchOptions {
  includeDomains?: string[];
  maxResults?: number;
}

export class ExternalServiceError extends Error {
  constructor(message: string, readonly status = 502) {
    super(message);
  }
}

const REQUEST_TIMEOUT_MS = 45_000;

export async function tavilySearch(query: string, apiKey: string): Promise<{
  answer: string;
  sources: WebSource[];
}>;
export async function tavilySearch(
  query: string,
  apiKey: string,
  options: TavilySearchOptions
): Promise<{ answer: string; sources: WebSource[] }>;
export async function tavilySearch(
  query: string,
  apiKey: string,
  options: TavilySearchOptions = {}
): Promise<{ answer: string; sources: WebSource[] }> {
  const response = await fetch("https://api.tavily.com/search", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${apiKey}`,
    },
    body: JSON.stringify({
      query: query.slice(0, 400),
      search_depth: "advanced",
      max_results: options.maxResults ?? 8,
      include_answer: true,
      include_raw_content: false,
      topic: "general",
      ...(options.includeDomains?.length ? { include_domains: options.includeDomains } : {}),
    }),
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
  });
  if (!response.ok) {
    throw new ExternalServiceError(`Tavily search failed (${response.status}).`);
  }
  const data = await response.json() as {
    answer?: unknown;
    results?: Array<{ title?: unknown; url?: unknown; content?: unknown }>;
  };
  const sources = (data.results ?? []).flatMap((item) => {
    if (typeof item.url !== "string" || !/^https?:\/\//iu.test(item.url)) return [];
    return [{
      title: typeof item.title === "string" && item.title.trim() ? item.title.trim() : item.url,
      url: item.url,
      snippet: typeof item.content === "string" ? item.content.trim().slice(0, 1_200) : "",
    }];
  });
  return { answer: typeof data.answer === "string" ? data.answer : "", sources };
}

export function isLinkRequest(message: string): boolean {
  return [
    /(?:عايز|عاوز|أريد|اريد|هات|ابعتلي|أعطني|اعطني)\s*(?:لي\s*)?(?:لينك|رابط)/iu,
    /(?:لينك|رابط)\s*(?:محاضرة|كتاب|فيديو|كورس|دورة|مرجع)/iu,
    /where\s+can\s+i\s+(?:find|watch|read)/iu,
    /give\s+me\s+a\s+link/iu,
    /link\s+to/iu,
  ].some((pattern) => pattern.test(message));
}

export function isArabicSearchQuery(query: string): boolean {
  return /[\u0600-\u06ff]/u.test(query);
}

export function filterReferencedSources(
  sources: WebSource[],
  reply: string
): ReferencedWebSource[] {
  const references = new Set(
    Array.from(reply.matchAll(/\[(\d+)\]/gu), (match) => Number(match[1]))
  );
  return sources.flatMap((source, index) =>
    references.has(index + 1) ? [{ ...source, reference: index + 1 }] : []
  );
}

export function hasValidSourceReferences(reply: string, sourceCount: number): boolean {
  const references = Array.from(reply.matchAll(/\[(\d+)\]/gu), (match) => Number(match[1]));
  return references.length > 0 &&
    references.every((reference) => reference >= 1 && reference <= sourceCount);
}

export interface GroqMessage {
  role: "system" | "user" | "assistant";
  content: string;
}

const DEFAULT_THINKING_MODELS = [
  "deepseek-r1-distill-llama-70b",
  "qwen/qwen3-32b",
  "openai/gpt-oss-120b",
];

export function stripReasoning(text: string): string {
  return text.replace(/<think>[\s\S]*?<\/think>/giu, "").replace(/^[\s\S]*?<\/think>/iu, "").trim();
}

export async function groqThinkingChat(
  messages: GroqMessage[],
  apiKey: string
): Promise<{ reply: string; model: string; thinkingSeconds: number }> {
  const configured = process.env.GROQ_THINKING_MODEL?.trim();
  const models = [...new Set([...(configured ? [configured] : []), ...DEFAULT_THINKING_MODELS])];
  let lastError = "";
  for (const model of models) {
    const startedAt = Date.now();
    const response = await fetch("https://api.groq.com/openai/v1/chat/completions", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${apiKey}`,
      },
      body: JSON.stringify({
        model,
        messages,
        temperature: 0.6,
        max_completion_tokens: 4_096,
      }),
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS * 2),
    });
    if (response.ok) {
      const data = await response.json() as {
        choices?: Array<{ message?: { content?: unknown } }>;
      };
      const raw = data.choices?.[0]?.message?.content;
      const reply = typeof raw === "string" ? stripReasoning(raw) : "";
      if (reply) {
        return {
          reply,
          model,
          thinkingSeconds: Math.max(1, Math.round((Date.now() - startedAt) / 1_000)),
        };
      }
      lastError = `${model}: empty reply`;
      continue;
    }
    lastError = `${model}: HTTP ${response.status}`;
    if (response.status === 401 || response.status === 403) {
      throw new ExternalServiceError("Groq rejected the configured API key.", 503);
    }
    if (response.status === 429) {
      throw new ExternalServiceError("Groq rate limit reached.", 429);
    }
  }
  throw new ExternalServiceError(`Groq thinking request failed (${lastError}).`);
}

export function parseHistory(raw: unknown, limit = 8): GroqMessage[] {
  if (!Array.isArray(raw)) return [];
  return raw.slice(-limit).flatMap((turn: unknown) => {
    if (typeof turn !== "object" || turn == null) return [];
    const item = turn as { role?: unknown; text?: unknown; content?: unknown };
    const text = typeof item.text === "string"
      ? item.text
      : typeof item.content === "string"
        ? item.content
        : "";
    if (!text.trim()) return [];
    return [{
      role: item.role === "user" ? "user" as const : "assistant" as const,
      content: text.slice(0, 8_000),
    }];
  });
}
