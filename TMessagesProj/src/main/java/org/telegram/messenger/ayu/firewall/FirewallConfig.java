package org.telegram.messenger.ayu.firewall;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.LinkedHashSet;

/**
 * AyuGram Personal Firewall configuration.
 * <p>
 * Kept deliberately separate from {@code AyuConfig} / {@code SharedConfig}: it lives in its own
 * SharedPreferences file so the firewall can never be disabled as a side effect of another feature.
 * Fields are plain statics so that the hot paths (every tapped document, every tapped link, every
 * auto-download decision) never touch SharedPreferences.
 */
public class FirewallConfig {

    public static final String PREFS_NAME = "ayufirewall";

    /** hard rule: every .exe / .apk below this size is banned outright, from anyone */
    public static final long HARD_RULE_MAX_SIZE = 5L * 1024L * 1024L;
    /** how many blocked events we keep in the persisted log */
    public static final int MAX_EVENTS = 100;

    public static SharedPreferences preferences;
    private static volatile boolean loaded;

    /** master switch, ON by default */
    public static boolean enabled = true;
    public static boolean blockExecutables = true;
    public static boolean blockSuspiciousLinks = true;
    public static boolean blockDangerousArchives = true;
    /** when true the soft rules only fire for unknown senders; the 5 MB exe/apk rule ignores this */
    public static boolean unknownSendersOnly = true;

    private static final LinkedHashSet<Long> whitelist = new LinkedHashSet<>();
    private static final ArrayList<Event> events = new ArrayList<>();

    /** one entry of the persisted blocked-events log */
    public static class Event {
        public long date;
        public long dialogId;
        public long senderId;
        /** resource *name* of the reason string, resolved lazily for display */
        public String reasonKey;
        public String detail;

        public Event() {
        }

        public Event(long date, long dialogId, long senderId, String reasonKey, String detail) {
            this.date = date;
            this.dialogId = dialogId;
            this.senderId = senderId;
            this.reasonKey = reasonKey;
            this.detail = detail;
        }
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        if (ApplicationLoader.applicationContext == null) {
            return;
        }
        preferences = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        enabled = preferences.getBoolean("enabled", true);
        blockExecutables = preferences.getBoolean("blockExecutables", true);
        blockSuspiciousLinks = preferences.getBoolean("blockSuspiciousLinks", true);
        blockDangerousArchives = preferences.getBoolean("blockDangerousArchives", true);
        unknownSendersOnly = preferences.getBoolean("unknownSendersOnly", true);

        whitelist.clear();
        String wl = preferences.getString("whitelist", "");
        if (wl != null && wl.length() > 0) {
            String[] parts = wl.split(",");
            for (int i = 0; i < parts.length; i++) {
                try {
                    whitelist.add(Long.parseLong(parts[i].trim()));
                } catch (Exception ignore) {
                }
            }
        }

        events.clear();
        String log = preferences.getString("events", "[]");
        try {
            JSONArray arr = new JSONArray(log == null ? "[]" : log);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                Event e = new Event();
                e.date = o.optLong("d");
                e.dialogId = o.optLong("c");
                e.senderId = o.optLong("s");
                e.reasonKey = o.optString("r", "");
                e.detail = o.optString("n", "");
                events.add(e);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }

        loaded = true;
    }

    public static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    /** true once the preferences have actually been read (false before ApplicationLoader is up) */
    public static boolean isLoaded() {
        return loaded;
    }

    private static void putBoolean(String key, boolean value) {
        ensureLoaded();
        if (preferences != null) {
            preferences.edit().putBoolean(key, value).apply();
        }
    }

    public static void setEnabled(boolean v) {
        enabled = v;
        putBoolean("enabled", v);
        FirewallVerdictCache.clear();
    }

    public static void setBlockExecutables(boolean v) {
        blockExecutables = v;
        putBoolean("blockExecutables", v);
        FirewallVerdictCache.clear();
    }

    public static void setBlockSuspiciousLinks(boolean v) {
        blockSuspiciousLinks = v;
        putBoolean("blockSuspiciousLinks", v);
        // link-only rule: does not affect the per-document verdict cache
    }

    public static void setBlockDangerousArchives(boolean v) {
        blockDangerousArchives = v;
        putBoolean("blockDangerousArchives", v);
        FirewallVerdictCache.clear();
    }

    public static void setUnknownSendersOnly(boolean v) {
        unknownSendersOnly = v;
        putBoolean("unknownSendersOnly", v);
        FirewallVerdictCache.clear();
    }

    // ---------------- whitelist ----------------

    public static synchronized boolean isWhitelisted(long peerId) {
        ensureLoaded();
        return peerId != 0 && whitelist.contains(peerId);
    }

    public static synchronized ArrayList<Long> getWhitelist() {
        ensureLoaded();
        return new ArrayList<>(whitelist);
    }

    public static synchronized void addToWhitelist(long peerId) {
        ensureLoaded();
        if (peerId == 0 || !whitelist.add(peerId)) {
            return;
        }
        saveWhitelist();
        FirewallVerdictCache.clear();
    }

    public static synchronized void removeFromWhitelist(long peerId) {
        ensureLoaded();
        if (!whitelist.remove(peerId)) {
            return;
        }
        saveWhitelist();
        FirewallVerdictCache.clear();
    }

    public static synchronized void clearWhitelist() {
        ensureLoaded();
        if (whitelist.isEmpty()) {
            return;
        }
        whitelist.clear();
        saveWhitelist();
        FirewallVerdictCache.clear();
    }

    private static void saveWhitelist() {
        if (preferences == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Long id : whitelist) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        preferences.edit().putString("whitelist", sb.toString()).apply();
    }

    // ---------------- blocked events log ----------------

    public static synchronized ArrayList<Event> getEvents() {
        ensureLoaded();
        return new ArrayList<>(events);
    }

    public static synchronized int getEventsCount() {
        ensureLoaded();
        return events.size();
    }

    /** newest first */
    public static synchronized void logEvent(long dialogId, long senderId, String reasonKey, String detail) {
        ensureLoaded();
        Event e = new Event(System.currentTimeMillis(), dialogId, senderId, reasonKey == null ? "" : reasonKey, detail == null ? "" : detail);
        events.add(0, e);
        while (events.size() > MAX_EVENTS) {
            events.remove(events.size() - 1);
        }
        saveEvents();
    }

    public static synchronized void clearEvents() {
        ensureLoaded();
        if (events.isEmpty()) {
            return;
        }
        events.clear();
        saveEvents();
    }

    private static void saveEvents() {
        if (preferences == null) {
            return;
        }
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < events.size(); i++) {
                Event e = events.get(i);
                JSONObject o = new JSONObject();
                o.put("d", e.date);
                o.put("c", e.dialogId);
                o.put("s", e.senderId);
                o.put("r", e.reasonKey == null ? "" : e.reasonKey);
                o.put("n", e.detail == null ? "" : e.detail);
                arr.put(o);
            }
            preferences.edit().putString("events", arr.toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
