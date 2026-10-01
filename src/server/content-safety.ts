export function containsProhibitedContent(
  text: string
): { isProhibited: boolean; reason: string | null } {
  const normalized = text.toLowerCase();

  const nsfwKeywords = [
    "naked", "nudity", "nsfw", "sexy", "erotic", "porn", "porno", "sex", "unclothed",
    "vagina", "penis", "breast", "boobs", "ass", "striptease", "vulg", "عاري", "جنس",
    "بورن", "إباحي", "ثدي", "مؤخرة",
  ];
  for (const keyword of nsfwKeywords) {
    const matched = keyword === "ass"
      ? /\bass\b/u.test(normalized)
      : normalized.includes(keyword);
    if (matched) {
      return {
        isProhibited: true,
        reason: "المحتوى أو الصور غير الأخلاقية والـ NSFW محظورة تماماً حفاظاً على سلامة المنصة.",
      };
    }
  }

  const examCheatingKeywords = [
    "حل هذا السؤال في الامتحان الآن", "غش في الامتحان", "حل امتحان لايف", "إجابة اختبار مباشر",
    "غشني", "cheat in exam", "live exam help", "solve exam question now",
  ];
  for (const keyword of examCheatingKeywords) {
    if (normalized.includes(keyword)) {
      return {
        isProhibited: true,
        reason: "يمنع تماماً استخدام المنصة للغش في الامتحانات أو الحصول على إجابات مباشرة مخصصة للاختبارات الجارية. يمكنني شرح المفاهيم العلمية والرياضية لمساعدتك على الفهم.",
      };
    }
  }

  const illegalKeywords = [
    "hack target", "make a bomb", "صنع قنبلة", "تهكير موقع", "bypass password",
    "steal credit card", "drugs", "مخدرات", "سلاح غير قانوني", "unlawful",
  ];
  for (const keyword of illegalKeywords) {
    if (normalized.includes(keyword)) {
      return {
        isProhibited: true,
        reason: "المحتوى الضار، غير القانوني، أو الذي يروج لأعمال تخريبية محظور تماماً.",
      };
    }
  }

  return { isProhibited: false, reason: null };
}
