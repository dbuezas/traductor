package com.dbuezas.traductor;

import java.util.Locale;

/** Languages offered in the app. englishName goes into the TranslateGemma prompt. */
enum Lang {
    DE("de", "German", "Deutsch", Locale.GERMANY, "🇩🇪", "Zum Sprechen gedrückt halten",
            "Loslassen zum Übersetzen", "Hört zu", "Übersetzt…", "Transkribiert…"),
    ES("es", "Spanish", "Español", new Locale("es", "AR"), "🇦🇷", "Mantené apretado para hablar",
            "Soltá para traducir", "Escuchando", "Traduciendo…", "Transcribiendo…"),
    EN("en", "English", "English", Locale.US, "🇬🇧", "Hold to talk",
            "Release to translate", "Listening", "Translating…", "Transcribing…"),
    FR("fr", "French", "Français", Locale.FRANCE, "🇫🇷", "Maintenir pour parler",
            "Relâcher pour traduire", "J'écoute", "Traduction…", "Transcription…"),
    IT("it", "Italian", "Italiano", Locale.ITALY, "🇮🇹", "Tieni premuto per parlare",
            "Rilascia per tradurre", "In ascolto", "Traduco…", "Trascrivo…"),
    PT("pt", "Portuguese", "Português", new Locale("pt", "BR"), "🇧🇷", "Segure para falar",
            "Solte para traduzir", "Ouvindo", "Traduzindo…", "Transcrevendo…"),
    NL("nl", "Dutch", "Nederlands", new Locale("nl", "NL"), "🇳🇱", "Ingedrukt houden om te praten",
            "Loslaten om te vertalen", "Luistert", "Vertalen…", "Transcriberen…");

    final String code;
    final String englishName;
    final String label;
    final Locale locale;
    final String flag;
    // UI words in this language, for the person reading this half of the screen
    final String holdToTalk, releaseToTranslate, listening, translating, transcribing;

    Lang(String code, String englishName, String label, Locale locale, String flag, String holdToTalk,
         String releaseToTranslate, String listening, String translating, String transcribing) {
        this.code = code;
        this.englishName = englishName;
        this.label = label;
        this.locale = locale;
        this.flag = flag;
        this.holdToTalk = holdToTalk;
        this.releaseToTranslate = releaseToTranslate;
        this.listening = listening;
        this.translating = translating;
        this.transcribing = transcribing;
    }

    /** Code for the translation prompt: Spanish goes to Argentina, the rest stay generic. */
    String targetCode() {
        return this == ES ? "es-AR" : code;
    }

    static Lang byCode(String code, Lang fallback) {
        for (Lang l : values()) if (l.code.equals(code)) return l;
        return fallback;
    }
}
