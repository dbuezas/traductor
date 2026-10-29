package com.dbuezas.traductor;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns "tú" Spanish into Rioplatense "vos" Spanish with a word list. Only unambiguous words are
 * listed (e.g. "mira" is left alone: it is also "he/she looks").
 */
final class Voseo {
    private static final Map<String, String> WORDS = new HashMap<>();

    static {
        String[] pairs = {
            "tú", "vos", "ti", "vos", "contigo", "con vos", "vosotros", "ustedes", "vosotras", "ustedes",
            "eres", "sos", "sabes", "sabés", "quieres", "querés", "puedes", "podés", "tienes", "tenés",
            "vienes", "venís", "dices", "decís", "haces", "hacés", "piensas", "pensás", "crees", "creés",
            "entiendes", "entendés", "sientes", "sentís", "prefieres", "preferís", "necesitas", "necesitás",
            "debes", "debés", "conoces", "conocés", "juegas", "jugás", "duermes", "dormís", "vuelves", "volvés",
            "cuentas", "contás", "recuerdas", "recordás", "encuentras", "encontrás", "empiezas", "empezás",
            "cierras", "cerrás", "pides", "pedís", "sigues", "seguís", "eliges", "elegís", "mueves", "movés",
            "pierdes", "perdés", "hablas", "hablás", "miras", "mirás", "buscas", "buscás", "llamas", "llamás",
            "comes", "comés", "bebes", "bebés", "vives", "vivís", "escribes", "escribís", "lees", "leés",
            "oyes", "oís", "sales", "salís", "pones", "ponés", "traes", "traés", "compras", "comprás",
            "trabajas", "trabajás", "estudias", "estudiás", "esperas", "esperás", "tomas", "tomás",
            "llevas", "llevás", "pagas", "pagás", "usas", "usás", "ayudas", "ayudás", "gustas", "gustás",
            "vuelas", "volás", "construyes", "construís", "decides", "decidís", "entiendes", "entendés",
            "acuerdas", "acordás", "preocupas", "preocupás", "imaginas", "imaginás", "cuidas", "cuidás",
            "ven", "vení", "ten", "tené", "haz", "hacé", "pon", "poné", "sal", "salí", "di", "decí",
            "dime", "decime", "dame", "dame", "mírame", "mirame", "escúchame", "escuchame", "espérame", "esperame",
            "cállate", "callate", "siéntate", "sentate", "levántate", "levantate", "tráeme", "traeme",
            "ayúdame", "ayudame", "déjame", "dejame", "díselo", "decíselo", "hazlo", "hacelo", "ponte", "ponete",
        };
        for (int i = 0; i < pairs.length; i += 2) WORDS.put(pairs[i], pairs[i + 1]);
    }

    private static final Pattern WORD = Pattern.compile("\\p{L}+");

    static String apply(String text) {
        Matcher m = WORD.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String w = m.group();
            String repl = WORDS.get(w.toLowerCase());
            if (repl == null) repl = w;
            else if (Character.isUpperCase(w.charAt(0))) repl = Character.toUpperCase(repl.charAt(0)) + repl.substring(1);
            m.appendReplacement(out, Matcher.quoteReplacement(repl));
        }
        m.appendTail(out);
        return out.toString();
    }

    private Voseo() {}
}
