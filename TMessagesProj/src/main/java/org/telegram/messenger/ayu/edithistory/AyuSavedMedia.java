package org.telegram.messenger.ayu.edithistory;

import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.ayu.AyuConstants;
import org.telegram.messenger.ayu.AyuHistoryStorage;
import org.telegram.messenger.ayu.entities.AyuMessageBase;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * Small copy helper that keeps the media of a replaced message version around.
 * <p>
 * Layout: {@code files_dir/ayu/media/<account>/<dialogId>/<messageId>_<name>} - literally the same
 * folder the anti-delete vault ({@link org.telegram.messenger.ayu.antidelete.AyuMediaVault}) uses,
 * so a single directory holds every locally preserved file and one size/clear pass covers both.
 */
public class AyuSavedMedia {

    private static final int BUFFER_SIZE = 64 * 1024;

    /**
     * Shared with anti-delete on purpose: {@link AyuHistoryStorage#getDatabaseSize()} and
     * {@link AyuHistoryStorage#clear()} account for this tree exactly once, via getMediaDir().
     */
    public static File getRootDir() {
        return AyuHistoryStorage.getMediaDir();
    }

    public static File getDir(int currentAccount, long dialogId) {
        final File dir = new File(new File(getRootDir(), Integer.toString(currentAccount)), Long.toString(dialogId));
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static String sanitize(String name) {
        if (TextUtils.isEmpty(name)) {
            return "file";
        }
        String result = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (result.length() > 64) {
            result = result.substring(result.length() - 64);
        }
        return result;
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
            in = new FileInputStream(source);
            out = new FileOutputStream(destination);
            final byte[] buffer = new byte[BUFFER_SIZE];
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

    /**
     * Copies the already downloaded media (and its best thumbnail) of the version that is about to
     * be replaced. Never downloads anything: an edit destroys the old file reference immediately, so
     * only what is already on disk can be kept.
     *
     * @return true when at least the thumbnail or the file itself was preserved
     */
    public static boolean saveForRevision(int currentAccount, TLRPC.Message message, AyuMessageBase row) {
        if (message == null || row == null) {
            return false;
        }
        if (!AyuEditHistoryConfig.keepOldMedia) {
            return false;
        }
        boolean saved = false;
        try {
            if (row.documentType == AyuConstants.DOCUMENT_TYPE_NONE) {
                return false;
            }
            final TLRPC.MessageMedia media = MessageObject.getMedia(message);
            if (media == null) {
                return false;
            }
            final File dir = getDir(currentAccount, row.dialogId);
            File source = null;
            if (!TextUtils.isEmpty(message.attachPath)) {
                final File attach = new File(message.attachPath);
                if (attach.exists() && attach.length() > 0) {
                    source = attach;
                }
            }
            if (source == null) {
                try {
                    source = FileLoader.getInstance(currentAccount).getPathToMessage(message);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            if (source != null && source.exists() && source.length() > 0) {
                final File destination = new File(dir, row.messageId + "_" + row.editDate + "_" + sanitize(source.getName()));
                if (copyFile(source, destination)) {
                    row.mediaPath = destination.getAbsolutePath();
                    saved = true;
                }
            }

            TLRPC.PhotoSize thumb = null;
            if (media.document != null) {
                thumb = FileLoader.getClosestPhotoSizeWithSize(media.document.thumbs, 320);
            } else if (media.photo != null) {
                thumb = FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes, 320);
            }
            if (thumb != null) {
                File thumbSource = null;
                try {
                    thumbSource = FileLoader.getInstance(currentAccount).getPathToAttach(thumb, true);
                    if (thumbSource == null || !thumbSource.exists()) {
                        thumbSource = FileLoader.getInstance(currentAccount).getPathToAttach(thumb, false);
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                if (thumbSource != null && thumbSource.exists() && thumbSource.length() > 0) {
                    final File destination = new File(dir, row.messageId + "_" + row.editDate + "_thumb_" + sanitize(thumbSource.getName()));
                    if (copyFile(thumbSource, destination)) {
                        row.hqThumbPath = destination.getAbsolutePath();
                        saved = true;
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return saved;
    }

    // ------------------------------------------------------------------ maintenance

    public static long totalSize() {
        try {
            return dirSize(getRootDir());
        } catch (Throwable e) {
            FileLog.e(e);
            return 0;
        }
    }

    private static long dirSize(File file) {
        if (file == null || !file.exists()) {
            return 0;
        }
        if (file.isFile()) {
            return file.length();
        }
        long total = 0;
        final File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                total += dirSize(child);
            }
        }
        return total;
    }

    /** removes every preserved file (called from the AyuGram "clear database" action) */
    public static void clearAll() {
        try {
            final File[] children = getRootDir().listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            final File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }
}
