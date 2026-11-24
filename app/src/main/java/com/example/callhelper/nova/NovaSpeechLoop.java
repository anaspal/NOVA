package com.example.callhelper.nova;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;

/**
 * حلقة تعرف صوتي مستمرة نسبياً: تستمع محلياً متى توفر محرك on-device،
 * وتعيد فتح جلسة قصيرة بعد كل نتيجة. لا تنفذ الأوامر في هذه المرحلة.
 */
public final class NovaSpeechLoop implements RecognitionListener {
    public interface Listener {
        void onWakeWord();
        void onCommand(NovaCommand command);
        void onStatus(String status);
    }

    private static final String TAG = "NovaSpeechLoop";
    private final Context appContext;
    private final Listener listener;
    private SpeechRecognizer recognizer;
    private boolean running;
    private boolean waitingForCommand;
    private boolean restarting;
    private boolean paused;
    private long conversationUntilMs;
    private static final long CONVERSATION_WINDOW_MS = 12000L;

    public NovaSpeechLoop(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    public static boolean isAvailable(Context context) {
        return SpeechRecognizer.isRecognitionAvailable(context);
    }

    public void start() {
        if (running) return;
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            listener.onStatus("لا يوجد محرك تعرف على الكلام مثبت على الجهاز");
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)) {
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext);
                listener.onStatus("التعرف الصوتي المحلي متاح");
            } else {
                recognizer = SpeechRecognizer.createSpeechRecognizer(appContext);
                listener.onStatus("محرك الجهاز لا يوفر التعرف المحلي؛ سيُطلب الوضع دون اتصال إن كان مدعوماً");
            }
            recognizer.setRecognitionListener(this);
            running = true;
            startSession();
        } catch (Exception e) {
            Log.e(TAG, "Unable to start speech recognizer", e);
            listener.onStatus("تعذر تشغيل التعرف الصوتي: " + e.getMessage());
            stop();
        }
    }

    private void startSession() {
        if (!running || recognizer == null || restarting) return;
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar");
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900);
            recognizer.startListening(intent);
        } catch (Exception e) {
            Log.w(TAG, "startListening failed", e);
            scheduleRestart();
        }
    }

    private void scheduleRestart() {
        if (!running || restarting) return;
        restarting = true;
        // SpeechRecognizer callbacks arrive on the main thread; defer without a tight retry loop.
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            restarting = false;
            startSession();
        }, 700);
    }

    private void handleText(String text) {
        if (text == null || text.trim().isEmpty()) return;
        boolean wake = NovaCommandParser.containsWakeWord(text);
        long now = android.os.SystemClock.elapsedRealtime();
        if (wake) {
            String afterWake = NovaCommandParser.removeWakeWord(text);
            conversationUntilMs = now + CONVERSATION_WINDOW_MS;
            if (paused) {
                if (afterWake.isEmpty()) {
                    waitingForCommand = true;
                    conversationUntilMs = now + CONVERSATION_WINDOW_MS;
                    listener.onWakeWord();
                    return;
                }
                NovaCommand resume = NovaCommandParser.parse(text);
                if (resume.type == NovaCommand.Type.RESUME_LISTENING) {
                    paused = false;
                    waitingForCommand = false;
                    conversationUntilMs = 0L;
                    listener.onCommand(resume);
                } else {
                    waitingForCommand = false;
                    conversationUntilMs = 0L;
                    listener.onStatus("الاستماع متوقف للأوامر؛ قل: نوفا استأنفي الاستماع");
                }
                return;
            }
            if (afterWake.isEmpty()) {
                waitingForCommand = true;
                listener.onWakeWord();
                return;
            }
            waitingForCommand = true;
            listener.onCommand(NovaCommandParser.parse(text));
            return;
        }
        // أثناء وضع التوقف نقبل كلمة الاستئناف فقط، ولا ننفذ أي أمر آخر.
        if (paused && waitingForCommand && now <= conversationUntilMs) {
            NovaCommand resume = NovaCommandParser.parse(text);
            waitingForCommand = false;
            conversationUntilMs = 0L;
            if (resume.type == NovaCommand.Type.RESUME_LISTENING) {
                paused = false;
                listener.onCommand(resume);
            } else {
                listener.onStatus("لم أستأنف الاستماع؛ قل: استأنفي الاستماع");
            }
            return;
        }
        // بعد نداء NOVA، تقبل الأوامر التالية لفترة قصيرة دون تكرار كلمة التنبيه.
        if (waitingForCommand && now <= conversationUntilMs) {
            conversationUntilMs = now + CONVERSATION_WINDOW_MS;
            NovaCommand command = NovaCommandParser.parse(text);
            if (!paused) listener.onCommand(command);
            return;
        }
        waitingForCommand = false;
        conversationUntilMs = 0L;
    }

    /** يوقف تنفيذ الأوامر مع إبقاء جلسة الاستماع لكلمة الاستئناف فقط. */
    public void pauseCommands() {
        paused = true;
        waitingForCommand = false;
        conversationUntilMs = 0L;
    }

    public void resumeCommands() {
        paused = false;
        waitingForCommand = false;
        conversationUntilMs = 0L;
    }

    public boolean isPaused() { return paused; }

    public void stop() {
        running = false;
        paused = false;
        waitingForCommand = false;
        conversationUntilMs = 0L;
        restarting = false;
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
            try { recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
    }

    @Override public void onReadyForSpeech(Bundle params) { }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { }
    @Override public void onError(int error) {
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            listener.onStatus("إذن الميكروفون غير متاح");
            stop();
            return;
        }
        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
        }
        if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            Log.d(TAG, "recognizer error=" + error);
        scheduleRestart();
    }
    @Override public void onResults(Bundle results) {
        ArrayList<String> matches = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null && !matches.isEmpty()) handleText(matches.get(0));
        scheduleRestart();
    }
    @Override public void onPartialResults(Bundle partialResults) { }
    @Override public void onEvent(int eventType, Bundle params) { }
}
