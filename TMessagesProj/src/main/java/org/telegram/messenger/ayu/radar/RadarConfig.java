package org.telegram.messenger.ayu.radar;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;

import java.util.ArrayList;

/**
 * AyuGram Mention Radar configuration.
 * <p>
 * Kept deliberately separate from {@code AyuConfig} / {@code SharedConfig}: the radar owns its own
 * SharedPreferences file ({@link #PREFS_NAME}) so the feature can be dropped in or removed without
 * touching any shared settings class. Fields are plain statics, loaded once, updated through the
 * setters below (same pattern as {@code org.telegram.messenger.ayu.AyuConfig}).
 */
public class RadarConfig {

    public static final String PREFS_NAME = "ayuradar";

    /** hits kept in the database per account; older rows are trimmed away */
    public static final int DEFAULT_MAX_ROWS = 5000;

    public static SharedPreferences preferences;
    private static boolean loaded;

    // ---------------- master ----------------
    /** master switch; when false nothing is collected */
    public static boolean enabled = true;

    // ---------------- what counts as a hit ----------------
    public static boolean matchMentions = true;
    public static boolean matchReplies = true;
    /** match my first / last name as whole words */
    public static boolean matchName = true;
    public static boolean matchKeywords = true;

    // ---------------- where to look ----------------
    public static boolean includeGroups = true;
    public static boolean includeChannels = true;
    /** private chats are noisy (everything there is "for me"), off by default */
    public static boolean includePrivateChats = false;
    /** skip dialogs the user muted */
    public static boolean skipMutedChats = false;
    /** skip my own messages */
    public static boolean skipOutgoing = true;

    // ---------------- notifications ----------------
    /** post a local notification when a keyword hit arrives */
    public static boolean notifyOnKeyword = false;

    // ---------------- storage ----------------
    public static int maxRows = DEFAULT_MAX_ROWS;

    /** JSON array of {@link RadarKeyword} */
    public static String keywords = "[]";

    private static ArrayList<RadarKeyword> keywordCache;

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        preferences = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        enabled = preferences.getBoolean("enabled", true);

        matchMentions = preferences.getBoolean("matchMentions", true);
        matchReplies = preferences.getBoolean("matchReplies", true);
        matchName = preferences.getBoolean("matchName", true);
        matchKeywords = preferences.getBoolean("matchKeywords", true);

        includeGroups = preferences.getBoolean("includeGroups", true);
        includeChannels = preferences.getBoolean("includeChannels", true);
        includePrivateChats = preferences.getBoolean("includePrivateChats", false);
        skipMutedChats = preferences.getBoolean("skipMutedChats", false);
        skipOutgoing = preferences.getBoolean("skipOutgoing", true);

        notifyOnKeyword = preferences.getBoolean("notifyOnKeyword", false);

        maxRows = preferences.getInt("maxRows", DEFAULT_MAX_ROWS);
        keywords = preferences.getString("keywords", "[]");

        loaded = true;
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    // ---------------- generic setters ----------------
    public static void putBoolean(String key, boolean value) {
        ensureLoaded();
        preferences.edit().putBoolean(key, value).apply();
    }

    public static void putString(String key, String value) {
        ensureLoaded();
        preferences.edit().putString(key, value).apply();
    }

    public static void putInt(String key, int value) {
        ensureLoaded();
        preferences.edit().putInt(key, value).apply();
    }

    // ---------------- typed setters ----------------
    public static void setEnabled(boolean v) { ensureLoaded(); enabled = v; putBoolean("enabled", v); }

    public static void setMatchMentions(boolean v) { ensureLoaded(); matchMentions = v; putBoolean("matchMentions", v); }
    public static void setMatchReplies(boolean v) { ensureLoaded(); matchReplies = v; putBoolean("matchReplies", v); }
    public static void setMatchName(boolean v) { ensureLoaded(); matchName = v; putBoolean("matchName", v); }
    public static void setMatchKeywords(boolean v) { ensureLoaded(); matchKeywords = v; putBoolean("matchKeywords", v); }

    public static void setIncludeGroups(boolean v) { ensureLoaded(); includeGroups = v; putBoolean("includeGroups", v); }
    public static void setIncludeChannels(boolean v) { ensureLoaded(); includeChannels = v; putBoolean("includeChannels", v); }
    public static void setIncludePrivateChats(boolean v) { ensureLoaded(); includePrivateChats = v; putBoolean("includePrivateChats", v); }
    public static void setSkipMutedChats(boolean v) { ensureLoaded(); skipMutedChats = v; putBoolean("skipMutedChats", v); }
    public static void setSkipOutgoing(boolean v) { ensureLoaded(); skipOutgoing = v; putBoolean("skipOutgoing", v); }

    public static void setNotifyOnKeyword(boolean v) { ensureLoaded(); notifyOnKeyword = v; putBoolean("notifyOnKeyword", v); }

    public static void setMaxRows(int v) { ensureLoaded(); maxRows = v; putInt("maxRows", v); }

    // ---------------- keywords ----------------

    public static synchronized ArrayList<RadarKeyword> getKeywords() {
        ensureLoaded();
        if (keywordCache == null) {
            keywordCache = RadarKeyword.deserialize(keywords);
        }
        return keywordCache;
    }

    public static synchronized void saveKeywords(ArrayList<RadarKeyword> list) {
        ensureLoaded();
        keywordCache = list == null ? new ArrayList<>() : list;
        keywords = RadarKeyword.serialize(keywordCache);
        putString("keywords", keywords);
    }

    public static synchronized void addKeyword(RadarKeyword keyword) {
        if (keyword == null || TextUtils.isEmpty(keyword.text)) {
            return;
        }
        ArrayList<RadarKeyword> list = new ArrayList<>(getKeywords());
        list.add(keyword);
        saveKeywords(list);
    }

    public static synchronized void updateKeyword(RadarKeyword keyword) {
        if (keyword == null) {
            return;
        }
        ArrayList<RadarKeyword> list = new ArrayList<>(getKeywords());
        for (int i = 0; i < list.size(); i++) {
            if (TextUtils.equals(list.get(i).id, keyword.id)) {
                list.set(i, keyword);
                saveKeywords(list);
                return;
            }
        }
        list.add(keyword);
        saveKeywords(list);
    }

    public static synchronized void removeKeyword(String id) {
        ArrayList<RadarKeyword> list = new ArrayList<>(getKeywords());
        for (int i = 0; i < list.size(); i++) {
            if (TextUtils.equals(list.get(i).id, id)) {
                list.remove(i);
                saveKeywords(list);
                return;
            }
        }
    }

    public static synchronized RadarKeyword getKeywordById(String id) {
        ArrayList<RadarKeyword> list = getKeywords();
        for (int i = 0; i < list.size(); i++) {
            if (TextUtils.equals(list.get(i).id, id)) {
                return list.get(i);
            }
        }
        return null;
    }

    /** true when at least one enabled, usable keyword exists */
    public static synchronized boolean hasUsableKeywords() {
        ArrayList<RadarKeyword> list = getKeywords();
        for (int i = 0; i < list.size(); i++) {
            RadarKeyword k = list.get(i);
            if (k.enabled && k.isValid()) {
                return true;
            }
        }
        return false;
    }
}
