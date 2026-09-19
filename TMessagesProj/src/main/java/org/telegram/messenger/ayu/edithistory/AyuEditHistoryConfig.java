package org.telegram.messenger.ayu.edithistory;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

/**
 * AyuGram "Edit history" feature preferences.
 * <p>
 * Kept in its own SharedPreferences file ({@link #PREFS_NAME}) so it never collides with
 * {@code AyuConfig}/{@code SharedConfig}. Every field is a plain static that call sites can read
 * without touching SharedPreferences; the setters persist asynchronously through {@code apply()}.
 */
public class AyuEditHistoryConfig {

    public static final String PREFS_NAME = "ayu_edithistory";

    private static SharedPreferences preferences;
    private static volatile boolean loaded;

    /** master switch for capturing revisions (on top of AyuConfig.saveMessagesHistory) */
    public static boolean captureEdits = true;
    /** compare messages coming from the server with the local copy and capture missed edits */
    public static boolean captureOnHistoryLoad = true;
    /** keep a copy of the replaced media file in files_dir/ayu/saved_media */
    public static boolean keepOldMedia = true;
    /** show "edited (N)" instead of the plain edited mark */
    public static boolean showEditedCount = true;
    /** tapping the edited mark opens the edit history sheet */
    public static boolean tapEditedOpensHistory = true;
    /** render a word level diff between consecutive versions */
    public static boolean wordDiff = true;
    /** start the sheet with the full text instead of only the changed parts */
    public static boolean expandFullTextByDefault = false;

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        try {
            preferences = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            captureEdits = preferences.getBoolean("captureEdits", true);
            captureOnHistoryLoad = preferences.getBoolean("captureOnHistoryLoad", true);
            keepOldMedia = preferences.getBoolean("keepOldMedia", true);
            showEditedCount = preferences.getBoolean("showEditedCount", true);
            tapEditedOpensHistory = preferences.getBoolean("tapEditedOpensHistory", true);
            wordDiff = preferences.getBoolean("wordDiff", true);
            expandFullTextByDefault = preferences.getBoolean("expandFullTextByDefault", false);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        loaded = true;
    }

    public static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    private static void putBoolean(String key, boolean value) {
        ensureLoaded();
        if (preferences == null) {
            return;
        }
        try {
            preferences.edit().putBoolean(key, value).apply();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void setCaptureEdits(boolean v) {
        captureEdits = v;
        putBoolean("captureEdits", v);
    }

    public static void setCaptureOnHistoryLoad(boolean v) {
        captureOnHistoryLoad = v;
        putBoolean("captureOnHistoryLoad", v);
    }

    public static void setKeepOldMedia(boolean v) {
        keepOldMedia = v;
        putBoolean("keepOldMedia", v);
    }

    public static void setShowEditedCount(boolean v) {
        showEditedCount = v;
        putBoolean("showEditedCount", v);
    }

    public static void setTapEditedOpensHistory(boolean v) {
        tapEditedOpensHistory = v;
        putBoolean("tapEditedOpensHistory", v);
    }

    public static void setWordDiff(boolean v) {
        wordDiff = v;
        putBoolean("wordDiff", v);
    }

    public static void setExpandFullTextByDefault(boolean v) {
        expandFullTextByDefault = v;
        putBoolean("expandFullTextByDefault", v);
    }

    /** capture is enabled only when both the shared AyuGram switch and ours are on */
    public static boolean isCaptureEnabled() {
        ensureLoaded();
        org.telegram.messenger.ayu.AyuConfig.load();
        return captureEdits && org.telegram.messenger.ayu.AyuConfig.saveMessagesHistory;
    }
}
