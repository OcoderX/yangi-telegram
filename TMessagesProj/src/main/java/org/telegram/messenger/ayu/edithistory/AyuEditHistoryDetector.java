package org.telegram.messenger.ayu.edithistory;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.ayu.AyuHistoryStorage;
import org.telegram.messenger.ayu.AyuMessagesController;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

/**
 * Catches the edits that never produced a {@code TL_updateEditMessage} for us - the app was closed,
 * the update was lost, or the dialog was only refreshed through a history load. Called from
 * {@code MessagesStorage.putMessages(messages_Messages, ...)} while still on the storage queue and
 * BEFORE the local rows are replaced, so the previous version is still readable.
 * <p>
 * //perf: the storage queue is the serial queue chat pagination waits on, so only the pre-write
 * {@code SELECT} runs there (it has to: a moment later putMessages overwrites those rows). The raw
 * blobs are copied out and the expensive part - {@code TLdeserialize}, the comparison and every
 * write - is handed to {@link AyuHistoryStorage#getQueue()}.
 */
public class AyuEditHistoryDetector {

    /** never compare more than this many messages of one batch */
    //perf: was 120; one history page is 50 messages, and 40 already covers the realistic case
    private static final int MAX_BATCH = 40;

    public static void onMessagesLoadedFromServer(int currentAccount, long dialogId, TLRPC.messages_Messages messages, int load_type, int mode) {
        try {
            if (messages == null || messages.messages == null || messages.messages.isEmpty()) {
                return;
            }
            // load_type == -2 is the "an edit update just arrived" path, already covered by
            // AyuMessagesController.onMessageEditedFromUpdate()
            if (load_type == -2 || mode != 0) {
                return;
            }
            if (!AyuEditHistoryConfig.isCaptureEnabled() || !AyuEditHistoryConfig.captureOnHistoryLoad) {
                return;
            }
            if (dialogId == 0 || DialogObject.isEncryptedDialog(dialogId)) {
                return;
            }
            if (UserConfig.getInstance(currentAccount).getClientUserId() == 0) {
                return;
            }

            final HashMap<Integer, TLRPC.Message> candidates = new HashMap<>();
            for (int a = 0, N = messages.messages.size(); a < N && candidates.size() < MAX_BATCH; a++) {
                final TLRPC.Message message = messages.messages.get(a);
                if (message == null || message instanceof TLRPC.TL_messageEmpty) {
                    continue;
                }
                if (message.id <= 0 || message.edit_date == 0) {
                    continue;
                }
                //perf: edit_date lives behind MESSAGE_FLAG_EDITED, so a message the server does not
                // flag as edited can never have a newer revision than our copy: do not even select it
                if ((message.flags & TLRPC.MESSAGE_FLAG_EDITED) == 0) {
                    continue;
                }
                if (message.action != null && !(message.action instanceof TLRPC.TL_messageActionEmpty)) {
                    continue;
                }
                candidates.put(message.id, message);
            }
            if (candidates.isEmpty()) {
                return;
            }

            //perf: storage queue does the SELECT and nothing else
            final ArrayList<StoredRow> stored = loadStoredRaw(currentAccount, dialogId, candidates.keySet());
            if (stored.isEmpty()) {
                return;
            }
            AyuHistoryStorage.getQueue().postRunnable(() -> compare(currentAccount, dialogId, candidates, stored));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** the still-serialized local copy of one message */
    private static class StoredRow {
        int id;
        byte[] data;
    }

    /**
     * Reads the current local copies straight from messages_v2; must run on the storage queue and
     * before putMessages replaces the rows. //perf: copies the blobs only, no TL deserialization.
     */
    private static ArrayList<StoredRow> loadStoredRaw(int currentAccount, long dialogId, Iterable<Integer> ids) {
        final ArrayList<StoredRow> result = new ArrayList<>();
        org.telegram.SQLite.SQLiteCursor cursor = null;
        try {
            final StringBuilder sb = new StringBuilder();
            for (Integer id : ids) {
                if (id == null) {
                    continue;
                }
                if (sb.length() != 0) {
                    sb.append(',');
                }
                sb.append(id.intValue());
            }
            if (sb.length() == 0) {
                return result;
            }
            final MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
            cursor = storage.getDatabase().queryFinalized(String.format(Locale.US,
                    "SELECT data, mid FROM messages_v2 WHERE uid = %d AND mid IN (%s)", dialogId, sb.toString()));
            while (cursor.next()) {
                final byte[] data = cursor.byteArrayValue(0);
                if (data == null || data.length < 4) {
                    continue;
                }
                final StoredRow row = new StoredRow();
                row.id = cursor.intValue(1);
                row.data = data;
                result.add(row);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                try {
                    cursor.dispose();
                } catch (Throwable ignore) {
                }
            }
        }
        return result;
    }

    /** //perf: runs on the edit-history queue, never on the storage queue */
    private static void compare(int currentAccount, long dialogId, HashMap<Integer, TLRPC.Message> candidates, ArrayList<StoredRow> stored) {
        try {
            final long selfId = UserConfig.getInstance(currentAccount).clientUserId;
            for (int a = 0; a < stored.size(); a++) {
                final StoredRow row = stored.get(a);
                final TLRPC.Message newMessage = candidates.get(row.id);
                if (newMessage == null) {
                    continue;
                }
                final TLRPC.Message oldMessage = deserialize(row, dialogId, selfId);
                if (oldMessage == null) {
                    continue;
                }
                if (oldMessage.edit_date == newMessage.edit_date) {
                    // the local copy is already the edited one
                    continue;
                }
                if (oldMessage.edit_date > newMessage.edit_date) {
                    // our copy is newer than what the server just sent (out of order response)
                    continue;
                }
                oldMessage.dialog_id = dialogId;
                if (newMessage.dialog_id == 0) {
                    newMessage.dialog_id = dialogId;
                }
                AyuMessagesController.getInstance().onMessageEdited(currentAccount, oldMessage, newMessage);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static TLRPC.Message deserialize(StoredRow row, long dialogId, long selfId) {
        if (row == null || row.data == null || row.data.length < 4) {
            return null;
        }
        SerializedData data = null;
        try {
            data = new SerializedData(row.data);
            final TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
            if (message == null) {
                return null;
            }
            message.readAttachPath(data, selfId);
            message.id = row.id;
            if (message.dialog_id == 0) {
                message.dialog_id = dialogId != 0 ? dialogId : MessageObject.getDialogId(message);
            }
            return message;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            if (data != null) {
                try {
                    data.cleanup();
                } catch (Throwable ignore) {
                }
            }
        }
    }
}
