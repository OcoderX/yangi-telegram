package org.telegram.messenger.ayu;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.ayu.antidelete.AyuAntiDeleteConfig;
import org.telegram.tgnet.TLRPC;

import java.util.Map;

/** Eligibility for future capture. Archive reads deliberately do not use this policy. */
public final class AyuChatCapturePolicy {
    private static final String PREFS_NAME = "ayu_chat_capture";
    private static volatile boolean loaded;
    private static SharedPreferences preferences;
    private static volatile Table overrides = new Table(0);
    private static volatile Table legacyExclusions = new Table(0);

    private AyuChatCapturePolicy() { }

    /** Call at startup or a storage boundary, never from a cell bind/draw. */
    public static synchronized void load() {
        if (loaded) return;
        try {
            AyuAntiDeleteConfig.load();
            updateLegacyExclusions(AyuAntiDeleteConfig.getExcludedDialogs());
            preferences = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            Map<String, ?> saved = preferences.getAll();
            Table table = new Table(saved.size());
            for (Map.Entry<String, ?> entry : saved.entrySet()) {
                if (!(entry.getValue() instanceof Boolean)) continue;
                int separator = entry.getKey().indexOf(':');
                if (separator <= 0) continue;
                try {
                    long owner = Long.parseLong(entry.getKey().substring(0, separator));
                    long dialog = Long.parseLong(entry.getKey().substring(separator + 1));
                    if (owner > 0 && validDialog(dialog)) {
                        table.put(owner, dialog, (Boolean) entry.getValue() ? 1 : -1);
                    }
                } catch (NumberFormatException ignore) { }
            }
            overrides = table;
            loaded = true;
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Memory-only reads; unknown ordinary peers stay off until metadata arrives. */
    public static boolean isEnabled(int account, long dialogId) {
        return isEnabled(account, dialogId, null);
    }

    public static boolean isEnabled(int account, long dialogId, TLRPC.Message message) {
        long owner = owner(account);
        if (!loaded || owner == 0 || !validDialog(dialogId)) return false;
        int explicit = overrides.get(owner, dialogId);
        if (explicit != 0) return explicit > 0;
        if (legacyExclusions.get(0, dialogId) != 0) return false;
        // Replies is a service inbox, even when its bot/user metadata has not loaded yet.
        if (UserObject.isReplyUser(dialogId)) return true;
        if (message != null && message.peer_id != null) {
            if (message.peer_id.channel_id != 0 && dialogId == -message.peer_id.channel_id) return true;
            if (message.peer_id.chat_id != 0 && dialogId == -message.peer_id.chat_id) return true;
        }
        if (dialogId > 0) {
            TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
            return user != null && !(user instanceof TLRPC.TL_userEmpty) && !user.min && !user.bot;
        }
        TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
        return chat != null && !(chat instanceof TLRPC.TL_chatEmpty);
    }

    public static boolean isExplicitlyEnabled(int account, long dialogId) {
        long owner = owner(account);
        return loaded && owner != 0 && validDialog(dialogId) && overrides.get(owner, dialogId) > 0;
    }

    /** A user action overrides the legacy exclusion for this identity only. */
    public static synchronized void setEnabled(int account, long dialogId, boolean enabled) {
        long owner = owner(account);
        if (owner == 0 || !validDialog(dialogId)) return;
        load();
        if (!loaded) return;
        Table copy = new Table(overrides.size + 1);
        overrides.copyInto(copy);
        copy.put(owner, dialogId, enabled ? 1 : -1);
        overrides = copy;
        preferences.edit().putBoolean(owner + ":" + dialogId, enabled).apply();
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance()
                .postNotificationName(NotificationCenter.ayuConfigChanged));
    }

    /** Settings-only enumeration. Regular readers use isEnabled without allocation or I/O. */
    public static long[] getExplicitDialogs(int account, boolean enabled) {
        load();
        long owner = owner(account);
        Table snapshot = overrides;
        int count = 0;
        for (int i = 0; i < snapshot.values.length; i++) {
            if (snapshot.owners[i] == owner && snapshot.values[i] == (enabled ? 1 : -1)) count++;
        }
        long[] result = new long[count];
        int at = 0;
        for (int i = 0; i < snapshot.values.length; i++) {
            if (snapshot.owners[i] == owner && snapshot.values[i] == (enabled ? 1 : -1)) result[at++] = snapshot.dialogs[i];
        }
        return result;
    }

    /** Called only when legacy preferences load/change; no boxing in policy reads. */
    public static void updateLegacyExclusions(long[] dialogs) {
        Table table = new Table(dialogs.length);
        for (long dialog : dialogs) if (dialog != 0) table.put(0, dialog, -1);
        legacyExclusions = table;
    }

    /** Shared content restrictions for both the keep-on-screen and archive write decisions. */
    public static boolean isRetainableMessage(TLRPC.Message message) {
        if (message == null || message.id <= 0 || message instanceof TLRPC.TL_messageEmpty) return false;
        if (message.action != null && !(message.action instanceof TLRPC.TL_messageActionEmpty)) return false;
        // Per-chat consent never grants permission to retain view-once / timed media.
        return message.ttl == 0 && (message.media == null || message.media.ttl_seconds == 0);
    }

    private static long owner(int account) {
        return account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT
                ? UserConfig.getInstance(account).getClientUserId() : 0;
    }

    private static boolean validDialog(long dialogId) {
        return dialogId != 0 && !DialogObject.isEncryptedDialog(dialogId);
    }

    /** Immutable after publication; primitive open addressing avoids per-message key allocation. */
    private static final class Table {
        final long[] owners;
        final long[] dialogs;
        final byte[] values;
        int size;

        Table(int count) {
            int capacity = 4;
            while (capacity < count * 2L) capacity <<= 1;
            owners = new long[capacity];
            dialogs = new long[capacity];
            values = new byte[capacity];
        }

        private int index(long owner, long dialog) {
            long hash = owner * 0x9e3779b97f4a7c15L + dialog;
            hash = (hash ^ (hash >>> 33)) * 0xff51afd7ed558ccdL;
            return (int) (hash ^ (hash >>> 33)) & (values.length - 1);
        }

        int get(long owner, long dialog) {
            int at = index(owner, dialog);
            while (values[at] != 0) {
                if (owners[at] == owner && dialogs[at] == dialog) return values[at];
                at = (at + 1) & (values.length - 1);
            }
            return 0;
        }

        void put(long owner, long dialog, int value) {
            int at = index(owner, dialog);
            while (values[at] != 0 && (owners[at] != owner || dialogs[at] != dialog)) {
                at = (at + 1) & (values.length - 1);
            }
            if (values[at] == 0) size++;
            owners[at] = owner;
            dialogs[at] = dialog;
            values[at] = (byte) value;
        }

        void copyInto(Table destination) {
            for (int i = 0; i < values.length; i++) {
                if (values[i] != 0) destination.put(owners[i], dialogs[i], values[i]);
            }
        }
    }
}
