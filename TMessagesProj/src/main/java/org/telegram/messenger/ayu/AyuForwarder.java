package org.telegram.messenger.ayu;

import android.app.Activity;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * AyuForward.
 * <p>
 * Telegram forbids forwarding / copying / saving content of chats marked with {@code noforwards}
 * (a.k.a. "protected content"), and messages that were deleted on the server (restored by AyuGram
 * from the local database, marked with {@link TLRPC.Message#ayuDeleted}) simply do not exist
 * server-side anymore, so they cannot be forwarded natively either.
 * <p>
 * AyuForward works around both cases by <b>re-creating</b> the message in the target chat: the
 * media file is taken from the local cache (downloading it first if needed) and uploaded as a brand
 * new message, keeping caption, entities and album grouping. Messages that <i>can</i> be forwarded
 * natively still go through the regular forward path so that the forward header is preserved.
 */
public class AyuForwarder {

    /** AyuGram never hides forward / copy / save actions. Flip to {@code false} to restore vanilla behaviour. */
    public static boolean UNRESTRICTED = true;

    private static final int DOWNLOAD_TIMEOUT_SECONDS = 90;

    // ------------------------------------------------------------------
    // restriction helpers - used by the UI to stop hiding actions
    // ------------------------------------------------------------------

    public static boolean isNoForwards(MessageObject messageObject) {
        return !UNRESTRICTED && messageObject != null && isNoForwards(messageObject.messageOwner);
    }

    public static boolean isNoForwards(TLRPC.Message message) {
        return !UNRESTRICTED && message != null && message.noforwards;
    }

    public static boolean isNoForwards(TLRPC.Chat chat) {
        return !UNRESTRICTED && chat != null && chat.noforwards;
    }

    // ------------------------------------------------------------------
    // routing
    // ------------------------------------------------------------------

    /** true when the message cannot be forwarded natively and has to be re-created. */
    public static boolean needsAyuForward(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return false;
        }
        final TLRPC.Message message = messageObject.messageOwner;
        if (message.ayuDeleted) {
            return true;
        }
        if (message.noforwards) {
            return true;
        }
        final long dialogId = messageObject.getDialogId();
        if (dialogId < 0) {
            final TLRPC.Chat chat = MessagesController.getInstance(messageObject.currentAccount).getChat(-dialogId);
            if (chat != null && chat.noforwards) {
                return true;
            }
            if (chat != null && chat.migrated_to != null) {
                final TLRPC.Chat migratedTo = MessagesController.getInstance(messageObject.currentAccount).getChat(chat.migrated_to.channel_id);
                if (migratedTo != null && migratedTo.noforwards) {
                    return true;
                }
            }
        } else if (dialogId > 0) {
            final TLRPC.UserFull userFull = MessagesController.getInstance(messageObject.currentAccount).getUserFull(dialogId);
            if (userFull != null && (userFull.noforwards_peer_enabled || userFull.noforwards_my_enabled)) {
                return true;
            }
        }
        return false;
    }

    public static boolean needsAyuForward(ArrayList<MessageObject> messages) {
        if (messages == null) {
            return false;
        }
        for (int a = 0; a < messages.size(); a++) {
            if (needsAyuForward(messages.get(a))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // public API
    // ------------------------------------------------------------------

    /**
     * @param replyToTopMsg id of the topic / thread root message, or 0. Kept for call-site
     *                      compatibility; use the {@link MessageObject} overload when the object is
     *                      available, the id alone cannot be turned into a reply target.
     */
    public static void forwardMessages(int currentAccount, ArrayList<MessageObject> messages, long toDialogId, long replyToTopMsg, boolean notify, int scheduleDate, Runnable onDone) {
        forwardMessages(currentAccount, messages, toDialogId, (MessageObject) null, notify, scheduleDate, onDone);
    }

    public static void forwardMessages(int currentAccount, ArrayList<MessageObject> messages, long toDialogId, MessageObject replyToTopMsg, boolean notify, int scheduleDate, Runnable onDone) {
        if (messages == null || messages.isEmpty()) {
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        final ArrayList<MessageObject> list = new ArrayList<>(messages);
        final AlertDialog[] progress = new AlertDialog[1];
        AndroidUtilities.runOnUIThread(() -> progress[0] = showProgress());

        // a dedicated thread: forwarding blocks while media is being downloaded, which must not
        // stall the shared queues
        final Thread thread = new Thread(() -> {
            int failed = 0;
            try {
                failed = forwardInternal(currentAccount, list, toDialogId, replyToTopMsg, notify, scheduleDate);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final int failedFinal = failed;
            AndroidUtilities.runOnUIThread(() -> {
                dismissProgress(progress[0]);
                if (failedFinal > 0) {
                    showError(failedFinal);
                }
                if (onDone != null) {
                    onDone.run();
                }
            });
        }, "AyuForwarder");
        thread.setPriority(Thread.MIN_PRIORITY + 2);
        thread.start();
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private static int forwardInternal(int currentAccount, ArrayList<MessageObject> list, long toDialogId, MessageObject replyToTopMsg, boolean notify, int scheduleDate) {
        int failed = 0;
        final ArrayList<MessageObject> nativeBatch = new ArrayList<>();

        int i = 0;
        while (i < list.size()) {
            final MessageObject first = list.get(i);
            final long groupedId = first.messageOwner != null ? first.messageOwner.grouped_id : 0;

            final ArrayList<MessageObject> unit = new ArrayList<>();
            unit.add(first);
            i++;
            if (groupedId != 0) {
                while (i < list.size()) {
                    final MessageObject next = list.get(i);
                    if (next.messageOwner == null || next.messageOwner.grouped_id != groupedId) {
                        break;
                    }
                    unit.add(next);
                    i++;
                }
            }

            boolean needs = false;
            for (int a = 0; a < unit.size(); a++) {
                if (needsAyuForward(unit.get(a))) {
                    needs = true;
                    break;
                }
            }

            if (!needs) {
                nativeBatch.addAll(unit);
                continue;
            }

            flushNativeBatch(currentAccount, nativeBatch, toDialogId, replyToTopMsg, notify, scheduleDate);
            failed += sendUnit(currentAccount, unit, toDialogId, replyToTopMsg, notify, scheduleDate);
        }

        flushNativeBatch(currentAccount, nativeBatch, toDialogId, replyToTopMsg, notify, scheduleDate);
        return failed;
    }

    private static void flushNativeBatch(int currentAccount, ArrayList<MessageObject> batch, long toDialogId, MessageObject replyToTopMsg, boolean notify, int scheduleDate) {
        if (batch.isEmpty()) {
            return;
        }
        final ArrayList<MessageObject> copy = new ArrayList<>(batch);
        batch.clear();
        AndroidUtilities.runOnUIThread(() ->
                SendMessagesHelper.getInstance(currentAccount).sendMessage(copy, toDialogId, false, false, notify, scheduleDate, replyToTopMsg, -1, 0));
    }

    /** @return number of messages that could not be re-created */
    private static int sendUnit(int currentAccount, ArrayList<MessageObject> unit, long toDialogId, MessageObject replyToTopMsg, boolean notify, int scheduleDate) {
        final ArrayList<SendMessagesHelper.SendMessageParams> built = new ArrayList<>();
        final boolean album = unit.size() > 1 && !DialogObject.isEncryptedDialog(toDialogId);
        final long groupId = album ? Utilities.random.nextLong() : 0;

        int failed = 0;
        for (int a = 0; a < unit.size(); a++) {
            final MessageObject messageObject = unit.get(a);
            SendMessagesHelper.SendMessageParams params = null;
            try {
                params = build(currentAccount, messageObject, toDialogId, replyToTopMsg, notify, scheduleDate);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            if (params == null) {
                failed++;
                continue;
            }
            built.add(params);
        }

        if (built.isEmpty()) {
            return failed;
        }

        if (groupId != 0 && built.size() > 1) {
            for (int a = 0; a < built.size(); a++) {
                final SendMessagesHelper.SendMessageParams params = built.get(a);
                if (params.params == null) {
                    params.params = new HashMap<>();
                }
                params.params.put("groupId", "" + groupId);
                if (a == built.size() - 1) {
                    params.params.put("final", "1");
                }
            }
        }

        AndroidUtilities.runOnUIThread(() -> {
            for (int a = 0; a < built.size(); a++) {
                try {
                    SendMessagesHelper.getInstance(currentAccount).sendMessage(built.get(a));
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        });
        return failed;
    }

    private static SendMessagesHelper.SendMessageParams build(int currentAccount, MessageObject messageObject, long toDialogId, MessageObject replyToTopMsg, boolean notify, int scheduleDate) {
        final TLRPC.Message message = messageObject.messageOwner;
        if (message == null) {
            return null;
        }
        final TLRPC.MessageMedia media = MessageObject.getMedia(message);
        final String caption = message.message == null ? "" : message.message;
        final ArrayList<TLRPC.MessageEntity> entities = copyEntities(message.entities);
        final HashMap<String, String> params = new HashMap<>();

        // --- poll ---
        if (media instanceof TLRPC.TL_messageMediaPoll) {
            final TLRPC.TL_messageMediaPoll poll = clonePoll((TLRPC.TL_messageMediaPoll) media);
            if (poll == null) {
                return null;
            }
            return SendMessagesHelper.SendMessageParams.of(poll, toDialogId, null, replyToTopMsg, null, params, notify, scheduleDate, 0);
        }

        // --- contact ---
        if (media instanceof TLRPC.TL_messageMediaContact) {
            final TLRPC.TL_messageMediaContact contact = (TLRPC.TL_messageMediaContact) media;
            final TLRPC.TL_user user = new TLRPC.TL_user();
            user.id = contact.user_id;
            user.phone = contact.phone_number;
            user.first_name = contact.first_name;
            user.last_name = contact.last_name;
            if (!TextUtils.isEmpty(contact.vcard)) {
                final TLRPC.TL_reastrictionReason reason = new TLRPC.TL_reastrictionReason();
                reason.platform = "";
                reason.reason = "";
                reason.text = contact.vcard;
                user.restriction_reason.add(reason);
            }
            return SendMessagesHelper.SendMessageParams.of(user, toDialogId, null, replyToTopMsg, null, params, notify, scheduleDate, 0);
        }

        // --- location / venue ---
        if (media instanceof TLRPC.TL_messageMediaVenue) {
            return SendMessagesHelper.SendMessageParams.of(media, toDialogId, null, replyToTopMsg, null, params, notify, scheduleDate, 0);
        }
        if (media instanceof TLRPC.TL_messageMediaGeo || media instanceof TLRPC.TL_messageMediaGeoLive) {
            final TLRPC.TL_messageMediaGeo geo = new TLRPC.TL_messageMediaGeo();
            geo.geo = media.geo;
            return SendMessagesHelper.SendMessageParams.of(geo, toDialogId, null, replyToTopMsg, null, params, notify, scheduleDate, 0);
        }

        // --- document (video / gif / voice / round / sticker / music / file) ---
        final TLRPC.Document sourceDocument = messageObject.getDocument();

        // stickers live in public sticker sets, the original document may simply be re-sent by
        // reference - re-uploading one would lose (or invalidate) its sticker set
        if (sourceDocument instanceof TLRPC.TL_document && sourceDocument.access_hash != 0 && messageObject.isAnyKindOfSticker()) {
            final File local = resolveLocalFile(currentAccount, messageObject);
            return SendMessagesHelper.SendMessageParams.of((TLRPC.TL_document) sourceDocument, null, local != null ? local.getAbsolutePath() : null,
                    toDialogId, null, replyToTopMsg, caption, entities, null, params, notify, scheduleDate, 0, 0, messageObject, null, false, false);
        }

        if (sourceDocument != null) {
            final File file = ensureDownloaded(currentAccount, messageObject);
            if (file == null) {
                return null;
            }
            final TLRPC.TL_document document = cloneDocument(sourceDocument, file);
            params.put("originalPath", file.getAbsolutePath() + file.length());
            return SendMessagesHelper.SendMessageParams.of(document, null, file.getAbsolutePath(), toDialogId, null, replyToTopMsg,
                    caption, entities, null, params, notify, scheduleDate, 0, 0, null, null, false, false);
        }

        // --- photo ---
        if (media instanceof TLRPC.TL_messageMediaPhoto && media.photo != null) {
            final File file = ensureDownloaded(currentAccount, messageObject);
            if (file == null) {
                return null;
            }
            final TLRPC.TL_photo photo = SendMessagesHelper.getInstance(currentAccount).generatePhotoSizes(file.getAbsolutePath(), null);
            if (photo == null) {
                return null;
            }
            params.put("originalPath", file.getAbsolutePath() + file.length());
            return SendMessagesHelper.SendMessageParams.of(photo, file.getAbsolutePath(), toDialogId, null, replyToTopMsg,
                    caption, entities, null, params, notify, scheduleDate, 0, 0, null, false, false);
        }

        // --- plain text / anything else with text ---
        CharSequence text = message.message;
        if (TextUtils.isEmpty(text)) {
            text = messageObject.messageText;
        }
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        return SendMessagesHelper.SendMessageParams.of(text.toString(), toDialogId, null, replyToTopMsg, null, true,
                entities, null, params, notify, scheduleDate, 0, null, false);
    }

    // ------------------------------------------------------------------
    // media helpers
    // ------------------------------------------------------------------

    private static ArrayList<TLRPC.MessageEntity> copyEntities(ArrayList<TLRPC.MessageEntity> src) {
        if (src == null || src.isEmpty()) {
            return null;
        }
        return new ArrayList<>(src);
    }

    private static TLRPC.TL_messageMediaPoll clonePoll(TLRPC.TL_messageMediaPoll src) {
        if (src == null || src.poll == null) {
            return null;
        }
        final TLRPC.TL_poll poll = new TLRPC.TL_poll();
        poll.id = 0;
        poll.question = src.poll.question;
        poll.answers.addAll(src.poll.answers);
        poll.multiple_choice = src.poll.multiple_choice;
        poll.public_voters = src.poll.public_voters;
        // a quiz cannot be re-created without its correct answer, send it as a regular poll
        poll.quiz = false;
        poll.closed = false;

        final TLRPC.TL_messageMediaPoll media = new TLRPC.TL_messageMediaPoll();
        media.poll = poll;
        media.results = new TLRPC.TL_pollResults();
        return media;
    }

    /**
     * Builds a fresh {@link TLRPC.TL_document} for the local {@code file}, keeping the mime type and
     * every attribute of the original document (so voice notes stay voice notes, round videos stay
     * round, stickers stay stickers, ...) but dropping the remote identity so that
     * {@link SendMessagesHelper} uploads the file instead of re-using the (restricted) reference.
     */
    private static TLRPC.TL_document cloneDocument(TLRPC.Document source, File file) {
        final TLRPC.TL_document document = new TLRPC.TL_document();
        document.id = 0;
        document.access_hash = 0;
        document.dc_id = 0;
        document.file_reference = new byte[0];
        document.date = (int) (System.currentTimeMillis() / 1000);
        document.mime_type = source.mime_type != null ? source.mime_type : "application/octet-stream";
        document.size = file.length();
        document.attributes.addAll(source.attributes);
        boolean hasFileName = false;
        for (int a = 0; a < document.attributes.size(); a++) {
            if (document.attributes.get(a) instanceof TLRPC.TL_documentAttributeFilename) {
                hasFileName = true;
                break;
            }
        }
        if (!hasFileName) {
            final TLRPC.TL_documentAttributeFilename fileName = new TLRPC.TL_documentAttributeFilename();
            fileName.file_name = file.getName();
            document.attributes.add(fileName);
        }
        // only inline (stripped) thumbs may be kept - anything else points at the restricted file
        if (source.thumbs != null) {
            for (int a = 0; a < source.thumbs.size(); a++) {
                final TLRPC.PhotoSize photoSize = source.thumbs.get(a);
                if (photoSize instanceof TLRPC.TL_photoStrippedSize) {
                    document.thumbs.add(photoSize);
                    document.flags |= 1;
                }
            }
        }
        return document;
    }

    private static File resolveLocalFile(int currentAccount, MessageObject messageObject) {
        final TLRPC.Message message = messageObject.messageOwner;
        if (message != null && !TextUtils.isEmpty(message.attachPath)) {
            final File attach = new File(message.attachPath);
            if (attach.exists() && attach.length() > 0) {
                return attach;
            }
        }
        try {
            final File file = FileLoader.getInstance(currentAccount).getPathToMessage(message);
            if (file != null && file.exists() && file.length() > 0) {
                return file;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return null;
    }

    /** Returns the local file for the message media, downloading it (blocking!) when necessary. */
    private static File ensureDownloaded(int currentAccount, MessageObject messageObject) {
        File file = resolveLocalFile(currentAccount, messageObject);
        if (file != null) {
            return file;
        }
        if (messageObject.messageOwner != null && messageObject.messageOwner.ayuDeleted) {
            // the message no longer exists on the server, nothing to download
            return null;
        }

        final TLRPC.Document document = messageObject.getDocument();
        TLRPC.Photo photo = null;
        TLRPC.PhotoSize photoSize = null;
        if (document == null) {
            photo = MessageObject.getPhoto(messageObject.messageOwner);
            if (photo != null) {
                photoSize = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, AndroidUtilities.getPhotoSize(), false, null, true);
            }
            if (photoSize == null) {
                return null;
            }
        }

        final String fileName = document != null ? FileLoader.getAttachFileName(document) : FileLoader.getAttachFileName(photoSize);
        if (TextUtils.isEmpty(fileName)) {
            return null;
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final File[] result = new File[1];
        final NotificationCenter.NotificationCenterDelegate[] observer = new NotificationCenter.NotificationCenterDelegate[1];
        observer[0] = (id, account, args) -> {
            if (account != currentAccount || args == null || args.length == 0) {
                return;
            }
            if (!(args[0] instanceof String) || !fileName.equals(args[0])) {
                return;
            }
            if (id == NotificationCenter.fileLoaded && args.length > 1 && args[1] instanceof File) {
                result[0] = (File) args[1];
            }
            latch.countDown();
        };

        final TLRPC.PhotoSize photoSizeFinal = photoSize;
        final TLRPC.Photo photoFinal = photo;
        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter.getInstance(currentAccount).addObserver(observer[0], NotificationCenter.fileLoaded);
            NotificationCenter.getInstance(currentAccount).addObserver(observer[0], NotificationCenter.fileLoadFailed);
            if (document != null) {
                FileLoader.getInstance(currentAccount).loadFile(document, messageObject, FileLoader.PRIORITY_HIGH, 0);
            } else {
                FileLoader.getInstance(currentAccount).loadFile(ImageLocation.getForObject(photoSizeFinal, photoFinal), messageObject, null, FileLoader.PRIORITY_HIGH, 1);
            }
        });

        try {
            latch.await(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            FileLog.e(e);
        }

        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter.getInstance(currentAccount).removeObserver(observer[0], NotificationCenter.fileLoaded);
            NotificationCenter.getInstance(currentAccount).removeObserver(observer[0], NotificationCenter.fileLoadFailed);
        });

        if (result[0] != null && result[0].exists() && result[0].length() > 0) {
            return result[0];
        }
        return resolveLocalFile(currentAccount, messageObject);
    }

    // ------------------------------------------------------------------
    // UI feedback
    // ------------------------------------------------------------------

    private static AlertDialog showProgress() {
        try {
            final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
            final Activity activity = fragment != null ? fragment.getParentActivity() : null;
            if (activity == null) {
                return null;
            }
            final AlertDialog dialog = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
            dialog.setCanCancel(false);
            dialog.showDelayed(400);
            return dialog;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static void dismissProgress(AlertDialog dialog) {
        if (dialog == null) {
            return;
        }
        try {
            dialog.dismiss();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void showError(int count) {
        try {
            final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
            if (fragment == null) {
                return;
            }
            BulletinFactory.of(fragment)
                    .createErrorBulletin(LocaleController.formatString(R.string.AyuForwardFailed, count))
                    .show();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
