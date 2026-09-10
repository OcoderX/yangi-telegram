package org.telegram.messenger.ayu;

import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.ayu.entities.AyuMessageBase;
import org.telegram.messenger.ayu.entities.DeletedMessage;
import org.telegram.messenger.ayu.entities.EditedMessage;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CONTRACT (implemented by the "history storage" agent, consumed by UI agents).
 * Persists deleted messages / edit history / self-destructing media in a dedicated SQLite DB
 * (AyuConstants.DB_NAME) that survives cache clears. All heavy work runs on the dedicated
 * {@code ayuStorageQueue}; async callbacks are delivered on the UI thread.
 */
public class AyuMessagesController {

    /** biggest file we are willing to download ourselves just to keep a copy of a deleted message */
    private static final long MAX_AUTO_DOWNLOAD_SIZE = 25L * 1024L * 1024L;

    private static final int DELETED_CACHE_SIZE = 512;

    private static volatile AyuMessagesController instance;

    /** small LRU so ChatActivity can ask "is this deleted?" for every visible cell */
    private final LinkedHashMap<String, Boolean> deletedCache = new LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > DELETED_CACHE_SIZE;
        }
    };

    private final HashMap<String, PendingMedia> pendingDownloads = new HashMap<>();
    private final boolean[] observerAdded = new boolean[UserConfig.MAX_ACCOUNT_COUNT];

    public static AyuMessagesController getInstance() {
        if (instance == null) {
            synchronized (AyuMessagesController.class) {
                if (instance == null) {
                    instance = new AyuMessagesController();
                }
            }
        }
        return instance;
    }

    /** opens (and creates on first run) the database off the main thread */
    public void warmUp() {
        AyuHistoryStorage.getQueue().postRunnable(() -> {
            try {
                AyuHistoryStorage.getInstance().getDatabaseSize();
                AyuHistoryStorage.getInstance().isDeleted(0, 0, 0);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    // =================================================================================
    //                                    write path
    // =================================================================================

    /** Called before messages get removed locally because of TL_updateDeleteMessages / DeleteChannelMessages. */
    public void onMessagesDeleted(int currentAccount, long dialogId, ArrayList<Integer> messageIds, long channelId) {
        if (!AyuConfig.saveDeletedMessages || messageIds == null || messageIds.isEmpty()) {
            return;
        }
        final long did = dialogId != 0 ? dialogId : (channelId != 0 ? -channelId : 0);
        if (did == 0) {
            return;
        }
        if (AyuState.consumeSkipNextSave()) {
            return;
        }
        final ArrayList<Integer> ids = new ArrayList<>(messageIds);
        MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
        storage.getStorageQueue().postRunnable(() -> {
            ArrayList<TLRPC.Message> messages = loadMessagesFromStorage(currentAccount, did, ids);
            if (!messages.isEmpty()) {
                saveDeletedInternal(currentAccount, messages);
            }
        });
    }

    /** Called with the already-loaded old message objects that are about to be deleted (preferred path). */
    public void onMessagesDeleted(int currentAccount, ArrayList<TLRPC.Message> oldMessages) {
        if (!AyuConfig.saveDeletedMessages || oldMessages == null || oldMessages.isEmpty()) {
            return;
        }
        if (AyuState.consumeSkipNextSave()) {
            return;
        }
        final ArrayList<TLRPC.Message> copy = new ArrayList<>(oldMessages);
        saveDeletedInternal(currentAccount, copy);
    }

    private void saveDeletedInternal(int currentAccount, ArrayList<TLRPC.Message> oldMessages) {
        AyuHistoryStorage.getQueue().postRunnable(() -> {
            try {
                final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
                if (selfId == 0) {
                    return;
                }
                ArrayList<DeletedMessage> rows = new ArrayList<>();
                HashMap<Long, ArrayList<Integer>> byDialog = new HashMap<>();
                for (int a = 0; a < oldMessages.size(); a++) {
                    TLRPC.Message message = oldMessages.get(a);
                    if (!shouldSave(currentAccount, message)) {
                        continue;
                    }
                    DeletedMessage row = new DeletedMessage();
                    fillRow(currentAccount, row, message, selfId);
                    if (isMediaSaveAllowed(currentAccount, row.dialogId)) {
                        saveMediaFor(currentAccount, message, row);
                    }
                    rows.add(row);
                    long did = row.dialogId;
                    ArrayList<Integer> list = byDialog.get(did);
                    if (list == null) {
                        byDialog.put(did, list = new ArrayList<>());
                    }
                    list.add(row.messageId);
                }
                if (rows.isEmpty()) {
                    return;
                }
                AyuHistoryStorage.getInstance().insertDeletedBatch(rows);
                // late media (needs downloading) - now that the rows exist we know their fakeId
                for (int a = 0; a < rows.size(); a++) {
                    DeletedMessage row = rows.get(a);
                    if (TextUtils.isEmpty(row.mediaPath) && row.documentType != AyuConstants.DOCUMENT_TYPE_NONE) {
                        DeletedMessage stored = AyuHistoryStorage.getInstance().getDeletedMessage(row.userId, row.dialogId, row.messageId);
                        if (stored != null) {
                            scheduleDownloadIfPossible(currentAccount, oldMessages, stored);
                        }
                    }
                }
                synchronized (deletedCache) {
                    for (int a = 0; a < rows.size(); a++) {
                        DeletedMessage row = rows.get(a);
                        deletedCache.put(cacheKey(currentAccount, row.dialogId, row.messageId), Boolean.TRUE);
                    }
                }
                for (Map.Entry<Long, ArrayList<Integer>> entry : byDialog.entrySet()) {
                    final long did = entry.getKey();
                    final ArrayList<Integer> ids = entry.getValue();
                    AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(currentAccount)
                            .postNotificationName(NotificationCenter.ayuMessageHistoryUpdated, did, ids, 0));
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /** Called when an edit update arrives; oldMessage is the previous version, newMessage the edited one. */
    public void onMessageEdited(int currentAccount, TLRPC.Message oldMessage, TLRPC.Message newMessage) {
        if (!AyuConfig.saveMessagesHistory || oldMessage == null || newMessage == null) {
            return;
        }
        if (!shouldSave(currentAccount, oldMessage)) {
            return;
        }
        if (!hasMeaningfulChange(oldMessage, newMessage)) {
            return;
        }
        final long dialogId = newMessage.dialog_id != 0 ? newMessage.dialog_id : MessageObject.getDialogId(newMessage);
        AyuHistoryStorage.getQueue().postRunnable(() -> {
            try {
                final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
                if (selfId == 0) {
                    return;
                }
                EditedMessage row = new EditedMessage();
                fillRow(currentAccount, row, oldMessage, selfId);
                if (row.dialogId == 0) {
                    row.dialogId = dialogId;
                }
                if (isMediaSaveAllowed(currentAccount, row.dialogId)) {
                    saveMediaFor(currentAccount, oldMessage, row);
                }
                AyuHistoryStorage.getInstance().insertEdited(row);
                final int count = AyuHistoryStorage.getInstance().getRevisionsCount(selfId, row.dialogId, row.messageId);
                newMessage.ayuEditedCount = count;
                final ArrayList<Integer> ids = new ArrayList<>();
                ids.add(row.messageId);
                AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(currentAccount)
                        .postNotificationName(NotificationCenter.ayuMessageHistoryUpdated, row.dialogId, ids, 1));
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /**
     * Hook for the TL_updateEditMessage / TL_updateEditChannelMessage path: the previous version is
     * still in messages_v2, so it is loaded on the storage queue (which is ordered before the
     * putMessages() that will overwrite it later in the same processUpdateArray pass).
     */
    public void onMessageEditedFromUpdate(int currentAccount, TLRPC.Message newMessage) {
        if (!AyuConfig.saveMessagesHistory || newMessage == null || newMessage.id <= 0) {
            return;
        }
        final long dialogId = newMessage.dialog_id != 0 ? newMessage.dialog_id : MessageObject.getDialogId(newMessage);
        if (dialogId == 0 || DialogObject.isEncryptedDialog(dialogId)) {
            return;
        }
        final int messageId = newMessage.id;
        MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
        storage.getStorageQueue().postRunnable(() -> {
            ArrayList<Integer> ids = new ArrayList<>();
            ids.add(messageId);
            ArrayList<TLRPC.Message> old = loadMessagesFromStorage(currentAccount, dialogId, ids);
            if (old.isEmpty()) {
                return;
            }
            TLRPC.Message oldMessage = old.get(0);
            oldMessage.dialog_id = dialogId;
            onMessageEdited(currentAccount, oldMessage, newMessage);
        });
    }

    /** Called when a TTL / view-once media is opened so a permanent copy is stored. */
    public void onSelfDestructMediaOpened(int currentAccount, MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return;
        }
        onSelfDestructMediaOpened(currentAccount, messageObject.messageOwner, false);
    }

    /**
     * @param copySynchronously when true the media file is copied on the calling thread. Needed for the
     *                          MessagesStorage.emptyMessagesMedia() hook because the original file is
     *                          deleted right after that call returns.
     */
    public void onSelfDestructMediaOpened(int currentAccount, TLRPC.Message message, boolean copySynchronously) {
        if (!AyuConfig.saveSelfDestructingMedia || message == null) {
            return;
        }
        final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
        if (selfId == 0) {
            return;
        }
        final long dialogId = message.dialog_id != 0 ? message.dialog_id : MessageObject.getDialogId(message);
        if (dialogId == 0) {
            return;
        }
        if (copySynchronously) {
            // called from the storage queue right before the physical file is wiped
            if (AyuHistoryStorage.getInstance().getTTLMedia(selfId, dialogId, message.id) != null) {
                return;
            }
            final DeletedMessage row = new DeletedMessage();
            fillRow(currentAccount, row, message, selfId);
            if (row.dialogId == 0) {
                row.dialogId = dialogId;
            }
            saveMediaFor(currentAccount, message, row);
            AyuHistoryStorage.getQueue().postRunnable(() -> {
                AyuHistoryStorage.getInstance().insertTTLMedia(row);
                postHistoryUpdated(currentAccount, row.dialogId, row.messageId, 2);
            });
        } else {
            AyuHistoryStorage.getQueue().postRunnable(() -> {
                if (AyuHistoryStorage.getInstance().getTTLMedia(selfId, dialogId, message.id) != null) {
                    return;
                }
                DeletedMessage row = new DeletedMessage();
                fillRow(currentAccount, row, message, selfId);
                if (row.dialogId == 0) {
                    row.dialogId = dialogId;
                }
                saveMediaFor(currentAccount, message, row);
                AyuHistoryStorage.getInstance().insertTTLMedia(row);
                postHistoryUpdated(currentAccount, row.dialogId, row.messageId, 2);
            });
        }
    }

    /** true when we already keep a permanent copy of this self-destructing media */
    public boolean hasSavedSelfDestructMedia(int currentAccount, long dialogId, int messageId) {
        try {
            final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
            DeletedMessage row = AyuHistoryStorage.getInstance().getTTLMedia(selfId, dialogId, messageId);
            return row != null && !TextUtils.isEmpty(row.mediaPath) && new File(row.mediaPath).exists();
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /** rebuilds the saved self-destructing message (attachPath already points at our permanent copy) */
    public TLRPC.Message getSavedSelfDestructMessage(int currentAccount, long dialogId, int messageId) {
        try {
            final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
            DeletedMessage row = AyuHistoryStorage.getInstance().getTTLMedia(selfId, dialogId, messageId);
            if (row == null) {
                return null;
            }
            return restoreMessage(row);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    public String getSavedSelfDestructMediaPath(int currentAccount, long dialogId, int messageId) {
        try {
            final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
            DeletedMessage row = AyuHistoryStorage.getInstance().getTTLMedia(selfId, dialogId, messageId);
            if (row != null && !TextUtils.isEmpty(row.mediaPath) && new File(row.mediaPath).exists()) {
                return row.mediaPath;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return null;
    }

    /**
     * Lets the UI delete messages locally without them being re-added to the AyuGram history
     * (used by "delete locally" on already-deleted bubbles). Also drops the stored rows.
     */
    public void deleteMessagesLocallyWithoutSaving(int currentAccount, long dialogId, ArrayList<Integer> messageIds) {
        AyuState.skipNextSave = true;
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }
        final ArrayList<Integer> ids = new ArrayList<>(messageIds);
        final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
        AyuHistoryStorage.getQueue().postRunnable(() -> {
            AyuHistoryStorage.getInstance().deleteDeletedByIds(selfId, dialogId, ids);
            synchronized (deletedCache) {
                for (int a = 0; a < ids.size(); a++) {
                    deletedCache.remove(cacheKey(currentAccount, dialogId, ids.get(a)));
                }
            }
            AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(currentAccount)
                    .postNotificationName(NotificationCenter.ayuMessageHistoryUpdated, dialogId, ids, 0));
        });
    }

    // =================================================================================
    //                                     read path
    // =================================================================================

    public boolean hasAnyRevisions(int currentAccount, long dialogId, int messageId) {
        try {
            final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
            return AyuHistoryStorage.getInstance().hasRevisions(selfId, dialogId, messageId);
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    public int getRevisionsCount(int currentAccount, long dialogId, int messageId) {
        try {
            final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
            return AyuHistoryStorage.getInstance().getRevisionsCount(selfId, dialogId, messageId);
        } catch (Throwable e) {
            FileLog.e(e);
            return 0;
        }
    }

    /** Edit history for one message, oldest first. */
    public List<EditedMessage> getRevisions(int currentAccount, long dialogId, int messageId) {
        try {
            final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
            return AyuHistoryStorage.getInstance().getRevisions(selfId, dialogId, messageId);
        } catch (Throwable e) {
            FileLog.e(e);
            return new ArrayList<>();
        }
    }

    /** Deleted messages of a dialog (and optional topic) with ids in (minId, maxId] range, newest first. */
    public List<DeletedMessage> getDeletedMessages(int currentAccount, long dialogId, long topicId, int minId, int maxId, int limit) {
        try {
            final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
            return AyuHistoryStorage.getInstance().getDeletedMessages(selfId, dialogId, topicId, minId, maxId, limit);
        } catch (Throwable e) {
            FileLog.e(e);
            return new ArrayList<>();
        }
    }

    /** same as {@link #getDeletedMessages} but already converted into ready-to-use TLRPC.Message objects */
    public ArrayList<TLRPC.Message> getDeletedMessagesAsObjects(int currentAccount, long dialogId, long topicId, int minId, int maxId, int limit) {
        ArrayList<TLRPC.Message> result = new ArrayList<>();
        List<DeletedMessage> rows = getDeletedMessages(currentAccount, dialogId, topicId, minId, maxId, limit);
        for (int a = 0; a < rows.size(); a++) {
            TLRPC.Message message = toTLMessage(rows.get(a));
            if (message != null) {
                result.add(message);
            }
        }
        return result;
    }

    public int getDeletedMessagesCount(int currentAccount, long dialogId) {
        try {
            final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
            return AyuHistoryStorage.getInstance().getDeletedMessagesCount(selfId, dialogId);
        } catch (Throwable e) {
            FileLog.e(e);
            return 0;
        }
    }

    /** cheap, cached "was this message deleted on the server?" check for ChatActivity/ChatMessageCell */
    public boolean isDeletedMessage(int currentAccount, long dialogId, int msgId) {
        if (msgId <= 0 || dialogId == 0) {
            return false;
        }
        final String key = cacheKey(currentAccount, dialogId, msgId);
        synchronized (deletedCache) {
            Boolean cached = deletedCache.get(key);
            if (cached != null) {
                return cached;
            }
        }
        boolean value;
        try {
            final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
            value = AyuHistoryStorage.getInstance().isDeleted(selfId, dialogId, msgId);
        } catch (Throwable e) {
            FileLog.e(e);
            value = false;
        }
        synchronized (deletedCache) {
            deletedCache.put(key, value);
        }
        return value;
    }

    public void invalidateDeletedCache() {
        synchronized (deletedCache) {
            deletedCache.clear();
        }
    }

    /** Builds a TLRPC.Message (with ayuDeleted=true) from a stored row so it can be inserted into ChatActivity. */
    public TLRPC.Message toTLMessage(DeletedMessage row) {
        TLRPC.Message message = restoreMessage(row);
        if (message != null) {
            message.ayuDeleted = true;
        }
        return message;
    }

    public TLRPC.Message toTLMessage(EditedMessage row) {
        return restoreMessage(row);
    }

    private TLRPC.Message restoreMessage(AyuMessageBase row) {
        if (row == null) {
            return null;
        }
        TLRPC.Message message = null;
        if (row.messageSerialized != null && row.messageSerialized.length > 4) {
            try {
                SerializedData data = new SerializedData(row.messageSerialized);
                message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                data.cleanup();
            } catch (Throwable e) {
                FileLog.e(e);
                message = null;
            }
        }
        if (message == null) {
            message = buildFallbackMessage(row);
        }
        if (message == null) {
            return null;
        }
        message.id = row.messageId;
        message.dialog_id = row.dialogId;
        if (message.date == 0) {
            message.date = row.date;
        }
        if (message.peer_id == null) {
            message.peer_id = peerFromDialogId(row.dialogId);
        }
        if (message.from_id == null && row.fromId != 0) {
            message.from_id = peerFromDialogId(row.fromId);
        }
        if (!AyuConfig.saveFormatting && message.entities != null) {
            message.entities.clear();
        }
        if (!AyuConfig.saveReactions) {
            message.reactions = null;
        }
        // make the locally saved copy win over the (now gone) server file
        if (!TextUtils.isEmpty(row.mediaPath)) {
            File file = new File(row.mediaPath);
            if (file.exists() && file.length() > 0) {
                message.attachPath = row.mediaPath;
            }
        }
        return message;
    }

    private TLRPC.Message buildFallbackMessage(AyuMessageBase row) {
        try {
            TLRPC.TL_message message = new TLRPC.TL_message();
            message.id = row.messageId;
            message.dialog_id = row.dialogId;
            message.date = row.date;
            message.edit_date = row.editDate;
            message.views = row.views;
            message.message = row.text != null ? row.text : "";
            message.peer_id = peerFromDialogId(row.dialogId);
            if (row.fromId != 0) {
                message.from_id = peerFromDialogId(row.fromId);
            }
            message.grouped_id = row.groupedId;
            if (row.textEntities != null && row.textEntities.length > 0) {
                ArrayList<TLRPC.MessageEntity> entities = deserializeEntities(row.textEntities);
                if (entities != null) {
                    message.entities = entities;
                }
            }
            if (row.reactionsSerialized != null && row.reactionsSerialized.length > 4) {
                try {
                    SerializedData data = new SerializedData(row.reactionsSerialized);
                    message.reactions = TLRPC.TL_messageReactions.TLdeserialize(data, data.readInt32(false), false);
                    data.cleanup();
                } catch (Throwable ignore) {
                }
            }
            if (row.replyMessageId != 0) {
                TLRPC.TL_messageReplyHeader reply = new TLRPC.TL_messageReplyHeader();
                reply.flags = row.replyFlags;
                reply.reply_to_msg_id = row.replyMessageId;
                reply.reply_to_top_id = row.replyTopId;
                reply.forum_topic = row.replyForumTopic;
                if (row.replyPeerId != 0) {
                    reply.reply_to_peer_id = peerFromDialogId(row.replyPeerId);
                }
                message.reply_to = reply;
            }
            message.media = new TLRPC.TL_messageMediaEmpty();
            return message;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    // =================================================================================
    //                                    maintenance
    // =================================================================================

    public long getDatabaseSize() {
        try {
            return AyuHistoryStorage.getInstance().getDatabaseSize();
        } catch (Throwable e) {
            FileLog.e(e);
            return 0;
        }
    }

    public void clearDatabase(Runnable onDone) {
        AyuHistoryStorage.getQueue().postRunnable(() -> {
            try {
                AyuHistoryStorage.getInstance().clear();
            } catch (Throwable e) {
                FileLog.e(e);
            }
            invalidateDeletedCache();
            if (onDone != null) {
                AndroidUtilities.runOnUIThread(onDone);
            }
        });
    }

    public void deleteDeletedMessage(long fakeId) {
        AyuHistoryStorage.getQueue().postRunnable(() -> {
            AyuHistoryStorage.getInstance().deleteDeleted(fakeId);
            invalidateDeletedCache();
        });
    }

    /** forgets one stored deleted message (used by the "delete locally" action on a deleted bubble) */
    public void deleteDeletedMessage(int currentAccount, long dialogId, int messageId) {
        AyuState.skipNextSave = true;
        final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
        final ArrayList<Integer> ids = new ArrayList<>();
        ids.add(messageId);
        synchronized (deletedCache) {
            deletedCache.remove(cacheKey(currentAccount, dialogId, messageId));
        }
        AyuHistoryStorage.getQueue().postRunnable(() -> {
            AyuHistoryStorage.getInstance().deleteDeletedByIds(selfId, dialogId, ids);
            AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(currentAccount)
                    .postNotificationName(NotificationCenter.ayuMessageHistoryUpdated, dialogId, ids, 0));
        });
    }

    // =================================================================================
    //                                     internals
    // =================================================================================

    private static String cacheKey(int account, long dialogId, int messageId) {
        return account + "_" + dialogId + "_" + messageId;
    }

    private void postHistoryUpdated(int currentAccount, long dialogId, int messageId, int type) {
        final ArrayList<Integer> ids = new ArrayList<>();
        ids.add(messageId);
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(currentAccount)
                .postNotificationName(NotificationCenter.ayuMessageHistoryUpdated, dialogId, ids, type));
    }

    /** loads the messages that are about to be deleted straight from messages_v2 (call on the storage queue) */
    private ArrayList<TLRPC.Message> loadMessagesFromStorage(int currentAccount, long dialogId, ArrayList<Integer> ids) {
        ArrayList<TLRPC.Message> result = new ArrayList<>();
        org.telegram.SQLite.SQLiteCursor cursor = null;
        try {
            StringBuilder sb = new StringBuilder();
            for (int a = 0; a < ids.size(); a++) {
                if (a != 0) {
                    sb.append(',');
                }
                sb.append(ids.get(a).intValue());
            }
            MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
            String query;
            if (dialogId != 0) {
                query = String.format(Locale.US, "SELECT data, mid FROM messages_v2 WHERE mid IN (%s) AND uid = %d", sb.toString(), dialogId);
            } else {
                query = String.format(Locale.US, "SELECT data, mid FROM messages_v2 WHERE mid IN (%s) AND is_channel = 0", sb.toString());
            }
            cursor = storage.getDatabase().queryFinalized(query);
            while (cursor.next()) {
                org.telegram.tgnet.NativeByteBuffer data = cursor.byteBufferValue(0);
                if (data == null) {
                    continue;
                }
                try {
                    TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
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

    private boolean shouldSave(int currentAccount, TLRPC.Message message) {
        if (message == null || message.id == 0) {
            return false;
        }
        if (message.ayuDeleted) {
            return false;
        }
        long dialogId = message.dialog_id != 0 ? message.dialog_id : MessageObject.getDialogId(message);
        if (dialogId == 0) {
            return false;
        }
        if (DialogObject.isEncryptedDialog(dialogId)) {
            return false;
        }
        if (message.id < 0) {
            // local / not yet sent
            return false;
        }
        if (message.action != null && !(message.action instanceof TLRPC.TL_messageActionEmpty)) {
            return false;
        }
        if (!AyuConfig.saveForBots && DialogObject.isUserDialog(dialogId)) {
            TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
            if (UserObject.isBot(user)) {
                return false;
            }
        }
        return true;
    }

    private boolean isMediaSaveAllowed(int currentAccount, long dialogId) {
        if (!AyuConfig.saveMedia) {
            return false;
        }
        boolean isPrivate = DialogObject.isUserDialog(dialogId);
        boolean isBot = false;
        boolean isChannel = false;
        boolean isPublic = false;
        if (isPrivate) {
            TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
            isBot = UserObject.isBot(user);
            isPublic = user != null && !TextUtils.isEmpty(UserObject.getPublicUsername(user));
        } else if (DialogObject.isChatDialog(dialogId)) {
            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
            isChannel = ChatObject.isChannelAndNotMegaGroup(chat);
            isPublic = chat != null && ChatObject.isPublic(chat);
        }
        return AyuConfig.isSaveMediaAllowed(isPrivate, isBot, isChannel, isPublic);
    }

    private static boolean hasMeaningfulChange(TLRPC.Message oldMessage, TLRPC.Message newMessage) {
        String oldText = oldMessage.message != null ? oldMessage.message : "";
        String newText = newMessage.message != null ? newMessage.message : "";
        if (!oldText.equals(newText)) {
            return true;
        }
        TLRPC.MessageMedia oldMedia = MessageObject.getMedia(oldMessage);
        TLRPC.MessageMedia newMedia = MessageObject.getMedia(newMessage);
        long oldId = mediaId(oldMedia);
        long newId = mediaId(newMedia);
        if (oldId != newId) {
            return true;
        }
        if (oldMedia == null != (newMedia == null)) {
            return true;
        }
        if (oldMedia != null && newMedia != null && oldMedia.getClass() != newMedia.getClass()) {
            return true;
        }
        return false;
    }

    private static long mediaId(TLRPC.MessageMedia media) {
        if (media == null) {
            return 0;
        }
        if (media.document != null) {
            return media.document.id;
        }
        if (media.photo != null) {
            return media.photo.id;
        }
        return 0;
    }

    private static TLRPC.Peer peerFromDialogId(long dialogId) {
        if (dialogId > 0) {
            TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
            peer.user_id = dialogId;
            return peer;
        }
        TLRPC.TL_peerChannel channel = new TLRPC.TL_peerChannel();
        channel.channel_id = -dialogId;
        return channel;
    }

    private static long computeTopicId(TLRPC.Message message) {
        if (message.reply_to != null && message.reply_to.forum_topic) {
            int topicId = message.reply_to.reply_to_top_id;
            if (topicId == 0) {
                topicId = message.reply_to.reply_to_msg_id;
            }
            return topicId;
        }
        return 0;
    }

    private static byte[] serialize(org.telegram.tgnet.TLObject object) {
        if (object == null) {
            return null;
        }
        try {
            SerializedData data = new SerializedData(object.getObjectSize());
            object.serializeToStream(data);
            byte[] bytes = data.toByteArray();
            data.cleanup();
            return bytes;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static byte[] serializeEntities(ArrayList<TLRPC.MessageEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return null;
        }
        try {
            SerializedData data = new SerializedData();
            data.writeInt32(entities.size());
            for (int a = 0; a < entities.size(); a++) {
                entities.get(a).serializeToStream(data);
            }
            byte[] bytes = data.toByteArray();
            data.cleanup();
            return bytes;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    public static ArrayList<TLRPC.MessageEntity> deserializeEntities(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return null;
        }
        try {
            SerializedData data = new SerializedData(bytes);
            int count = data.readInt32(false);
            ArrayList<TLRPC.MessageEntity> result = new ArrayList<>();
            for (int a = 0; a < count; a++) {
                TLRPC.MessageEntity entity = TLRPC.MessageEntity.TLdeserialize(data, data.readInt32(false), false);
                if (entity == null) {
                    break;
                }
                result.add(entity);
            }
            data.cleanup();
            return result;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static byte[] serializeThumbs(ArrayList<TLRPC.PhotoSize> thumbs) {
        if (thumbs == null || thumbs.isEmpty()) {
            return null;
        }
        try {
            SerializedData data = new SerializedData();
            data.writeInt32(thumbs.size());
            for (int a = 0; a < thumbs.size(); a++) {
                thumbs.get(a).serializeToStream(data);
            }
            byte[] bytes = data.toByteArray();
            data.cleanup();
            return bytes;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static byte[] serializeAttributes(ArrayList<TLRPC.DocumentAttribute> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        try {
            SerializedData data = new SerializedData();
            data.writeInt32(attributes.size());
            for (int a = 0; a < attributes.size(); a++) {
                attributes.get(a).serializeToStream(data);
            }
            byte[] bytes = data.toByteArray();
            data.cleanup();
            return bytes;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /** copies every flat column + the fully serialized message into the row */
    private void fillRow(int currentAccount, AyuMessageBase row, TLRPC.Message message, long selfId) {
        row.userId = selfId;
        row.dialogId = message.dialog_id != 0 ? message.dialog_id : MessageObject.getDialogId(message);
        row.peerId = message.peer_id != null ? DialogObject.getPeerDialogId(message.peer_id) : row.dialogId;
        row.fromId = message.from_id != null ? DialogObject.getPeerDialogId(message.from_id) : 0;
        row.groupedId = message.grouped_id;
        row.topicId = computeTopicId(message);
        row.messageId = message.id;
        row.date = message.date;
        row.flags = message.flags;
        row.editDate = message.edit_date;
        row.views = message.views;
        row.entityCreateDate = (int) (System.currentTimeMillis() / 1000L);
        row.text = message.message;

        if (message.fwd_from != null) {
            row.fwdFlags = message.fwd_from.flags;
            row.fwdFromId = message.fwd_from.from_id != null ? DialogObject.getPeerDialogId(message.fwd_from.from_id) : 0;
            row.fwdName = message.fwd_from.from_name;
            row.fwdDate = message.fwd_from.date;
            row.fwdPostAuthor = message.fwd_from.post_author;
        }
        if (message.reply_to != null) {
            row.replyFlags = message.reply_to.flags;
            row.replyMessageId = message.reply_to.reply_to_msg_id;
            row.replyPeerId = message.reply_to.reply_to_peer_id != null ? DialogObject.getPeerDialogId(message.reply_to.reply_to_peer_id) : 0;
            row.replyTopId = message.reply_to.reply_to_top_id;
            row.replyForumTopic = message.reply_to.forum_topic;
        }

        ArrayList<TLRPC.MessageEntity> savedEntities = null;
        if (AyuConfig.saveFormatting) {
            row.textEntities = serializeEntities(message.entities);
        } else {
            row.textEntities = null;
            if (message.entities != null && !message.entities.isEmpty()) {
                savedEntities = new ArrayList<>(message.entities);
                message.entities.clear();
            }
        }

        TLRPC.TL_messageReactions savedReactions = null;
        if (AyuConfig.saveReactions) {
            row.reactionsSerialized = serialize(message.reactions);
        } else {
            row.reactionsSerialized = null;
            savedReactions = message.reactions;
            message.reactions = null;
        }

        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        row.documentType = classify(message, media);
        if (media != null) {
            if (media.document != null) {
                row.documentSerialized = serialize(media.document);
                row.mimeType = media.document.mime_type;
                row.thumbsSerialized = serializeThumbs(media.document.thumbs);
                row.documentAttributesSerialized = serializeAttributes(media.document.attributes);
            } else if (media.photo != null) {
                row.documentSerialized = serialize(media.photo);
                row.mimeType = "image/jpeg";
                row.thumbsSerialized = serializeThumbs(media.photo.sizes);
            }
        }

        String previousAttachPath = message.attachPath;
        row.messageSerialized = serialize(message);
        message.attachPath = previousAttachPath;

        // restore what we temporarily stripped so the caller's object is untouched
        if (savedEntities != null) {
            message.entities.addAll(savedEntities);
        }
        if (savedReactions != null) {
            message.reactions = savedReactions;
        }
    }

    private static int classify(TLRPC.Message message, TLRPC.MessageMedia media) {
        if (media == null) {
            return AyuConstants.DOCUMENT_TYPE_NONE;
        }
        if (media.document != null) {
            TLRPC.Document document = media.document;
            if (MessageObject.isVoiceDocument(document)) {
                return AyuConstants.DOCUMENT_TYPE_VOICE;
            }
            if (MessageObject.isRoundVideoDocument(document)) {
                return AyuConstants.DOCUMENT_TYPE_ROUND;
            }
            if (MessageObject.isStickerDocument(document) || MessageObject.isAnimatedStickerDocument(document)) {
                return AyuConstants.DOCUMENT_TYPE_STICKER;
            }
            if (MessageObject.isGifDocument(document)) {
                return AyuConstants.DOCUMENT_TYPE_GIF;
            }
            if (MessageObject.isMusicDocument(document)) {
                return AyuConstants.DOCUMENT_TYPE_MUSIC;
            }
            if (MessageObject.isVideoDocument(document)) {
                return AyuConstants.DOCUMENT_TYPE_VIDEO;
            }
            return AyuConstants.DOCUMENT_TYPE_FILE;
        }
        if (media.photo != null) {
            return AyuConstants.DOCUMENT_TYPE_PHOTO;
        }
        return AyuConstants.DOCUMENT_TYPE_NONE;
    }

    // ------------------------------------------------------------------ media copying

    private static File mediaDirFor(int currentAccount, long dialogId) {
        File dir = new File(new File(AyuHistoryStorage.getMediaDir(), Integer.toString(currentAccount)), Long.toString(dialogId));
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static String sanitize(String name) {
        if (TextUtils.isEmpty(name)) {
            return "file";
        }
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static boolean copyFile(File source, File destination) {
        if (source == null || destination == null || !source.exists() || source.length() == 0) {
            return false;
        }
        if (destination.exists() && destination.length() == source.length()) {
            return true;
        }
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(source);
            out = new FileOutputStream(destination);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            try {
                destination.delete();
            } catch (Throwable ignore) {
            }
            return false;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignore) {
            }
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Throwable ignore) {
            }
        }
    }

    /** copies the already downloaded media (and its best thumbnail) into the ayu media dir */
    private void saveMediaFor(int currentAccount, TLRPC.Message message, AyuMessageBase row) {
        try {
            if (row.documentType == AyuConstants.DOCUMENT_TYPE_NONE) {
                return;
            }
            TLRPC.MessageMedia media = MessageObject.getMedia(message);
            if (media == null) {
                return;
            }
            File dir = mediaDirFor(currentAccount, row.dialogId);
            File source = null;
            if (!TextUtils.isEmpty(message.attachPath)) {
                File attach = new File(message.attachPath);
                if (attach.exists() && attach.length() > 0) {
                    source = attach;
                }
            }
            if (source == null) {
                source = FileLoader.getInstance(currentAccount).getPathToMessage(message);
            }
            if (source != null && source.exists() && source.length() > 0) {
                File destination = new File(dir, row.messageId + "_" + sanitize(source.getName()));
                if (copyFile(source, destination)) {
                    row.mediaPath = destination.getAbsolutePath();
                }
            }
            // thumbnail
            TLRPC.PhotoSize thumb = null;
            if (media.document != null) {
                thumb = FileLoader.getClosestPhotoSizeWithSize(media.document.thumbs, 320);
            } else if (media.photo != null) {
                thumb = FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes, 320);
            }
            if (thumb != null) {
                File thumbSource = FileLoader.getInstance(currentAccount).getPathToAttach(thumb, true);
                if (thumbSource == null || !thumbSource.exists()) {
                    thumbSource = FileLoader.getInstance(currentAccount).getPathToAttach(thumb, false);
                }
                if (thumbSource != null && thumbSource.exists() && thumbSource.length() > 0) {
                    File destination = new File(dir, row.messageId + "_thumb_" + sanitize(thumbSource.getName()));
                    if (copyFile(thumbSource, destination)) {
                        row.hqThumbPath = destination.getAbsolutePath();
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static class PendingMedia {
        int account;
        long userId;
        long dialogId;
        int messageId;
        String table;
        long fakeId;
        TLRPC.Message message;
    }

    /**
     * Best effort: if the media was not downloaded yet and it is small enough, start a download and
     * copy the file once it arrives (via NotificationCenter.fileLoaded).
     */
    private void scheduleDownloadIfPossible(int currentAccount, ArrayList<TLRPC.Message> source, DeletedMessage row) {
        try {
            if (!AyuConfig.saveMedia || !isMediaSaveAllowed(currentAccount, row.dialogId)) {
                return;
            }
            TLRPC.Message message = null;
            for (int a = 0; a < source.size(); a++) {
                if (source.get(a).id == row.messageId) {
                    message = source.get(a);
                    break;
                }
            }
            if (message == null) {
                return;
            }
            TLRPC.MessageMedia media = MessageObject.getMedia(message);
            if (media == null) {
                return;
            }
            final String fileName;
            final TLRPC.Message finalMessage = message;
            if (media.document != null) {
                if (media.document.size > MAX_AUTO_DOWNLOAD_SIZE) {
                    return;
                }
                fileName = FileLoader.getAttachFileName(media.document);
            } else if (media.photo != null) {
                TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes, 2048);
                if (size == null) {
                    return;
                }
                fileName = FileLoader.getAttachFileName(size);
            } else {
                return;
            }
            if (TextUtils.isEmpty(fileName)) {
                return;
            }
            PendingMedia pending = new PendingMedia();
            pending.account = currentAccount;
            pending.userId = row.userId;
            pending.dialogId = row.dialogId;
            pending.messageId = row.messageId;
            pending.table = AyuHistoryStorage.TABLE_DELETED;
            pending.fakeId = row.fakeId;
            pending.message = message;
            synchronized (pendingDownloads) {
                if (pendingDownloads.containsKey(currentAccount + ":" + fileName)) {
                    return;
                }
                pendingDownloads.put(currentAccount + ":" + fileName, pending);
            }
            AndroidUtilities.runOnUIThread(() -> {
                ensureObserver(currentAccount);
                try {
                    TLRPC.MessageMedia m = MessageObject.getMedia(finalMessage);
                    if (m == null) {
                        return;
                    }
                    if (m.document != null) {
                        FileLoader.getInstance(currentAccount).loadFile(m.document, finalMessage, FileLoader.PRIORITY_LOW, 0);
                    } else if (m.photo != null) {
                        TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(m.photo.sizes, 2048);
                        if (size != null) {
                            FileLoader.getInstance(currentAccount).loadFile(ImageLocation.getForObject(size, m.photo), finalMessage, "jpg", FileLoader.PRIORITY_LOW, 0);
                        }
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            });
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void ensureObserver(int currentAccount) {
        if (currentAccount < 0 || currentAccount >= observerAdded.length || observerAdded[currentAccount]) {
            return;
        }
        observerAdded[currentAccount] = true;
        NotificationCenter.NotificationCenterDelegate delegate = (id, account, args) -> {
            if (id != NotificationCenter.fileLoaded || args == null || args.length < 2) {
                return;
            }
            if (!(args[0] instanceof String)) {
                return;
            }
            final String fileName = (String) args[0];
            final PendingMedia pending;
            synchronized (pendingDownloads) {
                pending = pendingDownloads.remove(account + ":" + fileName);
            }
            if (pending == null) {
                return;
            }
            AyuHistoryStorage.getQueue().postRunnable(() -> {
                try {
                    DeletedMessage stored = AyuHistoryStorage.getInstance().getDeletedMessage(pending.userId, pending.dialogId, pending.messageId);
                    if (stored == null) {
                        return;
                    }
                    saveMediaFor(pending.account, pending.message, stored);
                    if (!TextUtils.isEmpty(stored.mediaPath)) {
                        AyuHistoryStorage.getInstance().updateMediaPath(pending.table, stored.fakeId, stored.mediaPath, stored.hqThumbPath);
                        postHistoryUpdated(pending.account, pending.dialogId, pending.messageId, 0);
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            });
        };
        NotificationCenter.getInstance(currentAccount).addObserver(delegate, NotificationCenter.fileLoaded);
    }
}
