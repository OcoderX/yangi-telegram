package org.telegram.messenger.ayu.chatexport;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.iv.RichMessageSimpleConversion;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit, read-only history export. No read acknowledgements or message-cache mutations. */
public final class ChatHistoryExporter {
    public enum Format { HTML, PDF }
    public enum Scope { CHAT, SAVED_DIALOG, MONOFORUM }
    public enum Error { INVALID_OPTIONS, UNSUPPORTED_CHAT, ACCOUNT_CHANGED, NETWORK, RATE_LIMIT, TIMEOUT, STORAGE, SIZE_LIMIT, CANCELLED, BUSY }

    public static final class Options {
        public Format format = Format.HTML;
        public boolean photos = true, videos, voiceMessages, videoMessages, stickers, gifs, files;
        public long maxMediaBytes = 8L * 1024 * 1024;
        public int fromDate, toDate;
        public Map<String, String> labels = new HashMap<>();

        private Options copy() {
            Options o = new Options();
            o.format = format; o.photos = photos; o.videos = videos;
            o.voiceMessages = voiceMessages; o.videoMessages = videoMessages;
            o.stickers = stickers; o.gifs = gifs; o.files = files;
            o.maxMediaBytes = maxMediaBytes; o.fromDate = fromDate; o.toDate = toDate;
            if (labels != null) o.labels.putAll(labels);
            return o;
        }
    }

    public static final class Request {
        public final int account;
        public final long dialogId, topicId, mergeDialogId, ownerId;
        public final String title;
        public final Scope scope;

        public Request(int account, long dialogId, long topicId, long mergeDialogId, String title) {
            this(account, dialogId, topicId, mergeDialogId, title, Scope.CHAT);
        }

        public Request(int account, long dialogId, long topicId, long mergeDialogId, String title, Scope scope) {
            this.account = account; this.dialogId = dialogId; this.topicId = topicId;
            this.mergeDialogId = mergeDialogId; this.title = title == null ? "" : title;
            this.scope = scope;
            ownerId = account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT
                    ? UserConfig.getInstance(account).getClientUserId() : 0;
        }
    }

    public interface Listener {
        void onProgress(int messages, int mediaCompleted);
        void onComplete(File artifact, String mimeType, int messageCount, int skippedMedia);
        void onError(Error error);
    }
    public interface Handle { void cancel(); }

    private static final AtomicBoolean active = new AtomicBoolean();
    // UI-thread owned; shared transfers survive cancellation, but export never starts an
    // unbounded backlog after slow/failed downloads or repeated cancelled exports.
    private static final ArrayList<Transfer> transfers = new ArrayList<>();
    private static final class Transfer {
        final int account;
        final String name;
        Transfer(int account, String name) { this.account = account; this.name = name; }
    }
    private ChatHistoryExporter() { }

    public static Handle start(Request request, Options options, Listener listener) {
        Job job = new Job(request, options == null ? null : options.copy(), listener);
        if (!active.compareAndSet(false, true)) {
            AndroidUtilities.runOnUIThread(() -> listener.onError(Error.BUSY));
            return job;
        }
        Thread thread = new Thread(job, "chat-history-export");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        job.thread = thread;
        thread.start();
        return job;
    }

    private static final class Failure extends RuntimeException {
        final Error error;
        Failure(Error error) { this.error = error; }
    }

    private static final class Job implements Runnable, Handle {
        final Request request;
        final Options options;
        final Listener listener;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final TimeZone zone = TimeZone.getDefault();
        volatile int requestToken;
        volatile Thread thread;
        File work;
        SQLiteDatabase database;
        int messageCount, mediaCompleted, skippedMedia;
        long mediaBytes, recordBytes, lastProgress;

        Job(Request request, Options options, Listener listener) {
            this.request = request; this.options = options; this.listener = listener;
        }

        @Override public void cancel() {
            cancelled.set(true);
            int token = requestToken;
            if (token != 0 && request != null) ConnectionsManager.getInstance(request.account).cancelRequest(token, true);
            Thread t = thread;
            if (t != null) t.interrupt();
            // A FileLoader transfer can have other consumers; never cancel their shared download.
        }

        void check() {
            if (cancelled.get()) throw new Failure(Error.CANCELLED);
            if (request == null || request.ownerId == 0 || request.account < 0 || request.account >= UserConfig.MAX_ACCOUNT_COUNT)
                throw new Failure(Error.ACCOUNT_CHANGED);
            if (UserConfig.getInstance(request.account).getClientUserId() != request.ownerId)
                throw new Failure(Error.ACCOUNT_CHANGED);
        }

        void validate() {
            check();
            TLRPC.User self = UserConfig.getInstance(request.account).getCurrentUser();
            if (self != null && self.bot) throw new Failure(Error.UNSUPPORTED_CHAT);
            if (options == null || options.format == null || options.maxMediaBytes < 0 || options.maxMediaBytes > ExportRules.DISK_LIMIT
                    || options.fromDate < 0 || options.toDate < 0 || options.fromDate != 0 && options.toDate != 0 && options.fromDate > options.toDate
                    || request.title.length() > 4096 || request.scope == null || request.dialogId == 0)
                throw new Failure(Error.INVALID_OPTIONS);
            if (DialogObject.isEncryptedDialog(request.dialogId) || request.mergeDialogId != 0 && DialogObject.isEncryptedDialog(request.mergeDialogId)
                    || request.scope == Scope.CHAT && (request.topicId < 0 || request.topicId > Integer.MAX_VALUE)
                    || request.scope != Scope.CHAT && (request.topicId == 0 || request.mergeDialogId != 0)
                    || request.topicId != 0 && request.mergeDialogId != 0)
                throw new Failure(Error.UNSUPPORTED_CHAT);
        }

        @Override public void run() {
            File artifact = null;
            Error error = null;
            ExportWriters.Writer writer = null;
            try {
                validate();
                File root = new File(ApplicationLoader.applicationContext.getCacheDir(), "chat_exports");
                if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cache directory");
                work = new File(root, "work_" + UUID.randomUUID());
                if (!work.mkdir() || !new File(work, "assets").mkdir()) throw new IOException("Export directory");
                database = SQLiteDatabase.openOrCreateDatabase(new File(work, "history.db"), null);
                database.execSQL("CREATE TABLE history (dialog INTEGER NOT NULL, mid INTEGER NOT NULL, date INTEGER NOT NULL, body TEXT NOT NULL, PRIMARY KEY(dialog,mid))");
                if (request.mergeDialogId != 0 && request.mergeDialogId != request.dialogId) collect(request.mergeDialogId, false);
                collect(request.dialogId, true);
                check();
                database.execSQL("CREATE INDEX history_order ON history(date,dialog,mid)");
                writer = ExportWriters.create(options.format, work, request.title, metadata(), options.labels, this::check);
                try (Cursor cursor = database.rawQuery("SELECT body FROM history ORDER BY date ASC,dialog ASC,mid ASC", null)) {
                    while (cursor.moveToNext()) {
                        check();
                        writer.add(ExportRecord.fromJson(cursor.getString(0)));
                    }
                }
                File finished = writer.finish();
                writer.close(); writer = null;
                check();
                File shareRoot = new File(ApplicationLoader.applicationContext.getFilesDir(), "cache/chat_exports");
                if (!shareRoot.isDirectory() && !shareRoot.mkdirs()) throw new IOException("Share directory");
                artifact = new File(shareRoot, "chat_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8)
                        + (options.format == Format.HTML ? ".zip" : ".pdf"));
                if (!finished.renameTo(artifact)) throw new IOException("Final export move");
            } catch (Failure failure) {
                error = failure.error;
            } catch (ExportWriters.SizeLimitException limit) {
                error = Error.SIZE_LIMIT;
            } catch (IOException | android.database.SQLException exception) {
                error = Error.STORAGE;
            } catch (Throwable exception) {
                error = cancelled.get() ? Error.CANCELLED : Error.STORAGE;
            } finally {
                try {
                    if (writer != null) try { writer.close(); } catch (Throwable ignored) { }
                    if (database != null) try { database.close(); } catch (Throwable ignored) { }
                    try { deleteTree(work); } catch (Throwable ignored) { }
                } finally { active.set(false); }
            }
            final File result = artifact;
            final Error terminalError = error;
            AndroidUtilities.runOnUIThread(() -> {
                Error current = terminalError;
                if (cancelled.get()) current = Error.CANCELLED;
                else if (request != null && request.account >= 0 && request.account < UserConfig.MAX_ACCOUNT_COUNT
                        && UserConfig.getInstance(request.account).getClientUserId() != request.ownerId) current = Error.ACCOUNT_CHANGED;
                if (current != null) {
                    if (result != null) result.delete();
                    listener.onError(current);
                } else listener.onComplete(result, options.format == Format.HTML ? "application/zip" : "application/pdf", messageCount, skippedMedia);
            });
        }

        void collect(long dialog, boolean current) throws IOException {
            int offset = 0;
            for (;;) {
                check();
                TLRPC.messages_Messages page = fetch(dialog, current, offset);
                if (page.messages.isEmpty()) return;
                int[] ids = new int[page.messages.size()];
                for (int i = 0; i < ids.length; i++) ids[i] = page.messages.get(i).id;
                final int next;
                try { next = ExportRules.nextOffset(offset, ids); }
                catch (IOException stalled) { throw new Failure(Error.NETWORK); }
                HashMap<Long, TLRPC.User> users = new HashMap<>();
                HashMap<Long, TLRPC.Chat> chats = new HashMap<>();
                for (TLRPC.User user : page.users) users.put(user.id, user);
                for (TLRPC.Chat chat : page.chats) chats.put(chat.id, chat);
                page.messages.sort(Comparator.comparingInt(m -> m.id));
                HashSet<Integer> seen = new HashSet<>();
                database.beginTransaction();
                try {
                    for (TLRPC.Message message : page.messages) {
                        check();
                        if (message.id <= 0 || offset != 0 && message.id >= offset || !seen.add(message.id)) continue;
                        if (message instanceof TLRPC.TL_messageEmpty) continue;
                        if (!ExportRules.inRange(message.date, options.fromDate, options.toDate)) continue;
                        long peer = message.peer_id == null ? dialog : MessageObject.getDialogId(message);
                        // Saved-history responses retain original peers; ordinary history must match its requested peer.
                        if (request.scope == Scope.CHAT && peer != dialog) throw new Failure(Error.NETWORK);
                        ExportRecord record = record(dialog, message, users, chats);
                        String json = record.toJson();
                        reserve(json.length() * 4L);
                        ContentValues values = new ContentValues();
                        values.put("dialog", dialog); values.put("mid", message.id); values.put("date", message.date); values.put("body", json);
                        database.insertOrThrow("history", null, values);
                        messageCount++;
                        progress(false);
                    }
                    database.setTransactionSuccessful();
                } finally { database.endTransaction(); }
                offset = next;
                if (offset <= 1) return;
                // Do not infer completion from a short page; only exhausted history ends pagination.
            }
        }

        TLRPC.messages_Messages fetch(long dialog, boolean current, int offset) {
            MessagesController controller = MessagesController.getInstance(request.account);
            TLObject query;
            if (current && request.scope != Scope.CHAT) {
                TLRPC.TL_messages_getSavedHistory saved = new TLRPC.TL_messages_getSavedHistory();
                saved.peer = controller.getInputPeer(request.topicId);
                if (request.scope == Scope.MONOFORUM) saved.parent_peer = controller.getInputPeer(dialog);
                saved.limit = 100; saved.offset_id = offset;
                query = saved;
            } else if (current && request.topicId != 0) {
                TLRPC.TL_messages_getReplies replies = new TLRPC.TL_messages_getReplies();
                replies.peer = controller.getInputPeer(dialog); replies.msg_id = (int) request.topicId;
                replies.limit = 100; replies.offset_id = offset;
                query = replies;
            } else {
                TLRPC.TL_messages_getHistory history = new TLRPC.TL_messages_getHistory();
                history.peer = controller.getInputPeer(dialog); history.limit = 100; history.offset_id = offset;
                query = history;
            }
            CountDownLatch latch = new CountDownLatch(1);
            TLObject[] response = new TLObject[1];
            TLRPC.TL_error[] errors = new TLRPC.TL_error[1];
            requestToken = ConnectionsManager.getInstance(request.account).sendRequest(query, (result, failure) -> {
                response[0] = result; errors[0] = failure; latch.countDown();
            }, ConnectionsManager.RequestFlagFailOnServerErrors);
            long deadline = SystemClock.elapsedRealtime() + 60000;
            try {
                while (!latch.await(200, TimeUnit.MILLISECONDS)) {
                    check();
                    if (SystemClock.elapsedRealtime() >= deadline) throw new Failure(Error.TIMEOUT);
                }
                check();
                if (errors[0] != null) {
                    String reason = errors[0].text == null ? "" : errors[0].text;
                    if (reason.startsWith("FLOOD_WAIT") || reason.startsWith("FLOOD_PREMIUM_WAIT")) throw new Failure(Error.RATE_LIMIT);
                    if (reason.contains("BOT_METHOD_INVALID") || reason.contains("CHANNEL_PRIVATE") || reason.contains("CHAT_ADMIN_REQUIRED"))
                        throw new Failure(Error.UNSUPPORTED_CHAT);
                    throw new Failure(Error.NETWORK);
                }
                if (!(response[0] instanceof TLRPC.messages_Messages) || response[0] instanceof TLRPC.TL_messages_messagesNotModified)
                    throw new Failure(Error.NETWORK);
                return (TLRPC.messages_Messages) response[0];
            } catch (InterruptedException interrupted) {
                check(); throw new Failure(Error.CANCELLED);
            } finally {
                if (latch.getCount() != 0) ConnectionsManager.getInstance(request.account).cancelRequest(requestToken, true);
                requestToken = 0;
            }
        }

        ExportRecord record(long dialog, TLRPC.Message message, HashMap<Long, TLRPC.User> users, HashMap<Long, TLRPC.Chat> chats) throws IOException {
            ExportRecord record = new ExportRecord();
            record.dialog = dialog; record.id = message.id; record.date = message.date;
            record.sender = sender(message.from_id, users, chats);
            if (record.sender.isEmpty()) record.sender = message.post_author != null ? message.post_author : request.title;
            String text = message.message == null ? "" : message.message;
            ArrayList<TLRPC.MessageEntity> entities = message.entities;
            StringBuilder meta = new StringBuilder(date(message.date)).append(" · #").append(message.id);
            if (message.edit_date != 0) meta.append("\n").append(label("edited", "Edited")).append(": ").append(date(message.edit_date));
            if (message.reply_to != null && message.reply_to.reply_to_msg_id != 0)
                meta.append("\n").append(label("reply", "Reply to")).append(": #").append(message.reply_to.reply_to_msg_id);
            if (message.fwd_from != null) {
                meta.append("\n").append(label("forwarded", "Forwarded")).append(": ");
                meta.append(message.fwd_from.from_name != null ? message.fwd_from.from_name : sender(message.fwd_from.from_id, users, chats));
                if (message.fwd_from.date != 0) meta.append(" · ").append(date(message.fwd_from.date));
            }
            if (message.rich_message != null) {
                String attachment = label("attachment", "Attachment"), omitted = label("omitted", "Not included");
                String unsupported = label("unsupported", "Unsupported content"), photo = label("photo", "Photo");
                RichMessageSimpleConversion.Labels labels = new RichMessageSimpleConversion.Labels(unsupported,
                        "%1$s: " + omitted, attachment, attachment, attachment, photo, attachment, attachment,
                        attachment, unsupported, unsupported, attachment);
                RichMessageSimpleConversion.Result converted = RichMessageSimpleConversion.convert(message.rich_message, labels);
                if ((converted.warningFlags & RichMessageSimpleConversion.LIMIT_REACHED) != 0) throw new Failure(Error.SIZE_LIMIT);
                text = converted.blocked ? label("unsupported", "Unsupported content") : converted.text;
                entities = converted.entities;
                if ((converted.warningFlags & (RichMessageSimpleConversion.INTERACTIVE_REMOVED
                        | RichMessageSimpleConversion.MEDIA_NOT_ATTACHED | RichMessageSimpleConversion.UNKNOWN_CONTENT)) != 0)
                    meta.append("\n").append(omitted).append(": ").append(unsupported);
            } else if (message instanceof TLRPC.TL_messageService) {
                MessageObject object = new MessageObject(request.account, message, users, chats, false, false);
                text = object.messageText == null ? label("service", "Service message") : object.messageText.toString();
                entities = null;
            }
            if (text.length() > ExportRules.TEXT_LIMIT) throw new Failure(Error.SIZE_LIMIT);
            record.text = text;
            record.html = html(text, entities);
            record.metadata = meta.toString();
            if (message.media != null && !(message.media instanceof TLRPC.TL_messageMediaEmpty)) {
                if (message.media.photo != null) photo(record, message, message.media.photo);
                else if (message.media.document != null) document(record, message, message.media.document);
                else {
                    ExportRecord.Attachment info = new ExportRecord.Attachment();
                    MessageObject object = new MessageObject(request.account, message, users, chats, false, false);
                    info.label = object.messageText == null ? label("unsupported", "Unsupported content") : object.messageText.toString();
                    TLRPC.MessageMedia media = message.media;
                    if (media instanceof TLRPC.TL_messageMediaContact) {
                        info.label += "\n" + value(media.first_name) + " " + value(media.last_name)
                                + "\n" + value(media.phone_number) + "\n" + value(media.vcard);
                    } else if (media instanceof TLRPC.TL_messageMediaPoll) {
                        TLRPC.Poll poll = ((TLRPC.TL_messageMediaPoll) media).poll;
                        if (poll != null) {
                            StringBuilder details = new StringBuilder(poll.question == null ? "" : value(poll.question.text));
                            for (TLRPC.PollAnswer answer : poll.answers) if (answer.text != null) details.append("\n• ").append(value(answer.text.text));
                            info.label = details.toString();
                        }
                    } else if (media.geo != null) {
                        info.label += "\n" + media.geo.lat + ", " + media.geo._long;
                        if (media.title != null) info.label += "\n" + media.title;
                        if (media.address != null) info.label += "\n" + media.address;
                    } else if (media.webpage != null) {
                        info.label += "\n" + value(media.webpage.url) + "\n" + value(media.webpage.title) + "\n" + value(media.webpage.description);
                    }
                    if (info.label.length() > ExportRules.TEXT_LIMIT) throw new Failure(Error.SIZE_LIMIT);
                    info.status = label("omitted", "Not included");
                    info.relativePath = ""; info.mime = "";
                    record.attachments.add(info);
                    skippedMedia++;
                }
            }
            if (message.rich_message != null) {
                for (TLRPC.Photo photo : message.rich_message.photos) photo(record, message, photo);
                for (TLRPC.Document document : message.rich_message.documents) document(record, message, document);
            }
            return record;
        }

        String sender(TLRPC.Peer peer, HashMap<Long, TLRPC.User> users, HashMap<Long, TLRPC.Chat> chats) {
            if (peer == null) return "";
            if (peer.user_id != 0) {
                TLRPC.User user = users.get(peer.user_id);
                if (user == null) return "ID " + peer.user_id;
                return ((user.first_name == null ? "" : user.first_name) + " " + (user.last_name == null ? "" : user.last_name)).trim() + " (" + peer.user_id + ")";
            }
            long id = peer.channel_id != 0 ? peer.channel_id : peer.chat_id;
            TLRPC.Chat chat = chats.get(id);
            return (chat == null ? "ID" : chat.title) + " (" + id + ")";
        }

        void photo(ExportRecord record, TLRPC.Message parent, TLRPC.Photo photo) throws IOException {
            TLRPC.PhotoSize best = null;
            for (TLRPC.PhotoSize size : photo.sizes) {
                if (!(size instanceof TLRPC.TL_photoSize) && !(size instanceof TLRPC.TL_photoSizeProgressive)) continue;
                if (best == null || (long) size.w * size.h > (long) best.w * best.h) best = size;
            }
            long bytes = best == null ? 0 : best.size;
            if (best instanceof TLRPC.TL_photoSizeProgressive && !((TLRPC.TL_photoSizeProgressive) best).sizes.isEmpty())
                bytes = ((TLRPC.TL_photoSizeProgressive) best).sizes.get(((TLRPC.TL_photoSizeProgressive) best).sizes.size() - 1);
            attach(record, parent, best, best == null ? null : ImageLocation.getForPhoto(best, photo), bytes,
                    label("photo", "Photo"), "image/jpeg", "jpg", options.photos, true);
        }

        void document(ExportRecord record, TLRPC.Message parent, TLRPC.Document doc) throws IOException {
            String name = FileLoader.getDocumentFileName(doc);
            if (name == null || name.isEmpty()) name = label("attachment", "Attachment");
            String mime = doc.mime_type == null ? "application/octet-stream" : doc.mime_type;
            boolean selected;
            if (MessageObject.isRoundVideoDocument(doc)) selected = options.videoMessages;
            else if (MessageObject.isVoiceDocument(doc)) selected = options.voiceMessages;
            else if (MessageObject.isStickerDocument(doc) || MessageObject.isAnimatedStickerDocument(doc, true)) selected = options.stickers;
            else if (MessageObject.isGifDocument(doc)) selected = options.gifs;
            else if (MessageObject.isVideoDocument(doc)) selected = options.videos;
            else selected = options.files;
            boolean preview = mime.equals("image/jpeg") || mime.equals("image/png") || mime.equals("image/webp") || mime.equals("image/gif");
            String ext = "bin";
            int dot = name.lastIndexOf('.');
            if (dot >= 0) ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            attach(record, parent, doc, null, doc.size, name, mime, ext, selected, preview);
        }

        void attach(ExportRecord record, TLRPC.Message parent, TLObject media, ImageLocation location, long expected,
                    String name, String mime, String ext, boolean selected, boolean preview) throws IOException {
            ExportRecord.Attachment item = new ExportRecord.Attachment();
            item.label = name; item.mime = mime; item.bytes = Math.max(0, expected); item.preview = preview; item.relativePath = "";
            record.attachments.add(item);
            if (!selected) item.status = label("notSelected", "Not selected");
            else if (options.format == Format.PDF && !preview) item.status = label("pdfDescription", "Attachment description only in PDF");
            else if (expected <= 0 || media == null || parent.media != null && parent.media.ttl_seconds != 0)
                item.status = label("unavailable", "Unavailable");
            else if (expected > options.maxMediaBytes) item.status = label("sizeLimit", "File exceeds size limit");
            else {
                File local = download(parent, media, location, expected);
                if (local == null) item.status = label("downloadFailed", "Download failed or timed out");
                else {
                    check();
                    long actual = local.length();
                    if (actual > options.maxMediaBytes) item.status = label("sizeLimit", "File exceeds size limit");
                    else if (actual != expected) item.status = label("unavailable", "Unavailable");
                    else {
                        String relative = ExportRules.assetName(record.dialog, record.id, record.attachments.size(), ext);
                        File target = new File(work, relative);
                        try (FileInputStream in = new FileInputStream(local); FileOutputStream out = new FileOutputStream(target)) {
                            byte[] buffer = new byte[32768];
                            long copied = 0;
                            for (int n; (n = in.read(buffer)) != -1;) {
                                check();
                                copied += n;
                                if (copied > expected) throw new Failure(Error.STORAGE);
                                reserveMedia(n);
                                out.write(buffer, 0, n);
                            }
                            if (copied != expected) throw new Failure(Error.STORAGE);
                        }
                        item.relativePath = relative; item.status = ""; item.bytes = actual;
                        mediaCompleted++; progress(true);
                        return;
                    }
                }
            }
            // Disabled checkboxes are deliberate exclusions, not failed selected downloads.
            if (selected) skippedMedia++;
        }

        File download(TLRPC.Message parent, TLObject media, ImageLocation location, long expected) {
            FileLoader loader = FileLoader.getInstance(request.account);
            File cached = loader.getPathToAttach(media, true);
            File normal = loader.getPathToAttach(media, false);
            if (complete(normal, expected)) return normal;
            if (complete(cached, expected)) return cached;
            check();
            String fileName = FileLoader.getAttachFileName(media);
            AtomicBoolean rejected = new AtomicBoolean();
            AndroidUtilities.runOnUIThread(() -> {
                try {
                if (!cancelled.get() && UserConfig.getInstance(request.account).getClientUserId() == request.ownerId) {
                    for (int i = transfers.size() - 1; i >= 0; i--) {
                        Transfer transfer = transfers.get(i);
                        if (!FileLoader.getInstance(transfer.account).isLoadingFile(transfer.name)) transfers.remove(i);
                    }
                    if (!loader.isLoadingFile(fileName)) {
                        if (transfers.size() >= 2) { rejected.set(true); return; }
                        transfers.add(new Transfer(request.account, fileName));
                        if (media instanceof TLRPC.Document) loader.loadFile((TLRPC.Document) media, parent, FileLoader.PRIORITY_LOW, 1);
                        else if (location != null) loader.loadFile(location, parent, "jpg", FileLoader.PRIORITY_LOW, 1);
                        else rejected.set(true);
                    }
                }
                } catch (Throwable failure) { rejected.set(true); }
            });
            long deadline = SystemClock.elapsedRealtime() + 60000;
            long nextCheck = 0;
            try {
                while (SystemClock.elapsedRealtime() < deadline) {
                    check();
                    if (complete(normal, expected)) return normal;
                    if (complete(cached, expected)) return cached;
                    if (rejected.get()) return null;
                    if (SystemClock.elapsedRealtime() >= nextCheck) {
                        nextCheck = SystemClock.elapsedRealtime() + 1000;
                        AndroidUtilities.runOnUIThread(() -> {
                            if (!loader.isLoadingFile(fileName)) rejected.set(true);
                        });
                    }
                    Thread.sleep(250);
                }
            } catch (InterruptedException interrupted) { check(); }
            return null;
        }

        boolean complete(File file, long expected) {
            return file != null && file.isFile() && file.length() == expected;
        }

        void reserve(long amount) {
            try { recordBytes = ExportRules.addBytes(recordBytes, amount); ExportRules.addBytes(recordBytes, mediaBytes); }
            catch (IOException full) { throw new Failure(Error.SIZE_LIMIT); }
        }
        void reserveMedia(long amount) {
            try { mediaBytes = ExportRules.addBytes(mediaBytes, amount); ExportRules.addBytes(recordBytes, mediaBytes); }
            catch (IOException full) { throw new Failure(Error.SIZE_LIMIT); }
        }

        String label(String key, String fallback) {
            String value = options.labels.get(key);
            return value == null || value.isEmpty() ? fallback : value;
        }

        String value(String value) { return value == null ? "" : value; }

        String date(int seconds) {
            SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss XXX", Locale.ROOT);
            formatter.setTimeZone(zone);
            return formatter.format(new Date(seconds * 1000L));
        }

        String metadata() {
            String scope = request.scope == Scope.SAVED_DIALOG ? label("scopeSaved", "Saved messages")
                    : request.scope == Scope.MONOFORUM ? label("scopeMonoforum", "Channel direct messages") : label("scopeChat", "Chat");
            return label("scope", "Scope") + ": " + scope + " · " + request.dialogId
                    + (request.topicId == 0 ? "" : " / " + request.topicId)
                    + (request.mergeDialogId == 0 ? "" : " + " + request.mergeDialogId)
                    + "\n" + label("from", "From") + ": " + (options.fromDate == 0 ? "—" : date(options.fromDate))
                    + "\n" + label("to", "To") + ": " + (options.toDate == 0 ? "—" : date(options.toDate))
                    + "\n" + label("timezone", "Time zone") + ": " + zone.getID()
                    + "\n" + label("messages", "Messages") + ": " + messageCount
                    + "\n" + label("exported", "Exported") + ": " + date((int) (System.currentTimeMillis() / 1000))
                    + "\n" + label("omitted", "Not included") + ": " + skippedMedia;
        }

        void progress(boolean force) {
            long now = SystemClock.elapsedRealtime();
            if (!force && now - lastProgress < 250) return;
            lastProgress = now;
            int messages = messageCount, media = mediaCompleted;
            AndroidUtilities.runOnUIThread(() -> {
                if (!cancelled.get() && UserConfig.getInstance(request.account).getClientUserId() == request.ownerId)
                    listener.onProgress(messages, media);
            });
        }
    }

    static String html(String text, ArrayList<TLRPC.MessageEntity> entities) {
        if (entities == null || entities.isEmpty()) return ExportRules.escape(text);
        if (entities.size() > 4096) throw new Failure(Error.SIZE_LIMIT);
        TreeSet<Integer> boundaries = new TreeSet<>(); boundaries.add(0); boundaries.add(text.length());
        ArrayList<TLRPC.MessageEntity> valid = new ArrayList<>();
        for (TLRPC.MessageEntity entity : entities) {
            long end = (long) entity.offset + entity.length;
            if (entity.offset < 0 || entity.length <= 0 || end > text.length()) continue;
            if (entity.offset > 0 && Character.isLowSurrogate(text.charAt(entity.offset)) || end < text.length() && Character.isLowSurrogate(text.charAt((int) end))) continue;
            valid.add(entity); boundaries.add(entity.offset); boundaries.add((int) end);
        }
        StringBuilder out = new StringBuilder();
        int start = 0;
        for (int end : boundaries) {
            if (end <= start) continue;
            StringBuilder closing = new StringBuilder();
            for (TLRPC.MessageEntity entity : valid) {
                if (entity.offset > start || entity.offset + entity.length < end) continue;
                String tag = null, close = null;
                if (entity instanceof TLRPC.TL_messageEntityBold) tag = "b";
                else if (entity instanceof TLRPC.TL_messageEntityItalic) tag = "i";
                else if (entity instanceof TLRPC.TL_messageEntityUnderline) tag = "u";
                else if (entity instanceof TLRPC.TL_messageEntityStrike) tag = "s";
                else if (entity instanceof TLRPC.TL_messageEntityCode || entity instanceof TLRPC.TL_messageEntityPre) tag = "code";
                else if (entity instanceof TLRPC.TL_messageEntityBlockquote) tag = "blockquote";
                else if (entity instanceof TLRPC.TL_messageEntityTextUrl || entity instanceof TLRPC.TL_messageEntityUrl) {
                    String url = entity instanceof TLRPC.TL_messageEntityTextUrl ? entity.url : text.substring(entity.offset, entity.offset + entity.length);
                    if (ExportRules.safeLink(url)) { tag = "a href=\"" + ExportRules.escape(url) + "\""; close = "a"; }
                }
                if (tag != null) {
                    out.append('<').append(tag).append('>');
                    closing.insert(0, "</" + (close == null ? tag : close) + ">");
                }
            }
            out.append(ExportRules.escape(text.substring(start, end))).append(closing);
            if (out.length() > 4 * ExportRules.TEXT_LIMIT) throw new Failure(Error.SIZE_LIMIT);
            start = end;
        }
        return out.toString();
    }

    private static void deleteTree(File file) {
        if (file == null) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
