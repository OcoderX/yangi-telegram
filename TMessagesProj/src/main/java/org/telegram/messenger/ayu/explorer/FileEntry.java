package org.telegram.messenger.ayu.explorer;

import android.text.TextUtils;

import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.Locale;

/**
 * AyuGram File Explorer: one indexed file coming either from the local message/media cache
 * (media_v4) or from a file found on disk inside one of the {@link FileLoader} directories.
 * <p>
 * Instances are immutable enough to be handed to the UI thread once the index is built; only the
 * "downloaded" state fields are updated by {@link FileIndexer} while indexing.
 */
public class FileEntry {

    public static final int CATEGORY_PHOTOS = 0;
    public static final int CATEGORY_VIDEOS = 1;
    public static final int CATEGORY_DOCUMENTS = 2;
    public static final int CATEGORY_MUSIC = 3;
    public static final int CATEGORY_VOICE = 4;
    public static final int CATEGORY_GIFS = 5;
    public static final int CATEGORY_STICKERS = 6;
    public static final int CATEGORY_OTHER = 7;
    public static final int CATEGORY_COUNT = 8;

    /** dialog the file was sent in, 0 when unknown (orphan file on disk) */
    public long dialogId;
    /** message id inside {@link #dialogId}, 0 when unknown */
    public int messageId;
    /** unix time (seconds) */
    public int date;
    public int category = CATEGORY_OTHER;

    /** display name; never null */
    public String name = "";
    /** {@link #name} lowercased, cached for the search field */
    public String lowerName = "";
    /** lowercase extension without the dot, may be empty */
    public String extension = "";
    public String mimeType;

    /** declared size (from the TL object) or, for orphan files, the on-disk length */
    public long size;
    /** real on-disk length, 0 when the file is not downloaded */
    public long onDiskSize;
    public boolean downloaded;
    /** absolute path the file would have / has on disk, may be null when it cannot be resolved */
    public String path;

    /** TL media kept so the row can show a thumbnail and the actions can open / download it */
    public TLRPC.Document document;
    public TLRPC.Photo photo;
    /** the full-size photo size of {@link #photo}, used to resolve the path */
    public TLRPC.PhotoSize photoSize;
    /** the small thumb of {@link #photo} / {@link #document}, used for the list row */
    public TLRPC.PhotoSize thumbSize;

    public File getFile() {
        return TextUtils.isEmpty(path) ? null : new File(path);
    }

    public boolean hasMessage() {
        return dialogId != 0 && messageId != 0;
    }

    /** a stable identity used by the multi-selection */
    public String key() {
        if (!TextUtils.isEmpty(path)) {
            return path;
        }
        return dialogId + "_" + messageId + "_" + name;
    }

    public long sizeForSorting() {
        return onDiskSize > 0 ? onDiskSize : size;
    }

    // ------------------------------------------------------------------ helpers

    public static int categoryOf(TLRPC.MessageMedia media) {
        if (media instanceof TLRPC.TL_messageMediaPhoto) {
            return CATEGORY_PHOTOS;
        }
        if (media != null && media.document != null) {
            return categoryOf(media.document);
        }
        return CATEGORY_OTHER;
    }

    public static int categoryOf(TLRPC.Document document) {
        if (document == null) {
            return CATEGORY_OTHER;
        }
        if (MessageObject.isVoiceDocument(document)) {
            return CATEGORY_VOICE;
        }
        if (MessageObject.isStickerDocument(document) || MessageObject.isAnimatedStickerDocument(document, true) || MessageObject.isVideoSticker(document)) {
            return CATEGORY_STICKERS;
        }
        if (MessageObject.isMusicDocument(document)) {
            return CATEGORY_MUSIC;
        }
        if (MessageObject.isGifDocument(document)) {
            return CATEGORY_GIFS;
        }
        if (MessageObject.isRoundVideoDocument(document) || MessageObject.isVideoDocument(document)) {
            return CATEGORY_VIDEOS;
        }
        final String mime = document.mime_type;
        if (mime != null) {
            final String lower = mime.toLowerCase(Locale.US);
            if (lower.startsWith("image/")) {
                return CATEGORY_PHOTOS;
            }
            if (lower.startsWith("video/")) {
                return CATEGORY_VIDEOS;
            }
            if (lower.startsWith("audio/")) {
                return CATEGORY_MUSIC;
            }
        }
        return CATEGORY_DOCUMENTS;
    }

    /** best-effort category for a file that is only known by its name (orphan on-disk file) */
    public static int categoryOfFileName(String fileName) {
        final String ext = extensionOf(fileName);
        switch (ext) {
            case "jpg":
            case "jpeg":
            case "png":
            case "heic":
            case "bmp":
                return CATEGORY_PHOTOS;
            case "webp":
            case "tgs":
                return CATEGORY_STICKERS;
            case "gif":
                return CATEGORY_GIFS;
            case "mp4":
            case "mkv":
            case "mov":
            case "avi":
            case "webm":
                return CATEGORY_VIDEOS;
            case "mp3":
            case "m4a":
            case "flac":
            case "wav":
                return CATEGORY_MUSIC;
            case "ogg":
            case "oga":
            case "opus":
                return CATEGORY_VOICE;
            case "":
                return CATEGORY_OTHER;
            default:
                return CATEGORY_DOCUMENTS;
        }
    }

    public static String extensionOf(String fileName) {
        if (TextUtils.isEmpty(fileName)) {
            return "";
        }
        final int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) {
            return "";
        }
        String ext = fileName.substring(idx + 1).toLowerCase(Locale.US);
        if (ext.length() > 8) {
            return "";
        }
        return ext;
    }
}
