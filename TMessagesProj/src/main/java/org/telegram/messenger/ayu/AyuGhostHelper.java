package org.telegram.messenger.ayu;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.VideoEditedInfo;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;

import java.io.File;

/**
 * Helper methods for the ghost mode logic (online packets, read on send / on interact, scheduled sending).
 */
public class AyuGhostHelper {

    /** bytes per second used to guess how long an upload will take when useScheduledMessages is on */
    private static final long SCHEDULED_UPLOAD_SPEED = 256 * 1024;
    /** never schedule further than 5 minutes into the future */
    private static final int SCHEDULED_MAX_DELAY_SECONDS = 5 * 60;

    public static void sendOfflinePacket(int account) {
        TL_account.updateStatus req = new TL_account.updateStatus();
        req.offline = true;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {

        });
    }

    public static void sendOnlinePacket(int account) {
        TL_account.updateStatus req = new TL_account.updateStatus();
        req.offline = false;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {

        });
    }

    /**
     * Called right after the network was resumed. In ghost mode the C++ layer may announce us as online
     * implicitly, so an explicit offline packet is sent to compensate.
     */
    public static void onNetworkResumed(int account) {
        if (!AyuConfig.sendOnlinePackets || AyuConfig.sendOfflinePacketAfterOnline) {
            sendOfflinePacket(account);
        }
    }

    /** allow exactly one read receipt for the given dialog and flush the pending read state */
    public static void allowReadAndFlush(int account, long dialogId) {
        if (dialogId == 0) {
            return;
        }
        MessagesController.getInstance(account).ayuFlushReadTasks(dialogId);
    }

    /** "Mark read after sending a message" */
    public static void onMessageSent(int account, long dialogId, boolean scheduled) {
        if (scheduled || AyuConfig.sendReadPackets || !AyuConfig.markReadAfterSend) {
            return;
        }
        allowReadAndFlush(account, dialogId);
    }

    /** "Read on interact" (reactions, votes, etc.) */
    public static void onUserInteraction(int account, long dialogId) {
        if (AyuConfig.sendReadPackets || !AyuConfig.markReadAfterAction) {
            return;
        }
        allowReadAndFlush(account, dialogId);
    }

    public static boolean isSendMessageRequest(Object req) {
        return req instanceof TLRPC.TL_messages_sendMessage
                || req instanceof TLRPC.TL_messages_sendMedia
                || req instanceof TLRPC.TL_messages_sendMultiMedia
                || req instanceof TLRPC.TL_messages_forwardMessages
                || req instanceof TLRPC.TL_messages_sendInlineBotResult;
    }

    /** delay (in seconds) used by "send messages as scheduled" for a plain text message */
    public static int getScheduledTextDelay() {
        return AyuConstants.SCHEDULED_TEXT_DELAY_SECONDS;
    }

    /** delay (in seconds) used by "send messages as scheduled", grows with the size of the attachment */
    public static int getScheduledDelay(long fileSize) {
        if (fileSize <= 0) {
            return AyuConstants.SCHEDULED_TEXT_DELAY_SECONDS;
        }
        long delay = AyuConstants.SCHEDULED_TEXT_DELAY_SECONDS + fileSize / SCHEDULED_UPLOAD_SPEED;
        if (delay > SCHEDULED_MAX_DELAY_SECONDS) {
            delay = SCHEDULED_MAX_DELAY_SECONDS;
        }
        return (int) delay;
    }

    public static int getScheduledDelay(TLRPC.TL_document document, TLRPC.TL_photo photo, VideoEditedInfo videoEditedInfo, String path) {
        return getScheduledDelay(estimateSize(document, photo, videoEditedInfo, path));
    }

    private static long estimateSize(TLRPC.TL_document document, TLRPC.TL_photo photo, VideoEditedInfo videoEditedInfo, String path) {
        long size = 0;
        if (videoEditedInfo != null && videoEditedInfo.estimatedSize > 0) {
            size = videoEditedInfo.estimatedSize;
        }
        if (size == 0 && document != null && document.size > 0) {
            size = document.size;
        }
        if (size == 0 && path != null && path.length() > 0) {
            try {
                File file = new File(path);
                if (file.exists()) {
                    size = file.length();
                }
            } catch (Exception ignore) {
            }
        }
        if (size == 0 && photo != null) {
            for (int a = 0; a < photo.sizes.size(); a++) {
                size = Math.max(size, photo.sizes.get(a).size);
            }
        }
        return size;
    }
}
