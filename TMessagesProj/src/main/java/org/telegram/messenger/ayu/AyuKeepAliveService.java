package org.telegram.messenger.ayu;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.ayu.sync.AyuSyncController;
import org.telegram.messenger.ayu.upload.AyuUploadConfig;
import org.telegram.messenger.ayu.upload.AyuUploadManager;
import org.telegram.ui.LaunchActivity;

/**
 * AyuGram "keep-alive" service.
 * <p>
 * A minimal foreground service whose only purpose is to keep the application process alive so that
 * updates (and therefore the deleted/edited message history) keep being received while the app is in
 * the background. It does no work of its own: the persistent low-priority notification is what makes
 * Android treat the process as foreground-important.
 * <p>
 * Controlled by {@link AyuConfig#keepAliveService}; call {@link #checkState(Context)} after the flag
 * changes (and once from ApplicationLoader after init) to start or stop it.
 */
public class AyuKeepAliveService extends Service {

    public static final String NOTIFICATION_CHANNEL_ID = "ayu_keepalive";
    private static final int NOTIFICATION_ID = 3117;

    private static volatile boolean running;
    private static volatile AyuKeepAliveService instance;
    private static volatile long lastNotificationUpdate;

    /**
     * The service is needed either because the user wants the process kept alive, or because the
     * AyuGram upload queue still has files to send (a foreground service is the only way Android
     * lets an upload continue while the app is in the background).
     */
    private static boolean shouldRun() {
        if (AyuConfig.keepAliveService) {
            return true;
        }
        try {
            return AyuUploadManager.hasActiveUploads();
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean isUploading() {
        try {
            return AyuUploadConfig.keepAliveWhileUploading && AyuUploadManager.getPendingCount() > 0;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Called by the AyuGram upload queue whenever it changed: starts the service when the queue is
     * no longer empty, refreshes the progress notification while it runs and stops it again once
     * nothing is left to upload (unless the keep-alive option itself is on).
     */
    public static void onUploadStatusChanged(Context context) {
        if (context == null) {
            context = ApplicationLoader.applicationContext;
        }
        final AyuKeepAliveService local = instance;
        if (local != null && running && shouldRun()) {
            final long now = System.currentTimeMillis();
            // throttle progress refreshes, but never skip the one that clears the progress bar
            if (isUploading() && now - lastNotificationUpdate < 1000) {
                return;
            }
            lastNotificationUpdate = now;
            local.updateNotification();
            return;
        }
        checkState(context);
    }

    /**
     * Starts the service when {@link AyuConfig#keepAliveService} is enabled and stops it otherwise.
     * Safe to call from any thread and any number of times.
     */
    public static void checkState(Context context) {
        if (context == null) {
            context = ApplicationLoader.applicationContext;
        }
        if (context == null) {
            return;
        }
        final Intent intent = new Intent(context, AyuKeepAliveService.class);
        try {
            if (shouldRun()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent);
                } else {
                    context.startService(intent);
                }
            } else if (running) {
                context.stopService(intent);
            }
        } catch (Throwable e) {
            // starting a foreground service from the background throws on API 31+; never crash for it
            FileLog.e(e);
        }
    }

    public static boolean isRunning() {
        return running;
    }

    private static NotificationCenter.NotificationCenterDelegate configObserver;

    /**
     * Applies the current AyuGram options once and keeps the keep-alive service and the AyuSync
     * client in step with later changes to them (posted as
     * {@link NotificationCenter#ayuConfigChanged}). Called once from ApplicationLoader.
     */
    public static void init(final Context context) {
        checkState(context);
        AndroidUtilities.runOnUIThread(() -> {
            if (configObserver != null) {
                return;
            }
            configObserver = (id, account, args) -> {
                if (id != NotificationCenter.ayuConfigChanged) {
                    return;
                }
                checkState(ApplicationLoader.applicationContext);
                try {
                    AyuSyncController.getInstance().checkState();
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            };
            try {
                NotificationCenter.getGlobalInstance().addObserver(configObserver, NotificationCenter.ayuConfigChanged);
            } catch (Throwable e) {
                FileLog.e(e);
                configObserver = null;
            }
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        instance = this;
    }

    /** refreshes the ongoing notification in place (upload progress) */
    private void updateNotification() {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, buildNotification());
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // The service may have been started with startForegroundService(); Android then *requires*
        // startForeground() to be called before the service goes away, even when the option was
        // switched off in the meantime. So always enter the foreground first, then stop if disabled.
        final boolean inForeground = enterForeground();
        if (!inForeground) {
            stopSelfSafely();
            return START_NOT_STICKY;
        }
        if (!shouldRun()) {
            stopSelfSafely();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    /** promotes the service to the foreground; returns false when that was not possible */
    private boolean enterForeground() {
        try {
            final Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    private void stopSelfSafely() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Throwable ignore) {
        }
        try {
            stopSelf();
        } catch (Throwable ignore) {
        }
    }

    private Notification buildNotification() {
        createChannel();

        Intent contentIntent = new Intent(this, LaunchActivity.class);
        contentIntent.setAction(Intent.ACTION_MAIN);
        contentIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, contentIntent, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.notification)
                .setContentTitle(AyuConstants.APP_NAME)
                .setContentText(LocaleController.getString(R.string.AyuKeepAliveNotification))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET);
        if (isUploading()) {
            // AyuGram upload queue: show what is still being uploaded instead of the idle text
            final int pending = AyuUploadManager.getPendingCount();
            final int progress = AyuUploadManager.getOverallProgress();
            builder.setContentTitle(LocaleController.getString(R.string.AyuUploadNotificationTitle));
            builder.setContentText(LocaleController.formatString(R.string.AyuUploadNotificationText, pending));
            builder.setProgress(100, Math.max(0, progress), progress < 0);
            builder.setPriority(NotificationCompat.PRIORITY_LOW);
            builder.setCategory(NotificationCompat.CATEGORY_PROGRESS);
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE);
        }
        return builder.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        try {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) {
                return;
            }
            if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) {
                return;
            }
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, AyuConstants.APP_NAME + " keep-alive", NotificationManager.IMPORTANCE_MIN);
            channel.setDescription("Keeps " + AyuConstants.APP_NAME + " running in the background");
            channel.enableLights(false);
            channel.enableVibration(false);
            channel.setSound(null, null);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
