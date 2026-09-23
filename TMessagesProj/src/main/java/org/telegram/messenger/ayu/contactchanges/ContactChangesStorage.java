package org.telegram.messenger.ayu.contactchanges;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

/**
 * OcoderX "Contacts Changes": SQLite storage of recorded contact profile changes.
 * <p>
 * One database for every account (rows carry an {@code account} column), living directly in
 * {@link ApplicationLoader#getFilesDirFixed()} so Telegram cache cleanups never touch it. Every
 * public method here must be called from {@link #getQueue()} — the class itself does no thread
 * hopping (same contract as {@code org.telegram.messenger.ayu.radar.RadarStorage}).
 */
public class ContactChangesStorage extends SQLiteOpenHelper {

    private static final int DB_VERSION = 1;

    public static final String DB_NAME = "ox_contact_changes.db";
    public static final String PHOTOS_DIR = "ox_contact_changes";

    public static final String TABLE_CHANGES = "changes";
    /** last seen bio per (account, user) — the baseline the BIO comparison needs */
    public static final String TABLE_BIOS = "bios";

    private static final String[] PROJECTION = {
            "id",         // 0
            "account",    // 1
            "user_id",    // 2
            "type",       // 3
            "old_value",  // 4
            "new_value",  // 5
            "timestamp",  // 6
            "extra"       // 7
    };

    private static final int C_ID = 0;
    private static final int C_ACCOUNT = 1;
    private static final int C_USER_ID = 2;
    private static final int C_TYPE = 3;
    private static final int C_OLD = 4;
    private static final int C_NEW = 5;
    private static final int C_TIME = 6;
    private static final int C_EXTRA = 7;

    /** hard cap so the history cannot grow without bounds */
    public static final int MAX_ROWS = 10000;

    private static volatile ContactChangesStorage instance;
    private static volatile DispatchQueue storageQueue;

    private final File databaseFile;

    public static ContactChangesStorage getInstance() {
        ContactChangesStorage local = instance;
        if (local == null) {
            synchronized (ContactChangesStorage.class) {
                local = instance;
                if (local == null) {
                    local = instance = new ContactChangesStorage();
                }
            }
        }
        return local;
    }

    /** dedicated background queue; every database access and file copy runs here */
    public static DispatchQueue getQueue() {
        DispatchQueue local = storageQueue;
        if (local == null) {
            synchronized (ContactChangesStorage.class) {
                local = storageQueue;
                if (local == null) {
                    local = storageQueue = new DispatchQueue("oxContactChangesQueue");
                }
            }
        }
        return local;
    }

    /** directory holding the saved copies of previous avatars */
    public static File getPhotosDir() {
        File dir = new File(ApplicationLoader.getFilesDirFixed(), PHOTOS_DIR);
        if (!dir.exists()) {
            try {
                dir.mkdirs();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        return dir;
    }

    public static File getPhotoFile(long userId, long photoId) {
        return new File(getPhotosDir(), userId + "_" + photoId + ".jpg");
    }

    private ContactChangesStorage() {
        super(ApplicationLoader.applicationContext, new File(ApplicationLoader.getFilesDirFixed(), DB_NAME).getAbsolutePath(), null, DB_VERSION);
        databaseFile = new File(ApplicationLoader.getFilesDirFixed(), DB_NAME);
    }

    public File getDatabaseFile() {
        return databaseFile;
    }

    // ------------------------------------------------------------------ schema

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_CHANGES + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "account INTEGER NOT NULL DEFAULT 0," +
                "user_id INTEGER NOT NULL DEFAULT 0," +
                "type TEXT," +
                "old_value TEXT," +
                "new_value TEXT," +
                "timestamp INTEGER NOT NULL DEFAULT 0," +
                "extra TEXT" +
                ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_changes_account_time ON " + TABLE_CHANGES + " (account, timestamp)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_changes_user_type ON " + TABLE_CHANGES + " (account, user_id, type)");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_BIOS + " (" +
                "account INTEGER NOT NULL DEFAULT 0," +
                "user_id INTEGER NOT NULL DEFAULT 0," +
                "bio TEXT," +
                "PRIMARY KEY (account, user_id)" +
                ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        onCreate(db);
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        onCreate(db);
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        try {
            if (!db.isReadOnly()) {
                db.execSQL("PRAGMA synchronous=NORMAL");
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private SQLiteDatabase db() {
        return getWritableDatabase();
    }

    // ------------------------------------------------------------------ writes

    public long insert(ContactChange change) {
        if (change == null) {
            return 0;
        }
        try {
            ContentValues cv = new ContentValues();
            cv.put("account", change.account);
            cv.put("user_id", change.userId);
            cv.put("type", change.type);
            cv.put("old_value", change.oldValue);
            cv.put("new_value", change.newValue);
            cv.put("timestamp", change.timestamp == 0 ? System.currentTimeMillis() : change.timestamp);
            cv.put("extra", change.extra);
            long id = db().insert(TABLE_CHANGES, null, cv);
            if (id > 0) {
                change.id = id;
            }
            return id;
        } catch (Throwable e) {
            FileLog.e(e);
            return 0;
        }
    }

    /** keeps only the newest {@link #MAX_ROWS} rows */
    public void trim() {
        try {
            db().execSQL("DELETE FROM " + TABLE_CHANGES + " WHERE id NOT IN (" +
                    "SELECT id FROM " + TABLE_CHANGES + " ORDER BY timestamp DESC, id DESC LIMIT " + MAX_ROWS + ")");
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public void clear(int account) {
        try {
            deleteSavedPhotos(account);
            db().delete(TABLE_CHANGES, "account = ?", new String[]{String.valueOf(account)});
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void deleteSavedPhotos(int account) {
        Cursor c = null;
        try {
            c = db().rawQuery("SELECT extra FROM " + TABLE_CHANGES + " WHERE account = ? AND extra IS NOT NULL",
                    new String[]{String.valueOf(account)});
            while (c.moveToNext()) {
                String path = c.isNull(0) ? null : c.getString(0);
                if (path == null) {
                    continue;
                }
                try {
                    File file = new File(path);
                    if (file.exists()) {
                        file.delete();
                    }
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            close(c);
        }
    }

    // ------------------------------------------------------------------ reads

    public ArrayList<ContactChange> load(int account, int limit, int offset) {
        ArrayList<ContactChange> result = new ArrayList<>();
        Cursor c = null;
        try {
            String limitStr = String.format(Locale.US, "%d,%d", Math.max(0, offset), Math.max(1, limit));
            c = db().query(TABLE_CHANGES, PROJECTION, "account = ?", new String[]{String.valueOf(account)},
                    null, null, "timestamp DESC, id DESC", limitStr);
            while (c.moveToNext()) {
                result.add(fromCursor(c));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            close(c);
        }
        return result;
    }

    /** the {@code new_value} of the newest row of this (account, user, type), null when there is none */
    public String getLastNewValue(int account, long userId, String type) {
        Cursor c = null;
        try {
            c = db().rawQuery("SELECT new_value FROM " + TABLE_CHANGES +
                            " WHERE account = ? AND user_id = ? AND type = ? ORDER BY timestamp DESC, id DESC LIMIT 1",
                    new String[]{String.valueOf(account), String.valueOf(userId), type});
            if (c.moveToFirst()) {
                return c.isNull(0) ? "" : c.getString(0);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            close(c);
        }
        return null;
    }

    public int getCount(int account) {
        Cursor c = null;
        try {
            c = db().rawQuery("SELECT COUNT(*) FROM " + TABLE_CHANGES + " WHERE account = ?",
                    new String[]{String.valueOf(account)});
            if (c.moveToFirst()) {
                return c.getInt(0);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            close(c);
        }
        return 0;
    }

    // ------------------------------------------------------------------ bio baseline

    /** null when we have never seen a bio for this user (so the first one is not a "change") */
    public String getBio(int account, long userId) {
        Cursor c = null;
        try {
            c = db().rawQuery("SELECT bio FROM " + TABLE_BIOS + " WHERE account = ? AND user_id = ? LIMIT 1",
                    new String[]{String.valueOf(account), String.valueOf(userId)});
            if (c.moveToFirst()) {
                return c.isNull(0) ? "" : c.getString(0);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            close(c);
        }
        return null;
    }

    public void setBio(int account, long userId, String bio) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("account", account);
            cv.put("user_id", userId);
            cv.put("bio", bio == null ? "" : bio);
            db().insertWithOnConflict(TABLE_BIOS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static ContactChange fromCursor(Cursor c) {
        ContactChange change = new ContactChange();
        change.id = c.getLong(C_ID);
        change.account = c.getInt(C_ACCOUNT);
        change.userId = c.getLong(C_USER_ID);
        change.type = c.isNull(C_TYPE) ? ContactChange.TYPE_NAME : c.getString(C_TYPE);
        change.oldValue = c.isNull(C_OLD) ? null : c.getString(C_OLD);
        change.newValue = c.isNull(C_NEW) ? null : c.getString(C_NEW);
        change.timestamp = c.getLong(C_TIME);
        change.extra = c.isNull(C_EXTRA) ? null : c.getString(C_EXTRA);
        return change;
    }

    private static void close(Cursor c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignore) {
            }
        }
    }
}
