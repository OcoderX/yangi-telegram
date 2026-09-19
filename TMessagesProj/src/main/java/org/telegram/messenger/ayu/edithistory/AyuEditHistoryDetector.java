package org.telegram.messenger.ayu.edithistory;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.ayu.AyuMessagesController;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

/**
 * Catches the edits that never produced a {@code TL_updateEditMessage} for us - the app was closed,
 * the update was lost, or the dialog was only refreshed through a history load. Called from
 * {@code MessagesStorage.putMessages(messages_Messages, ...)} while still on the storage queue and
 * BEFORE the local rows are replaced, so the previous version is still readable.
 */
public class AyuEditHistoryDetector {

    /** never compare more than this many messages of one batch */
    private static final int MAX_BATCH = 120;

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
                if (message.action != null && !(message.action instanceof TLRPC.TL_messageActionEmpty)) {
                    continue;
                }
                candidates.put(message.id, message);
            }
            if (candidates.isEmpty()) {
                return;
            }

            final ArrayList<TLRPC.Message> stored = loadStored(currentAccount, dialogId, candidates.keySet());
            for (int a = 0; a < stored.size(); a++) {
                final TLRPC.Message oldMessage = stored.get(a);
                final TLRPC.Message newMessage = candidates.get(oldMessage.id);
                if (newMessage == null) {
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

    /** reads the current local copies straight from messages_v2; must run on the storage queue */
    private static ArrayList<TLRPC.Message> loadStored(int currentAccount, long dialogId, Iterable<Integer> ids) {
        final ArrayList<TLRPC.Message> result = new ArrayList<>();
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
                final NativeByteBuffer data = cursor.byteBufferValue(0);
                if (data == null) {
                    continue;
                }
                try {
                    final TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    if (message != null) {
                        message.readAttachPath(data, UserConfig.getInstance(currentAccount).clientUserId);
                        message.id = cursor.intValue(1);
                        if (message.dialog_id == 0) {
                            message.dialog_id = dialogId != 0 ? dialogId : MessageObject.getDialogId(message);
                        }
                        result.add(message);
                    }
                } finally {
                    data.reuse();
                }
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
}
