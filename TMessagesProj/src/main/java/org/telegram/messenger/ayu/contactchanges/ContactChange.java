package org.telegram.messenger.ayu.contactchanges;

import org.telegram.tgnet.TLRPC;

/**
 * OcoderX "Contacts Changes": one recorded profile change of a contact.
 * <p>
 * Plain data holder — the persisted columns map 1:1 onto the {@code changes} table of
 * {@link ContactChangesStorage}. {@link #oldPhotoLocation} is the only transient field: it is
 * captured on the caller thread (the {@code TLRPC.User} object it belongs to may be mutated right
 * after our hook returns) and used on the storage queue to copy the cached avatar file.
 */
public class ContactChange {

    public static final String TYPE_NAME = "NAME";
    public static final String TYPE_USERNAME = "USERNAME";
    public static final String TYPE_PHONE = "PHONE";
    public static final String TYPE_PHOTO = "PHOTO";
    public static final String TYPE_BIO = "BIO";

    /** rowid in the database, 0 when not stored yet */
    public long id;
    public int account;
    public long userId;
    /** one of the TYPE_* constants */
    public String type = TYPE_NAME;
    public String oldValue;
    public String newValue;
    /** milliseconds, {@link System#currentTimeMillis()} */
    public long timestamp;
    /** absolute path of the saved copy of the previous avatar, null when we could not save one */
    public String extra;

    /** never persisted: the cached avatar file location of the previous photo */
    public transient TLRPC.FileLocation oldPhotoLocation;

    public ContactChange() {
    }

    public ContactChange(int account, long userId, String type, String oldValue, String newValue) {
        this.account = account;
        this.userId = userId;
        this.type = type;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.timestamp = System.currentTimeMillis();
    }

    public boolean isPhoto() {
        return TYPE_PHOTO.equals(type);
    }
}
