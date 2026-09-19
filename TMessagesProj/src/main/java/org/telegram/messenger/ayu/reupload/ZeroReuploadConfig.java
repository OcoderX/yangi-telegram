package org.telegram.messenger.ayu.reupload;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * Configuration of the AyuGram "Zero-Reupload" feature.
 * <p>
 * Kept deliberately separate from {@code AyuConfig} / {@code SharedConfig} so the feature owns its
 * own preference file and can be dropped without touching the shared config classes.
 */
public class ZeroReuploadConfig {

    public static final String PREFS_NAME = "ayu_zero_reupload";

    /** smallest file we bother fingerprinting, in kilobytes */
    public static final int DEFAULT_MIN_SIZE_KB = 64;

    public static SharedPreferences preferences;
    private static boolean loaded;

    /** master toggle, off by default */
    public static boolean enabled = false;
    /** also remember files that arrived through a download, not only the ones we uploaded */
    public static boolean indexReceived = false;
    /** show a short "sent instantly" bulletin on a cache hit */
    public static boolean showBulletin = false;
    /** files smaller than this are never fingerprinted (hashing them costs more than uploading) */
    public static int minSizeKb = DEFAULT_MIN_SIZE_KB;

    // ---------------- statistics ----------------
    /** how many uploads were skipped */
    public static long reusedCount = 0;
    /** how many bytes were not uploaded because of a cache hit */
    public static long bytesSaved = 0;

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        preferences = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        enabled = preferences.getBoolean("enabled", false);
        indexReceived = preferences.getBoolean("indexReceived", false);
        showBulletin = preferences.getBoolean("showBulletin", false);
        minSizeKb = preferences.getInt("minSizeKb", DEFAULT_MIN_SIZE_KB);
        reusedCount = preferences.getLong("reusedCount", 0);
        bytesSaved = preferences.getLong("bytesSaved", 0);

        loaded = true;
    }

    public static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    public static boolean isEnabled() {
        ensureLoaded();
        return enabled;
    }

    /** minimum file size in bytes */
    public static long getMinSizeBytes() {
        ensureLoaded();
        return Math.max(1, (long) minSizeKb) * 1024L;
    }

    public static void setEnabled(boolean v) {
        ensureLoaded();
        enabled = v;
        preferences.edit().putBoolean("enabled", v).apply();
    }

    public static void setIndexReceived(boolean v) {
        ensureLoaded();
        indexReceived = v;
        preferences.edit().putBoolean("indexReceived", v).apply();
    }

    public static void setShowBulletin(boolean v) {
        ensureLoaded();
        showBulletin = v;
        preferences.edit().putBoolean("showBulletin", v).apply();
    }

    public static void setMinSizeKb(int v) {
        ensureLoaded();
        minSizeKb = Math.max(1, v);
        preferences.edit().putInt("minSizeKb", minSizeKb).apply();
    }

    /** called once per skipped upload */
    public static synchronized void addSaved(long bytes) {
        ensureLoaded();
        reusedCount++;
        if (bytes > 0) {
            bytesSaved += bytes;
        }
        preferences.edit()
                .putLong("reusedCount", reusedCount)
                .putLong("bytesSaved", bytesSaved)
                .apply();
    }

    public static synchronized void resetStats() {
        ensureLoaded();
        reusedCount = 0;
        bytesSaved = 0;
        preferences.edit()
                .putLong("reusedCount", 0)
                .putLong("bytesSaved", 0)
                .apply();
    }
}
