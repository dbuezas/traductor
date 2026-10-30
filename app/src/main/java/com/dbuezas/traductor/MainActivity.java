package com.dbuezas.traductor;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.os.Build;
import android.view.WindowInsetsController;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Two people face each other with the phone between them. The bottom half is for person A,
 * the top half (upside down) for person B. Each half shows the whole conversation in its language.
 */
public class MainActivity extends Activity implements Engine.Listener {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Engine engine;
    private TextToSpeech tts;
    private boolean ttsReady;
    private int speaking;

    private Lang langA, langB;
    private Panel panelA, panelB;
    private TextView status, modeAuto, modeTurns;
    private FrameLayout mic;
    private ImageView micIcon;
    private boolean turnMode;
    private Engine.Session currentTurn;   // the turn whose button is held
    private Panel heldPanel;              // the panel whose talk button is held
    private Engine.Utt recordingUtt;      // the utterance being recorded now (auto mode)
    private boolean voiceNow;             // voice in the mic right now
    private String loadedWhisper, loadedLive, loadedTranslator;

    /** One half of the screen. */
    private final class Panel {
        final boolean top;
        final int accent, accentDark, tint;
        final LinearLayout list;
        final ScrollView scroll;
        final TextView langChip, rateChip, stateChip, emptyHint, talkLabel;
        final View meter;
        final LinearLayout talkButton;      // turn mode only
        final ImageView talkIcon;
        final Map<Integer, Bubble> rows = new HashMap<>();
        boolean ttsSpeaking;

        Panel(LinearLayout root, boolean top) {
            this.top = top;
            accent = top ? Ui.B : Ui.A;
            accentDark = top ? Ui.B_DARK : Ui.A_DARK;
            tint = top ? Ui.B_TINT : Ui.A_TINT;

            LinearLayout box = new LinearLayout(MainActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackgroundColor(tint);
            if (top) box.setRotation(180);

            // header: language chip, and what is happening on this side
            LinearLayout header = new LinearLayout(MainActivity.this);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(dp(16), dp(10), dp(16), dp(4));
            langChip = Ui.pill(MainActivity.this, "", Ui.CARD, accentDark);
            langChip.setOnClickListener(v -> pickLang(top));
            header.addView(langChip, new LinearLayout.LayoutParams(-2, -2));
            // how fast the phone reads aloud to this person; tap to cycle
            rateChip = Ui.pill(MainActivity.this, "", Ui.CARD, accentDark);
            rateChip.setOnClickListener(v -> {
                int i = 0;
                while (i < RATES.length && RATES[i] <= rate() + 0.01f) i++;
                Models.prefs(MainActivity.this).edit().putFloat("rate_" + (top ? "b" : "a"), RATES[i % RATES.length]).apply();
                updateTexts();
            });
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
            rlp.leftMargin = dp(8);
            header.addView(rateChip, rlp);
            header.addView(new View(MainActivity.this), new LinearLayout.LayoutParams(0, 1, 1));
            stateChip = Ui.text(MainActivity.this, "", 14, Ui.WHITE, true);
            stateChip.setPadding(dp(12), dp(6), dp(12), dp(6));
            stateChip.setVisibility(View.GONE);
            header.addView(stateChip, new LinearLayout.LayoutParams(-2, -2));
            box.addView(header, new LinearLayout.LayoutParams(-1, -2));

            // mic level of this side's speaker
            meter = new View(MainActivity.this);
            meter.setBackground(Ui.round(accent, dp(2)));
            meter.setPivotX(0);
            meter.setScaleX(0);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, dp(3));
            mlp.setMargins(dp(16), dp(6), dp(16), 0);
            box.addView(meter, mlp);

            FrameLayout content = new FrameLayout(MainActivity.this);
            scroll = new ScrollView(MainActivity.this);
            scroll.setClipToPadding(false);
            list = new LinearLayout(MainActivity.this);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setPadding(dp(14), dp(6), dp(14), dp(10));
            scroll.addView(list);
            content.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
            emptyHint = Ui.text(MainActivity.this, "", 20, Ui.FAINT, false);
            emptyHint.setLineSpacing(dp(4), 1f);
            emptyHint.setGravity(Gravity.CENTER);
            content.addView(emptyHint, new FrameLayout.LayoutParams(-1, -1));
            box.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

            // hold-to-talk button
            talkButton = new LinearLayout(MainActivity.this);
            talkButton.setGravity(Gravity.CENTER);
            talkIcon = new ImageView(MainActivity.this);
            talkIcon.setImageResource(R.drawable.ic_mic);
            talkIcon.setImageTintList(ColorStateList.valueOf(Ui.WHITE));
            talkButton.addView(talkIcon, new LinearLayout.LayoutParams(dp(28), dp(28)));
            talkLabel = Ui.text(MainActivity.this, "", 18, Ui.WHITE, true);
            talkLabel.setPadding(dp(10), 0, 0, 0);
            talkButton.addView(talkLabel);
            talkButton.setOnTouchListener((v, e) -> {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    if (startTurn(this)) setHeld(true);
                } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                    setHeld(false);
                    // a short tail, people let go while still saying the last word
                    Engine.Session turn = currentTurn;
                    currentTurn = null;
                    heldPanel = null;
                    if (turn != null) ui.postDelayed(() -> engine.endTurn(turn), 300);
                    refreshStates();
                }
                return true;
            });
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, dp(80));
            tlp.setMargins(dp(16), dp(6), dp(16), dp(16));
            box.addView(talkButton, tlp);
            setHeld(false);

            root.addView(box, new LinearLayout.LayoutParams(-1, 0, 1));
        }

        Lang lang() {
            return this == panelA ? langA : langB;
        }

        /** Speech rate for reading aloud to this side. */
        float rate() {
            return Models.prefs(MainActivity.this).getFloat("rate_" + (top ? "b" : "a"), 1f);
        }

        void setHeld(boolean held) {
            talkButton.setBackground(Ui.round(held ? Ui.RED : accent, dp(40)));
            talkButton.setScaleX(held ? 0.97f : 1f);
            talkButton.setScaleY(held ? 0.97f : 1f);
            talkLabel.setText(held ? lang().releaseToTranslate : lang().holdToTalk);
        }

        void updateTexts() {
            langChip.setText(lang().flag + "  " + lang().label + "  ▾");
            float r = rate();
            rateChip.setText("🔊 " + (r == (int) r ? String.valueOf((int) r) : String.valueOf(r)).replace('.', ',') + "×");
            setHeld(heldPanel == this);
            emptyHint.setText(lang().flag + "\n" + lang().label);
            emptyHint.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        }

        /** Shows what this side's app is doing: listening to this person, translating, speaking. */
        void setState(String text, int bg) {
            if (text == null) {
                stateChip.setVisibility(View.GONE);
                return;
            }
            stateChip.setText(text);
            stateChip.setBackground(Ui.round(bg, dp(100)));
            stateChip.setVisibility(View.VISIBLE);
        }

        /** Shows the utterance in this panel's language: own speech as original, the other's as translation. */
        void show(Engine.Utt u) {
            Bubble b = rows.get(u.id);
            if (u.dropped) {   // noise, nothing said, or cancelled
                if (b != null) list.removeView(b.view);
                rows.remove(u.id);
                emptyHint.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                return;
            }
            Lang said = u.lang != null ? u.lang : u.shownLang;   // null: not known yet
            boolean own = said == lang();
            if (b == null) {
                b = new Bubble();
                rows.put(u.id, b);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.topMargin = dp(8);
                list.addView(b.view, lp);
                emptyHint.setVisibility(View.GONE);
            }
            b.update(u, said == null ? null : own, this);
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    /** One chat bubble: the text, and a small line saying what is still happening to it. */
    private final class Bubble {
        final LinearLayout view = new LinearLayout(MainActivity.this);
        final TextView text = Ui.text(MainActivity.this, "", 19, Ui.TEXT, false);
        final TextView meta = Ui.text(MainActivity.this, "", 12, Ui.FAINT, true);
        final ImageView play = new ImageView(MainActivity.this);   // translations: play / pause the voice

        Bubble() {
            view.setOrientation(LinearLayout.VERTICAL);
            view.setPadding(dp(14), dp(10), dp(14), dp(10));
            text.setMaxWidth(getResources().getDisplayMetrics().widthPixels * 4 / 5);
            text.setLineSpacing(0, 1.1f);
            // wrap_content, not the default match_parent: in a wrap_content bubble a match_parent
            // child takes the width of the other children (the footer) and the text gets squeezed
            view.addView(text, new LinearLayout.LayoutParams(-2, -2));
            LinearLayout footer = new LinearLayout(MainActivity.this);
            footer.setGravity(Gravity.CENTER_VERTICAL);
            meta.setPadding(0, dp(2), 0, 0);
            // no weight here: inside a wrap_content bubble a weighted child gets no width
            footer.addView(meta, new LinearLayout.LayoutParams(-2, -2));
            play.setPadding(dp(7), dp(7), dp(7), dp(7));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(34), dp(34));
            plp.leftMargin = dp(10);
            plp.topMargin = dp(4);
            footer.addView(play, plp);
            view.addView(footer, new LinearLayout.LayoutParams(-2, -2));
        }

        /** own: spoken in this panel's language; null: language not known yet (neutral, centered). */
        void update(Engine.Utt u, Boolean own, Panel p) {
            float r = dp(20), tail = dp(6);
            boolean mine = Boolean.TRUE.equals(own);
            Lang l = p.lang();
            if (own == null) {
                view.setBackground(Ui.round(Ui.LINE, r));
                setGravity(Gravity.CENTER_HORIZONTAL);
                text.setVisibility(View.GONE);
                play.setVisibility(View.GONE);
                setMeta(u.recording ? "🎙  " + l.listening : "⋯  " + l.transcribing, Ui.MUTED, !u.recording);
                return;
            }
            int bg = mine ? p.accent : Ui.CARD;
            int fg = mine ? Ui.WHITE : Ui.TEXT;
            int pendingFg = mine ? 0xB3FFFFFF : Ui.FAINT;
            GradientDrawable shape = mine ? Ui.round(bg, r, r, tail, r) : Ui.round(bg, r, r, r, tail);
            if (!mine) shape.setStroke(dp(1), Ui.LINE);
            view.setBackground(shape);
            setGravity(mine ? Gravity.END : Gravity.START);

            // settled text solid, text that may still change faded
            String settled = mine ? u.committed : u.translationDone();
            String pending = mine ? u.tail : u.translating;
            SpannableStringBuilder s = new SpannableStringBuilder(settled);
            if (!pending.isEmpty()) {
                if (s.length() > 0) s.append(' ');
                int from = s.length();
                s.append(pending);
                s.setSpan(new ForegroundColorSpan(pendingFg), from, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            text.setText(s);
            text.setTextColor(fg);
            text.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);

            // what is still happening, in the reader's language
            boolean transcribing = !u.recording && !u.ended;
            boolean translating = u.ended && u.translations.size() < u.pieces.size();
            String m = u.recording ? "🎙  " + l.listening
                    : transcribing && mine ? "⋯  " + l.transcribing
                    : transcribing || translating ? "⋯  " + l.translating : "";
            setMeta(m, mine ? 0xCCFFFFFF : Ui.MUTED, transcribing || translating);

            // a finished translation can be played, and paused while it plays
            boolean playable = !mine && u.ended && !translating && !u.translationDone().isEmpty();
            play.setVisibility(playable ? View.VISIBLE : View.GONE);
            if (playable) {
                boolean playing = p.ttsSpeaking && u.id == speakingId;
                play.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
                play.setImageTintList(ColorStateList.valueOf(Ui.WHITE));
                play.setBackground(Ui.ripple(Ui.round(p.accent, dp(100)), 0x33FFFFFF));
                play.setOnClickListener(v -> {
                    if (playing) tts.stop();
                    else speak(u, p);
                });
            }
        }

        /** Changing layout params needs setLayoutParams, or the bubble keeps its old size. */
        private void setGravity(int g) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) view.getLayoutParams();
            if (lp.gravity == g) return;
            lp.gravity = g;
            view.setLayoutParams(lp);
        }

        /** The status line; busy: it pulses, so it is clear that the phone is working. */
        private void setMeta(String m, int color, boolean busy) {
            meta.setText(m);
            meta.setTextColor(color);
            meta.setVisibility(m.isEmpty() ? View.GONE : View.VISIBLE);
            if (busy && pulse == null) {
                pulse = ObjectAnimator.ofFloat(meta, View.ALPHA, 1f, 0.3f);
                pulse.setDuration(700);
                pulse.setRepeatCount(ValueAnimator.INFINITE);
                pulse.setRepeatMode(ValueAnimator.REVERSE);
                pulse.start();
            } else if (!busy && pulse != null) {
                pulse.cancel();
                pulse = null;
                meta.setAlpha(1f);
            }
        }

        private ObjectAnimator pulse;
    }

    private int speakingId = -1;
    private static final float[] RATES = {0.75f, 1f, 1.25f, 1.5f};
    private String lastStatus = "";
    private final Map<Integer, Engine.Utt> utts = new HashMap<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences p = Models.prefs(this);
        langA = Lang.byCode(p.getString("langA", "es"), Lang.ES);
        langB = Lang.byCode(p.getString("langB", "de"), Lang.DE);
        turnMode = p.getBoolean("turnMode", false);
        Models.init(this);
        buildUi();

        File cache = new File(getFilesDir(), "cache");
        cache.mkdirs();
        Native.setCacheDir(cache.getPath());
        engine = new Engine(this, langA, langB);
        engine.setDebugDir(new File(getFilesDir(), "debug"));
        applyModeUi();
        tts = new TextToSpeech(this, s -> ttsReady = s == TextToSpeech.SUCCESS);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {
                ui.post(() -> {
                    speaking++;
                    engine.setMuted(true);
                    setSpeaking(id, true);
                });
            }
            @Override public void onDone(String id) { ui.post(() -> doneSpeaking(id)); }
            @Override public void onError(String id) { ui.post(() -> doneSpeaking(id)); }
            @Override public void onStop(String id, boolean interrupted) { ui.post(() -> doneSpeaking(id)); }
        });

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }
        handleTestIntent(getIntent());
    }

    /** Builds the whole screen in the phone's current light/dark palette, keeping the conversation. */
    private void buildUi() {
        Ui.init(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setClipChildren(false);
        // behind the status and navigation bars: each side's tint
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Ui.B_TINT, Ui.B_TINT, Ui.A_TINT, Ui.A_TINT}));
        root.setFitsSystemWindows(true);

        panelB = new Panel(root, true);
        root.addView(buildBar(), new LinearLayout.LayoutParams(-1, -2));
        panelA = new Panel(root, false);
        setContentView(root);

        // dark status and navigation bar icons on the light palette, light ones on the dark one
        int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        if (Build.VERSION.SDK_INT >= 30) getWindow().getInsetsController().setSystemBarsAppearance(Ui.dark ? 0 : light, light);

        List<Engine.Utt> list = new ArrayList<>(utts.values());
        list.sort((x, y) -> Integer.compare(x.id, y.id));
        for (Engine.Utt u : list) {
            panelA.show(u);
            panelB.show(u);
        }
    }

    /** The phone switched light/dark mode: rebuild the screen; models and conversation stay. */
    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        buildUi();
        applyModeUi();
        status.setText(lastStatus);
    }

    /** The middle bar: mode switch, status, mic (auto mode) and settings. */
    private View buildBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(8), dp(8), dp(8));
        bar.setBackground(Ui.round(Ui.CARD, dp(24)));
        bar.setClipChildren(false);   // the mic button grows with the voice level
        bar.setElevation(dp(Ui.dark ? 2 : 6));
        if (Ui.dark) ((GradientDrawable) bar.getBackground()).setStroke(dp(1), Ui.LINE);

        LinearLayout seg = new LinearLayout(this);
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        seg.setBackground(Ui.round(Ui.BG, dp(100)));
        modeAuto = Ui.pill(this, getString(R.string.mode_auto), Ui.BG, Ui.MUTED);
        modeTurns = Ui.pill(this, getString(R.string.mode_turns), Ui.BG, Ui.MUTED);
        modeAuto.setOnClickListener(v -> setTurnMode(false));
        modeTurns.setOnClickListener(v -> setTurnMode(true));
        seg.addView(modeAuto);
        seg.addView(modeTurns);
        bar.addView(seg);

        status = Ui.text(this, "", 13, Ui.MUTED, false);
        status.setGravity(Gravity.CENTER);
        status.setMaxLines(2);
        status.setPadding(dp(8), 0, dp(8), 0);
        bar.addView(status, new LinearLayout.LayoutParams(0, -2, 1));

        mic = new FrameLayout(this);
        micIcon = new ImageView(this);
        micIcon.setImageTintList(ColorStateList.valueOf(Ui.WHITE));
        mic.addView(micIcon, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER));
        mic.setOnClickListener(v -> toggleMic());
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(52), dp(52));
        mlp.rightMargin = dp(6);
        bar.addView(mic, mlp);

        FrameLayout gear = new FrameLayout(this);
        ImageView gearIcon = new ImageView(this);
        gearIcon.setImageResource(R.drawable.ic_settings);
        gearIcon.setImageTintList(ColorStateList.valueOf(Ui.MUTED));
        gear.addView(gearIcon, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        gear.setBackground(Ui.ripple(Ui.round(Ui.BG, dp(100)), 0x22000000));
        gear.setClickable(true);
        gear.setOnClickListener(v -> startActivity(new Intent(this, ModelsActivity.class)));
        bar.addView(gear, new LinearLayout.LayoutParams(dp(44), dp(44)));

        // drawn above both halves (translationZ, no background of its own), so neither cuts off
        // the bar's shadow or the mic button when it grows with the voice
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(10), 0, dp(10), 0);
        wrap.setClipToPadding(false);
        wrap.setClipChildren(false);
        wrap.setTranslationZ(dp(8));
        wrap.addView(bar);
        return wrap;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleTestIntent(intent);
    }

    /**
     * adb shell am start -n com.dbuezas.traductor/.MainActivity --es wav files/test.wav
     *     --el at $(date +%s)000 [--es turn es]
     * "at" must be fresh: Android keeps the intent that started the task and gives it again when
     * the app is opened later from the launcher, which must not replay the test.
     */
    private void handleTestIntent(Intent intent) {
        String wav = intent.getStringExtra("wav");
        String turn = intent.getStringExtra("turn");   // optional: replay as one turn in this language
        long at = intent.getLongExtra("at", 0);
        if (wav == null || Math.abs(System.currentTimeMillis() - at) > 60_000) return;
        intent.removeExtra("wav");
        testing = true;
        testStarted = false;
        engine.stop();
        File f = new File(getDataDir(), wav);
        // wait until the models are loaded and the previous run has stopped
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                engine.stop();   // the mic may have started meanwhile
                if (!engine.isReady() || !engine.isIdle()) { ui.postDelayed(this, 500); return; }
                engine.start(f, turn == null ? null : Lang.byCode(turn, null));
                testStarted = true;
                refreshStates();
            }
        }, 500);
    }

    private void doneSpeaking(String id) {
        setSpeaking(id, false);
        speaking = Math.max(0, speaking - 1);
        // short delay so the room echo of the last word is not taken as speech
        if (speaking == 0) ui.postDelayed(() -> { if (speaking == 0) engine.setMuted(false); }, 300);
    }

    /** Utterance ids are "<id>:<a|b>": the panel that hears the translation. */
    private void setSpeaking(String id, boolean on) {
        String[] parts = id.split(":");
        Panel p = parts[1].equals("a") ? panelA : panelB;
        p.ttsSpeaking = on;
        int uid = Integer.parseInt(parts[0]);
        speakingId = on ? uid : -1;
        Engine.Utt u = utts.get(uid);
        if (u != null) p.show(u);   // its 🔊 mark
        refreshStates();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        ui.removeCallbacks(keepListeningTick);
        ui.post(keepListeningTick);
        File w = Models.selectedFile(this, Models.Kind.WHISPER);
        File live = null;   // no small live model any more: the main model runs pass after pass
        File t = Models.selectedFile(this, Models.Kind.TRANSLATOR);
        String ws = w == null ? null : w.getPath(), ls = live == null ? null : live.getPath(), ts = t == null ? null : t.getPath();
        boolean wChanged = ws != null && !ws.equals(loadedWhisper);
        boolean lChanged = ls != null && !ls.equals(loadedLive);
        boolean tChanged = ts != null && !ts.equals(loadedTranslator);
        if (wChanged || lChanged || tChanged) {
            Models.Model tm = Models.selectedModel(this, Models.Kind.TRANSLATOR);
            engine.load(wChanged ? w : null, lChanged ? live : null, tChanged ? t : null, tm != null && tm.general);
        }
        if (ls == null && loadedLive != null) engine.unloadLive();
        loadedWhisper = ws;
        loadedLive = ls;
        loadedTranslator = ts;
        if (ws == null || ts == null) status.setText(R.string.status_missing_models);
    }

    @Override
    protected void onDestroy() {
        engine.stop();
        tts.shutdown();
        super.onDestroy();
    }

    /** The round button in auto mode: pause on purpose, or listen again. */
    private void toggleMic() {
        paused = !paused;
        if (paused) engine.stop();
        else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }
        keepListening();
        refreshStates();
    }

    private boolean paused;    // auto mode paused on purpose with the round button
    private boolean resumed;   // the screen is in front (no mic in the background)
    private boolean testing;   // a test WAV replaces the mic until it ends
    private boolean testStarted;

    /**
     * Auto mode always listens while the app is in front, unless paused on purpose. Checked every
     * second, so it also starts once the models are loaded and after a test replay ends.
     */
    private final Runnable keepListeningTick = new Runnable() {
        @Override public void run() {
            keepListening();
            if (resumed) ui.postDelayed(this, 1000);
        }
    };

    private void keepListening() {
        if (testing) {
            if (!testStarted || engine.isListening()) return;
            testing = testStarted = false;
        }
        boolean want = resumed && !turnMode && !paused && engine.isReady()
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (want && !engine.isListening()) {
            engine.start();
            refreshStates();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (!testing && !turnMode) engine.stop();   // no mic in the background
        super.onPause();
    }

    /** Turn mode: each person holds the button on their side while talking. */
    private void setTurnMode(boolean on) {
        turnMode = on;
        Models.prefs(this).edit().putBoolean("turnMode", on).apply();
        engine.cancelAll();
        if (tts != null) tts.stop();
        speaking = 0;
        engine.setMuted(false);
        currentTurn = null;
        heldPanel = null;
        paused = false;
        applyModeUi();
        keepListening();
    }

    /** Shows the controls of the current mode. */
    private void applyModeUi() {
        boolean on = turnMode;
        modeAuto.setBackground(Ui.round(on ? Ui.BG : Ui.CARD, dp(100)));
        modeAuto.setTextColor(on ? Ui.MUTED : Ui.TEXT);
        modeTurns.setBackground(Ui.round(on ? Ui.CARD : Ui.BG, dp(100)));
        modeTurns.setTextColor(on ? Ui.TEXT : Ui.MUTED);
        mic.setVisibility(on ? View.GONE : View.VISIBLE);
        panelA.talkButton.setVisibility(on ? View.VISIBLE : View.GONE);
        panelB.talkButton.setVisibility(on ? View.VISIBLE : View.GONE);
        panelA.updateTexts();
        panelB.updateTexts();
        refreshStates();
    }

    private boolean startTurn(Panel p) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            return false;
        }
        tts.stop();   // talking over the phone interrupts it
        speaking = 0;
        engine.setMuted(false);
        currentTurn = engine.startTurn(p.lang());
        heldPanel = p;
        refreshStates();
        return true;
    }

    /**
     * Updates everything that shows what is happening: the mic button, the state chip of each
     * side (who is being listened to, who is being spoken to), and screen brightness.
     */
    private void refreshStates() {
        boolean listening = engine != null && engine.isListening();
        mic.setBackground(Ui.ripple(Ui.round(listening ? Ui.RED : Ui.STRONG, dp(100)), 0x33FFFFFF));
        micIcon.setImageResource(listening ? R.drawable.ic_pause : R.drawable.ic_mic);

        // who is being listened to: the held side (turns), or the side of the detected language (auto)
        Lang talking = null;
        if (heldPanel != null) talking = heldPanel.lang();
        else if (recordingUtt != null && recordingUtt.recording && !recordingUtt.dropped) {
            talking = recordingUtt.lang != null ? recordingUtt.lang : recordingUtt.shownLang;
        }
        for (Panel p : new Panel[]{panelA, panelB}) {
            Lang l = p.lang();
            if (p.ttsSpeaking) p.setState("🔊", p.accent);
            else if (talking == l) p.setState("●  " + l.listening, Ui.RED);
            else if (listening && !turnMode && talking == null) p.setState("●  " + l.listening, voiceNow ? Ui.RED : Ui.FAINT);
            else p.setState(null, 0);
        }

        boolean on = listening || turnMode;
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void pickLang(boolean top) {
        Lang[] all = Lang.values();
        String[] labels = new String[all.length];
        for (int i = 0; i < all.length; i++) labels[i] = all[i].flag + "   " + all[i].label;
        new AlertDialog.Builder(this, Ui.dialogTheme()).setItems(labels, (d, i) -> {
            if (top) langB = all[i];
            else langA = all[i];
            Models.prefs(this).edit().putString("langA", langA.code).putString("langB", langB.code).apply();
            engine.setLangs(langA, langB);
            panelA.updateTexts();
            panelB.updateTexts();
            refreshStates();
        }).show();
    }

    private int dp(int v) {
        return Ui.dp(this, v);
    }

    // ---- Engine.Listener (called from background threads) ----

    @Override
    public void onStatus(int text, Object... args) {
        ui.post(() -> {
            lastStatus = getString(text, args);
            status.setText(lastStatus);
            refreshStates();
        });
    }

    @Override
    public void onLevel(float level, boolean voice) {
        ui.post(() -> {
            voiceNow = voice;
            // the meter of the side being listened to; both sides while the language is unknown
            Lang talking = heldPanel != null ? heldPanel.lang()
                    : recordingUtt != null && recordingUtt.recording ? (recordingUtt.lang != null ? recordingUtt.lang : recordingUtt.shownLang) : null;
            for (Panel p : new Panel[]{panelA, panelB}) {
                boolean mine = talking == null ? engine.isListening() && heldPanel == null : talking == p.lang();
                p.meter.animate().scaleX(mine ? level : 0).setDuration(90).start();
            }
            float scale = 1 + (engine.isListening() && heldPanel == null ? level * 0.12f : 0);
            mic.animate().scaleX(scale).scaleY(scale).setDuration(90).start();
            refreshStates();
        });
    }

    @Override
    public void onUtt(Engine.Utt u) {
        ui.post(() -> {
            utts.put(u.id, u);
            if (u.recording && !u.dropped) recordingUtt = u;
            else if (recordingUtt == u) recordingUtt = null;
            panelA.show(u);
            panelB.show(u);
            refreshStates();
        });
    }

    @Override
    public void onTranslated(Engine.Utt u, String piece) {
        ui.post(() -> {
            Panel listener = u.lang != langA ? panelA : panelB;
            if (!ttsReady || piece.isEmpty()) return;
            setTtsLanguage(listener.lang());
            tts.setSpeechRate(listener.rate());
            tts.speak(piece, TextToSpeech.QUEUE_ADD, null, u.id + ":" + (listener == panelA ? "a" : "b"));
        });
    }

    /** Plays one translation now (the play button), interrupting whatever is being spoken. */
    private void speak(Engine.Utt u, Panel p) {
        if (!ttsReady) return;
        setTtsLanguage(p.lang());
        tts.setSpeechRate(p.rate());
        tts.speak(u.translationDone(), TextToSpeech.QUEUE_FLUSH, null, u.id + ":" + (p == panelA ? "a" : "b"));
    }

    /** Sets the voice for a language: the one picked in settings, else the default for it. */
    private void setTtsLanguage(Lang lang) {
        Voice v = Voices.find(this, tts, lang);
        if (v != null && tts.setVoice(v) == TextToSpeech.SUCCESS) return;
        Locale l = lang.locale;
        int r = tts.setLanguage(l);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(new Locale(l.getLanguage()));
        }
    }
}
