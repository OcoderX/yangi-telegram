package org.telegram.messenger.ayu.hidden;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatsWidgetProvider;
import org.telegram.messenger.ContactsWidgetProvider;
import org.telegram.messenger.FeedWidgetProvider;
import org.telegram.messenger.R;

/** Refresh cached launcher rows when the local visibility policy changes. */
final class HiddenChatsWidgets {
    static void refresh() {
        Context context = ApplicationLoader.applicationContext;
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            Class<?>[] providers = {ChatsWidgetProvider.class, ContactsWidgetProvider.class, FeedWidgetProvider.class};
            for (Class<?> provider : providers) {
                int[] ids = manager.getAppWidgetIds(new ComponentName(context, provider));
                if (ids.length != 0) manager.notifyAppWidgetViewDataChanged(ids, R.id.list_view);
            }
        } catch (RuntimeException ignored) {
            // Some devices disable widgets. The factories still filter every subsequent row request.
        }
    }
}
