package org.telegram.ui.ayu.screenshot;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * AyuGram: preferences of the long screenshot builder.
 * <p>
 * Deliberately kept in its own SharedPreferences file so that it never collides with
 * {@link org.telegram.messenger.ayu.AyuConfig} (which is owned by the AyuGram settings screens).
 * The access pattern mirrors AyuConfig: plain static fields + explicit setters that persist.
 */
public class ScreenshotConfig {

    public static final String PREFS_NAME = "ayu_screenshot";

    public static final int FORMAT_PNG = 0;
    public static final int FORMAT_PDF = 1;

    /** page background behind the bubbles */
    public static final int BACKGROUND_WALLPAPER = 0;
    public static final int BACKGROUND_LIGHT = 1;
    public static final int BACKGROUND_DARK = 2;

    /** what to do when a single PNG would be taller than the bitmap limit */
    public static final int OVERFLOW_SPLIT = 0;
    public static final int OVERFLOW_DOWNSCALE = 1;

    /** PDF pagination */
    public static final int PDF_CONTINUOUS = 0;
    public static final int PDF_A4 = 1;

    public static final float[] SCALES = new float[]{0.75f, 1.0f, 1.5f, 2.0f};

    public static SharedPreferences preferences;
    private static boolean loaded;

    public static int format = FORMAT_PNG;
    public static int background = BACKGROUND_WALLPAPER;
    public static int overflowMode = OVERFLOW_SPLIT;
    public static int pdfMode = PDF_CONTINUOUS;
    public static int scaleIndex = 1;

    public static boolean hideNames = false;
    public static boolean hideAvatars = false;
    public static boolean hideTimestamps = false;
    public static boolean includeHeader = true;
    public static boolean includeWatermark = true;

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        preferences = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        format = preferences.getInt("format", FORMAT_PNG);
        background = preferences.getInt("background", BACKGROUND_WALLPAPER);
        overflowMode = preferences.getInt("overflowMode", OVERFLOW_SPLIT);
        pdfMode = preferences.getInt("pdfMode", PDF_CONTINUOUS);
        scaleIndex = preferences.getInt("scaleIndex", 1);

        hideNames = preferences.getBoolean("hideNames", false);
        hideAvatars = preferences.getBoolean("hideAvatars", false);
        hideTimestamps = preferences.getBoolean("hideTimestamps", false);
        includeHeader = preferences.getBoolean("includeHeader", true);
        includeWatermark = preferences.getBoolean("includeWatermark", true);

        loaded = true;
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    public static float getScale() {
        ensureLoaded();
        int index = scaleIndex;
        if (index < 0) {
            index = 0;
        } else if (index >= SCALES.length) {
            index = SCALES.length - 1;
        }
        return SCALES[index];
    }

    // ---------------- generic setters ----------------

    public static void putBoolean(String key, boolean value) {
        ensureLoaded();
        preferences.edit().putBoolean(key, value).apply();
    }

    public static void putInt(String key, int value) {
        ensureLoaded();
        preferences.edit().putInt(key, value).apply();
    }

    // ---------------- typed setters ----------------

    public static void setFormat(int v) {
        ensureLoaded();
        format = v;
        putInt("format", v);
    }

    public static void setBackground(int v) {
        ensureLoaded();
        background = v;
        putInt("background", v);
    }

    public static void setOverflowMode(int v) {
        ensureLoaded();
        overflowMode = v;
        putInt("overflowMode", v);
    }

    public static void setPdfMode(int v) {
        ensureLoaded();
        pdfMode = v;
        putInt("pdfMode", v);
    }

    public static void setScaleIndex(int v) {
        ensureLoaded();
        scaleIndex = v;
        putInt("scaleIndex", v);
    }

    public static void setHideNames(boolean v) {
        ensureLoaded();
        hideNames = v;
        putBoolean("hideNames", v);
    }

    public static void setHideAvatars(boolean v) {
        ensureLoaded();
        hideAvatars = v;
        putBoolean("hideAvatars", v);
    }

    public static void setHideTimestamps(boolean v) {
        ensureLoaded();
        hideTimestamps = v;
        putBoolean("hideTimestamps", v);
    }

    public static void setIncludeHeader(boolean v) {
        ensureLoaded();
        includeHeader = v;
        putBoolean("includeHeader", v);
    }

    public static void setIncludeWatermark(boolean v) {
        ensureLoaded();
        includeWatermark = v;
        putBoolean("includeWatermark", v);
    }
}
