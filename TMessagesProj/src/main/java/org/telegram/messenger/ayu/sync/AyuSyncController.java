package org.telegram.messenger.ayu.sync;

import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.support.LongSparseIntArray;
import org.telegram.messenger.ayu.AyuConfig;
import org.telegram.messenger.ayu.AyuMessagesController;
import org.telegram.messenger.ayu.entities.EditedMessage;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;


/**
 * AyuSync client.
 * <p>
 * Upstream AyuGram talks to its AyuSync server over a WebSocket. Adding a WebSocket library here
 * would mean a new Gradle dependency, so this is a self-contained HTTP implementation of the very
 * same event model built on {@link HttpURLConnection}. The wire protocol is documented in
 * {@code README_AYUSYNC.md} next to this class.
 * <p>
 * Everything network related runs on a single background {@link DispatchQueue}; the only work done
 * on the UI thread is registering the {@link NotificationCenter} observers that feed the outgoing
 * event queue, and dispatching status changes to {@link AyuSyncListener}s.
 */
public class AyuSyncController implements NotificationCenter.NotificationCenterDelegate {

    // ---------------------------------------------------------------- public API

    public enum Status {
        DISABLED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    /** Deliberately NotificationCenter-free so the settings screen can observe without an account. */
    public interface AyuSyncListener {
        void onAyuSyncStatusChanged(Status status, String lastError);
    }

    /** Callback for the one-shot "register device" request of the settings screen. */
    public interface RegisterCallback {
        void onResult(boolean success, String tokenOrError);
    }

    private static volatile AyuSyncController instance;

    public static AyuSyncController getInstance() {
        if (instance == null) {
            synchronized (AyuSyncController.class) {
                if (instance == null) {
                    instance = new AyuSyncController();
                }
            }
        }
        return instance;
    }

    // ---------------------------------------------------------------- constants

    private static final int POLL_INTERVAL_MS = 10_000;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 35_000;
    private static final long MIN_BACKOFF_MS = 5_000L;
    private static final long MAX_BACKOFF_MS = 300_000L;
    private static final int MAX_QUEUE_SIZE = 500;

    public static final String EVENT_READ = "read";
    public static final String EVENT_DELETED = "deleted";
    public static final String EVENT_EDITED = "edited";
    public static final String EVENT_GHOST = "ghost";

    // ---------------------------------------------------------------- state

    private DispatchQueue queue;
    private volatile boolean started;
    /** generation counter: every stop() invalidates the runnables of the previous run */
    private volatile int generation;

    private volatile Status status = Status.DISABLED;
    private volatile String lastError;

    private final ArrayList<AyuSyncListener> listeners = new ArrayList<>();
    private final ArrayList<JSONObject> outgoing = new ArrayList<>();

    /** server timestamp (unix seconds) of the newest event we already applied */
    private volatile long since;
    private int failures;
    private String deviceId;

    /**
     * Reads that arrived from the server and were applied locally. They are remembered for a short
     * while so that the {@link NotificationCenter#messagesRead} they trigger is not echoed back.
     */
    private final HashSet<String> appliedReads = new HashSet<>();

    private AyuSyncController() {
    }

    // ---------------------------------------------------------------- lifecycle

    /** Starts or stops the client to match {@link AyuConfig#syncEnabled}. Safe to call repeatedly. */
    public void checkState() {
        if (AyuConfig.syncEnabled) {
            start();
        } else {
            stop();
        }
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        if (!AyuConfig.syncEnabled) {
            return;
        }
        started = true;
        generation++;
        failures = 0;
        if (queue == null) {
            queue = new DispatchQueue("AyuSyncQueue");
        }
        setStatus(Status.CONNECTING, null);
        AndroidUtilities.runOnUIThread(this::attachObservers);
        final int gen = generation;
        queue.postRunnable(() -> loop(gen));
    }

    public synchronized void stop() {
        if (!started) {
            if (status != Status.DISABLED) {
                setStatus(Status.DISABLED, null);
            }
            return;
        }
        started = false;
        generation++;
        synchronized (outgoing) {
            outgoing.clear();
        }
        AndroidUtilities.runOnUIThread(this::detachObservers);
        setStatus(Status.DISABLED, null);
    }

    /** Runs one poll/flush iteration immediately instead of waiting for the next tick. */
    public void forceSync() {
        if (!started || queue == null) {
            return;
        }
        final int gen = generation;
        queue.postRunnable(() -> {
            if (gen != generation) {
                return;
            }
            iterate(gen);
        });
    }

    public Status getStatus() {
        return status;
    }

    public String getLastError() {
        return lastError;
    }

    public void addListener(AyuSyncListener listener) {
        if (listener == null) {
            return;
        }
        synchronized (listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        }
    }

    public void removeListener(AyuSyncListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    private void setStatus(Status newStatus, String error) {
        status = newStatus;
        lastError = error;
        final ArrayList<AyuSyncListener> copy;
        synchronized (listeners) {
            if (listeners.isEmpty()) {
                return;
            }
            copy = new ArrayList<>(listeners);
        }
        AndroidUtilities.runOnUIThread(() -> {
            for (int i = 0; i < copy.size(); i++) {
                try {
                    copy.get(i).onAyuSyncStatusChanged(newStatus, error);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        });
    }

    // ---------------------------------------------------------------- observers (event sources)

    private boolean observersAttached;

    private void attachObservers() {
        if (observersAttached) {
            return;
        }
        observersAttached = true;
        try {
            NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.ayuGhostModeChanged);
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                NotificationCenter.getInstance(a).addObserver(this, NotificationCenter.ayuMessageHistoryUpdated);
                NotificationCenter.getInstance(a).addObserver(this, NotificationCenter.messagesRead);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void detachObservers() {
        if (!observersAttached) {
            return;
        }
        observersAttached = false;
        try {
            NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.ayuGhostModeChanged);
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                NotificationCenter.getInstance(a).removeObserver(this, NotificationCenter.ayuMessageHistoryUpdated);
                NotificationCenter.getInstance(a).removeObserver(this, NotificationCenter.messagesRead);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (!started) {
            return;
        }
        try {
            if (id == NotificationCenter.ayuGhostModeChanged) {
                JSONObject event = new JSONObject();
                event.put("type", EVENT_GHOST);
                event.put("enabled", AyuConfig.isGhostModeActive());
                event.put("ts", now());
                enqueue(event);
            } else if (id == NotificationCenter.ayuMessageHistoryUpdated) {
                onHistoryUpdated(account, args);
            } else if (id == NotificationCenter.messagesRead) {
                onMessagesRead(account, args);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @SuppressWarnings("unchecked")
    private void onHistoryUpdated(int account, Object... args) throws Exception {
        if (args == null || args.length < 3) {
            return;
        }
        final long dialogId = (Long) args[0];
        final ArrayList<Integer> ids = (ArrayList<Integer>) args[1];
        final int type = (Integer) args[2];
        if (ids == null || ids.isEmpty()) {
            return;
        }
        final long ownerId = UserConfig.getInstance(account).getClientUserId();
        if (type == 0) {
            JSONObject event = new JSONObject();
            event.put("type", EVENT_DELETED);
            event.put("userId", ownerId);
            event.put("dialogId", dialogId);
            JSONArray arr = new JSONArray();
            for (int i = 0; i < ids.size(); i++) {
                arr.put((int) ids.get(i));
            }
            event.put("ids", arr);
            event.put("ts", now());
            enqueue(event);
        } else if (type == 1) {
            // one "edited" event per message; oldText is looked up from the local edit history
            for (int i = 0; i < ids.size(); i++) {
                final int messageId = ids.get(i);
                String oldText = null;
                try {
                    List<EditedMessage> revisions = AyuMessagesController.getInstance().getRevisions(account, dialogId, messageId);
                    if (revisions != null && !revisions.isEmpty()) {
                        oldText = revisions.get(revisions.size() - 1).text;
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                JSONObject event = new JSONObject();
                event.put("type", EVENT_EDITED);
                event.put("userId", ownerId);
                event.put("dialogId", dialogId);
                event.put("id", messageId);
                event.put("oldText", oldText == null ? "" : oldText);
                event.put("ts", now());
                enqueue(event);
            }
        }
    }

    private void onMessagesRead(int account, Object... args) throws Exception {
        if (args == null || args.length < 1 || !(args[0] instanceof LongSparseIntArray)) {
            return;
        }
        final LongSparseIntArray inbox = (LongSparseIntArray) args[0];
        if (inbox == null || inbox.size() == 0) {
            return;
        }
        final long ownerId = UserConfig.getInstance(account).getClientUserId();
        for (int i = 0; i < inbox.size(); i++) {
            final long dialogId = inbox.keyAt(i);
            final int maxId = inbox.valueAt(i);
            final String key = account + "_" + dialogId + "_" + maxId;
            synchronized (appliedReads) {
                if (appliedReads.remove(key)) {
                    continue; // this read came from the sync server, do not send it back
                }
            }
            JSONObject event = new JSONObject();
            event.put("type", EVENT_READ);
            event.put("userId", ownerId);
            event.put("dialogId", dialogId);
            event.put("topicId", 0);
            event.put("maxId", maxId);
            event.put("ts", now());
            enqueue(event);
        }
    }

    private void enqueue(JSONObject event) {
        synchronized (outgoing) {
            if (outgoing.size() >= MAX_QUEUE_SIZE) {
                outgoing.remove(0);
            }
            outgoing.add(event);
        }
    }

    // ---------------------------------------------------------------- background loop

    private void loop(int gen) {
        if (gen != generation || !started) {
            return;
        }
        iterate(gen);
        if (gen != generation || !started) {
            return;
        }
        long delay = POLL_INTERVAL_MS;
        if (failures > 0) {
            delay = Math.min(MAX_BACKOFF_MS, MIN_BACKOFF_MS * (1L << Math.min(failures - 1, 6)));
        }
        queue.postRunnable(() -> loop(gen), delay);
    }

    private void iterate(int gen) {
        if (gen != generation || !started) {
            return;
        }
        final String base = normalizedBaseUrl();
        if (TextUtils.isEmpty(base)) {
            failures++;
            setStatus(Status.ERROR, "Server URL is not set");
            return;
        }
        try {
            flushOutgoing(base);
            pollEvents(base);
            failures = 0;
            setStatus(Status.CONNECTED, null);
        } catch (Throwable e) {
            failures++;
            final String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            setStatus(Status.ERROR, message);
            if (BuildVars.LOGS_ENABLED) {
                FileLog.e("AyuSync: " + message);
            }
        }
    }

    private void flushOutgoing(String base) throws Exception {
        final ArrayList<JSONObject> batch;
        synchronized (outgoing) {
            if (outgoing.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(outgoing);
            outgoing.clear();
        }
        try {
            JSONObject body = new JSONObject();
            JSONArray arr = new JSONArray();
            for (int i = 0; i < batch.size(); i++) {
                arr.put(batch.get(i));
            }
            body.put("deviceId", getDeviceId());
            body.put("events", arr);
            request(base + "/v1/events", "POST", body.toString());
        } catch (Exception e) {
            // put the events back so they are retried on the next tick
            synchronized (outgoing) {
                batch.addAll(outgoing);
                outgoing.clear();
                if (batch.size() > MAX_QUEUE_SIZE) {
                    outgoing.addAll(batch.subList(batch.size() - MAX_QUEUE_SIZE, batch.size()));
                } else {
                    outgoing.addAll(batch);
                }
            }
            throw e;
        }
    }

    private void pollEvents(String base) throws Exception {
        final String response = request(base + "/v1/events?since=" + since, "GET", null);
        if (TextUtils.isEmpty(response)) {
            return;
        }
        JSONArray events;
        long newSince = since;
        try {
            JSONObject root = new JSONObject(response);
            newSince = root.optLong("now", since);
            events = root.optJSONArray("events");
        } catch (Throwable e) {
            // some deployments answer with a bare array
            try {
                events = new JSONArray(response);
            } catch (Throwable ignore) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("AyuSync: malformed events payload");
                }
                return;
            }
        }
        if (events == null) {
            since = Math.max(since, newSince);
            return;
        }
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event == null) {
                continue;
            }
            try {
                applyEvent(event);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            newSince = Math.max(newSince, event.optLong("ts", 0));
        }
        since = Math.max(since, newSince);
    }

    private void applyEvent(JSONObject event) {
        final String type = event.optString("type", "");
        if (EVENT_GHOST.equals(type)) {
            final boolean enabled = event.optBoolean("enabled", false);
            AndroidUtilities.runOnUIThread(() -> {
                if (AyuConfig.isGhostModeActive() != enabled) {
                    AyuConfig.setGhostMode(enabled);
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.ayuConfigChanged);
                }
            });
        } else if (EVENT_READ.equals(type)) {
            applyRead(event);
        }
        // "deleted" / "edited" are informational for other devices; the local history database is
        // authoritative here, so incoming ones are intentionally not written back.
    }

    /**
     * Applies a remote read receipt WITHOUT sending anything to Telegram: only the local dialog rows
     * are updated and the UI is notified, exactly like an incoming update would do.
     */
    private void applyRead(JSONObject event) {
        final long ownerId = event.optLong("userId", 0);
        final long dialogId = event.optLong("dialogId", 0);
        final int maxId = event.optInt("maxId", 0);
        if (dialogId == 0 || maxId <= 0) {
            return;
        }
        final int account = findAccount(ownerId);
        if (account < 0) {
            return;
        }
        final LongSparseIntArray inbox = new LongSparseIntArray();
        inbox.put(dialogId, maxId);
        synchronized (appliedReads) {
            if (appliedReads.size() > 256) {
                appliedReads.clear();
            }
            appliedReads.add(account + "_" + dialogId + "_" + maxId);
        }
        MessagesStorage.getInstance(account).updateDialogsWithReadMessages(inbox, null, null, null, true);
        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.messagesRead, inbox, null);
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
        });
    }

    private int findAccount(long ownerId) {
        if (ownerId == 0) {
            return UserConfig.selectedAccount;
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated() && UserConfig.getInstance(a).getClientUserId() == ownerId) {
                return a;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- device registration

    /**
     * POSTs {@code /v1/register} and, when the server answers with a token, stores it in
     * {@link AyuConfig#syncServerToken}. The callback is delivered on the UI thread.
     */
    public void registerDevice(RegisterCallback callback) {
        if (queue == null) {
            queue = new DispatchQueue("AyuSyncQueue");
        }
        queue.postRunnable(() -> {
            String resultToken = null;
            String error = null;
            try {
                final String base = normalizedBaseUrl();
                if (TextUtils.isEmpty(base)) {
                    throw new IllegalStateException("Server URL is not set");
                }
                JSONObject body = new JSONObject();
                body.put("deviceId", getDeviceId());
                body.put("app", "AyuGram-Android");
                final String response = request(base + "/v1/register", "POST", body.toString());
                if (!TextUtils.isEmpty(response)) {
                    JSONObject root = new JSONObject(response);
                    String token = root.optString("token", null);
                    if (TextUtils.isEmpty(token)) {
                        token = root.optString("accessToken", null);
                    }
                    if (!TextUtils.isEmpty(token)) {
                        resultToken = token;
                    }
                }
                if (resultToken == null) {
                    error = "Server returned no token";
                }
            } catch (Throwable e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                FileLog.e(e);
            }
            final String finalToken = resultToken;
            final String finalError = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (finalToken != null) {
                    AyuConfig.setSyncServerToken(finalToken);
                }
                if (callback != null) {
                    callback.onResult(finalToken != null, finalToken != null ? finalToken : finalError);
                }
            });
        });
    }

    // ---------------------------------------------------------------- http

    /** Normalizes {@link AyuConfig#syncServerURL} into {@code http(s)://host[:port][/path]} with no trailing slash. */
    public static String normalizedBaseUrl() {
        String url = AyuConfig.syncServerURL;
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        url = url.trim();
        final boolean secure = AyuConfig.useSecureConnection;
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("ws://")) {
            url = url.substring(5);
            lower = url.toLowerCase(Locale.ROOT);
        } else if (lower.startsWith("wss://")) {
            url = url.substring(6);
            lower = url.toLowerCase(Locale.ROOT);
        }
        if (lower.startsWith("http://")) {
            url = url.substring(7);
        } else if (lower.startsWith("https://")) {
            url = url.substring(8);
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        return (secure ? "https://" : "http://") + url;
    }

    private String request(String url, String method, String body) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "AyuGram-Android");
            connection.setRequestProperty("X-Device-Id", getDeviceId());
            final String token = AyuConfig.syncServerToken;
            if (!TextUtils.isEmpty(token)) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                final byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(payload.length);
                OutputStream os = connection.getOutputStream();
                try {
                    os.write(payload);
                    os.flush();
                } finally {
                    try {
                        os.close();
                    } catch (Throwable ignore) {
                    }
                }
            }
            final int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code);
            }
            return readAll(connection.getInputStream());
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        } finally {
            try {
                reader.close();
            } catch (Throwable ignore) {
            }
        }
        return sb.toString();
    }

    public String getDeviceId() {
        if (deviceId != null) {
            return deviceId;
        }
        String id = null;
        try {
            final Context context = ApplicationLoader.applicationContext;
            if (context != null) {
                id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        if (TextUtils.isEmpty(id)) {
            id = "unknown";
        }
        deviceId = id;
        return deviceId;
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }
}
