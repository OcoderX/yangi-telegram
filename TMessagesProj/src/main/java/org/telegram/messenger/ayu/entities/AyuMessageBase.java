package org.telegram.messenger.ayu.entities;

/**
 * Base row for the AyuGram messages database (deleted messages and edit history).
 * Mirrors AyuGram's AyuMessageBase.
 */
public class AyuMessageBase {
    /** owner account user id */
    public long userId;
    public long dialogId;
    public long groupedId;
    public long peerId;
    public long fromId;
    public long topicId;
    public int messageId;
    public int date;
    public int flags;
    public int editDate;
    public int views;

    public int fwdFlags;
    public long fwdFromId;
    public String fwdName;
    public int fwdDate;
    public String fwdPostAuthor;

    public int replyFlags;
    public int replyMessageId;
    public long replyPeerId;
    public int replyTopId;
    public boolean replyForumTopic;

    /** when this row was created (unix seconds) */
    public int entityCreateDate;

    public String text;
    /** serialized TLRPC.MessageEntity vector */
    public byte[] textEntities;

    /** absolute path of the saved media copy (may be null) */
    public String mediaPath;
    public String hqThumbPath;
    /** see AyuConstants.DOCUMENT_TYPE_* */
    public int documentType;
    /** serialized TLRPC.Document / TLRPC.Photo */
    public byte[] documentSerialized;
    public byte[] thumbsSerialized;
    public byte[] documentAttributesSerialized;
    public String mimeType;

    /** serialized TLRPC.TL_messageReactions (optional) */
    public byte[] reactionsSerialized;
    /** full serialized TLRPC.Message, used to rebuild MessageObject exactly */
    public byte[] messageSerialized;
}
