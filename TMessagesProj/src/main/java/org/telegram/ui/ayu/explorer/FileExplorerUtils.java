package org.telegram.ui.ayu.explorer;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import androidx.core.content.FileProvider;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.ayu.explorer.FileEntry;
import org.telegram.messenger.ayu.explorer.FileIndexer;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.SearchViewPager;
import org.telegram.ui.PhotoViewer;
import org.telegram.ui.Storage.CacheModel;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AyuGram File Explorer: naming helpers plus every action a file row can trigger
 * (open, go to message, share, delete the local copy).
 */
public class FileExplorerUtils {

    private FileExplorerUtils() {
    }

    // ------------------------------------------------------------------ naming

    public static String getDialogTitle(int currentAccount, long dialogId) {
        if (dialogId == 0) {
            return LocaleController.getString(R.string.AyuFeUnknownChat);
        }
        if (UserConfig.getInstance(currentAccount).getClientUserId() == dialogId) {
            return LocaleController.getString(R.string.SavedMessages);
        }
        final String name = DialogObject.getName(currentAccount, dialogId);
        if (TextUtils.isEmpty(name)) {
            return String.valueOf(dialogId);
        }
        return name;
    }

    public static String getCategoryName(int category) {
        switch (category) {
            case FileEntry.CATEGORY_PHOTOS:
                return LocaleController.getString(R.string.AyuFeCategoryPhotos);
            case FileEntry.CATEGORY_VIDEOS:
                return LocaleController.getString(R.string.AyuFeCategoryVideos);
            case FileEntry.CATEGORY_DOCUMENTS:
                return LocaleController.getString(R.string.AyuFeCategoryDocuments);
            case FileEntry.CATEGORY_MUSIC:
                return LocaleController.getString(R.string.AyuFeCategoryMusic);
            case FileEntry.CATEGORY_VOICE:
                return LocaleController.getString(R.string.AyuFeCategoryVoice);
            case FileEntry.CATEGORY_GIFS:
                return LocaleController.getString(R.string.AyuFeCategoryGifs);
            case FileEntry.CATEGORY_STICKERS:
                return LocaleController.getString(R.string.AyuFeCategoryStickers);
            default:
                return LocaleController.getString(R.string.AyuFeCategoryOther);
        }
    }

    public static int getCategoryIcon(int category) {
        switch (category) {
            case FileEntry.CATEGORY_PHOTOS:
                return R.drawable.msg_filled_data_photos;
            case FileEntry.CATEGORY_VIDEOS:
                return R.drawable.msg_filled_data_videos;
            case FileEntry.CATEGORY_DOCUMENTS:
                return R.drawable.msg_filled_data_files;
            case FileEntry.CATEGORY_MUSIC:
                return R.drawable.msg_filled_data_music;
            case FileEntry.CATEGORY_VOICE:
                return R.drawable.msg_filled_data_voice;
            case FileEntry.CATEGORY_GIFS:
                return R.drawable.msg_gif;
            case FileEntry.CATEGORY_STICKERS:
                return R.drawable.msg_emoji_stickers;
            default:
                return R.drawable.msg_filled_datausage;
        }
    }

    public static int getCategoryColorKey(int category) {
        switch (category) {
            case FileEntry.CATEGORY_PHOTOS:
                return org.telegram.ui.ActionBar.Theme.key_statisticChartLine_lightblue;
            case FileEntry.CATEGORY_VIDEOS:
                return org.telegram.ui.ActionBar.Theme.key_statisticChartLine_blue;
            case FileEntry.CATEGORY_DOCUMENTS:
                return org.telegram.ui.ActionBar.Theme.key_statisticChartLine_green;
            case FileEntry.CATEGORY_MUSIC:
                return org.telegram.ui.ActionBar.Theme.key_statisticChartLine_red;
            case FileEntry.CATEGORY_VOICE:
                return org.telegram.ui.ActionBar.Theme.key_statisticChartLine_lightgreen;
            case FileEntry.CATEGORY_GIFS:
                return org.telegram.ui.ActionBar.Theme.key_statisticChartLine_orange;
            case FileEntry.CATEGORY_STICKERS:
                return org.telegram.ui.ActionBar.Theme.key_statisticChartLine_purple;
            default:
                return org.telegram.ui.ActionBar.Theme.key_statisticChartLine_golden;
        }
    }

    public static String formatFilesCount(int count, long size) {
        return LocaleController.formatPluralString("AyuFeFiles", count) + ", " + AndroidUtilities.formatFileSize(size);
    }

    // ------------------------------------------------------------------ loading the message back

    public interface MessageCallback {
        void run(MessageObject messageObject);
    }

    /**
     * Re-reads the {@link TLRPC.Message} behind an entry from the local media cache and hands the
     * resulting {@link MessageObject} back on the UI thread (null when it is gone).
     */
    public static void loadMessageObject(int currentAccount, FileEntry entry, MessageCallback callback) {
        if (entry == null || !entry.hasMessage()) {
            callback.run(null);
            return;
        }
        final long dialogId = entry.dialogId;
        final int messageId = entry.messageId;
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
            TLRPC.Message message = null;
            final SQLiteDatabase database = MessagesStorage.getInstance(currentAccount).getDatabase();
            if (database != null) {
                SQLiteCursor cursor = null;
                try {
                    cursor = database.queryFinalized(String.format(Locale.US,
                            "SELECT data FROM media_v4 WHERE mid = %d AND uid = %d", messageId, dialogId));
                    if (cursor.next()) {
                        final NativeByteBuffer data = cursor.byteBufferValue(0);
                        if (data != null) {
                            message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                            if (message != null) {
                                message.readAttachPath(data, UserConfig.getInstance(currentAccount).getClientUserId());
                                message.id = messageId;
                                message.dialog_id = dialogId;
                            }
                            data.reuse();
                        }
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                } finally {
                    if (cursor != null) {
                        cursor.dispose();
                    }
                }
            }
            final TLRPC.Message finalMessage = message;
            AndroidUtilities.runOnUIThread(() -> {
                if (finalMessage == null) {
                    callback.run(null);
                    return;
                }
                MessageObject messageObject = null;
                try {
                    messageObject = new MessageObject(currentAccount, finalMessage, true, true);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                callback.run(messageObject);
            });
        });
    }

    // ------------------------------------------------------------------ actions

    /** opens the file: PhotoViewer for photos / videos, the system viewer otherwise */
    public static void open(BaseFragment fragment, int currentAccount, FileEntry entry) {
        if (fragment == null || entry == null) {
            return;
        }
        final Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final File file = entry.getFile();
        if (file == null || !file.exists()) {
            download(fragment, currentAccount, entry);
            return;
        }
        if (entry.hasMessage() && (entry.category == FileEntry.CATEGORY_PHOTOS || entry.category == FileEntry.CATEGORY_VIDEOS || entry.category == FileEntry.CATEGORY_GIFS)) {
            loadMessageObject(currentAccount, entry, messageObject -> {
                if (messageObject != null && (messageObject.isPhoto() || messageObject.isVideo() || messageObject.canPreviewDocument())) {
                    final ArrayList<MessageObject> list = new ArrayList<>();
                    list.add(messageObject);
                    PhotoViewer.getInstance().setParentActivity(fragment);
                    PhotoViewer.getInstance().openPhoto(list, 0, 0, 0, 0, new PhotoViewer.EmptyPhotoViewerProvider());
                } else {
                    openWithSystemViewer(fragment, entry, file);
                }
            });
            return;
        }
        openWithSystemViewer(fragment, entry, file);
    }

    private static void openWithSystemViewer(BaseFragment fragment, FileEntry entry, File file) {
        final Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        try {
            AndroidUtilities.openForView(file, entry.name, entry.mimeType, activity, fragment.getResourceProvider(), false);
        } catch (Throwable e) {
            FileLog.e(e);
            BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.AyuFeCannotOpen)).show();
        }
    }

    /** starts downloading the file behind an entry that is not on disk yet */
    public static void download(BaseFragment fragment, int currentAccount, FileEntry entry) {
        if (entry == null || entry.document == null) {
            BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.AyuFeNotDownloaded)).show();
            return;
        }
        FileLoader.getInstance(currentAccount).loadFile(entry.document, entry.document, FileLoader.PRIORITY_NORMAL, 0);
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.ic_download, LocaleController.getString(R.string.AyuFeDownloadStarted)).show();
    }

    /** opens the chat the file was sent in, scrolled to the message */
    public static void goToMessage(BaseFragment fragment, int currentAccount, FileEntry entry) {
        if (fragment == null || entry == null || !entry.hasMessage()) {
            if (fragment != null) {
                BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.AyuFeNoMessage)).show();
            }
            return;
        }
        if (MessagesController.getInstance(currentAccount).getUser(entry.dialogId) == null
                && MessagesController.getInstance(currentAccount).getChat(-entry.dialogId) == null
                && !DialogObject.isEncryptedDialog(entry.dialogId)) {
            BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.AyuFeNoMessage)).show();
            return;
        }
        loadMessageObject(currentAccount, entry, messageObject -> {
            if (messageObject == null) {
                BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.AyuFeNoMessage)).show();
                return;
            }
            fragment.presentFragment(SearchViewPager.createFragmentFromMessage(currentAccount, messageObject));
        });
    }

    /** shares the local copies with the system chooser */
    public static void share(BaseFragment fragment, List<FileEntry> entries) {
        if (fragment == null || entries == null || entries.isEmpty()) {
            return;
        }
        final Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final ArrayList<Uri> uris = new ArrayList<>();
        String mimeType = null;
        boolean sameMime = true;
        for (int i = 0; i < entries.size(); i++) {
            final FileEntry entry = entries.get(i);
            final File file = entry.getFile();
            if (file == null || !file.exists()) {
                continue;
            }
            Uri uri = null;
            if (Build.VERSION.SDK_INT >= 24) {
                try {
                    uri = FileProvider.getUriForFile(ApplicationLoader.applicationContext, ApplicationLoader.getApplicationId() + ".provider", file);
                } catch (Exception ignore) {
                }
            }
            if (uri == null) {
                uri = Uri.fromFile(file);
            }
            uris.add(uri);
            if (mimeType == null) {
                mimeType = entry.mimeType;
            } else if (!TextUtils.equals(mimeType, entry.mimeType)) {
                sameMime = false;
            }
        }
        if (uris.isEmpty()) {
            BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.AyuFeNotDownloaded)).show();
            return;
        }
        try {
            final Intent intent;
            if (uris.size() == 1) {
                intent = new Intent(Intent.ACTION_SEND);
                intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            }
            intent.setType(sameMime && !TextUtils.isEmpty(mimeType) ? mimeType : "*/*");
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivityForResult(Intent.createChooser(intent, LocaleController.getString(R.string.ShareFile)), 500);
        } catch (Throwable e) {
            FileLog.e(e);
            BulletinFactory.of(fragment).createErrorBulletin(LocaleController.getString(R.string.AyuFeCannotOpen)).show();
        }
    }

    /** asks for confirmation and then deletes the local copies (the messages stay untouched) */
    public static void confirmDelete(BaseFragment fragment, int currentAccount, ArrayList<FileEntry> entries, Runnable onDone) {
        if (fragment == null || fragment.getParentActivity() == null || entries == null || entries.isEmpty()) {
            return;
        }
        long size = 0;
        for (int i = 0; i < entries.size(); i++) {
            size += entries.get(i).onDiskSize;
        }
        final long finalSize = size;
        new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
                .setTitle(LocaleController.formatPluralString("AyuFeDeleteTitle", entries.size()))
                .setMessage(LocaleController.formatString(R.string.AyuFeDeleteMessage, AndroidUtilities.formatFileSize(finalSize)))
                .setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> deleteLocalCopies(fragment, currentAccount, entries, onDone))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    /**
     * Deletes the files from disk exactly the way CacheControlActivity does: the FileLoader path
     * database rows are dropped first, running downloads are cancelled, the files are removed on the
     * file loader queue and the in-memory image cache is invalidated afterwards.
     */
    public static void deleteLocalCopies(BaseFragment fragment, int currentAccount, ArrayList<FileEntry> entries, Runnable onDone) {
        final ArrayList<CacheModel.FileInfo> infos = new ArrayList<>();
        final ArrayList<FileEntry> deleted = new ArrayList<>();
        long freed = 0;
        for (int i = 0; i < entries.size(); i++) {
            final FileEntry entry = entries.get(i);
            final File file = entry.getFile();
            if (file == null || !file.exists()) {
                continue;
            }
            final CacheModel.FileInfo info = new CacheModel.FileInfo(file);
            info.size = entry.onDiskSize;
            info.dialogId = entry.dialogId;
            info.messageId = entry.messageId;
            infos.add(info);
            deleted.add(entry);
            freed += entry.onDiskSize;
        }
        if (infos.isEmpty()) {
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        final long finalFreed = freed;
        final FileLoader fileLoader = FileLoader.getInstance(currentAccount);
        fileLoader.getFileDatabase().removeFiles(infos);
        fileLoader.cancelLoadAllFiles();
        fileLoader.getFileLoaderQueue().postRunnable(() -> {
            for (int i = 0; i < infos.size(); i++) {
                try {
                    infos.get(i).file.delete();
                } catch (Throwable ignore) {
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    FileLoader.getInstance(currentAccount).checkCurrentDownloadsFiles();
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                try {
                    ImageLoader.getInstance().clearMemory();
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                for (int i = 0; i < deleted.size(); i++) {
                    final FileEntry entry = deleted.get(i);
                    entry.downloaded = false;
                    entry.onDiskSize = 0;
                }
                final FileIndexer indexer = FileIndexer.getInstance(currentAccount);
                // files that only existed on disk are gone for good, drop them from the index;
                // the rest just stops being "downloaded", which the incremental refresh picks up
                final ArrayList<FileEntry> orphans = new ArrayList<>();
                for (int i = 0; i < deleted.size(); i++) {
                    final FileEntry entry = deleted.get(i);
                    if (!entry.hasMessage() && entry.document == null && entry.photo == null) {
                        orphans.add(entry);
                    }
                }
                indexer.removeEntries(orphans);
                indexer.refreshDownloadedState();
                if (fragment != null && fragment.getParentActivity() != null) {
                    BulletinFactory.of(fragment).createSimpleBulletin(R.raw.ic_delete,
                            LocaleController.formatString(R.string.AyuFeDeleted, AndroidUtilities.formatFileSize(finalFreed))).show();
                }
                if (onDone != null) {
                    onDone.run();
                }
            });
        });
    }

    /** used by the root screen to show how much of the device is free */
    public static void loadDeviceFreeSpace(Utilities.Callback<Long> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            long free = 0;
            try {
                final File dir = FileLoader.checkDirectory(FileLoader.MEDIA_DIR_CACHE);
                if (dir != null) {
                    free = dir.getFreeSpace();
                }
            } catch (Throwable ignore) {
            }
            final long finalFree = free;
            AndroidUtilities.runOnUIThread(() -> callback.run(finalFree));
        });
    }
}
