package org.telegram.messenger.ayu;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Transient runtime state for AyuGram features (not persisted).
 */
public class AyuState {

    /** One-shot override that lets the next read-history call go through even in ghost mode
     *  (e.g. "mark read after send", "read on interact", manual "mark as read" from a menu). */
    private static boolean allowReadPacket;
    private static long allowReadPacketDialogId;
    private static boolean allowReadPacketReset = true;

    /** Set while the client is sending a message that was rewritten as "scheduled" by useScheduledMessages. */
    public static boolean automaticallyScheduled;

    /** One-shot flag: the next local deletion must NOT be written into the AyuGram history database
     *  (used by "delete locally" on messages that are already stored as deleted). */
    public static boolean skipNextSave;

    /** reads and clears {@link #skipNextSave} */
    public static boolean consumeSkipNextSave() {
        if (skipNextSave) {
            skipNextSave = false;
            return true;
        }
        return false;
    }

    public static void setAllowReadPacket(boolean allow, boolean reset) {
        allowReadPacket = allow;
        allowReadPacketReset = reset;
        allowReadPacketDialogId = 0;
    }

    public static void setAllowReadPacket(long dialogId, boolean allow, boolean reset) {
        allowReadPacket = allow;
        allowReadPacketReset = reset;
        allowReadPacketDialogId = dialogId;
    }

    /**
     * Whether a read packet may be sent for the given dialog. Honors the ghost mode option and the one-shot override.
     */
    public static boolean isAllowReadPacket(long dialogId) {
        if (AyuConfig.sendReadPackets) {
            return true;
        }
        if (allowReadPacket && (allowReadPacketDialogId == 0 || allowReadPacketDialogId == dialogId)) {
            if (allowReadPacketReset) {
                allowReadPacket = false;
                allowReadPacketDialogId = 0;
            }
            return true;
        }
        return false;
    }

    public static boolean isAllowReadPacket() {
        return isAllowReadPacket(0);
    }

    /**
     * A read receipt that was not sent because of ghost mode. It is kept so that "mark read after send" /
     * "read on interact" can replay it later instead of silently losing it.
     */
    public static class SuppressedRead {
        public long dialogId;
        public long replyId;
        public long monoForumPeerId;
        public int maxId;
        public int maxDate;
    }

    private static final HashMap<String, SuppressedRead> suppressedReads = new HashMap<>();

    private static String suppressedKey(long dialogId, long replyId) {
        return dialogId + "_" + replyId;
    }

    public static void saveSuppressedRead(long dialogId, long replyId, long monoForumPeerId, int maxId, int maxDate) {
        synchronized (suppressedReads) {
            String key = suppressedKey(dialogId, replyId);
            SuppressedRead read = suppressedReads.get(key);
            if (read == null) {
                read = new SuppressedRead();
                read.dialogId = dialogId;
                read.replyId = replyId;
                suppressedReads.put(key, read);
            }
            read.monoForumPeerId = monoForumPeerId;
            read.maxId = Math.max(read.maxId, maxId);
            read.maxDate = Math.max(read.maxDate, maxDate);
        }
    }

    /** returns and removes every suppressed read receipt of the given dialog */
    public static ArrayList<SuppressedRead> takeSuppressedReads(long dialogId) {
        ArrayList<SuppressedRead> result = new ArrayList<>();
        synchronized (suppressedReads) {
            for (Iterator<Map.Entry<String, SuppressedRead>> it = suppressedReads.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, SuppressedRead> entry = it.next();
                if (entry.getValue().dialogId == dialogId) {
                    result.add(entry.getValue());
                    it.remove();
                }
            }
        }
        return result;
    }

    public static void clearSuppressedReads(long dialogId, long replyId) {
        synchronized (suppressedReads) {
            suppressedReads.remove(suppressedKey(dialogId, replyId));
        }
    }

    /** marks the message that is being sent right now as automatically rescheduled by useScheduledMessages */
    public static void markAutomaticallyScheduled() {
        automaticallyScheduled = true;
        AndroidUtilities.runOnUIThread(() -> automaticallyScheduled = false);
    }

    public static void onGhostModeChanged() {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.ayuGhostModeChanged));
    }
}
