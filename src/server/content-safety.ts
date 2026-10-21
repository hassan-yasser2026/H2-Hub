import { BLOCKED_WORDS } from "./blocked-words.js";

const safetyBlockMessage =
  "تم حجب المحتوى لعدم توافقه مع سياسة التطبيق.";

function normalizeSafetyText(text: string): string {
  return text
    .normalize("NFKC")
    .toLocaleLowerCase()
    .replace(/[\u064B-\u065F\u0670\u0640]/gu, "")
    .replace(/[أإآٱ]/gu, "ا")
    .replace(/ى/gu, "ي")
    .replace(/ة/gu, "ه")
    .replace(/[^\p{L}\p{N}]+/gu, " ")
    .trim();
}

function hasBlockedTerm(normalizedText: string, term: string): boolean {
  const normalizedTerm = normalizeSafetyText(term);
  return ` ${normalizedText} `.includes(` ${normalizedTerm} `);
}

export function containsProhibitedContent(
  text: string
): { isProhibited: boolean; reason: string | null } {
  const normalizedText = normalizeSafetyText(text);

  for (const terms of Object.values(BLOCKED_WORDS)) {
    if (terms.some((term) => hasBlockedTerm(normalizedText, term))) {
      return { isProhibited: true, reason: safetyBlockMessage };
    }
  }

  const examCheatingKeywords = [
    "حل هذا السؤال في الامتحان الآن", "غش في الامتحان", "حل امتحان لايف", "إجابة اختبار مباشر",
    "غشني", "cheat in exam", "live exam help", "solve exam question now",
  ];
  if (examCheatingKeywords.some((term) => hasBlockedTerm(normalizedText, term))) {
    return {
      isProhibited: true,
      reason: "يمنع استخدام المنصة للغش في الامتحانات أو الحصول على إجابات مباشرة للاختبارات الجارية. يمكنني شرح المفاهيم لمساعدتك على الفهم.",
    };
  }

  const illegalKeywords = [
    "hack target", "make a bomb", "صنع قنبلة", "تهكير موقع", "bypass password",
    "steal credit card", "unlawful", "build a bomb", "how to build a weapon",
    "instructions to harm someone", "plan an attack", "خطة لقتل", "طريقة إيذاء شخص",
    "التخطيط لهجوم",
  ];
  if (illegalKeywords.some((term) => hasBlockedTerm(normalizedText, term))) {
    return {
      isProhibited: true,
      reason: "تم حجب المحتوى لعدم توافقه مع سياسة التطبيق.",
    };
  }

  return { isProhibited: false, reason: null };
}
