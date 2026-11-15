package org.telegram.messenger.ayu.contacts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.util.SparseIntArray;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
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
    private static final String NOTIFICATION_TAG_PREFIX = CHANNEL_ID + ":";
    // Accessed on the UI thread only; track posted IDs, rather than scanning every contact slot.
    private static final SparseIntArray postedAccounts = new SparseIntArray();

    private static boolean canNotify(int account) {
        return SharedConfig.showNotificationsForAllAccounts || account == UserConfig.selectedAccount;
    }

    public static void notifyStatus(int account, TLRPC.User user, boolean online) {
        if (user == null || !canNotify(account)
                || org.telegram.messenger.ayu.hidden.HiddenChatsController.getInstance(account).isHidden(user.id)) {
            return;
        }
        final String name = UserObject.getUserName(user);
        final long userId = user.id;
        AndroidUtilities.runOnUIThread(() -> post(account, userId, name, online));
    }

    private static void post(int account, long userId, String name, boolean online) {
        if (!canNotify(account) || org.telegram.messenger.ayu.hidden.HiddenChatsController.getInstance(account).isHidden(userId)) {
            return;
        }
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
            if (!canNotify(account)) {
                return;
            }
            NotificationManagerCompat manager = NotificationManagerCompat.from(context);
            String tag = NOTIFICATION_TAG_PREFIX + account;
            manager.cancel(notificationId); // Replace a surviving notification from the untagged format.
            if (!canNotify(account)) {
                return;
            }
            manager.notify(tag, notificationId, notification);
            if (canNotify(account)) {
                postedAccounts.put(notificationId, account);
            } else {
                manager.cancel(tag, notificationId);
                postedAccounts.delete(notificationId);
            }
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
        AndroidUtilities.runOnUIThread(() -> {
            try {
                NotificationManagerCompat manager = NotificationManagerCompat.from(ApplicationLoader.applicationContext);
                int id = notificationId(account, userId);
                manager.cancel(NOTIFICATION_TAG_PREFIX + account, id);
                manager.cancel(id);
                postedAccounts.delete(id);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    public static void cancelInactiveNotifications() {
        if (SharedConfig.showNotificationsForAllAccounts) {
            return;
        }
        AndroidUtilities.runOnUIThread(ContactNotifier::cancelInactiveNotificationsOnUIThread);
    }

    private static void cancelInactiveNotificationsOnUIThread() {
        if (SharedConfig.showNotificationsForAllAccounts) {
            return;
        }
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return;
            }
            NotificationManagerCompat manager = NotificationManagerCompat.from(context);
            for (int i = postedAccounts.size() - 1; i >= 0; i--) {
                if (SharedConfig.showNotificationsForAllAccounts) {
                    return;
                }
                int account = postedAccounts.valueAt(i);
                if (account != UserConfig.selectedAccount) {
                    int id = postedAccounts.keyAt(i);
                    manager.cancel(NOTIFICATION_TAG_PREFIX + account, id);
                    manager.cancel(id);
                    postedAccounts.removeAt(i);
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                cancelInactiveActiveNotifications(context);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.M)
    private static void cancelInactiveActiveNotifications(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        for (StatusBarNotification posted : manager.getActiveNotifications()) {
            if (SharedConfig.showNotificationsForAllAccounts) {
                return;
            }
            int offset = posted.getId() - NOTIFICATION_ID_BASE;
            if (offset < 0 || offset >= UserConfig.MAX_ACCOUNT_COUNT * 1000) {
                continue;
            }
            int account = offset / 1000;
            if (account == UserConfig.selectedAccount) {
                continue;
            }
            String tag = posted.getTag();
            boolean ours = (NOTIFICATION_TAG_PREFIX + account).equals(tag);
            if (tag == null) {
                Notification notification = posted.getNotification();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ours = CHANNEL_ID.equals(notification.getChannelId());
                } else {
                    // The old format predates tags and channels. Its reserved IDs, status
                    // category, icon and auto-cancel flag distinguish it from message/service posts.
                    ours = NotificationCompat.CATEGORY_STATUS.equals(notification.category)
                            && notification.icon == R.drawable.notification
                            && (notification.flags & Notification.FLAG_AUTO_CANCEL) != 0;
                }
            }
            if (ours) {
                manager.cancel(tag, posted.getId());
            }
        }
    }
}
