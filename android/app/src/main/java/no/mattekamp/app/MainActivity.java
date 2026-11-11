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
import java.util.Locale;

/**
 * Viser spillet (mattekamp.html) i en WebView og kobler det til Androids
 * egen talegjenkjenning og opplesing på norsk.
 *
 * Siden kaller AndroidBridge.speech("start" | "stop" | "reset") og AndroidBridge.tts(tekst).
 * Appen svarer med window.nativeSpeech(tekst, ferdig, alternativ),
 * window.nativeSpeechState(status) og window.nativeTTSDone().
 */
public class MainActivity extends Activity implements RecognitionListener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean wanted = false;
    private int utterance = 0;
    /** Antall ord i den pågående lytteøkten som spillet har bedt oss glemme («reset»). */
    private int skipWords = 0;
    private String lastHeard = "";
    private boolean sessionActive = false;
    /** Språkene spilleren som har tur svarer på. Det første er hovedspråket. */
    private String[] languages = {"nb-NO"};

    /** Ord gjenkjenneren bør være ekstra oppmerksom på (Android 13+). */
    private static final String[] BIAS = {
            "null", "en", "to", "tre", "fire", "fem", "seks", "sju", "syv", "åtte", "ni", "ti", "elleve", "tolv",
            "tretten", "fjorten", "femten", "seksten", "sytten", "atten", "nitten", "tjue", "tretti", "førti",
            "femti", "seksti", "sytti", "åtti", "nitti", "hundre", "tusen", "minus", "pass", "gjenta"};

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
        if (recognizer != null) recognizer.cancel();
        if (tts != null) tts.stop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wanted && recognizer != null) listen();
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) recognizer.destroy();
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
        if (command.startsWith("lang:")) {
            String[] next = command.substring(5).split(",");
            if (next.length == 0 || next[0].isEmpty() || Arrays.equals(next, languages)) return;
            languages = next;
            // Nytt språk krever en ny lytteøkt.
            if (wanted && recognizer != null) {
                recognizer.cancel();
                listen();
            }
            return;
        }
        switch (command) {
            case "start":
                wanted = true;
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
                } else {
                    begin();
                }
                break;
            case "stop":
                wanted = false;
                sessionActive = false;
                if (recognizer != null) recognizer.cancel();
                state("idle");
                break;
            case "reset":
                // Ikke start lyttingen på nytt: det gir et hull på et halvt sekund der svar går tapt.
                // Vi hopper heller over ordene som allerede er hørt i denne økten.
                if (sessionActive) skipWords = countWords(lastHeard);
                else if (wanted && recognizer != null) listen();
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
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            state("unavailable");
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(this);
        }
        listen();
    }

    private void listen() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, languages[0]);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languages[0]);
        if (languages.length > 1) {
            // Ekstra språk for spillere med flere språk. Ikke alle telefoner støtter dette.
            i.putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", Arrays.copyOfRange(languages, 1, languages.length));
        }
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        // Hold økten åpen lenger, så den ikke avsluttes midt mellom to svar.
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 4000L);
        if (Build.VERSION.SDK_INT >= 33 && languages[0].startsWith("nb")) {
            i.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, new ArrayList<>(Arrays.asList(BIAS)));
        }
        skipWords = 0;
        lastHeard = "";
        try {
            recognizer.startListening(i);
            sessionActive = true;
            state("listening");
        } catch (Exception e) {
            state("error");
        }
    }

    /** Androids gjenkjenner stopper etter hver setning. Start den på nytt så lenge spillet lytter. */
    private void restart(long delayMs, boolean cancelFirst) {
        sessionActive = false;
        if (!wanted) return;
        main.postDelayed(() -> {
            if (wanted && recognizer != null && !sessionActive) {
                if (cancelFirst) recognizer.cancel();
                listen();
            }
        }, delayMs);
    }

    private static int countWords(String text) {
        String t = text.trim();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }

    /** Fjerner ordene spillet har bedt oss glemme fra starten av teksten. */
    private String afterSkip(String text) {
        if (skipWords <= 0) return text;
        String[] words = text.trim().split("\\s+");
        if (words.length <= skipWords) return "";
        StringBuilder sb = new StringBuilder();
        for (int k = skipWords; k < words.length; k++) sb.append(words[k]).append(' ');
        return sb.toString().trim();
    }

    private void emit(String text, boolean isFinal, boolean alt) {
        js("window.nativeSpeech && window.nativeSpeech(" + JSONObject.quote(text) + "," + isFinal + "," + alt + ")");
    }

    @Override
    public void onPartialResults(Bundle b) {
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String text = list != null && !list.isEmpty() ? list.get(0) : "";
        // Google sender ofte de ferskeste ordene som «ustabil tekst». Ta dem med, så svaret oppfattes raskere.
        ArrayList<String> unstable = b.getStringArrayList("android.speech.extra.UNSTABLE_TEXT");
        if (unstable != null && !unstable.isEmpty() && !unstable.get(0).isEmpty()) text = (text + " " + unstable.get(0)).trim();
        if (text.isEmpty()) return;
        lastHeard = text;
        String fresh = afterSkip(text);
        if (!fresh.isEmpty()) emit(fresh, false, false);
    }

    @Override
    public void onResults(Bundle b) {
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list != null && !list.isEmpty()) {
            for (int k = 1; k < list.size(); k++) {
                String alt = afterSkip(list.get(k));
                if (!alt.isEmpty()) emit(alt, false, true);
            }
            String best = afterSkip(list.get(0));
            if (!best.isEmpty()) emit(best, true, false);
        }
        restart(0, false);   // start neste økt med en gang
    }

    @Override
    public void onError(int error) {
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            wanted = false;
            state("denied");
            return;
        }
        // Stillhet eller «fant ingenting»: lytt videre med en gang. Andre feil trenger en liten pause.
        boolean quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
        restart(quiet ? 0 : 250, !quiet);
    }

    @Override public void onReadyForSpeech(Bundle params) { }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { }
    @Override public void onEvent(int eventType, Bundle params) { }
}
