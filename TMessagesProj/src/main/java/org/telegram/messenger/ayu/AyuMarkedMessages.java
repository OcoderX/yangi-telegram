package org.telegram.messenger.ayu;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;

/**
 * Ox-gram "Marking message": a purely local bookmark list. The marked message ids are kept per
 * account + dialog in their own SharedPreferences file ("ox_marked_messages") as a JSON array, and
 * mirrored in memory because {@link #isMarked(int, long, int)} is consulted while drawing.
 */
public class AyuMarkedMessages {

    public static final String PREFS_NAME = "ox_marked_messages";
    /** hard cap so a runaway list cannot grow without bounds */
    private static final int MAX_PER_DIALOG = 2000;

    private static SharedPreferences preferences;
    /** key -> ordered set of message ids (newest first) */
    private static final HashMap<String, LinkedHashSet<Integer>> cache = new HashMap<>();

    private AyuMarkedMessages() {
    }

    private static SharedPreferences prefs() {
        if (preferences == null && ApplicationLoader.applicationContext != null) {
            preferences = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
        return preferences;
    }

    private static String key(int account, long dialogId) {
        return account + "_" + dialogId;
    }

    private static LinkedHashSet<Integer> get(int account, long dialogId) {
        final String key = key(account, dialogId);
        synchronized (cache) {
            LinkedHashSet<Integer> ids = cache.get(key);
            if (ids != null) {
                return ids;
            }
            ids = new LinkedHashSet<>();
            final SharedPreferences prefs = prefs();
            if (prefs != null) {
                final String stored = prefs.getString(key, null);
                if (!TextUtils.isEmpty(stored)) {
                    try {
                        JSONArray array = new JSONArray(stored);
                        for (int a = 0; a < array.length(); a++) {
                            ids.add(array.optInt(a));
                        }
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
            }
            cache.put(key, ids);
            return ids;
        }
    }

    private static void save(int account, long dialogId, LinkedHashSet<Integer> ids) {
        final SharedPreferences prefs = prefs();
        if (prefs == null) {
            return;
        }
        final String key = key(account, dialogId);
        if (ids.isEmpty()) {
            prefs.edit().remove(key).apply();
            return;
        }
        JSONArray array = new JSONArray();
        for (Integer id : ids) {
            array.put(id);
        }
        prefs.edit().putString(key, array.toString()).apply();
    }

    public static boolean isMarked(int account, long dialogId, int messageId) {
        if (messageId == 0 || dialogId == 0) {
            return false;
        }
        synchronized (cache) {
            return get(account, dialogId).contains(messageId);
        }
    }

    /** flips the bookmark, returns the new state */
    public static boolean toggle(int account, long dialogId, int messageId) {
        if (messageId == 0 || dialogId == 0) {
            return false;
        }
        synchronized (cache) {
            LinkedHashSet<Integer> ids = get(account, dialogId);
            final boolean marked;
            if (ids.contains(messageId)) {
                ids.remove(messageId);
                marked = false;
            } else {
                ids.add(messageId);
                marked = true;
                while (ids.size() > MAX_PER_DIALOG) {
                    ids.remove(ids.iterator().next());
                }
            }
            save(account, dialogId, ids);
            return marked;
        }
    }

    public static void remove(int account, long dialogId, int messageId) {
        synchronized (cache) {
            LinkedHashSet<Integer> ids = get(account, dialogId);
            if (ids.remove(messageId)) {
                save(account, dialogId, ids);
            }
        }
    }

    /** marked ids of the dialog, newest message first */
    public static ArrayList<Integer> getMarked(int account, long dialogId) {
        ArrayList<Integer> result;
        synchronized (cache) {
            result = new ArrayList<>(get(account, dialogId));
        }
        Collections.sort(result, (a, b) -> Integer.compare(b, a));
        return result;
    }

    public static int getCount(int account, long dialogId) {
        synchronized (cache) {
            return get(account, dialogId).size();
        }
    }

    public static void clear(int account, long dialogId) {
        synchronized (cache) {
            LinkedHashSet<Integer> ids = get(account, dialogId);
            ids.clear();
            save(account, dialogId, ids);
        }
    }
}
