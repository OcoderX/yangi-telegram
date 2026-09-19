package org.telegram.messenger.ayu.edithistory;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.ayu.AyuHistoryStorage;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory "how many revisions does this message have" cache.
 * <p>
 * {@code TLRPC.Message.ayuEditedCount} only survives while the app is running, so the bubble would
 * lose its "edited (N)" mark after a restart. This cache is filled once per dialog with a single
 * grouped query on the AyuGram storage queue and then answers in O(1) from the UI thread.
 * Everything is keyed by {@code currentAccount} so multi-account setups never mix.
 */
public class AyuEditHistoryCache {

    /** "account_dialog_message" -> revisions */
    private static final ConcurrentHashMap<String, Integer> counts = new ConcurrentHashMap<>();
    /** "account_dialog" of the dialogs whose counts are already in memory */
    private static final ConcurrentHashMap<String, Boolean> loadedDialogs = new ConcurrentHashMap<>();
    /** "account_dialog" of the dialogs currently being loaded */
    private static final ConcurrentHashMap<String, Boolean> loadingDialogs = new ConcurrentHashMap<>();

    /** how many ids one refresh notification carries at most */
    private static final int MAX_NOTIFIED_IDS = 400;

    private static String dialogKey(int currentAccount, long dialogId) {
        return currentAccount + "_" + dialogId;
    }

    private static String messageKey(int currentAccount, long dialogId, int messageId) {
        return currentAccount + "_" + dialogId + "_" + messageId;
    }

    /**
     * Non blocking. Returns 0 (and schedules a background load) until the dialog is warmed up.
     * Safe to call from the UI thread.
     */
    public static int getCount(int currentAccount, long dialogId, int messageId) {
        if (messageId <= 0 || dialogId == 0) {
            return 0;
        }
        final Integer value = counts.get(messageKey(currentAccount, dialogId, messageId));
        if (value != null) {
            return value;
        }
        if (!loadedDialogs.containsKey(dialogKey(currentAccount, dialogId))) {
            requestDialogLoad(currentAccount, dialogId);
        }
        return 0;
    }

    public static boolean hasRevisions(int currentAccount, long dialogId, int messageId) {
        return getCount(currentAccount, dialogId, messageId) > 0;
    }

    /** called right after a new revision was stored */
    public static void put(int currentAccount, long dialogId, int messageId, int count) {
        if (messageId <= 0 || dialogId == 0 || count <= 0) {
            return;
        }
        counts.put(messageKey(currentAccount, dialogId, messageId), count);
    }

    public static void remove(int currentAccount, long dialogId, int messageId) {
        counts.remove(messageKey(currentAccount, dialogId, messageId));
    }

    public static void invalidateAll() {
        counts.clear();
        loadedDialogs.clear();
        loadingDialogs.clear();
    }

    public static void invalidateDialog(int currentAccount, long dialogId) {
        final String dKey = dialogKey(currentAccount, dialogId);
        loadedDialogs.remove(dKey);
        loadingDialogs.remove(dKey);
        final String prefix = dKey + "_";
        for (String key : counts.keySet()) {
            if (key.startsWith(prefix)) {
                counts.remove(key);
            }
        }
    }

    public static void requestDialogLoad(int currentAccount, long dialogId) {
        if (dialogId == 0) {
            return;
        }
        final String dKey = dialogKey(currentAccount, dialogId);
        if (loadedDialogs.containsKey(dKey) || loadingDialogs.putIfAbsent(dKey, Boolean.TRUE) != null) {
            return;
        }
        AyuHistoryStorage.getQueue().postRunnable(() -> {
            ArrayList<Integer> changed = new ArrayList<>();
            try {
                final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
                if (selfId == 0) {
                    loadingDialogs.remove(dKey);
                    return;
                }
                final Map<Integer, Integer> loaded = AyuHistoryStorage.getInstance().getRevisionCounts(selfId, dialogId);
                for (Map.Entry<Integer, Integer> entry : loaded.entrySet()) {
                    final Integer messageId = entry.getKey();
                    final Integer count = entry.getValue();
                    if (messageId == null || count == null || count <= 0) {
                        continue;
                    }
                    counts.put(messageKey(currentAccount, dialogId, messageId), count);
                    if (changed.size() < MAX_NOTIFIED_IDS) {
                        changed.add(messageId);
                    }
                }
                loadedDialogs.put(dKey, Boolean.TRUE);
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                loadingDialogs.remove(dKey);
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
