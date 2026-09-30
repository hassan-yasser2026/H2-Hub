import express from "express";
import { GoogleGenAI, Type } from "@google/genai";
import dotenv from "dotenv";
import multer from "multer";
import rateLimit from "express-rate-limit";

dotenv.config();

const app = express();
const PORT = Number(process.env.PORT || 3000);
app.set("trust proxy", 1);

// Initialize Google Gen AI securely on the server
const ai = new GoogleGenAI({
  apiKey: process.env.GEMINI_API_KEY,
  httpOptions: {
    headers: {
      "User-Agent": "aistudio-build",
    },
  },
});

// Setup express middle-wares
app.use("/api", rateLimit({
  windowMs: 15 * 60 * 1000,
  limit: 120,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: "تم تجاوز عدد الطلبات المسموح به مؤقتاً. حاول مرة أخرى لاحقاً." },
}));
app.use("/v1beta/models", rateLimit({
  windowMs: 15 * 60 * 1000,
  limit: 60,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: "تم تجاوز عدد طلبات الذكاء الاصطناعي المسموح به مؤقتاً." },
}));
app.use(express.json({ limit: "20mb" }));
app.use(express.urlencoded({ limit: "1mb", extended: true, parameterLimit: 1000 }));

app.get("/health", (_req, res) => {
  res.json({
    status: "ok",
    geminiConfigured: Boolean(process.env.GEMINI_API_KEY),
    elevenLabsConfigured: Boolean(process.env.ELEVEN_LABS_API_KEY),
    databaseConfigured: false,
    persistence: "none",
  });
});

const quranAudioUpload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: 15 * 1024 * 1024, files: 1 },
  fileFilter: (_req, file, callback) => {
    const supportedAudioTypes = new Set([
      "audio/aac",
      "audio/flac",
      "audio/mp4",
      "audio/m4a",
      "audio/mpeg",
      "audio/ogg",
      "audio/wav",
      "audio/webm",
      "audio/x-wav",
    ]);
    if (!supportedAudioTypes.has(file.mimetype.toLowerCase())) {
      callback(new Error("Unsupported audio format."));
      return;
    }
    callback(null, true);
  },
});

const parseQuranAudio = (req: express.Request, res: express.Response, next: express.NextFunction) => {
  quranAudioUpload.single("audio")(req, res, (error) => {
    if (error instanceof multer.MulterError) {
      const status = error.code === "LIMIT_FILE_SIZE" ? 413 : 400;
      res.status(status).json({ error: error.message });
      return;
    }
    if (error) {
      res.status(400).json({ error: error.message });
      return;
    }
    next();
  });
};

type QuranSurahResponse = {
  data?: {
    ayahs?: Array<{ text?: string; numberInSurah?: number }>;
  };
};

const quranSurahCache = new Map<number, { expiresAt: number; ayahs: string[] }>();

async function getQuranAyah(surahNumber: number, ayahNumber: number): Promise<string> {
  const cached = quranSurahCache.get(surahNumber);
  let ayahs = cached && cached.expiresAt > Date.now() ? cached.ayahs : undefined;

  if (!ayahs) {
    const response = await fetch(`https://api.alquran.cloud/v1/surah/${surahNumber}`, {
      signal: AbortSignal.timeout(20_000),
    });
    if (!response.ok) {
      throw new Error(`Quran source returned HTTP ${response.status}.`);
    }
    const body = (await response.json()) as QuranSurahResponse;
    ayahs = body.data?.ayahs?.map((ayah) => ayah.text?.trim() ?? "");
    if (!ayahs || ayahs.some((ayah) => !ayah)) {
      throw new Error("Quran source returned an incomplete surah.");
    }
    quranSurahCache.set(surahNumber, {
      expiresAt: Date.now() + 24 * 60 * 60 * 1000,
      ayahs,
    });
  }

  const ayahText = ayahs[ayahNumber - 1];
  if (!ayahText) {
    throw new RangeError("Ayah number is outside the selected surah.");
  }
  return ayahText;
}

const quranCheckRateLimit = rateLimit({
  windowMs: 15 * 60 * 1000,
  limit: 10,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: "تم تجاوز عدد محاولات تقييم التلاوة مؤقتاً. حاول مرة أخرى بعد قليل." },
});

app.post("/api/quran/check", quranCheckRateLimit, parseQuranAudio, async (req, res) => {
  const surahNumber = Number(req.body.surahNumber);
  const ayahNumber = Number(req.body.ayahNumber);
  const audio = req.file;

  if (!Number.isInteger(surahNumber) || surahNumber < 1 || surahNumber > 114) {
    return res.status(400).json({ error: "surahNumber must be an integer from 1 to 114." });
  }
  if (!Number.isInteger(ayahNumber) || ayahNumber < 1) {
    return res.status(400).json({ error: "ayahNumber must be a positive integer." });
  }
  if (!audio || audio.size === 0) {
    return res.status(400).json({ error: "An audio file is required in the audio field." });
  }
  if (!process.env.GEMINI_API_KEY) {
    return res.status(503).json({ error: "Quran recitation checking is not configured on the server." });
  }

  try {
    const ayahText = await getQuranAyah(surahNumber, ayahNumber);
    const audioData = audio.buffer.toString("base64");
    const transcriptionResponse = await ai.models.generateContent({
      model: "gemini-2.5-flash",
      contents: [{
        role: "user",
        parts: [
          { text: "Transcribe only the Arabic words actually recited in this audio. Do not correct, complete, or infer missing Quranic words. If speech is unclear, mark the unclear words as [غير واضح]. Return only the transcription." },
          { inlineData: { mimeType: audio.mimetype, data: audioData } },
        ],
      }],
      config: { temperature: 0 },
    });
    const transcript = transcriptionResponse.text?.trim();
    if (!transcript) {
      return res.status(422).json({ error: "تعذر تفريغ التسجيل الصوتي بوضوح. حاول التسجيل مرة أخرى." });
    }

    const assessmentResponse = await ai.models.generateContent({
      model: "gemini-2.5-flash",
      contents: [{
        role: "user",
        parts: [
          {
            text: `Compare this Arabic speech transcription with the exact Quran ayah. Assess audio quality from the attached recording itself, including clarity and disruptive noise. Report only clear differences; do not invent pronunciation or tajweed errors.
Surah number: ${surahNumber}
Ayah number: ${ayahNumber}
Original ayah: ${ayahText}
Transcription: ${transcript}

Write in Arabic. Give audioQuality as exactly one of: "واضح", "فيه مشاكل", "محتاج تحسين".
Give performance as exactly one of: "ممتاز", "جيد", "محتاج تدريب".
List at most 3 short, specific tajweedTips; only give advice supported by the recording and transcription.
Return a JSON object with: mistakes (array of objects with heard and correct strings), audioQuality, performance, tajweedTips (array of strings).`,
          },
          { inlineData: { mimeType: audio.mimetype, data: audioData } },
        ],
      }],
      config: {
        temperature: 0.2,
        responseMimeType: "application/json",
        responseSchema: {
          type: Type.OBJECT,
          properties: {
            mistakes: {
              type: Type.ARRAY,
              items: {
                type: Type.OBJECT,
                properties: {
                  heard: { type: Type.STRING },
                  correct: { type: Type.STRING },
                },
                required: ["heard", "correct"],
              },
            },
            audioQuality: {
              type: Type.STRING,
              enum: ["واضح", "فيه مشاكل", "محتاج تحسين"],
            },
            performance: {
              type: Type.STRING,
              enum: ["ممتاز", "جيد", "محتاج تدريب"],
            },
            tajweedTips: { type: Type.ARRAY, items: { type: Type.STRING } },
          },
          required: ["mistakes", "audioQuality", "performance", "tajweedTips"],
        },
      },
    });

    const assessmentText = assessmentResponse.text;
    if (!assessmentText) {
      throw new Error("Gemini returned an empty Quran assessment.");
    }
    const assessment = JSON.parse(assessmentText) as {
      mistakes: Array<{ heard: string; correct: string }>;
      audioQuality: string;
      performance: string;
      tajweedTips: string[];
    };
    const allowedAudioQuality = new Set(["واضح", "فيه مشاكل", "محتاج تحسين"]);
    const allowedPerformance = new Set(["ممتاز", "جيد", "محتاج تدريب"]);
    if (
      !Array.isArray(assessment.mistakes) ||
      !Array.isArray(assessment.tajweedTips) ||
      !allowedAudioQuality.has(assessment.audioQuality) ||
      !allowedPerformance.has(assessment.performance)
    ) {
      throw new Error("Gemini returned an invalid Quran assessment.");
    }

    const mistakes = assessment.mistakes
      .filter((mistake) => typeof mistake.heard === "string" && typeof mistake.correct === "string")
      .slice(0, 3);
    const tajweedTips = assessment.tajweedTips
      .filter((tip): tip is string => typeof tip === "string" && tip.trim().length > 0)
      .slice(0, 3);
    const mistakeSummary = mistakes.length
      ? `الكلمات التي تحتاج مراجعة: ${mistakes.map(({ heard, correct }) => `(${heard} ← ${correct})`).join("، ")}`
      : "ما شاء الله، لم تظهر كلمات مخالفة للآية 🙂";
    const tipsSummary = tajweedTips.length
      ? `نصائح التجويد: ${tajweedTips.join(" • ")}`
      : "استمر على القراءة المتأنية 🐥";
    const summary = [
      `جودة الصوت: ${assessment.audioQuality} 🥹`,
      `الأداء العام: ${assessment.performance} ${assessment.performance === "ممتاز" ? "🙂" : "🙃"}`,
      mistakeSummary,
      tipsSummary,
    ].join("\n");

    return res.json({
      transcript,
      mistakes,
      audioQuality: assessment.audioQuality,
      performance: assessment.performance,
      tajweedTips,
      summary,
    });
  } catch (error) {
    console.error("Quran recitation check failed:", error);
    return res.status(502).json({ error: "تعذر تحليل التلاوة حالياً. حاول مرة أخرى بعد قليل." });
  }
});

const allowedGeminiModelActions = new Set([
  "gemini-2.5-flash:generateContent",
  "gemini-2.5-flash-preview-tts:generateContent",
  "gemini-3.1-flash-lite-image:generateContent",
  "gemini-3.1-flash-tts-preview:generateContent",
  "gemini-3.1-pro-preview:generateContent",
]);

app.post("/v1beta/models/*", async (req, res) => {
  const modelAction = String(req.params[0] ?? "").replace(/^\/+|\/+$/g, "");
  if (!allowedGeminiModelActions.has(modelAction)) {
    return res.status(404).json({ error: "Gemini model or action is not supported." });
  }
  if (!process.env.GEMINI_API_KEY) {
    return res.status(503).json({ error: "Gemini is not configured on the server." });
  }

  try {
    const target = new URL(`https://generativelanguage.googleapis.com${req.originalUrl}`);
    target.searchParams.set("key", process.env.GEMINI_API_KEY);
    const upstream = await fetch(target, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(req.body),
      signal: AbortSignal.timeout(120_000),
    });
    const body = await upstream.text();
    res.status(upstream.status).type(upstream.headers.get("content-type") || "application/json").send(body);
  } catch (error) {
    console.error("Gemini proxy request failed:", error);
    res.status(502).json({ error: "Unable to contact the Gemini service." });
  }
});

// Ensure safe content filters (Content Moderation & NSFW checker)
function containsProhibitedContent(text: string): { isProhibited: boolean; reason: string | null } {
  const normalized = text.toLowerCase();
  
  // Strict NSFW, explicit adult terms, nudity
  const nsfwKeywords = [
    "naked", "nudity", "nsfw", "sexy", "erotic", "porn", "porno", "sex", "unclothed", "vagina", "penis", "breast", "boobs", "ass", "striptease", "vulg", "عاري", "جنس", "بورن", "إباحي", "ثدي", "مؤخرة"
  ];
  for (const kw of nsfwKeywords) {
    if (normalized.includes(kw)) {
      return { isProhibited: true, reason: "المحتوى أو الصور غير الأخلاقية والـ NSFW محظورة تماماً حفاظاً على سلامة المنصة." };
    }
  }

  // Academic Dishonesty / Direct Cheating prevention (Strict Student Safety layer)
  // Let's prohibit terms that represent direct requests for cheating in live exams
  const examCheatingKeywords = [
    "حل هذا السؤال في الامتحان الآن", "غش في الامتحان", "حل امتحان لايف", "إجابة اختبار مباشر", "غشني", "cheat in exam", "live exam help", "solve exam question now"
  ];
  for (const kw of examCheatingKeywords) {
    if (normalized.includes(kw)) {
      return { isProhibited: true, reason: "يمنع تماماً استخدام المنصة للغش في الامتحانات أو الحصول على إجابات مباشرة مخصصة للاختبارات الجارية. يمكنني شرح المفاهيم العلمية والرياضية لمساعدتك على الفهم." };
    }
  }

  // General illegal, harmful, or unethical content
  const illegalKeywords = [
    "hack target", "make a bomb", "صنع قنبلة", "تهكير موقع", "bypass password", "steal credit card", "drugs", "مخدرات", "سلاح غير قانوني", "unlawful"
  ];
  for (const kw of illegalKeywords) {
    if (normalized.includes(kw)) {
      return { isProhibited: true, reason: "المحتوى الضار، غير القانوني، أو الذي يروج لأعمال تخريبية محظور تماماً." };
    }
  }

  return { isProhibited: false, reason: null };
}

// 1. SMART CHAT API with System Prompts, Context & Persona Guidance
app.post("/api/chat", async (req, res) => {
  try {
    const { message, history = [], username = "Hassan", persona = "default", attachedFile } = req.body;

    if (!message) {
      return res.status(400).json({ error: "Message is required" });
    }

    // Safety and Moderation check on the new message
    const safetyCheck = containsProhibitedContent(message);
    if (safetyCheck.isProhibited) {
      return res.status(400).json({ error: safetyCheck.reason });
    }

    // Construct system instructions based on selected AI Persona
    let systemInstruction = `You are "H&J Smart Hub" (Hassan & Jana), an elite, highly advanced AI assistant and a globally recognized Super App brand. You speak in a polite, highly intellectual, and helpful tone (Arabic/English depending on the input).

Strict Rules of Conduct (Safety & Ethics Layer):
1. Never generate any NSFW, nudity, erotic, or sexually suggestive content under any circumstances. (Strict NSFW Filter).
2. Academic Integrity / Student Moderation: If the user asks for homework or test help, DO NOT give direct answers or write exams for them. Instead, act as an expert tutor. Explain the steps, teach the underlying concepts, provide similar examples, and ask questions to help them solve it themselves. Emphasize that cheating is wrong.
3. Strictly decline any illegal, harmful, or unethical requests (e.g., hacking, weapons, drugs).

4. Honest & Blunt Feedback System (نظام التقييم الصريح والدقيق للوسائط والملفات المرفوعة):
- عند قيام المستخدم برفع أو إرسال مقطع صوتي (تلاوة قرآن كريم، غناء، إلقاء شعري، أو خطابة) وسؤال الذكاء الاصطناعي عن رأيه أو أداءه، يجب أن تجيب بكل صراحة ووضوح وتتجنب أي مجاملات فارغة تماماً. أعطِ نصائح حقيقية ونقد بناء دقيق جداً للتحسين يشمل مخارج الحروف الصحيحة، التون (Tone)، طبقة الصوت، الأداء، الأخطاء النطقية أو التجويدية، ومواضع الضعف الفنية بكل أمانة ومصداقية.
- عند قيام المستخدم برفع صورة شخصية وسؤالك "أنا حلو؟" أو طلب تقييم مظهره العام، تجنب الاكتفاء بكلمات المجاملة التقليدية أو المديح الأعمى. يجب أن تقدم تقييماً واقعياً وصريحاً جداً بكل وضوح وبأسلوب محترم، لبق، وذكي في نفس الوقت. قم بالتعليق الدقيق والمفصل على عناصر اللقطة والمظهر مثل: الإضاءة، الملابس وتناسقها، زاوية التصوير، التعبيرات، جودة الصورة، أو المظهر الكلي، ونبهه بصدق عما يحتاج تعديل أو تحسين (على سبيل المثال: تناسق الألوان، ترتيب الملابس، أو زاوية الكاميرا).
- في جميع التقييمات، كن صادقاً 100% ولا تخش قول الحقيقة المطلقة، ولكن حافظ على أسلوب محترم، ذكي، وراقٍ للارتقاء بمهارات ومظهر المستخدمين.

Now, execute your role with distinction.`;

    if (persona === "teacher") {
      systemInstruction += `\n\nPersona: [Academic Expert & Teacher / مدرس]
- Your primary goal is to guide students step-by-step.
- NEVER solve active exams or provide raw copy-paste answers to homework tasks.
- Explain the theory, show calculations, use clear examples, and test the student's understanding by giving them a simple follow-up question.`;
    } else if (persona === "coder") {
      systemInstruction += `\n\nPersona: [Senior Software Engineer / مبرمج]
- Explain code structures cleanly and write fully robust, typed code (prefer TypeScript, modern layouts).
- Use syntax explanation, point out performance bottlenecks, and explain algorithms step-by-step.`;
    } else if (persona === "doctor") {
      systemInstruction += `\n\nPersona: [Compassionate Medical Advisor / دكتور ومستشار طبي]
- STRICT MEDICAL DISCLAIMER: You MUST always start your diagnosis or advice with a prominent warning that you are an AI assistant and this is only educational advice, not professional medical counseling, and they must see a real doctor for any serious conditions.
- Analyze symptoms empathetically, list potential general causes, suggest safe home remedies (like hydration or rest), and suggest what specialist they should consult.`;
    } else if (persona === "advisor") {
      systemInstruction += `\n\nPersona: [Elite Business Consultant / مستشار أعمال]
- Analyze business plans, design agile marketing strategies, draft financial projections, and offer concrete execution items.
- Use corporate terminology but keep details highly actionable.`;
    } else if (persona === "designer") {
      systemInstruction += `\n\nPersona: [Creative UI/UX & Art Director / مصمم]
- Critique designs with a focus on negative space, color palettes (Tailwind hexes), font pairing, and micro-interactions.
- Provide direct modern design advice (e.g. glassmorphism, flat design, brutalist elements).`;
    }

    // Convert history into Google Gen AI parts format
    // Format: list of { role: "user" | "model", parts: [{ text: "..." }] }
    const contents: any[] = [];
    history.forEach((turn: any) => {
      const parts: any[] = [{ text: turn.text || "" }];
      if (turn.attachedFile && turn.attachedFile.data) {
        parts.push({
          inlineData: {
            mimeType: turn.attachedFile.mimeType,
            data: turn.attachedFile.data,
          },
        });
      }
      contents.push({
        role: turn.role === "user" ? "user" : "model",
        parts: parts,
      });
    });

    // Add current user message with optional attached image/audio file
    const currentParts: any[] = [{ text: message }];
    if (attachedFile && attachedFile.data) {
      currentParts.push({
        inlineData: {
          mimeType: attachedFile.mimeType,
          data: attachedFile.data,
        },
      });
    }

    contents.push({
      role: "user",
      parts: currentParts,
    });

    // Request response from Gemini
    const response = await ai.models.generateContent({
      model: "gemini-2.5-flash",
      contents,
      config: {
        systemInstruction,
        temperature: 0.75,
      },
    });

    const replyText = response.text || "لم أتمكن من صياغة إجابة، يرجى المحاولة مرة أخرى.";

    // Double check response safety
    const replySafetyCheck = containsProhibitedContent(replyText);
    if (replySafetyCheck.isProhibited) {
      return res.status(400).json({ error: "تم حجب الإجابة نظراً لاحتوائها على محتوى غير ملائم وفقاً لسياسة الأمان الصارمة." });
    }

    res.json({ reply: replyText });
  } catch (error: any) {
    console.error("Chat API Error:", error);
    const errString = error?.message?.toLowerCase() || "";
    if (errString.includes("safety") || errString.includes("block") || errString.includes("candidate") || errString.includes("finishreason")) {
      return res.status(400).json({ 
        error: "⚠️ تم حظر هذا الملف أو المحتوى بواسطة نظام الحماية الصارم (Strict Safety Layer). يمنع تماماً رفع صور غير أخلاقية أو عارية أو مواد خادشة للحياء." 
      });
    }
    res.status(500).json({ error: "حدث خطأ في الخادم أثناء معالجة المحادثة: " + error.message });
  }
});

// 2. PRODUCTIVITY: BOOK/DOCUMENT SUMMARIZER API
app.post("/api/summarize", async (req, res) => {
  try {
    const { documentText, fileName, format = "bullets" } = req.body;

    if (!documentText) {
      return res.status(400).json({ error: "Document text content is required" });
    }

    let summaryPrompt = `Please summarize the following document content:
File Name: ${fileName || "Uploaded Document"}
Format option: ${format}

Provide:
1. Executive Summary (إيجاز تنفيذي)
2. Key Findings & Crucial Points (النقاط والمحاور الرئيسية)
3. Action Items or Conclusions (التوصيات والخطوات التالية)

Please write the summary in highly professional Arabic (or English if the document is strictly English).`;

    const response = await ai.models.generateContent({
      model: "gemini-2.5-flash",
      contents: [
        { text: summaryPrompt },
        { text: documentText }
      ],
      config: {
        systemInstruction: "You are an elite research analyst and fast executive summarizer for 'H&J Smart Hub'.",
      }
    });

    res.json({ summary: response.text || "فشل التلخيص." });
  } catch (error: any) {
    console.error("Summarizer Error:", error);
    res.status(500).json({ error: "خطأ أثناء تلخيص الملف: " + error.message });
  }
});

// 3. PRODUCTIVITY: RESEARCH & ESSAY WRITER API
app.post("/api/research", async (req, res) => {
  try {
    const { topic, academicField, detailLevel = "comprehensive" } = req.body;

    if (!topic) {
      return res.status(400).json({ error: "Research topic is required" });
    }

    const researchPrompt = `Design a comprehensive academic research framework and essay on:
Topic: "${topic}"
Academic Field: ${academicField || "General Studies"}
Detail level: ${detailLevel}

Include:
1. Dynamic, Catchy Title (عنوان مقترح)
2. Structural Outline (الهيكل التنظيمي للبحث)
3. Full Detailed Content / Sections (المحتوى المفصل والأقسام كاملة)
4. Key Academic References and Sources (المراجع والمصادر المقترحة)

Please write this research study beautifully with markdown. Keep it strictly professional, well-formatted, and completely unique. Prevent any direct copy/paste elements from external cheating worksheets.`;

    const response = await ai.models.generateContent({
      model: "gemini-2.5-flash",
      contents: researchPrompt,
      config: {
        systemInstruction: "You are H&J academic lead and essay author. You produce extremely well-structured, cited, and unique research articles.",
      }
    });

    res.json({ content: response.text || "فشلت كتابة البحث." });
  } catch (error: any) {
    console.error("Research Writer Error:", error);
    res.status(500).json({ error: "خطأ أثناء توليد البحث: " + error.message });
  }
});

// 4. PRODUCTIVITY: PRESENTATION GENERATOR API
app.post("/api/presentation", async (req, res) => {
  try {
    const { topic, slidesCount = 5 } = req.body;

    if (!topic) {
      return res.status(400).json({ error: "Presentation topic is required" });
    }

    const presentationPrompt = `Generate a structural, highly structured slide deck presentation about:
Topic: "${topic}"
Desired slides count: ${slidesCount}

Please structure the output strictly in a JSON array format so the application can render the slides interactively in a slideshow player!
For each slide, return:
- slideNumber (integer)
- title (string, in Arabic or English depending on topic)
- bullets (array of 3 to 4 strings containing points)
- designTip (string, suggesting CSS/styling accent for this specific slide, e.g. "Use custom blue glow", "Modern minimalist layout")

JSON Format Requirement:
Provide ONLY the JSON list. No surrounding explanation, no markdown tags.`;

    const response = await ai.models.generateContent({
      model: "gemini-2.5-flash",
      contents: presentationPrompt,
      config: {
        responseMimeType: "application/json",
        responseSchema: {
          type: Type.ARRAY,
          items: {
            type: Type.OBJECT,
            properties: {
              slideNumber: { type: Type.INTEGER },
              title: { type: Type.STRING },
              bullets: {
                type: Type.ARRAY,
                items: { type: Type.STRING }
              },
              designTip: { type: Type.STRING }
            },
            required: ["slideNumber", "title", "bullets", "designTip"]
          }
        },
        systemInstruction: "You are an executive slide designer who generates beautiful presentations in JSON formats."
      }
    });

    const slidesJson = JSON.parse(response.text || "[]");
    res.json({ slides: slidesJson });
  } catch (error: any) {
    console.error("Presentation API Error:", error);
    res.status(500).json({ error: "خطأ أثناء إنشاء العرض التقديمي: " + error.message });
  }
});

app.post("/api/tts/elevenlabs", async (req, res) => {
  const apiKey = process.env.ELEVEN_LABS_API_KEY;
  if (!apiKey) {
    return res.status(503).json({ error: "ElevenLabs speech is not configured on the server." });
  }

  const text = typeof req.body?.text === "string" ? req.body.text.trim() : "";
  const voiceId = req.query.voiceId;
  const allowedVoiceIds = new Set(["pNInz6obpgDQGcFmaJgB", "21m00Tcm4TlvDq8ikWAM"]);
  if (!text || text.length > 5000 || typeof voiceId !== "string" || !allowedVoiceIds.has(voiceId)) {
    return res.status(400).json({ error: "A valid text and supported voice are required." });
  }

  const safetyCheck = containsProhibitedContent(text);
  if (safetyCheck.isProhibited) {
    return res.status(400).json({ error: "لا يمكن قراءة نصوص تحتوي على ألفاظ أو مواضيع محظورة." });
  }

  try {
    const response = await fetch(`https://api.elevenlabs.io/v1/text-to-speech/${voiceId}`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "xi-api-key": apiKey,
      },
      body: JSON.stringify({
        text,
        model_id: "eleven_multilingual_v2",
        voice_settings: { stability: 0.35, similarity_boost: 0.85, style: 0.3 },
      }),
      signal: AbortSignal.timeout(60_000),
    });

    if (!response.ok) {
      console.error(`ElevenLabs TTS returned HTTP ${response.status}.`);
      return res.status(502).json({ error: "ElevenLabs speech generation failed." });
    }

    const audio = Buffer.from(await response.arrayBuffer()).toString("base64");
    return res.json({ audio });
  } catch (error) {
    console.error("ElevenLabs TTS request failed:", error);
    return res.status(502).json({ error: "Unable to contact ElevenLabs." });
  }
});

// 5. SPEECH SYNTHESIS API (Text-To-Speech) via gemini-3.1-flash-tts-preview
app.post("/api/tts", async (req, res) => {
  try {
    const { text, voice = "Zephyr" } = req.body;

    if (!text) {
      return res.status(400).json({ error: "Text is required" });
    }

    // Safety and Moderation check on text
    const safetyCheck = containsProhibitedContent(text);
    if (safetyCheck.isProhibited) {
      return res.status(400).json({ error: "لا يمكن قراءة نصوص تحتوي على ألفاظ أو مواضيع محظورة." });
    }

    // Generate speech audio
    const response = await ai.models.generateContent({
      model: "gemini-3.1-flash-tts-preview",
      contents: [{ parts: [{ text: `Read clearly: ${text}` }] }],
      config: {
        responseModalities: ["AUDIO"],
        speechConfig: {
          voiceConfig: {
            prebuiltVoiceConfig: { voiceName: voice }, // 'Puck', 'Charon', 'Kore', 'Fenrir', 'Zephyr'
          },
        },
      },
    });

    const base64Audio = response.candidates?.[0]?.content?.parts?.[0]?.inlineData?.data;

    if (!base64Audio) {
      throw new Error("Could not extract synthesized audio from Gemini response.");
    }

    res.json({ audio: base64Audio });
  } catch (error: any) {
    console.error("TTS API Error:", error);
    // Graceful client fallback: Since TTS requires a paid key, we explain in response
    // but still allow the client to know it failed or provide a clear error message.
    res.status(500).json({ 
      error: "خطأ في تحويل النص إلى صوت: " + error.message,
      isTtsError: true,
      hint: "تأكد من تفعيل مفتاح API المدفوع والتحقق من توافر موديل gemini-3.1-flash-tts-preview." 
    });
  }
});

// 6. AI IMAGE GENERATION API with Fallbacks & Moderation Check
app.post("/api/image/generate", async (req, res) => {
  try {
    const { prompt, aspectRatio = "1:1", style = "photorealistic" } = req.body;

    if (!prompt) {
      return res.status(400).json({ error: "Prompt is required" });
    }

    // Strict safety layer checks
    const safety = containsProhibitedContent(prompt);
    if (safety.isProhibited) {
      return res.status(400).json({ error: safety.reason });
    }

    const enhancedPrompt = `A high quality, professional, beautiful ${style} representation of: ${prompt}. Clean lighting, premium design, detailed composition, brand grade.`;

    try {
      // Call Gemini Image Generator
      const response = await ai.models.generateContent({
        model: "gemini-3.1-flash-lite-image",
        contents: {
          parts: [{ text: enhancedPrompt }]
        },
        config: {
          imageConfig: {
            aspectRatio: aspectRatio as any, // "1:1" | "3:4" | "4:3" | "9:16" | "16:9"
          }
        }
      });

      let base64Image = "";
      const candidates = response.candidates;
      if (candidates && candidates[0]?.content?.parts) {
        for (const part of candidates[0].content.parts) {
          if (part.inlineData) {
            base64Image = part.inlineData.data;
            break;
          }
        }
      }

      if (base64Image) {
        return res.json({ imageUrl: `data:image/png;base64,${base64Image}` });
      } else {
        throw new Error("No image data returned from API.");
      }
    } catch (apiError: any) {
      console.warn("Gemini Image API failed, using premium stock placeholder simulation:", apiError.message);
      
      // Highly-polished placeholder simulation to guarantee the user can test the app beautifully
      // even if their Gemini key lacks image billing/permissions
      const cleanKeyword = encodeURIComponent(prompt.trim().split(" ").slice(0, 3).join(","));
      const stockUrls = [
        `https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?auto=format&fit=crop&w=1000&q=80`,
        `https://images.unsplash.com/photo-1620712943543-bcc4688e7485?auto=format&fit=crop&w=1000&q=80`,
        `https://images.unsplash.com/photo-1634017839464-5c339ebe3cb4?auto=format&fit=crop&w=1000&q=80`,
        `https://images.unsplash.com/photo-1639762681485-074b7f938ba0?auto=format&fit=crop&w=1000&q=80`
      ];
      const randomStock = stockUrls[Math.floor(Math.random() * stockUrls.length)];
      const fallbackUrl = `https://images.unsplash.com/photo-1579783902614-a3fb3927b6a5?auto=format&fit=crop&w=1000&q=80`;

      res.json({ 
        imageUrl: `https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?auto=format&fit=crop&w=800&q=80`, // Premium default
        isFallback: true,
        fallbackExplanation: "تم استخدام محاكاة صور Unsplash الفنية الراقية للتجربة. لتشغيل Gemini Image Generation الفعلي، يرجى تفعيل مفتاح API مدفوع."
      });
    }
  } catch (error: any) {
    console.error("Image Generation Error:", error);
    res.status(500).json({ error: "خطأ في معالجة توليد الصورة: " + error.message });
  }
});

app.post("/api/sync/save", (_req, res) => {
  res.status(503).json({
    error: "مزامنة الحسابات غير متاحة حتى إعداد تخزين دائم وتسجيل دخول آمن.",
  });
});

app.get("/api/sync/load", (_req, res) => {
  res.status(503).json({
    error: "مزامنة الحسابات غير متاحة حتى إعداد تخزين دائم وتسجيل دخول آمن.",
  });
});

// 8. AUTO-SCHEDULE PLANNER CREATOR (Uses LLM to schedule your day)
app.post("/api/planner/generate", async (req, res) => {
  try {
    const { prompt, wakeTime = "07:00", sleepTime = "23:00" } = req.body;

    if (!prompt) {
      return res.status(400).json({ error: "Planner prompt is required" });
    }

    const plannerPrompt = `I need to schedule my day with activities.
My Goal/Request: "${prompt}"
Wake up time: ${wakeTime}
Sleep time: ${sleepTime}

Please generate an structured timeline of tasks/activities starting from ${wakeTime} to ${sleepTime}.
Return strictly a JSON array of events.
Each event must contain:
- time (string, format e.g. "08:00 AM" or "02:30 PM")
- title (string, short, Arabic)
- duration (string, e.g. "1 hour" or "45 mins")
- description (string, Arabic)
- category (string, choice of: "study", "health", "leisure", "coding", "business")

JSON Format Requirement:
Provide ONLY the raw JSON list. Do not surround with markdown codes.`;

    const response = await ai.models.generateContent({
      model: "gemini-2.5-flash",
      contents: plannerPrompt,
      config: {
        responseMimeType: "application/json",
        responseSchema: {
          type: Type.ARRAY,
          items: {
            type: Type.OBJECT,
            properties: {
              time: { type: Type.STRING },
              title: { type: Type.STRING },
              duration: { type: Type.STRING },
              description: { type: Type.STRING },
              category: { type: Type.STRING }
            },
            required: ["time", "title", "duration", "description", "category"]
          }
        },
        systemInstruction: "You are an elite productivity scheduler that writes schedules strictly in JSON lists."
      }
    });

    const parsedTimeline = JSON.parse(response.text || "[]");
    res.json({ timeline: parsedTimeline });
  } catch (error: any) {
    console.error("Planner Creator Error:", error);
    res.status(500).json({ error: "خطأ أثناء جدولة اليوم بالذكاء الاصطناعي: " + error.message });
  }
});

app.use((_req, res) => {
  res.status(404).json({ error: "API route not found." });
});

app.use((
  error: Error & { status?: number; type?: string },
  _req: express.Request,
  res: express.Response,
  _next: express.NextFunction,
) => {
  if (res.headersSent) return;

  const status = error.status === 413 ? 413 : error.status === 400 ? 400 : 500;
  const message = status === 413
    ? "حجم الطلب أكبر من الحد المسموح."
    : status === 400
      ? "تعذر قراءة الطلب. تحقق من البيانات المرسلة."
      : "حدث خطأ غير متوقع في الخادم.";

  if (status === 500) {
    console.error("Unhandled request error:", error);
  }
  res.status(status).json({ error: message });
});

app.listen(PORT, "0.0.0.0", () => {
  console.log(`API server is running on port ${PORT}`);
});
