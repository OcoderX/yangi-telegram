package org.telegram.messenger.ayu;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Account-owned Ghost settings. Hot-path reads only access an immutable memory snapshot. */
public final class AyuGhostConfig {
    private static final int DEFAULT_BITS = 15 | (1 << 5);
    private static final int CORE_BITS = 31;
    private static final AtomicReferenceArray<Settings> accounts = new AtomicReferenceArray<>(UserConfig.MAX_ACCOUNT_COUNT);
    private static final Settings NORMAL = new Settings(0, DEFAULT_BITS);
    private static SharedPreferences preferences;

    public static final class Settings {
        public final long ownerId;
        private final int bits;
        public final boolean sendReadPackets;
        public final boolean sendReadStories;
        public final boolean sendOnlinePackets;
        public final boolean sendUploadProgress;
        public final boolean sendOfflinePacketAfterOnline;
        public final boolean markReadAfterSend;
        public final boolean markReadAfterAction;
        public final boolean useScheduledMessages;
        public final boolean alertBeforeOpeningStory;
        private Settings(long ownerId, int bits) {
            this.ownerId = ownerId;
            this.bits = bits;
            sendReadPackets = (bits & (1 << 0)) != 0;
            sendReadStories = (bits & (1 << 1)) != 0;
            sendOnlinePackets = (bits & (1 << 2)) != 0;
            sendUploadProgress = (bits & (1 << 3)) != 0;
            sendOfflinePacketAfterOnline = (bits & (1 << 4)) != 0;
            markReadAfterSend = (bits & (1 << 5)) != 0;
            markReadAfterAction = (bits & (1 << 6)) != 0;
            useScheduledMessages = (bits & (1 << 7)) != 0;
            alertBeforeOpeningStory = (bits & (1 << 8)) != 0;
        }
        public boolean isActive() { return (bits & CORE_BITS) == 16 || (!sendReadPackets && !sendOnlinePackets); }
        public int selectedCount() { return Integer.bitCount((~bits & 15) | (bits & 16)); }
    }

    /** No preference access, controller initialization, locking or allocation. */
    public static Settings get(int account) {
        if (account < 0 || account >= accounts.length()) return NORMAL;
        Settings settings = accounts.get(account);
        return settings == null ? NORMAL : settings;
    }

    private static void initialize() {
        if (preferences != null) return;
        preferences = ApplicationLoader.applicationContext.getSharedPreferences(AyuConstants.PREFS_NAME, Context.MODE_PRIVATE);
        if (!preferences.getBoolean("ghostOwnersMigrated", false)) {
            int legacy = 0;
            if (preferences.getBoolean("sendReadPackets", true)) legacy |= 1 << 0;
            if (preferences.getBoolean("sendReadStories", true)) legacy |= 1 << 1;
            if (preferences.getBoolean("sendOnlinePackets", true)) legacy |= 1 << 2;
            if (preferences.getBoolean("sendUploadProgress", true)) legacy |= 1 << 3;
            if (preferences.getBoolean("sendOfflinePacketAfterOnline", false)) legacy |= 1 << 4;
            if (preferences.getBoolean("markReadAfterSend", true)) legacy |= 1 << 5;
            if (preferences.getBoolean("markReadAfterAction", false)) legacy |= 1 << 6;
            if (preferences.getBoolean("useScheduledMessages", false)) legacy |= 1 << 7;
            if (preferences.getBoolean("alertBeforeOpeningStory", false)) legacy |= 1 << 8;
            SharedPreferences.Editor editor = preferences.edit();
            // Snapshot only accounts that existed before this upgrade. Do not initialize their controllers.
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                SharedPreferences userPrefs = ApplicationLoader.applicationContext.getSharedPreferences(
                        account == 0 ? "userconfing" : "userconfig" + account, Context.MODE_PRIVATE);
                String encoded = userPrefs.getString("user", null);
                if (encoded == null) continue;
                SerializedData data = null;
                try {
                    data = new SerializedData(Base64.decode(encoded, Base64.DEFAULT));
                    TLRPC.User user = TLRPC.User.TLdeserialize(data, data.readInt32(false), false);
                    if (user != null && user.id != 0 && !preferences.contains(key(user.id))) {
                        editor.putInt(key(user.id), legacy);
                    }
                } catch (Exception ignored) {
                    // An unreadable saved session is not an authenticated owner.
                } finally {
                    if (data != null) data.cleanup();
                }
            }
            editor.putBoolean("ghostOwnersMigrated", true).apply();
        }
    }

    private static String key(long ownerId) { return "ghostOwner_" + ownerId; }

    /** Called by UserConfig at load/login/logout, never by drawing or binding code. */
    public static synchronized void bind(int account, long ownerId) {
        if (account < 0 || account >= accounts.length()) return;
        initialize();
        Settings previous = accounts.get(account);
        if (previous != null && previous.ownerId == ownerId) return;
        AyuState.clearAccount(account);
        accounts.set(account, ownerId == 0 ? NORMAL : new Settings(ownerId, preferences.getInt(key(ownerId), DEFAULT_BITS)));
        AyuState.onGhostModeChanged(account);
    }

    public static synchronized void setGhostMode(int account, boolean enabled) {
        Settings old = get(account);
        save(account, old, (old.bits & ~CORE_BITS) | (enabled ? 16 : 15));
    }

    public static synchronized void setOption(int account, int option, boolean enabled) {
        if (option < 0 || option > 8) return;
        Settings old = get(account);
        save(account, old, enabled ? old.bits | (1 << option) : old.bits & ~(1 << option));
    }

    private static void save(int account, Settings old, int bits) {
        if (old.ownerId == 0 || old.bits == bits) return;
        Settings updated = new Settings(old.ownerId, bits);
        preferences.edit().putInt(key(old.ownerId), bits).apply();
        accounts.set(account, updated);
        if (((old.bits ^ bits) & ((1 << 2) | (1 << 4))) != 0) {
            AyuGhostHelper.onOnlineSettingsChanged(account);
        }
        AyuState.onGhostModeChanged(account);
    }
}
