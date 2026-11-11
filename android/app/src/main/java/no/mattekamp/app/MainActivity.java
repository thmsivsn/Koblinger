package no.mattekamp.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;

/**
 * Viser spillet (mattekamp.html) i en WebView og kobler det til Androids
 * egen talegjenkjenning og opplesing på norsk.
 *
 * Siden kaller AndroidBridge.speech("start" | "stop" | "reset") og AndroidBridge.tts(tekst).
 * Appen svarer med window.nativeSpeech(tekst, ferdig, alternativ),
 * window.nativeSpeechState(status) og window.nativeTTSDone().
 */
public class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean wanted = false;
    private int utterance = 0;
    private String lastHeard = "";
    /** Tekst som var hørt da spillet sist ba oss glemme («reset»). Fjernes fra starten av nye resultater. */
    private String base = "";
    private boolean sessionActive = false;
    /** Språkene spilleren som har tur svarer på. Det første er hovedspråket. */
    private String[] languages = {"nb-NO"};

    /** Vanlig gjenkjenner (via nett) og gjenkjenner på selve telefonen (Android 13+). */
    private SpeechRecognizer online;
    private SpeechRecognizer device;
    private SpeechRecognizer active;
    private boolean segmented = false;
    /** Lyttemåte valgt i spillet: "fast" (telefonen, fortløpende tekst), "segmented" (telefonen, sammenhengende) eller "online" (via nett). */
    private String engineMode = "fast";
    private int generation = 0;
    private boolean supportChecked = false;
    private boolean checking = false;
    private List<String> deviceLangs = new ArrayList<>();
    private List<String> deviceSupported = new ArrayList<>();
    private final Set<String> downloadAsked = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(0xFF0A0F2C);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        setContentView(web);
        web.loadUrl("file:///android_asset/mattekamp.html");

        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) return;
            tts.setLanguage(new Locale("nb", "NO"));
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { js("window.nativeTTSDone && window.nativeTTSDone()"); }
                @Override public void onError(String id) { js("window.nativeTTSDone && window.nativeTTSDone()"); }
            });
            ttsReady = true;
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);   // ikke avslutt midt i en kamp
    }

    @Override
    protected void onPause() {
        super.onPause();
        sessionActive = false;
        generation++;
        if (active != null) active.cancel();
        if (tts != null) tts.stop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wanted && supportChecked) listen();
    }

    @Override
    protected void onDestroy() {
        if (online != null) online.destroy();
        if (device != null) device.destroy();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    /** Kalles fra siden. Metodene kjører på en bakgrunnstråd, så alt sendes til hovedtråden. */
    private class Bridge {
        @JavascriptInterface
        public void speech(String command) { main.post(() -> handleSpeech(command)); }

        @JavascriptInterface
        public void tts(String text) { main.post(() -> speak(text)); }
    }

    private void js(String script) {
        main.post(() -> web.evaluateJavascript(script, null));
    }

    private void state(String s) {
        js("window.nativeSpeechState && window.nativeSpeechState('" + s + "')");
    }

    // ---------- Opplesing ----------

    /** Enten ren tekst (tom = stopp) eller JSON: {"t": tekst, "l": språkkode}. */
    private void speak(String raw) {
        if (tts == null || !ttsReady) {
            js("window.nativeTTSDone && window.nativeTTSDone()");
            return;
        }
        tts.stop();
        if (raw == null || raw.isEmpty()) return;
        String text = raw;
        String lang = "nb-NO";
        if (raw.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(raw);
                text = o.optString("t", "");
                lang = o.optString("l", "nb-NO");
            } catch (Exception ignored) { }
        }
        if (text.isEmpty()) return;
        tts.setLanguage(Locale.forLanguageTag(lang));
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + (++utterance));
    }

    // ---------- Talegjenkjenning ----------

    private void handleSpeech(String command) {
        if (command.startsWith("engine:")) {
            String next = command.substring(7);
            if (next.equals(engineMode)) return;
            engineMode = next;
            if (wanted && supportChecked) listen();
            return;
        }
        if (command.startsWith("lang:")) {
            String[] next = command.substring(5).split(",");
            if (next.length == 0 || next[0].isEmpty() || Arrays.equals(next, languages)) return;
            languages = next;
            if (wanted && supportChecked) listen();   // nytt språk krever en ny lytteøkt
            return;
        }
        switch (command) {
            case "start":
                wanted = true;
                if (!downloadAsked.isEmpty()) supportChecked = false;   // en språkpakke kan ha blitt ferdig nedlastet
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
                } else {
                    begin();
                }
                break;
            case "stop":
                wanted = false;
                sessionActive = false;
                generation++;
                if (active != null) active.cancel();
                state("idle");
                break;
            case "reset":
                // Ikke start lyttingen på nytt (det gir et hull der svar går tapt).
                // Husk hva som er hørt, og fjern det fra starten av det som kommer etterpå.
                if (sessionActive) base = lastHeard;
                else if (wanted && supportChecked) listen();
                break;
            default:
                break;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != 1) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            begin();
        } else {
            wanted = false;
            state("denied");
        }
    }

    private void begin() {
        if (!wanted) return;
        if (!supportChecked && Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            checkDeviceSupport();
            return;
        }
        supportChecked = true;
        listen();
    }

    /** Spør telefonen hvilke språk den kan gjenkjenne uten nett (Android 13+). */
    private void checkDeviceSupport() {
        if (checking) return;
        checking = true;
        try {
            if (device == null) device = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
            device.checkRecognitionSupport(intentFor(languages[0], false), getMainExecutor(), new RecognitionSupportCallback() {
                @Override
                public void onSupportResult(RecognitionSupport support) {
                    deviceLangs = new ArrayList<>(support.getInstalledOnDeviceLanguages());
                    deviceSupported = new ArrayList<>(support.getSupportedOnDeviceLanguages());
                    deviceSupported.addAll(support.getPendingOnDeviceLanguages());
                    finish();
                }

                @Override
                public void onError(int error) {
                    finish();
                }

                private void finish() {
                    supportChecked = true;
                    checking = false;
                    if (wanted) listen();
                }
            });
        } catch (Exception e) {
            supportChecked = true;
            checking = false;
            listen();
        }
    }

    /** Finner språket i en liste fra telefonen. «zh-CN» kan hete «cmn-Hans-CN» der. */
    private static String match(List<String> list, String lang) {
        for (String tag : list) if (tag.equalsIgnoreCase(lang)) return tag;
        String two = lang.substring(0, 2).toLowerCase(Locale.ROOT);
        for (String tag : list) {
            String t = tag.toLowerCase(Locale.ROOT);
            if (t.startsWith(two) || (two.equals("zh") && t.startsWith("cmn-hans")) || (two.equals("nb") && t.startsWith("no"))) return tag;
        }
        return null;
    }

    private Intent intentFor(String lang, boolean continuous) {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        if (continuous && Build.VERSION.SDK_INT >= 33) {
            // Sammenhengende lytting: resultatene kommer bit for bit, uten at økten avsluttes.
            i.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS);
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L);
        }
        return i;
    }

    private void listen() {
        generation++;
        if (active != null) active.cancel();
        sessionActive = false;
        String lang = languages[0];
        String tag = device != null && !engineMode.equals("online") ? match(deviceLangs, lang) : null;
        boolean onDevice = tag != null;
        boolean continuous = onDevice && engineMode.equals("segmented");

        // Språket kan lastes ned til telefonen: be om det én gang, og bruk nett imens.
        if (!onDevice && device != null && Build.VERSION.SDK_INT >= 33) {
            String downloadable = match(deviceSupported, lang);
            if (downloadable != null && downloadAsked.add(lang)) {
                try { device.triggerModelDownload(intentFor(downloadable, false)); } catch (Exception ignored) { }
            }
        }
        if (!onDevice && online == null) {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                state("unavailable");
                return;
            }
            online = SpeechRecognizer.createSpeechRecognizer(this);
        }
        active = onDevice ? device : online;
        segmented = continuous;
        base = "";
        lastHeard = "";
        try {
            active.setRecognitionListener(new Session(generation));
            active.startListening(intentFor(onDevice ? tag : lang, continuous));
            sessionActive = true;
            state("listening");
            js("window.nativeSpeechInfo && window.nativeSpeechInfo('" + (continuous ? "segmented" : onDevice ? "device" : "online") + "','" + lang + "')");
        } catch (Exception e) {
            state("error");
        }
    }

    private void restart(long delayMs) {
        sessionActive = false;
        if (!wanted) return;
        final int gen = generation;
        main.postDelayed(() -> {
            if (wanted && gen == generation) listen();
        }, delayMs);
    }

    /** Fjerner det som var hørt før siste «reset» fra starten av teksten. */
    private String fresh(String text) {
        if (!base.isEmpty() && text.startsWith(base)) return text.substring(base.length()).trim();
        return text;
    }

    private void emit(String text, boolean isFinal, boolean alt) {
        js("window.nativeSpeech && window.nativeSpeech(" + JSONObject.quote(text) + "," + isFinal + "," + alt + ")");
    }

    /** Lytter for én økt. Tilbakekall fra gamle økter blir ignorert. */
    private class Session implements RecognitionListener {
        private final int gen;

        Session(int gen) {
            this.gen = gen;
        }

        private boolean stale() {
            return gen != generation;
        }

        private void deliver(Bundle b, boolean isFinal) {
            ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list == null || list.isEmpty()) return;
            if (!isFinal) {
                String text = list.get(0);
                if (text.isEmpty()) return;
                lastHeard = text;
                String now = fresh(text);
                if (!now.isEmpty()) emit(now, false, false);
                return;
            }
            for (int k = 1; k < list.size(); k++) {
                String alt = fresh(list.get(k));
                if (!alt.isEmpty()) emit(alt, false, true);
            }
            String best = fresh(list.get(0));
            if (!best.isEmpty()) emit(best, true, false);
        }

        @Override
        public void onPartialResults(Bundle b) {
            if (!stale()) deliver(b, false);
        }

        @Override
        public void onResults(Bundle b) {
            if (stale()) return;
            deliver(b, true);
            restart(0);   // økten er over: start neste med en gang
        }

        @Override
        public void onSegmentResults(Bundle b) {
            if (stale()) return;
            deliver(b, true);
            base = "";        // neste bit starter med blanke ark
            lastHeard = "";
        }

        @Override
        public void onEndOfSegmentedSession() {
            if (!stale()) restart(0);
        }

        @Override
        public void onError(int error) {
            if (stale()) return;
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                wanted = false;
                state("denied");
                return;
            }
            // Språket fantes likevel ikke på telefonen: bruk nett for dette språket.
            if (active == device && (error == 12 || error == 13)) {
                String tag = match(deviceLangs, languages[0]);
                if (tag != null) deviceLangs.remove(tag);
            }
            boolean quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
            restart(quiet ? 0 : 250);
        }

        @Override public void onReadyForSpeech(Bundle params) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int eventType, Bundle params) { }
    }
}
