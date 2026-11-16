package org.telegram.messenger.ayu.radar;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.text.TextUtils;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.ayu.AyuConstants;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;

/**
 * AyuGram Mention Radar: optional local notification for keyword hits.
 * <p>
 * Deliberately self-contained — {@code NotificationsController} exposes no public "post an arbitrary
 * notification" entry point (everything there renders the push message stack), so the radar owns a
 * private low-importance channel exactly like {@code AyuKeepAliveService} does. Tapping the
 * notification opens the app; there is no deep link into the radar feed because that would require
 * patching {@code LaunchActivity}.
 */
public class RadarNotifier {

    private static final String CHANNEL_ID = "ayu_radar";
    /** far away from Telegram's own notification ids (and from AyuKeepAliveService's 3117) */
    private static final int NOTIFICATION_ID_BASE = 74100;

    public static void notifyKeywordHits(int account, ArrayList<RadarHit> hits) {
        if (hits == null || hits.isEmpty() || !RadarConfig.notifyOnKeyword) {
            return;
        }
        final RadarHit last = hits.get(hits.size() - 1);
        final int count = hits.size();
        AndroidUtilities.runOnUIThread(() -> post(account, last, count));
    }

    private static void post(int account, RadarHit hit, int count) {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null || hit == null) {
                return;
            }
            createChannel(context);

            String chatName = null;
            try {
                chatName = DialogObject.getName(account, hit.dialogId);
            } catch (Throwable ignore) {
            }
            String title;
            if (count > 1) {
                title = LocaleController.formatString(R.string.RadarNotificationTitleMany, count);
            } else if (!TextUtils.isEmpty(hit.matched)) {
                title = LocaleController.formatString(R.string.RadarNotificationTitle, hit.matched);
            } else {
                title = LocaleController.getString(R.string.RadarTitle);
            }
            String text = hit.snippet;
            if (TextUtils.isEmpty(text)) {
                text = TextUtils.isEmpty(chatName) ? AyuConstants.APP_NAME : chatName;
            } else if (!TextUtils.isEmpty(chatName)) {
                text = chatName + ": " + text;
            }

            Intent contentIntent = new Intent(context, LaunchActivity.class);
            contentIntent.setAction(Intent.ACTION_MAIN);
            contentIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pendingIntent = PendingIntent.getActivity(context, NOTIFICATION_ID_BASE + account, contentIntent, flags);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.notification)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setShowWhen(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE);

            Notification notification = builder.build();
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_BASE + account, notification);
        } catch (Throwable e) {
            FileLog.e(e);
        }
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
                    AyuConstants.APP_NAME + " " + LocaleController.getString(R.string.RadarTitle),
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(LocaleController.getString(R.string.RadarNotifyKeywordsInfo));
            channel.enableVibration(false);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void cancel(int account) {
        try {
            NotificationManagerCompat.from(ApplicationLoader.applicationContext).cancel(NOTIFICATION_ID_BASE + account);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
