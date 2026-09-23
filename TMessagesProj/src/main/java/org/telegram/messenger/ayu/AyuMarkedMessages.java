package org.telegram.messenger.ayu;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.SparseIntArray;

import androidx.collection.LongSparseArray;

import org.json.JSONArray;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Ox-gram "Marking message": a purely local bookmark list. The marked message ids are kept per
 * account + dialog in their own SharedPreferences file ("ox_marked_messages") as a JSON array, and
 * mirrored in memory because {@link #isMarked(int, long, int)} is consulted while binding cells.
 * <p>
 * Hot-path friendly: lookups use primitive keys (no string concatenation, no boxing) and the
 * preferences file is warmed up off the UI thread by {@link #preloadAsync()}.
 */
public class AyuMarkedMessages {

    public static final String PREFS_NAME = "ox_marked_messages";
    /** hard cap so a runaway list cannot grow without bounds */
    private static final int MAX_PER_DIALOG = 2000;

    private static volatile SharedPreferences preferences;
    /** account -> dialogId -> (messageId -> 1); SparseIntArray keeps ids sorted so the oldest id is index 0 */
    @SuppressWarnings("unchecked")
    private static final LongSparseArray<SparseIntArray>[] cache = new LongSparseArray[UserConfig.MAX_ACCOUNT_COUNT];
    /** bumped on every change so cells can cheaply detect that a cached "marked" flag is stale */
    private static volatile int version = 1;

    private AyuMarkedMessages() {
    }

    /** current change counter, compare against the value seen when a cell resolved its flag */
    public static int getVersion() {
        return version;
    }

    /** loads the preferences file on a background thread so the first lookup does not hit the disk on the UI thread */
    public static void preloadAsync() {
        Utilities.globalQueue.postRunnable(AyuMarkedMessages::prefs);
    }

    private static SharedPreferences prefs() {
        SharedPreferences p = preferences;
        if (p == null && ApplicationLoader.applicationContext != null) {
            p = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            preferences = p;
        }
        return p;
    }

    private static String key(int account, long dialogId) {
        return account + "_" + dialogId;
    }

    /** must be called under {@code synchronized (cache)} */
    private static SparseIntArray get(int account, long dialogId) {
        if (account < 0 || account >= cache.length) {
            return null;
        }
        LongSparseArray<SparseIntArray> dialogs = cache[account];
        if (dialogs == null) {
            dialogs = new LongSparseArray<>();
            cache[account] = dialogs;
        }
        SparseIntArray ids = dialogs.get(dialogId);
        if (ids != null) {
            return ids;
        }
        ids = new SparseIntArray();
        final SharedPreferences prefs = prefs();
        if (prefs != null) {
            final String stored = prefs.getString(key(account, dialogId), null);
            if (!TextUtils.isEmpty(stored)) {
                try {
                    JSONArray array = new JSONArray(stored);
                    for (int a = 0; a < array.length(); a++) {
                        final int id = array.optInt(a);
                        if (id != 0) {
                            ids.put(id, 1);
                        }
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }
        dialogs.put(dialogId, ids);
        return ids;
    }

    private static void save(int account, long dialogId, SparseIntArray ids) {
        final SharedPreferences prefs = prefs();
        if (prefs == null) {
            return;
        }
        final String key = key(account, dialogId);
        if (ids.size() == 0) {
            prefs.edit().remove(key).apply();
            return;
        }
        JSONArray array = new JSONArray();
        for (int i = 0, n = ids.size(); i < n; i++) {
            array.put(ids.keyAt(i));
        }
        prefs.edit().putString(key, array.toString()).apply();
    }

    public static boolean isMarked(int account, long dialogId, int messageId) {
        if (messageId == 0 || dialogId == 0) {
            return false;
        }
        synchronized (cache) {
            final SparseIntArray ids = get(account, dialogId);
            return ids != null && ids.size() != 0 && ids.indexOfKey(messageId) >= 0;
        }
    }

    /** flips the bookmark, returns the new state */
    public static boolean toggle(int account, long dialogId, int messageId) {
        if (messageId == 0 || dialogId == 0) {
            return false;
        }
        synchronized (cache) {
            SparseIntArray ids = get(account, dialogId);
            if (ids == null) {
                return false;
            }
            final boolean marked;
            if (ids.indexOfKey(messageId) >= 0) {
                ids.delete(messageId);
                marked = false;
            } else {
                ids.put(messageId, 1);
                marked = true;
                while (ids.size() > MAX_PER_DIALOG) {
                    ids.removeAt(0);
                }
            }
            version++;
            save(account, dialogId, ids);
            return marked;
        }
    }

    public static void remove(int account, long dialogId, int messageId) {
        synchronized (cache) {
            SparseIntArray ids = get(account, dialogId);
            if (ids != null && ids.indexOfKey(messageId) >= 0) {
                ids.delete(messageId);
                version++;
                save(account, dialogId, ids);
            }
        }
    }

    /** marked ids of the dialog, newest message first */
    public static ArrayList<Integer> getMarked(int account, long dialogId) {
        ArrayList<Integer> result = new ArrayList<>();
        synchronized (cache) {
            SparseIntArray ids = get(account, dialogId);
            if (ids != null) {
                for (int i = 0, n = ids.size(); i < n; i++) {
                    result.add(ids.keyAt(i));
                }
            }
        }
        Collections.sort(result, (a, b) -> Integer.compare(b, a));
        return result;
    }

    public static int getCount(int account, long dialogId) {
        synchronized (cache) {
            SparseIntArray ids = get(account, dialogId);
            return ids == null ? 0 : ids.size();
        }
    }

    public static void clear(int account, long dialogId) {
        synchronized (cache) {
            SparseIntArray ids = get(account, dialogId);
            if (ids != null && ids.size() != 0) {
                ids.clear();
                version++;
                save(account, dialogId, ids);
            }
        }
    }
}
