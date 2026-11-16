package org.telegram.messenger.ayu.edithistory;

import android.util.SparseIntArray;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.ayu.AyuHistoryStorage;

import java.util.Arrays;
import java.util.Map;

/**
 * Revision counts loaded once per dialog on the history queue. Cell binds only look up primitive
 * keys in memory. Building a large dialog snapshot never holds the lock used by those lookups.
 */
public class AyuEditHistoryCache {

    /** ayuMessageHistoryUpdated type for a completed cache load; ids are null, no edit was stored. */
    public static final int NOTIFICATION_CACHE_LOADED = 2;

    private static final int LOADED = 1;
    private static final int LOADING = 2;
    private static final Object lock = new Object();

    private static class DialogCounts {
        // Published snapshots are never mutated. Updates, including zero-valued removals, win over
        // a snapshot that may have been read before the update arrived.
        SparseIntArray snapshot;
        final SparseIntArray updates = new SparseIntArray();
        int state;
    }

    @SuppressWarnings("unchecked")
    private static final LongSparseArray<DialogCounts>[] counts = new LongSparseArray[UserConfig.MAX_ACCOUNT_COUNT];
    private static final long[] accountOwners = new long[UserConfig.MAX_ACCOUNT_COUNT];

    private static boolean validAccount(int currentAccount) {
        return currentAccount >= 0 && currentAccount < counts.length;
    }

    /** Must be called under {@link #lock}; account slots can be reused after logout. */
    private static DialogCounts dialogCounts(int currentAccount, long dialogId, boolean create) {
        final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
        if (accountOwners[currentAccount] != selfId) {
            counts[currentAccount] = null;
            accountOwners[currentAccount] = selfId;
        }
        if (selfId == 0) {
            return null;
        }
        LongSparseArray<DialogCounts> dialogs = counts[currentAccount];
        if (dialogs == null) {
            if (!create) {
                return null;
            }
            dialogs = new LongSparseArray<>();
            counts[currentAccount] = dialogs;
        }
        DialogCounts result = dialogs.get(dialogId);
        if (result == null && create) {
            result = new DialogCounts();
            dialogs.put(dialogId, result);
        }
        return result;
    }

    /** Returns immediately; a missing dialog is loaded in the background. */
    public static int getCount(int currentAccount, long dialogId, int messageId) {
        if (messageId <= 0 || dialogId == 0 || !validAccount(currentAccount)) {
            return 0;
        }
        final boolean needLoad;
        int value = 0;
        synchronized (lock) {
            final DialogCounts dialog = dialogCounts(currentAccount, dialogId, false);
            if (dialog != null) {
                final int updateIndex = dialog.updates.indexOfKey(messageId);
                if (updateIndex >= 0) {
                    value = dialog.updates.valueAt(updateIndex);
                } else if (dialog.snapshot != null) {
                    value = dialog.snapshot.get(messageId, 0);
                }
            }
            needLoad = dialog == null || dialog.state == 0;
        }
        if (needLoad) {
            requestDialogLoad(currentAccount, dialogId);
        }
        return value;
    }

    public static boolean hasRevisions(int currentAccount, long dialogId, int messageId) {
        return getCount(currentAccount, dialogId, messageId) > 0;
    }

    /** Called right after a new revision was stored. */
    public static void put(int currentAccount, long dialogId, int messageId, int count) {
        if (messageId <= 0 || dialogId == 0 || count <= 0 || !validAccount(currentAccount)) {
            return;
        }
        synchronized (lock) {
            final DialogCounts dialog = dialogCounts(currentAccount, dialogId, true);
            if (dialog != null) {
                dialog.updates.put(messageId, count);
            }
        }
    }

    public static void remove(int currentAccount, long dialogId, int messageId) {
        if (messageId <= 0 || dialogId == 0 || !validAccount(currentAccount)) {
            return;
        }
        synchronized (lock) {
            final DialogCounts dialog = dialogCounts(currentAccount, dialogId, false);
            if (dialog != null) {
                dialog.updates.put(messageId, 0);
            }
        }
    }

    public static void invalidateAll() {
        synchronized (lock) {
            for (int a = 0; a < counts.length; a++) {
                counts[a] = null;
                accountOwners[a] = 0;
            }
        }
    }

    public static void invalidateDialog(int currentAccount, long dialogId) {
        if (!validAccount(currentAccount)) {
            return;
        }
        synchronized (lock) {
            final LongSparseArray<DialogCounts> dialogs = counts[currentAccount];
            if (dialogs != null) {
                dialogs.remove(dialogId);
            }
        }
    }

    public static void requestDialogLoad(int currentAccount, long dialogId) {
        if (dialogId == 0 || !validAccount(currentAccount)) {
            return;
        }
        final DialogCounts loadingDialog;
        final long selfId;
        synchronized (lock) {
            loadingDialog = dialogCounts(currentAccount, dialogId, true);
            if (loadingDialog == null || loadingDialog.state != 0) {
                return;
            }
            selfId = accountOwners[currentAccount];
            loadingDialog.state = LOADING;
        }
        AyuHistoryStorage.getQueue().postRunnable(() -> {
            try {
                final Map<Integer, Integer> result = AyuHistoryStorage.getInstance().getRevisionCounts(selfId, dialogId);
                final int[] messageIds = new int[result.size()];
                int size = 0;
                for (Map.Entry<Integer, Integer> entry : result.entrySet()) {
                    if (entry.getKey() != null && entry.getKey() > 0 && entry.getValue() != null && entry.getValue() > 0) {
                        messageIds[size++] = entry.getKey();
                    }
                }
                Arrays.sort(messageIds, 0, size);
                final SparseIntArray snapshot = new SparseIntArray(size);
                for (int a = 0; a < size; a++) {
                    // Appending ordered keys avoids shifting the full array for each map entry.
                    snapshot.append(messageIds[a], result.get(messageIds[a]));
                }
                synchronized (lock) {
                    if (dialogCounts(currentAccount, dialogId, false) != loadingDialog) {
                        return;
                    }
                    loadingDialog.snapshot = snapshot;
                    loadingDialog.state = LOADED;
                }
                AndroidUtilities.runOnUIThread(() -> {
                    synchronized (lock) {
                        if (dialogCounts(currentAccount, dialogId, false) != loadingDialog) {
                            return;
                        }
                    }
                    // Warming a cache is not a new edit: sync observers must not reread and resend
                    // the entire stored history. The chat refreshes only its already loaded rows.
                    NotificationCenter.getInstance(currentAccount).postNotificationName(
                            NotificationCenter.ayuMessageHistoryUpdated, dialogId, null, NOTIFICATION_CACHE_LOADED);
                });
            } catch (Throwable e) {
                FileLog.e(e);
                synchronized (lock) {
                    if (dialogCounts(currentAccount, dialogId, false) == loadingDialog) {
                        loadingDialog.state = 0;
                    }
                }
            }
        });
    }
}
