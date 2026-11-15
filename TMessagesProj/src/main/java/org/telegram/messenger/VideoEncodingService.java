/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.text.TextUtils;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.telegram.ui.LaunchActivity;

import java.io.File;

public class VideoEncodingService extends Service implements NotificationCenter.NotificationCenterDelegate {

    private NotificationCompat.Builder builder;
    private MediaController.VideoConvertMessage currentMessage;
    private static VideoEncodingService instance;
    private VideoUploadNotificationTarget target;
    private PendingIntent openIntent;
    private PendingIntent cancelIntent;

    int currentAccount;
    String currentPath;

    public VideoEncodingService() {
        super();
    }

    public static void start(boolean cancelled) {
        if (instance == null) {
            try {
                Intent intent = new Intent(ApplicationLoader.applicationContext, VideoEncodingService.class);
                ApplicationLoader.applicationContext.startService(intent);
            } catch (Exception e) {
                FileLog.e(e);
            }
        } else {
            MediaController.VideoConvertMessage messageInController = MediaController.getInstance().getCurrentForegroundConverMessage();
            if (instance.currentMessage != messageInController) {
                if (messageInController != null) {
                    instance.setCurrentMessage(messageInController);
                } else if (cancelled) {
                    instance.stopSelf();
                }
            }
        }
    }

    public static void stop() {
        if (instance != null) {
            instance.stopSelf();
        }
    }

    public IBinder onBind(Intent arg2) {
        return null;
    }


    public void onDestroy() {
        super.onDestroy();
        if (instance == this) {
            instance = null;
        }
        cancelPendingIntents();
        try {
            stopForeground(true);
        } catch (Throwable ignore) {

        }
        NotificationManagerCompat.from(ApplicationLoader.applicationContext).cancel(4);
        observeCurrentAccount(false);
        currentMessage = null;
        target = null;
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("VideoEncodingService: destroy video service");
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (account != currentAccount || builder == null || !isCurrentOwner()) {
            if (!isCurrentOwner()) {
                stopSelf();
            }
            return;
        }
        if (id == NotificationCenter.appDidLogout) {
            stopSelf();
            return;
        }
        if (id == NotificationCenter.fileUploadProgressChanged) {
            String fileName = (String) args[0];
            if (currentPath != null && currentPath.equals(fileName)
                    && (Boolean) args[3] == DialogObject.isEncryptedDialog(target.dialogId)) {
                Long loadedSize = (Long) args[1];
                Long totalSize = (Long) args[2];
                int currentProgress = VideoUploadNotificationTarget.progress(loadedSize, totalSize);
                builder.setProgress(100, Math.max(0, currentProgress), currentProgress < 0);
                updateNotification();
            }
        } else if (id == NotificationCenter.fileNewChunkAvailable || id == NotificationCenter.filePreparingFailed) {
            if (args[0] != currentMessage.messageObject) {
                return;
            }
            if (id == NotificationCenter.filePreparingFailed
                    || currentMessage.foregroundConversion && (Long) args[3] > 0) {
                advanceOrStop();
            } else if (currentMessage.foregroundConversion) {
                float progress = (Float) args[4];
                boolean known = !Float.isNaN(progress) && !Float.isInfinite(progress) && progress >= 0;
                builder.setProgress(100, known ? (int) (Math.min(1f, progress) * 100) : 0, !known);
                updateNotification();
            }
        } else if (id == NotificationCenter.fileUploaded || id == NotificationCenter.fileUploadFailed) {
            String fileName = (String) args[0];
            boolean encrypted = id == NotificationCenter.fileUploadFailed ? (Boolean) args[1] : args[2] != null;
            if (currentPath != null && currentPath.equals(fileName)
                    && encrypted == DialogObject.isEncryptedDialog(target.dialogId)) {
                advanceOrStop();
            }
        }
    }

    private void updateNotification() {
        try {
            if (builder == null || currentMessage == null || !isCurrentOwner()) {
                return;
            }
            NotificationManagerCompat.from(ApplicationLoader.applicationContext).notify(4, builder.build());
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        MediaController.VideoConvertMessage videoConvertMessage = MediaController.getInstance().getCurrentForegroundConverMessage();
        if (videoConvertMessage == null) {
            if (currentMessage == null) {
                stopSelf(startId);
            }
            return Service.START_NOT_STICKY;
        }
        instance = this;
        setCurrentMessage(videoConvertMessage);
        if (builder == null) {
            stopSelf(startId);
            return Service.START_NOT_STICKY;
        }
        try {
            startForeground(4, builder.build());
        } catch (Throwable e) {
            //ignore ForegroundServiceStartNotAllowedException
            FileLog.e(e);
        }
        AndroidUtilities.runOnUIThread(this::updateNotification);
        return Service.START_NOT_STICKY;
    }

    private void updateBuilderForMessage(MediaController.VideoConvertMessage videoConvertMessage) {
        NotificationsController.checkOtherNotificationsChannel();
        builder = new NotificationCompat.Builder(ApplicationLoader.applicationContext, NotificationsController.OTHER_NOTIFICATIONS_CHANNEL);
        builder.setSmallIcon(android.R.drawable.stat_sys_upload);
        builder.setWhen(System.currentTimeMillis());
        builder.setOngoing(true);
        builder.setOnlyAlertOnce(true);
        builder.setCategory(NotificationCompat.CATEGORY_PROGRESS);
        builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE);
        boolean isGif = videoConvertMessage.messageObject != null && MessageObject.isGifMessage(videoConvertMessage.messageObject.messageOwner);
        String status;
        if (videoConvertMessage.foregroundConversion) {
            status = LocaleController.getString(R.string.ConvertingVideo);
        } else if (isGif) {
            status = LocaleController.getString(R.string.SendingGif);
        } else {
            status = LocaleController.getString(R.string.SendingVideo);
        }
        MessageObject message = videoConvertMessage.messageObject;
        String name = message.getDocumentName();
        if (TextUtils.isEmpty(name) && videoConvertMessage.videoEditedInfo != null
                && !TextUtils.isEmpty(videoConvertMessage.videoEditedInfo.originalPath)) {
            name = new File(videoConvertMessage.videoEditedInfo.originalPath).getName();
        }
        if (TextUtils.isEmpty(name)) {
            name = new File(currentPath).getName();
        }
        String peer = MessagesController.getInstance(currentAccount).getPeerName(target.dialogId);
        builder.setContentTitle(status);
        builder.setContentText(name);
        if (!TextUtils.isEmpty(peer)) {
            builder.setSubText(peer);
        }
        builder.setStyle(new NotificationCompat.BigTextStyle().bigText(TextUtils.isEmpty(peer) ? name : name + "\n" + peer));
        builder.setProgress(100, 0, true);

        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        Intent open = new Intent(this, LaunchActivity.class);
        open.setAction("com.tmessages.openchat.video." + target.generation);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        open.putExtra("currentAccount", currentAccount);
        open.putExtra("ox_video_notification_owner", target.owner);
        open.putExtra("message_id", target.messageId);
        if (DialogObject.isEncryptedDialog(target.dialogId)) {
            open.putExtra("encId", DialogObject.getEncryptedChatId(target.dialogId));
        } else if (target.dialogId < 0) {
            open.putExtra("chatId", -target.dialogId);
            open.putExtra("topicId", message.getTopicId());
        } else {
            open.putExtra("userId", target.dialogId);
        }
        openIntent = PendingIntent.getActivity(this, 0, open, pendingFlags);
        builder.setContentIntent(openIntent);
        Intent cancel = new Intent(this, CancelVideoUploadReceiver.class);
        cancel.setAction(CancelVideoUploadReceiver.ACTION);
        cancel.setData(Uri.parse("ox-video-upload://" + target.owner + "/" + target.generation));
        cancel.putExtra("ox_video_account", target.account);
        cancel.putExtra("ox_video_owner", target.owner);
        cancel.putExtra("ox_video_dialog", target.dialogId);
        cancel.putExtra("ox_video_message", target.messageId);
        cancel.putExtra("ox_video_random", target.randomId);
        cancel.putExtra("ox_video_path", target.path);
        cancel.putExtra("ox_video_generation", target.generation);
        cancelIntent = PendingIntent.getBroadcast(this, 0, cancel, pendingFlags);
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, LocaleController.getString(R.string.CancelSending), cancelIntent);
    }

    private void setCurrentMessage(MediaController.VideoConvertMessage message) {
        if (currentMessage == message && isCurrentOwner()) {
            return;
        }
        if (message == null || message.messageObject == null || message.messageObject.messageOwner == null
                || !UserConfig.isValidAccount(message.currentAccount)) {
            stopSelf();
            return;
        }
        observeCurrentAccount(false);
        cancelPendingIntents();
        currentMessage = message;
        currentAccount = message.currentAccount;
        currentPath = message.messageObject.messageOwner.attachPath;
        long owner = message.accountOwnerId;
        long generation;
        do {
            generation = Utilities.random.nextLong();
        } while (generation == 0);
        target = new VideoUploadNotificationTarget(currentAccount, owner, message.messageObject.getDialogId(),
                message.messageObject.getId(), message.messageObject.messageOwner.random_id, currentPath, generation);
        if (!isCurrentOwner() || TextUtils.isEmpty(currentPath)) {
            builder = null;
            stopSelf();
            return;
        }
        updateBuilderForMessage(message);
        observeCurrentAccount(true);
        if (isRunning()) {
            updateNotification();
        }
    }

    private void observeCurrentAccount(boolean add) {
        if (currentMessage == null) {
            return;
        }
        int[] events = {NotificationCenter.fileUploadProgressChanged, NotificationCenter.fileUploadFailed,
                NotificationCenter.fileUploaded, NotificationCenter.fileNewChunkAvailable,
                NotificationCenter.filePreparingFailed, NotificationCenter.appDidLogout};
        for (int event : events) {
            if (add) {
                NotificationCenter.getInstance(currentAccount).addObserver(this, event);
            } else {
                NotificationCenter.getInstance(currentAccount).removeObserver(this, event);
            }
        }
    }

    private boolean isCurrentOwner() {
        return target != null && target.owner > 0 && UserConfig.isValidAccount(target.account)
                && UserConfig.getInstance(target.account).isClientActivated()
                && UserConfig.getInstance(target.account).getClientUserId() == target.owner;
    }

    private void cancelPendingIntents() {
        if (openIntent != null) {
            openIntent.cancel();
            openIntent = null;
        }
        if (cancelIntent != null) {
            cancelIntent.cancel();
            cancelIntent = null;
        }
    }

    private void advanceOrStop() {
        MediaController.VideoConvertMessage next = MediaController.getInstance().getCurrentForegroundConverMessage();
        if (next != null && next != currentMessage) {
            setCurrentMessage(next);
        } else {
            stopSelf();
        }
    }

    static void cancelFromNotification(Intent intent) {
        VideoEncodingService service = instance;
        if (service == null || service.currentMessage == null || service.target == null || !service.isCurrentOwner()) {
            return;
        }
        VideoUploadNotificationTarget target = service.target;
        if (!target.matches(intent.getIntExtra("ox_video_account", -1), intent.getLongExtra("ox_video_owner", 0),
                intent.getLongExtra("ox_video_dialog", 0), intent.getIntExtra("ox_video_message", 0),
                intent.getLongExtra("ox_video_random", 0), intent.getStringExtra("ox_video_path"),
                intent.getLongExtra("ox_video_generation", 0))) {
            return;
        }
        MessageObject message = service.currentMessage.messageObject;
        if (message == null || message.messageOwner == null || !target.matches(message.currentAccount,
                UserConfig.getInstance(message.currentAccount).getClientUserId(), message.getDialogId(), message.getId(),
                message.messageOwner.random_id, message.messageOwner.attachPath, target.generation)) {
            return;
        }
        if (service.currentMessage.foregroundConversion) {
            if (MediaController.getInstance().getCurrentForegroundConverMessage() != service.currentMessage) {
                return;
            }
            MediaController.getInstance().cancelVideoConvert(message);
        } else if (!SendMessagesHelper.getInstance(target.account).cancelVideoSendingFromNotification(message,
                target.owner, target.dialogId, target.messageId, target.randomId, target.path)) {
            return;
        }
        if (instance == service && service.target == target) {
            service.advanceOrStop();
        }
    }

    public static boolean isRunning() {
        return instance != null;
    }

}
