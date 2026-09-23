package org.telegram.messenger.ayu.antidelete;

import android.os.SystemClock;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.ayu.AyuConfig;
import org.telegram.messenger.ayu.AyuMessagesController;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * ayu: the single gate that decides whether a message that is being deleted may be <b>kept</b> by the
 * anti-delete feature (archived in the AyuGram history DB and left on screen with the
 * "deleted by author" badge).
 * <p>
 * The feature must only ever fire for deletions the user did <i>not</i> start on this device. Everything
 * the user deletes here (delete for me, delete for everyone, "delete locally" on an existing ghost) has
 * to disappear root and branch. Since a deletion travels through several asynchronous hops
 * <pre>
 *   MessagesController.deleteMessages()
 *     -> MessagesStorage.markMessagesAsDeleted()        (archives the rows)
 *     -> NotificationCenter.messagesDeleted             (ChatActivity keeps the bubble)
 *     -> server request -> TL_updateDeleteMessages      (a second, delayed pass over the same ids)
 * </pre>
 * the ids are parked in a small, time-boxed registry here and every hop consults it.
 */
public final class AyuAntiDelete {

    /** how long a locally started deletion keeps suppressing the anti-delete for its ids */
    private static final long LOCAL_WINDOW_MS = 2 * 60 * 1000L;
    /** hard cap so a "clear 10k messages" burst can not grow the registry without bound */
    private static final int MAX_TRACKED = 8192;
    /** we only probe the anti-delete DB for a selection of a sane size (runs on the UI thread) */
    private static final int MAX_KEPT_PROBE = 256;

    /** key -> elapsedRealtime deadline */
    private static final HashMap<String, Long> localDeletions = new HashMap<>();

    private AyuAntiDelete() {
    }

    // =================================================================================
    //                      registry of locally initiated deletions
    // =================================================================================

    private static String key(int account, long dialogId, int messageId) {
        return account + ":" + dialogId + ":" + messageId;
    }

    /** key used when the deletion arrives without a dialog id (TL_updateDeleteMessages carries ids only) */
    private static String anyDialogKey(int account, int messageId) {
        return account + ":*:" + messageId;
    }

    /**
     * Remembers that the user started the deletion of these ids on this device.
     *
     * @param dialogId the dialog the ids belong to, 0 when unknown
     */
    public static void markLocalDeletion(int account, long dialogId, List<Integer> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }
        // updateDeleteMessages (private chats and basic groups) has no dialog id, so a second,
        // dialog-less key is needed. Channel ids are only unique per channel, and the channel
        // variant of the update always carries the channel id - so no wildcard key for those.
        boolean wildcard = true;
        if (DialogObject.isChatDialog(dialogId)) {
            try {
                TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
                wildcard = !ChatObject.isChannel(chat);
            } catch (Throwable ignore) {
            }
        }
        final long deadline = SystemClock.elapsedRealtime() + LOCAL_WINDOW_MS;
        synchronized (localDeletions) {
            if (localDeletions.size() > MAX_TRACKED) {
                pruneExpired(SystemClock.elapsedRealtime());
                if (localDeletions.size() > MAX_TRACKED) {
                    localDeletions.clear();
                }
            }
            for (int a = 0, N = messageIds.size(); a < N; a++) {
                final Integer id = messageIds.get(a);
                if (id == null || id <= 0) {
                    continue;
                }
                if (dialogId != 0) {
                    localDeletions.put(key(account, dialogId, id), deadline);
                }
                if (wildcard || dialogId == 0) {
                    localDeletions.put(anyDialogKey(account, id), deadline);
                }
            }
        }
    }

    /** true when this very id is being deleted because the user asked for it on this device */
    public static boolean isLocalDeletion(int account, long dialogId, int messageId) {
        if (messageId <= 0) {
            return false;
        }
        final long now = SystemClock.elapsedRealtime();
        synchronized (localDeletions) {
            if (localDeletions.isEmpty()) {
                return false;
            }
            if (dialogId != 0) {
                Long deadline = localDeletions.get(key(account, dialogId, messageId));
                if (deadline != null && deadline >= now) {
                    return true;
                }
            }
            Long deadline = localDeletions.get(anyDialogKey(account, messageId));
            return deadline != null && deadline >= now;
        }
    }

    /**
     * Drops every locally initiated id from {@code messageIds}.
     *
     * @return the ids that still have to go through the anti-delete (possibly the very same list)
     */
    public static ArrayList<Integer> filterLocalDeletions(int account, long dialogId, ArrayList<Integer> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return messageIds;
        }
        ArrayList<Integer> rest = null;
        for (int a = 0, N = messageIds.size(); a < N; a++) {
            final Integer id = messageIds.get(a);
            if (id == null || !isLocalDeletion(account, dialogId, id)) {
                continue;
            }
            if (rest == null) {
                rest = new ArrayList<>(messageIds);
            }
            rest.remove(id);
        }
        return rest == null ? messageIds : rest;
    }

    private static void pruneExpired(long now) {
        for (Iterator<Map.Entry<String, Long>> it = localDeletions.entrySet().iterator(); it.hasNext(); ) {
            final Long deadline = it.next().getValue();
            if (deadline == null || deadline < now) {
                it.remove();
            }
        }
    }

    // =================================================================================
    //                                   the keep gate
    // =================================================================================

    /**
     * Whether the anti-delete may keep this message once it is deleted. Consulted both when the
     * message is archived (AyuMessagesController) and when the bubble would stay on screen
     * (ChatActivity), so the two can never disagree.
     */
    public static boolean shouldKeepDeleted(int account, long dialogId, TLRPC.Message message) {
        if (!AyuConfig.saveDeletedMessages || message == null || message.id <= 0) {
            return false;
        }
        long did = dialogId != 0 ? dialogId : (message.dialog_id != 0 ? message.dialog_id : MessageObject.getDialogId(message));
        if (did == 0 || DialogObject.isEncryptedDialog(did)) {
            return false;
        }
        if (AyuAntiDeleteConfig.isExcluded(did)) {
            return false;
        }
        if (isLocalDeletion(account, did, message.id)) {
            // the user deleted it here - never keep a ghost of it
            return false;
        }
        if (!AyuAntiDeleteConfig.keepOwnDeleted && isOwnMessage(account, message)) {
            // our own message deleted somewhere (usually "delete for everyone" from another device):
            // the user does not want to see their own message come back as "deleted by author"
            return false;
        }
        return true;
    }

    /** {@link #shouldKeepDeleted} for an object that is already on screen */
    public static boolean shouldKeepInChat(int account, long dialogId, MessageObject messageObject) {
        return messageObject != null && shouldKeepDeleted(account, dialogId, messageObject.messageOwner);
    }

    /** true when the message was written by this account */
    public static boolean isOwnMessage(int account, TLRPC.Message message) {
        if (message == null) {
            return false;
        }
        if (message.out) {
            return true;
        }
        try {
            final long selfId = UserConfig.getInstance(account).getClientUserId();
            return selfId != 0 && message.from_id instanceof TLRPC.TL_peerUser && message.from_id.user_id == selfId;
        } catch (Throwable ignore) {
            return false;
        }
    }

    // =================================================================================
    //                       messages that only exist in our archive
    // =================================================================================

    /**
     * Picks the ids of {@code messageIds} whose only copy is the anti-delete archive (the bubbles drawn
     * with the "deleted by author" badge) and forgets them: the stored row is dropped so the ghost can
     * not come back when the history is reloaded.
     * <p>
     * The caller must not send those ids to the server - the server deleted them long ago, and for
     * channels the request would fail with MESSAGE_ID_INVALID.
     *
     * @return the ids that were kept locally only, or null when there is none
     */
    public static ArrayList<Integer> extractLocallyKept(int account, long dialogId, List<Integer> messageIds) {
        if (messageIds == null || messageIds.isEmpty() || dialogId == 0) {
            return null;
        }
        if (DialogObject.isEncryptedDialog(dialogId) || messageIds.size() > MAX_KEPT_PROBE) {
            return null;
        }
        ArrayList<Integer> kept = null;
        try {
            final AyuMessagesController controller = AyuMessagesController.getInstance();
            // one index-covered COUNT instead of one lookup per selected id: dialogs without a
            // single kept message (the normal case) cost nothing on the UI thread
            if (controller.getDeletedMessagesCount(account, dialogId) <= 0) {
                return null;
            }
            for (int a = 0, N = messageIds.size(); a < N; a++) {
                final Integer id = messageIds.get(a);
                if (id == null || id <= 0) {
                    continue;
                }
                if (controller.isDeletedMessage(account, dialogId, id)) {
                    if (kept == null) {
                        kept = new ArrayList<>();
                    }
                    kept.add(id);
                }
            }
            if (kept != null) {
                controller.forgetDeletedMessages(account, dialogId, kept);
            }
        } catch (Throwable ignore) {
            return kept;
        }
        return kept;
    }
}
