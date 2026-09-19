package org.telegram.messenger.ayu.duplicates;

import android.text.TextUtils;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FilePathDatabase;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AyuGram Duplicate Cleaner: finds byte-identical local media files that were downloaded more than
 * once (for example the same video forwarded into several chats).
 * <p>
 * Pipeline, all off the main thread:
 * <ol>
 *     <li>walk every {@link FileLoader} media directory and collect candidate files;</li>
 *     <li>group by exact file size — a unique size can never be a duplicate;</li>
 *     <li>for large files, a cheap head+tail signature narrows the size collisions down;</li>
 *     <li>full streaming SHA-256 for what is left; equal digests = duplicates;</li>
 *     <li>resolve the owning dialog/message for every duplicate (FilePathDatabase first, then a
 *     bounded {@code media_v4} lookup on the storage queue).</li>
 * </ol>
 * Results are delivered on the main thread. {@link #cancel()} is honoured between files and between
 * hash chunks.
 */
public class DuplicateScanner {

    // ---------------- progress stages ----------------
    public static final int STAGE_ENUMERATING = 0;
    public static final int STAGE_HASHING = 1;
    public static final int STAGE_RESOLVING = 2;

    /** how many media_v4 rows we are willing to deserialize when reverse-resolving file names */
    private static final int MEDIA_TABLE_SCAN_LIMIT = 40000;
    /** how many files we bother to reverse-resolve at all */
    private static final int MAX_RESOLVE_FILES = 4000;
    /** progress callbacks are throttled to this interval */
    private static final long PROGRESS_INTERVAL_MS = 120;

    private static volatile DispatchQueue scanQueue;

    // ---------------- model ----------------

    /** one local file that belongs to a duplicate group */
    public static class DuplicateFile {
        public final File file;
        public final String path;
        public long size;
        public long lastModified;
        public String hash;

        /** resolved owner, 0 when unknown */
        public long dialogId;
        public int messageId;
        public int messageType;
        /** unix time in seconds of the owning message, 0 when unknown */
        public int messageDate;
        /** original document file name when it could be recovered */
        public String documentName;
        /** filled in by the UI once users/chats are in MessagesController */
        public String chatName;

        public DuplicateFile(File file) {
            this.file = file;
            this.path = file.getAbsolutePath();
            this.size = file.length();
            this.lastModified = file.lastModified();
        }

        public boolean hasOwner() {
            return dialogId != 0;
        }

        public boolean canOpenMessage() {
            return dialogId != 0 && messageId != 0;
        }

        public String getName() {
            if (!TextUtils.isEmpty(documentName)) {
                return documentName;
            }
            return file.getName();
        }
    }

    /** a set of byte-identical files */
    public static class DuplicateGroup {
        public String hash;
        public long size;
        public final ArrayList<DuplicateFile> files = new ArrayList<>();

        public int getCopies() {
            return files.size();
        }

        /** bytes freed by keeping exactly one copy */
        public long getReclaimableBytes() {
            return size * Math.max(0, files.size() - 1);
        }

        public DuplicateFile getOldest() {
            DuplicateFile best = null;
            for (int i = 0; i < files.size(); i++) {
                DuplicateFile f = files.get(i);
                if (best == null || f.lastModified < best.lastModified) {
                    best = f;
                }
            }
            return best;
        }

        public DuplicateFile getNewest() {
            DuplicateFile best = null;
            for (int i = 0; i < files.size(); i++) {
                DuplicateFile f = files.get(i);
                if (best == null || f.lastModified > best.lastModified) {
                    best = f;
                }
            }
            return best;
        }

        /** best display name available for the group */
        public String getDisplayName() {
            for (int i = 0; i < files.size(); i++) {
                String name = files.get(i).documentName;
                if (!TextUtils.isEmpty(name)) {
                    return name;
                }
            }
            return files.isEmpty() ? "" : files.get(0).file.getName();
        }

        /** a file of this group that still has a resolvable message, or null */
        public DuplicateFile findOpenable() {
            for (int i = 0; i < files.size(); i++) {
                if (files.get(i).canOpenMessage()) {
                    return files.get(i);
                }
            }
            return null;
        }
    }

    public static class ScanResult {
        public final ArrayList<DuplicateGroup> groups = new ArrayList<>();
        public int totalFilesScanned;
        public long totalBytesScanned;
        /** redundant copies = sum over groups of (copies - 1) */
        public int duplicateFiles;
        public long reclaimableBytes;
        public long timestamp;
        /** users/chats loaded from the local database so the UI can name the dialogs */
        public final ArrayList<TLRPC.User> users = new ArrayList<>();
        public final ArrayList<TLRPC.Chat> chats = new ArrayList<>();
    }

    public interface Callback {
        /**
         * @param stage      one of STAGE_*
         * @param filesDone  files finished in this stage
         * @param filesTotal files in this stage, 0 when unknown
         * @param bytesDone  bytes read in this stage
         * @param bytesTotal bytes to read in this stage, 0 when unknown
         */
        void onProgress(int stage, int filesDone, int filesTotal, long bytesDone, long bytesTotal);

        void onFinished(ScanResult result);

        void onCancelled();
    }

    // ---------------- state ----------------

    private final int currentAccount;
    private volatile boolean cancelled;
    private volatile boolean running;
    private Callback callback;

    private long lastProgressTime;

    public DuplicateScanner(int currentAccount) {
        this.currentAccount = currentAccount;
    }

    private static DispatchQueue getQueue() {
        if (scanQueue == null) {
            synchronized (DuplicateScanner.class) {
                if (scanQueue == null) {
                    scanQueue = new DispatchQueue("ayuDuplicatesQueue");
                }
            }
        }
        return scanQueue;
    }

    public boolean isRunning() {
        return running;
    }

    public void cancel() {
        cancelled = true;
    }

    public void start(Callback callback) {
        if (running) {
            return;
        }
        this.callback = callback;
        this.cancelled = false;
        this.running = true;
        DuplicatesConfig.load();
        getQueue().postRunnable(this::runScan);
    }

    // ---------------- stage 1: enumerate ----------------

    private void runScan() {
        try {
            final ArrayList<DuplicateFile> candidates = new ArrayList<>();
            final HashSet<String> visitedDirs = new HashSet<>();
            long[] scannedBytes = new long[]{0};

            for (int type : collectDirectoryTypes()) {
                if (cancelled) {
                    finishCancelled();
                    return;
                }
                File dir = FileLoader.checkDirectory(type);
                walk(dir, candidates, visitedDirs, scannedBytes, 0);
            }
            if (cancelled) {
                finishCancelled();
                return;
            }

            final int totalFiles = candidates.size();
            final long totalBytes = scannedBytes[0];
            reportProgress(STAGE_ENUMERATING, totalFiles, totalFiles, totalBytes, totalBytes, true);

            // ---------------- stage 2: size buckets ----------------
            HashMap<Long, ArrayList<DuplicateFile>> bySize = new HashMap<>();
            for (int i = 0; i < candidates.size(); i++) {
                DuplicateFile f = candidates.get(i);
                ArrayList<DuplicateFile> list = bySize.get(f.size);
                if (list == null) {
                    list = new ArrayList<>();
                    bySize.put(f.size, list);
                }
                list.add(f);
            }

            ArrayList<ArrayList<DuplicateFile>> collisions = new ArrayList<>();
            long bytesToHash = 0;
            int filesToHash = 0;
            for (Map.Entry<Long, ArrayList<DuplicateFile>> entry : bySize.entrySet()) {
                ArrayList<DuplicateFile> list = entry.getValue();
                if (list.size() > 1) {
                    collisions.add(list);
                    filesToHash += list.size();
                    bytesToHash += entry.getKey() * list.size();
                }
            }
            bySize.clear();

            // ---------------- stage 3: hashing ----------------
            final ArrayList<DuplicateGroup> groups = new ArrayList<>();
            final byte[] buffer = DuplicateHasher.allocateBuffer();
            final long[] hashedBytes = new long[]{0};
            final int[] hashedFiles = new int[]{0};
            final int totalToHash = filesToHash;
            final long totalHashBytes = bytesToHash;

            DuplicateHasher.Listener listener = new DuplicateHasher.Listener() {
                @Override
                public void onBytesHashed(long bytes) {
                    hashedBytes[0] += bytes;
                    reportProgress(STAGE_HASHING, hashedFiles[0], totalToHash, hashedBytes[0], totalHashBytes, false);
                }

                @Override
                public boolean isCancelled() {
                    return cancelled;
                }
            };

            for (int c = 0; c < collisions.size(); c++) {
                if (cancelled) {
                    finishCancelled();
                    return;
                }
                ArrayList<DuplicateFile> sameSize = collisions.get(c);

                // cheap pre-pass for big files so identical sizes do not force a full read
                ArrayList<ArrayList<DuplicateFile>> narrowed = new ArrayList<>();
                if (sameSize.get(0).size > DuplicateHasher.QUICK_SIGNATURE_THRESHOLD) {
                    HashMap<String, ArrayList<DuplicateFile>> byQuick = new HashMap<>();
                    for (int i = 0; i < sameSize.size(); i++) {
                        if (cancelled) {
                            finishCancelled();
                            return;
                        }
                        DuplicateFile f = sameSize.get(i);
                        String quick = DuplicateHasher.quickSignature(f.file, buffer, listener);
                        if (quick == null) {
                            continue;
                        }
                        ArrayList<DuplicateFile> list = byQuick.get(quick);
                        if (list == null) {
                            list = new ArrayList<>();
                            byQuick.put(quick, list);
                        }
                        list.add(f);
                    }
                    for (ArrayList<DuplicateFile> list : byQuick.values()) {
                        if (list.size() > 1) {
                            narrowed.add(list);
                        } else {
                            hashedFiles[0]++;
                        }
                    }
                } else {
                    narrowed.add(sameSize);
                }

                for (int n = 0; n < narrowed.size(); n++) {
                    ArrayList<DuplicateFile> list = narrowed.get(n);
                    HashMap<String, ArrayList<DuplicateFile>> byHash = new HashMap<>();
                    for (int i = 0; i < list.size(); i++) {
                        if (cancelled) {
                            finishCancelled();
                            return;
                        }
                        DuplicateFile f = list.get(i);
                        String hash = DuplicateHasher.hashFile(f.file, buffer, listener);
                        hashedFiles[0]++;
                        if (hash == null) {
                            continue;
                        }
                        f.hash = hash;
                        ArrayList<DuplicateFile> bucket = byHash.get(hash);
                        if (bucket == null) {
                            bucket = new ArrayList<>();
                            byHash.put(hash, bucket);
                        }
                        bucket.add(f);
                    }
                    for (Map.Entry<String, ArrayList<DuplicateFile>> entry : byHash.entrySet()) {
                        if (entry.getValue().size() > 1) {
                            DuplicateGroup group = new DuplicateGroup();
                            group.hash = entry.getKey();
                            group.size = entry.getValue().get(0).size;
                            group.files.addAll(entry.getValue());
                            Collections.sort(group.files, (a, b) -> Long.compare(a.lastModified, b.lastModified));
                            groups.add(group);
                        }
                    }
                }
            }

            if (cancelled) {
                finishCancelled();
                return;
            }

            Collections.sort(groups, (a, b) -> Long.compare(b.getReclaimableBytes(), a.getReclaimableBytes()));

            final ScanResult result = new ScanResult();
            result.groups.addAll(groups);
            result.totalFilesScanned = totalFiles;
            result.totalBytesScanned = totalBytes;
            for (int i = 0; i < groups.size(); i++) {
                result.duplicateFiles += groups.get(i).getCopies() - 1;
                result.reclaimableBytes += groups.get(i).getReclaimableBytes();
            }
            result.timestamp = System.currentTimeMillis();

            reportProgress(STAGE_RESOLVING, 0, 0, 0, 0, true);
            resolveOwners(result);
        } catch (Throwable e) {
            FileLog.e(e);
            finishCancelled();
        }
    }

    private static int[] collectDirectoryTypes() {
        return new int[]{
                FileLoader.MEDIA_DIR_IMAGE,
                FileLoader.MEDIA_DIR_IMAGE_PUBLIC,
                FileLoader.MEDIA_DIR_VIDEO,
                FileLoader.MEDIA_DIR_VIDEO_PUBLIC,
                FileLoader.MEDIA_DIR_DOCUMENT,
                FileLoader.MEDIA_DIR_FILES,
                FileLoader.MEDIA_DIR_AUDIO,
                FileLoader.MEDIA_DIR_STORIES,
                FileLoader.MEDIA_DIR_CACHE
        };
    }

    private void walk(File dir, ArrayList<DuplicateFile> out, HashSet<String> visitedDirs, long[] scannedBytes, int depth) {
        if (dir == null || cancelled || depth > 8) {
            return;
        }
        String key;
        try {
            key = dir.getCanonicalPath();
        } catch (Throwable e) {
            key = dir.getAbsolutePath();
        }
        if (!visitedDirs.add(key)) {
            return;
        }
        if (!DuplicatesConfig.includeStickerCache && "acache".equals(dir.getName())) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (int i = 0; i < files.length; i++) {
            if (cancelled) {
                return;
            }
            File entry = files[i];
            if (entry.isDirectory()) {
                walk(entry, out, visitedDirs, scannedBytes, depth + 1);
                continue;
            }
            if (!isCandidate(entry)) {
                continue;
            }
            DuplicateFile f = new DuplicateFile(entry);
            out.add(f);
            scannedBytes[0] += f.size;
            if (out.size() % 64 == 0) {
                reportProgress(STAGE_ENUMERATING, out.size(), 0, scannedBytes[0], 0, false);
            }
        }
    }

    private static boolean isCandidate(File file) {
        final String name = file.getName();
        if (name.startsWith(".") || name.startsWith("q_")) {
            // hidden files and the small "q_" thumbnails that belong to another file
            return false;
        }
        final String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".temp") || lower.endsWith(".part") || lower.endsWith(".enc")
                || lower.endsWith(".enc.key") || lower.endsWith(".key") || lower.endsWith(".preload")
                || lower.endsWith(".parts") || lower.endsWith(".log") || lower.endsWith(".journal")) {
            return false;
        }
        long size = file.length();
        if (size < DuplicatesConfig.minFileSize) {
            return false;
        }
        return file.canRead();
    }

    // ---------------- stage 4: owner resolution ----------------

    private void resolveOwners(ScanResult result) {
        final ArrayList<DuplicateFile> files = new ArrayList<>();
        for (int i = 0; i < result.groups.size(); i++) {
            files.addAll(result.groups.get(i).files);
        }
        if (files.isEmpty()) {
            finishSuccess(result);
            return;
        }
        final List<DuplicateFile> toResolve = files.size() > MAX_RESOLVE_FILES
                ? files.subList(0, MAX_RESOLVE_FILES) : files;

        final FilePathDatabase pathDatabase = FileLoader.getInstance(currentAccount).getFileDatabase();
        pathDatabase.getQueue().postRunnable(() -> {
            try {
                pathDatabase.ensureDatabaseCreated();
                FilePathDatabase.FileMeta meta = new FilePathDatabase.FileMeta();
                for (int i = 0; i < toResolve.size(); i++) {
                    if (cancelled) {
                        break;
                    }
                    DuplicateFile f = toResolve.get(i);
                    FilePathDatabase.FileMeta resolved = pathDatabase.getFileDialogId(f.file, meta);
                    if (resolved != null) {
                        f.dialogId = resolved.dialogId;
                        f.messageId = resolved.messageId;
                        f.messageType = resolved.messageType;
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            resolveInStorage(result, toResolve);
        });
    }

    private void resolveInStorage(ScanResult result, List<DuplicateFile> toResolve) {
        final MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
        storage.getStorageQueue().postRunnable(() -> {
            try {
                reverseLookupUnresolved(storage, toResolve);
                loadMessageDates(storage, toResolve);
                loadDialogs(storage, toResolve, result);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            finishSuccess(result);
        });
    }

    /**
     * Files the FilePathDatabase does not know about are matched by reversing
     * {@code FileLoader.getAttachFileName}: {@code <dc>_<id>.<ext>}, {@code <id>.<ext>} and
     * {@code <volume>_<local>.jpg}. Both candidate keys are registered for an ambiguous name and the
     * media table decides which one is real.
     */
    private void reverseLookupUnresolved(MessagesStorage storage, List<DuplicateFile> toResolve) {
        HashMap<String, ArrayList<DuplicateFile>> wanted = new HashMap<>();
        for (int i = 0; i < toResolve.size(); i++) {
            DuplicateFile f = toResolve.get(i);
            if (f.hasOwner()) {
                continue;
            }
            for (String key : fileNameKeys(f.file.getName())) {
                ArrayList<DuplicateFile> list = wanted.get(key);
                if (list == null) {
                    list = new ArrayList<>();
                    wanted.put(key, list);
                }
                list.add(f);
            }
        }
        if (wanted.isEmpty()) {
            return;
        }
        SQLiteCursor cursor = null;
        try {
            cursor = storage.getDatabase().queryFinalized(String.format(Locale.US,
                    "SELECT uid, mid, date, data FROM media_v4 ORDER BY date DESC LIMIT %d", MEDIA_TABLE_SCAN_LIMIT));
            while (cursor.next()) {
                if (cancelled || wanted.isEmpty()) {
                    break;
                }
                NativeByteBuffer data = cursor.byteBufferValue(3);
                if (data == null) {
                    continue;
                }
                TLRPC.Message message;
                try {
                    message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                } catch (Throwable e) {
                    message = null;
                }
                data.reuse();
                if (message == null) {
                    continue;
                }
                long uid = cursor.longValue(0);
                int mid = cursor.intValue(1);
                int date = cursor.intValue(2);

                ArrayList<String> keys = new ArrayList<>();
                ArrayList<String> names = new ArrayList<>();
                collectMessageKeys(message, keys, names);
                for (int k = 0; k < keys.size(); k++) {
                    ArrayList<DuplicateFile> hits = wanted.remove(keys.get(k));
                    if (hits == null) {
                        continue;
                    }
                    String name = k < names.size() ? names.get(k) : null;
                    for (int h = 0; h < hits.size(); h++) {
                        DuplicateFile f = hits.get(h);
                        if (f.hasOwner()) {
                            continue;
                        }
                        f.dialogId = uid;
                        f.messageId = mid;
                        f.messageDate = date;
                        if (TextUtils.isEmpty(f.documentName) && !TextUtils.isEmpty(name)) {
                            f.documentName = name;
                        }
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) {
                try {
                    cursor.dispose();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /** the keys a local file name could match */
    static ArrayList<String> fileNameKeys(String fileName) {
        ArrayList<String> keys = new ArrayList<>(2);
        if (TextUtils.isEmpty(fileName)) {
            return keys;
        }
        String base = fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        int underscore = base.indexOf('_');
        if (underscore <= 0) {
            long id = parseLong(base);
            if (id != 0) {
                keys.add("d" + id);
            }
            return keys;
        }
        String left = base.substring(0, underscore);
        String right = base.substring(underscore + 1);
        long leftValue = parseLong(left);
        long rightValue = parseLong(right);
        if (rightValue != 0) {
            // <dc>_<documentId>
            keys.add("d" + rightValue);
        }
        if (leftValue != 0 && rightValue != 0) {
            // <volumeId>_<localId>
            keys.add("p" + leftValue + "_" + rightValue);
        }
        return keys;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Throwable e) {
            return 0;
        }
    }

    /** every attachment key a stored message can produce, with a matching display name */
    private static void collectMessageKeys(TLRPC.Message message, ArrayList<String> keys, ArrayList<String> names) {
        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        if (media == null) {
            return;
        }
        addDocumentKey(media.document, keys, names);
        addPhotoKeys(media.photo, keys, names);
        if (media.webpage != null) {
            addDocumentKey(media.webpage.document, keys, names);
            addPhotoKeys(media.webpage.photo, keys, names);
        }
    }

    private static void addDocumentKey(TLRPC.Document document, ArrayList<String> keys, ArrayList<String> names) {
        if (document == null || document.id == 0) {
            return;
        }
        keys.add("d" + document.id);
        String name = null;
        try {
            name = FileLoader.getDocumentFileName(document);
        } catch (Throwable ignore) {
        }
        names.add(TextUtils.isEmpty(name) ? null : name);
    }

    private static void addPhotoKeys(TLRPC.Photo photo, ArrayList<String> keys, ArrayList<String> names) {
        if (photo == null || photo.sizes == null) {
            return;
        }
        for (int i = 0; i < photo.sizes.size(); i++) {
            TLRPC.PhotoSize size = photo.sizes.get(i);
            if (size == null || size.location == null || size.location instanceof TLRPC.TL_fileLocationUnavailable) {
                continue;
            }
            keys.add("p" + size.location.volume_id + "_" + size.location.local_id);
            names.add(null);
        }
    }

    /** message dates for files resolved through the FilePathDatabase */
    private void loadMessageDates(MessagesStorage storage, List<DuplicateFile> toResolve) {
        HashMap<Long, ArrayList<DuplicateFile>> byDialog = new HashMap<>();
        for (int i = 0; i < toResolve.size(); i++) {
            DuplicateFile f = toResolve.get(i);
            if (f.dialogId == 0 || f.messageId == 0 || f.messageDate != 0) {
                continue;
            }
            ArrayList<DuplicateFile> list = byDialog.get(f.dialogId);
            if (list == null) {
                list = new ArrayList<>();
                byDialog.put(f.dialogId, list);
            }
            list.add(f);
        }
        for (Map.Entry<Long, ArrayList<DuplicateFile>> entry : byDialog.entrySet()) {
            if (cancelled) {
                return;
            }
            ArrayList<DuplicateFile> list = entry.getValue();
            StringBuilder ids = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (ids.length() > 0) {
                    ids.append(",");
                }
                ids.append(list.get(i).messageId);
            }
            SQLiteCursor cursor = null;
            try {
                cursor = storage.getDatabase().queryFinalized(String.format(Locale.US,
                        "SELECT mid, date FROM messages_v2 WHERE uid = %d AND mid IN (%s)", entry.getKey(), ids));
                HashMap<Integer, Integer> dates = new HashMap<>();
                while (cursor.next()) {
                    dates.put(cursor.intValue(0), cursor.intValue(1));
                }
                for (int i = 0; i < list.size(); i++) {
                    Integer date = dates.get(list.get(i).messageId);
                    if (date != null) {
                        list.get(i).messageDate = date;
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    try {
                        cursor.dispose();
                    } catch (Throwable ignore) {
                    }
                }
            }
        }
    }

    /** pull the users/chats the UI will need to render dialog titles */
    private void loadDialogs(MessagesStorage storage, List<DuplicateFile> toResolve, ScanResult result) {
        HashSet<Long> userIds = new HashSet<>();
        HashSet<Long> chatIds = new HashSet<>();
        for (int i = 0; i < toResolve.size(); i++) {
            long dialogId = toResolve.get(i).dialogId;
            if (dialogId > 0) {
                userIds.add(dialogId);
            } else if (dialogId < 0) {
                chatIds.add(-dialogId);
            }
        }
        if (!userIds.isEmpty()) {
            try {
                storage.getUsersInternal(new ArrayList<>(userIds), result.users);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        if (!chatIds.isEmpty()) {
            try {
                storage.getChatsInternal(TextUtils.join(",", chatIds), result.chats);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    // ---------------- callbacks ----------------

    private void reportProgress(int stage, int filesDone, int filesTotal, long bytesDone, long bytesTotal, boolean force) {
        final long now = System.currentTimeMillis();
        if (!force && now - lastProgressTime < PROGRESS_INTERVAL_MS) {
            return;
        }
        lastProgressTime = now;
        final Callback cb = callback;
        if (cb == null) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (!cancelled) {
                cb.onProgress(stage, filesDone, filesTotal, bytesDone, bytesTotal);
            }
        });
    }

    private void finishCancelled() {
        running = false;
        final Callback cb = callback;
        callback = null;
        if (cb != null) {
            AndroidUtilities.runOnUIThread(cb::onCancelled);
        }
    }

    private void finishSuccess(ScanResult result) {
        running = false;
        if (cancelled) {
            finishCancelled();
            return;
        }
        final Callback cb = callback;
        callback = null;
        DuplicatesConfig.saveLastScan(result.groups.size(), result.duplicateFiles,
                result.reclaimableBytes, result.totalFilesScanned, result.totalBytesScanned);
        if (cb != null) {
            AndroidUtilities.runOnUIThread(() -> cb.onFinished(result));
        }
    }
}
