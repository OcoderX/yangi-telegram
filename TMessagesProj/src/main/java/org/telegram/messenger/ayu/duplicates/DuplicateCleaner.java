package org.telegram.messenger.ayu.duplicates;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.ui.Storage.CacheModel;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Removes the redundant copies found by {@link DuplicateScanner}.
 * <p>
 * Deletion follows exactly the same path the built-in cache screen uses so nothing gets out of sync:
 * the paths are dropped from {@code FilePathDatabase} first, running downloads are cancelled, the
 * files themselves are unlinked on the file loader queue (together with their {@code .enc},
 * {@code .enc.key} and {@code q_} companions) and {@code checkCurrentDownloadsFiles()} refreshes the
 * download state of every message afterwards.
 */
public class DuplicateCleaner {

    public interface DeleteCallback {
        /** called on the main thread once every file has been unlinked */
        void onDeleted(int deletedFiles, long freedBytes);
    }

    private DuplicateCleaner() {
    }

    /**
     * @param keepImageMemory false clears the in-memory image cache after the deletion, which is what
     *                        the cache screen does whenever image folders were touched
     */
    public static void delete(final int currentAccount,
                              final List<DuplicateScanner.DuplicateFile> filesToDelete,
                              final boolean keepImageMemory,
                              final DeleteCallback callback) {
        if (filesToDelete == null || filesToDelete.isEmpty()) {
            if (callback != null) {
                callback.onDeleted(0, 0);
            }
            return;
        }

        final ArrayList<CacheModel.FileInfo> fileInfos = new ArrayList<>(filesToDelete.size());
        for (int i = 0; i < filesToDelete.size(); i++) {
            DuplicateScanner.DuplicateFile f = filesToDelete.get(i);
            CacheModel.FileInfo info = new CacheModel.FileInfo(f.file);
            info.size = f.size;
            info.dialogId = f.dialogId;
            info.messageId = f.messageId;
            info.messageType = f.messageType;
            fileInfos.add(info);
        }

        final FileLoader fileLoader = FileLoader.getInstance(currentAccount);
        fileLoader.getFileDatabase().removeFiles(fileInfos);
        fileLoader.cancelLoadAllFiles();
        fileLoader.getFileLoaderQueue().postRunnable(() -> {
            int deleted = 0;
            long freed = 0;
            for (int i = 0; i < fileInfos.size(); i++) {
                CacheModel.FileInfo info = fileInfos.get(i);
                if (deleteFileWithCompanions(info.file)) {
                    deleted++;
                    freed += info.size;
                }
            }
            final int deletedFinal = deleted;
            final long freedFinal = freed;
            AndroidUtilities.runOnUIThread(() -> {
                FileLoader.getInstance(currentAccount).checkCurrentDownloadsFiles();
                if (!keepImageMemory) {
                    ImageLoader.getInstance().clearMemory();
                }
                DuplicatesConfig.onBytesFreed(freedFinal);
                if (callback != null) {
                    callback.onDeleted(deletedFinal, freedFinal);
                }
            });
        });
    }

    /** mirrors FileLoader.deleteFiles() for a single file */
    private static boolean deleteFileWithCompanions(File file) {
        if (file == null) {
            return false;
        }
        boolean removed = false;
        try {
            File encrypted = new File(file.getAbsolutePath() + ".enc");
            if (encrypted.exists()) {
                if (!encrypted.delete()) {
                    encrypted.deleteOnExit();
                }
                removed = true;
                try {
                    File key = new File(FileLoader.getInternalCacheDir(), file.getName() + ".enc.key");
                    if (key.exists() && !key.delete()) {
                        key.deleteOnExit();
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            } else if (file.exists()) {
                if (file.delete()) {
                    removed = true;
                } else {
                    file.deleteOnExit();
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        try {
            File parent = file.getParentFile();
            if (parent != null) {
                File qFile = new File(parent, "q_" + file.getName());
                if (qFile.exists() && !qFile.delete()) {
                    qFile.deleteOnExit();
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return removed;
    }
}
