package org.telegram.messenger.ayu.contacts;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;

import java.util.ArrayList;
import java.util.LinkedHashSet;

/**
 * Ox-gram "Special contacts": the persisted set of user ids the user wants to watch, plus the
 * notification switches for the {@link ContactTracker}.
 * <p>
 * Owns its own SharedPreferences file ({@link #PREFS_NAME}) so the feature stays self contained —
 * exactly the pattern used by {@code org.telegram.messenger.ayu.radar.RadarConfig}. The id set is
 * stored as one comma separated string; user ids are globally unique in Telegram, so the set is
 * shared by every logged in account.
 */
public class SpecialContactsConfig {

    public static final String PREFS_NAME = "ox_contacts";

    public static SharedPreferences preferences;
    private static boolean loaded;

    /** master switch for the online/offline notifications */
    public static boolean notificationsEnabled = true;
    public static boolean notifyOnline = true;
    public static boolean notifyOffline = true;
    /** also write every transition to the tracker database */
    public static boolean trackHistory = true;

    /** raw storage value, kept in sync with {@link #ids} */
    private static String idsRaw = "";
    private static final LinkedHashSet<Long> ids = new LinkedHashSet<>();

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        preferences = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        notificationsEnabled = preferences.getBoolean("notificationsEnabled", true);
        notifyOnline = preferences.getBoolean("notifyOnline", true);
        notifyOffline = preferences.getBoolean("notifyOffline", true);
        trackHistory = preferences.getBoolean("trackHistory", true);

        idsRaw = preferences.getString("ids", "");
        ids.clear();
        ids.addAll(parse(idsRaw));

        loaded = true;
    }

    private static synchronized void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    private static ArrayList<Long> parse(String raw) {
        ArrayList<Long> result = new ArrayList<>();
        if (TextUtils.isEmpty(raw)) {
            return result;
        }
        String[] parts = raw.split(",");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (TextUtils.isEmpty(part)) {
                continue;
            }
            try {
                long id = Long.parseLong(part);
                if (id != 0 && !result.contains(id)) {
                    result.add(id);
                }
            } catch (Throwable ignore) {
            }
        }
        return result;
    }

    private static String serialize(LinkedHashSet<Long> set) {
        StringBuilder sb = new StringBuilder();
        for (Long id : set) {
            if (id == null || id == 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }

    private static void save() {
        idsRaw = serialize(ids);
        preferences.edit().putString("ids", idsRaw).apply();
    }

    // ---------------- generic setters ----------------

    public static void putBoolean(String key, boolean value) {
        ensureLoaded();
        preferences.edit().putBoolean(key, value).apply();
    }

    // ---------------- typed setters ----------------

    public static void setNotificationsEnabled(boolean v) {
        ensureLoaded();
        notificationsEnabled = v;
        putBoolean("notificationsEnabled", v);
    }

    public static void setNotifyOnline(boolean v) {
        ensureLoaded();
        notifyOnline = v;
        putBoolean("notifyOnline", v);
    }

    public static void setNotifyOffline(boolean v) {
        ensureLoaded();
        notifyOffline = v;
        putBoolean("notifyOffline", v);
    }

    public static void setTrackHistory(boolean v) {
        ensureLoaded();
        trackHistory = v;
        putBoolean("trackHistory", v);
    }

    // ---------------- the id set ----------------

    /** a copy of the special contact ids, in insertion order */
    public static synchronized ArrayList<Long> getAll() {
        ensureLoaded();
        return new ArrayList<>(ids);
    }

    public static synchronized boolean isSpecial(long userId) {
        ensureLoaded();
        return ids.contains(userId);
    }

    public static synchronized int size() {
        ensureLoaded();
        return ids.size();
    }

    /**
     * //perf: the cheap early-out for hot callers such as {@code ContactTracker.check()}.
     * Unlike {@link #getAll()} it copies nothing, and unlike the previous {@code size() == 0} body it
     * does not re-enter the monitor.
     */
    public static synchronized boolean isEmpty() {
        ensureLoaded();
        return ids.isEmpty();
    }

    public static synchronized void add(long userId) {
        ensureLoaded();
        if (userId == 0 || ids.contains(userId)) {
            return;
        }
        ids.add(userId);
        save();
    }

    public static synchronized void remove(long userId) {
        ensureLoaded();
        if (!ids.remove(userId)) {
            return;
        }
        save();
    }

    /** @return true when the contact is special afterwards */
    public static synchronized boolean toggle(long userId) {
        ensureLoaded();
        if (ids.contains(userId)) {
            remove(userId);
            return false;
        }
        add(userId);
        return true;
    }

    public static synchronized void clear() {
        ensureLoaded();
        if (ids.isEmpty()) {
            return;
        }
        ids.clear();
        save();
    }
}
