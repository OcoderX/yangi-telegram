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
 * Revision counts loaded on the history queue and retained for recently read dialogs. Cell binds
 * only look up primitive keys in memory. Building a large snapshot never holds the lookup lock.
 */
public class AyuEditHistoryCache {

    /** ayuMessageHistoryUpdated type for a completed cache load; ids are null, no edit was stored. */
    public static final int NOTIFICATION_CACHE_LOADED = 2;

    private static final int LOADED = 1;
    private static final int LOADING = 2;
    private static final int MAX_DIALOGS = 128;
    // Soft limit: a single requested dialog and in-flight updates must remain complete.
    private static final int MAX_ENTRIES = 32768;
    private static final Object lock = new Object();

    private static class DialogCounts {
        // Published snapshots are never mutated. Updates, including zero-valued removals, win over
        // a snapshot that may have been read before the update arrived.
        SparseIntArray snapshot;
        final SparseIntArray updates = new SparseIntArray();
        int state;
        long dialogId;
        int entries;
        DialogCounts older, newer;
        boolean linked;
    }

    private static class AccountCounts {
        final LongSparseArray<DialogCounts> dialogs = new LongSparseArray<>();
        DialogCounts oldest, newest;
        int entries;
    }

    private static final AccountCounts[] counts = new AccountCounts[UserConfig.MAX_ACCOUNT_COUNT];
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
        AccountCounts account = counts[currentAccount];
        if (account == null) {
            if (!create) {
                return null;
            }
            account = new AccountCounts();
            counts[currentAccount] = account;
        }
        DialogCounts result = account.dialogs.get(dialogId);
        if (result == null && create) {
            if (account.dialogs.size() >= MAX_DIALOGS) {
                // Loading dialogs are not in the eviction list. If all slots are busy, the
                // next access can retry after one completes without flooding the history queue.
                if (account.oldest == null) {
                    return null;
                }
                evict(account, account.oldest);
            }
            result = new DialogCounts();
            result.dialogId = dialogId;
            account.dialogs.put(dialogId, result);
            touch(account, result);
        }
        return result;
    }

    // Intrusive recency list: no boxing, allocation or dialog scans on cell lookups. Only
    // non-loading dialogs participate, so eviction never discards updates awaiting a snapshot.
    private static void unlink(AccountCounts account, DialogCounts dialog) {
        if (!dialog.linked) {
            return;
        }
        if (dialog.older == null) account.oldest = dialog.newer;
        else dialog.older.newer = dialog.newer;
        if (dialog.newer == null) account.newest = dialog.older;
        else dialog.newer.older = dialog.older;
        dialog.older = dialog.newer = null;
        dialog.linked = false;
    }

    private static void touch(AccountCounts account, DialogCounts dialog) {
        if (dialog.state == LOADING || account.newest == dialog) {
            return;
        }
        unlink(account, dialog);
        dialog.older = account.newest;
        if (account.newest == null) account.oldest = dialog;
        else account.newest.newer = dialog;
        account.newest = dialog;
        dialog.linked = true;
    }

    private static void evict(AccountCounts account, DialogCounts dialog) {
        unlink(account, dialog);
        account.dialogs.remove(dialog.dialogId);
        account.entries -= dialog.entries;
    }

    /** Called only on mutation/publication, never while reading a cached message count. */
    private static void trimEntries(AccountCounts account, DialogCounts protectedDialog) {
        final int entries = protectedDialog.updates.size()
                + (protectedDialog.snapshot == null ? 0 : protectedDialog.snapshot.size());
        account.entries += entries - protectedDialog.entries;
        protectedDialog.entries = entries;
        while (account.entries > MAX_ENTRIES) {
            DialogCounts oldest = account.oldest;
            if (oldest == protectedDialog) oldest = oldest.newer;
            if (oldest == null) break;
            evict(account, oldest);
        }
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
                touch(counts[currentAccount], dialog);
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
            // Unseen dialogs need no live cache entry: the next load reads the stored revision.
            final DialogCounts dialog = dialogCounts(currentAccount, dialogId, false);
            if (dialog != null) {
                dialog.updates.put(messageId, count);
                trimEntries(counts[currentAccount], dialog);
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
                trimEntries(counts[currentAccount], dialog);
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
            final AccountCounts account = counts[currentAccount];
            if (account != null) {
                final DialogCounts dialog = account.dialogs.get(dialogId);
                if (dialog != null) evict(account, dialog);
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
            unlink(counts[currentAccount], loadingDialog);
            loadingDialog.state = LOADING;
        }
        AyuHistoryStorage.getQueue().postRunnable(() -> {
            try {
                synchronized (lock) {
                    if (dialogCounts(currentAccount, dialogId, false) != loadingDialog) {
                        return;
                    }
                }
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
                    touch(counts[currentAccount], loadingDialog);
                    trimEntries(counts[currentAccount], loadingDialog);
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
                        touch(counts[currentAccount], loadingDialog);
                        trimEntries(counts[currentAccount], loadingDialog);
                    }
                }
            }
        });
    }
}
