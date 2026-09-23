package org.telegram.messenger.ayu;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;

/**
 * Ox-gram: credentials of the OcoderX-sync userbot.
 * <p>
 * The user orders the service from @OcoderX, receives an API ID, an API hash and a session string
 * and stores them here. The userbot engine itself is not part of this class; it only has to call
 * {@link #hasCredentials()} and read {@link #apiId}, {@link #apiHash} and {@link #sessionString}.
 * <p>
 * Everything lives in its own SharedPreferences file ("ox_sync"), completely separate from
 * {@link AyuConfig} and from the old AyuSync server settings.
 */
public class OxSyncConfig {

    public static final String PREFS_NAME = "ox_sync";

    private static final String KEY_API_ID = "api_id";
    private static final String KEY_API_HASH = "api_hash";
    private static final String KEY_SESSION = "session_string";

    private static SharedPreferences preferences;
    private static boolean loaded;

    /** API ID issued by OcoderX, 0 when not set. */
    public static long apiId = 0;
    /** API hash issued by OcoderX, empty when not set. */
    public static String apiHash = "";
    /** Session string issued by OcoderX, empty when not set. */
    public static String sessionString = "";

    private OxSyncConfig() {
    }

    public static SharedPreferences getPreferences() {
        if (preferences == null) {
            preferences = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
        return preferences;
    }

    /** Reads the stored values into the static fields. Safe to call any number of times. */
    public static synchronized void load() {
        if (loaded) {
            return;
        }
        final SharedPreferences prefs = getPreferences();
        apiId = prefs.getLong(KEY_API_ID, 0);
        apiHash = prefs.getString(KEY_API_HASH, "");
        sessionString = prefs.getString(KEY_SESSION, "");
        loaded = true;
    }

    // ---------------------------------------------------------------- getters

    public static long getApiId() {
        load();
        return apiId;
    }

    public static String getApiHash() {
        load();
        return apiHash == null ? "" : apiHash;
    }

    public static String getSessionString() {
        load();
        return sessionString == null ? "" : sessionString;
    }

    // ---------------------------------------------------------------- setters

    public static void setApiId(long value) {
        load();
        apiId = value < 0 ? 0 : value;
        getPreferences().edit().putLong(KEY_API_ID, apiId).apply();
    }

    public static void setApiHash(String value) {
        load();
        apiHash = value == null ? "" : value.trim();
        getPreferences().edit().putString(KEY_API_HASH, apiHash).apply();
    }

    public static void setSessionString(String value) {
        load();
        sessionString = value == null ? "" : value.trim();
        getPreferences().edit().putString(KEY_SESSION, sessionString).apply();
    }

    // ---------------------------------------------------------------- state

    /** True when the API ID, the API hash and the session string are all filled in. */
    public static boolean isConfigured() {
        load();
        return apiId > 0 && !TextUtils.isEmpty(apiHash) && !TextUtils.isEmpty(sessionString);
    }

    /** Static helper for the future userbot engine. */
    public static boolean hasCredentials() {
        return isConfigured();
    }

    /** Removes every stored credential from this device. */
    public static void clear() {
        load();
        apiId = 0;
        apiHash = "";
        sessionString = "";
        getPreferences().edit()
                .remove(KEY_API_ID)
                .remove(KEY_API_HASH)
                .remove(KEY_SESSION)
                .apply();
    }
}
