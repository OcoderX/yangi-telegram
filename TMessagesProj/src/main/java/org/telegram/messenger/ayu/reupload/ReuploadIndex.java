package org.telegram.messenger.ayu.reupload;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;

/**
 * Per-account content-addressed index of media this account already has on Telegram's servers.
 * <p>
 * It is a content-hash keyed mirror of Telegram's own {@code sent_files_v2} table: the value we store
 * is the very same serialized {@link TLRPC.MessageMedia} wrapper plus the same {@code "sent_..."}
 * parent string, so an expired {@code file_reference} is refreshed by the stock
 * {@code FileRefController} without any extra code.
 * <p>
 * The database lives in {@code filesDir/ayu/reupload_&lt;account&gt;.db}. It has to be per account
 * because {@code access_hash} values are only valid for the account that received them.
 */
public class ReuploadIndex extends SQLiteOpenHelper {

    private static final int DB_VERSION = 1;
    private static final String TABLE = "reupload_files";

    // media kinds, informational (shown in the settings screen / useful for debugging)
    public static final int KIND_DOCUMENT = 0;
    public static final int KIND_PHOTO = 1;
    public static final int KIND_VIDEO = 2;
    public static final int KIND_AUDIO = 3;
    public static final int KIND_VOICE = 4;
    public static final int KIND_GIF = 5;
    public static final int KIND_STICKER = 6;

    private static final ReuploadIndex[] instances = new ReuploadIndex[UserConfig.MAX_ACCOUNT_COUNT];
    private static volatile DispatchQueue queue;

    private final File databaseFile;
    private final int currentAccount;

    public static ReuploadIndex getInstance(int account) {
        if (account < 0 || account >= instances.length) {
            account = 0;
        }
        ReuploadIndex local = instances[account];
        if (local == null) {
            synchronized (ReuploadIndex.class) {
                local = instances[account];
                if (local == null) {
                    local = instances[account] = new ReuploadIndex(account);
                }
            }
        }
        return local;
    }

    /** dedicated queue for every write and for the received-file hashing */
    public static DispatchQueue getQueue() {
        DispatchQueue local = queue;
        if (local == null) {
            synchronized (ReuploadIndex.class) {
                local = queue;
                if (local == null) {
                    local = queue = new DispatchQueue("ayuReuploadQueue");
                }
            }
        }
        return local;
    }

    public static File getDir() {
        File dir = new File(ApplicationLoader.getFilesDirFixed(), "ayu");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static String getDatabaseName(int account) {
        return "reupload_" + account + ".db";
    }

    private ReuploadIndex(int account) {
        super(ApplicationLoader.applicationContext, new File(getDir(), getDatabaseName(account)).getAbsolutePath(), null, DB_VERSION);
        currentAccount = account;
        databaseFile = new File(getDir(), getDatabaseName(account));
    }

    public int getCurrentAccount() {
        return currentAccount;
    }

    public File getDatabaseFile() {
        return databaseFile;
    }

    // ------------------------------------------------------------------ schema

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + " (" +
                "hash TEXT NOT NULL," +
                "size INTEGER NOT NULL DEFAULT 0," +
                "type INTEGER NOT NULL DEFAULT 0," +
                "kind INTEGER NOT NULL DEFAULT 0," +
                "data BLOB," +
                "parent TEXT," +
                "mime TEXT," +
                "file_name TEXT," +
                "date INTEGER NOT NULL DEFAULT 0," +
                "times_reused INTEGER NOT NULL DEFAULT 0," +
                "PRIMARY KEY (hash, size, type)" +
                ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_" + TABLE + "_hash ON " + TABLE + " (hash)");
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

    // ------------------------------------------------------------------ reads

    /**
     * Mirrors {@code MessagesStorage.getSentFile}: returns {@code {TLObject media, String parent}}
     * or null when nothing is indexed under that content hash.
     */
    public Object[] get(String hash, long size, int type) {
        if (hash == null) {
            return null;
        }
        Cursor cursor = null;
        try {
            cursor = getReadableDatabase().rawQuery(
                    "SELECT data, parent FROM " + TABLE + " WHERE hash = ? AND size = ? AND type = ?",
                    new String[]{hash, Long.toString(size), Integer.toString(type)});
            if (cursor.moveToFirst()) {
                byte[] bytes = cursor.getBlob(0);
                String parent = cursor.getString(1);
                TLObject media = deserialize(bytes);
                if (media != null) {
                    return new Object[]{media, parent};
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            closeQuietly(cursor);
        }
        return null;
    }

    public long getEntryCount() {
        Cursor cursor = null;
        try {
            cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + TABLE, null);
            if (cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            closeQuietly(cursor);
        }
        return 0;
    }

    /** total size of every indexed file; the amount of data that never has to be uploaded again */
    public long getIndexedBytes() {
        Cursor cursor = null;
        try {
            cursor = getReadableDatabase().rawQuery("SELECT SUM(size) FROM " + TABLE, null);
            if (cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            closeQuietly(cursor);
        }
        return 0;
    }

    public long getDatabaseSize() {
        try {
            return databaseFile.exists() ? databaseFile.length() : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ writes

    /**
     * Mirrors {@code MessagesStorage.putSentFile}. {@code file} is a {@link TLRPC.Document} or a
     * {@link TLRPC.Photo}; it is wrapped in the matching {@code MessageMedia} exactly like Telegram
     * does, so the blob format is identical.
     */
    public void put(String hash, long size, int type, TLObject file, String parent, String mime, String fileName) {
        if (hash == null || file == null) {
            return;
        }
        try {
            TLRPC.MessageMedia messageMedia = null;
            int kind = KIND_DOCUMENT;
            if (file instanceof TLRPC.Photo) {
                messageMedia = new TLRPC.TL_messageMediaPhoto();
                messageMedia.photo = (TLRPC.Photo) file;
                messageMedia.flags |= 1;
                kind = KIND_PHOTO;
            } else if (file instanceof TLRPC.Document) {
                messageMedia = new TLRPC.TL_messageMediaDocument();
                messageMedia.document = (TLRPC.Document) file;
                messageMedia.flags |= 1;
                kind = kindOf((TLRPC.Document) file);
            }
            if (messageMedia == null) {
                return;
            }
            byte[] bytes = serialize(messageMedia);
            if (bytes == null) {
                return;
            }
            ContentValues values = new ContentValues();
            values.put("hash", hash);
            values.put("size", size);
            values.put("type", type);
            values.put("kind", kind);
            values.put("data", bytes);
            values.put("parent", parent == null ? "" : parent);
            values.put("mime", mime == null ? "" : mime);
            values.put("file_name", fileName == null ? "" : fileName);
            values.put("date", System.currentTimeMillis() / 1000L);
            values.put("times_reused", 0);
            getWritableDatabase().insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public void bumpReuse(String hash, long size, int type) {
        if (hash == null) {
            return;
        }
        try {
            getWritableDatabase().execSQL(
                    "UPDATE " + TABLE + " SET times_reused = times_reused + 1 WHERE hash = ? AND size = ? AND type = ?",
                    new Object[]{hash, size, type});
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * Drops every row whose stored media points at the given remote file id.
     * <p>
     * Called when the server rejects a reused row (an expired {@code file_reference} that even
     * {@code FileRefController} could not refresh, or media deleted server side): without this the
     * stale row would be handed out again on every retry and the send could never recover. Dropping
     * it costs one real upload and then the file is indexed afresh.
     * <p>
     * Must run on {@link #getQueue()}.
     *
     * @return true when at least one row was removed
     */
    public boolean removeByFileId(long fileId) {
        if (fileId == 0) {
            return false;
        }
        Cursor cursor = null;
        boolean removed = false;
        try {
            cursor = getReadableDatabase().rawQuery("SELECT hash, size, type, data FROM " + TABLE, null);
            while (cursor.moveToNext()) {
                final TLObject media = deserialize(cursor.getBlob(3));
                if (fileIdOf(media) != fileId) {
                    continue;
                }
                getWritableDatabase().execSQL(
                        "DELETE FROM " + TABLE + " WHERE hash = ? AND size = ? AND type = ?",
                        new Object[]{cursor.getString(0), cursor.getLong(1), cursor.getInt(2)});
                removed = true;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            closeQuietly(cursor);
        }
        return removed;
    }

    /** remote file id of a stored {@code MessageMedia} wrapper, or of a bare Document/Photo; 0 if none */
    public static long fileIdOf(TLObject object) {
        if (object instanceof TLRPC.MessageMedia) {
            final TLRPC.MessageMedia media = (TLRPC.MessageMedia) object;
            if (media.document != null) {
                return media.document.id;
            }
            if (media.photo != null) {
                return media.photo.id;
            }
            return 0;
        }
        if (object instanceof TLRPC.Document) {
            return ((TLRPC.Document) object).id;
        }
        if (object instanceof TLRPC.Photo) {
            return ((TLRPC.Photo) object).id;
        }
        return 0;
    }

    public void clear() {
        try {
            getWritableDatabase().execSQL("DELETE FROM " + TABLE);
            getWritableDatabase().execSQL("VACUUM");
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // ------------------------------------------------------------------ helpers

    public static int kindOf(TLRPC.Document document) {
        if (document == null) {
            return KIND_DOCUMENT;
        }
        try {
            if (org.telegram.messenger.MessageObject.isStickerDocument(document)) {
                return KIND_STICKER;
            }
            if (org.telegram.messenger.MessageObject.isVoiceDocument(document)) {
                return KIND_VOICE;
            }
            if (org.telegram.messenger.MessageObject.isRoundVideoDocument(document)) {
                return KIND_VIDEO;
            }
            if (org.telegram.messenger.MessageObject.isGifDocument(document)) {
                return KIND_GIF;
            }
            if (org.telegram.messenger.MessageObject.isVideoDocument(document)) {
                return KIND_VIDEO;
            }
            if (org.telegram.messenger.MessageObject.isMusicDocument(document)) {
                return KIND_AUDIO;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return KIND_DOCUMENT;
    }

    private static byte[] serialize(TLObject object) {
        if (object == null) {
            return null;
        }
        try {
            SerializedData data = new SerializedData(object.getObjectSize());
            object.serializeToStream(data);
            byte[] bytes = data.toByteArray();
            data.cleanup();
            return bytes;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static TLObject deserialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            SerializedData data = new SerializedData(bytes);
            TLRPC.MessageMedia media = TLRPC.MessageMedia.TLdeserialize(data, data.readInt32(false), false);
            data.cleanup();
            if (media instanceof TLRPC.TL_messageMediaDocument) {
                return media.document;
            }
            if (media instanceof TLRPC.TL_messageMediaPhoto) {
                return media.photo;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return null;
    }

    private static void closeQuietly(Cursor cursor) {
        if (cursor != null) {
            try {
                cursor.close();
            } catch (Throwable ignore) {
            }
        }
    }
}
