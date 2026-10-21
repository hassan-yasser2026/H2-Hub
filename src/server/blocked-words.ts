export const BLOCKED_WORDS: Readonly<Record<string, readonly string[]>> = {
  sexual: [
    "naked", "nudity", "nsfw", "sexy", "erotic", "porn", "porno", "sex", "ass", "unclothed",
    "vagina", "penis", "breast", "boobs", "striptease", "sexual exploitation",
    "عاري", "جنس", "سكس", "بورن", "اباحي", "ثدي", "مؤخره",
  ],
  violence: [
    "violence", "violent", "murder", "kill", "killing", "assassinate", "assassination", "torture", "execute",
    "execution", "violent attack", "massacre", "اغتيال", "قتل", "اقتل", "عنف", "العنف", "تعذيب", "مجزره",
  ],
  religion: [
    "insult islam", "insult the prophet", "insult christianity", "blaspheme islam",
    "سب الاسلام", "اهانه الاسلام", "اهانه الرسول", "سب الرسول", "ازدراء الاديان",
    "اهانه المسيحيه", "اهانه اليهوديه",
  ],
  sensitivePolitics: [
    "political persuasion", "target voters", "voter suppression", "election disinformation",
    "تحريض سياسي", "توجيه الناخبين", "استهداف الناخبين", "محتوى سياسي حساس",
    "المحتوى السياسي الحساس",
    "دعايه انتخابيه", "التلاعب بالانتخابات",
  ],
  drugs: [
    "drug", "drugs", "cocaine", "heroin", "methamphetamine", "meth", "fentanyl", "drug synthesis",
    "make meth", "مخدرات", "المخدرات", "تصنيع المخدرات", "كوكايين", "هيروين", "ميثامفيتامين",
  ],
  weapons: [
    "weapon", "weapons", "firearm", "gun", "rifle", "bomb", "explosive", "build a weapon",
    "how to make a gun", "make a bomb",     "سلاح", "اسلحه", "الاسلحه", "مسدس", "بندقيه", "قنبله", "متفجرات",
    "صنع سلاح", "صناعة قنبله",
  ],
  terrorism: [
    "terrorism", "terrorist", "terror attack", "suicide bombing",     "ارهاب", "الارهاب", "ارهابي",
    "تفجير انتحاري",
  ],
} as const;
