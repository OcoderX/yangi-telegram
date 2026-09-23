package org.telegram.messenger.ayu;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ayu.entities.AyuMessageBase;
import org.telegram.messenger.ayu.entities.DeletedMessage;
import org.telegram.messenger.ayu.entities.EditedMessage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dedicated SQLite database for the AyuGram message history (deleted messages, edit revisions and
 * self-destructing media). Lives in {@code filesDir/ayu/} so it survives Telegram cache cleanups.
 * <p>
 * All writes are executed on the dedicated {@code ayuStorageQueue}; reads are synchronous but
 * thread-safe (android's SQLiteDatabase serializes access internally).
 */
public class AyuHistoryStorage extends SQLiteOpenHelper {

    private static final int DB_VERSION = 1;

    public static final String TABLE_DELETED = "deleted_messages";
    public static final String TABLE_EDITED = "edited_messages";
    public static final String TABLE_TTL = "ttl_media";

    // ---- projection / column indices ----
    public static final String[] PROJECTION = {
            "fakeId",                        // 0
            "userId",                        // 1
            "dialogId",                      // 2
            "groupedId",                     // 3
            "peerId",                        // 4
            "fromId",                        // 5
            "topicId",                       // 6
            "messageId",                     // 7
            "date",                          // 8
            "flags",                         // 9
            "editDate",                      // 10
            "views",                         // 11
            "fwdFlags",                      // 12
            "fwdFromId",                     // 13
            "fwdName",                       // 14
            "fwdDate",                       // 15
            "fwdPostAuthor",                 // 16
            "replyFlags",                    // 17
            "replyMessageId",                // 18
            "replyPeerId",                   // 19
            "replyTopId",                    // 20
            "replyForumTopic",               // 21
            "entityCreateDate",              // 22
            "text",                          // 23
            "textEntities",                  // 24
            "mediaPath",                     // 25
            "hqThumbPath",                   // 26
            "documentType",                  // 27
            "documentSerialized",            // 28
            "thumbsSerialized",              // 29
            "documentAttributesSerialized",  // 30
            "mimeType",                      // 31
            "reactionsSerialized",           // 32
            "messageSerialized"              // 33
    };

    private static final int C_FAKE_ID = 0;
    private static final int C_USER_ID = 1;
    private static final int C_DIALOG_ID = 2;
    private static final int C_GROUPED_ID = 3;
    private static final int C_PEER_ID = 4;
    private static final int C_FROM_ID = 5;
    private static final int C_TOPIC_ID = 6;
    private static final int C_MESSAGE_ID = 7;
    private static final int C_DATE = 8;
    private static final int C_FLAGS = 9;
    private static final int C_EDIT_DATE = 10;
    private static final int C_VIEWS = 11;
    private static final int C_FWD_FLAGS = 12;
    private static final int C_FWD_FROM_ID = 13;
    private static final int C_FWD_NAME = 14;
    private static final int C_FWD_DATE = 15;
    private static final int C_FWD_POST_AUTHOR = 16;
    private static final int C_REPLY_FLAGS = 17;
    private static final int C_REPLY_MESSAGE_ID = 18;
    private static final int C_REPLY_PEER_ID = 19;
    private static final int C_REPLY_TOP_ID = 20;
    private static final int C_REPLY_FORUM_TOPIC = 21;
    private static final int C_ENTITY_CREATE_DATE = 22;
    private static final int C_TEXT = 23;
    private static final int C_TEXT_ENTITIES = 24;
    private static final int C_MEDIA_PATH = 25;
    private static final int C_HQ_THUMB_PATH = 26;
    private static final int C_DOCUMENT_TYPE = 27;
    private static final int C_DOCUMENT = 28;
    private static final int C_THUMBS = 29;
    private static final int C_DOCUMENT_ATTRIBUTES = 30;
    private static final int C_MIME_TYPE = 31;
    private static final int C_REACTIONS = 32;
    private static final int C_MESSAGE = 33;

    private static volatile AyuHistoryStorage instance;
    private static volatile DispatchQueue storageQueue;

    private final File databaseFile;

    public static AyuHistoryStorage getInstance() {
        AyuHistoryStorage local = instance;
        if (local == null) {
            synchronized (AyuHistoryStorage.class) {
                local = instance;
                if (local == null) {
                    local = instance = new AyuHistoryStorage();
                }
            }
        }
        return local;
    }

    /** dedicated queue used for every write (and for the deferred media copies) */
    public static DispatchQueue getQueue() {
        DispatchQueue local = storageQueue;
        if (local == null) {
            synchronized (AyuHistoryStorage.class) {
                local = storageQueue;
                if (local == null) {
                    local = storageQueue = new DispatchQueue("ayuStorageQueue");
                }
            }
        }
        return local;
    }

    public static File getAyuDir() {
        File dir = new File(ApplicationLoader.getFilesDirFixed(), "ayu");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static File getMediaDir() {
        File dir = new File(getAyuDir(), "media");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private AyuHistoryStorage() {
        super(ApplicationLoader.applicationContext, new File(getAyuDir(), AyuConstants.DB_NAME).getAbsolutePath(), null, DB_VERSION);
        databaseFile = new File(getAyuDir(), AyuConstants.DB_NAME);
        try {
            setWriteAheadLoggingEnabled(AyuConfig.walMode);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public File getDatabaseFile() {
        return databaseFile;
    }

    // ------------------------------------------------------------------ schema

    private static String createTableSql(String name) {
        return "CREATE TABLE IF NOT EXISTS " + name + " (" +
                "fakeId INTEGER PRIMARY KEY AUTOINCREMENT," +
                "userId INTEGER NOT NULL DEFAULT 0," +
                "dialogId INTEGER NOT NULL DEFAULT 0," +
                "groupedId INTEGER NOT NULL DEFAULT 0," +
                "peerId INTEGER NOT NULL DEFAULT 0," +
                "fromId INTEGER NOT NULL DEFAULT 0," +
                "topicId INTEGER NOT NULL DEFAULT 0," +
                "messageId INTEGER NOT NULL DEFAULT 0," +
                "date INTEGER NOT NULL DEFAULT 0," +
                "flags INTEGER NOT NULL DEFAULT 0," +
                "editDate INTEGER NOT NULL DEFAULT 0," +
                "views INTEGER NOT NULL DEFAULT 0," +
                "fwdFlags INTEGER NOT NULL DEFAULT 0," +
                "fwdFromId INTEGER NOT NULL DEFAULT 0," +
                "fwdName TEXT," +
                "fwdDate INTEGER NOT NULL DEFAULT 0," +
                "fwdPostAuthor TEXT," +
                "replyFlags INTEGER NOT NULL DEFAULT 0," +
                "replyMessageId INTEGER NOT NULL DEFAULT 0," +
                "replyPeerId INTEGER NOT NULL DEFAULT 0," +
                "replyTopId INTEGER NOT NULL DEFAULT 0," +
                "replyForumTopic INTEGER NOT NULL DEFAULT 0," +
                "entityCreateDate INTEGER NOT NULL DEFAULT 0," +
                "text TEXT," +
                "textEntities BLOB," +
                "mediaPath TEXT," +
                "hqThumbPath TEXT," +
                "documentType INTEGER NOT NULL DEFAULT 0," +
                "documentSerialized BLOB," +
                "thumbsSerialized BLOB," +
                "documentAttributesSerialized BLOB," +
                "mimeType TEXT," +
                "reactionsSerialized BLOB," +
                "messageSerialized BLOB" +
                ")";
    }

    private static void createIndexes(SQLiteDatabase db, String name, boolean uniqueMessage, boolean uniqueRevision) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_" + name + "_main ON " + name + " (userId, dialogId, messageId)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_" + name + "_topic ON " + name + " (userId, dialogId, topicId, messageId)");
        if (uniqueMessage) {
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_" + name + "_unique ON " + name + " (userId, dialogId, messageId)");
        }
        if (uniqueRevision) {
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_" + name + "_unique ON " + name + " (userId, dialogId, messageId, editDate, date)");
        }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(createTableSql(TABLE_DELETED));
        db.execSQL(createTableSql(TABLE_EDITED));
        db.execSQL(createTableSql(TABLE_TTL));
        createIndexes(db, TABLE_DELETED, true, false);
        createIndexes(db, TABLE_EDITED, false, true);
        createIndexes(db, TABLE_TTL, true, false);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // nothing to migrate yet; make sure everything exists
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
                // WAL itself is already turned on/off through setWriteAheadLoggingEnabled() in the
                // constructor; issuing "PRAGMA journal_mode=WAL" again on a pooled connection can throw,
                // so only the non-WAL mode is forced explicitly here.
                if (!AyuConfig.walMode) {
                    db.execSQL("PRAGMA journal_mode=TRUNCATE");
                }
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

    private static ContentValues toValues(AyuMessageBase row) {
        ContentValues cv = new ContentValues();
        cv.put("userId", row.userId);
        cv.put("dialogId", row.dialogId);
        cv.put("groupedId", row.groupedId);
        cv.put("peerId", row.peerId);
        cv.put("fromId", row.fromId);
        cv.put("topicId", row.topicId);
        cv.put("messageId", row.messageId);
        cv.put("date", row.date);
        cv.put("flags", row.flags);
        cv.put("editDate", row.editDate);
        cv.put("views", row.views);
        cv.put("fwdFlags", row.fwdFlags);
        cv.put("fwdFromId", row.fwdFromId);
        cv.put("fwdName", row.fwdName);
        cv.put("fwdDate", row.fwdDate);
        cv.put("fwdPostAuthor", row.fwdPostAuthor);
        cv.put("replyFlags", row.replyFlags);
        cv.put("replyMessageId", row.replyMessageId);
        cv.put("replyPeerId", row.replyPeerId);
        cv.put("replyTopId", row.replyTopId);
        cv.put("replyForumTopic", row.replyForumTopic ? 1 : 0);
        cv.put("entityCreateDate", row.entityCreateDate);
        cv.put("text", row.text);
        cv.put("textEntities", row.textEntities);
        cv.put("mediaPath", row.mediaPath);
        cv.put("hqThumbPath", row.hqThumbPath);
        cv.put("documentType", row.documentType);
        cv.put("documentSerialized", row.documentSerialized);
        cv.put("thumbsSerialized", row.thumbsSerialized);
        cv.put("documentAttributesSerialized", row.documentAttributesSerialized);
        cv.put("mimeType", row.mimeType);
        cv.put("reactionsSerialized", row.reactionsSerialized);
        cv.put("messageSerialized", row.messageSerialized);
        return cv;
    }

    private static void fill(AyuMessageBase row, Cursor c) {
        row.userId = c.getLong(C_USER_ID);
        row.dialogId = c.getLong(C_DIALOG_ID);
        row.groupedId = c.getLong(C_GROUPED_ID);
        row.peerId = c.getLong(C_PEER_ID);
        row.fromId = c.getLong(C_FROM_ID);
        row.topicId = c.getLong(C_TOPIC_ID);
        row.messageId = c.getInt(C_MESSAGE_ID);
        row.date = c.getInt(C_DATE);
        row.flags = c.getInt(C_FLAGS);
        row.editDate = c.getInt(C_EDIT_DATE);
        row.views = c.getInt(C_VIEWS);
        row.fwdFlags = c.getInt(C_FWD_FLAGS);
        row.fwdFromId = c.getLong(C_FWD_FROM_ID);
        row.fwdName = c.getString(C_FWD_NAME);
        row.fwdDate = c.getInt(C_FWD_DATE);
        row.fwdPostAuthor = c.getString(C_FWD_POST_AUTHOR);
        row.replyFlags = c.getInt(C_REPLY_FLAGS);
        row.replyMessageId = c.getInt(C_REPLY_MESSAGE_ID);
        row.replyPeerId = c.getLong(C_REPLY_PEER_ID);
        row.replyTopId = c.getInt(C_REPLY_TOP_ID);
        row.replyForumTopic = c.getInt(C_REPLY_FORUM_TOPIC) != 0;
        row.entityCreateDate = c.getInt(C_ENTITY_CREATE_DATE);
        row.text = c.getString(C_TEXT);
        row.textEntities = c.isNull(C_TEXT_ENTITIES) ? null : c.getBlob(C_TEXT_ENTITIES);
        row.mediaPath = c.getString(C_MEDIA_PATH);
        row.hqThumbPath = c.getString(C_HQ_THUMB_PATH);
        row.documentType = c.getInt(C_DOCUMENT_TYPE);
        row.documentSerialized = c.isNull(C_DOCUMENT) ? null : c.getBlob(C_DOCUMENT);
        row.thumbsSerialized = c.isNull(C_THUMBS) ? null : c.getBlob(C_THUMBS);
        row.documentAttributesSerialized = c.isNull(C_DOCUMENT_ATTRIBUTES) ? null : c.getBlob(C_DOCUMENT_ATTRIBUTES);
        row.mimeType = c.getString(C_MIME_TYPE);
        row.reactionsSerialized = c.isNull(C_REACTIONS) ? null : c.getBlob(C_REACTIONS);
        row.messageSerialized = c.isNull(C_MESSAGE) ? null : c.getBlob(C_MESSAGE);
    }

    private static DeletedMessage readDeleted(Cursor c) {
        DeletedMessage row = new DeletedMessage();
        row.fakeId = c.getLong(C_FAKE_ID);
        fill(row, c);
        return row;
    }

    private static EditedMessage readEdited(Cursor c) {
        EditedMessage row = new EditedMessage();
        row.fakeId = c.getLong(C_FAKE_ID);
        fill(row, c);
        return row;
    }

    /** Insert (synchronously - callers must already be on the ayu queue). */
    public long insertDeleted(DeletedMessage row) {
        try {
            return db().insertWithOnConflict(TABLE_DELETED, null, toValues(row), SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Throwable e) {
            FileLog.e(e);
            return -1;
        }
    }

    public void insertDeletedBatch(List<DeletedMessage> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        SQLiteDatabase database;
        try {
            database = db();
        } catch (Throwable e) {
            FileLog.e(e);
            return;
        }
        try {
            database.beginTransaction();
            for (int a = 0; a < rows.size(); a++) {
                try {
                    database.insertWithOnConflict(TABLE_DELETED, null, toValues(rows.get(a)), SQLiteDatabase.CONFLICT_REPLACE);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            database.setTransactionSuccessful();
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            try {
                database.endTransaction();
            } catch (Throwable ignore) {
            }
        }
    }

    public long insertEdited(EditedMessage row) {
        try {
            return db().insertWithOnConflict(TABLE_EDITED, null, toValues(row), SQLiteDatabase.CONFLICT_IGNORE);
        } catch (Throwable e) {
            FileLog.e(e);
            return -1;
        }
    }

    public long insertTTLMedia(DeletedMessage row) {
        try {
            return db().insertWithOnConflict(TABLE_TTL, null, toValues(row), SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Throwable e) {
            FileLog.e(e);
            return -1;
        }
    }

    public void updateMediaPath(String table, long fakeId, String mediaPath, String thumbPath) {
        if (fakeId <= 0) {
            return;
        }
        try {
            ContentValues cv = new ContentValues();
            cv.put("mediaPath", mediaPath);
            if (thumbPath != null) {
                cv.put("hqThumbPath", thumbPath);
            }
            db().update(table, cv, "fakeId = ?", new String[]{Long.toString(fakeId)});
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public void deleteDeleted(long fakeId) {
        try {
            db().delete(TABLE_DELETED, "fakeId = ?", new String[]{Long.toString(fakeId)});
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public void deleteDeletedByIds(long userId, long dialogId, List<Integer> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            for (int a = 0; a < messageIds.size(); a++) {
                if (a != 0) {
                    sb.append(',');
                }
                sb.append(messageIds.get(a).intValue());
            }
            db().delete(TABLE_DELETED, String.format(Locale.US, "userId = %d AND dialogId = %d AND messageId IN (%s)", userId, dialogId, sb.toString()), null);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** ayu: drops every kept message of a dialog (used by "clear history" / "delete chat") */
    public void deleteDeletedForDialog(long userId, long dialogId) {
        try {
            db().delete(TABLE_DELETED, String.format(Locale.US, "userId = %d AND dialogId = %d", userId, dialogId), null);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // ------------------------------------------------------------------ reads

    /** newest first; ids in (minId, maxId] - pass 0 for minId and 0 (or Integer.MAX_VALUE) for maxId to get everything */
    public ArrayList<DeletedMessage> getDeletedMessages(long userId, long dialogId, long topicId, int minId, int maxId, int limit) {
        ArrayList<DeletedMessage> result = new ArrayList<>();
        Cursor c = null;
        try {
            StringBuilder where = new StringBuilder("userId = ? AND dialogId = ?");
            ArrayList<String> args = new ArrayList<>();
            args.add(Long.toString(userId));
            args.add(Long.toString(dialogId));
            if (topicId == 1) {
                // forum "General" topic: messages posted there carry no reply_to.forum_topic at all,
                // so they were stored with topicId = 0 - accept both.
                where.append(" AND topicId IN (0, 1)");
            } else if (topicId != 0) {
                where.append(" AND topicId = ?");
                args.add(Long.toString(topicId));
            }
            if (minId > 0) {
                where.append(" AND messageId > ?");
                args.add(Integer.toString(minId));
            }
            if (maxId > 0 && maxId != Integer.MAX_VALUE) {
                where.append(" AND messageId <= ?");
                args.add(Integer.toString(maxId));
            }
            c = db().query(TABLE_DELETED, PROJECTION, where.toString(), args.toArray(new String[0]),
                    null, null, "messageId DESC", limit > 0 ? Integer.toString(limit) : null);
            while (c.moveToNext()) {
                result.add(readDeleted(c));
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

    public DeletedMessage getDeletedMessage(long userId, long dialogId, int messageId) {
        Cursor c = null;
        try {
            c = db().query(TABLE_DELETED, PROJECTION, "userId = ? AND dialogId = ? AND messageId = ?",
                    new String[]{Long.toString(userId), Long.toString(dialogId), Integer.toString(messageId)},
                    null, null, null, "1");
            if (c.moveToNext()) {
                return readDeleted(c);
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
        return null;
    }

    public DeletedMessage getTTLMedia(long userId, long dialogId, int messageId) {
        Cursor c = null;
        try {
            c = db().query(TABLE_TTL, PROJECTION, "userId = ? AND dialogId = ? AND messageId = ?",
                    new String[]{Long.toString(userId), Long.toString(dialogId), Integer.toString(messageId)},
                    null, null, null, "1");
            if (c.moveToNext()) {
                return readDeleted(c);
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
        return null;
    }

    public boolean isDeleted(long userId, long dialogId, int messageId) {
        Cursor c = null;
        try {
            c = db().query(TABLE_DELETED, new String[]{"fakeId"}, "userId = ? AND dialogId = ? AND messageId = ?",
                    new String[]{Long.toString(userId), Long.toString(dialogId), Integer.toString(messageId)},
                    null, null, null, "1");
            return c.moveToNext();
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
        return false;
    }

    public int getDeletedMessagesCount(long userId, long dialogId) {
        Cursor c = null;
        try {
            if (dialogId == 0) {
                c = db().rawQuery("SELECT COUNT(*) FROM " + TABLE_DELETED + " WHERE userId = ?",
                        new String[]{Long.toString(userId)});
            } else {
                c = db().rawQuery("SELECT COUNT(*) FROM " + TABLE_DELETED + " WHERE userId = ? AND dialogId = ?",
                        new String[]{Long.toString(userId), Long.toString(dialogId)});
            }
            if (c.moveToNext()) {
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

    /** oldest first */
    public ArrayList<EditedMessage> getRevisions(long userId, long dialogId, int messageId) {
        ArrayList<EditedMessage> result = new ArrayList<>();
        Cursor c = null;
        try {
            c = db().query(TABLE_EDITED, PROJECTION, "userId = ? AND dialogId = ? AND messageId = ?",
                    new String[]{Long.toString(userId), Long.toString(dialogId), Integer.toString(messageId)},
                    null, null, "entityCreateDate ASC, fakeId ASC", null);
            while (c.moveToNext()) {
                result.add(readEdited(c));
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

    public int getRevisionsCount(long userId, long dialogId, int messageId) {
        Cursor c = null;
        try {
            c = db().rawQuery("SELECT COUNT(*) FROM " + TABLE_EDITED + " WHERE userId = ? AND dialogId = ? AND messageId = ?",
                    new String[]{Long.toString(userId), Long.toString(dialogId), Integer.toString(messageId)});
            if (c.moveToNext()) {
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

    public boolean hasRevisions(long userId, long dialogId, int messageId) {
        return getRevisionsCount(userId, dialogId, messageId) > 0;
    }

    /** ayu edit history: messageId -&gt; number of stored revisions for a whole dialog (one query) */
    public java.util.HashMap<Integer, Integer> getRevisionCounts(long userId, long dialogId) {
        java.util.HashMap<Integer, Integer> result = new java.util.HashMap<>();
        Cursor c = null;
        try {
            c = db().rawQuery("SELECT messageId, COUNT(*) FROM " + TABLE_EDITED + " WHERE userId = ? AND dialogId = ? GROUP BY messageId",
                    new String[]{Long.toString(userId), Long.toString(dialogId)});
            while (c.moveToNext()) {
                result.put(c.getInt(0), c.getInt(1));
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

    /** ayu edit history: drops every stored revision of one message */
    public void deleteRevisions(long userId, long dialogId, int messageId) {
        try {
            db().delete(TABLE_EDITED, "userId = ? AND dialogId = ? AND messageId = ?",
                    new String[]{Long.toString(userId), Long.toString(dialogId), Integer.toString(messageId)});
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // ------------------------------------------------------------------ maintenance

    private static long dirSize(File file) {
        if (file == null || !file.exists()) {
            return 0;
        }
        if (file.isFile()) {
            return file.length();
        }
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                total += dirSize(child);
            }
        }
        return total;
    }

    /** database (+ wal/shm) plus every saved media byte */
    public long getDatabaseSize() {
        long size = 0;
        try {
            File file = databaseFile;
            size += file.length();
            size += new File(file.getAbsolutePath() + "-wal").length();
            size += new File(file.getAbsolutePath() + "-shm").length();
            size += new File(file.getAbsolutePath() + "-journal").length();
            //ayu: covers anti-delete AND edit-history media - AyuSavedMedia.getRootDir() == getMediaDir()
            size += dirSize(getMediaDir());
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return size;
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    /** wipes every stored row and every saved media file. Must be called on the ayu queue. */
    public void clear() {
        try {
            SQLiteDatabase database = db();
            database.execSQL("DELETE FROM " + TABLE_DELETED);
            database.execSQL("DELETE FROM " + TABLE_EDITED);
            database.execSQL("DELETE FROM " + TABLE_TTL);
            try {
                database.execSQL("DELETE FROM sqlite_sequence WHERE name IN ('" + TABLE_DELETED + "','" + TABLE_EDITED + "','" + TABLE_TTL + "')");
            } catch (Throwable ignore) {
            }
            database.execSQL("VACUUM");
        } catch (Throwable e) {
            FileLog.e(e);
        }
        try {
            File media = getMediaDir();
            File[] children = media.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        //ayu-edithistory: the pre-edit media lives under getMediaDir() too, already wiped above
        org.telegram.messenger.ayu.edithistory.AyuEditHistoryCache.invalidateAll();
    }
}
