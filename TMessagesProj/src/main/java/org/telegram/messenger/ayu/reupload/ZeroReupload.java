package org.telegram.messenger.ayu.reupload;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.BulletinFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AyuGram "Zero-Reupload": when a file the account already put on Telegram's servers is sent again,
 * send the server copy instead of uploading the bytes a second time.
 * <p>
 * Telegram already does this, but only keyed by {@code path + length + lastModified}, so the very same
 * file coming from a different folder, a re-download or another app is uploaded again. This class adds
 * a content (SHA-256) keyed index on top of exactly the same storage format, which means:
 * <ul>
 *     <li>hits are fed straight back into the stock send pipeline (a non-zero {@code access_hash}
 *     makes {@code SendMessagesHelper} build a {@code TL_inputMediaDocument} / reuse the photo instead
 *     of an uploaded-media constructor, so captions, entities, attributes, spoilers, reply and forward
 *     context are untouched);</li>
 *     <li>the stored parent string has the same {@code "sent_<channel>_<id>_<dialog>_<type>_<size>"}
 *     shape Telegram uses, so {@code FileRefController} refreshes an expired {@code file_reference}
 *     with no extra code, and a hard failure simply falls back to a normal upload.</li>
 * </ul>
 * Everything is keyed by account: {@code access_hash} values are only valid for the account that
 * received them.
 */
public class ZeroReupload {

    private static final int PENDING_CACHE_SIZE = 128;
    private static final int RECEIVED_CACHE_SIZE = 256;
    private static final long BULLETIN_THROTTLE_MS = 3000L;

    /** mangled "originalPath" key -> fingerprint of the file that is being sent under it */
    private static final LinkedHashMap<String, Pending> pending = new LinkedHashMap<String, Pending>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Pending> eldest) {
            return size() > PENDING_CACHE_SIZE;
        }
    };

    /** "account:attachFileName" -> the received document we may index once it finishes downloading */
    private static final LinkedHashMap<String, Received> receivedCandidates = new LinkedHashMap<String, Received>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Received> eldest) {
            return size() > RECEIVED_CACHE_SIZE;
        }
    };

    private static final boolean[] observerInstalled = new boolean[UserConfig.MAX_ACCOUNT_COUNT];
    private static final NotificationCenter.NotificationCenterDelegate[] observers =
            new NotificationCenter.NotificationCenterDelegate[UserConfig.MAX_ACCOUNT_COUNT];

    private static long lastBulletinTime;

    private ZeroReupload() {
    }

    private static class Pending {
        final String hash;
        final long size;

        Pending(String hash, long size) {
            this.hash = hash;
            this.size = size;
        }
    }

    private static class Received {
        final TLRPC.Document document;
        final String parent;
        final int type;

        Received(TLRPC.Document document, String parent, int type) {
            this.document = document;
            this.parent = parent;
            this.type = type;
        }
    }

    // ------------------------------------------------------------------ send side

    /**
     * Content-hash counterpart of {@code MessagesStorage.getSentFile}. Call it only after the stock
     * lookup missed, from a background thread (every {@code prepareSending*} already is one).
     *
     * @param currentAccount  the sending account
     * @param realPath        an existing local file; content:// sources are already copied to a real
     *                        path by {@code MediaController.copyFileToCache} before this point
     * @param originalPathKey the mangled key the stock cache uses for this send (goes into
     *                        {@code params["originalPath"]}); may be null, then the send is not indexed
     * @param type            one of {@code MessagesStorage.SENT_FILE_TYPE_*}
     * @return {@code {TLRPC.Document|TLRPC.Photo, String parent}} or null
     */
    public static Object[] getSentFile(int currentAccount, String realPath, String originalPathKey, int type) {
        try {
            ZeroReuploadConfig.ensureLoaded();
            if (!ZeroReuploadConfig.enabled) {
                return null;
            }
            if (realPath == null || realPath.length() == 0) {
                return null;
            }
            final File file = new File(realPath);
            if (!file.exists() || !file.isFile()) {
                return null;
            }
            final long size = file.length();
            if (size < ZeroReuploadConfig.getMinSizeBytes()) {
                return null;
            }
            final String hash = FileFingerprint.of(file);
            if (hash == null) {
                return null;
            }
            if (originalPathKey != null) {
                synchronized (pending) {
                    pending.put(originalPathKey, new Pending(hash, size));
                }
            }
            final Object[] row = ReuploadIndex.getInstance(currentAccount).get(hash, size, type);
            if (row == null || row[0] == null) {
                return null;
            }
            final int account = currentAccount;
            ReuploadIndex.getQueue().postRunnable(() -> ReuploadIndex.getInstance(account).bumpReuse(hash, size, type));
            ZeroReuploadConfig.addSaved(size);
            showHitBulletin();
            ensureObserver(currentAccount);
            return row;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * Content-hash counterpart of {@code MessagesStorage.putSentFile}: called right after the stock one
     * from {@code SendMessagesHelper.updateMediaPaths}, i.e. once the server confirmed the upload and
     * handed us the real media. The fingerprint was computed during {@code prepareSending*} and is
     * looked up by the very same mangled key the stock cache uses.
     */
    public static void putSentFile(int currentAccount, String originalPathKey, TLObject file, int type, String parent) {
        try {
            ZeroReuploadConfig.ensureLoaded();
            if (!ZeroReuploadConfig.enabled) {
                return;
            }
            if (originalPathKey == null || file == null) {
                return;
            }
            final Pending p;
            synchronized (pending) {
                p = pending.get(originalPathKey);
            }
            if (p == null) {
                return;
            }
            String mime = null;
            String name = null;
            if (file instanceof TLRPC.Document) {
                TLRPC.Document document = (TLRPC.Document) file;
                mime = document.mime_type;
                name = FileLoader.getDocumentFileName(document);
            }
            final String mimeFinal = mime;
            final String nameFinal = name;
            final int account = currentAccount;
            ReuploadIndex.getQueue().postRunnable(() ->
                    ReuploadIndex.getInstance(account).put(p.hash, p.size, type, file, parent, mimeFinal, nameFinal));
            ensureObserver(currentAccount);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** the parent string Telegram itself stores for a sent file, reused verbatim for file-ref refresh */
    public static String buildParent(TLRPC.Message message, int messageType, long mediaSize) {
        if (message == null) {
            return "";
        }
        final long channelId = message.peer_id != null ? message.peer_id.channel_id : 0;
        final long dialogId = message.peer_id != null ? DialogObject.getPeerDialogId(message.peer_id) : 0;
        return "sent_" + channelId + "_" + message.id + "_" + dialogId + "_" + messageType + "_" + mediaSize;
    }

    // ------------------------------------------------------------------ receive side (optional)

    /**
     * Installs the per-account observers used by the optional "also index received files" mode.
     * Safe to call repeatedly and from any thread.
     */
    public static void ensureObserver(int currentAccount) {
        if (currentAccount < 0 || currentAccount >= observerInstalled.length) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            try {
                if (observerInstalled[currentAccount]) {
                    return;
                }
                observerInstalled[currentAccount] = true;
                NotificationCenter.NotificationCenterDelegate delegate = (id, account, args) -> {
                    try {
                        if (id == NotificationCenter.didReceiveNewMessages) {
                            if (args != null && args.length > 1 && args[1] instanceof ArrayList) {
                                noteMessages(account, (ArrayList<?>) args[1]);
                            }
                        } else if (id == NotificationCenter.messagesDidLoad) {
                            if (args != null && args.length > 2 && args[2] instanceof ArrayList) {
                                noteMessages(account, (ArrayList<?>) args[2]);
                            }
                        } else if (id == NotificationCenter.fileLoaded) {
                            if (args != null && args.length > 0 && args[0] instanceof String) {
                                onFileLoaded(account, (String) args[0]);
                            }
                        }
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                };
                observers[currentAccount] = delegate;
                NotificationCenter.getInstance(currentAccount).addObserver(delegate, NotificationCenter.didReceiveNewMessages);
                NotificationCenter.getInstance(currentAccount).addObserver(delegate, NotificationCenter.messagesDidLoad);
                NotificationCenter.getInstance(currentAccount).addObserver(delegate, NotificationCenter.fileLoaded);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    private static void noteMessages(int account, ArrayList<?> objects) {
        ZeroReuploadConfig.ensureLoaded();
        if (!ZeroReuploadConfig.enabled || !ZeroReuploadConfig.indexReceived || objects == null) {
            return;
        }
        final long minSize = ZeroReuploadConfig.getMinSizeBytes();
        for (int a = 0; a < objects.size(); a++) {
            Object object = objects.get(a);
            if (!(object instanceof MessageObject)) {
                continue;
            }
            final MessageObject messageObject = (MessageObject) object;
            final TLRPC.Document document = messageObject.getDocument();
            if (document == null || document.access_hash == 0 || document.size < minSize) {
                continue;
            }
            if (messageObject.messageOwner == null || messageObject.messageOwner.id <= 0) {
                continue;
            }
            final int type = (MessageObject.isVideoDocument(document) || MessageObject.isGifDocument(document))
                    ? MessagesStorage.SENT_FILE_TYPE_VIDEO
                    : MessagesStorage.SENT_FILE_TYPE_AUDIO;
            final String parent = buildParent(messageObject.messageOwner, messageObject.type, messageObject.getSize());
            final String fileName = FileLoader.getAttachFileName(document);
            if (fileName == null || fileName.length() == 0) {
                continue;
            }
            synchronized (receivedCandidates) {
                receivedCandidates.put(account + ":" + fileName, new Received(document, parent, type));
            }
            if (messageObject.mediaExists) {
                indexReceived(account, fileName);
            }
        }
    }

    private static void onFileLoaded(int account, String fileName) {
        ZeroReuploadConfig.ensureLoaded();
        if (!ZeroReuploadConfig.enabled || !ZeroReuploadConfig.indexReceived) {
            return;
        }
        indexReceived(account, fileName);
    }

    private static void indexReceived(int account, String fileName) {
        final Received received;
        synchronized (receivedCandidates) {
            received = receivedCandidates.get(account + ":" + fileName);
        }
        if (received == null || received.document == null) {
            return;
        }
        ReuploadIndex.getQueue().postRunnable(() -> {
            try {
                final File file = FileLoader.getInstance(account).getPathToAttach(received.document, false);
                if (file == null || !file.exists() || file.length() != received.document.size) {
                    return;
                }
                final String hash = FileFingerprint.of(file);
                if (hash == null) {
                    return;
                }
                if (ReuploadIndex.getInstance(account).get(hash, file.length(), received.type) != null) {
                    return;
                }
                ReuploadIndex.getInstance(account).put(hash, file.length(), received.type, received.document,
                        received.parent, received.document.mime_type, FileLoader.getDocumentFileName(received.document));
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    // ------------------------------------------------------------------ misc

    private static void showHitBulletin() {
        if (!ZeroReuploadConfig.showBulletin) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - lastBulletinTime < BULLETIN_THROTTLE_MS) {
            return;
        }
        lastBulletinTime = now;
        AndroidUtilities.runOnUIThread(() -> {
            try {
                BulletinFactory.global()
                        .createSimpleBulletin(R.raw.chats_infotip, LocaleController.getString(R.string.AyuZeroReuploadSentInstantly))
                        .show();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /**
     * Drops the indexed row behind a reused media that the server has just rejected, so the very next
     * attempt uploads the file for real instead of replaying the same dead row forever.
     * <p>
     * Call it from the FILE_REFERENCE_EXPIRED handling in {@code SendMessagesHelper} once
     * {@code FileRefController} has had its chance. {@code media} is the {@code InputMedia} that was
     * sent (or the stored Document/Photo); anything else is ignored. Safe on any thread.
     */
    public static void invalidate(int currentAccount, TLObject media) {
        try {
            final long fileId = inputFileIdOf(media);
            if (fileId == 0) {
                return;
            }
            ReuploadIndex.getQueue().postRunnable(() -> {
                if (ReuploadIndex.getInstance(currentAccount).removeByFileId(fileId)) {
                    FileLog.d("ayu-reupload: dropped stale index row for file id " + fileId);
                }
            });
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * {@link #invalidate(int, TLObject)} for a send request whose media we may have handed out from
     * the index. Call it while {@code req} still holds the rejected media, i.e. before the stock
     * fallback swaps in {@code delayedMessage.inputUploadMedia}.
     */
    public static void invalidateForRequest(int currentAccount, TLObject req) {
        try {
            if (req instanceof TLRPC.TL_messages_sendMedia) {
                invalidate(currentAccount, ((TLRPC.TL_messages_sendMedia) req).media);
            } else if (req instanceof TLRPC.TL_messages_editMessage) {
                invalidate(currentAccount, ((TLRPC.TL_messages_editMessage) req).media);
            } else if (req instanceof TLRPC.TL_ephemeral_sendMessage) {
                invalidate(currentAccount, ((TLRPC.TL_ephemeral_sendMessage) req).media);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** remote file id behind an InputMedia / InputDocument / InputPhoto, or a stored Document/Photo */
    private static long inputFileIdOf(TLObject object) {
        if (object instanceof TLRPC.TL_inputMediaDocument) {
            final TLRPC.InputDocument id = ((TLRPC.TL_inputMediaDocument) object).id;
            return id != null ? id.id : 0;
        }
        if (object instanceof TLRPC.TL_inputMediaPhoto) {
            final TLRPC.InputPhoto id = ((TLRPC.TL_inputMediaPhoto) object).id;
            return id != null ? id.id : 0;
        }
        if (object instanceof TLRPC.InputDocument) {
            return ((TLRPC.InputDocument) object).id;
        }
        if (object instanceof TLRPC.InputPhoto) {
            return ((TLRPC.InputPhoto) object).id;
        }
        return ReuploadIndex.fileIdOf(object);
    }

    /** wipes the index of one account and the in-memory fingerprint caches */
    public static void clear(int currentAccount) {
        synchronized (pending) {
            pending.clear();
        }
        synchronized (receivedCandidates) {
            receivedCandidates.clear();
        }
        FileFingerprint.invalidateCache();
        ReuploadIndex.getQueue().postRunnable(() -> ReuploadIndex.getInstance(currentAccount).clear());
    }
}
