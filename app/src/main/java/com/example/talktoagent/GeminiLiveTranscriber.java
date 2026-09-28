package com.example.talktoagent;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.json.JSONException;
import org.json.JSONObject;

/** A single press-only Gemini 3.5 Transcribe Live SMART session. Never forwards audio to Receiver. */
final class GeminiLiveTranscriber {
    interface Listener {
        void onInterim(String text);
        void onFinal(String text);
        void onError(String reason);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient client = new OkHttpClient();
    private final Listener listener;
    private final AtomicBoolean recording = new AtomicBoolean(true);
    private volatile WebSocket socket;
    private volatile AudioRecord recorder;
    private volatile boolean released;
    private volatile boolean endSent;
    private volatile boolean finished;
    private final FinalTranscript transcript = new FinalTranscript();
    private final Runnable timeout = () -> {
        // At the deadline, the quiet window can expire at exactly the same time.
        String text = transcript.takeIfSettled(SystemClock.uptimeMillis());
        if (text != null) finishFinal(text);
        else finishError("等待 Gemini 轉錄定稿逾時，未傳送");
    };
    private final Runnable deliverSettled = () -> {
        String text = transcript.takeIfSettled(SystemClock.uptimeMillis());
        if (text != null && !finished) finishFinal(text);
    };
    private final Runnable captureLimit = () -> finishError("本次收音超過 45 秒，未傳送；請重新驗證 Receiver");

    GeminiLiveTranscriber(String key, Listener listener) {
        this.listener = listener;
        // This URL is never logged; a user-supplied key is only used in the phone's TLS connection.
        String url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key="
                + android.net.Uri.encode(key);
        socket = client.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                webSocket.send("{\"setup\":{\"model\":\"models/gemini-3.5-transcribe-live\","
                        + "\"generationConfig\":{\"responseModalities\":[\"TEXT\"]},"
                        + "\"realtimeInputConfig\":{\"automaticActivityDetection\":{\"disabled\":true}},"
                        + "\"inputAudioTranscription\":{\"mode\":\"SMART\",\"languageCodes\":[]}}}");
            }
            @Override public void onMessage(WebSocket webSocket, String text) {
                main.post(() -> receive(text));
            }
            @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                main.post(() -> finishError("Gemini 連線或辨識失敗，未傳送（請檢查金鑰與網路）"));
            }
            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                main.post(() -> finishError("Gemini 連線已關閉，未取得定稿"));
            }
        });
        main.postDelayed(() -> {
            if (!finished && recorder == null && !released) finishError("Gemini 連線等待逾時，未收音");
        }, 10000);
        main.postDelayed(captureLimit, 45000);
    }

    private void receive(String text) {
        if (finished) return;
        try {
            JSONObject response = new JSONObject(text);
            if (response.has("setupComplete")) {
                if (!released) startAudio();
                return;
            }
            JSONObject content = response.optJSONObject("serverContent");
            if (content == null) return;
            JSONObject interim = content.optJSONObject("interimInputTranscription");
            if (interim != null && !released) listener.onInterim(interim.optString("text", ""));
            JSONObject actual = content.optJSONObject("inputTranscription");
            if (actual != null) {
                if (transcript.addFinal(actual.optString("text", ""), SystemClock.uptimeMillis())
                        && released && endSent) scheduleSettledDelivery();
            }
        } catch (JSONException ignored) {
            finishError("Gemini 回覆格式錯誤，未傳送");
        }
    }

    private void startAudio() {
        int min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) { finishError("麥克風格式不支援，未收音"); return; }
        try {
            AudioRecord audio = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 3200));
            if (audio.getState() != AudioRecord.STATE_INITIALIZED) {
                audio.release(); finishError("麥克風無法啟動，未收音"); return;
            }
            recorder = audio;
            audio.startRecording();
            socket.send("{\"realtimeInput\":{\"activityStart\":{}}}");
            Thread worker = new Thread(() -> {
                byte[] chunk = new byte[3200]; // 100ms of 16kHz, mono, 16-bit PCM.
                try {
                    while (recording.get()) {
                        int count = audio.read(chunk, 0, chunk.length);
                        if (!recording.get() || finished) break;
                        if (count <= 0) { main.post(() -> finishError("麥克風讀取失敗，未傳送")); break; }
                        byte[] exact = java.util.Arrays.copyOf(chunk, count);
                        String payload = Base64.encodeToString(exact, Base64.NO_WRAP);
                        if (!socket.send("{\"realtimeInput\":{\"audio\":{\"data\":\"" + payload
                                + "\",\"mimeType\":\"audio/pcm;rate=16000\"}}}")) {
                            main.post(() -> finishError("Gemini 音訊傳送失敗，未傳送")); break;
                        }
                    }
                } finally {
                    try { audio.stop(); } catch (IllegalStateException ignored) { /* already stopped */ }
                    audio.release();
                    recorder = null;
                    if (released && !finished) {
                        if (!socket.send("{\"realtimeInput\":{\"activityEnd\":{}}}")) {
                            main.post(() -> finishError("Gemini 收尾失敗，未傳送"));
                        } else {
                            main.post(() -> {
                                if (finished) return;
                                endSent = true;
                                transcript.streamEnded(SystemClock.uptimeMillis());
                                scheduleSettledDelivery();
                            });
                        }
                    }
                }
            }, "Gemini microphone");
            worker.start();
        } catch (SecurityException | IllegalStateException exception) {
            AudioRecord current = recorder;
            recorder = null;
            if (current != null) current.release();
            finishError("麥克風無法啟動或權限遭拒，未收音");
        }
    }

    void release() {
        if (finished || released) return;
        released = true;
        transcript.release(SystemClock.uptimeMillis());
        recording.set(false);
        if (recorder == null) {
            // Setup may still be pending; do not start capturing after the button was released.
            finishError("放開前 Gemini 尚未開始收音，未傳送");
            return;
        }
        main.removeCallbacks(captureLimit);
        main.postDelayed(timeout, 8000);
    }

    private void scheduleSettledDelivery() {
        main.removeCallbacks(deliverSettled);
        // The API doesn't guarantee inputTranscription ordering relative to turnComplete.
        // Wait for a short quiet window after the last real FINAL, not the first post-release event.
        main.postDelayed(deliverSettled, FinalTranscript.QUIET_PERIOD_MS);
    }

    private void finishFinal(String text) {
        if (finished) return;
        finished = true;
        main.removeCallbacks(timeout);
        main.removeCallbacks(captureLimit);
        main.removeCallbacks(deliverSettled);
        listener.onFinal(text);
        close();
    }

    private void finishError(String reason) {
        if (finished) return;
        finished = true;
        main.removeCallbacks(timeout);
        main.removeCallbacks(captureLimit);
        main.removeCallbacks(deliverSettled);
        listener.onError(reason);
        close();
    }

    void close() {
        finished = true;
        recording.set(false);
        main.removeCallbacks(timeout);
        main.removeCallbacks(captureLimit);
        main.removeCallbacks(deliverSettled);
        if (socket != null) socket.cancel();
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
    }
}
