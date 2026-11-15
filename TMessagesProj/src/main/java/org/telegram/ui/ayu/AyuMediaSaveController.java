package org.telegram.ui.ayu;

import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.ayu.AyuForwarder;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.ayu.firewall.FirewallAlerts;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.BooleanSupplier;

/** Explicit document exports. No work runs on chat bind or draw paths. */
public final class AyuMediaSaveController {
    private final BaseFragment host;
    private final HashMap<String, Request> requests = new HashMap<>();

    public AyuMediaSaveController(BaseFragment host) {
        this.host = host;
    }

    public static boolean canSave(MessageObject source) {
        if (source == null || source.messageOwner == null
                || source.getDocument() == null
                || source.getId() <= 0 || source.isSending() || source.isEditing()
                || source.type == MessageObject.TYPE_PAID_MEDIA || source.hasRevealedExtendedMedia()
                || source.isSecretMedia() || source.isEphemeral()
                || source.isVoiceOnce() || source.isRoundOnce() || source.needDrawBluredPreview()
                || source.isHiddenSensitive() && !source.isMediaSpoilersRevealed
                || source.messageOwner.ttl != 0
                || source.messageOwner.media != null && source.messageOwner.media.ttl_seconds != 0) {
            return false;
        }
        final long did = source.getDialogId();
        // Explicit local exports use the existing channel/group content setting. The source
        // message keeps its protection flags; personal peer restrictions still apply.
        if (did < 0 && AyuForwarder.UNRESTRICTED) return true;
        if (source.messageOwner.noforwards) return false;
        if (did < 0) {
            TLRPC.Chat chat = MessagesController.getInstance(source.currentAccount).getChat(-did);
            if (chat != null && chat.noforwards) return false;
        } else if (did > 0) {
            TLRPC.UserFull user = MessagesController.getInstance(source.currentAccount).getUserFull(did);
            if (user != null && (user.noforwards_peer_enabled || user.noforwards_my_enabled)) return false;
        }
        return true;
    }

    private static TLRPC.Document exportDocument(MessageObject source) {
        if (source.qualityToSave != null) return source.qualityToSave;
        if (source.cachedQuality != null && source.cachedQuality.document != null) return source.cachedQuality.document;
        return source.getDocument();
    }

    public void save(MessageObject source, BooleanSupplier stillAllowed, Runnable onSaved) {
        if (!canSave(source) || host.isFinished || host.getParentActivity() == null || !stillAllowed.getAsBoolean()) return;
        final int account = source.currentAccount;
        final TLRPC.Document document = exportDocument(source);
        if (document == null) return;
        final String key = account + ":" + UserConfig.getInstance(account).getClientUserId()
                + ":" + source.getDialogId() + ":" + source.getId() + ":" + document.id;
        if (requests.containsKey(key)) return;
        final Request request = new Request(source, document, key, stillAllowed, onSaved);
        final Runnable begin = () -> {
            if (!request.isValid() || requests.containsKey(key)) return;
            requests.put(key, request);
            request.lookup();
        };
        if (!FirewallAlerts.guardDocumentTap(host.getParentActivity(), host.getResourceProvider(), account, source, document, begin)) begin.run();
    }

    public void cancelAll() {
        for (Request request : new ArrayList<>(requests.values())) request.cancel(false);
        requests.clear();
    }

    private final class Request implements NotificationCenter.NotificationCenterDelegate {
        final MessageObject source;
        final TLRPC.Document document, originalDocument;
        final String key, fileName;
        final int account;
        final long ownerId;
        final BooleanSupplier stillAllowed;
        final Runnable onSaved;
        final Runnable timeout = () -> cancel(true);
        AlertDialog progress;
        boolean observing, cancelled, copyStarted;

        Request(MessageObject source, TLRPC.Document document, String key, BooleanSupplier stillAllowed, Runnable onSaved) {
            this.source = source;
            this.document = document;
            originalDocument = source.getDocument();
            this.key = key;
            this.stillAllowed = stillAllowed;
            this.onSaved = onSaved;
            account = source.currentAccount;
            ownerId = UserConfig.getInstance(account).getClientUserId();
            fileName = FileLoader.getAttachFileName(document);
        }

        boolean isValid() {
            return !cancelled && !host.isFinished && host.getParentActivity() != null
                    && host.getCurrentAccount() == account && source.currentAccount == account
                    && UserConfig.selectedAccount == account
                    && UserConfig.getInstance(account).isClientActivated()
                    && UserConfig.getInstance(account).getClientUserId() == ownerId
                    && source.getDocument() == originalDocument && exportDocument(source) == document
                    && canSave(source) && stillAllowed.getAsBoolean();
        }

        void lookup() {
            AndroidUtilities.runOnUIThread(timeout, 120_000);
            Utilities.globalQueue.postRunnable(() -> {
                File file = findFile();
                AndroidUtilities.runOnUIThread(() -> {
                    if (!isValid()) { cancel(false); return; }
                    if (file != null) ready(file);
                    else download();
                });
            });
        }

        File findFile() {
            FileLoader loader = FileLoader.getInstance(account);
            // A selected quality must never fall back to the original attachment's bytes.
            if (source.qualityToSave == null && source.cachedQuality != null
                    && source.cachedQuality.document == document && source.cachedQuality.isCached()) {
                File cached = new File(source.cachedQuality.uri.getPath());
                if (cached.isFile()) return cached;
            }
            if (document == originalDocument) {
                String path = source.messageOwner.attachPath;
                if (!TextUtils.isEmpty(path)) {
                    File file = new File(path);
                    if (file.isFile()) return file;
                }
                File file = loader.getPathToMessage(source.messageOwner);
                if (file != null && file.isFile()) return file;
            }
            File file = loader.getPathToAttach(document, false);
            if (file != null && file.isFile()) return file;
            file = loader.getPathToAttach(document, true);
            return file != null && file.isFile() ? file : null;
        }

        void download() {
            if (!isValid() || TextUtils.isEmpty(fileName)) { cancel(true); return; }
            NotificationCenter center = NotificationCenter.getInstance(account);
            center.addObserver(this, NotificationCenter.fileLoaded);
            center.addObserver(this, NotificationCenter.fileLoadFailed);
            center.addObserver(this, NotificationCenter.fileLoadProgressChanged);
            observing = true;
            progress = new AlertDialog(host.getParentActivity(), AlertDialog.ALERT_TYPE_LOADING, host.getResourceProvider());
            progress.setMessage(LocaleController.getString(R.string.Downloading));
            progress.setCancelable(true);
            progress.setCancelDialog(true);
            progress.setOnCancelListener(dialog -> cancel(false));
            progress.showDelayed(250);
            try {
                FileLoader.getInstance(account).loadFile(document, source, FileLoader.PRIORITY_NORMAL_UP, 0);
            } catch (Exception e) {
                FileLog.e(e);
                cancel(true);
            }
        }

        void stopWaiting() {
            AndroidUtilities.cancelRunOnUIThread(timeout);
            if (observing) {
                NotificationCenter center = NotificationCenter.getInstance(account);
                center.removeObserver(this, NotificationCenter.fileLoaded);
                center.removeObserver(this, NotificationCenter.fileLoadFailed);
                center.removeObserver(this, NotificationCenter.fileLoadProgressChanged);
                observing = false;
            }
            if (progress != null) {
                progress.dismiss();
                progress = null;
            }
        }

        void cancel(boolean error) {
            if (cancelled) return;
            cancelled = true;
            stopWaiting();
            if (requests.get(key) == this) requests.remove(key);
            // Playback and other clients can share this transfer; cancellation detaches this export.
            if (error && !host.isFinished && host.getParentActivity() != null) {
                BulletinFactory.of(host).createErrorBulletin(LocaleController.getString(R.string.ErrorOccurred)).show();
            }
        }

        void ready(File file) {
            stopWaiting();
            if (!isValid()) { cancel(false); return; }
            // Release while a firewall dialog is open so cancelling it cannot lock out a later try.
            if (requests.get(key) == this) requests.remove(key);
            Utilities.globalQueue.postRunnable(() -> {
                final MessageObject export;
                try {
                    export = exportSnapshot(source, document, file);
                } catch (Exception e) {
                    FileLog.e(e);
                    AndroidUtilities.runOnUIThread(() -> cancel(true));
                    return;
                }
                AndroidUtilities.runOnUIThread(() -> {
                    if (!isValid()) { cancel(false); return; }
                    final Runnable copy = () -> {
                        if (!isValid() || copyStarted || requests.containsKey(key)) return;
                        copyStarted = true;
                        requests.put(key, this);
                        final ArrayList<MessageObject> messages = new ArrayList<>();
                        messages.add(export);
                        MediaController.saveFilesFromMessagesWithResult(host.getParentActivity(), AccountInstance.getInstance(account), messages, (count, copyCancelled) -> {
                            if (requests.get(key) == this) requests.remove(key);
                            if (!isValid() || copyCancelled) return;
                            if (count > 0) onSaved.run();
                            else BulletinFactory.of(host).createErrorBulletin(LocaleController.getString(R.string.ErrorOccurred)).show();
                        });
                    };
                    // A downloaded archive must pass inspection before leaving the app cache.
                    if (!FirewallAlerts.guardDocumentTap(host.getParentActivity(), host.getResourceProvider(), account, export, export.getDocument(), copy)) copy.run();
                });
            });
        }

        @Override
        public void didReceivedNotification(int id, int notificationAccount, Object... args) {
            if (notificationAccount != account || cancelled || !observing || args.length == 0 || !fileName.equals(args[0])) return;
            if (!isValid()) { cancel(false); return; }
            if (id == NotificationCenter.fileLoaded) {
                stopWaiting();
                Utilities.globalQueue.postRunnable(() -> {
                    File file = findFile();
                    AndroidUtilities.runOnUIThread(() -> {
                        if (!isValid()) cancel(false);
                        else if (file == null) cancel(true);
                        else ready(file);
                    });
                });
            } else if (id == NotificationCenter.fileLoadFailed) {
                cancel(true);
            } else if (id == NotificationCenter.fileLoadProgressChanged && progress != null && args.length >= 3) {
                long loaded = ((Number) args[1]).longValue(), total = ((Number) args[2]).longValue();
                if (total > 0) progress.setProgress((int) Math.min(100, loaded * 100.0 / total));
            }
        }
    }

    private static MessageObject exportSnapshot(MessageObject source, TLRPC.Document document, File file) {
        SerializedData output = new SerializedData(document.getObjectSize());
        document.serializeToStream(output);
        SerializedData input = new SerializedData(output.toByteArray());
        TLRPC.Document copy = TLRPC.Document.TLdeserialize(input, input.readInt32(true), true);
        input.cleanup();
        output.cleanup();
        String name = exportFileName(FileLoader.getDocumentFileName(document), file.getName(), document.mime_type, source.isVoice(), source.getId(), document.id);
        copy.attributes.removeIf(attribute -> attribute instanceof TLRPC.TL_documentAttributeFilename);
        TLRPC.TL_documentAttributeFilename filename = new TLRPC.TL_documentAttributeFilename();
        filename.file_name = name;
        copy.attributes.add(filename);
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = source.getId();
        message.realId = source.getRealId();
        message.dialog_id = source.getDialogId();
        message.peer_id = source.messageOwner.peer_id;
        message.from_id = source.messageOwner.from_id;
        message.out = source.messageOwner.out;
        message.date = source.messageOwner.date;
        message.message = "";
        message.attachPath = file.getAbsolutePath();
        message.media = new TLRPC.TL_messageMediaDocument();
        message.media.flags = 1;
        message.media.document = copy;
        message.flags |= TLRPC.MESSAGE_FLAG_HAS_MEDIA;
        if (message.from_id != null) message.flags |= TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
        MessageObject result = new MessageObject(source.currentAccount, message, false, false);
        result.mediaExists = result.attachPathExists = true;
        return result;
    }

    static String exportFileName(String original, String cached, String mime, boolean voice, int messageId, long documentId) {
        String name = original == null ? "" : original.replace('\\', '_').replace('/', '_').replace('\0', '_');
        if (name.equals(".") || name.equals("..")) name = "";
        String extension = "";
        int dot = cached == null ? -1 : cached.lastIndexOf('.');
        if (dot >= 0 && dot < cached.length() - 1) extension = cached.substring(dot + 1);
        if (extension.isEmpty() && mime != null) {
            String guessed = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            if (guessed != null) extension = guessed;
        }
        if (extension.isEmpty() && voice) extension = "ogg";
        if (name.isEmpty()) name = (voice ? "voice_" : "file_") + messageId + "_" + documentId;
        if (name.indexOf('.') < 0 && !extension.isEmpty()) name += "." + extension;
        return name;
    }
}
