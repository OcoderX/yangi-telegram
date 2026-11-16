package org.telegram.messenger.ayu.contacts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.ayu.AyuConstants;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.LaunchActivity;

/**
 * Ox-gram special contacts: the local "went online / went offline" notification.
 * <p>
 * Self contained for the same reason {@code org.telegram.messenger.ayu.radar.RadarNotifier} is:
 * {@code NotificationsController} only renders the push message stack and has no entry point for an
 * arbitrary local notification, so the feature owns a private channel, created exactly like the one
 * of {@code AyuKeepAliveService}. Tapping opens the app.
 */
public class ContactNotifier {

    private static final String CHANNEL_ID = "ox_contacts";
    /** far away from Telegram's own ids, from AyuKeepAliveService's 3117 and from the radar's 74100 */
    private static final int NOTIFICATION_ID_BASE = 74300;

    public static void notifyStatus(int account, TLRPC.User user, boolean online) {
        if (user == null) {
            return;
        }
        final String name = UserObject.getUserName(user);
        final long userId = user.id;
        AndroidUtilities.runOnUIThread(() -> post(account, userId, name, online));
    }

    private static void post(int account, long userId, String name, boolean online) {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return;
            }
            createChannel(context);

            String title = LocaleController.formatString(online ? R.string.OxContactsIsOnline : R.string.OxContactsWentOffline, name);
            String text = LocaleController.getString(R.string.OxContactsSpecialTitle);

            Intent contentIntent = new Intent(context, LaunchActivity.class);
            contentIntent.setAction(Intent.ACTION_MAIN);
            contentIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            int notificationId = notificationId(account, userId);
            PendingIntent pendingIntent = PendingIntent.getActivity(context, notificationId, contentIntent, flags);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.notification)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setShowWhen(true)
                    .setOnlyAlertOnce(false)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setCategory(NotificationCompat.CATEGORY_STATUS);

            Notification notification = builder.build();
            NotificationManagerCompat.from(context).notify(notificationId, notification);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static int notificationId(int account, long userId) {
        return NOTIFICATION_ID_BASE + account * 1000 + (int) Math.abs(userId % 1000);
    }

    private static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        try {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) {
                return;
            }
            if (manager.getNotificationChannel(CHANNEL_ID) != null) {
                return;
            }
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    AyuConstants.APP_NAME + " " + LocaleController.getString(R.string.OxContactsSpecialTitle),
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(LocaleController.getString(R.string.OxContactsNotifyInfo));
            channel.enableVibration(false);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void cancel(int account, long userId) {
        try {
            NotificationManagerCompat.from(ApplicationLoader.applicationContext).cancel(notificationId(account, userId));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
