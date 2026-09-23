package org.telegram.messenger.ayu.ai;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.ayu.AyuConfig;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

/**
 * Ox-gram "AI Tools": a tiny LLM client used by the message context menu.
 * <p>
 * Two providers are supported:
 * <ul>
 *     <li>{@link AyuConfig#AI_PROVIDER_CLAUDE} - the Claude Messages API
 *         (<code>POST /v1/messages</code>, <code>x-api-key</code> + <code>anthropic-version</code>).</li>
 *     <li>{@link AyuConfig#AI_PROVIDER_OPENAI} - any OpenAI-compatible endpoint
 *         (<code>POST /chat/completions</code>, <code>Authorization: Bearer</code>).</li>
 * </ul>
 * Every request runs on a background queue, the callback is always delivered on the UI thread.
 * The API key is never logged.
 */
public class AyuAiClient {

    /** Claude Messages API version header value */
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int CONNECT_TIMEOUT = 20_000;
    private static final int READ_TIMEOUT = 120_000;
    private static final int MAX_TOKENS = 2048;

    public interface Callback {
        void onResult(String text);

        void onError(String error);
    }

    private AyuAiClient() {
    }

    /** whether the feature is switched on and an API key is present */
    public static boolean isConfigured() {
        AyuConfig.load();
        return AyuConfig.aiToolsEnabled && !TextUtils.isEmpty(AyuConfig.aiApiKey);
    }

    /**
     * Sends a single-turn request. Never throws; failures are reported through
     * {@link Callback#onError(String)} on the UI thread.
     */
    public static void request(String systemPrompt, String userPrompt, Callback callback) {
        AyuConfig.load();
        final int provider = AyuConfig.aiProvider;
        final String apiKey = AyuConfig.aiApiKey;
        final String model = AyuConfig.getAiModel();
        final String baseUrl = AyuConfig.getAiBaseUrl();
        if (TextUtils.isEmpty(apiKey)) {
            deliverError(callback, "no api key");
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String result = provider == AyuConfig.AI_PROVIDER_OPENAI
                        ? requestOpenAi(baseUrl, apiKey, model, systemPrompt, userPrompt)
                        : requestClaude(baseUrl, apiKey, model, systemPrompt, userPrompt);
                if (TextUtils.isEmpty(result)) {
                    deliverError(callback, "empty response");
                } else {
                    final String finalResult = result;
                    AndroidUtilities.runOnUIThread(() -> {
                        if (callback != null) {
                            callback.onResult(finalResult);
                        }
                    });
                }
            } catch (Throwable e) {
                // never print the key: only the message of the exception is forwarded
                deliverError(callback, describe(e));
            }
        });
    }

    private static void deliverError(Callback callback, String error) {
        AndroidUtilities.runOnUIThread(() -> {
            if (callback != null) {
                callback.onError(error);
            }
        });
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        if (TextUtils.isEmpty(message)) {
            message = e.getClass().getSimpleName();
        }
        return message;
    }

    // ---------------------------------------------------------------- Claude

    private static String requestClaude(String baseUrl, String apiKey, String model,
                                        String systemPrompt, String userPrompt) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("max_tokens", MAX_TOKENS);
        if (!TextUtils.isEmpty(systemPrompt)) {
            body.put("system", systemPrompt);
        }
        JSONArray messages = new JSONArray();
        JSONObject message = new JSONObject();
        message.put("role", "user");
        message.put("content", userPrompt);
        messages.put(message);
        body.put("messages", messages);

        HttpURLConnection connection = open(join(baseUrl, "/v1/messages"));
        connection.setRequestProperty("x-api-key", apiKey);
        connection.setRequestProperty("anthropic-version", ANTHROPIC_VERSION);
        String response = exchange(connection, body.toString());

        JSONObject json = new JSONObject(response);
        JSONArray content = json.optJSONArray("content");
        if (content == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int a = 0; a < content.length(); a++) {
            JSONObject block = content.optJSONObject(a);
            if (block == null || !"text".equals(block.optString("type"))) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(block.optString("text"));
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------- OpenAI-compatible

    private static String requestOpenAi(String baseUrl, String apiKey, String model,
                                        String systemPrompt, String userPrompt) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("max_tokens", MAX_TOKENS);
        JSONArray messages = new JSONArray();
        if (!TextUtils.isEmpty(systemPrompt)) {
            JSONObject system = new JSONObject();
            system.put("role", "system");
            system.put("content", systemPrompt);
            messages.put(system);
        }
        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", userPrompt);
        messages.put(user);
        body.put("messages", messages);

        HttpURLConnection connection = open(join(baseUrl, "/chat/completions"));
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        String response = exchange(connection, body.toString());

        JSONObject json = new JSONObject(response);
        JSONArray choices = json.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            return null;
        }
        JSONObject first = choices.optJSONObject(0);
        if (first == null) {
            return null;
        }
        JSONObject message = first.optJSONObject("message");
        if (message == null) {
            return null;
        }
        return message.optString("content").trim();
    }

    // ---------------------------------------------------------------- plumbing

    private static String join(String baseUrl, String path) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith(path)) {
            return base;
        }
        return base + path;
    }

    private static HttpURLConnection open(String url) throws Exception {
        URL parsed = new URL(url);
        HttpURLConnection connection = (HttpURLConnection) parsed.openConnection();
        if (!(connection instanceof HttpsURLConnection) && !"http".equals(parsed.getProtocol())) {
            throw new IllegalArgumentException("unsupported url");
        }
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        connection.setDoOutput(true);
        connection.setDoInput(true);
        connection.setRequestProperty("content-type", "application/json");
        connection.setRequestProperty("accept", "application/json");
        return connection;
    }

    private static String exchange(HttpURLConnection connection, String body) throws Exception {
        try {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
                out.flush();
            }
            int code = connection.getResponseCode();
            String response = read(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream());
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + (TextUtils.isEmpty(response) ? "" : ": " + extractError(response)));
            }
            return response;
        } finally {
            try {
                connection.disconnect();
            } catch (Throwable ignore) {
            }
        }
    }

    private static String extractError(String response) {
        try {
            JSONObject json = new JSONObject(response);
            JSONObject error = json.optJSONObject("error");
            if (error != null) {
                String message = error.optString("message");
                if (!TextUtils.isEmpty(message)) {
                    return message;
                }
            }
        } catch (Throwable ignore) {
        }
        return response.length() > 200 ? response.substring(0, 200) : response;
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, read);
            }
        }
        return sb.toString();
    }
}
