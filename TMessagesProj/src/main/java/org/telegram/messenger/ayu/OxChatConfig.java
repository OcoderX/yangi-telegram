package org.telegram.messenger.ayu;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * Ox-gram: chat bubble tweaks that are not part of AyuGram.
 * <p>
 * Right now it only holds the "collapse long messages" feature: a text message longer than
 * {@link #longMessageCollapseLines} lines (or {@link #longMessageCollapseChars} characters) is laid
 * out truncated and gets a "Whole Message" button inside the bubble that expands it in place.
 * <p>
 * Everything lives in its own SharedPreferences file ("ox_chat"), separate from {@link AyuConfig}.
 */
public class OxChatConfig {

    public static final String PREFS_NAME = "ox_chat";

    private static final String KEY_COLLAPSE_LONG_MESSAGES = "collapse_long_messages";
    private static final String KEY_COLLAPSE_LINES = "long_message_collapse_lines";
    private static final String KEY_COLLAPSE_CHARS = "long_message_collapse_chars";

    public static final int DEFAULT_COLLAPSE_LINES = 12;
    public static final int DEFAULT_COLLAPSE_CHARS = 700;
    public static final int MIN_COLLAPSE_LINES = 4;
    public static final int MAX_COLLAPSE_LINES = 60;

    private static SharedPreferences preferences;
    private static boolean loaded;

    /** Master toggle of the whole feature. */
    public static boolean collapseLongMessages = true;
    /** How many lines of a long text stay visible while it is collapsed. */
    public static int longMessageCollapseLines = DEFAULT_COLLAPSE_LINES;
    /** Texts longer than this many characters are collapsed even when they have fewer lines. */
    public static int longMessageCollapseChars = DEFAULT_COLLAPSE_CHARS;

    private OxChatConfig() {
    }

    public static SharedPreferences getPreferences() {
        if (preferences == null && ApplicationLoader.applicationContext != null) {
            preferences = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
        return preferences;
    }

    /** Reads the stored values into the static fields. Safe to call any number of times. */
    public static synchronized void load() {
        if (loaded) {
            return;
        }
        final SharedPreferences prefs = getPreferences();
        if (prefs == null) {
            // too early (no application context yet): keep the defaults, retry on the next call
            return;
        }
        collapseLongMessages = prefs.getBoolean(KEY_COLLAPSE_LONG_MESSAGES, true);
        longMessageCollapseLines = clampLines(prefs.getInt(KEY_COLLAPSE_LINES, DEFAULT_COLLAPSE_LINES));
        longMessageCollapseChars = clampChars(prefs.getInt(KEY_COLLAPSE_CHARS, DEFAULT_COLLAPSE_CHARS));
        loaded = true;
    }

    private static int clampLines(int value) {
        if (value < MIN_COLLAPSE_LINES) {
            return MIN_COLLAPSE_LINES;
        }
        if (value > MAX_COLLAPSE_LINES) {
            return MAX_COLLAPSE_LINES;
        }
        return value;
    }

    private static int clampChars(int value) {
        if (value < 100) {
            return 100;
        }
        return value;
    }

    // ---------------------------------------------------------------- getters

    public static boolean isCollapseLongMessages() {
        load();
        return collapseLongMessages;
    }

    public static int getLongMessageCollapseLines() {
        load();
        return clampLines(longMessageCollapseLines);
    }

    public static int getLongMessageCollapseChars() {
        load();
        return clampChars(longMessageCollapseChars);
    }

    // ---------------------------------------------------------------- setters

    public static void setCollapseLongMessages(boolean value) {
        load();
        collapseLongMessages = value;
        final SharedPreferences prefs = getPreferences();
        if (prefs != null) {
            prefs.edit().putBoolean(KEY_COLLAPSE_LONG_MESSAGES, value).apply();
        }
    }

    /** Convenience for a preferences row: flips the toggle and returns the new state. */
    public static boolean toggleCollapseLongMessages() {
        setCollapseLongMessages(!isCollapseLongMessages());
        return collapseLongMessages;
    }

    public static void setLongMessageCollapseLines(int value) {
        load();
        longMessageCollapseLines = clampLines(value);
        final SharedPreferences prefs = getPreferences();
        if (prefs != null) {
            prefs.edit().putInt(KEY_COLLAPSE_LINES, longMessageCollapseLines).apply();
        }
    }

    public static void setLongMessageCollapseChars(int value) {
        load();
        longMessageCollapseChars = clampChars(value);
        final SharedPreferences prefs = getPreferences();
        if (prefs != null) {
            prefs.edit().putInt(KEY_COLLAPSE_CHARS, longMessageCollapseChars).apply();
        }
    }
}
