package com.orb.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final Locale RU = new Locale("ru", "RU");
    private static final int REQ_MIC = 7;

    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean russianOk = false;
    private boolean installAsked = false;
    private SpeechRecognizer recognizer;
    private boolean pendingListen = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(web);

        WebSettings st = web.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);              // чтобы ключ API сохранялся
        st.setAllowFileAccess(true);
        st.setAllowUniversalAccessFromFileURLs(true); // запросы к API из локальной страницы
        st.setMediaPlaybackRequiresUserGesture(false);

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.grant(request.getResources());
            }
        });
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.loadUrl("file:///android_asset/index.html");

        initTts();
    }

    /* ---------- озвучка ---------- */

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) {
                ttsReady = false;
                js("window.refreshVoices && refreshVoices()");
                return;
            }
            ttsReady = true;
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { js("onNativeTts('" + id + "','start')"); }
                @Override public void onDone(String id) { js("onNativeTts('" + id + "','done')"); }
                @Override public void onError(String id) { js("onNativeTts('" + id + "','error')"); }
                @Override public void onError(String id, int code) { js("onNativeTts('" + id + "','error:" + code + "')"); }
            });
            checkRussian(true);
        });
    }

    /** Проверяет русский голос. Если его нет, один раз открывает экран загрузки голоса. */
    private void checkRussian(boolean askInstall) {
        if (!ttsReady) return;
        int r = tts.isLanguageAvailable(RU);
        russianOk = r >= TextToSpeech.LANG_AVAILABLE;
        if (russianOk) {
            tts.setLanguage(RU);
        } else if (askInstall && !installAsked) {
            installAsked = true;
            installVoice();
        }
        js("window.refreshVoices && refreshVoices()");
    }

    private void installVoice() {
        try {
            startActivity(new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Toast.makeText(this, "Скачайте русский голос и вернитесь в приложение", Toast.LENGTH_LONG).show();
            return;
        } catch (ActivityNotFoundException ignored) { }
        try {
            startActivity(new Intent("com.android.settings.TTS_SETTINGS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Toast.makeText(this, "Выберите «Синтез речи Google» и установите русский голос", Toast.LENGTH_LONG).show();
            return;
        } catch (ActivityNotFoundException ignored) { }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=com.google.android.tts")));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Установите «Синтез речи Google» из магазина приложений", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ttsReady) checkRussian(false); // вернулись с экрана загрузки голоса
    }

    /* ---------- распознавание речи ---------- */

    private void startListening() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingListen = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            js("onNativeStt('error','100')");
            return;
        }
        destroyRecognizer();
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float v) { }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onEvent(int i, Bundle b) { }
            @Override public void onError(int code) { js("onNativeStt('error','" + code + "')"); }
            @Override public void onPartialResults(Bundle b) { send("partial", b); }
            @Override public void onResults(Bundle b) { send("final", b); }
            private void send(String type, Bundle b) {
                ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list == null || list.isEmpty()) return;
                js("onNativeStt('" + type + "'," + JSONObject.quote(list.get(0)) + ")");
            }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizer.startListening(i);
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != REQ_MIC) return;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
            if (pendingListen) { pendingListen = false; startListening(); }
        } else {
            pendingListen = false;
            js("onNativeStt('error','9')");
        }
    }

    /* ---------- мост JS <-> Android ---------- */

    private void js(String code) {
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(code, null); });
    }

    private class Bridge {
        @JavascriptInterface
        public boolean hasRussian() { return russianOk; }

        @JavascriptInterface
        public void speak(String text, float pitch, float rate, String id) {
            if (!ttsReady || !russianOk) {
                js("onNativeTts('" + id + "','error:no-voice')");
                runOnUiThread(() -> { if (!russianOk) installVoice(); });
                return;
            }
            tts.setPitch(pitch);
            tts.setSpeechRate(rate);
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, id);
        }

        @JavascriptInterface
        public void stop() { if (ttsReady) tts.stop(); }

        @JavascriptInterface
        public void installVoice() { runOnUiThread(MainActivity.this::installVoice); }

        @JavascriptInterface
        public void listen() { runOnUiThread(MainActivity.this::startListening); }

        @JavascriptInterface
        public void stopListen() {
            runOnUiThread(() -> { if (recognizer != null) recognizer.stopListening(); });
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        destroyRecognizer();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }
}
