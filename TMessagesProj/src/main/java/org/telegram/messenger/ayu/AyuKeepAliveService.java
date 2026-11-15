package org.telegram.messenger.ayu;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.ayu.upload.AyuUploadConfig;
import org.telegram.messenger.ayu.upload.AyuUploadManager;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.LaunchActivity;

/**
 * AyuGram "keep-alive" service.
 * <p>
 * A foreground service that holds activated accounts' update connections while Android permits
 * background data synchronization. Transport holds do not change UI pause state or online presence.
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
    private static boolean startRequested;
    private static boolean restartBlocked;
    private static boolean restartBlockLoaded;
    private static final String TIMEOUT_PREFS = "ayu_background_service";
    private static final String TIMEOUT_BLOCKED = "timeout_blocked";

    private final ConnectionsManager[] heldConnections = new ConnectionsManager[UserConfig.MAX_ACCOUNT_COUNT];
    private final long[] heldOwners = new long[UserConfig.MAX_ACCOUNT_COUNT];
    private boolean stopping;
    private boolean restartAfterStop;

    /**
     * The service is needed either because the user wants the process kept alive, or because the
     * AyuGram upload queue still has files to send and needs foreground execution priority.
     */
    private static boolean shouldRun() {
        if (AyuConfig.keepAliveService) {
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                if (UserConfig.getInstance(account).isClientActivated()) {
                    return true;
                }
            }
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
        final Context appContext = context == null ? ApplicationLoader.applicationContext : context.getApplicationContext();
        AndroidUtilities.runOnUIThread(() -> {
            final AyuKeepAliveService local = instance;
            if (local != null && running && !local.stopping) {
                if (!shouldRun()) {
                    checkStateOnMain(appContext);
                    return;
                }
                final long now = System.currentTimeMillis();
                final boolean uploading = isUploading();
                // Throttle progress refreshes, but never skip clearing the progress bar.
                if (uploading && now - lastNotificationUpdate < 1000) {
                    return;
                }
                lastNotificationUpdate = now;
                local.updateNotification();
                return;
            }
            checkStateOnMain(appContext);
        });
    }

    /**
     * Starts the service when {@link AyuConfig#keepAliveService} is enabled and stops it otherwise.
     * Safe to call from any thread and any number of times.
     */
    public static void checkState(Context context) {
        final Context appContext = context == null ? ApplicationLoader.applicationContext : context.getApplicationContext();
        AndroidUtilities.runOnUIThread(() -> checkStateOnMain(appContext));
    }

    /** Login/logout and slot reuse are reconciled from current identity, never a queued old owner. */
    public static void onAccountChanged() {
        checkState(ApplicationLoader.applicationContext);
    }

    /** A real foreground interaction allows a fresh attempt after an OS limit or start rejection. */
    public static void onAppForeground() {
        AndroidUtilities.runOnUIThread(() -> {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return;
            }
            SharedPreferences preferences = context.getSharedPreferences(TIMEOUT_PREFS, Context.MODE_PRIVATE);
            if (preferences.getBoolean(TIMEOUT_BLOCKED, false)) {
                preferences.edit().remove(TIMEOUT_BLOCKED).apply();
            }
            restartBlockLoaded = true;
            restartBlocked = false;
            checkStateOnMain(context);
        });
    }

    private static void loadRestartBlock(Context context) {
        if (!restartBlockLoaded) {
            restartBlocked = context.getSharedPreferences(TIMEOUT_PREFS, Context.MODE_PRIVATE).getBoolean(TIMEOUT_BLOCKED, false);
            restartBlockLoaded = true;
        }
    }

    private static void checkStateOnMain(Context context) {
        if (context == null) {
            return;
        }
        loadRestartBlock(context);
        final boolean needed = !restartBlocked && shouldRun();
        final AyuKeepAliveService local = instance;
        if (local != null) {
            if (local.stopping) {
                local.restartAfterStop = needed;
                return;
            }
            if (!needed) {
                local.stopSelfSafely();
            } else if (running) {
                local.reconcileNetworkHolds();
                local.updateNotification();
            }
            return;
        }
        if (!needed || startRequested) {
            return;
        }
        final Intent intent = new Intent(context, AyuKeepAliveService.class);
        try {
            startRequested = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable e) {
            startRequested = false;
            restartBlocked = true;
            // Retry on user foreground, not on every upload progress callback.
            FileLog.e(e);
        }
    }

    public static boolean isRunning() {
        return running;
    }

    private static NotificationCenter.NotificationCenterDelegate configObserver;

    /**
     * Applies the current AyuGram options once and keeps the keep-alive service
     * in step with later changes to them (posted as
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
        startRequested = false;
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
        startRequested = false;
        // The service may have been started with startForegroundService(); Android then *requires*
        // startForeground() to be called before the service goes away, even when the option was
        // switched off in the meantime. So always enter the foreground first, then stop if disabled.
        final boolean inForeground = enterForeground();
        if (!inForeground) {
            restartBlocked = true;
            stopSelfSafely();
            return START_NOT_STICKY;
        }
        ApplicationLoader.postInitApplication();
        loadRestartBlock(this);
        if (stopping || restartBlocked || !shouldRun()) {
            stopSelfSafely();
            return START_NOT_STICKY;
        }
        running = true;
        reconcileNetworkHolds();
        return START_STICKY;
    }

    private void reconcileNetworkHolds() {
        for (int account = 0; account < heldOwners.length; account++) {
            long owner = running && !stopping && AyuConfig.keepAliveService
                    ? UserConfig.getInstance(account).getClientUserId() : 0;
            if (heldOwners[account] == owner) {
                continue;
            }
            if (heldConnections[account] != null) {
                heldConnections[account].setBackgroundNetworkEnabled(heldOwners[account], false);
                heldConnections[account] = null;
                heldOwners[account] = 0;
            }
            if (owner != 0) {
                ConnectionsManager connection = ConnectionsManager.getInstance(account);
                connection.setBackgroundNetworkEnabled(owner, true);
                heldConnections[account] = connection;
                heldOwners[account] = owner;
            }
        }
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        // Android 15 dataSync limits also apply to sticky restarts. Only user foreground unblocks it.
        restartBlocked = true;
        restartBlockLoaded = true;
        getSharedPreferences(TIMEOUT_PREFS, Context.MODE_PRIVATE).edit().putBoolean(TIMEOUT_BLOCKED, true).apply();
        restartAfterStop = false;
        stopSelfSafely();
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
        stopping = true;
        running = false;
        reconcileNetworkHolds();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Throwable ignore) {
        }
        try {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.cancel(NOTIFICATION_ID);
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
        } else {
            builder.setProgress(0, 0, false);
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
        stopping = true;
        reconcileNetworkHolds();
        if (instance == this) {
            running = false;
            instance = null;
        }
        super.onDestroy();
        if (restartAfterStop && !restartBlocked) {
            checkState(ApplicationLoader.applicationContext);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
