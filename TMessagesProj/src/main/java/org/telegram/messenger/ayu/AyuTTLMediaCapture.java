package org.telegram.messenger.ayu;

import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Looper;

import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.ayu.entities.DeletedMessage;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicLong;

/** Separate local archive; never changes the live message or sends a read/open acknowledgement. */
public final class AyuTTLMediaCapture {
    private static final long MAX_BYTES = 25L * 1024 * 1024;
    private static final long MAX_VIDEO_BYTES = 100L * 1024 * 1024;
    private static final long MIN_FREE_BYTES = 128L * 1024 * 1024;
    private static final int MAX_PENDING = 32;
    private static final int MAX_DOWNLOADS = 4;
    private static final DispatchQueue queue = new DispatchQueue("ayuTTLMedia");
    private static final LinkedHashMap<String, Job> pending = new LinkedHashMap<>();
    // Slots survive a metadata timeout while FileLoader is still working. A slow download must
    // not free a slot and silently allow an unbounded number of background downloads.
    private static final HashSet<String> downloads = new HashSet<>();
    private static final AtomicLong generation = new AtomicLong();

    private AyuTTLMediaCapture() { }

    private static long owner(int account) {
        return account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT
                ? UserConfig.getInstance(account).getClientUserId() : 0;
    }

    static boolean eligible(int account, TLRPC.Message message) {
        if (!AyuConfig.saveSelfDestructingMedia || !AyuConfig.saveMedia || owner(account) == 0
                || message == null || message.id <= 0 || message.out || message.media == null
                || message.media.ttl_seconds <= 0 || !supportedMedia(message.media)) return false;
        long dialog = MessageObject.getDialogId(message);
        return !DialogObject.isEncryptedDialog(dialog) && AyuChatCapturePolicy.isEnabled(account, dialog, message)
                && AyuMessagesController.getInstance().isMediaSaveAllowed(account, dialog);
    }

    private static boolean supportedMedia(TLRPC.MessageMedia media) {
        if (media.photo != null && !(media.photo instanceof TLRPC.TL_photoEmpty)) return true;
        TLRPC.Document document = media.document;
        return document != null && document.key == null && document.size > 0 && document.size <= MAX_VIDEO_BYTES
                && (MessageObject.isVideoDocument(document) || MessageObject.isRoundVideoDocument(document));
    }

    private static TLRPC.PhotoSize fullSize(TLRPC.Photo photo) {
        TLRPC.PhotoSize best = null;
        for (TLRPC.PhotoSize size : photo.sizes) {
            if (!(size instanceof TLRPC.TL_photoSize) && !(size instanceof TLRPC.TL_photoSizeProgressive)) continue;
            if (size.location == null || size.size <= 0) continue;
            if (best == null || (long) size.w * size.h > (long) best.w * best.h
                    || ((long) size.w * size.h == (long) best.w * best.h && size.size > best.size)) best = size;
        }
        return best != null && best.size <= MAX_BYTES ? best : null;
    }

    private static String key(int account, long owner, long dialog, int message) {
        return account + ":" + owner + ":" + dialog + ":" + message;
    }

    private static final class Job {
        final int account;
        final long owner, dialog, generation;
        final TLRPC.Message message;
        final TLRPC.PhotoSize size;
        final TLRPC.Document document;
        final TLObject attachment;
        final int expectedBytes;
        final String key, fileName;
        volatile boolean claimed, downloading, cancelled, prepared;

        Job(int account, long owner, TLRPC.Message message, TLRPC.PhotoSize size, long generation) {
            this.account = account;
            this.owner = owner;
            this.message = message;
            this.size = size;
            this.document = size == null ? message.media.document : null;
            this.attachment = document != null ? document : size;
            this.expectedBytes = document != null ? (int) document.size : size.size;
            this.dialog = MessageObject.getDialogId(message);
            this.key = key(account, owner, dialog, message.id);
            this.fileName = FileLoader.getAttachFileName(attachment);
            this.generation = generation;
        }

        boolean valid() {
            return !cancelled && generation == AyuTTLMediaCapture.generation.get()
                    && owner == owner(account) && eligible(account, message);
        }
    }

    /** Called from message storage boundaries, not cells/binds. */
    public static void onIncomingSelfDestructPhoto(int account, TLRPC.Message message) {
        capture(account, message, false);
    }

    public static void onIncomingSelfDestructPhoto(int account, TLRPC.Message message, long expectedOwner) {
        capture(account, message, expectedOwner);
    }

    public static void captureBeforeMediaDeletion(int account, TLRPC.Message message, long expectedOwner) {
        capture(account, message, expectedOwner);
    }

    static void capture(int account, TLRPC.Message message, boolean beforeDelete) {
        capture(account, message, owner(account));
    }

    private static void capture(int account, TLRPC.Message message, long expectedOwner) {
        if (message == null || message.media == null || message.media.ttl_seconds <= 0
                || !supportedMedia(message.media) || !AyuConfig.saveSelfDestructingMedia || !AyuConfig.saveMedia) return;
        final long epoch = generation.get();
        Runnable work = () -> {
            try {
            if (expectedOwner == 0 || expectedOwner != owner(account) || epoch != generation.get()) return;
            AyuChatCapturePolicy.load();
            Job job = register(account, expectedOwner, message, epoch);
            if (job == null) return;
            claimAvailable(job, null);
            queue.postRunnable(() -> prepare(job));
            } catch (Throwable e) { FileLog.e(e); }
        };
        // The pre-wipe storage hook must open the file and snapshot its key before returning.
        // An accidental UI caller is always deferred.
        if (Looper.myLooper() == Looper.getMainLooper()) queue.postRunnable(work);
        else work.run();
    }

    private static Job register(int account, long owner, TLRPC.Message source, long epoch) {
        if (owner == 0 || owner != owner(account) || epoch != generation.get() || !eligible(account, source)) return null;
        String key = key(account, owner, MessageObject.getDialogId(source), source.id);
        synchronized (pending) {
            Job existing = pending.get(key);
            if (existing != null) return existing;
            if (pending.size() >= MAX_PENDING) return null;
            try {
                // All later serialization/config filtering acts on this private clone.
                SerializedData output = new SerializedData(source.getObjectSize());
                source.serializeToStream(output);
                byte[] bytes = output.toByteArray();
                output.cleanup();
                SerializedData input = new SerializedData(bytes);
                TLRPC.Message clone = TLRPC.Message.TLdeserialize(input, input.readInt32(true), true);
                input.cleanup();
                clone.dialog_id = MessageObject.getDialogId(source);
                TLRPC.PhotoSize size = clone.media.photo != null ? fullSize(clone.media.photo) : null;
                if (size == null && (clone.media.photo != null || !supportedMedia(clone.media))) return null;
                if (owner != owner(account) || epoch != generation.get()) return null;
                Job job = new Job(account, owner, clone, size, epoch);
                pending.put(key, job);
                queue.postRunnable(() -> expire(job), 120_000);
                return job;
            } catch (Throwable e) {
                FileLog.e(e);
                return null;
            }
        }
    }

    private static void prepare(Job job) {
        if (!job.valid()) { finish(job); return; }
        AyuHistoryStorage.getQueue().postRunnable(() -> {
            try {
                AyuHistoryStorage storage = AyuHistoryStorage.getInstance();
                DeletedMessage old = storage.getTTLMedia(job.owner, job.dialog, job.message.id);
                if (!storage.hasTTLMediaCapacity(job.expectedBytes)
                        || storage.isTTLMediaRemoved(job.owner, job.dialog, job.message.id)
                        || old != null && validSavedFile(old)) {
                    finish(job);
                    return;
                }
                job.prepared = true;
                queue.postRunnable(AyuTTLMediaCapture::pump);
            } catch (Throwable e) {
                FileLog.e(e);
                finish(job);
            }
        });
    }

    private static void pump() {
        ArrayList<Job> start = new ArrayList<>();
        synchronized (pending) {
            for (Job job : pending.values()) {
                if (downloads.size() >= MAX_DOWNLOADS) break;
                if (job.prepared && !job.claimed && !job.downloading && job.valid()) {
                    job.downloading = true;
                    if (downloads.add(job.account + ":" + job.fileName)) start.add(job);
                }
            }
        }
        for (Job job : start) {
            if (claimAvailable(job, null)) { releaseDownload(job.account, job.fileName); continue; }
            if (job.document != null) {
                if (!job.valid()) { releaseDownload(job.account, job.fileName); finish(job); continue; }
                FileLoader.getInstance(job.account).loadFile(job.document, job.message, FileLoader.PRIORITY_LOW, 2);
                continue;
            }
            ImageLocation location = ImageLocation.getForPhoto(job.size, job.message.media.photo);
            if (location == null || !job.valid()) { releaseDownload(job.account, job.fileName); finish(job); continue; }
            // cacheType 2 is the same encrypted-cache download used by SecretMediaViewer.
            FileLoader.getInstance(job.account).loadFile(location, job.message, "jpg", FileLoader.PRIORITY_LOW, 2);
        }
    }

    public static void onSelfDestructPhotoFileLoaded(int account, Object parentObject, String fileName, File finalFile) {
        if (!hasWork()) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            queue.postRunnable(() -> onSelfDestructPhotoFileLoaded(account, parentObject, fileName, finalFile));
            return;
        }
        // A completed FileLoader operation can outlive logout. Its parentObject cannot establish
        // the current slot's owner; only a previously registered, owner-bound job can accept it.
        for (Job job : snapshot()) {
            if (job.account == account && job.fileName.equals(fileName)) claimAvailable(job, finalFile);
        }
        releaseDownload(account, fileName);
    }

    public static void onSelfDestructPhotoFileFailed(int account, String fileName) {
        if (!hasWork()) return;
        for (Job job : snapshot()) {
            if (job.account == account && job.fileName.equals(fileName) && !job.claimed) finish(job);
        }
        releaseDownload(account, fileName);
    }

    private static void releaseDownload(int account, String fileName) {
        boolean changed;
        synchronized (pending) { changed = downloads.remove(account + ":" + fileName); }
        if (changed) queue.postRunnable(AyuTTLMediaCapture::pump);
    }

    private static void expire(Job job) {
        boolean active;
        synchronized (pending) {
            active = downloads.contains(job.account + ":" + job.fileName);
        }
        if (active && FileLoader.getInstance(job.account).isLoadingFile(job.fileName)) {
            // Retain bounded metadata for a genuinely slow download; do not lose its completion.
            queue.postRunnable(() -> expire(job), 30_000);
            return;
        }
        releaseDownload(job.account, job.fileName);
        finish(job);
    }

    /** FileLoader worker hook: pin both the inode and key before either can be unlinked. */
    public static void beforeFilesDeleted(int account, ArrayList<File> files) {
        if (files == null || files.isEmpty() || !hasWork() || Looper.myLooper() == Looper.getMainLooper()) return;
        for (Job job : snapshot()) {
            // Telegram's cache paths are shared by slots; any slot can unlink the same file.
            if (job.claimed) continue;
            for (File file : files) {
                if (file != null && (file.getName().equals(job.fileName) || file.getName().equals(job.fileName + ".enc"))) {
                    claimAvailable(job, file);
                    break;
                }
            }
        }
    }

    private static ArrayList<Job> snapshot() {
        synchronized (pending) { return new ArrayList<>(pending.values()); }
    }

    private static boolean hasWork() {
        synchronized (pending) { return !pending.isEmpty() || !downloads.isEmpty(); }
    }

    private static boolean claimAvailable(Job job, File supplied) {
        synchronized (job) {
            if (job.claimed || !job.valid()) return job.claimed;
            File base = FileLoader.getInstance(job.account).getPathToAttach(job.attachment, true);
            File[] candidates = {supplied, new File(base.getPath() + ".enc"), base,
                    FileLoader.getInstance(job.account).getPathToAttach(job.attachment, false)};
            for (File candidate : candidates) {
                if (candidate == null || !candidate.isFile() || candidate.length() != job.expectedBytes) continue;
                FileInputStream stream = null;
                byte[] key = null, iv = null;
                try {
                    stream = new FileInputStream(candidate);
                    if (candidate.getName().endsWith(".enc")) {
                        File keyFile = new File(FileLoader.getInternalCacheDir(), job.fileName + ".enc.key");
                        try (RandomAccessFile keyInput = new RandomAccessFile(keyFile, "r")) {
                            if (keyInput.length() != 48) throw new java.io.IOException("Invalid media key length");
                            key = new byte[32];
                            iv = new byte[16];
                            keyInput.readFully(key);
                            keyInput.readFully(iv);
                        }
                    }
                    job.claimed = true;
                    final FileInputStream pinned = stream;
                    final byte[] fileKey = key, fileIv = iv;
                    queue.postRunnable(() -> save(job, pinned, fileKey, fileIv));
                    return true;
                } catch (Throwable e) {
                    if (stream != null) try { stream.close(); } catch (Exception ignore) { }
                    if (key != null) Arrays.fill(key, (byte) 0);
                    if (iv != null) Arrays.fill(iv, (byte) 0);
                }
            }
            return false;
        }
    }

    private static File directory(Job job) {
        return new File(new File(new File(new File(AyuHistoryStorage.getMediaDir(), "ttl"),
                Integer.toString(job.account)), Long.toString(job.owner)), Long.toString(job.dialog));
    }

    private static void save(Job job, FileInputStream input, byte[] key, byte[] iv) {
        File temporary = null;
        boolean committing = false;
        try (FileInputStream stream = input) {
            if (!job.valid()) return;
            File dir = directory(job);
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            if (dir.getUsableSpace() < job.expectedBytes + MIN_FREE_BYTES) return;
            temporary = File.createTempFile("m" + job.message.id + "_", ".part", dir);
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                copyComplete(stream, output, job.expectedBytes, key, iv);
                output.getFD().sync();
            }
            if (!validMedia(temporary, job.document != null) || !job.valid()) return;
            final File ready = temporary;
            temporary = null;
            AyuHistoryStorage.getQueue().postRunnable(() -> commit(job, ready));
            committing = true;
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            if (key != null) Arrays.fill(key, (byte) 0);
            if (iv != null) Arrays.fill(iv, (byte) 0);
            if (temporary != null) temporary.delete();
            // Keep the job pinned until commit validates its generation and removal tombstone.
            if (!committing) finish(job);
        }
    }

    private static boolean validMedia(File file, boolean video) throws Exception {
        if (!video) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            return options.outWidth > 0 && options.outHeight > 0;
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            return Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) > 0
                    && Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)) > 0
                    && Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)) > 0;
        } finally {
            retriever.release();
        }
    }

    /** Only actual bytes read are decrypted; offsets advance by that count, including short reads. */
    static void copyComplete(java.io.InputStream input, java.io.OutputStream output, int expected,
                             byte[] key, byte[] iv) throws java.io.IOException {
        byte[] bytes = new byte[64 * 1024];
        int offset = 0;
        while (offset < expected) {
            int read = input.read(bytes, 0, Math.min(bytes.length, expected - offset));
            if (read <= 0) throw new java.io.IOException("Incomplete media");
            if (key != null) Utilities.aesCtrDecryptionByteArray(bytes, key, iv, 0, read, offset);
            output.write(bytes, 0, read);
            offset += read;
        }
        if (input.read() != -1) throw new java.io.IOException("Unexpected media length");
    }

    private static void commit(Job job, File ready) {
        File destination = null;
        boolean published = false;
        try {
            AyuHistoryStorage storage = AyuHistoryStorage.getInstance();
            if (!job.valid() || !storage.hasTTLMediaCapacity(job.expectedBytes)
                    || storage.isTTLMediaRemoved(job.owner, job.dialog, job.message.id)) return;
            DeletedMessage old = storage.getTTLMedia(job.owner, job.dialog, job.message.id);
            if (old != null && validSavedFile(old)) return;
            // Unique name prevents an older interrupted attempt deleting a newer successful copy.
            destination = new File(ready.getParentFile(), ready.getName().replace(".part", job.document != null ? ".mp4" : ".jpg"));
            if (!ready.renameTo(destination)) return;
            DeletedMessage row = new DeletedMessage();
            AyuMessagesController.getInstance().fillRow(job.account, row, job.message, job.owner);
            row.mediaPath = destination.getAbsolutePath();
            row.hqThumbPath = null;
            if (!job.valid() || storage.insertTTLMedia(row) <= 0) return;
            published = true;
            AyuMessagesController.getInstance().postHistoryUpdated(job.account, job.dialog, job.message.id, 2);
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            ready.delete();
            if (!published && destination != null) destination.delete();
            finish(job);
        }
    }

    static boolean validSavedFile(DeletedMessage row) {
        return row != null && row.mediaPath != null && new File(row.mediaPath).isFile()
                && new File(row.mediaPath).length() > 0;
    }

    private static void finish(Job job) {
        synchronized (pending) {
            if (pending.get(job.key) == job) pending.remove(job.key);
            job.cancelled = true;
            job.downloading = false;
        }
        queue.postRunnable(AyuTTLMediaCapture::pump);
    }

    static void cancel(int account, long owner, long dialog, int message) {
        Job job;
        synchronized (pending) { job = pending.get(key(account, owner, dialog, message)); }
        if (job != null) finish(job);
    }

    static void clearPending() {
        generation.incrementAndGet();
        synchronized (pending) {
            for (Job job : pending.values()) job.cancelled = true;
            pending.clear();
        }
    }
}
