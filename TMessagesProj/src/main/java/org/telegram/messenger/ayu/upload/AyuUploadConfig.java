package org.telegram.messenger.ayu.upload;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * AyuGram Upload Accelerator configuration.
 * <p>
 * Kept in its own SharedPreferences file ({@link #PREFS_NAME}) so that it never collides with
 * {@code AyuConfig} / {@code SharedConfig}. Same pattern as {@code org.telegram.messenger.ayu.AyuConfig}:
 * values are loaded once into plain static fields and written back through the setters.
 */
public class AyuUploadConfig {

    public static final String PREFS_NAME = "ayu_upload";

    public static final int MIN_CONCURRENCY = 1;
    public static final int MAX_CONCURRENCY = 3;

    public static final int MIN_PART_RETRIES = 0;
    public static final int MAX_PART_RETRIES = 10;

    public static SharedPreferences preferences;
    private static boolean loaded;

    /** master switch of the AyuGram background upload queue */
    public static boolean queueEnabled = true;
    /** how many "big" uploads may run at the same time, 1..3 */
    public static int concurrency = 1;
    /** keep a foreground service + progress notification alive while the queue is not empty */
    public static boolean keepAliveWhileUploading = true;
    /** how many times a single failed part is retried before the whole upload is failed */
    public static int partRetries = 5;
    /** pause instead of failing when the connection drops, and resume from the last confirmed part */
    public static boolean resumeAfterNetworkLoss = true;
    /** remember which parts the server already confirmed and never send them twice */
    public static boolean reuseConfirmedParts = true;

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        if (ApplicationLoader.applicationContext == null) {
            return;
        }
        preferences = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        queueEnabled = preferences.getBoolean("queueEnabled", true);
        concurrency = clampConcurrency(preferences.getInt("concurrency", 1));
        keepAliveWhileUploading = preferences.getBoolean("keepAliveWhileUploading", true);
        partRetries = clampRetries(preferences.getInt("partRetries", 5));
        resumeAfterNetworkLoss = preferences.getBoolean("resumeAfterNetworkLoss", true);
        reuseConfirmedParts = preferences.getBoolean("reuseConfirmedParts", true);

        loaded = true;
    }

    public static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    public static int clampConcurrency(int value) {
        if (value < MIN_CONCURRENCY) {
            return MIN_CONCURRENCY;
        }
        if (value > MAX_CONCURRENCY) {
            return MAX_CONCURRENCY;
        }
        return value;
    }

    public static int clampRetries(int value) {
        if (value < MIN_PART_RETRIES) {
            return MIN_PART_RETRIES;
        }
        if (value > MAX_PART_RETRIES) {
            return MAX_PART_RETRIES;
        }
        return value;
    }

    private static void putBoolean(String key, boolean value) {
        ensureLoaded();
        if (preferences != null) {
            preferences.edit().putBoolean(key, value).apply();
        }
    }

    private static void putInt(String key, int value) {
        ensureLoaded();
        if (preferences != null) {
            preferences.edit().putInt(key, value).apply();
        }
    }

    static void putString(String key, String value) {
        ensureLoaded();
        if (preferences != null) {
            preferences.edit().putString(key, value).apply();
        }
    }

    static String getString(String key, String def) {
        ensureLoaded();
        if (preferences == null) {
            return def;
        }
        return preferences.getString(key, def);
    }

    // ---------------- setters ----------------

    public static void setQueueEnabled(boolean v) {
        queueEnabled = v;
        putBoolean("queueEnabled", v);
    }

    public static void setConcurrency(int v) {
        concurrency = clampConcurrency(v);
        putInt("concurrency", concurrency);
    }

    public static void setKeepAliveWhileUploading(boolean v) {
        keepAliveWhileUploading = v;
        putBoolean("keepAliveWhileUploading", v);
    }

    public static void setPartRetries(int v) {
        partRetries = clampRetries(v);
        putInt("partRetries", partRetries);
    }

    public static void setResumeAfterNetworkLoss(boolean v) {
        resumeAfterNetworkLoss = v;
        putBoolean("resumeAfterNetworkLoss", v);
    }

    public static void setReuseConfirmedParts(boolean v) {
        reuseConfirmedParts = v;
        putBoolean("reuseConfirmedParts", v);
    }

    // ---------------- derived ----------------

    /** number of "big file" upload operations FileLoader may run at once */
    public static int getConcurrency() {
        ensureLoaded();
        if (!queueEnabled) {
            return 1;
        }
        return clampConcurrency(concurrency);
    }

    /** retries for a single failed upload part; 0 disables per-part retry (old behaviour) */
    public static int getPartRetries() {
        ensureLoaded();
        return clampRetries(partRetries);
    }

    public static boolean isResumeEnabled() {
        ensureLoaded();
        return resumeAfterNetworkLoss;
    }

    public static boolean isReuseConfirmedPartsEnabled() {
        ensureLoaded();
        return reuseConfirmedParts;
    }
}
