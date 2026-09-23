package org.telegram.messenger.ayu.contacts;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.util.ArrayList;

/**
 * Ox-gram contact tracker: SQLite log of the online/offline transitions of special contacts.
 * <p>
 * One database for every account (each row carries its {@code account}), living in
 * {@code filesDir/ox_contact_tracker.db} — same idea as
 * {@code org.telegram.messenger.ayu.radar.RadarStorage}, but the per-feature {@link
 * org.telegram.messenger.DispatchQueue} is replaced by {@link Utilities#globalQueue}: the tracker
 * writes a couple of rows per hour, so a dedicated thread would be wasteful.
 * <p>
 * Every public method here hops to {@code Utilities.globalQueue} itself and delivers results back on
 * the main thread, so callers never touch the database directly.
 */
public class ContactTrackerStorage extends SQLiteOpenHelper {

    private static final int DB_VERSION = 1;
    public static final String DB_NAME = "ox_contact_tracker.db";
    public static final String TABLE_LOG = "contact_log";

    /** rows kept per user; older transitions are deleted */
    public static final int MAX_ROWS_PER_USER = 5000;

    private static volatile ContactTrackerStorage instance;

    public static ContactTrackerStorage getInstance() {
        ContactTrackerStorage local = instance;
        if (local == null) {
            synchronized (ContactTrackerStorage.class) {
                local = instance;
                if (local == null) {
                    local = instance = new ContactTrackerStorage();
                }
            }
        }
        return local;
    }

    public static File getDatabaseFile() {
        return new File(ApplicationLoader.getFilesDirFixed(), DB_NAME);
    }

    private ContactTrackerStorage() {
        super(ApplicationLoader.applicationContext, getDatabaseFile().getAbsolutePath(), null, DB_VERSION);
    }

    // ------------------------------------------------------------------ schema

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_LOG + " (" +
                "rowId INTEGER PRIMARY KEY AUTOINCREMENT," +
                "user_id INTEGER NOT NULL DEFAULT 0," +
                "account INTEGER NOT NULL DEFAULT 0," +
                "online INTEGER NOT NULL DEFAULT 0," +
                "timestamp INTEGER NOT NULL DEFAULT 0" +
                ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_contact_log_user ON " + TABLE_LOG + " (user_id, account, timestamp)");
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

    // ------------------------------------------------------------------ writes

    /** records a transition; runs on {@link Utilities#globalQueue} */
    public void log(long userId, int account, boolean online, long timestamp) {
        if (userId == 0) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                ContentValues cv = new ContentValues();
                cv.put("user_id", userId);
                cv.put("account", account);
                cv.put("online", online ? 1 : 0);
                cv.put("timestamp", timestamp);
                getWritableDatabase().insert(TABLE_LOG, null, cv);
                trimInternal(userId);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /** keeps only the newest {@link #MAX_ROWS_PER_USER} rows of one user */
    private void trimInternal(long userId) {
        try {
            getWritableDatabase().execSQL("DELETE FROM " + TABLE_LOG + " WHERE user_id = " + userId +
                    " AND rowId NOT IN (SELECT rowId FROM " + TABLE_LOG + " WHERE user_id = " + userId +
                    " ORDER BY timestamp DESC, rowId DESC LIMIT " + MAX_ROWS_PER_USER + ")");
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public void clearUser(long userId, int account, Runnable onDone) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                getWritableDatabase().delete(TABLE_LOG, "user_id = ? AND account = ?",
                        new String[]{String.valueOf(userId), String.valueOf(account)});
            } catch (Throwable e) {
                FileLog.e(e);
            }
            if (onDone != null) {
                AndroidUtilities.runOnUIThread(onDone);
            }
        });
    }

    public void clearAll(Runnable onDone) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                getWritableDatabase().execSQL("DELETE FROM " + TABLE_LOG);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            if (onDone != null) {
                AndroidUtilities.runOnUIThread(onDone);
            }
        });
    }

    // ------------------------------------------------------------------ reads

    /**
     * Loads the newest {@code limit} transitions of one contact, newest first, and delivers them on
     * the main thread.
     */
    public void loadForUser(long userId, int account, int limit, Utilities.Callback<ArrayList<ContactStatusEntry>> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            final ArrayList<ContactStatusEntry> result = loadForUserSync(userId, account, limit);
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.run(result));
            }
        });
    }

    /** must run on {@link Utilities#globalQueue} */
    private ArrayList<ContactStatusEntry> loadForUserSync(long userId, int account, int limit) {
        ArrayList<ContactStatusEntry> result = new ArrayList<>();
        Cursor c = null;
        try {
            c = getWritableDatabase().query(TABLE_LOG,
                    new String[]{"rowId", "user_id", "account", "online", "timestamp"},
                    "user_id = ? AND account = ?",
                    new String[]{String.valueOf(userId), String.valueOf(account)},
                    null, null, "timestamp DESC, rowId DESC",
                    String.valueOf(Math.max(1, limit)));
            while (c.moveToNext()) {
                ContactStatusEntry entry = new ContactStatusEntry();
                entry.rowId = c.getLong(0);
                entry.userId = c.getLong(1);
                entry.account = c.getInt(2);
                entry.online = c.getInt(3) != 0;
                entry.timestamp = c.getLong(4);
                result.add(entry);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            closeSilently(c);
        }
        return result;
    }

    /**
     * Number of recorded transitions of one contact, delivered on the main thread. Used by the
     * tracker list to show "N events".
     */
    public void countForUser(long userId, int account, Utilities.Callback<Integer> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            int count = 0;
            Cursor c = null;
            try {
                c = getWritableDatabase().rawQuery("SELECT COUNT(*) FROM " + TABLE_LOG + " WHERE user_id = ? AND account = ?",
                        new String[]{String.valueOf(userId), String.valueOf(account)});
                if (c.moveToFirst()) {
                    count = c.getInt(0);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                closeSilently(c);
            }
            final int result = count;
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.run(result));
            }
        });
    }

    private static void closeSilently(Cursor c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignore) {
            }
        }
    }
}
