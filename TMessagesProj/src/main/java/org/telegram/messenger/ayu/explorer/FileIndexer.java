package org.telegram.messenger.ayu.explorer;

import android.text.TextUtils;
import android.util.LongSparseArray;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FilePathDatabase;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;

/**
 * AyuGram File Explorer data layer.
 * <p>
 * The index is built in two phases:
 * <ol>
 *     <li>on {@link MessagesStorage#getStorageQueue()}: the local media cache table {@code media_v4}
 *     is scanned, every row is deserialized into a {@link TLRPC.Message} and turned into a
 *     {@link FileEntry} (dialog, message id, date, file name, mime, declared size, TL media);</li>
 *     <li>on the {@link FilePathDatabase} queue: every {@link FileLoader} media directory is walked so
 *     the real on-disk size is known, each entry is resolved with
 *     {@code FileLoader#getPathToAttach} and files on disk that no message claims are added as
 *     extra entries (their dialog is looked up in {@code paths_by_dialog_id}).</li>
 * </ol>
 * The result is cached in memory; {@link #load(boolean)} refreshes it, the "incremental" refresh
 * ({@link #refreshDownloadedState()}) only re-checks the on-disk state of the current entries.
 */
public class FileIndexer {

    /** hard cap on the number of message-backed entries kept in memory */
    public static final int MAX_MESSAGE_ENTRIES = 20000;
    /** hard cap on the number of files picked up from disk */
    public static final int MAX_DISK_FILES = 30000;
    /**
     * The shared cache directory is full of sticker / emoji / thumbnail fragments. A file found
     * there that no message and no path-database row claims is only listed when it is at least this
     * big, otherwise the explorer would be flooded with a few thousand tiny files.
     */
    private static final long MIN_UNCLAIMED_CACHE_FILE_SIZE = 512 * 1024L;
    /** the cached index is considered fresh for this long */
    private static final long CACHE_TTL = 2 * 60 * 1000L;

    private static final FileIndexer[] instances = new FileIndexer[UserConfig.MAX_ACCOUNT_COUNT];

    public static FileIndexer getInstance(int account) {
        FileIndexer local = instances[account];
        if (local == null) {
            synchronized (FileIndexer.class) {
                local = instances[account];
                if (local == null) {
                    local = instances[account] = new FileIndexer(account);
                }
            }
        }
        return local;
    }

    // ------------------------------------------------------------------ model

    /** one dialog worth of files */
    public static class Folder {
        public final long dialogId;
        public final ArrayList<FileEntry> entries = new ArrayList<>();
        public long totalSize;
        public long downloadedSize;
        public int downloadedCount;

        public Folder(long dialogId) {
            this.dialogId = dialogId;
        }

        public String getTitle(int currentAccount) {
            final String name = DialogObject.getName(currentAccount, dialogId);
            if (TextUtils.isEmpty(name)) {
                return String.valueOf(dialogId);
            }
            return name;
        }
    }

    /** an immutable snapshot handed to the UI */
    public static class Index {
        public final ArrayList<FileEntry> entries = new ArrayList<>();
        public final LongSparseArray<Folder> foldersByDialog = new LongSparseArray<>();
        public final ArrayList<Folder> folders = new ArrayList<>();
        public final long[] categorySize = new long[FileEntry.CATEGORY_COUNT];
        public final long[] categoryDownloadedSize = new long[FileEntry.CATEGORY_COUNT];
        public final int[] categoryCount = new int[FileEntry.CATEGORY_COUNT];
        public final int[] categoryDownloadedCount = new int[FileEntry.CATEGORY_COUNT];
        public long totalSize;
        public long downloadedSize;
        public int downloadedCount;
        public long builtAt;

        public int size() {
            return entries.size();
        }
    }

    public interface Listener {
        /** 0..1, called on the UI thread while the index is being built */
        void onFileIndexProgress(float progress);

        /** called on the UI thread when a fresh index is available */
        void onFileIndexUpdated(Index index);
    }

    // ------------------------------------------------------------------ state

    private final int currentAccount;
    private final ArrayList<Listener> listeners = new ArrayList<>();
    private Index index;
    private boolean loading;
    /** lazily created worker thread used for the (potentially long) disk walk */
    private DispatchQueue queue;
    /** absolute path of {@link FileLoader#MEDIA_DIR_CACHE}, filled in by the disk walk */
    private volatile String cacheRootPath;

    private FileIndexer(int account) {
        this.currentAccount = account;
    }

    private synchronized DispatchQueue getQueue() {
        if (queue == null) {
            queue = new DispatchQueue("ayuFileExplorer");
        }
        return queue;
    }

    public Index getIndex() {
        return index;
    }

    public boolean isLoading() {
        return loading;
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyProgress(float progress) {
        AndroidUtilities.runOnUIThread(() -> {
            final ArrayList<Listener> copy = new ArrayList<>(listeners);
            for (int i = 0; i < copy.size(); i++) {
                copy.get(i).onFileIndexProgress(progress);
            }
        });
    }

    private void notifyUpdated(Index result) {
        AndroidUtilities.runOnUIThread(() -> {
            index = result;
            loading = false;
            final ArrayList<Listener> copy = new ArrayList<>(listeners);
            for (int i = 0; i < copy.size(); i++) {
                copy.get(i).onFileIndexUpdated(result);
            }
        });
    }

    /**
     * Builds (or returns the cached) index. Must be called on the UI thread.
     *
     * @param force rebuild even when a fresh index is already in memory
     */
    public void load(boolean force) {
        FileExplorerConfig.load();
        if (loading) {
            return;
        }
        if (!force && index != null && System.currentTimeMillis() - index.builtAt < CACHE_TTL) {
            final Index cached = index;
            AndroidUtilities.runOnUIThread(() -> {
                final ArrayList<Listener> copy = new ArrayList<>(listeners);
                for (int i = 0; i < copy.size(); i++) {
                    copy.get(i).onFileIndexUpdated(cached);
                }
            });
            return;
        }
        loading = true;
        notifyProgress(0f);
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
            final ArrayList<FileEntry> entries = new ArrayList<>();
            try {
                readMediaTable(entries);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            getQueue().postRunnable(() -> {
                final HashMap<String, Long> filesOnDisk = new HashMap<>();
                try {
                    if (FileExplorerConfig.includeOrphanFiles) {
                        collectFilesOnDisk(filesOnDisk);
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                FileLoader.getInstance(currentAccount).getFileDatabase().getQueue().postRunnable(() -> {
                    Index result;
                    try {
                        result = resolveAndBuild(entries, filesOnDisk);
                    } catch (Throwable e) {
                        FileLog.e(e);
                        result = new Index();
                        result.builtAt = System.currentTimeMillis();
                    }
                    notifyUpdated(result);
                });
            });
        });
    }

    /**
     * Cheap refresh: only re-checks which of the already indexed files are present on disk.
     * Used after a delete so the list does not have to be rebuilt from the database.
     */
    public void refreshDownloadedState() {
        final Index current = index;
        if (current == null) {
            return;
        }
        getQueue().postRunnable(() -> {
            for (int i = 0; i < current.entries.size(); i++) {
                final FileEntry entry = current.entries.get(i);
                if (TextUtils.isEmpty(entry.path)) {
                    continue;
                }
                final File file = new File(entry.path);
                final long length = file.exists() ? file.length() : 0;
                entry.downloaded = length > 0;
                entry.onDiskSize = length;
            }
            recount(current);
            AndroidUtilities.runOnUIThread(() -> {
                final ArrayList<Listener> copy = new ArrayList<>(listeners);
                for (int i = 0; i < copy.size(); i++) {
                    copy.get(i).onFileIndexUpdated(current);
                }
            });
        });
    }

    /** removes the given entries from the cached index without rebuilding it */
    public void removeEntries(ArrayList<FileEntry> removed) {
        final Index current = index;
        if (current == null || removed == null || removed.isEmpty()) {
            return;
        }
        final HashSet<FileEntry> set = new HashSet<>(removed);
        current.entries.removeAll(set);
        for (int i = 0; i < current.folders.size(); i++) {
            current.folders.get(i).entries.removeAll(set);
        }
        for (int i = current.folders.size() - 1; i >= 0; i--) {
            if (current.folders.get(i).entries.isEmpty()) {
                current.foldersByDialog.remove(current.folders.get(i).dialogId);
                current.folders.remove(i);
            }
        }
        recount(current);
    }

    /** drops the cached index so the next {@link #load(boolean)} rebuilds it */
    public void invalidate() {
        index = null;
    }

    // ------------------------------------------------------------------ phase 1: database

    private void readMediaTable(ArrayList<FileEntry> out) {
        final SQLiteDatabase database = MessagesStorage.getInstance(currentAccount).getDatabase();
        if (database == null) {
            return;
        }
        final long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
        int total = 0;
        SQLiteCursor cursor = null;
        try {
            cursor = database.queryFinalized("SELECT COUNT(mid) FROM media_v4 WHERE mid > 0");
            if (cursor.next()) {
                total = cursor.intValue(0);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
                cursor = null;
            }
        }
        if (total <= 0) {
            total = 1;
        }

        final HashSet<String> seen = new HashSet<>();
        int processed = 0;
        try {
            cursor = database.queryFinalized(String.format(Locale.US,
                    "SELECT uid, mid, date, data FROM media_v4 WHERE mid > 0 ORDER BY date DESC LIMIT %d", MAX_MESSAGE_ENTRIES));
            while (cursor.next()) {
                processed++;
                if ((processed & 255) == 0) {
                    notifyProgress(Math.min(0.75f, 0.75f * processed / (float) total));
                }
                final long uid = cursor.longValue(0);
                final int mid = cursor.intValue(1);
                final int date = cursor.intValue(2);
                if (!seen.add(uid + "_" + mid)) {
                    continue;
                }
                NativeByteBuffer data = null;
                try {
                    data = cursor.byteBufferValue(3);
                    if (data == null) {
                        continue;
                    }
                    final TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    if (message == null) {
                        continue;
                    }
                    message.readAttachPath(data, selfId);
                    message.id = mid;
                    message.dialog_id = uid;
                    final FileEntry entry = fromMessage(message, uid, mid, date);
                    if (entry != null) {
                        out.add(entry);
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                } finally {
                    if (data != null) {
                        data.reuse();
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                cursor.dispose();
            }
        }
        notifyProgress(0.75f);
    }

    private FileEntry fromMessage(TLRPC.Message message, long dialogId, int messageId, int date) {
        final TLRPC.MessageMedia media = MessageObject.getMedia(message);
        if (media == null) {
            return null;
        }
        final TLRPC.Document document = MessageObject.getDocument(message);
        TLRPC.Photo photo = null;
        if (document == null) {
            if (media instanceof TLRPC.TL_messageMediaPhoto) {
                photo = media.photo;
            } else if (media instanceof TLRPC.TL_messageMediaWebPage && media.webpage != null) {
                photo = media.webpage.photo;
            }
            if (photo == null) {
                return null;
            }
        }

        final FileEntry entry = new FileEntry();
        entry.dialogId = dialogId;
        entry.messageId = messageId;
        entry.date = date != 0 ? date : message.date;

        if (document != null) {
            entry.document = document;
            entry.size = document.size;
            entry.mimeType = document.mime_type;
            String name = FileLoader.getDocumentFileName(document);
            if (TextUtils.isEmpty(name)) {
                name = FileLoader.getAttachFileName(document);
            }
            entry.name = name == null ? "" : name;
            entry.category = FileEntry.categoryOf(document);
            entry.thumbSize = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 40);
        } else {
            entry.photo = photo;
            entry.photoSize = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, AndroidUtilities.getPhotoSize(true), false, null, true);
            if (entry.photoSize == null) {
                return null;
            }
            entry.thumbSize = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, 40);
            entry.size = entry.photoSize.size;
            entry.mimeType = "image/jpeg";
            entry.category = FileEntry.CATEGORY_PHOTOS;
            entry.name = FileLoader.getAttachFileName(entry.photoSize);
        }
        if (TextUtils.isEmpty(entry.name)) {
            entry.name = String.valueOf(messageId);
        }
        entry.lowerName = entry.name.toLowerCase(Locale.US);
        entry.extension = FileEntry.extensionOf(entry.name);
        return entry;
    }

    // ------------------------------------------------------------------ phase 2: disk

    private static final int[] SCANNED_DIRS = new int[]{
            FileLoader.MEDIA_DIR_IMAGE,
            FileLoader.MEDIA_DIR_AUDIO,
            FileLoader.MEDIA_DIR_VIDEO,
            FileLoader.MEDIA_DIR_DOCUMENT,
            FileLoader.MEDIA_DIR_FILES,
            FileLoader.MEDIA_DIR_STORIES,
            FileLoader.MEDIA_DIR_IMAGE_PUBLIC,
            FileLoader.MEDIA_DIR_VIDEO_PUBLIC,
            FileLoader.MEDIA_DIR_CACHE
    };

    private void collectFilesOnDisk(HashMap<String, Long> out) {
        final HashSet<String> roots = new HashSet<>();
        try {
            final File cacheDir = FileLoader.checkDirectory(FileLoader.MEDIA_DIR_CACHE);
            cacheRootPath = cacheDir == null ? null : cacheDir.getAbsolutePath();
        } catch (Throwable e) {
            cacheRootPath = null;
        }
        for (int i = 0; i < SCANNED_DIRS.length; i++) {
            File dir;
            try {
                dir = FileLoader.checkDirectory(SCANNED_DIRS[i]);
            } catch (Throwable e) {
                dir = null;
            }
            if (dir == null || !dir.exists()) {
                continue;
            }
            final String path = dir.getAbsolutePath();
            if (!roots.add(path)) {
                continue;
            }
            walk(dir, out, 0);
            if (out.size() >= MAX_DISK_FILES) {
                break;
            }
        }
        notifyProgress(0.9f);
    }

    private void walk(File dir, HashMap<String, Long> out, int depth) {
        if (depth > 6 || out.size() >= MAX_DISK_FILES) {
            return;
        }
        final File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (int i = 0; i < files.length; i++) {
            final File file = files[i];
            if (file == null) {
                continue;
            }
            if (file.isDirectory()) {
                walk(file, out, depth + 1);
                if (out.size() >= MAX_DISK_FILES) {
                    return;
                }
                continue;
            }
            final String name = file.getName();
            if (".nomedia".equals(name) || name.endsWith(".temp") || name.endsWith(".preload")) {
                continue;
            }
            final long length = file.length();
            if (length <= 0) {
                continue;
            }
            out.put(file.getAbsolutePath(), length);
            if (out.size() >= MAX_DISK_FILES) {
                return;
            }
        }
    }

    // ------------------------------------------------------------------ phase 3: merge

    private Index resolveAndBuild(ArrayList<FileEntry> entries, HashMap<String, Long> filesOnDisk) {
        final FileLoader fileLoader = FileLoader.getInstance(currentAccount);
        final FilePathDatabase pathDatabase = fileLoader.getFileDatabase();
        try {
            pathDatabase.ensureDatabaseCreated();
        } catch (Throwable e) {
            FileLog.e(e);
        }

        for (int i = 0; i < entries.size(); i++) {
            final FileEntry entry = entries.get(i);
            File file = null;
            try {
                if (entry.document != null) {
                    file = fileLoader.getPathToAttach(entry.document, null, null, false, true);
                } else if (entry.photoSize != null) {
                    file = fileLoader.getPathToAttach(entry.photoSize, null, null, false, true);
                }
            } catch (Throwable e) {
                file = null;
            }
            if (file == null || TextUtils.isEmpty(file.getPath())) {
                continue;
            }
            entry.path = file.getAbsolutePath();
            Long onDisk = filesOnDisk.remove(entry.path);
            if (onDisk == null) {
                try {
                    if (file.exists()) {
                        onDisk = file.length();
                    }
                } catch (Throwable ignore) {
                }
            }
            if (onDisk != null && onDisk > 0) {
                entry.downloaded = true;
                entry.onDiskSize = onDisk;
                if (entry.size <= 0) {
                    entry.size = onDisk;
                }
            }
            if ((i & 511) == 0) {
                notifyProgress(0.9f + 0.05f * (i / (float) Math.max(1, entries.size())));
            }
        }

        if (FileExplorerConfig.includeOrphanFiles && !filesOnDisk.isEmpty()) {
            final FilePathDatabase.FileMeta meta = new FilePathDatabase.FileMeta();
            final String cacheRoot = cacheRootPath;
            for (Map.Entry<String, Long> pair : filesOnDisk.entrySet()) {
                final File file = new File(pair.getKey());
                final FileEntry entry = new FileEntry();
                entry.path = pair.getKey();
                entry.name = file.getName();
                entry.lowerName = entry.name.toLowerCase(Locale.US);
                entry.extension = FileEntry.extensionOf(entry.name);
                entry.size = pair.getValue();
                entry.onDiskSize = pair.getValue();
                entry.downloaded = true;
                entry.category = FileEntry.categoryOfFileName(entry.name);
                entry.date = (int) (file.lastModified() / 1000L);
                try {
                    final FilePathDatabase.FileMeta found = pathDatabase.getFileDialogId(file, meta);
                    if (found != null) {
                        entry.dialogId = found.dialogId;
                        entry.messageId = found.messageId;
                    }
                } catch (Throwable ignore) {
                }
                if (entry.dialogId == 0 && cacheRoot != null && entry.path.startsWith(cacheRoot)
                        && entry.onDiskSize < MIN_UNCLAIMED_CACHE_FILE_SIZE) {
                    continue;
                }
                entries.add(entry);
            }
        }
        notifyProgress(0.97f);

        final Index result = new Index();
        result.entries.addAll(entries);
        Collections.sort(result.entries, (a, b) -> Integer.compare(b.date, a.date));
        for (int i = 0; i < result.entries.size(); i++) {
            final FileEntry entry = result.entries.get(i);
            Folder folder = result.foldersByDialog.get(entry.dialogId);
            if (folder == null) {
                folder = new Folder(entry.dialogId);
                result.foldersByDialog.put(entry.dialogId, folder);
                result.folders.add(folder);
            }
            folder.entries.add(entry);
        }
        recount(result);
        Collections.sort(result.folders, (a, b) -> Long.compare(b.totalSize, a.totalSize));
        result.builtAt = System.currentTimeMillis();
        return result;
    }

    private static void recount(Index result) {
        result.totalSize = 0;
        result.downloadedSize = 0;
        result.downloadedCount = 0;
        for (int i = 0; i < FileEntry.CATEGORY_COUNT; i++) {
            result.categorySize[i] = 0;
            result.categoryDownloadedSize[i] = 0;
            result.categoryCount[i] = 0;
            result.categoryDownloadedCount[i] = 0;
        }
        for (int i = 0; i < result.folders.size(); i++) {
            final Folder folder = result.folders.get(i);
            folder.totalSize = 0;
            folder.downloadedSize = 0;
            folder.downloadedCount = 0;
        }
        for (int i = 0; i < result.entries.size(); i++) {
            final FileEntry entry = result.entries.get(i);
            final int category = entry.category < 0 || entry.category >= FileEntry.CATEGORY_COUNT ? FileEntry.CATEGORY_OTHER : entry.category;
            result.totalSize += entry.size;
            result.categorySize[category] += entry.size;
            result.categoryCount[category]++;
            if (entry.downloaded) {
                result.downloadedSize += entry.onDiskSize;
                result.downloadedCount++;
                result.categoryDownloadedSize[category] += entry.onDiskSize;
                result.categoryDownloadedCount[category]++;
            }
            final Folder folder = result.foldersByDialog.get(entry.dialogId);
            if (folder != null) {
                folder.totalSize += entry.size;
                if (entry.downloaded) {
                    folder.downloadedSize += entry.onDiskSize;
                    folder.downloadedCount++;
                }
            }
        }
    }

    // ------------------------------------------------------------------ queries used by the UI

    /** filters + sorts a list of entries according to the current preferences and the search query */
    public static ArrayList<FileEntry> filter(ArrayList<FileEntry> source, String query, int category, String extension, int sortMode, boolean onlyDownloaded) {
        final ArrayList<FileEntry> out = new ArrayList<>();
        if (source == null) {
            return out;
        }
        final String lowerQuery = TextUtils.isEmpty(query) ? null : query.toLowerCase(Locale.US).trim();
        for (int i = 0; i < source.size(); i++) {
            final FileEntry entry = source.get(i);
            if (onlyDownloaded && !entry.downloaded) {
                continue;
            }
            if (category >= 0 && entry.category != category) {
                continue;
            }
            if (extension != null && !extension.equals(entry.extension)) {
                continue;
            }
            if (lowerQuery != null && !lowerQuery.isEmpty() && (entry.lowerName == null || !entry.lowerName.contains(lowerQuery))) {
                continue;
            }
            out.add(entry);
        }
        sort(out, sortMode);
        return out;
    }

    public static void sort(ArrayList<FileEntry> list, int sortMode) {
        switch (sortMode) {
            case FileExplorerConfig.SORT_DATE_ASC:
                Collections.sort(list, (a, b) -> Integer.compare(a.date, b.date));
                break;
            case FileExplorerConfig.SORT_SIZE_DESC:
                Collections.sort(list, (a, b) -> Long.compare(b.sizeForSorting(), a.sizeForSorting()));
                break;
            case FileExplorerConfig.SORT_SIZE_ASC:
                Collections.sort(list, (a, b) -> Long.compare(a.sizeForSorting(), b.sizeForSorting()));
                break;
            case FileExplorerConfig.SORT_NAME_ASC:
                Collections.sort(list, (a, b) -> nullSafe(a.lowerName).compareTo(nullSafe(b.lowerName)));
                break;
            case FileExplorerConfig.SORT_NAME_DESC:
                Collections.sort(list, (a, b) -> nullSafe(b.lowerName).compareTo(nullSafe(a.lowerName)));
                break;
            case FileExplorerConfig.SORT_DATE_DESC:
            default:
                Collections.sort(list, (a, b) -> Integer.compare(b.date, a.date));
                break;
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /** all extensions found inside the "Documents" category, largest group first */
    public static ArrayList<String> documentExtensions(Index index, boolean onlyDownloaded) {
        final ArrayList<String> out = new ArrayList<>();
        if (index == null) {
            return out;
        }
        final HashMap<String, long[]> map = new HashMap<>();
        for (int i = 0; i < index.entries.size(); i++) {
            final FileEntry entry = index.entries.get(i);
            if (entry.category != FileEntry.CATEGORY_DOCUMENTS) {
                continue;
            }
            if (onlyDownloaded && !entry.downloaded) {
                continue;
            }
            final String ext = TextUtils.isEmpty(entry.extension) ? "" : entry.extension;
            long[] value = map.get(ext);
            if (value == null) {
                value = new long[2];
                map.put(ext, value);
            }
            value[0]++;
            value[1] += entry.sizeForSorting();
        }
        out.addAll(map.keySet());
        Collections.sort(out, (a, b) -> {
            final long[] va = map.get(a);
            final long[] vb = map.get(b);
            final long sa = va == null ? 0 : va[1];
            final long sb = vb == null ? 0 : vb[1];
            return Long.compare(sb, sa);
        });
        return out;
    }

    /** {count, totalSize} of the "Documents" entries matching an extension */
    public static long[] extensionStats(Index index, String extension, boolean onlyDownloaded) {
        final long[] result = new long[2];
        if (index == null) {
            return result;
        }
        for (int i = 0; i < index.entries.size(); i++) {
            final FileEntry entry = index.entries.get(i);
            if (entry.category != FileEntry.CATEGORY_DOCUMENTS) {
                continue;
            }
            if (onlyDownloaded && !entry.downloaded) {
                continue;
            }
            if (!TextUtils.equals(extension, entry.extension)) {
                continue;
            }
            result[0]++;
            result[1] += entry.sizeForSorting();
        }
        return result;
    }

    /** kept so callers do not have to import Utilities directly */
    public static void runOnWorker(Runnable runnable) {
        Utilities.globalQueue.postRunnable(runnable);
    }
}
