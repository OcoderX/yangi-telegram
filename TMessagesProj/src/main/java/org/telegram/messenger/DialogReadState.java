package org.telegram.messenger;

/** Monotonic cloud-history read state. Manual unread marks are deliberately separate. */
final class DialogReadState {
    private DialogReadState() {}

    static int cloudReadMax(int value, int topMessage) {
        // Integer.MAX_VALUE is a local "read all" sentinel, never a durable message ID.
        return value == Integer.MAX_VALUE ? Math.max(0, topMessage) : value;
    }

    static int unreadCount(int remoteReadMax, int remoteTop, int remoteCount,
                           int localReadMax, int localTop, int localCount) {
        if (localTop > remoteTop && localCount >= 0) {
            // A delayed page must not erase unread messages beyond its last message.
            int effectiveMax = Math.max(localReadMax, remoteReadMax);
            return Math.min(localCount, Math.max(0, localTop - effectiveMax));
        }
        if (localReadMax <= 0 || remoteReadMax > localReadMax) {
            return remoteCount;
        }
        if (remoteTop > 0 && remoteTop <= localReadMax) {
            return 0;
        }
        int count = remoteCount;
        if (localTop >= remoteTop && localCount >= 0) {
            count = Math.min(count, localCount);
        }
        if (remoteTop > localReadMax) {
            // IDs can have gaps. This is an upper bound, never an inferred exact message count.
            count = Math.min(count, remoteTop - localReadMax);
        }
        return Math.max(0, count);
    }
}
