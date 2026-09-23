package org.telegram.messenger.ayu.radar;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;

/**
 * AyuGram Mention Radar: per-account SQLite storage for collected hits.
 * <p>
 * Mirrors {@code org.telegram.messenger.ayu.AyuHistoryStorage}: a plain
 * {@link SQLiteOpenHelper} living in {@code filesDir/ayu/} (so Telegram cache cleanups do not touch
 * it), with a dedicated {@link DispatchQueue} shared by every account instance. Every public method
 * here is meant to be called from {@link #getQueue()} — the class itself does no thread hopping.
 */
public class RadarStorage extends SQLiteOpenHelper {

    private static final int DB_VERSION = 1;

    /** //perf: how many ids go into one {@code msgId IN (...)} statement of {@link #filterExisting} */
    private static final int EXISTS_CHUNK = 500;

    public static final String TABLE_HITS = "radar_hits";

    private static final String[] PROJECTION = {
            "rowId",       // 0
            "dialogId",    // 1
            "msgId",       // 2
            "date",        // 3
            "kind",        // 4
            "matched",     // 5
            "senderId",    // 6
            "snippet",     // 7
            "matchStart",  // 8
            "matchEnd",    // 9
            "readFlag",    // 10
            "data"         // 11
    };

    private static final int C_ROW_ID = 0;
    private static final int C_DIALOG_ID = 1;
    private static final int C_MSG_ID = 2;
    private static final int C_DATE = 3;
    private static final int C_KIND = 4;
    private static final int C_MATCHED = 5;
    private static final int C_SENDER_ID = 6;
    private static final int C_SNIPPET = 7;
    private static final int C_MATCH_START = 8;
    private static final int C_MATCH_END = 9;
    private static final int C_READ = 10;
    private static final int C_DATA = 11;

    private static final RadarStorage[] instances = new RadarStorage[UserConfig.MAX_ACCOUNT_COUNT];
    private static volatile DispatchQueue radarQueue;

    private final int currentAccount;
    private final File databaseFile;

    public static RadarStorage getInstance(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            account = 0;
        }
        RadarStorage local = instances[account];
        if (local == null) {
            synchronized (RadarStorage.class) {
                local = instances[account];
                if (local == null) {
                    local = instances[account] = new RadarStorage(account);
                }
            }
        }
        return local;
    }

    /** dedicated background queue; every radar database access runs here */
    public static DispatchQueue getQueue() {
        DispatchQueue local = radarQueue;
        if (local == null) {
            synchronized (RadarStorage.class) {
                local = radarQueue;
                if (local == null) {
                    local = radarQueue = new DispatchQueue("ayuRadarQueue");
                }
            }
        }
        return local;
    }

    public static File getRadarDir() {
        File dir = new File(ApplicationLoader.getFilesDirFixed(), "ayu");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static String getDatabaseName(int account) {
        return "radar_" + account + ".db";
    }

    private RadarStorage(int account) {
        super(ApplicationLoader.applicationContext, new File(getRadarDir(), getDatabaseName(account)).getAbsolutePath(), null, DB_VERSION);
        currentAccount = account;
        databaseFile = new File(getRadarDir(), getDatabaseName(account));
    }

    public File getDatabaseFile() {
        return databaseFile;
    }

    public int getCurrentAccount() {
        return currentAccount;
    }

    // ------------------------------------------------------------------ schema

    private static String createTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + TABLE_HITS + " (" +
                "rowId INTEGER PRIMARY KEY AUTOINCREMENT," +
                "dialogId INTEGER NOT NULL DEFAULT 0," +
                "msgId INTEGER NOT NULL DEFAULT 0," +
                "date INTEGER NOT NULL DEFAULT 0," +
                "kind INTEGER NOT NULL DEFAULT 0," +
                "matched TEXT," +
                "senderId INTEGER NOT NULL DEFAULT 0," +
                "snippet TEXT," +
                "matchStart INTEGER NOT NULL DEFAULT -1," +
                "matchEnd INTEGER NOT NULL DEFAULT -1," +
                "readFlag INTEGER NOT NULL DEFAULT 0," +
                "data BLOB" +
                ")";
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(createTableSql());
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_radar_unique ON " + TABLE_HITS + " (dialogId, msgId, kind)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_radar_date ON " + TABLE_HITS + " (date)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_radar_kind_date ON " + TABLE_HITS + " (kind, date)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_radar_unread ON " + TABLE_HITS + " (readFlag)");
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

    private static ContentValues toValues(RadarHit hit) {
        ContentValues cv = new ContentValues();
        cv.put("dialogId", hit.dialogId);
        cv.put("msgId", hit.msgId);
        cv.put("date", hit.date);
        cv.put("kind", hit.kind);
        cv.put("matched", hit.matched);
        cv.put("senderId", hit.senderId);
        cv.put("snippet", hit.snippet);
        cv.put("matchStart", hit.matchStart);
        cv.put("matchEnd", hit.matchEnd);
        cv.put("readFlag", hit.read ? 1 : 0);
        cv.put("data", hit.data);
        return cv;
    }

    /**
     * Inserts a hit, replacing an existing row with the same (dialogId, msgId, kind).
     *
     * @return true when the row is new (so callers may notify / ring), false on replace or error
     */
    public boolean put(RadarHit hit) {
        if (hit == null) {
            return false;
        }
        try {
            boolean isNew = !exists(hit.dialogId, hit.msgId, hit.kind);
            long id = db().insertWithOnConflict(TABLE_HITS, null, toValues(hit), SQLiteDatabase.CONFLICT_REPLACE);
            if (id > 0) {
                hit.rowId = id;
            }
            return isNew && id > 0;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /** bulk insert used by the history scan; returns the number of rows that were new */
    public int putAll(ArrayList<RadarHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return 0;
        }
        int added = 0;
        SQLiteDatabase database = null;
        try {
            database = db();
            database.beginTransaction();
            for (int i = 0; i < hits.size(); i++) {
                RadarHit hit = hits.get(i);
                if (hit == null) {
                    continue;
                }
                if (!exists(hit.dialogId, hit.msgId, hit.kind)) {
                    added++;
                }
                long id = database.insertWithOnConflict(TABLE_HITS, null, toValues(hit), SQLiteDatabase.CONFLICT_REPLACE);
                if (id > 0) {
                    hit.rowId = id;
                }
            }
            database.setTransactionSuccessful();
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (database != null) {
                try {
                    database.endTransaction();
                } catch (Throwable ignore) {
                }
            }
        }
        return added;
    }

    public boolean exists(long dialogId, int msgId, int kind) {
        Cursor c = null;
        try {
            c = db().rawQuery("SELECT 1 FROM " + TABLE_HITS + " WHERE dialogId = ? AND msgId = ? AND kind = ? LIMIT 1",
                    new String[]{String.valueOf(dialogId), String.valueOf(msgId), String.valueOf(kind)});
            return c.moveToFirst();
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /**
     * //perf: batched replacement for a per-message {@link #exists(long, int, int)} loop.
     * <p>
     * The live collector used to run one {@code SELECT 1} per (message, kind) pair, i.e. four
     * round trips per message and 200 per 50-message history page. This answers the same question
     * for a whole page with one statement per {@value #EXISTS_CHUNK} ids.
     *
     * @return the subset of {@code msgIds} that already has a hit of <i>any</i> kind in this dialog
     *         (the radar stores at most one hit per message, so "any kind" is what the caller asked)
     */
    public HashSet<Integer> filterExisting(long dialogId, ArrayList<Integer> msgIds) {
        final HashSet<Integer> result = new HashSet<>();
        if (msgIds == null || msgIds.isEmpty()) {
            return result;
        }
        try {
            final SQLiteDatabase database = db();
            final StringBuilder sb = new StringBuilder();
            for (int from = 0; from < msgIds.size(); from += EXISTS_CHUNK) {
                final int to = Math.min(msgIds.size(), from + EXISTS_CHUNK);
                sb.setLength(0);
                for (int i = from; i < to; i++) {
                    final Integer id = msgIds.get(i);
                    if (id == null) {
                        continue;
                    }
                    if (sb.length() > 0) {
                        sb.append(',');
                    }
                    // both values are numeric primitives: nothing to escape
                    sb.append(id.intValue());
                }
                if (sb.length() == 0) {
                    continue;
                }
                Cursor c = null;
                try {
                    c = database.rawQuery("SELECT msgId FROM " + TABLE_HITS + " WHERE dialogId = " + dialogId
                            + " AND msgId IN (" + sb + ")", null);
                    while (c.moveToNext()) {
                        result.add(c.getInt(0));
                    }
                } finally {
                    if (c != null) {
                        try {
                            c.close();
                        } catch (Throwable ignore) {
                        }
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return result;
    }

    public void markRead(long rowId) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("readFlag", 1);
            db().update(TABLE_HITS, cv, "rowId = ?", new String[]{String.valueOf(rowId)});
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public void markAllRead() {
        try {
            ContentValues cv = new ContentValues();
            cv.put("readFlag", 1);
            db().update(TABLE_HITS, cv, "readFlag = 0", null);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public void delete(long rowId) {
        try {
            db().delete(TABLE_HITS, "rowId = ?", new String[]{String.valueOf(rowId)});
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public void deleteByDialog(long dialogId) {
        try {
            db().delete(TABLE_HITS, "dialogId = ?", new String[]{String.valueOf(dialogId)});
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public void clear() {
        try {
            db().execSQL("DELETE FROM " + TABLE_HITS);
            db().execSQL("DELETE FROM sqlite_sequence WHERE name = '" + TABLE_HITS + "'");
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** keeps only the newest {@code maxRows} hits */
    public void trim(int maxRows) {
        if (maxRows <= 0) {
            return;
        }
        try {
            db().execSQL("DELETE FROM " + TABLE_HITS + " WHERE rowId NOT IN (" +
                    "SELECT rowId FROM " + TABLE_HITS + " ORDER BY date DESC, rowId DESC LIMIT " + maxRows + ")");
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // ------------------------------------------------------------------ reads

    /**
     * @param kind       {@link RadarHit#FILTER_ALL} or one of the KIND_* constants
     * @param beforeDate 0 for the newest page, otherwise the {@code date} of the last loaded hit
     */
    public ArrayList<RadarHit> load(int kind, int limit, int offset) {
        ArrayList<RadarHit> result = new ArrayList<>();
        Cursor c = null;
        try {
            String where = kind == RadarHit.FILTER_ALL ? null : "kind = " + kind;
            String limitStr = String.format(Locale.US, "%d,%d", Math.max(0, offset), Math.max(1, limit));
            c = db().query(TABLE_HITS, PROJECTION, where, null, null, null, "date DESC, rowId DESC", limitStr);
            while (c.moveToNext()) {
                result.add(fromCursor(c));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignore) {
                }
            }
        }
        return result;
    }

    public int getUnreadCount() {
        return count("readFlag = 0");
    }

    public int getUnreadCount(int kind) {
        return count("readFlag = 0 AND kind = " + kind);
    }

    public int getTotalCount() {
        return count(null);
    }

    private int count(String where) {
        Cursor c = null;
        try {
            c = db().rawQuery("SELECT COUNT(*) FROM " + TABLE_HITS + (where == null ? "" : " WHERE " + where), null);
            if (c.moveToFirst()) {
                return c.getInt(0);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignore) {
                }
            }
        }
        return 0;
    }

    private static RadarHit fromCursor(Cursor c) {
        RadarHit hit = new RadarHit();
        hit.rowId = c.getLong(C_ROW_ID);
        hit.dialogId = c.getLong(C_DIALOG_ID);
        hit.msgId = c.getInt(C_MSG_ID);
        hit.date = c.getInt(C_DATE);
        hit.kind = c.getInt(C_KIND);
        hit.matched = c.isNull(C_MATCHED) ? null : c.getString(C_MATCHED);
        hit.senderId = c.getLong(C_SENDER_ID);
        hit.snippet = c.isNull(C_SNIPPET) ? null : c.getString(C_SNIPPET);
        hit.matchStart = c.getInt(C_MATCH_START);
        hit.matchEnd = c.getInt(C_MATCH_END);
        hit.read = c.getInt(C_READ) != 0;
        hit.data = c.isNull(C_DATA) ? null : c.getBlob(C_DATA);
        return hit;
    }

    public long getDatabaseSize() {
        long size = 0;
        try {
            size += databaseFile.length();
            size += new File(databaseFile.getAbsolutePath() + "-wal").length();
            size += new File(databaseFile.getAbsolutePath() + "-shm").length();
            size += new File(databaseFile.getAbsolutePath() + "-journal").length();
        } catch (Throwable ignore) {
        }
        return size;
    }
}
