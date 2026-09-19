package org.telegram.messenger.ayu;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.VideoEditedInfo;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
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
    /** "send when online" pseudo schedule date used by Telegram */
    private static final int SCHEDULE_WHEN_ONLINE = 0x7ffffffe;

    private static boolean isActivated(int account) {
        return account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT && UserConfig.getInstance(account).isClientActivated();
    }

    public static void sendOfflinePacket(int account) {
        if (!isActivated(account)) {
            return;
        }
        TL_account.updateStatus req = new TL_account.updateStatus();
        req.offline = true;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {

        });
    }

    public static void sendOnlinePacket(int account) {
        if (!isActivated(account)) {
            return;
        }
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

    /**
     * Called whenever one of the online-related ghost options changes. Immediately pushes the new status to the
     * server for every logged-in account instead of waiting for the next app pause / resume.
     */
    public static void onOnlineSettingsChanged() {
        // the status fields of MessagesController are owned by the stage queue (see updateTimerProc)
        Utilities.stageQueue.postRunnable(() -> {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (!isActivated(a)) {
                    continue;
                }
                MessagesController.getInstance(a).ayuOnOnlineSettingsChanged();
            }
        });
    }

    /** allow exactly one read receipt for the given dialog and flush the pending read state */
    public static void allowReadAndFlush(int account, long dialogId) {
        if (dialogId == 0 || !isActivated(account)) {
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

    /** explicit "mark as read" chosen by the user from a menu: always goes through, even in ghost mode */
    public static void onManualMarkAsRead(int account, long dialogId) {
        if (AyuConfig.sendReadPackets) {
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

    /**
     * Called right before a send request leaves the device. Uploads can take longer than the delay that was
     * guessed when the message was created; a schedule date that is already (almost) in the past would be rejected
     * by the server with SCHEDULE_DATE_INVALID, so it is moved to "now + text delay".
     */
    public static void fixScheduleDate(TLObject req, int now) {
        if (req == null || now <= 0) {
            return;
        }
        final int minimum = now + AyuConstants.SCHEDULED_TEXT_DELAY_SECONDS;
        if (req instanceof TLRPC.TL_messages_sendMessage) {
            TLRPC.TL_messages_sendMessage r = (TLRPC.TL_messages_sendMessage) req;
            if ((r.flags & 1024) != 0 && needsFix(r.schedule_date, minimum)) {
                r.schedule_date = minimum;
            }
        } else if (req instanceof TLRPC.TL_messages_sendMedia) {
            TLRPC.TL_messages_sendMedia r = (TLRPC.TL_messages_sendMedia) req;
            if ((r.flags & 1024) != 0 && needsFix(r.schedule_date, minimum)) {
                r.schedule_date = minimum;
            }
        } else if (req instanceof TLRPC.TL_messages_sendMultiMedia) {
            TLRPC.TL_messages_sendMultiMedia r = (TLRPC.TL_messages_sendMultiMedia) req;
            if ((r.flags & 1024) != 0 && needsFix(r.schedule_date, minimum)) {
                r.schedule_date = minimum;
            }
        } else if (req instanceof TLRPC.TL_messages_sendInlineBotResult) {
            TLRPC.TL_messages_sendInlineBotResult r = (TLRPC.TL_messages_sendInlineBotResult) req;
            if ((r.flags & 1024) != 0 && needsFix(r.schedule_date, minimum)) {
                r.schedule_date = minimum;
            }
        } else if (req instanceof TLRPC.TL_messages_forwardMessages) {
            TLRPC.TL_messages_forwardMessages r = (TLRPC.TL_messages_forwardMessages) req;
            if ((r.flags & 1024) != 0 && needsFix(r.schedule_date, minimum)) {
                r.schedule_date = minimum;
            }
        }
    }

    private static boolean needsFix(int scheduleDate, int minimum) {
        return scheduleDate != 0 && scheduleDate != SCHEDULE_WHEN_ONLINE && scheduleDate < minimum;
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
