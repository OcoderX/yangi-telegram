package org.telegram.messenger.ayu.netdiag;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * Preferences for the AyuGram network diagnostics feature.
 * <p>
 * Kept in its own SharedPreferences file (and out of {@code AyuConfig} / {@code SharedConfig})
 * so the diagnostics feature owns its storage completely. The pattern mirrors
 * {@link org.telegram.messenger.ayu.AyuConfig}: plain static fields loaded once, written through
 * small setters.
 */
public class NetDiagConfig {

    public static final String PREFS_NAME = "ayunetdiag";

    public static SharedPreferences preferences;
    private static boolean loaded;

    /** show the "network" mode in the Dynamic Island */
    public static boolean islandNetwork = true;
    /** append the current throughput to the island's download row as secondary text */
    public static boolean islandNetworkWithDownloads = true;
    /** elements of the compact network pill the user wants to see */
    public static boolean islandShowDown = true;
    public static boolean islandShowUp = true;
    public static boolean islandShowPing = false;
    public static boolean islandShowDot = false;

    /** how often a sample is produced, ms */
    public static int samplingIntervalMs = 1000;
    /** how often a round-trip probe is sent while sampling, ms */
    public static int probeIntervalMs = 3000;
    /** how often the current proxy is re-checked while sampling, ms */
    public static int proxyCheckIntervalMs = 10000;

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        preferences = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        islandNetwork = preferences.getBoolean("islandNetwork", true);
        islandNetworkWithDownloads = preferences.getBoolean("islandNetworkWithDownloads", true);
        islandShowDown = preferences.getBoolean("islandShowDown", true);
        islandShowUp = preferences.getBoolean("islandShowUp", true);
        islandShowPing = preferences.getBoolean("islandShowPing", false);
        islandShowDot = preferences.getBoolean("islandShowDot", false);
        samplingIntervalMs = clamp(preferences.getInt("samplingIntervalMs", 1000), 500, 10000);
        probeIntervalMs = clamp(preferences.getInt("probeIntervalMs", 3000), 1000, 60000);
        proxyCheckIntervalMs = clamp(preferences.getInt("proxyCheckIntervalMs", 10000), 5000, 120000);

        loaded = true;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    public static void putBoolean(String key, boolean value) {
        ensureLoaded();
        preferences.edit().putBoolean(key, value).apply();
    }

    public static void putInt(String key, int value) {
        ensureLoaded();
        preferences.edit().putInt(key, value).apply();
    }

    public static void setIslandNetwork(boolean v) {
        islandNetwork = v;
        putBoolean("islandNetwork", v);
    }

    public static void setIslandNetworkWithDownloads(boolean v) {
        islandNetworkWithDownloads = v;
        putBoolean("islandNetworkWithDownloads", v);
    }

    public static void setIslandShowDown(boolean v) {
        islandShowDown = v;
        putBoolean("islandShowDown", v);
    }

    public static void setIslandShowUp(boolean v) {
        islandShowUp = v;
        putBoolean("islandShowUp", v);
    }

    public static void setIslandShowPing(boolean v) {
        islandShowPing = v;
        putBoolean("islandShowPing", v);
    }

    public static void setIslandShowDot(boolean v) {
        islandShowDot = v;
        putBoolean("islandShowDot", v);
    }

    public static void setSamplingIntervalMs(int v) {
        samplingIntervalMs = clamp(v, 500, 10000);
        putInt("samplingIntervalMs", samplingIntervalMs);
    }

    public static void setProbeIntervalMs(int v) {
        probeIntervalMs = clamp(v, 1000, 60000);
        putInt("probeIntervalMs", probeIntervalMs);
    }

    public static void setProxyCheckIntervalMs(int v) {
        proxyCheckIntervalMs = clamp(v, 5000, 120000);
        putInt("proxyCheckIntervalMs", proxyCheckIntervalMs);
    }
}
