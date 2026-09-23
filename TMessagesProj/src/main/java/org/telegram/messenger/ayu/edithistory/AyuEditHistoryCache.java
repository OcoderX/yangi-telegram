package org.telegram.messenger.ayu.edithistory;

import android.util.SparseIntArray;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.ayu.AyuHistoryStorage;

import java.util.ArrayList;
import java.util.Map;

/**
 * In-memory "how many revisions does this message have" cache.
 * <p>
 * {@code TLRPC.Message.ayuEditedCount} only survives while the app is running, so the bubble would
 * lose its "edited (N)" mark after a restart. This cache is filled once per dialog with a single
 * grouped query on the AyuGram storage queue and then answers in O(1) from the UI thread.
 * Everything is keyed by {@code currentAccount} so multi-account setups never mix.
 * <p>
 * Hot-path friendly: primitive keys only (account index, dialog id, message id), no string
 * building and no boxing on the lookup path, which runs once per cell bind.
 */
public class AyuEditHistoryCache {

    private static final int LOADED = 1;
    private static final int LOADING = 2;

    private static final Object lock = new Object();
    /** account -> dialogId -> (messageId -> revisions) */
    @SuppressWarnings("unchecked")
    private static final LongSparseArray<SparseIntArray>[] counts = new LongSparseArray[UserConfig.MAX_ACCOUNT_COUNT];
    /** account -> dialogId -> LOADED / LOADING */
    @SuppressWarnings("unchecked")
    private static final LongSparseArray<Integer>[] dialogState = new LongSparseArray[UserConfig.MAX_ACCOUNT_COUNT];

    /** how many ids one refresh notification carries at most */
    private static final int MAX_NOTIFIED_IDS = 400;

    private static final Integer STATE_LOADED = LOADED;
    private static final Integer STATE_LOADING = LOADING;

    private static boolean validAccount(int currentAccount) {
        return currentAccount >= 0 && currentAccount < counts.length;
    }

    /** must be called under {@link #lock} */
    private static SparseIntArray dialogCounts(int currentAccount, long dialogId, boolean create) {
        LongSparseArray<SparseIntArray> dialogs = counts[currentAccount];
        if (dialogs == null) {
            if (!create) {
                return null;
            }
            dialogs = new LongSparseArray<>();
            counts[currentAccount] = dialogs;
        }
        SparseIntArray result = dialogs.get(dialogId);
        if (result == null && create) {
            result = new SparseIntArray();
            dialogs.put(dialogId, result);
        }
        return result;
    }

    /** must be called under {@link #lock} */
    private static int stateOf(int currentAccount, long dialogId) {
        LongSparseArray<Integer> states = dialogState[currentAccount];
        if (states == null) {
            return 0;
        }
        Integer state = states.get(dialogId);
        return state == null ? 0 : state;
    }

    /** must be called under {@link #lock} */
    private static void setState(int currentAccount, long dialogId, int state) {
        LongSparseArray<Integer> states = dialogState[currentAccount];
        if (states == null) {
            if (state == 0) {
                return;
            }
            states = new LongSparseArray<>();
            dialogState[currentAccount] = states;
        }
        if (state == 0) {
            states.remove(dialogId);
        } else {
            states.put(dialogId, state == LOADED ? STATE_LOADED : STATE_LOADING);
        }
    }

    /**
     * Non blocking. Returns 0 (and schedules a background load) until the dialog is warmed up.
     * Safe to call from the UI thread.
     */
    public static int getCount(int currentAccount, long dialogId, int messageId) {
        if (messageId <= 0 || dialogId == 0 || !validAccount(currentAccount)) {
            return 0;
        }
        final boolean needLoad;
        synchronized (lock) {
            final SparseIntArray dialog = dialogCounts(currentAccount, dialogId, false);
            if (dialog != null) {
                final int value = dialog.get(messageId, 0);
                if (value > 0) {
                    return value;
                }
            }
            needLoad = stateOf(currentAccount, dialogId) == 0;
        }
        if (needLoad) {
            requestDialogLoad(currentAccount, dialogId);
        }
        return 0;
    }

    public static boolean hasRevisions(int currentAccount, long dialogId, int messageId) {
        return getCount(currentAccount, dialogId, messageId) > 0;
    }

    /** called right after a new revision was stored */
    public static void put(int currentAccount, long dialogId, int messageId, int count) {
        if (messageId <= 0 || dialogId == 0 || count <= 0 || !validAccount(currentAccount)) {
            return;
        }
        synchronized (lock) {
            dialogCounts(currentAccount, dialogId, true).put(messageId, count);
        }
    }

    public static void remove(int currentAccount, long dialogId, int messageId) {
        if (!validAccount(currentAccount)) {
            return;
        }
        synchronized (lock) {
            final SparseIntArray dialog = dialogCounts(currentAccount, dialogId, false);
            if (dialog != null) {
                dialog.delete(messageId);
            }
        }
    }

    public static void invalidateAll() {
        synchronized (lock) {
            for (int a = 0; a < counts.length; a++) {
                counts[a] = null;
                dialogState[a] = null;
            }
        }
    }

    public static void invalidateDialog(int currentAccount, long dialogId) {
        if (!validAccount(currentAccount)) {
            return;
        }
        synchronized (lock) {
            setState(currentAccount, dialogId, 0);
            LongSparseArray<SparseIntArray> dialogs = counts[currentAccount];
            if (dialogs != null) {
                dialogs.remove(dialogId);
            }
        }
    }

    public static void requestDialogLoad(int currentAccount, long dialogId) {
        if (dialogId == 0 || !validAccount(currentAccount)) {
            return;
        }
        synchronized (lock) {
            if (stateOf(currentAccount, dialogId) != 0) {
                return;
            }
            setState(currentAccount, dialogId, LOADING);
        }
        AyuHistoryStorage.getQueue().postRunnable(() -> {
            ArrayList<Integer> changed = new ArrayList<>();
            boolean loaded = false;
            try {
                final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
                if (selfId == 0) {
                    return;
                }
                final Map<Integer, Integer> result = AyuHistoryStorage.getInstance().getRevisionCounts(selfId, dialogId);
                synchronized (lock) {
                    final SparseIntArray dialog = dialogCounts(currentAccount, dialogId, true);
                    for (Map.Entry<Integer, Integer> entry : result.entrySet()) {
                        final Integer messageId = entry.getKey();
                        final Integer count = entry.getValue();
                        if (messageId == null || count == null || count <= 0) {
                            continue;
                        }
                        dialog.put(messageId, count);
                        if (changed.size() < MAX_NOTIFIED_IDS) {
                            changed.add(messageId);
                        }
                    }
                }
                loaded = true;
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                synchronized (lock) {
                    setState(currentAccount, dialogId, loaded ? LOADED : 0);
                }
            }
            if (!changed.isEmpty()) {
                final ArrayList<Integer> ids = changed;
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        NotificationCenter.getInstance(currentAccount)
                                .postNotificationName(NotificationCenter.ayuMessageHistoryUpdated, dialogId, ids, 1);
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                });
            }
        });
    }
}
