package org.telegram.messenger.bot;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
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
 * learns the rest by itself and stays correct if Telegram ever relaxes a restriction.
 */
public class BotAccountHelper {

    public static final String BOT_METHOD_INVALID = "BOT_METHOD_INVALID";

    private static final String PREFS_NAME = "botmethods";
    private static final String PREFS_KEY = "blocked";

    /** Methods Telegram is known to refuse for bots. Keys come from {@link #methodKey}. */
    private static final Set<String> KNOWN_BLOCKED = new HashSet<>(Arrays.asList(
            // the dialog list and everything derived from it
            "TL_messages_getDialogs",
            "TL_messages_getPeerDialogs",
            "TL_messages_getPinnedDialogs",
            "TL_messages_getDialogFilters",
            "TL_messages_getSuggestedDialogFilters",
            "TL_messages_getAllDrafts",
            // read receipts
            "TL_messages_readHistory",
            "TL_messages_readMentions",
            "TL_messages_readReactions",
            "TL_channels_readHistory",
            // contacts
            "TL_contacts_getContacts",
            "TL_contacts_getStatuses",
            "TL_contacts_importContacts",
            "TL_contacts_getTopPeers",
            "TL_contacts_getBlocked",
            "TL_contacts_getSaved",
            "TL_contacts_resetSaved",
            // account settings, none of which a bot owns
            "TL_account.updateStatus",
            "TL_account.registerDevice",
            "TL_account.unregisterDevice",
            "TL_account.getNotifySettings",
            "TL_account.updateNotifySettings",
            "TL_account.getGlobalPrivacySettings",
            "TL_account.getContactSignUpNotification",
            "TL_account.getReactionsNotifySettings",
            "TL_account.getAccountTTL",
            "TL_account.getPassword",
            "TL_account.getPrivacy",
            "TL_account.getAuthorizations",
            "TL_account.getBirthdays",
            "TL_account.getContentSettings",
            "TL_account.getDefaultEmojiStatuses",
            "TL_account.getSavedRingtones",
            "TL_account.getThemes",
            "TL_account.getWallPapers",
            "TL_account.getAutoDownloadSettings",
            // promo / suggestions aimed at human accounts
            "TL_help_getPromoData",
            "TL_help_getRecentMeUrls",
            "TL_help_getPremiumPromo",
            "TL_help_getInviteText",
            "TL_help_getTermsOfServiceUpdate",
            // stories
            "TL_stories.getAllStories",
            "TL_stories.getAllReadPeerStories"
    ));

    private static final Set<String> learnedBlocked = Collections.synchronizedSet(new HashSet<>());
    private static volatile boolean learnedLoaded;

    public static boolean isBotAccount(int account) {
        return UserConfig.isBotAccount(account);
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

    /** True when this request must not leave the device for this account. */
    public static boolean shouldBlock(int account, TLObject object) {
        if (object == null || !isBotAccount(account)) {
            return false;
        }
        final String key = methodKey(object);
        if (KNOWN_BLOCKED.contains(key)) {
            return true;
        }
        ensureLearnedLoaded();
        return learnedBlocked.contains(key);
    }

    /** The error the server would have produced, so callers behave exactly as they do online. */
    public static TLRPC.TL_error blockedError() {
        TLRPC.TL_error error = new TLRPC.TL_error();
        error.code = 400;
        error.text = BOT_METHOD_INVALID;
        return error;
    }

    /** Remembers a method the server just refused, so it is never sent again. */
    public static void onRequestError(int account, TLObject object, TLRPC.TL_error error) {
        if (object == null || error == null || error.text == null || !error.text.contains(BOT_METHOD_INVALID)) {
            return;
        }
        if (!isBotAccount(account)) {
            return;
        }
        final String key = methodKey(object);
        if (key.isEmpty() || KNOWN_BLOCKED.contains(key)) {
            return;
        }
        ensureLearnedLoaded();
        if (learnedBlocked.add(key)) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("bot mode: " + key + " is not available for bots, will not be sent again");
            }
            saveLearned();
        }
    }

    private static void ensureLearnedLoaded() {
        if (learnedLoaded) {
            return;
        }
        synchronized (BotAccountHelper.class) {
            if (learnedLoaded) {
                return;
            }
            try {
                SharedPreferences preferences = getPreferences();
                if (preferences != null) {
                    Set<String> stored = preferences.getStringSet(PREFS_KEY, null);
                    if (stored != null) {
                        learnedBlocked.addAll(stored);
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            learnedLoaded = true;
        }
    }

    private static void saveLearned() {
        try {
            SharedPreferences preferences = getPreferences();
            if (preferences == null) {
                return;
            }
            preferences.edit().putStringSet(PREFS_KEY, new HashSet<>(learnedBlocked)).apply();
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
