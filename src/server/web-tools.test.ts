import assert from "node:assert/strict";
import { test } from "node:test";
import {
  filterReferencedSources,
  hasValidSourceReferences,
  isArabicSearchQuery,
  isLinkRequest,
  tavilySearch,
} from "./web-tools.js";

test("Tavily uses advanced English-oriented search options and link domains", async () => {
  const originalFetch = globalThis.fetch;
  let requestBody: Record<string, unknown> | undefined;
  globalThis.fetch = async (_input, init) => {
    requestBody = JSON.parse(String(init?.body)) as Record<string, unknown>;
    return new Response(JSON.stringify({
      answer: "A search summary.",
      results: [{
        title: "Node.js releases",
        url: "https://nodejs.org/en/about/previous-releases",
        content: "Current release information.",
      }],
    }), { status: 200 });
  };

  try {
    const result = await tavilySearch("latest Node.js release", "test-key", {
      includeDomains: ["nodejs.org"],
      maxResults: 10,
    });

    assert.equal(requestBody?.query, "latest Node.js release");
    assert.equal(requestBody?.search_depth, "advanced");
    assert.equal(requestBody?.max_results, 10);
    assert.equal(requestBody?.include_answer, true);
    assert.equal(requestBody?.include_raw_content, false);
    assert.equal(requestBody?.topic, "general");
    assert.deepEqual(requestBody?.include_domains, ["nodejs.org"]);
    assert.equal(result.answer, "A search summary.");
    assert.equal(result.sources[0]?.title, "Node.js releases");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("link intent detection covers Arabic and English direct-link requests", () => {
  assert.equal(isLinkRequest("عايز لينك محاضرة عن التفاضل"), true);
  assert.equal(isLinkRequest("أريد رابط كتاب الفيزياء"), true);
  assert.equal(isLinkRequest("give me a link to a calculus lecture"), true);
  assert.equal(isLinkRequest("اشرح لي التفاضل"), false);
});

test("only cited sources are returned and their original citation numbers remain intact", () => {
  const sources = [
    { title: "Source 1", url: "https://example.com/1", snippet: "One" },
    { title: "Source 2", url: "https://example.com/2", snippet: "Two" },
    { title: "Source 3", url: "https://example.com/3", snippet: "Three" },
  ];

  assert.deepEqual(filterReferencedSources(sources, "See [1] and [3]."), [
    { ...sources[0], reference: 1 },
    { ...sources[2], reference: 3 },
  ]);
});

test("search replies must cite at least one available source and no unknown references", () => {
  assert.equal(hasValidSourceReferences("Supported by [1] and [3].", 3), true);
  assert.equal(hasValidSourceReferences("Supported by [4].", 3), false);
  assert.equal(hasValidSourceReferences("No citations.", 3), false);
});

test("Arabic queries can be identified for English translation before web search", () => {
  assert.equal(isArabicSearchQuery("آخر إصدار من Node.js"), true);
  assert.equal(isArabicSearchQuery("latest Node.js release"), false);
});
