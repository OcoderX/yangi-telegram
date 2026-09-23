package org.telegram.messenger.ayu;

import android.app.Activity;
import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

/**
 * Ox-gram: once a month asks users who are NOT subscribed to the @OcoderXs channel whether they
 * want to join. "Subscribe" performs channels.joinChannel for the current account; "Later" postpones
 * the question for another month. Members are never asked.
 */
public class OcoderXChannelPrompt {

    public static final String CHANNEL_USERNAME = "OcoderXs";

    private static final String PREF_LAST_ASKED = "ox_channel_prompt_last_asked";
    private static final long ASK_INTERVAL = 30L * 24 * 60 * 60 * 1000; // once a month
    private static final long START_DELAY = 4000; // let the main screen settle first

    private static boolean checking;
    private static boolean scheduled;

    /** Called from LaunchActivity.onResume(). Cheap when nothing has to be done. */
    public static void checkAndShow(LaunchActivity activity) {
        if (activity == null || scheduled || checking) {
            return;
        }
        int account = UserConfig.selectedAccount;
        UserConfig userConfig = UserConfig.getInstance(account);
        if (!userConfig.isClientActivated() || userConfig.getCurrentUser() == null || userConfig.getCurrentUser().bot) {
            return;
        }
        SharedPreferences prefs = MessagesController.getGlobalMainSettings();
        long lastAsked = prefs.getLong(PREF_LAST_ASKED, 0);
        if (System.currentTimeMillis() - lastAsked < ASK_INTERVAL) {
            return;
        }
        scheduled = true;
        AndroidUtilities.runOnUIThread(() -> {
            scheduled = false;
            if (activity.isFinishing() || (SharedConfig.passcodeHash.length() > 0 && SharedConfig.appLocked)) {
                return;
            }
            resolveAndAsk(account, prefs);
        }, START_DELAY);
    }

    private static void resolveAndAsk(int account, SharedPreferences prefs) {
        checking = true;
        MessagesController controller = MessagesController.getInstance(account);
        controller.getUserNameResolver().resolve(CHANNEL_USERNAME, null, true, peerId -> {
            checking = false;
            if (peerId == null || peerId == Long.MAX_VALUE || peerId >= 0) {
                return;
            }
            long chatId = -peerId;
            TLRPC.Chat chat = controller.getChat(chatId);
            if (chat == null || !ChatObject.isChannel(chat)) {
                return;
            }
            long now = System.currentTimeMillis();
            if (!ChatObject.isNotInChat(chat)) {
                // already a member: never ask, re-check in a month
                prefs.edit().putLong(PREF_LAST_ASKED, now).apply();
                return;
            }
            BaseFragment fragment = LaunchActivity.getSafeLastFragment();
            Activity parent = fragment != null ? fragment.getParentActivity() : null;
            if (fragment == null || parent == null || parent.isFinishing()) {
                return;
            }
            prefs.edit().putLong(PREF_LAST_ASKED, now).apply();

            AlertDialog.Builder builder = new AlertDialog.Builder(parent);
            builder.setTitle(LocaleController.getString(R.string.OxChannelPromptTitle));
            builder.setMessage(LocaleController.formatString(R.string.OxChannelPromptText, "@" + CHANNEL_USERNAME));
            builder.setPositiveButton(LocaleController.getString(R.string.OxChannelPromptJoin), (dialog, which) -> join(account, chat, fragment));
            builder.setNegativeButton(LocaleController.getString(R.string.OxChannelPromptLater), null);
            fragment.showDialog(builder.create());
        });
    }

    private static void join(int account, TLRPC.Chat chat, BaseFragment fragment) {
        MessagesController controller = MessagesController.getInstance(account);
        TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        if (self == null) {
            return;
        }
        controller.addUserToChat(chat.id, self, 0, null, fragment, () -> {
            TLRPC.Chat updated = controller.getChat(chat.id);
            if (updated != null && !ChatObject.isNotInChat(updated) && fragment.getParentActivity() != null) {
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check,
                        LocaleController.formatString(R.string.OxChannelPromptJoined, "@" + CHANNEL_USERNAME)).show();
            }
        });
    }
}
