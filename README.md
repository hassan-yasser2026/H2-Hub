# H2 Smart Hub

This repository contains the native Android app and the API server it uses. The web interface has been removed.

## Android app

Open the repository in Android Studio and build the debug app, or run this from the repository root on Windows:

```powershell
.\gradlew.bat :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Google sign-in is required so conversations, subscription status, server-side usage, saved study schedules and sessions, lessons, educational documents, Quran recitation records, and app preferences follow the Firebase account across devices. Existing local schedules, documents, and recitation records are copied to the first signed-in account; switching accounts loads that account's data instead. AdMob remains disabled (`ADS_ENABLED = false` in `app/build.gradle.kts`) until it is intentionally enabled.

The Android UI uses shared dark surface colors and rounded shapes from `ui/theme`, with reusable `HubCard` and `HubSectionHeading` components for consistent section layouts. `AccentColors` assigns each section and AI persona its own accent without changing the shared layout. The dashboard, persona grid, friend cards, and sign-in screen adapt their spacing and component sizes to compact, wide, and short displays.

### Subscription plan catalog (stage 1)

The API initializes `subscription_plans`, `user_subscriptions`, and `free_user_daily_usage` when its existing PostgreSQL subscription schema is initialized. `GET /api/subscription/plans` returns the five server-owned EGP plans and their message allowances/features. Plan rows are seeded idempotently from `src/server/subscriptions.ts`. The manual-payment flow accepts these plans alongside the legacy monthly, quarterly, and yearly plans; administrator approval activates a metered subscription for a new plan.

The Android app reads `SERVER_URL` from `app/android-defaults.properties`; local overrides belong in the ignored `app/android.properties` file. The production default is `https://h2-smart-hub-production.up.railway.app`.

### Conversation sync (stages 1–2)

Firebase Auth is configured for Google sign-in. Firestore is enabled in project `h2-hub` in `europe-west1` (the existing database location; it cannot be changed to `eur3`). The Android app includes the Firestore SDK, and `firebase.json` targets `firestore.rules`. The rules restrict each user's documents to that authenticated Firebase UID and prevent clients from changing `subscriptionStatus`, which remains server-owned. The rules were deployed to `h2-hub`.

When a user signs in, conversation documents and messages are stored under `users/{uid}/conversations`; the app loads the latest 50 conversations and listens for remote changes. Room is the local cache, and Firestore's persistent offline queue retains cloud writes while disconnected. On first launch after this update, the prior `SharedPreferences` conversation store is migrated into Room, then its duplicate chat arrays are cleared. Existing local history is uploaded to the first signed-in account. Switching accounts clears the prior account's Room conversation cache before loading the new UID's history. Image-analysis messages sync their text/question but not the device-local image file path or image bytes. Subscription status remains in the API server, not Firestore.

The account privacy notice now explains cloud storage and cross-device sync. Deploy subsequent rule changes with `npx --yes firebase-tools@latest deploy --only firestore:rules --project h2-hub`.

### Conversation sidebar (stage 3)

The Smart Cat, persona, friend, and H2 Code sidebars display conversations under relative-time groups (today, last 7 days, last 30 days, then Arabic month/year). Entries show the title only; their activity time is represented by the group heading. The groups use the latest activity timestamp so active conversations move to the top and into the appropriate group.

Speech playback uses the Arabic voices installed in Android's text-to-speech engine. The voice choice adjusts pitch, and the chat settings control playback speed; available pronunciation depends on the device's installed voice data. Quran recitation streams as media, while microphone recognition and recitation assessment require microphone permission.

Do not put Gemini or ElevenLabs credentials in Android properties or the app bundle. Configure `GEMINI_API_KEY` and, when needed, `ELEVEN_LABS_API_KEY` only in the server environment (Railway Variables for production).

If Gemini reports an exhausted daily quota, review the quota and billing status for the Google project that owns the server-side `GEMINI_API_KEY` in Google AI Studio. Enabling billing may allow paid usage, but does not remove all model quotas and can incur charges. The Android app retries transient connection failures; exhausted quotas are reported without retrying.

## API server

The Node.js API server is retained because the Android app uses it as a proxy for Gemini requests. To run it locally, install Node.js, install dependencies with `npm install`, set `GEMINI_API_KEY` in your environment, then run:

```powershell
npm run dev
```

For production, `npm run build` bundles the API server and `npm start` runs it. The server listens on `PORT` (default `3000`).

Image generation is controlled by `IMAGE_GENERATION_ENABLED` and is disabled by default. Set it to `false` in Railway Variables to pause image generation; `/api/generate-image` then returns HTTP 503, while the Android **ارسم** control displays a maintenance notice. The app reads the current setting from `GET /api/feature-flags`. Set the variable to `true` to enable generation again.

For generated-image narration and chat read-aloud, Android calls the server's text-to-speech endpoint. The server uses Google Cloud Text-to-Speech with the `ar-XA-Wavenet-B` or `ar-XA-Wavenet-C` voice when `GOOGLE_CLOUD_TTS_API_KEY` is configured in Railway; otherwise, or if that service fails, it falls back to Gemini TTS. Android Text-to-Speech remains the device-side fallback if server speech is unavailable. Keep the Google key only in Railway Variables, never in the Android app or source control.

### Manual subscriptions (stage 2A)

The Android **اشتراكي** screen loads the server-owned catalog from `GET /api/subscription/plans`. The current plans are trial (EGP 100/30 days/500 messages), basic (EGP 1,000/30 days/5,000 messages), premium (EGP 2,000/30 days/15,000 messages), annual (EGP 10,000/365 days/100,000 messages), and annual premium (EGP 20,000/365 days/300,000 messages). It asks the user for a Vodafone Cash mobile number (`01067254988`); payment requests remain subject to manual admin review. Existing monthly, quarterly, and yearly plan identifiers are retained for legacy subscription records.

`DATABASE_URL` is required. Subscription tables are created on first use in PostgreSQL. Transfer screenshots are validated as JPEG, PNG, or WebP, limited to 5 MiB, and stored as `BYTEA` in PostgreSQL rather than on Railway's ephemeral local filesystem. The admin page at `/admin` adds a subscription-request queue; an authenticated admin can inspect the proof and approve or reject pending requests with a reason.

The Android client uses these endpoints:

- `POST /api/subscription/register` — associate the device ID and mobile number.
- `GET /api/subscription/status?deviceId=...` — read the subscription and recent requests.
- `POST /api/subscription/generate-code` — create a payment request and server-priced code.
- `POST /api/subscription/upload-screenshot` — upload proof for a draft request.
- `POST /api/subscription/request` — submit the draft for manual review.

Admin operations use the existing JWT-protected `/api/admin/subscriptions` routes. A device ID identifies an installation; it is not account authentication or proof of a legitimate payment, so this manual flow should not be treated as a strong anti-fraud boundary. Renewal extension, push notices, and subscription analytics are not part of stage 2A.

### Message quotas (stage 2)

New subscriptions draw from their plan's monthly/yearly message balance. Users without an active subscription share a limit of 20 messages per Egyptian calendar day; balances reset at midnight in `Africa/Cairo`. A current legacy subscriber (monthly, quarterly, or yearly plan from before the new catalog) remains unlimited until the recorded end date, then returns to the free daily limit. Quran recitation does not consume messages. Free users and the trial plan cannot generate images; image generation is available from the basic plan upward.

Operation costs are enforced server-side: ordinary chat/H2 Code 1, web search 2, thinking 2, search plus thinking 3, image analysis 3, and image generation 20 messages. Quota is reserved atomically before processing and refunded when the request fails. Insufficient balance returns HTTP 429 with `used`, `limit`, `required`, `remaining`, and `resetAt`; a feature disabled by the plan returns HTTP 403. API App Check enforcement is controlled by `FIREBASE_APP_CHECK_MODE`: use `unenforced` during rollout, which accepts requests without an App Check token but still verifies any token that is supplied; use `enforced` only after clients are ready. An invalid supplied token is rejected in either mode. If omitted, the server temporarily defaults to `unenforced`. Signed-in requests also include a Firebase ID token, which is verified independently. Anonymous quota remains keyed to the installation ID in `X-H2-Device-Id`; signed-in quota uses the verified Firebase UID. The first authenticated request binds that installation to its UID, transfers its existing daily usage, and attaches existing legacy subscriptions without changing their legacy status. A device already bound to another UID is rejected. `GET /api/subscription/usage?deviceId=...` returns the current balance and plan status.

The API App Check mode is separate from Firebase Console enforcement for Firebase products: enabling enforcement for Firestore or another Firebase product does not enforce this Railway API, and `FIREBASE_APP_CHECK_MODE=unenforced` does not weaken Firebase product rules. The Android app uses the Firebase debug provider in debug builds and Play Integrity in release builds. Debug App Check tokens must be registered in Firebase Console and treated like credentials; never commit or share them.

### Subscription and quota UI (stage 3)

The Android subscription screen fetches the active five-plan catalog from `GET /api/subscription/plans` and creates manual-payment requests using the selected server plan identifier. Smart Cat, persona chats, and H2 Code read `GET /api/subscription/usage`; their balance is shown as a compact toolbar pill that opens a detail sheet, distinguishing free daily quota, metered plans, and unlimited legacy subscriptions. Low-balance warnings begin at 20% and become critical at 5%. Sending, search, thinking, and image generation controls are disabled when their remaining message balance cannot cover the operation. Chat screens omit the separate balance banner and AdMob banner to maximize conversation space. Quran recitation stays outside the quota UI.

Signed-in subscriptions, payment requests, and free-message balances are scoped to the verified Firebase account UID, not the phone's device ID. Switching Google accounts on one phone therefore starts a separate account balance, while the same account keeps its subscription across devices. Signed-out usage remains device-scoped.

The authenticated `/api/admin/dashboard` response includes active subscriber counts per plan, total recorded quota consumption, service operation counts, and active legacy subscribers (at most 100 records in the detail list). Service counters start when quota instrumentation is deployed; they do not reconstruct service usage from before that point. The dashboard does not expose subscriber phone numbers.

### Push notifications (Firebase Cloud Messaging)

The Android app uses native Firebase Messaging. In Firebase Console, register an Android app with package name `com.aistudio.h2hub.nqpwbz`, download `google-services.json`, and place it at `app/google-services.json`. This client configuration can be committed; never commit the Firebase service-account key. The app asks for notification permission once after opening; on Android 13 and newer, users must grant `POST_NOTIFICATIONS`. Device registration is stored in the existing PostgreSQL database and requires `DATABASE_URL`. Tokens can be registered at `POST /api/device/register` (also available at `POST /api/push/token`).

Create a Firebase service account with permission to send Firebase Cloud Messaging messages. Store the complete service-account JSON in Railway as `FIREBASE_SERVICE_ACCOUNT_JSON`, and set a separate random `ADMIN_PUSH_PASSWORD` of at least 24 characters. Never put the service-account key or the admin password in Android configuration or Git. The API fails closed when either setting is missing.

After deploying the API and rebuilding the Android app, send a broadcast with an authenticated request to `POST /api/admin/send-notification` (also available at `POST /api/admin/push`):

```powershell
Invoke-RestMethod -Method Post `
  -Uri "https://YOUR-API-HOST/api/admin/send-notification" `
  -Headers @{ Authorization = "Bearer YOUR_ADMIN_PUSH_PASSWORD" } `
  -ContentType "application/json" `
  -Body '{"title":"إشعار تجريبي","body":"تم إعداد إشعارات H2 Hub."}'
```

The response reports attempted, sent, and failed device counts. Invalid/uninstalled device tokens are removed automatically. Test delivery on a physical Android device with notification permission enabled; compiling the app alone does not verify Firebase project credentials or Railway delivery.

Background Smart Cat text requests are stored in PostgreSQL by `POST /api/chat-async`; the Android app persists the pending request locally and uses WorkManager to retrieve the saved reply. Each request carries the originating chat message UUID as its idempotency key. PostgreSQL serializes submissions by that key and commits the quota reservation together with the job row, so a retry with the same payload returns the existing job without another quota reservation; reusing a key for different content is rejected. WorkManager retries the saved request with the same key. When processing finishes, the server sends a data-only FCM message to that device. `POST /api/chat-async/:jobId/result` requires both the job ID and its registered device token. Completed requests are retained for 30 days. This background route currently handles standard text chats; Socratic, course-search, and attachment requests continue using their existing synchronous routes.

`GET /api/quran/daily-verse` returns the same short Quran verse for a given UTC day. The app schedules a periodic WorkManager reminder using the locally saved time and also supports exam-specific reminders saved on-device. WorkManager delivery is battery- and OS-managed, so a selected time is approximate rather than an exact-alarm guarantee. To send an optional app-update notification, use the authenticated broadcast endpoint with `"type":"app_update"` in its JSON body; the user must enable app updates in notification settings.

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

The Smart Cat composer has one **+** menu for camera capture, gallery images, PDF files, plugins/tools, and deeper reasoning. Chat PDF attachments are read off the UI thread and capped at 10 MiB to avoid excessive memory use. The tools submenu includes the Socratic tutoring toggle, settings, and a new-chat shortcut. **فكّر بعمق أكبر** selects `gemini-3.1-pro-preview` for the next chat request and falls back to the standard flash model if needed; the selection can be toggled back off. Gemini keys remain server-only. In educational-content mode, the app does not fall back to ungrounded chat if the RAG endpoint is unavailable.

### Student learning profile and account memory

The Android app keeps a Room database (`h2_learning_profile.db`) on the device for locally detected question topics, question timestamps, strengths, and an extractive summary copy. Existing chat history is copied into this database automatically; Android cloud backup and device transfer exclude this database. Locally, a topic is marked as a recurring weakness after three questions classified under it. A topic is marked as a strength when the student explicitly says they understood it.

Open **المزيد → ملفي التعليمي** to review total questions, active subjects, recurring weak topics, strengths, recent cloud summaries, a long-term learning summary, and a seven-day question activity chart. Classification is performed locally with a small Arabic/English keyword list and falls back to a short normalized topic label for unknown questions.

When signed in, closing/selecting away from a chat requests a Gemini summary immediately; otherwise WorkManager schedules it after 24 hours without new messages and waits for network connectivity. The server verifies the Firebase ID token and saves summaries under `users/{uid}/memorySummaries/{conversationId}`; the long-term profile and recurring-topic counts are stored in `users/{uid}/memory/profile`, `users/{uid}/memory/longTermSummary`, and `users/{uid}/memory/weakPoints`. A combined long-term summary is refreshed every five newly summarized conversations. Each authenticated chat request loads the account's last three summaries, long-term summary, and recurring weak topics from Firestore and adds this context to the server-side AI instructions. Requests are scoped to the verified Firebase UID; the client cannot select another account's memory.

The backend must have `FIREBASE_SERVICE_ACCOUNT_JSON` configured for the same Firebase project as the Android app. The normal chat flow remains available while signed out, but account memory is only loaded or saved for a valid signed-in Firebase user. Images/PDF attachments are sent to Gemini only when the student chooses to analyze them. Smart Cat response ratings are sent to the feedback API; feedback is stored server-side for review.

### Socratic tutoring mode

Enable **وضع التدريس السقراطي** in Smart Cat settings. Text questions then use `POST /api/chat-socratic`, grounded in the course excerpts, which asks one guiding question at a time and keeps the current step until the student's answer is correct. Progress and the active problem are saved locally per chat session. Correct answers raise the challenge slightly; after three consecutive correct answers, difficulty returns to normal. Two consecutive incorrect answers simplify it. **الرد من المحتوى التعليمي** uses `POST /api/chat-rag` to retrieve course material and cite its sources. These modes send only the active conversation context and current question, not the student's long-term profile. The server uses its existing Gemini key and PostgreSQL/pgvector configuration.

### Interactive quizzes and flashcards

Use **اعمل اختبار** or **كروت مراجعة** below a Smart Cat answer. Android sends that answer and its student question to `POST /api/generate-quiz` with `mode` set to `quiz` or `flashcards` and a requested `count` from 5 to 10. The server uses its existing Gemini key and returns validated structured content; quiz items have four choices, the correct choice index, and an explanation. The quiz shows one question at a time with immediate feedback, progress, a final score, and replay. Flashcards flip on tap. Completed quiz scores are stored only on-device in Room (`learning_quiz_results`, database migration 3→4) and appear under **ملفي التعليمي → اختباراتي**. No paid provider or Android-side AI key is used.

### Math and interactive diagrams in chat

Smart Cat formats mathematical expressions as inline `$...$` or display `$$...$$` LaTeX and may return Mermaid fenced blocks for diagrams. Android renders LaTeX with the open-source KaTeX library and diagrams with Mermaid (both pinned to versioned jsDelivr URLs; KaTeX is MIT licensed and Mermaid is MIT licensed). These static library files are fetched by WebView when needed; chat text and rendered equations/diagrams are not sent to the CDN. An internet connection is required to load the renderers. Mermaid diagrams support pinch/controls and drag-to-pan; use the image button on an equation or diagram to save it under `Pictures/Smart Cat`. Simple curves use Mermaid `xychart-beta`; circuits and geometric explanations use labelled conceptual flowcharts.

### Smart Cat answer feedback

Every assistant response paired with a student question has 👍, 👎, and 🚩 controls in Android. Negative ratings can include an optional note; successful submissions show a thank-you message. `POST /api/feedback` records the response ID, question, reply, rating, and note in `data/feedback.json` by default. Set `FEEDBACK_STORE_PATH` to move that file; JSON storage is intended for a single server instance and is not durable on Railway's ephemeral filesystem, so attach a persistent volume and set a path such as `/data/smart-cat-feedback.json` before relying on it.

Set a long, private `ADMIN_FEEDBACK_PASSWORD` in the server environment (Railway Variables in production). Then visit `/admin/feedback`, enter the password, and load the negative-feedback list and rating statistics; the CSV button exports all received ratings. The admin API endpoints `/api/admin/feedback` and `/api/admin/feedback.csv` require `Authorization: Bearer <ADMIN_FEEDBACK_PASSWORD>`. If the password is unset, those endpoints fail closed. The dashboard uses DOM text nodes for feedback content rather than rendering untrusted HTML. Run `npm run test:feedback`, `npm run lint`, and `npm run build` to validate the server feature.
