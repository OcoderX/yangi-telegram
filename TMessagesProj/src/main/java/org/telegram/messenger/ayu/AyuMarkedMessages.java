package org.telegram.messenger.ayu;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.SparseIntArray;

import androidx.collection.LongSparseArray;

import org.json.JSONArray;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;
import java.util.Map;

/**
 * Ox-gram "Marking message": a purely local bookmark list. The marked message ids are kept per
 * account + dialog in their own SharedPreferences file ("ox_marked_messages") as a JSON array, and
 * mirrored in memory because {@link #isMarkedCached(int, long, int)} is consulted while binding cells.
 * <p>
 * Hot-path friendly: lookups use primitive keys (no string concatenation, no boxing) and the
 * preferences file and its JSON arrays are loaded off the UI thread by {@link #preloadAsync()}.
 */
public class AyuMarkedMessages {

    public static final String PREFS_NAME = "ox_marked_messages";
    /** hard cap so a runaway list cannot grow without bounds */
    private static final int MAX_PER_DIALOG = 2000;

    private static volatile SharedPreferences preferences;
    /** account -> dialogId -> (messageId -> 1); SparseIntArray keeps ids sorted so the oldest id is index 0 */
    @SuppressWarnings("unchecked")
    private static final LongSparseArray<SparseIntArray>[] cache = new LongSparseArray[UserConfig.MAX_ACCOUNT_COUNT];
    private static final ArrayList<Runnable> loadListeners = new ArrayList<>();
    private static boolean preloadStarted;
    private static volatile boolean preloaded;
    /** bumped on every change so cells can cheaply detect that a cached "marked" flag is stale */
    private static volatile int version = 1;

    private AyuMarkedMessages() {
    }

    /** current change counter, compare against the value seen when a cell resolved its flag */
    public static int getVersion() {
        return version;
    }

    /** Load and parse once; cold cell lookups never wait for disk or JSON parsing. */
    public static void preloadAsync() {
        synchronized (cache) {
            if (preloadStarted || preloaded) {
                return;
            }
            preloadStarted = true;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                SharedPreferences prefs = prefs();
                if (prefs == null) {
                    return;
                }
                for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                    String key = entry.getKey();
                    int separator = key.indexOf('_');
                    if (separator <= 0 || !(entry.getValue() instanceof String)) {
                        continue;
                    }
                    final int account;
                    final long dialogId;
                    try {
                        account = Integer.parseInt(key.substring(0, separator));
                        dialogId = Long.parseLong(key.substring(separator + 1));
                    } catch (NumberFormatException ignore) {
                        continue;
                    }
                    if (account < 0 || account >= cache.length || dialogId == 0) {
                        continue;
                    }
                    // Parse outside the lock so scrolling cannot wait behind a JSON array.
                    SparseIntArray ids = parseIds((String) entry.getValue());
                    synchronized (cache) {
                        LongSparseArray<SparseIntArray> dialogs = cache[account];
                        if (dialogs == null) {
                            cache[account] = dialogs = new LongSparseArray<>();
                        }
                        // An explicit toggle/clear may already have resolved and changed this dialog.
                        if (dialogs.get(dialogId) == null) {
                            dialogs.put(dialogId, ids);
                        }
                    }
                }
                synchronized (cache) {
                    preloaded = true;
                    version++;
                }
                AndroidUtilities.runOnUIThread(() -> {
                    ArrayList<Runnable> listeners;
                    synchronized (cache) {
                        listeners = new ArrayList<>(loadListeners);
                        loadListeners.clear();
                    }
                    for (int i = 0; i < listeners.size(); i++) {
                        listeners.get(i).run();
                    }
                });
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                synchronized (cache) {
                    preloadStarted = false;
                }
            }
        });
    }

    /** A cell only subscribes during cold startup, and removes its callback when detached. */
    public static void addLoadListener(Runnable listener) {
        synchronized (cache) {
            if (preloaded) {
                return;
            }
            if (!loadListeners.contains(listener)) {
                loadListeners.add(listener);
            }
        }
        preloadAsync();
    }

    public static void removeLoadListener(Runnable listener) {
        synchronized (cache) {
            loadListeners.remove(listener);
        }
    }

    private static SparseIntArray parseIds(String stored) {
        SparseIntArray ids = new SparseIntArray();
        if (!TextUtils.isEmpty(stored)) {
            try {
                JSONArray array = new JSONArray(stored);
                for (int i = 0; i < array.length(); i++) {
                    int id = array.optInt(i);
                    if (id != 0) {
                        ids.put(id, 1);
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return ids;
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
        final SharedPreferences prefs = preloaded ? null : prefs();
        ids = prefs == null ? new SparseIntArray() : parseIds(prefs.getString(key(account, dialogId), null));
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

    /** Read-only cell lookup: no preferences, parsing, cache insertion, or work scheduling. */
    public static boolean isMarkedCached(int account, long dialogId, int messageId) {
        if (account < 0 || account >= cache.length || messageId == 0 || dialogId == 0) {
            return false;
        }
        synchronized (cache) {
            LongSparseArray<SparseIntArray> dialogs = cache[account];
            SparseIntArray ids = dialogs == null ? null : dialogs.get(dialogId);
            return ids != null && ids.indexOfKey(messageId) >= 0;
        }
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
                for (int i = ids.size() - 1; i >= 0; i--) {
                    result.add(ids.keyAt(i));
                }
            }
        }
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
