package org.telegram.messenger;

import java.util.Objects;

final class VideoUploadNotificationTarget {
    final int account;
    final long owner;
    final long dialogId;
    final int messageId;
    final long randomId;
    final String path;
    final long generation;

    VideoUploadNotificationTarget(int account, long owner, long dialogId, int messageId, long randomId, String path, long generation) {
        this.account = account;
        this.owner = owner;
        this.dialogId = dialogId;
        this.messageId = messageId;
        this.randomId = randomId;
        this.path = path;
        this.generation = generation;
    }

    boolean matches(int account, long owner, long dialogId, int messageId, long randomId, String path, long generation) {
        return this.owner > 0 && this.generation != 0 && this.path != null && !this.path.isEmpty()
                && this.account == account && this.owner == owner && this.dialogId == dialogId
                && this.messageId == messageId && this.randomId == randomId
                && this.generation == generation && Objects.equals(this.path, path);
    }

    static int progress(long uploaded, long total) {
        if (uploaded < 0 || total <= 0) {
            return -1;
        }
        return (int) Math.min(100d, uploaded * 100d / total);
    }
}
