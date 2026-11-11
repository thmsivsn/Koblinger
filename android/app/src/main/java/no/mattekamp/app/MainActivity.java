package no.mattekamp.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
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

    private void speak(String text) {
        if (tts == null || !ttsReady) {
            js("window.nativeTTSDone && window.nativeTTSDone()");
            return;
        }
        tts.stop();
        if (text == null || text.isEmpty()) return;
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + (++utterance));
    }

    // ---------- Talegjenkjenning ----------

    private void handleSpeech(String command) {
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
                if (recognizer != null) recognizer.cancel();
                state("idle");
                break;
            case "reset":
                if (wanted && recognizer != null) {
                    recognizer.cancel();
                    listen();
                }
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
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "nb-NO");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "nb-NO");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        try {
            recognizer.startListening(i);
            state("listening");
        } catch (Exception e) {
            state("error");
        }
    }

    /** Androids gjenkjenner stopper etter hver setning. Start den på nytt så lenge spillet lytter. */
    private void restart() {
        if (!wanted) return;
        main.postDelayed(() -> {
            if (wanted && recognizer != null) {
                recognizer.cancel();
                listen();
            }
        }, 150);
    }

    private void emit(String text, boolean isFinal, boolean alt) {
        js("window.nativeSpeech && window.nativeSpeech(" + JSONObject.quote(text) + "," + isFinal + "," + alt + ")");
    }

    @Override
    public void onPartialResults(Bundle b) {
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list != null && !list.isEmpty() && !list.get(0).isEmpty()) emit(list.get(0), false, false);
    }

    @Override
    public void onResults(Bundle b) {
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list != null && !list.isEmpty()) {
            for (int k = 1; k < list.size(); k++) emit(list.get(k), false, true);
            emit(list.get(0), true, false);
        }
        restart();
    }

    @Override
    public void onError(int error) {
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            wanted = false;
            state("denied");
            return;
        }
        restart();
    }

    @Override public void onReadyForSpeech(Bundle params) { }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { }
    @Override public void onEvent(int eventType, Bundle params) { }
}
