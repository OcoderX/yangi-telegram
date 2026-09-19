package org.telegram.messenger.ayu.radar;

import org.telegram.messenger.MessageObject;

/**
 * AyuGram Mention Radar: one collected hit (a message that mentioned me, replied to me, contained
 * my name or one of my keywords).
 */
public class RadarHit {

    public static final int KIND_MENTION = 0;
    public static final int KIND_REPLY = 1;
    public static final int KIND_NAME = 2;
    public static final int KIND_KEYWORD = 3;

    public static final int FILTER_ALL = -1;

    /** rowid in the radar database, 0 when not stored yet */
    public long rowId;
    public long dialogId;
    public int msgId;
    /** unix time of the message */
    public int date;
    public int kind = KIND_MENTION;
    /** the exact text that matched (keyword text, my name, my @username, …) */
    public String matched;
    public long senderId;
    /** plain-text preview of the message */
    public String snippet;
    /** match range inside {@link #snippet}, -1 when unknown */
    public int matchStart = -1;
    public int matchEnd = -1;
    public boolean read;
    /** serialized TLRPC.Message so the feed can render a real message cell */
    public byte[] data;

    /** lazily rebuilt from {@link #data}, never persisted */
    public transient MessageObject messageObject;

    public static boolean isValidKind(int kind) {
        return kind >= KIND_MENTION && kind <= KIND_KEYWORD;
    }
}
