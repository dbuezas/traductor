package com.dbuezas.traductor;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The phone's text-to-speech voices per language, and which one the user picked. */
final class Voices {
    /** The two languages of the conversation. */
    static Lang[] langsInUse(Context c) {
        Lang a = Lang.byCode(Models.prefs(c).getString("langA", "es"), Lang.ES);
        Lang b = Lang.byCode(Models.prefs(c).getString("langB", "de"), Lang.DE);
        return a == b ? new Lang[]{a} : new Lang[]{a, b};
    }

    /** Installed voices that speak this language, offline ones first. */
    static List<Voice> forLang(TextToSpeech tts, Lang l) {
        List<Voice> out = new ArrayList<>();
        Set<Voice> all = tts.getVoices();
        if (all == null) return out;
        for (Voice v : all) {
            if (v.getLocale().getLanguage().equals(l.locale.getLanguage())
                    && !v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) out.add(v);
        }
        out.sort((x, y) -> x.isNetworkConnectionRequired() != y.isNetworkConnectionRequired()
                ? (x.isNetworkConnectionRequired() ? 1 : -1) : x.getName().compareTo(y.getName()));
        return out;
    }

    /** Voice names are codes like "de-de-x-deb-local": show the country and whether it needs internet. */
    static String label(Voice v, int index) {
        String country = v.getLocale().getDisplayCountry();
        return "Voz " + (index + 1) + (country.isEmpty() ? "" : " · " + country)
                + (v.isNetworkConnectionRequired() ? " · necesita internet" : "");
    }

    static String chosen(Context c, Lang l) {
        return Models.prefs(c).getString("voice_" + l.code, null);
    }

    static void choose(Context c, Lang l, Voice v) {
        Models.prefs(c).edit().putString("voice_" + l.code, v == null ? null : v.getName()).apply();
    }

    /** The picked voice for this language, if it is still installed. */
    static Voice find(Context c, TextToSpeech tts, Lang l) {
        String name = chosen(c, l);
        if (name == null || tts.getVoices() == null) return null;
        for (Voice v : tts.getVoices()) if (v.getName().equals(name)) return v;
        return null;
    }

    private Voices() {}
}
