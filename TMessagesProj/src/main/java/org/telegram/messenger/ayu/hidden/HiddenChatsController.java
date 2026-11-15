package org.telegram.messenger.ayu.hidden;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Base64;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.ayu.contacts.ContactNotifier;
import org.telegram.messenger.ayu.radar.RadarNotifier;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Local, account-owned hidden-chat policy. Membership is independent of the unlock session. */
public final class HiddenChatsController {
    public enum PasscodeResult {
        SUCCESS, INVALID_PASSCODE, THROTTLED, NOT_CONFIGURED, ALREADY_CONFIGURED,
        INVALID_NEW_PASSCODE, STORAGE_ERROR, CORRUPT_STATE, STALE_SESSION
    }

    public interface PasscodeCallback {
        void onResult(PasscodeResult result);
    }

    private static final AtomicReferenceArray<HiddenChatsController> INSTANCES =
            new AtomicReferenceArray<>(UserConfig.MAX_ACCOUNT_COUNT);
    private static final ExecutorService PASSWORD_WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "HiddenChatsPassword");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private static final int MAX_HIDDEN = 100_000;
    private static final long PASSWORD_AUTH_WINDOW = 60_000L;

    private final int account;
    private final long owner;
    private final SharedPreferences preferences;
    private volatile HiddenChatSecurity.IdSet hidden = HiddenChatSecurity.IdSet.EMPTY;
    private volatile boolean configured;
    private volatile boolean corrupt;
    private volatile boolean corruptMembership;
    private volatile boolean biometric;
    private volatile boolean unlocked;
    private volatile boolean revoked;
    private volatile long retryUntilWall;
    private volatile long retryUntilElapsed;
    private byte[] salt;
    private byte[] hash;
    private int failures;
    private long generation;
    private long passwordVerifiedAt = -1;

    public static HiddenChatsController getInstance(int account) {
        if (account < 0 || account >= INSTANCES.length()) throw new IllegalArgumentException("Invalid account");
        long owner = UserConfig.getInstance(account).getClientUserId();
        HiddenChatsController result = INSTANCES.get(account);
        if (result == null || result.owner != owner) {
            synchronized (INSTANCES) {
                result = INSTANCES.get(account);
                if (result == null || result.owner != owner) {
                    if (result != null) result.lock();
                    result = new HiddenChatsController(account, owner);
                    INSTANCES.set(account, result);
                }
            }
        }
        return result;
    }

    private HiddenChatsController(int account, long owner) {
        this.account = account;
        this.owner = owner;
        preferences = ApplicationLoader.applicationContext.getSharedPreferences("ox_hidden_chats_" + account, Context.MODE_PRIVATE);
        // An uninitialised UserConfig must never erase a persisted account's state.
        if (owner == 0) return;
        try {
            long storedOwner = preferences.getLong("owner", 0);
            if (storedOwner != owner) {
                if (storedOwner != 0) {
                    if (!preferences.edit().clear().commit()) corrupt = true;
                    return;
                }
                if (!preferences.getAll().isEmpty()) {
                    corrupt = corruptMembership = configured = true;
                }
                return;
            }
            configured = preferences.getBoolean("configured", false);
            try {
                Set<String> stored = preferences.getStringSet("dialogs", null);
                if (stored != null) {
                    if (stored.size() > MAX_HIDDEN) throw new IllegalStateException();
                    long[] ids = new long[stored.size()];
                    int index = 0;
                    for (String value : stored) {
                        long id = Long.parseLong(value);
                        if (DialogObject.isFolderDialogId(id)) throw new IllegalArgumentException();
                        ids[index++] = id;
                    }
                    hidden = new HiddenChatSecurity.IdSet(ids);
                }
            } catch (Exception ignored) {
                corrupt = corruptMembership = true;
            }
            if (!configured) {
                if (hidden.ids.length != 0 || preferences.contains("hash") || corruptMembership) corrupt = configured = true;
                return;
            }
            salt = Base64.decode(preferences.getString("salt", ""), Base64.NO_WRAP);
            hash = Base64.decode(preferences.getString("hash", ""), Base64.NO_WRAP);
            if (salt.length != HiddenChatSecurity.SALT_BYTES || hash.length != HiddenChatSecurity.HASH_BYTES
                    || preferences.getInt("version", 0) != 1) corrupt = true;
            biometric = !corrupt && preferences.getBoolean("biometric", false);
            failures = preferences.getInt("failures", 0);
            retryUntilWall = preferences.getLong("retryUntil", 0);
            long lastAttempt = preferences.getLong("lastAttempt", 0);
            if (failures < 0 || retryUntilWall < 0 || lastAttempt < 0) corrupt = true;
            // A wall-clock rollback must not bypass a persisted lockout after a restart.
            long remaining = Math.max(0, retryUntilWall - System.currentTimeMillis());
            if (System.currentTimeMillis() < lastAttempt) remaining = Math.max(remaining, HiddenChatSecurity.delayMillis(failures));
            retryUntilElapsed = SystemClock.elapsedRealtime() + Math.min(3_600_000L, remaining);
        } catch (Exception ignored) {
            // Keep unknown state intact and fail closed instead of silently revealing chats.
            configured = corrupt = corruptMembership = true;
        }
    }

    private boolean isCurrentOwner() {
        return !revoked && INSTANCES.get(account) == this && owner != 0
                && UserConfig.getInstance(account).getClientUserId() == owner;
    }

    public boolean isHidden(long dialogId) {
        return dialogId != 0 && !DialogObject.isFolderDialogId(dialogId) && isCurrentOwner()
                && (corruptMembership || hidden.contains(dialogId));
    }

    public boolean hasHiddenChats() {
        return isCurrentOwner() && (corruptMembership || hidden.ids.length != 0);
    }

    public boolean isConfigured() { return isCurrentOwner() && configured; }
    public boolean isCorrupt() { return isCurrentOwner() && corrupt; }
    public boolean isUnlocked() { return isCurrentOwner() && unlocked && !corrupt; }
    public boolean isBiometricEnabled() { return isConfigured() && !corrupt && biometric; }

    public long[] getHiddenDialogIds() {
        return isUnlocked() ? hidden.ids.clone() : new long[0];
    }

    /** Call only following successful platform biometric authentication. */
    public synchronized void unlock() {
        if (isBiometricEnabled() && getRetryAfterMillis() == 0) {
            unlocked = true;
            notifyChanged(false);
        }
    }

    public synchronized void lock() {
        generation++;
        passwordVerifiedAt = -1;
        boolean wasUnlocked = unlocked;
        unlocked = false;
        if (wasUnlocked) notifyChanged(false);
    }

    public static void lockAll() {
        for (int i = 0; i < INSTANCES.length(); i++) {
            HiddenChatsController controller = INSTANCES.get(i);
            if (controller != null) controller.lock();
        }
    }

    /** Invoke on explicit logout, even if the same user later reuses this account slot. */
    public static void clearAccount(int account) {
        synchronized (INSTANCES) {
            HiddenChatsController controller = INSTANCES.get(account);
            if (controller != null) {
                synchronized (controller) {
                    controller.revoked = true;
                    controller.lock();
                }
            }
            SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("ox_hidden_chats_" + account, Context.MODE_PRIVATE);
            if (prefs.edit().clear().commit()) INSTANCES.set(account, null);
        }
    }

    public synchronized boolean setHidden(long dialogId, boolean value) {
        if (!isConfigured() || !isUnlocked() || dialogId == 0 || DialogObject.isFolderDialogId(dialogId)) return false;
        if (hidden.contains(dialogId) == value) return true;
        if (value && hidden.ids.length >= MAX_HIDDEN) return false;
        long[] ids = new long[hidden.ids.length + (value ? 1 : -1)];
        if (value) {
            System.arraycopy(hidden.ids, 0, ids, 0, hidden.ids.length);
            ids[ids.length - 1] = dialogId;
        } else {
            int index = 0;
            for (long id : hidden.ids) if (id != dialogId) ids[index++] = id;
        }
        Set<String> stored = new HashSet<>();
        for (long id : ids) stored.add(Long.toString(id));
        if (!preferences.edit().putStringSet("dialogs", stored).commit()) return false;
        hidden = new HiddenChatSecurity.IdSet(ids);
        if (value) {
            AndroidUtilities.runOnUIThread(() -> {
                if (!isCurrentOwner() || !isHidden(dialogId)) return;
                MediaDataController.getInstance(account).uninstallShortcut(dialogId, MediaDataController.SHORTCUT_TYPE_USER_OR_CHAT);
                MediaDataController.getInstance(account).uninstallShortcut(dialogId, MediaDataController.SHORTCUT_TYPE_ATTACHED_BOT);
            });
        }
        notifyChanged(true);
        return true;
    }

    public synchronized boolean setBiometricEnabled(boolean enabled) {
        if (!isConfigured() || !isUnlocked() || (enabled && !hasRecentPasswordVerification())) return false;
        if (!preferences.edit().putBoolean("biometric", enabled).commit()) return false;
        biometric = enabled;
        notifyChanged(false);
        return true;
    }

    private boolean hasRecentPasswordVerification() {
        long elapsed = SystemClock.elapsedRealtime() - passwordVerifiedAt;
        return passwordVerifiedAt >= 0 && elapsed >= 0 && elapsed <= PASSWORD_AUTH_WINDOW;
    }

    public synchronized boolean disableAndUnhideAll() {
        if (!isUnlocked() || !hasRecentPasswordVerification()) return false;
        if (!preferences.edit().clear().commit()) return false;
        generation++;
        configured = biometric = unlocked = false;
        passwordVerifiedAt = -1;
        hidden = HiddenChatSecurity.IdSet.EMPTY;
        salt = hash = null;
        failures = 0;
        retryUntilWall = retryUntilElapsed = 0;
        notifyChanged(true);
        return true;
    }

    public long getRetryAfterMillis() {
        return Math.max(0, retryUntilElapsed - SystemClock.elapsedRealtime());
    }

    public void setPasscode(char[] password, PasscodeCallback callback) {
        submit(null, password, 0, callback);
    }

    public void verifyPasscode(char[] password, PasscodeCallback callback) {
        submit(password, null, 1, callback);
    }

    public void changePasscode(char[] oldPassword, char[] newPassword, PasscodeCallback callback) {
        submit(oldPassword, newPassword, 2, callback);
    }

    private void submit(char[] password, char[] replacement, int operation, PasscodeCallback callback) {
        final char[] supplied = password == null ? new char[0] : password.clone();
        final char[] next = replacement == null ? new char[0] : replacement.clone();
        final long token;
        synchronized (this) { token = generation; }
        PASSWORD_WORKER.execute(() -> {
            PasscodeResult result;
            try {
                result = passwordOperation(supplied, next, operation, token);
            } catch (Exception ignored) {
                result = PasscodeResult.STORAGE_ERROR;
            } finally {
                Arrays.fill(supplied, '\0');
                Arrays.fill(next, '\0');
            }
            final PasscodeResult completed = result;
            AndroidUtilities.runOnUIThread(() -> {
                PasscodeResult delivered = completed;
                synchronized (HiddenChatsController.this) {
                    if (!isCurrentOwner() || token != generation) delivered = PasscodeResult.STALE_SESSION;
                }
                if (callback != null) callback.onResult(delivered);
            });
        });
    }

    private PasscodeResult passwordOperation(char[] password, char[] next, int operation, long token) throws Exception {
        byte[] oldSalt;
        byte[] oldHash;
        synchronized (this) {
            if (!isCurrentOwner() || token != generation) return PasscodeResult.STALE_SESSION;
            if (corrupt) return PasscodeResult.CORRUPT_STATE;
            if (operation == 0 && configured) return PasscodeResult.ALREADY_CONFIGURED;
            if (operation != 0 && !configured) return PasscodeResult.NOT_CONFIGURED;
            if (operation != 1 && !HiddenChatSecurity.validPassword(next)) return PasscodeResult.INVALID_NEW_PASSCODE;
            if (getRetryAfterMillis() > 0) return PasscodeResult.THROTTLED;
            oldSalt = salt;
            oldHash = hash;
        }
        boolean verified = operation == 0 || (password.length <= 256 && HiddenChatSecurity.matches(password, oldSalt, oldHash));
        byte[] newSalt = verified && operation != 1 ? HiddenChatSecurity.salt() : null;
        byte[] newHash = newSalt == null ? null : HiddenChatSecurity.derive(next, newSalt);
        synchronized (this) {
            if (!isCurrentOwner() || token != generation) return PasscodeResult.STALE_SESSION;
            long now = System.currentTimeMillis();
            if (!verified) {
                int nextFailures = Math.min(30, failures + 1);
                long delay = HiddenChatSecurity.delayMillis(nextFailures);
                long deadline = now + delay;
                // Persist before reporting failure, so killing the process cannot reset lockout.
                if (!preferences.edit().putInt("failures", nextFailures).putLong("lastAttempt", now)
                        .putLong("retryUntil", deadline).commit()) {
                    lock();
                    corrupt = true;
                    return PasscodeResult.STORAGE_ERROR;
                }
                failures = nextFailures;
                retryUntilWall = deadline;
                retryUntilElapsed = SystemClock.elapsedRealtime() + delay;
                return delay > 0 ? PasscodeResult.THROTTLED : PasscodeResult.INVALID_PASSCODE;
            }
            SharedPreferences.Editor editor = preferences.edit().putLong("owner", owner)
                    .putBoolean("configured", true).putInt("version", 1)
                    .putInt("failures", 0).putLong("lastAttempt", 0).putLong("retryUntil", 0);
            if (newHash != null) {
                editor.putString("salt", Base64.encodeToString(newSalt, Base64.NO_WRAP))
                        .putString("hash", Base64.encodeToString(newHash, Base64.NO_WRAP));
            }
            if (!editor.commit()) return PasscodeResult.STORAGE_ERROR;
            if (newHash != null) {
                salt = newSalt;
                hash = newHash;
            }
            configured = unlocked = true;
            failures = 0;
            retryUntilWall = retryUntilElapsed = 0;
            passwordVerifiedAt = SystemClock.elapsedRealtime();
            notifyChanged(false);
            return PasscodeResult.SUCCESS;
        }
    }

    private void notifyChanged(boolean membershipChanged) {
        AndroidUtilities.runOnUIThread(() -> {
            if (!isCurrentOwner()) return;
            if (membershipChanged) {
                MessagesController.getInstance(account).sortDialogs(null);
                NotificationsController.getInstance(account).onHiddenChatsChanged();
                MediaDataController.getInstance(account).removeHiddenChatHints();
                for (long id : hidden.ids) {
                    if (DialogObject.isUserDialog(id)) ContactNotifier.cancel(account, id);
                }
                RadarNotifier.cancelForAccount(account);
                HiddenChatsWidgets.refresh();
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.contactsDidLoad);
            }
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.ayuConfigChanged);
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
        });
    }
}
