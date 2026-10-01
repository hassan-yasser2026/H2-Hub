# H2 Smart Hub

This repository contains the native Android app and the API server it uses. The web interface has been removed.

## Android app

Open the repository in Android Studio and build the debug app, or run this from the repository root on Windows:

```powershell
.\gradlew.bat :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

The Android app reads `SERVER_URL` from `app/android-defaults.properties`; local overrides belong in the ignored `app/android.properties` file. The production default is `https://h2-smart-hub-production.up.railway.app`.

Do not put Gemini or ElevenLabs credentials in Android properties or the app bundle. Configure `GEMINI_API_KEY` and, when needed, `ELEVEN_LABS_API_KEY` only in the server environment (Railway Variables for production).

If Gemini reports an exhausted daily quota, review the quota and billing status for the Google project that owns the server-side `GEMINI_API_KEY` in Google AI Studio. Enabling billing may allow paid usage, but does not remove all model quotas and can incur charges. The Android app retries transient connection failures; exhausted quotas are reported without retrying.

## API server

The Node.js API server is retained because the Android app uses it as a proxy for Gemini requests. To run it locally, install Node.js, install dependencies with `npm install`, set `GEMINI_API_KEY` in your environment, then run:

```powershell
npm run dev
```

For production, `npm run build` bundles the API server and `npm start` runs it. The server listens on `PORT` (default `3000`).

`GET /health` reports the Railway deployment commit (`deploymentCommit`), whether the feedback routes are present, whether AI provider keys are configured, and whether PostgreSQL persistence is configured. After a Railway deployment, verify that `deploymentCommit` matches the deployed GitHub SHA and `feedbackRoutesAvailable` is `true`. The PostgreSQL database is used for the course knowledge vector index; account/cloud-sync persistence is not implemented, so cloud-sync routes return `503` until authenticated accounts are added. The AI routes have per-IP rate limits, and the Gemini proxy accepts only the model actions used by the app.

### Smart Cat knowledge search (RAG)

When **الرد من المحتوى التعليمي** is enabled in Android settings, Smart Cat calls `POST /api/chat-rag`. The server embeds the question with Google's `gemini-embedding-001`, retrieves up to five relevant passages from PostgreSQL using pgvector cosine distance, and asks Gemini to answer only from those passages. Responses include inline passage references and a source list (PDF page numbers are included when available). PDF text is extracted with `pdf-parse`; Word `.docx` text uses Mammoth. The embedding model is Google's currently documented text embedding model; `text-embedding-004` is not used.

The vector store is the existing Railway PostgreSQL service and its attached volume. No extra hosted vector database is required. Embeddings use the existing server-side Gemini key; the app never receives the database URL or provider key. Use Google's free API tier if available for the key/account, and monitor its quotas; provider limits and free-tier eligibility can change.

Set these variables in the API server environment (for production, Railway Variables). For local ingestion, put them in the ignored root `.env` file or set them in your shell:

- `GEMINI_API_KEY`: server-only Google AI Studio key.
- `DATABASE_URL`: PostgreSQL connection string. In Railway, add a reference from the API service to the Postgres service's `DATABASE_URL` variable, for example `${{Postgres.DATABASE_URL}}`.
- PostgreSQL must have the `vector` extension enabled. The app expects `CREATE EXTENSION IF NOT EXISTS vector;` to have been run in that database.

To add course notes:

1. Put `.pdf`, `.docx`, or `.txt` files in the repository's `docs/` folder. Legacy `.doc` files must be saved as `.docx` first.
2. Ensure `DATABASE_URL` and `GEMINI_API_KEY` are available to the ingestion process. Do not put production secrets in Android configuration or commit them.
3. Run `npm run ingest`. The script extracts text, splits it into 700-word chunks with 100 words of overlap, embeds the chunks, and replaces the indexed corpus in PostgreSQL in one transaction. PDF sources retain page numbers; DOCX/TXT sources are labelled without a page number. Check extraction without credentials first with `npm run ingest -- --dry-run`.
4. The updated corpus is available to the live endpoint as soon as ingestion commits; no server redeploy is needed.

Five original, small educational PDFs are included under `docs/` as examples. To regenerate them, run `npm run docs:demo`. Run focused extraction checks with `npm run test:rag` and type-check/build with `npm run lint` and `npm run build`. Live answers additionally require valid Gemini and PostgreSQL variables and an ingested corpus.

To exercise the endpoint, send a `POST` to `/api/chat-rag` with JSON such as:

```json
{
  "message": "How do you solve 3x + 5 = 20?",
  "history": [{ "role": "user", "text": "How do you solve 3x + 5 = 20?" }],
  "systemInstruction": "You are Smart Cat, a helpful tutor."
}
```

The response shape is `{ "reply": "...[1]", "sources": [{ "reference": 1, "fileName": "...", "page": 1, "label": "...", "score": 0.8 }] }`. If retrieval finds no relevant passage, the endpoint says so instead of generating an unsupported general answer.

### Smart Cat image questions

The Android chat has camera and gallery buttons. Images are rotated using EXIF metadata, converted to JPEG, and compressed to at most 1 MiB before being stored in the app's private files and uploaded as multipart data. The server accepts JPG, PNG, and WebP up to 1 MiB, and asks Gemini Vision to read the problem and show a careful step-by-step explanation. A “حل تاني” action resubmits the same image and question.

The server first uses `GEMINI_VISION_MODEL` (default `gemini-2.5-flash`) with the existing server-side `GEMINI_API_KEY`; if Google reports that model is unavailable, it retries with `gemini-3.8-flash`. No Gemini key is bundled into Android. Gemini models have a limited free tier when available, but quotas and model eligibility can vary by account/region and can be exhausted. If the Google project has billing enabled, successful calls may incur token-based charges; this change does not enable billing or silently switch to a paid third-party model. Check current [Gemini API pricing](https://ai.google.dev/gemini-api/docs/pricing) and quotas before enabling paid use.

As an optional quota fallback, configure a server-side `OPENROUTER_API_KEY`. The server then tries `OPENROUTER_VISION_MODEL` (default `qwen/qwen3.8-27b:free`) only after Gemini returns a quota/rate-limit error. The server rejects fallback model names without the `:free` suffix; OpenRouter free models still have changing availability and request limits. Without the OpenRouter key, Gemini quota errors return an explicit message and no other provider is contacted. Both provider keys stay on the server. Images are capped at 1 MiB, and the server limits image-analysis requests to 30 per IP per 15 minutes.

The Smart Cat composer has one **+** menu for camera capture, gallery images, PDF files, plugins/tools, and deeper reasoning. The tools submenu includes the Socratic tutoring toggle, settings, and a new-chat shortcut. **فكّر بعمق أكبر** selects `gemini-3.1-pro-preview` for the next chat request and falls back to the standard flash model if needed; the selection can be toggled back off. Gemini keys remain server-only. In educational-content mode, the app does not fall back to ungrounded chat if the RAG endpoint is unavailable.

### Student learning profile

The Android app keeps a Room database (`h2_learning_profile.db`) on the device for a local copy of chat messages, locally generated conversation summaries, question timestamps, and detected topics. Existing chat history is copied into this database automatically; the app's existing storage remains unchanged. Android cloud backup and device transfer exclude this database. A topic is marked as a recurring weakness after three questions classified under it. A topic is marked as a strength when the student explicitly says they understood it.

Open **المزيد → ملفي التعليمي** to review total questions, active subjects, recurring weak topics, strengths, recent summaries, and a seven-day question activity chart. Classification is performed locally with a small Arabic/English keyword list and falls back to a short normalized topic label for unknown questions.

Conversation summaries are extractive and created on-device when a chat is closed or another conversation is selected. New questions are answered online by the server-side Gemini proxy; when educational-content or Socratic mode is enabled, Android sends the active conversation context to the matching server endpoint. The app does not attach past conversation summaries or the weakness list to these requests. If a student asks what they discussed before, Android searches the local summaries and answers without a network request. Messages and answers are still retained locally in the app's chat history and Room learning profile. Images/PDF attachments are sent to Gemini only when the student chooses to analyze them. Smart Cat response ratings are sent to the feedback API; feedback is stored server-side for review.

Long-term memory and its SQLite database stay on-device. The profile and chat preference backup rules exclude both `h2_learning_profile.db` and the `h2hub_data.xml` shared preferences file from Android cloud backup and device transfer.

### Socratic tutoring mode

Enable **وضع التدريس السقراطي** in Smart Cat settings. Text questions then use `POST /api/chat-socratic`, grounded in the course excerpts, which asks one guiding question at a time and keeps the current step until the student's answer is correct. Progress and the active problem are saved locally per chat session. Correct answers raise the challenge slightly; after three consecutive correct answers, difficulty returns to normal. Two consecutive incorrect answers simplify it. **الرد من المحتوى التعليمي** uses `POST /api/chat-rag` to retrieve course material and cite its sources. These modes send only the active conversation context and current question, not the student's long-term profile. The server uses its existing Gemini key and PostgreSQL/pgvector configuration.

### Interactive quizzes and flashcards

Use **اعمل اختبار** or **كروت مراجعة** below a Smart Cat answer. Android sends that answer and its student question to `POST /api/generate-quiz` with `mode` set to `quiz` or `flashcards` and a requested `count` from 5 to 10. The server uses its existing Gemini key and returns validated structured content; quiz items have four choices, the correct choice index, and an explanation. The quiz shows one question at a time with immediate feedback, progress, a final score, and replay. Flashcards flip on tap. Completed quiz scores are stored only on-device in Room (`learning_quiz_results`, database migration 3→4) and appear under **ملفي التعليمي → اختباراتي**. No paid provider or Android-side AI key is used.

### Math and interactive diagrams in chat

Smart Cat formats mathematical expressions as inline `$...$` or display `$$...$$` LaTeX and may return Mermaid fenced blocks for diagrams. Android renders LaTeX with the open-source KaTeX library and diagrams with Mermaid (both pinned to versioned jsDelivr URLs; KaTeX is MIT licensed and Mermaid is MIT licensed). These static library files are fetched by WebView when needed; chat text and rendered equations/diagrams are not sent to the CDN. An internet connection is required to load the renderers. Mermaid diagrams support pinch/controls and drag-to-pan; use the image button on an equation or diagram to save it under `Pictures/Smart Cat`. Simple curves use Mermaid `xychart-beta`; circuits and geometric explanations use labelled conceptual flowcharts.

### Smart Cat answer feedback

Every assistant response paired with a student question has 👍, 👎, and 🚩 controls in Android. Negative ratings can include an optional note; successful submissions show a thank-you message. `POST /api/feedback` records the response ID, question, reply, rating, and note in `data/feedback.json` by default. Set `FEEDBACK_STORE_PATH` to move that file; JSON storage is intended for a single server instance and is not durable on Railway's ephemeral filesystem, so attach a persistent volume and set a path such as `/data/smart-cat-feedback.json` before relying on it.

Set a long, private `ADMIN_FEEDBACK_PASSWORD` in the server environment (Railway Variables in production). Then visit `/admin/feedback`, enter the password, and load the negative-feedback list and rating statistics; the CSV button exports all received ratings. The admin API endpoints `/api/admin/feedback` and `/api/admin/feedback.csv` require `Authorization: Bearer <ADMIN_FEEDBACK_PASSWORD>`. If the password is unset, those endpoints fail closed. The dashboard uses DOM text nodes for feedback content rather than rendering untrusted HTML. Run `npm run test:feedback`, `npm run lint`, and `npm run build` to validate the server feature.
