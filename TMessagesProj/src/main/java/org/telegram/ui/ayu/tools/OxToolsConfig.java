package org.telegram.ui.ayu.tools;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;

/**
 * OcoderX-gram: shared preferences for the small "tools" screens (Screen Light, ID Finder).
 * <p>
 * Deliberately kept in its own SharedPreferences file ("ox_tools") so it never collides with
 * {@link org.telegram.messenger.ayu.AyuConfig}. The access pattern mirrors AyuConfig / ScreenshotConfig:
 * plain static fields + explicit setters that persist immediately.
 */
public class OxToolsConfig {

    public static final String PREFS_NAME = "ox_tools";

    public static final int MAX_ID_HISTORY = 10;

    public static SharedPreferences preferences;
    private static boolean loaded;

    // ---------------- Screen Light ----------------
    public static float screenLightBrightness = 1f;
    public static int screenLightColor = 0xFFFFFFFF;

    // ---------------- ID Finder ----------------

    /** One past lookup, newest first in {@link #idFinderHistory}. */
    public static class IdFinderEntry {
        public final String type;
        public final long id;
        public final String title;
        public final String username;

        public IdFinderEntry(String type, long id, String title, String username) {
            this.type = type;
            this.id = id;
            this.title = title == null ? "" : title;
            this.username = username == null ? "" : username;
        }
    }

    public static final ArrayList<IdFinderEntry> idFinderHistory = new ArrayList<>();

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        preferences = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        screenLightBrightness = preferences.getFloat("screenLightBrightness", 1f);
        screenLightColor = preferences.getInt("screenLightColor", 0xFFFFFFFF);

        idFinderHistory.clear();
        String raw = preferences.getString("idFinderHistory", "");
        if (raw != null && raw.length() > 0) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    idFinderHistory.add(new IdFinderEntry(
                            obj.optString("type", ""),
                            obj.optLong("id", 0),
                            obj.optString("title", ""),
                            obj.optString("username", "")));
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }

        loaded = true;
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    public static void setScreenLightBrightness(float value) {
        ensureLoaded();
        screenLightBrightness = value;
        preferences.edit().putFloat("screenLightBrightness", value).apply();
    }

    public static void setScreenLightColor(int color) {
        ensureLoaded();
        screenLightColor = color;
        preferences.edit().putInt("screenLightColor", color).apply();
    }

    /** Adds (or moves to front) one lookup entry, trimming the list to {@link #MAX_ID_HISTORY}. */
    public static void addIdFinderHistory(String type, long id, String title, String username) {
        ensureLoaded();
        for (int i = idFinderHistory.size() - 1; i >= 0; i--) {
            IdFinderEntry existing = idFinderHistory.get(i);
            if (existing.type.equals(type) && existing.id == id) {
                idFinderHistory.remove(i);
            }
        }

        idFinderHistory.add(0, new IdFinderEntry(type, id, title, username));
        while (idFinderHistory.size() > MAX_ID_HISTORY) {
            idFinderHistory.remove(idFinderHistory.size() - 1);
        }
        persistHistory();
    }

    public static void clearIdFinderHistory() {
        ensureLoaded();
        idFinderHistory.clear();
        persistHistory();
    }

    private static void persistHistory() {
        JSONArray arr = new JSONArray();
        try {
            for (IdFinderEntry entry : idFinderHistory) {
                JSONObject obj = new JSONObject();
                obj.put("type", entry.type);
                obj.put("id", entry.id);
                obj.put("title", entry.title);
                obj.put("username", entry.username);
                arr.put(obj);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        preferences.edit().putString("idFinderHistory", arr.toString()).apply();
    }
}
