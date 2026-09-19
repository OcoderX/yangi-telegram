package org.telegram.messenger.ayu;

import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Transient runtime state for AyuGram features (not persisted).
 * Every accessor is thread-safe: the read-receipt overrides are consulted from the UI thread,
 * the stage queue and the storage queue at the same time.
 */
public class AyuState {

    /** dialogId used by {@link #setAllowReadPacket(boolean, boolean)} to allow every dialog */
    private static final long ANY_DIALOG = 0;

    /**
     * Per-dialog one-shot overrides that let a read-history call go through even in ghost mode
     * (e.g. "mark read after send", "read on interact", manual "mark as read" from a menu).
     * value = true when the override must be consumed by the first matching read.
     */
    private static final HashMap<Long, Boolean> allowReadPackets = new HashMap<>();

    /** One-shot flag: the next local deletion must NOT be written into the AyuGram history database
     *  (used by "delete locally" on messages that are already stored as deleted). */
    public static volatile boolean skipNextSave;

    /** reads and clears {@link #skipNextSave} */
    public static synchronized boolean consumeSkipNextSave() {
        if (skipNextSave) {
            skipNextSave = false;
            return true;
        }
        return false;
    }

    /** allow (or forbid) read packets for every dialog */
    public static void setAllowReadPacket(boolean allow, boolean reset) {
        setAllowReadPacket(ANY_DIALOG, allow, reset);
    }

    /**
     * @param dialogId dialog the override applies to, 0 = every dialog
     * @param allow    true to permit read packets, false to remove a previously granted override
     * @param reset    true = the override is consumed by the first read that uses it
     */
    public static void setAllowReadPacket(long dialogId, boolean allow, boolean reset) {
        synchronized (allowReadPackets) {
            if (allow) {
                allowReadPackets.put(dialogId, reset);
            } else {
                allowReadPackets.remove(dialogId);
            }
        }
    }

    /**
     * Whether a read packet may be sent for the given dialog. Honors the ghost mode option and the one-shot overrides.
     */
    public static boolean isAllowReadPacket(long dialogId) {
        if (AyuConfig.sendReadPackets) {
            return true;
        }
        synchronized (allowReadPackets) {
            if (allowReadPackets.isEmpty()) {
                return false;
            }
            Boolean reset = allowReadPackets.get(dialogId);
            if (reset != null) {
                if (reset) {
                    allowReadPackets.remove(dialogId);
                }
                return true;
            }
            if (dialogId != ANY_DIALOG) {
                reset = allowReadPackets.get(ANY_DIALOG);
                if (reset != null) {
                    if (reset) {
                        allowReadPackets.remove(ANY_DIALOG);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isAllowReadPacket() {
        return isAllowReadPacket(ANY_DIALOG);
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

    // ---------------- "send messages as scheduled" ----------------

    /** dialogId -> elapsedRealtime deadline until which sends to that dialog count as automatically scheduled */
    private static final HashMap<Long, Long> automaticallyScheduled = new HashMap<>();

    /** extra grace period added to the schedule delay so the server round-trip is covered too */
    private static final long AUTO_SCHEDULED_GRACE_MS = 30_000L;

    /**
     * Marks the dialog as having a message that was rescheduled by useScheduledMessages, so that the chat UI
     * does not jump to the "scheduled messages" screen when the local copy shows up.
     */
    public static void markAutomaticallyScheduled(long dialogId, int delaySeconds) {
        long deadline = SystemClock.elapsedRealtime() + delaySeconds * 1000L + AUTO_SCHEDULED_GRACE_MS;
        synchronized (automaticallyScheduled) {
            Long current = automaticallyScheduled.get(dialogId);
            if (current == null || current < deadline) {
                automaticallyScheduled.put(dialogId, deadline);
            }
        }
    }

    /** whether a scheduled message that shows up in the given dialog right now was created automatically */
    public static boolean isAutomaticallyScheduled(long dialogId) {
        long now = SystemClock.elapsedRealtime();
        synchronized (automaticallyScheduled) {
            Long deadline = automaticallyScheduled.get(dialogId);
            if (deadline == null) {
                return false;
            }
            if (deadline < now) {
                automaticallyScheduled.remove(dialogId);
                return false;
            }
            return true;
        }
    }

    public static void onGhostModeChanged() {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.ayuGhostModeChanged));
    }
}
