package org.telegram.messenger.ayu.explorer;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * Preferences of the AyuGram File Explorer. Kept in its own SharedPreferences file so that it does
 * not interfere with {@code org.telegram.messenger.ayu.AyuConfig}.
 */
public class FileExplorerConfig {

    public static final String PREFS_NAME = "ayu_file_explorer";

    public static final int SORT_DATE_DESC = 0;
    public static final int SORT_DATE_ASC = 1;
    public static final int SORT_SIZE_DESC = 2;
    public static final int SORT_SIZE_ASC = 3;
    public static final int SORT_NAME_ASC = 4;
    public static final int SORT_NAME_DESC = 5;

    public static SharedPreferences preferences;
    private static boolean loaded;

    /** default sorting used by the "By chat" / "By type" file lists */
    public static int sortMode = SORT_DATE_DESC;
    /** show only files that are physically present on this device */
    public static boolean onlyDownloaded = true;
    /** also walk the FileLoader directories looking for files without a message */
    public static boolean includeOrphanFiles = true;

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        preferences = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sortMode = preferences.getInt("sortMode", SORT_DATE_DESC);
        onlyDownloaded = preferences.getBoolean("onlyDownloaded", true);
        includeOrphanFiles = preferences.getBoolean("includeOrphanFiles", true);
        loaded = true;
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    public static void setSortMode(int value) {
        ensureLoaded();
        sortMode = value;
        preferences.edit().putInt("sortMode", value).apply();
    }

    public static void setOnlyDownloaded(boolean value) {
        ensureLoaded();
        onlyDownloaded = value;
        preferences.edit().putBoolean("onlyDownloaded", value).apply();
    }

    public static void setIncludeOrphanFiles(boolean value) {
        ensureLoaded();
        includeOrphanFiles = value;
        preferences.edit().putBoolean("includeOrphanFiles", value).apply();
    }
}
