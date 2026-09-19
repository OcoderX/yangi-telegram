package org.telegram.messenger.ayu.duplicates;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * AyuGram Duplicate Cleaner preferences.
 * <p>
 * Kept deliberately separate from {@code AyuConfig} / {@code SharedConfig} so the feature owns its
 * own SharedPreferences file and nothing else has to change when options are added here.
 */
public class DuplicatesConfig {

    public static final String PREFS_NAME = "ayu_duplicates";

    /** files smaller than this are never considered (bytes) */
    public static final long DEFAULT_MIN_FILE_SIZE = 16 * 1024L;

    private static SharedPreferences preferences;
    private static boolean loaded;

    // ---------------- last scan summary ----------------
    /** unix time (ms) of the last finished scan, 0 = never scanned */
    public static long lastScanTime = 0;
    /** number of duplicate groups found by the last scan */
    public static int lastScanGroups = 0;
    /** number of redundant copies (total files in groups minus one per group) */
    public static int lastScanDuplicateFiles = 0;
    /** bytes that could be freed by keeping one copy per group */
    public static long lastScanReclaimable = 0;
    /** total number of local files examined by the last scan */
    public static int lastScanTotalFiles = 0;
    /** total bytes of the files examined by the last scan */
    public static long lastScanTotalBytes = 0;

    // ---------------- lifetime stats ----------------
    /** bytes actually deleted by this feature since install */
    public static long totalFreedBytes = 0;

    // ---------------- options ----------------
    /** minimum file size to consider, in bytes */
    public static long minFileSize = DEFAULT_MIN_FILE_SIZE;
    /** true = also look inside the emoji/sticker "acache" folder */
    public static boolean includeStickerCache = false;

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        preferences = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        lastScanTime = preferences.getLong("lastScanTime", 0);
        lastScanGroups = preferences.getInt("lastScanGroups", 0);
        lastScanDuplicateFiles = preferences.getInt("lastScanDuplicateFiles", 0);
        lastScanReclaimable = preferences.getLong("lastScanReclaimable", 0);
        lastScanTotalFiles = preferences.getInt("lastScanTotalFiles", 0);
        lastScanTotalBytes = preferences.getLong("lastScanTotalBytes", 0);
        totalFreedBytes = preferences.getLong("totalFreedBytes", 0);
        minFileSize = preferences.getLong("minFileSize", DEFAULT_MIN_FILE_SIZE);
        includeStickerCache = preferences.getBoolean("includeStickerCache", false);

        loaded = true;
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    public static void saveLastScan(int groups, int duplicateFiles, long reclaimable, int totalFiles, long totalBytes) {
        ensureLoaded();
        lastScanTime = System.currentTimeMillis();
        lastScanGroups = groups;
        lastScanDuplicateFiles = duplicateFiles;
        lastScanReclaimable = reclaimable;
        lastScanTotalFiles = totalFiles;
        lastScanTotalBytes = totalBytes;
        preferences.edit()
                .putLong("lastScanTime", lastScanTime)
                .putInt("lastScanGroups", lastScanGroups)
                .putInt("lastScanDuplicateFiles", lastScanDuplicateFiles)
                .putLong("lastScanReclaimable", lastScanReclaimable)
                .putInt("lastScanTotalFiles", lastScanTotalFiles)
                .putLong("lastScanTotalBytes", lastScanTotalBytes)
                .apply();
    }

    /** called after files have actually been removed from disk */
    public static void onBytesFreed(long bytes) {
        ensureLoaded();
        if (bytes <= 0) {
            return;
        }
        totalFreedBytes += bytes;
        lastScanReclaimable = Math.max(0, lastScanReclaimable - bytes);
        preferences.edit()
                .putLong("totalFreedBytes", totalFreedBytes)
                .putLong("lastScanReclaimable", lastScanReclaimable)
                .apply();
    }

    public static void setMinFileSize(long value) {
        ensureLoaded();
        minFileSize = value;
        preferences.edit().putLong("minFileSize", value).apply();
    }

    public static void setIncludeStickerCache(boolean value) {
        ensureLoaded();
        includeStickerCache = value;
        preferences.edit().putBoolean("includeStickerCache", value).apply();
    }

    public static void clearLastScan() {
        ensureLoaded();
        lastScanTime = 0;
        lastScanGroups = 0;
        lastScanDuplicateFiles = 0;
        lastScanReclaimable = 0;
        lastScanTotalFiles = 0;
        lastScanTotalBytes = 0;
        preferences.edit()
                .remove("lastScanTime")
                .remove("lastScanGroups")
                .remove("lastScanDuplicateFiles")
                .remove("lastScanReclaimable")
                .remove("lastScanTotalFiles")
                .remove("lastScanTotalBytes")
                .apply();
    }

    public static boolean hasLastScan() {
        ensureLoaded();
        return lastScanTime > 0;
    }
}
