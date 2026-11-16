package org.telegram.messenger.bot;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.SparseArray;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Bot accounts (logged in with auth.importBotAuthorization) are refused a large part of the API
 * by the server. Firing those requests anyway costs a round trip and floods the logs, so they are
 * short-circuited here with the very same error the server would have returned.
 *
 * <p>The list below only holds the methods we are certain about; everything else is sent normally
 * and {@link #onRequestError} remembers whatever comes back as BOT_METHOD_INVALID, so the client
 * learns the rest by itself and stays correct if Telegram ever relaxes a restriction. Only that
 * exact error is learned: a flood wait, a timeout or a network failure never blocks a method.
 *
 * <p>The learned set is kept per account slot and wiped when that slot logs out.
 */
public class BotAccountHelper {

    public static final String BOT_METHOD_INVALID = "BOT_METHOD_INVALID";

    private static final String PREFS_NAME = "botmethods";
    private static final String PREFS_KEY_PREFIX = "blocked_";

    /**
     * Methods Telegram is known to refuse for bots. Keys come from {@link #methodKey}: classes
     * nested in TLRPC use their simple name, classes generated into {@code tgnet.tl} are prefixed
     * with their outer class (e.g. {@code TL_account.updateStatus},
     * {@code TL_stories.TL_stories_getAllStories}).
     */
    private static final Set<String> KNOWN_BLOCKED = new HashSet<>(Arrays.asList(
            // the dialog list and everything derived from it
            "TL_messages_getDialogs",
            "TL_messages_getPeerDialogs",
            "TL_messages_getPinnedDialogs",
            "TL_messages_getDialogUnreadMarks",
            "TL_messages_getDialogFilters",
            "TL_messages_getSuggestedDialogFilters",
            "TL_messages_updateDialogFilter",
            "TL_messages_updateDialogFiltersOrder",
            "TL_messages_getAllDrafts",
            "TL_messages_getSavedDialogs",
            "TL_messages_toggleDialogPin",
            "TL_messages_reorderPinnedDialogs",
            "TL_messages_markDialogUnread",
            "TL_folders_editPeerFolders",
            // read receipts
            "TL_messages_readHistory",
            "TL_messages_readMentions",
            "TL_messages_readReactions",
            "TL_messages_readDiscussion",
            "TL_channels_readHistory",
            "TL_channels_readMessageContents",
            // contacts
            "TL_contacts_getContacts",
            "TL_contacts_getStatuses",
            "TL_contacts_importContacts",
            "TL_contacts_addContact",
            "TL_contacts_deleteContacts",
            "TL_contacts_deleteByPhones",
            "TL_contacts_getTopPeers",
            "TL_contacts_resetTopPeerRating",
            "TL_contacts_toggleTopPeers",
            "TL_contacts_getBlocked",
            "TL_contacts_block",
            "TL_contacts_unblock",
            "TL_contacts_getSaved",
            "TL_contacts_resetSaved",
            "TL_contacts_getLocated",
            "TL_contacts_getBirthdays",
            // account settings, none of which a bot owns
            "TL_account.updateStatus",
            "TL_account.registerDevice",
            "TL_account.unregisterDevice",
            "TL_account.getNotifySettings",
            "TL_account.updateNotifySettings",
            "TL_account.getNotifyExceptions",
            "TL_account.resetNotifySettings",
            "TL_account.getGlobalPrivacySettings",
            "TL_account.setGlobalPrivacySettings",
            "TL_account.getContactSignUpNotification",
            "TL_account.setContactSignUpNotification",
            "TL_account.getReactionsNotifySettings",
            "TL_account.getAccountTTL",
            "TL_account.setAccountTTL",
            "TL_account.getPassword",
            "TL_account.getPasswordSettings",
            "TL_account.updatePasswordSettings",
            "TL_account.getPrivacy",
            "TL_account.setPrivacy",
            "TL_account.getAuthorizations",
            "TL_account.resetAuthorization",
            "TL_account.getWebAuthorizations",
            "TL_account.getBirthdays",
            "TL_account.updateBirthday",
            "TL_account.getContentSettings",
            "TL_account.setContentSettings",
            "TL_account.getDefaultEmojiStatuses",
            "TL_account.getSavedRingtones",
            "TL_account.getThemes",
            "TL_account.getWallPapers",
            "TL_account.getAutoDownloadSettings",
            "TL_account.saveAutoDownloadSettings",
            "TL_account.updateProfile",
            "TL_account.updateUsername",
            "TL_account.sendChangePhoneCode",
            "TL_account.changePhone",
            "TL_account.deleteAccount",
            "TL_account.getTmpPassword",
            // auth flows that only make sense for a human
            "TL_auth_exportLoginToken",
            "TL_auth_acceptLoginToken",
            // promo / suggestions aimed at human accounts
            "TL_help_getPromoData",
            "TL_help_hidePromoData",
            "TL_help_getRecentMeUrls",
            "TL_help_getPremiumPromo",
            "TL_help_getInviteText",
            "TL_help_getTermsOfServiceUpdate",
            "TL_help_acceptTermsOfService",
            // stories
            "TL_stories.TL_stories_getAllStories",
            "TL_stories.TL_stories_getAllReadPeerStories",
            "TL_stories.TL_stories_getPeerStories",
            "TL_stories.TL_stories_getStoriesArchive",
            "TL_stories.TL_stories_getPinnedStories",
            "TL_stories.TL_stories_readStories",
            "TL_stories.TL_stories_incrementStoryViews",
            "TL_stories.TL_stories_getPeerMaxIDs",
            "TL_stories.TL_stories_getChatsToSend",
            // calls
            "TL_phone_requestCall",
            "TL_phone_getCallConfig"
    ));

    private static final SparseArray<Set<String>> learnedBlocked = new SparseArray<>();

    public static boolean isBotAccount(int account) {
        return UserConfig.isBotAccount(account);
    }

    /**
     * Display name of an account for the account switchers: the user name, followed by a small
     * "bot" badge when the slot holds a bot, so bots are told apart from people at a glance.
     */
    public static CharSequence accountName(int account, TLRPC.User user, int badgeColor) {
        final String name = UserObject.getUserName(user);
        if (user == null || !user.bot) {
            return name;
        }
        final SpannableStringBuilder sb = new SpannableStringBuilder(name).append(" ");
        final int start = sb.length();
        sb.append(LocaleController.getString(R.string.AyuBotAccountBadge));
        sb.setSpan(new RelativeSizeSpan(0.75f), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(badgeColor), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    /**
     * Stable identifier of a request class, e.g. {@code TL_messages_getDialogs} for classes nested
     * in TLRPC and {@code TL_account.updateStatus} for the generated ones in {@code tgnet.tl}.
     */
    public static String methodKey(TLObject object) {
        if (object == null) {
            return "";
        }
        Class<?> cls = object.getClass();
        Class<?> outer = cls.getEnclosingClass();
        String name = cls.getSimpleName();
        if (outer != null && !"TLRPC".equals(outer.getSimpleName())) {
            return outer.getSimpleName() + "." + name;
        }
        return name;
    }

    /** True when this method is in the hardcoded list, independent of the account. */
    public static boolean isKnownBlocked(TLObject object) {
        return object != null && KNOWN_BLOCKED.contains(methodKey(object));
    }

    /** True when this request must not leave the device for this account. */
    public static boolean shouldBlock(int account, TLObject object) {
        if (object == null || !isBotAccount(account)) {
            return false;
        }
        final String key = methodKey(object);
        if (KNOWN_BLOCKED.contains(key)) {
            return true;
        }
        return learned(account).contains(key);
    }

    /** True when the error is the bot-restriction error, synthetic or from the server. */
    public static boolean isBotMethodInvalid(TLRPC.TL_error error) {
        return error != null && error.text != null && error.text.contains(BOT_METHOD_INVALID);
    }

    /** The error the server would have produced, so callers behave exactly as they do online. */
    public static TLRPC.TL_error blockedError() {
        TLRPC.TL_error error = new TLRPC.TL_error();
        error.code = 400;
        error.text = BOT_METHOD_INVALID;
        return error;
    }

    /** Remembers a method the server just refused, so it is never sent again for this account. */
    public static void onRequestError(int account, TLObject object, TLRPC.TL_error error) {
        if (object == null || !isBotMethodInvalid(error)) {
            return;
        }
        if (!isBotAccount(account)) {
            return;
        }
        final String key = methodKey(object);
        if (key.isEmpty() || KNOWN_BLOCKED.contains(key)) {
            return;
        }
        final Set<String> set = learned(account);
        if (set.add(key)) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("bot mode: " + key + " is not available for bots, will not be sent again (account " + account + ")");
            }
            saveLearned(account, set);
        }
    }

    /** Forgets everything learned for this slot; called when the account logs out. */
    public static void onLogout(int account) {
        synchronized (BotAccountHelper.class) {
            learnedBlocked.remove(account);
        }
        try {
            SharedPreferences preferences = getPreferences();
            if (preferences != null) {
                preferences.edit().remove(PREFS_KEY_PREFIX + account).apply();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static Set<String> learned(int account) {
        synchronized (BotAccountHelper.class) {
            Set<String> set = learnedBlocked.get(account);
            if (set == null) {
                set = Collections.synchronizedSet(new HashSet<>());
                try {
                    SharedPreferences preferences = getPreferences();
                    if (preferences != null) {
                        Set<String> stored = preferences.getStringSet(PREFS_KEY_PREFIX + account, null);
                        if (stored != null) {
                            set.addAll(stored);
                        }
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
                learnedBlocked.put(account, set);
            }
            return set;
        }
    }

    private static void saveLearned(int account, Set<String> set) {
        try {
            SharedPreferences preferences = getPreferences();
            if (preferences == null) {
                return;
            }
            preferences.edit().putStringSet(PREFS_KEY_PREFIX + account, new HashSet<>(set)).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static SharedPreferences getPreferences() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return null;
        }
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
