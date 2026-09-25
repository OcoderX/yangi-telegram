package org.telegram.messenger.ayu.contacts;

import android.os.Handler;
import android.os.Looper;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * Ox-gram contact tracker: watches the online status of the special contacts on every logged in
 * account, posts a local notification on every transition and writes it to
 * {@link ContactTrackerStorage}.
 * <p>
 * Purely passive — it only listens to {@link NotificationCenter#updateInterfaces} with
 * {@link MessagesController#UPDATE_MASK_STATUS} (the event Telegram fires when it receives
 * {@code updateUserStatus}) and never issues a request, so it is safe in ghost mode. Statuses only
 * arrive for users the client already receives updates for, which in practice means contacts and
 * people with open chats.
 * <p>
 * Initialised once from {@code ApplicationLoader}; everything runs on the main thread except the
 * database writes, which {@link ContactTrackerStorage} moves to {@code Utilities.globalQueue}.
 */
public class ContactTracker implements NotificationCenter.NotificationCenterDelegate {

    /** listener for the UI, called on the main thread */
    public interface ContactTrackerDelegate {
        void onSpecialContactStatusChanged(int account, long userId, boolean online);
    }

    private static volatile ContactTracker instance;

    public static ContactTracker getInstance() {
        ContactTracker local = instance;
        if (local == null) {
            synchronized (ContactTracker.class) {
                local = instance;
                if (local == null) {
                    local = instance = new ContactTracker();
                }
            }
        }
        return local;
    }

    private volatile boolean initialized;

    /** last known online flag per account, {@code null} entry means "not observed yet" */
    @SuppressWarnings("unchecked")
    private final LongSparseArray<Boolean>[] knownStates = new LongSparseArray[UserConfig.MAX_ACCOUNT_COUNT];

    private final ArrayList<ContactTrackerDelegate> delegates = new ArrayList<>();

    private ContactTracker() {
    }

    /** called from {@code ApplicationLoader}; cheap and idempotent */
    public void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        //perf: the special-contacts prefs file is read off the main thread; check() goes through
        // SpecialContactsConfig.isEmpty(), which is synchronized and loads on demand, so an early
        // status update simply waits for (or performs) the read itself
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            try {
                SpecialContactsConfig.load();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
        // observers must be added from the main thread; ApplicationLoader.applicationHandler may not
        // exist yet at this point, so use the looper directly instead of AndroidUtilities
        new Handler(Looper.getMainLooper()).post(this::attach);
    }

    private void attach() {
        try {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                NotificationCenter center = NotificationCenter.getInstance(a);
                center.addObserver(this, NotificationCenter.updateInterfaces);
                center.addObserver(this, NotificationCenter.contactsDidLoad);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // ------------------------------------------------------------------ delegates

    public void addDelegate(ContactTrackerDelegate delegate) {
        if (delegate == null || delegates.contains(delegate)) {
            return;
        }
        delegates.add(delegate);
    }

    public void removeDelegate(ContactTrackerDelegate delegate) {
        delegates.remove(delegate);
    }

    private void notifyDelegates(int account, long userId, boolean online) {
        for (int i = 0; i < delegates.size(); i++) {
            try {
                delegates.get(i).onSpecialContactStatusChanged(account, userId, online);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    // ------------------------------------------------------------------ observing

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.updateInterfaces) {
            int mask = 0;
            if (args != null && args.length > 0 && args[0] instanceof Integer) {
                mask = (Integer) args[0];
            }
            if ((mask & MessagesController.UPDATE_MASK_STATUS) == 0) {
                return;
            }
            check(account, true);
        } else if (id == NotificationCenter.contactsDidLoad) {
            check(account, false);
        }
    }

    /**
     * @param allowNotify false while seeding (first observation of a contact never rings)
     */
    private void check(int account, boolean allowNotify) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return;
        }
        try {
            if (!UserConfig.getInstance(account).isClientActivated()) {
                return;
            }
            //perf: updateInterfaces/UPDATE_MASK_STATUS fires for every status update on every
            // account; getAll() copies the whole id list, so ask for the size first and allocate
            // nothing at all in the (normal) case of no special contacts
            if (SpecialContactsConfig.isEmpty()) {
                return;
            }
            final ArrayList<Long> ids = SpecialContactsConfig.getAll();
            if (ids.isEmpty()) {
                return;
            }
            LongSparseArray<Boolean> map = knownStates[account];
            if (map == null) {
                map = knownStates[account] = new LongSparseArray<>();
            }
            final long now = System.currentTimeMillis();
            final MessagesController controller = MessagesController.getInstance(account);
            for (int i = 0; i < ids.size(); i++) {
                final long userId = ids.get(i);
                TLRPC.User user = controller.getUser(userId);
                if (user == null) {
                    // this account does not know the user at all: nothing to compare
                    continue;
                }
                final boolean online = isOnline(account, user);
                final Boolean previous = map.get(userId);
                if (previous == null) {
                    // first observation on this account: remember and record the current state, so a
                    // session left open by a previous process gets closed
                    map.put(userId, online);
                    if (SpecialContactsConfig.trackHistory) {
                        ContactTrackerStorage.getInstance().log(userId, account, online, now);
                    }
                    continue;
                }
                if (previous == online) {
                    continue;
                }
                map.put(userId, online);
                if (SpecialContactsConfig.trackHistory) {
                    ContactTrackerStorage.getInstance().log(userId, account, online, now);
                }
                if (allowNotify && SpecialContactsConfig.notificationsEnabled
                        && (online ? SpecialContactsConfig.notifyOnline : SpecialContactsConfig.notifyOffline)) {
                    ContactNotifier.notifyStatus(account, user, online);
                }
                notifyDelegates(account, userId, online);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** drops the cached state of one contact, so it is seeded again on the next status update */
    public void forget(long userId) {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            LongSparseArray<Boolean> map = knownStates[a];
            if (map != null) {
                map.remove(userId);
            }
        }
    }

    /** last observed state, or null when the contact was never observed on this account */
    public Boolean getKnownState(int account, long userId) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return null;
        }
        LongSparseArray<Boolean> map = knownStates[account];
        return map == null ? null : map.get(userId);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * True when the user is online right now: {@code TL_userStatusOnline} that has not expired yet.
     * Bots, deleted users and the account owner never count.
     */
    public static boolean isOnline(int account, TLRPC.User user) {
        if (user == null || user.bot || UserObject.isDeleted(user) || user instanceof TLRPC.TL_userEmpty) {
            return false;
        }
        if (user.id == UserConfig.getInstance(account).getClientUserId()) {
            return false;
        }
        if (!(user.status instanceof TLRPC.TL_userStatusOnline)) {
            return false;
        }
        return user.status.expires > ConnectionsManager.getInstance(account).getCurrentTime();
    }
}
