package org.telegram.messenger.ayu.contactchanges;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * OcoderX "Contacts Changes" configuration.
 * <p>
 * Owns its own SharedPreferences file ({@link #PREFS_NAME}) so the feature can be added or removed
 * without touching any shared settings class (same pattern as
 * {@code org.telegram.messenger.ayu.radar.RadarConfig}).
 */
public class ContactChangesConfig {

    public static final String PREFS_NAME = "ox_contact_changes";

    /** master switch; when false nothing is recorded */
    public static volatile boolean enabled = true;

    private static SharedPreferences preferences;
    private static volatile boolean loaded;

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        enabled = preferences.getBoolean("enabled", true);
        loaded = true;
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    /** cheap: safe to call from the {@code putUser} hook on any thread */
    public static boolean isEnabled() {
        if (!loaded) {
            load();
            if (!loaded) {
                return false;
            }
        }
        return enabled;
    }

    public static void setEnabled(boolean value) {
        ensureLoaded();
        enabled = value;
        if (preferences != null) {
            preferences.edit().putBoolean("enabled", value).apply();
        }
    }
}
