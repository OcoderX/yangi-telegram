package org.telegram.messenger.ayu.edithistory;

import android.text.TextUtils;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.ayu.AyuConstants;
import org.telegram.messenger.ayu.AyuMessagesController;
import org.telegram.messenger.ayu.entities.EditedMessage;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * One version of a message as shown by the edit history sheet. Built either from a stored
 * {@link EditedMessage} row (a previous version) or from the live message (the current version).
 */
public class AyuRevision {

    /** row id of the stored revision, 0 for the live version */
    public long fakeId;
    /** true for the version that is currently on the server */
    public boolean current;
    /** true for the oldest stored version (the message as it was originally sent) */
    public boolean original;
    /** 1-based index among the stored revisions, 0 for the current version */
    public int index;

    /** edit_date of this version (0 when it was never edited) */
    public int editDate;
    /** date the message was sent */
    public int date;
    /** wall clock time this version was archived; 0 for the live version */
    public int capturedAt;

    public String text;
    public ArrayList<TLRPC.MessageEntity> entities;

    public int documentType = AyuConstants.DOCUMENT_TYPE_NONE;
    public String mediaPath;
    public String thumbPath;
    public String mimeType;
    /** cheap identity of the attached media, used to tell media replacements apart */
    public long mediaId;

    public TLRPC.Message message;

    /** the timestamp shown in the sheet: when this version stopped being the visible one */
    public int getDisplayDate() {
        if (current) {
            return editDate != 0 ? editDate : date;
        }
        if (capturedAt != 0) {
            return capturedAt;
        }
        return editDate != 0 ? editDate : date;
    }

    public boolean hasMedia() {
        return documentType != AyuConstants.DOCUMENT_TYPE_NONE;
    }

    public boolean hasSavedMediaFile() {
        if (TextUtils.isEmpty(mediaPath)) {
            return false;
        }
        try {
            final File file = new File(mediaPath);
            return file.exists() && file.length() > 0;
        } catch (Throwable e) {
            return false;
        }
    }

    public String getPreviewPath() {
        if (!TextUtils.isEmpty(thumbPath)) {
            try {
                final File file = new File(thumbPath);
                if (file.exists() && file.length() > 0) {
                    return thumbPath;
                }
            } catch (Throwable ignore) {
            }
        }
        if (documentType == AyuConstants.DOCUMENT_TYPE_PHOTO && hasSavedMediaFile()) {
            return mediaPath;
        }
        return null;
    }

    // ------------------------------------------------------------------ factories

    public static AyuRevision fromRow(EditedMessage row) {
        if (row == null) {
            return null;
        }
        final AyuRevision revision = new AyuRevision();
        revision.fakeId = row.fakeId;
        revision.editDate = row.editDate;
        revision.date = row.date;
        revision.capturedAt = row.entityCreateDate;
        revision.text = row.text;
        revision.documentType = row.documentType;
        revision.mediaPath = row.mediaPath;
        revision.thumbPath = row.hqThumbPath;
        revision.mimeType = row.mimeType;
        try {
            revision.message = AyuMessagesController.getInstance().toTLMessage(row);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        if (revision.message != null) {
            if (TextUtils.isEmpty(revision.text)) {
                revision.text = revision.message.message;
            }
            revision.entities = revision.message.entities;
            revision.mediaId = mediaIdOf(MessageObject.getMedia(revision.message));
            if (revision.documentType == AyuConstants.DOCUMENT_TYPE_NONE && revision.mediaId != 0) {
                revision.documentType = AyuConstants.DOCUMENT_TYPE_FILE;
            }
        }
        if (revision.entities == null && row.textEntities != null) {
            try {
                revision.entities = AyuMessagesController.deserializeEntities(row.textEntities);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        return revision;
    }

    public static AyuRevision fromCurrent(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return null;
        }
        final TLRPC.Message message = messageObject.messageOwner;
        final AyuRevision revision = new AyuRevision();
        revision.current = true;
        revision.message = message;
        revision.editDate = message.edit_date;
        revision.date = message.date;
        revision.text = message.message;
        revision.entities = message.entities;
        final TLRPC.MessageMedia media = MessageObject.getMedia(message);
        revision.mediaId = mediaIdOf(media);
        if (revision.mediaId != 0) {
            revision.documentType = AyuConstants.DOCUMENT_TYPE_FILE;
            if (media != null && media.document != null) {
                revision.mimeType = media.document.mime_type;
            }
        }
        return revision;
    }

    public static long mediaIdOf(TLRPC.MessageMedia media) {
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

    /** builds the full list for one message, newest (current) first */
    public static ArrayList<AyuRevision> buildList(int currentAccount, MessageObject messageObject) {
        final ArrayList<AyuRevision> result = new ArrayList<>();
        if (messageObject == null || messageObject.messageOwner == null) {
            return result;
        }
        List<EditedMessage> rows = null;
        try {
            rows = AyuMessagesController.getInstance().getRevisions(currentAccount, messageObject.getDialogId(), messageObject.getId());
        } catch (Throwable e) {
            FileLog.e(e);
        }
        final ArrayList<AyuRevision> stored = new ArrayList<>();
        if (rows != null) {
            for (int a = 0; a < rows.size(); a++) {
                final AyuRevision revision = fromRow(rows.get(a));
                if (revision == null) {
                    continue;
                }
                revision.index = a + 1;
                revision.original = a == 0;
                stored.add(revision);
            }
        }
        final AyuRevision current = fromCurrent(messageObject);
        if (current != null) {
            result.add(current);
        }
        for (int a = stored.size() - 1; a >= 0; a--) {
            result.add(stored.get(a));
        }
        return result;
    }
}
