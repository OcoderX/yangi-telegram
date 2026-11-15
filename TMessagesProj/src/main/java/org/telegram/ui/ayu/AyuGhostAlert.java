package org.telegram.ui.ayu;

import android.content.Context;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.ayu.AyuConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;

/**
 * Alerts shown by the ghost mode features.
 */
public class AyuGhostAlert {

    /**
     * "Alert before opening a story": warns that the author will see the view and offers to enable ghost mode.
     * The dialog is dismissed without doing anything when tapped outside.
     */
    public static void showStoryAlert(int account, Context context, Theme.ResourcesProvider resourcesProvider, Runnable onContinue) {
        if (context == null) {
            if (onContinue != null) {
                onContinue.run();
            }
            return;
        }
        final long ownerId = org.telegram.messenger.ayu.AyuGhostConfig.get(account).ownerId;
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(LocaleController.getString(R.string.AyuGhostStoryAlertTitle));
        builder.setMessage(LocaleController.getString(R.string.AyuGhostStoryAlertMessage));
        builder.setPositiveButton(LocaleController.getString(R.string.AyuGhostStoryAlertEnable), (dialog, which) -> {
            if (org.telegram.messenger.ayu.AyuGhostConfig.get(account).ownerId != ownerId) return;
            AyuConfig.setGhostMode(account, true);
            dialog.dismiss();
            if (onContinue != null) {
                onContinue.run();
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.AyuGhostStoryAlertOpenAnyway), (dialog, which) -> {
            if (org.telegram.messenger.ayu.AyuGhostConfig.get(account).ownerId != ownerId) return;
            dialog.dismiss();
            if (onContinue != null) {
                onContinue.run();
            }
        });
        builder.show();
    }
}
