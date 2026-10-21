# H2 Hub — Google Play materials

Draft prepared from the Android app and server implementation. Review every disclosure and replace the marked placeholders before publishing.

## App details

- App name: H2 Hub
- Android application ID: `com.aistudio.h2hub.nqpwbz`
- Category: Education
- Suggested audience: General education audience; complete Play Console's target-audience and content-rating questionnaires based on the final app experience. Do not select children as a target audience without completing the Families policy review.
- Current Android version: `1.3.0` (`versionCode` 4). Confirm whether this is the release intended for submission.

## Short description

Arabic (55 characters):  
`تعلّم، نظّم يومك، وتحدّث مع مساعدك الذكي في تطبيق واحد.`

English (50 characters):  
`Learn, plan your day, and chat with AI in one app.`

## Full description — Arabic

H2 Hub تطبيق تعليمي يجمع أدوات الذكاء الاصطناعي والإنتاجية. تحدث مع Smart Cat، واستخدم البحث والتفكير عند توفرهما. تدرب على تلاوة القرآن: اختر سورة واستمع للتلاوة وسجل قراءتك للحصول على تفريغ وملاحظات. استخدم شخصيات AI وH2 Code للبرمجة، وأنشئ مستندات وعروضاً ونظم مهامك. راجع ملخصاتك التعليمية، وزامن محادثاتك وذاكرتك عند تسجيل الدخول. تتوفر خدمة مجانية وخطط مدفوعة. يلزم الإنترنت لبعض الميزات. قد تخطئ إجابات AI؛ تحقق من المعلومات المهمة. التطبيق مساعد تعليمي.

## Full description — English

H2 Hub brings AI learning and productivity into one app. Chat with Smart Cat; use search and deeper reasoning when available. Practice Quran recitation and record your reading for a transcript and feedback. Ask specialist AI personas or use H2 Code for programming. Create documents, organize tasks, and review learning summaries. Sign-in enables conversation and learning-memory sync. Free usage and paid plans are available. AI may be inaccurate; verify important information.

## Data Safety preparation (verify in Play Console)

The following is a technical inventory, not a completed Google declaration. Confirm each item against the production configuration and the current Google Play form.

| Data category | Examples observed in the app | Purpose | Handling to declare/check |
|---|---|---|---|
| Personal info | Google account UID, email, display name, profile photo | Sign-in, account profile, syncing | Collected; account-linked |
| App activity | Chat prompts/replies, conversation history, summaries, learning topics and weak-point profile | Core AI features, sync, personalization | Collected; may be shared with AI processors |
| Audio | Quran recitation recording, transcript and assessment | Recitation assessment | User-submitted to the H2 Hub server and processed with Google Gemini |
| Photos and files | Images for vision analysis; subscription payment screenshot; documents selected for productivity tools | Requested feature processing and manual payment review | User-submitted; disclose destinations and retention |
| Identifiers | Firebase UID, anonymous device ID, Firebase Cloud Messaging token, advertising identifier where provided by Google Mobile Ads | Account/quota security, push notifications, advertising | Collected/used; review SDK disclosures and Ads settings |
| Financial info | Vodafone Cash number and manual subscription request/payment proof, if submitted | Subscription review and activation | User-provided; confirm server retention and who can access it |
| Diagnostics/usage | Network/service request metadata and provider/SDK diagnostics | Service operation, security, ads | Check Railway, Firebase, Google Mobile Ads and other provider settings |

### Third-party services identified in the code

- Firebase Authentication and Cloud Firestore for Google sign-in and account-linked conversation/learning sync.
- Firebase Cloud Messaging for push notifications.
- Google Gemini for AI chat, recitation analysis and related AI operations.
- AdMob / Google Mobile Ads for advertisements.
- The server also integrates external search and reasoning providers for selected features. Confirm the currently enabled provider names and their privacy terms before declaring them.
- Railway hosts the API. Payment review is manual; subscription proof is uploaded to the API.

### Items to resolve before submission

1. Replace the privacy-policy contact placeholder and publish the reviewed policy at a stable public HTTPS URL.
2. Confirm the account/data deletion process, including Firestore conversations, memory summaries, PostgreSQL subscription records and uploaded payment screenshots. The app has per-conversation deletion and sign-out, but no verified end-to-end account deletion flow.
3. Set and document retention periods for each cloud data type and provider.
4. Review AdMob consent/personalized-ad settings and any applicable consent flow for the countries where the app will be distributed.
5. Confirm the final target audience and complete the content-rating questionnaire in Play Console.
6. Confirm the final release version and signing certificate before upload.

## Store artwork status

- App icon: launcher icons exist in Android density-specific resources. A verified 512 × 512 Play Store source image has not been prepared.
- Feature graphic: 1024 × 500 artwork has not been prepared.
- Phone screenshots: capture at least four clean, production-like screens at the required Play Console dimensions. The connected device's current screenshot resolution does not match 1080 × 1920, so no resized or cropped images are represented as final store screenshots.
