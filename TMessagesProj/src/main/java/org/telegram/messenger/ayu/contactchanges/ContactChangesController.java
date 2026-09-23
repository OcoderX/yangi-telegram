package org.telegram.messenger.ayu.contactchanges;

import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;

/**
 * OcoderX "Contacts Changes" — keeps a local history of profile changes of the user's contacts.
 * <p>
 * The name / username / phone / photo comparison is fed by a single one-line hook inside
 * {@code MessagesController.putUser()} (see {@link #onUserUpdated(TLRPC.User, TLRPC.User)}); the bio
 * comparison listens to {@link NotificationCenter#userInfoDidLoad}.
 * <p>
 * Threading contract:
 * <ul>
 *     <li>{@link #onUserUpdated(TLRPC.User, TLRPC.User)} runs on the caller thread and does nothing
 *         but a handful of field comparisons — no I/O, no allocations unless something changed;</li>
 *     <li>the avatar file copy, every database access and the de-duplication query run on
 *         {@link ContactChangesStorage#getQueue()};</li>
 *     <li>listener callbacks are always delivered on the main thread.</li>
 * </ul>
 */
public class ContactChangesController implements NotificationCenter.NotificationCenterDelegate {

    public interface Listener {
        /** new rows landed in the database for this account */
        void onContactChangesUpdated(int account);
    }

    private static final ContactChangesController[] instances = new ContactChangesController[UserConfig.MAX_ACCOUNT_COUNT];

    private final int currentAccount;
    private final ArrayList<Listener> listeners = new ArrayList<>();

    private volatile boolean startRequested;
    private boolean started;
    /** rows inserted since the app started; used to trim the table once in a while */
    private int insertedSinceTrim;

    public static ContactChangesController getInstance(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            account = 0;
        }
        ContactChangesController local = instances[account];
        if (local == null) {
            synchronized (ContactChangesController.class) {
                local = instances[account];
                if (local == null) {
                    local = instances[account] = new ContactChangesController(account);
                }
            }
        }
        return local;
    }

    private ContactChangesController(int account) {
        currentAccount = account;
    }

    public int getCurrentAccount() {
        return currentAccount;
    }

    // ------------------------------------------------------------------ lifecycle

    /** must be called on the main thread */
    public void start() {
        if (started) {
            return;
        }
        started = true;
        startRequested = true;
        try {
            NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.userInfoDidLoad);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void requestStart() {
        if (startRequested) {
            return;
        }
        startRequested = true;
        AndroidUtilities.runOnUIThread(this::start);
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        AndroidUtilities.runOnUIThread(() -> {
            for (int i = 0; i < listeners.size(); i++) {
                try {
                    listeners.get(i).onContactChangesUpdated(currentAccount);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        });
    }

    // ------------------------------------------------------------------ the hook

    /**
     * Called from {@code MessagesController.putUser()} with the user object we had and the one that
     * just arrived. Cheap by design: a few string comparisons, then everything heavy is posted to
     * {@link ContactChangesStorage#getQueue()}.
     */
    public void onUserUpdated(TLRPC.User oldUser, TLRPC.User newUser) {
        try {
            if (oldUser == null || newUser == null || oldUser == newUser) {
                return;
            }
            if (oldUser.id != newUser.id || newUser.id == 0) {
                return;
            }
            if (!ContactChangesConfig.isEnabled()) {
                return;
            }
            if (newUser.self || newUser.bot || newUser.support || newUser.id == UserConfig.getInstance(currentAccount).getClientUserId()) {
                return;
            }
            if (!isContact(newUser, oldUser)) {
                return;
            }

            final boolean oldPartial = oldUser.min;
            final boolean newPartial = newUser.min;

            ArrayList<ContactChange> changes = null;

            // ---- name
            String oldName = fullName(oldUser);
            String newName = fullName(newUser);
            if (!TextUtils.equals(oldName, newName) && allowed(oldName, newName, oldPartial, newPartial)) {
                changes = add(changes, new ContactChange(currentAccount, newUser.id, ContactChange.TYPE_NAME, oldName, newName));
            }

            // ---- username
            String oldUsername = nullToEmpty(oldUser.username);
            String newUsername = nullToEmpty(newUser.username);
            if (!TextUtils.equals(oldUsername, newUsername) && allowed(oldUsername, newUsername, oldPartial, newPartial)) {
                changes = add(changes, new ContactChange(currentAccount, newUser.id, ContactChange.TYPE_USERNAME, oldUsername, newUsername));
            }

            // ---- phone (only when both sides are known: phone is hidden in most user objects)
            String oldPhone = nullToEmpty(oldUser.phone);
            String newPhone = nullToEmpty(newUser.phone);
            if (!TextUtils.equals(oldPhone, newPhone) && oldPhone.length() > 0 && newPhone.length() > 0) {
                changes = add(changes, new ContactChange(currentAccount, newUser.id, ContactChange.TYPE_PHONE, oldPhone, newPhone));
            }

            // ---- profile photo
            long oldPhotoId = photoId(oldUser.photo);
            long newPhotoId = photoId(newUser.photo);
            if (oldPhotoId != newPhotoId && (newPhotoId != 0 || !newPartial) && (oldPhotoId != 0 || !oldPartial)) {
                ContactChange change = new ContactChange(currentAccount, newUser.id, ContactChange.TYPE_PHOTO,
                        oldPhotoId == 0 ? "" : String.valueOf(oldPhotoId),
                        newPhotoId == 0 ? "" : String.valueOf(newPhotoId));
                if (oldUser.photo != null && oldPhotoId != 0) {
                    // captured now: oldUser.photo may be replaced right after this hook returns
                    change.oldPhotoLocation = oldUser.photo.photo_small;
                }
                changes = add(changes, change);
            }

            if (changes == null) {
                return;
            }
            requestStart();
            store(changes);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * A value that became empty is only trusted when the new object is complete, and a value that
     * was empty is only trusted when the old object was complete — {@code min} users carry partial
     * data and would otherwise produce phantom changes.
     */
    private static boolean allowed(String oldValue, String newValue, boolean oldPartial, boolean newPartial) {
        if (newValue.length() == 0) {
            return !newPartial;
        }
        if (oldValue.length() == 0) {
            return !oldPartial;
        }
        return true;
    }

    private static ArrayList<ContactChange> add(ArrayList<ContactChange> list, ContactChange change) {
        if (list == null) {
            list = new ArrayList<>(2);
        }
        list.add(change);
        return list;
    }

    private boolean isContact(TLRPC.User newUser, TLRPC.User oldUser) {
        if (newUser.contact || newUser.mutual_contact || oldUser.contact || oldUser.mutual_contact) {
            return true;
        }
        try {
            return ContactsController.getInstance(currentAccount).isContact(newUser.id);
        } catch (Throwable e) {
            return false;
        }
    }

    private static String fullName(TLRPC.User user) {
        String first = nullToEmpty(user.first_name).trim();
        String last = nullToEmpty(user.last_name).trim();
        if (first.length() == 0) {
            return last;
        }
        if (last.length() == 0) {
            return first;
        }
        return first + " " + last;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static long photoId(TLRPC.UserProfilePhoto photo) {
        if (photo == null || photo instanceof TLRPC.TL_userProfilePhotoEmpty) {
            return 0;
        }
        return photo.photo_id;
    }

    // ------------------------------------------------------------------ storing

    private void store(ArrayList<ContactChange> changes) {
        DispatchQueue queue = ContactChangesStorage.getQueue();
        queue.postRunnable(() -> {
            boolean any = false;
            ContactChangesStorage storage = ContactChangesStorage.getInstance();
            for (int i = 0; i < changes.size(); i++) {
                ContactChange change = changes.get(i);
                try {
                    String last = storage.getLastNewValue(change.account, change.userId, change.type);
                    if (last != null && TextUtils.equals(last, nullToEmpty(change.newValue))) {
                        continue; // already recorded this exact value
                    }
                    if (change.isPhoto()) {
                        change.extra = savePreviousAvatar(change);
                    }
                    change.oldPhotoLocation = null;
                    if (storage.insert(change) > 0) {
                        any = true;
                        insertedSinceTrim++;
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            if (any) {
                if (insertedSinceTrim > 200) {
                    insertedSinceTrim = 0;
                    storage.trim();
                }
                notifyListeners();
            }
        });
    }

    /**
     * Copies the cached small avatar of the previous photo into
     * {@code files/ox_contact_changes/<user>_<photoId>.jpg} so the history can still show it after
     * Telegram evicts its cache. Runs on the storage queue.
     *
     * @return the absolute path of the saved copy, or null when nothing was cached
     */
    private String savePreviousAvatar(ContactChange change) {
        TLRPC.FileLocation location = change.oldPhotoLocation;
        if (location == null || TextUtils.isEmpty(change.oldValue)) {
            return null;
        }
        long photoId;
        try {
            photoId = Long.parseLong(change.oldValue);
        } catch (Throwable e) {
            return null;
        }
        try {
            File destination = ContactChangesStorage.getPhotoFile(change.userId, photoId);
            if (destination.exists() && destination.length() > 0) {
                return destination.getAbsolutePath();
            }
            File source = null;
            try {
                source = FileLoader.getInstance(change.account).getPathToAttach(location, true);
            } catch (Throwable ignore) {
            }
            if (source == null || !source.exists() || source.length() == 0) {
                try {
                    source = FileLoader.getInstance(change.account).getPathToAttach(location, false);
                } catch (Throwable ignore) {
                }
            }
            if (source == null || !source.exists() || source.length() == 0) {
                return null;
            }
            if (AndroidUtilities.copyFileSafe(source, destination) && destination.exists() && destination.length() > 0) {
                return destination.getAbsolutePath();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return null;
    }

    // ------------------------------------------------------------------ bio

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.userInfoDidLoad || args == null || args.length < 2) {
            return;
        }
        try {
            if (!ContactChangesConfig.isEnabled()) {
                return;
            }
            if (!(args[1] instanceof TLRPC.UserFull)) {
                return;
            }
            TLRPC.UserFull userFull = (TLRPC.UserFull) args[1];
            long userId = args[0] instanceof Long ? (Long) args[0] : 0;
            if (userId <= 0 || userId == UserConfig.getInstance(currentAccount).getClientUserId()) {
                return;
            }
            if (!ContactsController.getInstance(currentAccount).isContact(userId)) {
                return;
            }
            final String about = nullToEmpty(userFull.about).trim();
            final long finalUserId = userId;
            ContactChangesStorage.getQueue().postRunnable(() -> {
                try {
                    ContactChangesStorage storage = ContactChangesStorage.getInstance();
                    String previous = storage.getBio(currentAccount, finalUserId);
                    if (previous == null) {
                        storage.setBio(currentAccount, finalUserId, about); // first sighting: baseline only
                        return;
                    }
                    if (TextUtils.equals(previous, about)) {
                        return;
                    }
                    storage.setBio(currentAccount, finalUserId, about);
                    ContactChange change = new ContactChange(currentAccount, finalUserId, ContactChange.TYPE_BIO, previous, about);
                    if (storage.insert(change) > 0) {
                        notifyListeners();
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            });
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // ------------------------------------------------------------------ screen helpers

    public void loadChanges(int limit, int offset, LoadCallback callback) {
        ContactChangesStorage.getQueue().postRunnable(() -> {
            final ArrayList<ContactChange> result = ContactChangesStorage.getInstance().load(currentAccount, limit, offset);
            AndroidUtilities.runOnUIThread(() -> callback.onChangesLoaded(result));
        });
    }

    public void clearHistory(Runnable done) {
        ContactChangesStorage.getQueue().postRunnable(() -> {
            ContactChangesStorage.getInstance().clear(currentAccount);
            if (done != null) {
                AndroidUtilities.runOnUIThread(done);
            }
        });
    }

    public interface LoadCallback {
        void onChangesLoaded(ArrayList<ContactChange> changes);
    }
}
