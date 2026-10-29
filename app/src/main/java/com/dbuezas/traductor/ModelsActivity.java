package com.dbuezas.traductor;

import android.app.Activity;
import android.app.AlertDialog;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Settings: download, choose and delete models, and pick the voice of each language. */
public class ModelsActivity extends Activity {
    private LinearLayout list;
    private TextToSpeech tts;   // to list the phone's voices and play samples
    private boolean ttsReady;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(Ui.BG);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(20), dp(16), dp(24));
        scroll.addView(list);
        setContentView(scroll);
        tts = new TextToSpeech(this, st -> runOnUiThread(() -> {
            ttsReady = st == TextToSpeech.SUCCESS;
            render();
        }));
    }

    @Override
    protected void onDestroy() {
        tts.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // progress only updates its bar and number: redrawing everything ~20 times per second
        // would swap the buttons under the finger, so a second "Bajar" tap would get lost
        Models.listener = (m, done) -> runOnUiThread(() -> {
            if (done) render();
            else updateProgress(m);
        });
        render();
    }

    @Override
    protected void onPause() {
        Models.listener = null;
        super.onPause();
    }

    private final java.util.Map<String, Object[]> progressViews = new java.util.HashMap<>();   // id -> {bar, label}

    private void updateProgress(Models.Model m) {
        Object[] v = progressViews.get(m.id);
        if (v == null) {   // a download that started elsewhere: show its bar
            render();
            return;
        }
        Long got = Models.progress.get(m.id);
        long bytes = got == null ? 0 : got;
        ((ProgressBar) v[0]).setProgress((int) (bytes * 1000 / m.size));
        ((TextView) v[1]).setText(mb(bytes) + " de " + mb(m.size));
    }

    private final java.util.Set<Models.Kind> open = new java.util.HashSet<>();   // sections showing all options

    private void render() {
        list.removeAllViews();
        progressViews.clear();
        list.addView(text("Ajustes", 28));
        title("Escuchar (Whisper)");
        section(Models.Kind.WHISPER);
        title("Traducir");
        section(Models.Kind.TRANSLATOR);
        title("Voces");
        for (Lang l : Voices.langsInUse(this)) voiceRow(l);
        TextView note = text("Ordenados de mejor a peor. Precisión y velocidad son una estimación mía en un Pixel 7. Los modelos se bajan de Hugging Face una sola vez; después todo anda sin internet.", 14);
        note.setTextColor(Ui.MUTED);
        note.setPadding(dp(4), dp(24), dp(4), 0);
        list.addView(note);
    }

    /**
     * Like a select box: shows the model in use; tapping it opens the list of all options. A
     * section with a download in progress stays open, so its progress can be seen.
     */
    private void section(Models.Kind kind) {
        boolean downloading = false;
        for (Models.Model m : Models.ALL) if (m.kind == kind && Models.isDownloading(m)) downloading = true;
        boolean expanded = open.contains(kind) || downloading;
        Models.Model sel = Models.selectedModel(this, kind);

        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable card = Ui.round(Ui.CARD, dp(18));
        card.setStroke(dp(sel != null ? 2 : 1), sel != null ? Ui.A : Ui.LINE);
        box.setBackground(Ui.ripple(card, Ui.dark ? 0x22FFFFFF : 0x22000000));
        box.setOnClickListener(v -> {
            if (!open.remove(kind)) open.add(kind);
            render();
        });
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(this, sel != null ? sel.label : "Elegí un modelo", 17, Ui.TEXT, true));
        if (sel != null) texts.addView(ratings(sel));
        texts.addView(Ui.text(this, sel != null ? mb(sel.size) + " · " + sel.note : "Tocá para ver las opciones y bajar uno.", 14, Ui.MUTED, false));
        box.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(Ui.text(this, expanded ? "▴" : "▾", 22, Ui.MUTED, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        list.addView(box, lp);

        if (expanded) for (Models.Model m : Models.ALL) if (m.kind == kind) row(m);
    }

    /** One option in an open section: tap a downloaded one to use it. */
    private void row(Models.Model m) {
        boolean have = Models.isDownloaded(this, m);
        boolean downloading = Models.isDownloading(m);
        boolean selected = have && m.id.equals(Models.selectedId(this, m.kind));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(10), dp(12), dp(10));
        GradientDrawable card = Ui.round(selected ? Ui.A_TINT : Ui.CARD, dp(14));
        card.setStroke(dp(1), selected ? Ui.A : Ui.LINE);
        box.setBackground(card);
        if (have && !selected) {
            box.setBackground(Ui.ripple(card, Ui.dark ? 0x22FFFFFF : 0x22000000));
            box.setOnClickListener(v -> {
                Models.select(this, m);
                open.remove(m.kind);
                render();
            });
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        lp.leftMargin = dp(12);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(this, (selected ? "✓  " : "") + m.label, 16, selected ? Ui.A_DARK : Ui.TEXT, true));
        texts.addView(ratings(m));
        texts.addView(Ui.text(this, mb(m.size) + " · " + m.note, 13, Ui.MUTED, false));
        head.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        if (downloading) head.addView(button("Cancelar", () -> Models.cancel(m)));
        else if (!have) head.addView(button("Bajar", () -> { Models.download(this, m); render(); }));
        else if (!selected) head.addView(button("Borrar", () -> { Models.delete(this, m); render(); }));
        box.addView(head);

        String err = Models.errors.get(m.id);
        if (err != null) box.addView(Ui.text(this, "Error: " + err, 13, Ui.RED, false));
        if (downloading) {
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            box.addView(bar);
            TextView label = Ui.text(this, "", 13, Ui.MUTED, false);
            box.addView(label);
            progressViews.put(m.id, new Object[]{bar, label});
            updateProgress(m);
        }
        list.addView(box, lp);
    }

    /** The voice used to read translations aloud in this language: tap to pick another. */
    private void voiceRow(Lang l) {
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable card = Ui.round(Ui.CARD, dp(18));
        card.setStroke(dp(1), Ui.LINE);
        box.setBackground(card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(this, l.flag + "  " + l.label, 17, Ui.TEXT, true));
        List<Voice> voices = ttsReady ? Voices.forLang(tts, l) : new ArrayList<>();
        String chosen = Voices.chosen(this, l);
        int index = -1;
        for (int i = 0; i < voices.size(); i++) if (voices.get(i).getName().equals(chosen)) index = i;
        String current = !ttsReady ? "…" : voices.isEmpty() ? "No hay voces instaladas"
                : index < 0 ? "Automática" : Voices.label(voices.get(index), index);
        texts.addView(Ui.text(this, current + (voices.isEmpty() ? "" : " · " + voices.size() + " voces"), 14, Ui.MUTED, false));
        box.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        if (!voices.isEmpty()) {   // tapping the card picks the voice
            box.setBackground(Ui.ripple(card, Ui.dark ? 0x22FFFFFF : 0x22000000));
            box.setOnClickListener(v -> pickVoice(l, voices));
            box.addView(Ui.text(this, "▾", 22, Ui.MUTED, false));
        }
        list.addView(box, lp);
    }

    private void pickVoice(Lang l, List<Voice> voices) {
        String[] labels = new String[voices.size() + 1];
        labels[0] = "Automática";
        for (int i = 0; i < voices.size(); i++) labels[i + 1] = Voices.label(voices.get(i), i);
        new AlertDialog.Builder(this, Ui.dialogTheme())
                .setTitle(l.flag + "  " + l.label)
                .setItems(labels, (d, i) -> {
                    Voice v = i == 0 ? null : voices.get(i - 1);
                    Voices.choose(this, l, v);
                    if (v != null) {   // play a sample, so the choice can be heard
                        tts.setVoice(v);
                        tts.speak(l.holdToTalk, TextToSpeech.QUEUE_FLUSH, null, "sample");
                    }
                    render();
                })
                .show();
    }

    private void title(String s) {
        TextView t = Ui.text(this, s.toUpperCase(), 13, Ui.MUTED, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(4), dp(24), 0, dp(4));
        list.addView(t);
    }

    /** "Precisión ●●●●○   Velocidad ●●●○○" */
    private TextView ratings(Models.Model m) {
        TextView t = Ui.text(this, "Precisión " + dots(m.precision) + "    Velocidad " + dots(m.speed), 13, Ui.MUTED, true);
        t.setPadding(0, dp(3), 0, dp(1));
        return t;
    }

    private static String dots(int n) {
        return "●●●●●".substring(0, n) + "○○○○○".substring(n);
    }

    private TextView text(String s, int sp) {
        return Ui.text(this, s, sp, Ui.TEXT, false);
    }

    private View button(String label, Runnable r) {
        boolean danger = label.equals("Borrar") || label.equals("Cancelar");
        boolean primary = label.equals("Bajar");
        TextView b = Ui.pill(this, label, primary ? Ui.A : danger ? (Ui.dark ? 0xFF3B1414 : 0xFFFEE2E2) : Ui.BG,
                primary ? Ui.WHITE : danger ? Ui.RED : Ui.TEXT);
        b.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.0f MB", bytes / 1e6);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
