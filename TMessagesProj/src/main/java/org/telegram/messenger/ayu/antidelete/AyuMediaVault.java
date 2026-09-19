package org.telegram.messenger.ayu.antidelete;

import android.system.ErrnoException;
import android.system.Os;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.ayu.AyuConstants;
import org.telegram.messenger.ayu.AyuHistoryStorage;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;

/**
 * Keeps the bytes of a message alive after Telegram deleted (or evicted) them.
 * <p>
 * Two halves:
 * <ul>
 *     <li><b>preserve</b> - copies an already downloaded file out of the Telegram cache into the
 *     app-private AyuGram media folder. This <i>must</i> happen on the thread that is about to run
 *     the deletion ({@code MessagesStorage.storageQueue}), because {@code FileLoader.deleteFiles()}
 *     is called a few statements later and an asynchronous copy loses the race.</li>
 *     <li><b>resolve</b> - points a restored {@code TLRPC.Message} back at the saved copy.
 *     Documents (video / voice / round / gif / music / file / sticker) are resolved for free through
 *     {@code TLRPC.Document.localPath}, which {@code FileLoader.getPathToAttach()} honours.
 *     Photos have no such field, so the saved copy is linked (or copied) back into the exact cache
 *     path {@code FileLoader} would look at.</li>
 * </ul>
 * All file work is done off the main thread; the only synchronous entry point is
 * {@link #preserve} which is already called from a background queue.
 */
public class AyuMediaVault {

    /** never stream-copy a restored photo bigger than this back into the cache */
    private static final long MAX_RESTORE_COPY_SIZE = 32L * 1024L * 1024L;

    /**
     * Biggest file we are willing to stream-copy while the caller's queue waits. Anything larger is
     * "claimed" instead: an input stream is opened synchronously and the copy finishes on the
     * AyuGram queue - unlinking a file that still has an open descriptor does not free its data, so
     * the bytes survive {@code FileLoader.deleteFiles()} even though the copy runs afterwards.
     */
    private static final long SYNC_COPY_LIMIT = 16L * 1024L * 1024L;

    private AyuMediaVault() {
    }

    // =================================================================================
    //                                    preserve
    // =================================================================================

    public static File dirFor(int currentAccount, long dialogId) {
        File dir = new File(new File(AyuHistoryStorage.getMediaDir(), Integer.toString(currentAccount)), Long.toString(dialogId));
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static String sanitize(String name) {
        if (TextUtils.isEmpty(name)) {
            return "file";
        }
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * Copies the already downloaded media (and its best cached thumbnail) of {@code message} into
     * the AyuGram media folder. Runs on the calling thread on purpose.
     *
     * @return {@code {mediaPath, thumbPath}} - either entry may be null; never null itself.
     */
    public static String[] preserve(int currentAccount, TLRPC.Message message, long dialogId, int documentType) {
        final String[] result = new String[2];
        try {
            if (message == null || documentType == AyuConstants.DOCUMENT_TYPE_NONE) {
                return result;
            }
            TLRPC.MessageMedia media = MessageObject.getMedia(message);
            if (media == null) {
                return result;
            }
            final File dir = dirFor(currentAccount, dialogId);

            File source = null;
            if (!TextUtils.isEmpty(message.attachPath)) {
                File attach = new File(message.attachPath);
                if (attach.exists() && attach.length() > 0) {
                    source = attach;
                }
            }
            if (source == null) {
                source = FileLoader.getInstance(currentAccount).getPathToMessage(message);
            }
            if (source != null && source.exists() && source.length() > 0) {
                File destination = new File(dir, message.id + "_" + sanitize(source.getName()));
                if (claim(source, destination)) {
                    result[0] = destination.getAbsolutePath();
                }
            }

            TLRPC.PhotoSize thumb = null;
            if (media.document != null) {
                thumb = FileLoader.getClosestPhotoSizeWithSize(media.document.thumbs, 320);
            } else if (media.photo != null) {
                thumb = FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes, 320);
            }
            if (thumb != null) {
                File thumbSource = FileLoader.getInstance(currentAccount).getPathToAttach(thumb, true);
                if (thumbSource == null || !thumbSource.exists()) {
                    thumbSource = FileLoader.getInstance(currentAccount).getPathToAttach(thumb, false);
                }
                if (thumbSource != null && thumbSource.exists() && thumbSource.length() > 0) {
                    File destination = new File(dir, message.id + "_thumb_" + sanitize(thumbSource.getName()));
                    if (linkOrCopy(thumbSource, destination)) {
                        result[1] = destination.getAbsolutePath();
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return result;
    }

    // =================================================================================
    //                                     resolve
    // =================================================================================

    /**
     * Makes a message that was rebuilt from the AyuGram database resolve to the saved copy.
     * Cheap and safe to call from the UI thread: only in-memory fields are touched here, the
     * (rare) photo copy-back is posted to the AyuGram queue.
     */
    public static void applySavedPaths(int currentAccount, TLRPC.Message message, String mediaPath, String thumbPath) {
        if (message == null || TextUtils.isEmpty(mediaPath)) {
            return;
        }
        try {
            final File saved = new File(mediaPath);
            if (!saved.exists() || saved.length() == 0) {
                return;
            }
            // honoured by MessageObject.checkMediaExistance(), MediaController and AndroidUtilities.openForView()
            message.attachPath = mediaPath;

            final TLRPC.MessageMedia media = MessageObject.getMedia(message);
            if (media == null) {
                return;
            }
            if (media.document != null) {
                // FileLoader.getPathToAttach() returns this verbatim, so every document type
                // (video, voice, round, gif, music, sticker, plain file) resolves to our copy.
                media.document.localPath = mediaPath;
                return;
            }
            if (media.photo != null && AyuAntiDeleteConfig.restoreMediaToCache) {
                restorePhotoAsync(currentAccount, message, mediaPath, thumbPath);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * Photos are addressed by their file location, so the only way to make them open again is to put
     * the saved bytes back where {@code FileLoader} expects them. Done on the AyuGram queue.
     */
    private static void restorePhotoAsync(int currentAccount, TLRPC.Message message, String mediaPath, String thumbPath) {
        final TLRPC.Message copy = message;
        AyuHistoryStorage.getQueue().postRunnable(() -> {
            try {
                boolean restored = restoreInto(mediaPath, FileLoader.getInstance(currentAccount).getPathToMessage(copy));
                if (!TextUtils.isEmpty(thumbPath)) {
                    TLRPC.MessageMedia media = MessageObject.getMedia(copy);
                    if (media != null && media.photo != null) {
                        TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes, 320);
                        if (thumb != null) {
                            restored |= restoreInto(thumbPath, FileLoader.getInstance(currentAccount).getPathToAttach(thumb, false));
                        }
                    }
                }
                if (restored) {
                    // the file only showed up now, so whoever drew the bubble has to look again.
                    // a second pass through here is a no-op (the target then already exists).
                    final long dialogId = copy.dialog_id != 0 ? copy.dialog_id : MessageObject.getDialogId(copy);
                    final ArrayList<Integer> ids = new ArrayList<>();
                    ids.add(copy.id);
                    AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(currentAccount)
                            .postNotificationName(NotificationCenter.ayuMessageHistoryUpdated, dialogId, ids, 0));
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    private static boolean restoreInto(String savedPath, File target) {
        if (TextUtils.isEmpty(savedPath) || target == null) {
            return false;
        }
        final String targetPath = target.getAbsolutePath();
        if (TextUtils.isEmpty(targetPath) || targetPath.equals(savedPath)) {
            return false;
        }
        final File saved = new File(savedPath);
        if (!saved.exists() || saved.length() == 0) {
            return false;
        }
        if (target.exists() && target.length() == saved.length()) {
            return false;
        }
        if (saved.length() > MAX_RESTORE_COPY_SIZE) {
            // only try the free variant for very big files
            return hardLink(saved, target);
        }
        return linkOrCopy(saved, target);
    }

    // =================================================================================
    //                                   file helpers
    // =================================================================================

    /**
     * Takes ownership of {@code source} before the caller deletes it, without blocking for long.
     *
     * @return true when {@code destination} is, or is about to become, a full copy
     */
    private static boolean claim(File source, File destination) {
        if (source == null || destination == null || !source.exists() || source.length() == 0) {
            return false;
        }
        if (destination.exists() && destination.length() == source.length()) {
            return true;
        }
        if (hardLink(source, destination)) {
            return true;
        }
        if (source.length() <= SYNC_COPY_LIMIT) {
            return copyFile(source, destination);
        }
        FileInputStream in;
        try {
            File parent = destination.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            in = new FileInputStream(source);
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
        final FileInputStream claimed = in;
        AyuHistoryStorage.getQueue().postRunnable(() -> copyFromStream(claimed, destination));
        return true;
    }

    /** finishes a {@link #claim} - {@code in} is already open, so the source may be gone by now */
    private static void copyFromStream(FileInputStream in, File destination) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(destination);
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } catch (Throwable e) {
            FileLog.e(e);
            try {
                destination.delete();
            } catch (Throwable ignore) {
            }
        } finally {
            try {
                in.close();
            } catch (Throwable ignore) {
            }
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Throwable ignore) {
            }
        }
    }

    /** hard link first (free, and unlinking the cache copy never touches ours), stream copy as fallback */
    public static boolean linkOrCopy(File source, File destination) {
        if (source == null || destination == null || !source.exists() || source.length() == 0) {
            return false;
        }
        if (destination.exists() && destination.length() == source.length()) {
            return true;
        }
        if (hardLink(source, destination)) {
            return true;
        }
        return copyFile(source, destination);
    }

    private static boolean hardLink(File source, File destination) {
        try {
            File parent = destination.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            if (destination.exists()) {
                if (destination.length() == source.length()) {
                    return true;
                }
                if (!destination.delete()) {
                    return false;
                }
            }
            Os.link(source.getAbsolutePath(), destination.getAbsolutePath());
            return destination.exists() && destination.length() > 0;
        } catch (ErrnoException e) {
            // EXDEV (cache and files dir on different mounts) / EPERM - fall back to copying
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean copyFile(File source, File destination) {
        if (source == null || destination == null || !source.exists() || source.length() == 0) {
            return false;
        }
        if (destination.exists() && destination.length() == source.length()) {
            return true;
        }
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            File parent = destination.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            in = new FileInputStream(source);
            out = new FileOutputStream(destination);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            try {
                destination.delete();
            } catch (Throwable ignore) {
            }
            return false;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Throwable ignore) {
            }
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Throwable ignore) {
            }
        }
    }
}
