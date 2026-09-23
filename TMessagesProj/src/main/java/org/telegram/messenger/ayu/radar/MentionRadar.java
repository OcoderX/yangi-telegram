package org.telegram.messenger.ayu.radar;

import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Locale;

/**
 * AyuGram Mention Radar — collects every message that mentions me, replies to me, carries my name
 * or one of my keywords into a single feed.
 * <p>
 * One instance per account. Live collection listens to {@link NotificationCenter#didReceiveNewMessages}
 * and {@link NotificationCenter#messagesDidLoad}; candidates are debounced on the UI thread and then
 * matched + stored on {@link RadarStorage#getQueue()}. No existing Telegram class is patched — the
 * radar is a pure observer.
 * <p>
 * Threading contract:
 * <ul>
 *     <li>observer registration, delegate callbacks and candidate capture: main thread</li>
 *     <li>matching, serialization and every database access: {@link RadarStorage#getQueue()}</li>
 *     <li>reply-target lookups: {@code MessagesStorage.getMessage()} (it hops to storageQueue itself
 *         and blocks the caller, which is safe because we call it from the radar queue)</li>
 * </ul>
 */
public class MentionRadar implements NotificationCenter.NotificationCenterDelegate {

    // ------------------------------------------------------------------ delegate

    public interface RadarDelegate {
        /** new hits landed in the database (or hits were read / deleted) */
        void onRadarHitsChanged(int account, int newHits);

        void onRadarScanProgress(int account, int done, int total, int found);

        void onRadarScanFinished(int account, int found, boolean cancelled, String error);
    }

    // ------------------------------------------------------------------ scan modes

    public static final int SCAN_MENTIONS = 1;
    public static final int SCAN_KEYWORDS = 2;

    private static final int DEBOUNCE_MS = 400;
    private static final int SNIPPET_LENGTH = 220;
    private static final int SEARCH_PAGE = 60;
    /** pages requested per keyword during a history scan */
    private static final int MAX_KEYWORD_PAGES = 8;

    // ------------------------------------------------------------------ instances

    private static final MentionRadar[] instances = new MentionRadar[UserConfig.MAX_ACCOUNT_COUNT];

    public static MentionRadar getInstance(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            account = 0;
        }
        MentionRadar local = instances[account];
        if (local == null) {
            synchronized (MentionRadar.class) {
                local = instances[account];
                if (local == null) {
                    local = instances[account] = new MentionRadar(account);
                }
            }
        }
        return local;
    }

    /** starts the radar on every activated account; safe to call more than once */
    public static void initAll() {
        AndroidUtilities.runOnUIThread(() -> {
            RadarConfig.load();
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                try {
                    if (UserConfig.getInstance(a).isClientActivated() && !UserConfig.getInstance(a).isBotAccount()) {
                        getInstance(a).start();
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        });
    }

    // ------------------------------------------------------------------ state

    private final int currentAccount;
    private boolean started;

    private final ArrayList<RadarDelegate> delegates = new ArrayList<>();

    /** captured on the UI thread, drained on the radar queue */
    private final ArrayList<Candidate> pending = new ArrayList<>();
    private boolean flushScheduled;

    private volatile int unreadCount;
    private volatile boolean unreadCountLoaded;

    private HistoryScan scan;

    private MentionRadar(int account) {
        currentAccount = account;
        RadarConfig.load();
    }

    public int getCurrentAccount() {
        return currentAccount;
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() {
        if (started) {
            return;
        }
        started = true;
        NotificationCenter center = NotificationCenter.getInstance(currentAccount);
        center.addObserver(this, NotificationCenter.didReceiveNewMessages);
        center.addObserver(this, NotificationCenter.messagesDidLoad);
        reloadUnreadCount();
    }

    public void stop() {
        if (!started) {
            return;
        }
        started = false;
        NotificationCenter center = NotificationCenter.getInstance(currentAccount);
        center.removeObserver(this, NotificationCenter.didReceiveNewMessages);
        center.removeObserver(this, NotificationCenter.messagesDidLoad);
    }

    public void addDelegate(RadarDelegate delegate) {
        if (delegate != null && !delegates.contains(delegate)) {
            delegates.add(delegate);
        }
    }

    public void removeDelegate(RadarDelegate delegate) {
        delegates.remove(delegate);
    }

    private void notifyHitsChanged(int newHits) {
        AndroidUtilities.runOnUIThread(() -> {
            for (int i = 0; i < delegates.size(); i++) {
                try {
                    delegates.get(i).onRadarHitsChanged(currentAccount, newHits);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        });
    }

    // ------------------------------------------------------------------ unread count

    /** cached value; {@code 0} until the first {@link #reloadUnreadCount()} completes */
    public int getUnreadCount() {
        if (!unreadCountLoaded) {
            reloadUnreadCount();
        }
        return unreadCount;
    }

    public void reloadUnreadCount() {
        RadarStorage.getQueue().postRunnable(() -> {
            int count = RadarStorage.getInstance(currentAccount).getUnreadCount();
            unreadCount = count;
            unreadCountLoaded = true;
            notifyHitsChanged(0);
        });
    }

    // ------------------------------------------------------------------ feed operations

    /**
     * Loads a page of hits and rebuilds their {@link MessageObject}s (so the feed can render real
     * message cells with the match highlighted) on the radar queue.
     */
    public void loadHits(int kind, int limit, int offset, org.telegram.messenger.Utilities.Callback<ArrayList<RadarHit>> callback) {
        RadarStorage.getQueue().postRunnable(() -> {
            ArrayList<RadarHit> hits = RadarStorage.getInstance(currentAccount).load(kind, limit, offset);
            for (int i = 0; i < hits.size(); i++) {
                inflate(hits.get(i));
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (callback != null) {
                    callback.run(hits);
                }
            });
        });
    }

    /** rebuilds {@link RadarHit#messageObject} from the stored blob; safe to call twice */
    public void inflate(RadarHit hit) {
        if (hit == null || hit.messageObject != null) {
            return;
        }
        try {
            TLRPC.Message message = deserialize(hit.data);
            if (message == null) {
                return;
            }
            if (message.dialog_id == 0) {
                message.dialog_id = hit.dialogId;
            }
            MessageObject messageObject = new MessageObject(currentAccount, message, false, false);
            if (!TextUtils.isEmpty(hit.matched)) {
                messageObject.setQuery(hit.matched);
            }
            hit.messageObject = messageObject;
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public void markRead(long rowId) {
        RadarStorage.getQueue().postRunnable(() -> {
            RadarStorage storage = RadarStorage.getInstance(currentAccount);
            storage.markRead(rowId);
            unreadCount = storage.getUnreadCount();
            unreadCountLoaded = true;
            notifyHitsChanged(0);
        });
    }

    public void markAllRead() {
        RadarStorage.getQueue().postRunnable(() -> {
            RadarStorage storage = RadarStorage.getInstance(currentAccount);
            storage.markAllRead();
            unreadCount = 0;
            unreadCountLoaded = true;
            notifyHitsChanged(0);
        });
    }

    public void deleteHit(long rowId) {
        RadarStorage.getQueue().postRunnable(() -> {
            RadarStorage storage = RadarStorage.getInstance(currentAccount);
            storage.delete(rowId);
            unreadCount = storage.getUnreadCount();
            unreadCountLoaded = true;
            notifyHitsChanged(0);
        });
    }

    public void clearAll() {
        RadarStorage.getQueue().postRunnable(() -> {
            RadarStorage storage = RadarStorage.getInstance(currentAccount);
            storage.clear();
            unreadCount = 0;
            unreadCountLoaded = true;
            notifyHitsChanged(0);
        });
    }

    // ------------------------------------------------------------------ live collection

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (account != currentAccount || !RadarConfig.enabled) {
            return;
        }
        try {
            if (id == NotificationCenter.didReceiveNewMessages) {
                if (args.length < 4) {
                    return;
                }
                boolean scheduled = args[2] instanceof Boolean && (Boolean) args[2];
                int mode = args[3] instanceof Integer ? (Integer) args[3] : 0;
                if (scheduled || mode != 0) {
                    return;
                }
                capture(args[1]);
            } else if (id == NotificationCenter.messagesDidLoad) {
                if (args.length < 15) {
                    return;
                }
                int mode = args[14] instanceof Integer ? (Integer) args[14] : 0;
                if (mode != 0) {
                    return;
                }
                capture(args[2]);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @SuppressWarnings("unchecked")
    private void capture(Object arg) {
        if (!(arg instanceof ArrayList)) {
            return;
        }
        ArrayList<?> list = (ArrayList<?>) arg;
        if (list.isEmpty()) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            Object o = list.get(i);
            if (!(o instanceof MessageObject)) {
                continue;
            }
            MessageObject mo = (MessageObject) o;
            TLRPC.Message message = mo.messageOwner;
            if (message == null || message.id <= 0 || message.action != null) {
                continue;
            }
            long dialogId = mo.getDialogId();
            if (!isDialogWatched(dialogId)) {
                continue;
            }
            if (RadarConfig.skipOutgoing && (message.out || MessageObject.getFromChatId(message) == getMyId())) {
                continue;
            }
            Candidate candidate = new Candidate();
            candidate.dialogId = dialogId;
            candidate.message = message;
            MessageObject reply = mo.replyMessageObject;
            if (reply != null && reply.messageOwner != null) {
                candidate.replyState = (reply.messageOwner.out || MessageObject.getFromChatId(reply.messageOwner) == getMyId())
                        ? Candidate.REPLY_MINE : Candidate.REPLY_FOREIGN;
            }
            pending.add(candidate);
        }
        scheduleFlush();
    }

    private void scheduleFlush() {
        if (flushScheduled || pending.isEmpty()) {
            return;
        }
        flushScheduled = true;
        AndroidUtilities.runOnUIThread(flushRunnable, DEBOUNCE_MS);
    }

    private final Runnable flushRunnable = new Runnable() {
        @Override
        public void run() {
            flushScheduled = false;
            if (pending.isEmpty()) {
                return;
            }
            final ArrayList<Candidate> batch = new ArrayList<>(pending);
            pending.clear();
            final MatchContext context = buildContext();
            RadarStorage.getQueue().postRunnable(() -> process(batch, context));
        }
    };

    private void process(ArrayList<Candidate> batch, MatchContext context) {
        RadarStorage storage = RadarStorage.getInstance(currentAccount);
        ArrayList<RadarHit> hits = new ArrayList<>();
        ArrayList<RadarHit> keywordHits = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            Candidate candidate = batch.get(i);
            if (storage.exists(candidate.dialogId, candidate.message.id, RadarHit.KIND_MENTION)
                    || storage.exists(candidate.dialogId, candidate.message.id, RadarHit.KIND_REPLY)
                    || storage.exists(candidate.dialogId, candidate.message.id, RadarHit.KIND_KEYWORD)
                    || storage.exists(candidate.dialogId, candidate.message.id, RadarHit.KIND_NAME)) {
                continue;
            }
            RadarHit hit = match(candidate, context, true);
            if (hit != null) {
                hits.add(hit);
                if (hit.kind == RadarHit.KIND_KEYWORD) {
                    keywordHits.add(hit);
                }
            }
        }
        if (hits.isEmpty()) {
            return;
        }
        int added = storage.putAll(hits);
        storage.trim(RadarConfig.maxRows);
        unreadCount = storage.getUnreadCount();
        unreadCountLoaded = true;
        notifyHitsChanged(added);
        if (added > 0 && RadarConfig.notifyOnKeyword && !keywordHits.isEmpty()) {
            RadarNotifier.notifyKeywordHits(currentAccount, keywordHits);
        }
    }

    // ------------------------------------------------------------------ matching

    /**
     * Classifies a candidate. A message produces at most one hit; the kind is picked by priority
     * (mention &gt; reply &gt; keyword &gt; name) so the feed never shows the same message twice.
     *
     * @param allowStorageLookup {@code true} to resolve unknown reply targets through MessagesStorage
     *                           (blocking, must not be called from the storage queue)
     */
    private RadarHit match(Candidate candidate, MatchContext context, boolean allowStorageLookup) {
        TLRPC.Message message = candidate.message;
        if (message == null) {
            return null;
        }
        String text = message.message == null ? "" : message.message;
        String lower = text.isEmpty() ? "" : text.toLowerCase(Locale.ROOT);
        // some characters change length when lower-cased (e.g. U+0130); indices are only reusable
        // when the two strings line up
        final boolean indicesUsable = lower.length() == text.length();

        // ---- 1. explicit mention through entities ----
        if (RadarConfig.matchMentions) {
            int[] range = new int[2];
            String matched = findMentionOfMe(message, text, context, range);
            if (matched != null) {
                return build(candidate, RadarHit.KIND_MENTION, matched, text, range[0], range[1]);
            }
        }

        // ---- 2. reply to one of my messages ----
        if (RadarConfig.matchReplies) {
            int replyId = getReplyToMsgId(message);
            if (replyId != 0) {
                int state = candidate.replyState;
                if (state == Candidate.REPLY_UNKNOWN && allowStorageLookup) {
                    state = resolveReplyOwner(candidate.dialogId, replyId, context.myId);
                    candidate.replyState = state;
                }
                if (state == Candidate.REPLY_MINE
                        || (state == Candidate.REPLY_UNKNOWN && message.mentioned)) {
                    return build(candidate, RadarHit.KIND_REPLY, null, text, -1, -1);
                }
            }
        }

        // ---- 3. the server marked this message as mentioning me ----
        if (RadarConfig.matchMentions && message.mentioned) {
            return build(candidate, RadarHit.KIND_MENTION, context.primaryUsername, text, -1, -1);
        }

        if (text.isEmpty()) {
            return null;
        }

        // ---- 4. user keywords ----
        if (RadarConfig.matchKeywords && context.keywords != null) {
            for (int i = 0; i < context.keywords.size(); i++) {
                RadarKeyword keyword = context.keywords.get(i);
                if (!keyword.enabled) {
                    continue;
                }
                int[] range = keyword.find(text, lower);
                if (range != null) {
                    boolean usable = keyword.regex || indicesUsable;
                    return build(candidate, RadarHit.KIND_KEYWORD, keyword.text, text,
                            usable ? range[0] : -1, usable ? range[1] : -1);
                }
            }
        }

        // ---- 5. my first / last name as whole words ----
        if (RadarConfig.matchName && context.names != null) {
            for (int i = 0; i < context.names.size(); i++) {
                String name = context.names.get(i);
                int[] range = RadarKeyword.findPlain(lower, name, true);
                if (range != null) {
                    String matched = indicesUsable ? safeSubstring(text, range[0], range[1]) : name;
                    return build(candidate, RadarHit.KIND_NAME, matched, text,
                            indicesUsable ? range[0] : -1, indicesUsable ? range[1] : -1);
                }
            }
        }
        return null;
    }

    /** @return the matched @username / name, or null; {@code range} receives the match bounds */
    private static String findMentionOfMe(TLRPC.Message message, String text, MatchContext context, int[] range) {
        range[0] = -1;
        range[1] = -1;
        ArrayList<TLRPC.MessageEntity> entities = message.entities;
        if (entities == null || entities.isEmpty()) {
            return null;
        }
        for (int i = 0; i < entities.size(); i++) {
            TLRPC.MessageEntity entity = entities.get(i);
            if (entity == null) {
                continue;
            }
            if (entity instanceof TLRPC.TL_messageEntityMentionName) {
                if (((TLRPC.TL_messageEntityMentionName) entity).user_id == context.myId) {
                    fillRange(range, entity, text);
                    return safeSubstring(text, range[0], range[1]);
                }
            } else if (entity instanceof TLRPC.TL_inputMessageEntityMentionName) {
                TLRPC.InputUser inputUser = ((TLRPC.TL_inputMessageEntityMentionName) entity).user_id;
                if (inputUser != null && inputUser.user_id == context.myId) {
                    fillRange(range, entity, text);
                    return safeSubstring(text, range[0], range[1]);
                }
            } else if (entity instanceof TLRPC.TL_messageEntityMention) {
                fillRange(range, entity, text);
                String mention = safeSubstring(text, range[0], range[1]);
                if (mention == null) {
                    continue;
                }
                String username = mention.startsWith("@") ? mention.substring(1) : mention;
                if (context.usernames != null) {
                    for (int a = 0; a < context.usernames.size(); a++) {
                        if (username.equalsIgnoreCase(context.usernames.get(a))) {
                            return mention;
                        }
                    }
                }
            }
        }
        range[0] = -1;
        range[1] = -1;
        return null;
    }

    private static void fillRange(int[] range, TLRPC.MessageEntity entity, String text) {
        int start = Math.max(0, entity.offset);
        int end = Math.min(text.length(), entity.offset + entity.length);
        range[0] = start <= end ? start : -1;
        range[1] = start <= end ? end : -1;
    }

    private static String safeSubstring(String text, int start, int end) {
        if (text == null || start < 0 || end > text.length() || start >= end) {
            return null;
        }
        return text.substring(start, end);
    }

    private static int getReplyToMsgId(TLRPC.Message message) {
        TLRPC.MessageReplyHeader reply = message.reply_to;
        if (reply == null) {
            return 0;
        }
        return reply.reply_to_msg_id;
    }

    /** blocking lookup of the reply target; must run on the radar queue */
    private int resolveReplyOwner(long dialogId, int replyId, long myId) {
        try {
            TLRPC.Message original = MessagesStorage.getInstance(currentAccount).getMessage(dialogId, replyId);
            if (original == null) {
                return Candidate.REPLY_UNKNOWN;
            }
            if (original.out || MessageObject.getFromChatId(original) == myId) {
                return Candidate.REPLY_MINE;
            }
            return Candidate.REPLY_FOREIGN;
        } catch (Throwable e) {
            FileLog.e(e);
            return Candidate.REPLY_UNKNOWN;
        }
    }

    private RadarHit build(Candidate candidate, int kind, String matched, String text, int start, int end) {
        RadarHit hit = new RadarHit();
        hit.dialogId = candidate.dialogId;
        hit.msgId = candidate.message.id;
        hit.date = candidate.message.date;
        hit.kind = kind;
        hit.matched = matched;
        hit.senderId = MessageObject.getFromChatId(candidate.message);
        hit.read = false;
        hit.data = serialize(candidate.message);

        String source = text == null ? "" : text;
        if (source.length() <= SNIPPET_LENGTH) {
            hit.snippet = source;
            hit.matchStart = start;
            hit.matchEnd = end;
        } else if (start >= 0) {
            int from = Math.max(0, start - SNIPPET_LENGTH / 3);
            int to = Math.min(source.length(), from + SNIPPET_LENGTH);
            from = Math.max(0, to - SNIPPET_LENGTH);
            hit.snippet = (from > 0 ? "…" : "") + source.substring(from, to) + (to < source.length() ? "…" : "");
            int shift = (from > 0 ? 1 : 0) - from;
            hit.matchStart = start + shift;
            hit.matchEnd = end + shift;
        } else {
            hit.snippet = source.substring(0, SNIPPET_LENGTH) + "…";
            hit.matchStart = -1;
            hit.matchEnd = -1;
        }
        return hit;
    }

    // ------------------------------------------------------------------ helpers

    private long getMyId() {
        try {
            return UserConfig.getInstance(currentAccount).getClientUserId();
        } catch (Throwable e) {
            return 0;
        }
    }

    /** true when messages of this dialog should be inspected at all */
    public boolean isDialogWatched(long dialogId) {
        if (dialogId == 0 || DialogObject.isEncryptedDialog(dialogId) || DialogObject.isFolderDialogId(dialogId)) {
            return false;
        }
        if (dialogId == getMyId()) {
            return false;
        }
        if (DialogObject.isUserDialog(dialogId)) {
            return RadarConfig.includePrivateChats;
        }
        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
        boolean broadcast = chat != null && ChatObject.isChannel(chat) && !chat.megagroup;
        if (broadcast ? !RadarConfig.includeChannels : !RadarConfig.includeGroups) {
            return false;
        }
        if (RadarConfig.skipMutedChats) {
            try {
                if (MessagesController.getInstance(currentAccount).isDialogMuted(dialogId, 0)) {
                    return false;
                }
            } catch (Throwable ignore) {
            }
        }
        return true;
    }

    /** must be built on the UI thread; it snapshots my names, usernames and keywords */
    private MatchContext buildContext() {
        MatchContext context = new MatchContext();
        context.myId = getMyId();
        TLRPC.User self = null;
        try {
            self = UserConfig.getInstance(currentAccount).getCurrentUser();
        } catch (Throwable ignore) {
        }
        context.usernames = new ArrayList<>();
        context.names = new ArrayList<>();
        if (self != null) {
            if (!TextUtils.isEmpty(self.username)) {
                context.usernames.add(self.username);
            }
            if (self.usernames != null) {
                for (int i = 0; i < self.usernames.size(); i++) {
                    TLRPC.TL_username u = self.usernames.get(i);
                    if (u != null && u.active && !TextUtils.isEmpty(u.username)) {
                        context.usernames.add(u.username);
                    }
                }
            }
            if (!TextUtils.isEmpty(self.first_name)) {
                context.names.add(self.first_name.toLowerCase(Locale.ROOT));
            }
            if (!TextUtils.isEmpty(self.last_name)) {
                context.names.add(self.last_name.toLowerCase(Locale.ROOT));
            }
            context.primaryUsername = UserObject.getPublicUsername(self);
        }
        context.keywords = new ArrayList<>(RadarConfig.getKeywords());
        return context;
    }

    public static byte[] serialize(TLRPC.Message message) {
        if (message == null) {
            return null;
        }
        try {
            SerializedData data = new SerializedData(message.getObjectSize());
            message.serializeToStream(data);
            byte[] bytes = data.toByteArray();
            data.cleanup();
            return bytes;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    public static TLRPC.Message deserialize(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return null;
        }
        try {
            SerializedData data = new SerializedData(bytes);
            TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
            data.cleanup();
            return message;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    // ------------------------------------------------------------------ history scan

    public boolean isScanning() {
        return scan != null && !scan.finished;
    }

    /**
     * Starts a one-shot history scan.
     *
     * @param modes bit mask of {@link #SCAN_MENTIONS} / {@link #SCAN_KEYWORDS}
     */
    public void startScan(int modes) {
        if (isScanning()) {
            return;
        }
        if (UserConfig.getInstance(currentAccount).isBotAccount()) {
            // messages.search / searchGlobal are bot-forbidden: nothing to scan
            notifyScanFinished(0, true, null);
            return;
        }
        scan = new HistoryScan(modes, buildContext());
        scan.start();
    }

    public void cancelScan() {
        if (scan != null) {
            scan.cancel();
        }
    }

    private void notifyScanProgress(int done, int total, int found) {
        AndroidUtilities.runOnUIThread(() -> {
            for (int i = 0; i < delegates.size(); i++) {
                try {
                    delegates.get(i).onRadarScanProgress(currentAccount, done, total, found);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        });
    }

    private void notifyScanFinished(int found, boolean cancelled, String error) {
        AndroidUtilities.runOnUIThread(() -> {
            for (int i = 0; i < delegates.size(); i++) {
                try {
                    delegates.get(i).onRadarScanFinished(currentAccount, found, cancelled, error);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        });
    }

    /**
     * Sequential, cancellable history scan.
     * <ul>
     *     <li>keywords: {@code messages.searchGlobal} per plain keyword, paged</li>
     *     <li>mentions: {@code messages.search} with {@code inputMessagesFilterMyMentions} per group /
     *         channel dialog (this is also what catches replies to me, because the server flags them
     *         as mentions)</li>
     *     <li>replies and regex keywords cannot be searched server-side; they stay live-only</li>
     * </ul>
     */
    private class HistoryScan {

        private final int modes;
        private final MatchContext context;

        private final ArrayList<String> keywordQueue = new ArrayList<>();
        private final ArrayList<Long> dialogQueue = new ArrayList<>();

        private int totalSteps;
        private int doneSteps;
        private int found;

        private volatile boolean cancelled;
        private volatile boolean finished;
        private int currentRequest;

        // paging state for the current keyword
        private String currentKeyword;
        private int keywordPage;
        private int offsetRate;
        private int offsetId;
        private TLRPC.InputPeer offsetPeer;

        HistoryScan(int modes, MatchContext context) {
            this.modes = modes;
            this.context = context;
        }

        void start() {
            if ((modes & SCAN_KEYWORDS) != 0 && context.keywords != null) {
                for (int i = 0; i < context.keywords.size(); i++) {
                    RadarKeyword keyword = context.keywords.get(i);
                    if (keyword.enabled && !keyword.regex && !TextUtils.isEmpty(keyword.text)) {
                        keywordQueue.add(keyword.text);
                    }
                }
            }
            if ((modes & SCAN_MENTIONS) != 0) {
                try {
                    ArrayList<TLRPC.Dialog> dialogs = MessagesController.getInstance(currentAccount).getAllDialogs();
                    for (int i = 0; i < dialogs.size(); i++) {
                        TLRPC.Dialog dialog = dialogs.get(i);
                        if (dialog == null || DialogObject.isUserDialog(dialog.id)) {
                            continue;
                        }
                        if (isDialogWatched(dialog.id)) {
                            dialogQueue.add(dialog.id);
                        }
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            totalSteps = keywordQueue.size() + dialogQueue.size();
            if (totalSteps == 0) {
                finish(false, null);
                return;
            }
            notifyScanProgress(0, totalSteps, 0);
            next();
        }

        void cancel() {
            if (finished) {
                return;
            }
            cancelled = true;
            if (currentRequest != 0) {
                try {
                    ConnectionsManager.getInstance(currentAccount).cancelRequest(currentRequest, true);
                } catch (Throwable ignore) {
                }
                currentRequest = 0;
            }
            finish(true, null);
        }

        private void finish(boolean wasCancelled, String error) {
            if (finished) {
                return;
            }
            finished = true;
            notifyScanFinished(found, wasCancelled, error);
        }

        private void next() {
            if (cancelled || finished) {
                return;
            }
            if (currentKeyword != null) {
                requestKeyword();
                return;
            }
            if (!keywordQueue.isEmpty()) {
                currentKeyword = keywordQueue.remove(0);
                keywordPage = 0;
                offsetRate = 0;
                offsetId = 0;
                offsetPeer = new TLRPC.TL_inputPeerEmpty();
                requestKeyword();
                return;
            }
            if (!dialogQueue.isEmpty()) {
                requestMentions(dialogQueue.remove(0));
                return;
            }
            finish(false, null);
        }

        private void stepDone() {
            doneSteps++;
            notifyScanProgress(Math.min(doneSteps, totalSteps), totalSteps, found);
        }

        // ---------------- keywords ----------------

        private void requestKeyword() {
            TLRPC.TL_messages_searchGlobal req = new TLRPC.TL_messages_searchGlobal();
            req.q = currentKeyword;
            req.filter = new TLRPC.TL_inputMessagesFilterEmpty();
            req.flags |= 1;
            req.folder_id = 0;
            req.limit = SEARCH_PAGE;
            req.offset_rate = offsetRate;
            req.offset_id = offsetId;
            req.offset_peer = offsetPeer != null ? offsetPeer : new TLRPC.TL_inputPeerEmpty();
            final String keyword = currentKeyword;
            currentRequest = ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
                currentRequest = 0;
                if (cancelled) {
                    return;
                }
                if (error != null || !(response instanceof TLRPC.messages_Messages)) {
                    currentKeyword = null;
                    stepDone();
                    next();
                    return;
                }
                TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                putPeers(res);
                final int size = res.messages.size();
                if (size > 0) {
                    TLRPC.Message last = res.messages.get(size - 1);
                    offsetId = last.id;
                    offsetRate = res.next_rate;
                    offsetPeer = MessagesController.getInstance(currentAccount).getInputPeer(MessageObject.getDialogId(last));
                }
                keywordPage++;
                final boolean more = size >= SEARCH_PAGE && keywordPage < MAX_KEYWORD_PAGES && offsetPeer != null;
                storeMessages(res.messages, RadarHit.KIND_KEYWORD, keyword, () -> {
                    if (cancelled) {
                        return;
                    }
                    if (!more) {
                        currentKeyword = null;
                        stepDone();
                    } else {
                        notifyScanProgress(doneSteps, totalSteps, found);
                    }
                    next();
                });
            });
        }

        // ---------------- mentions ----------------

        private void requestMentions(long dialogId) {
            TLRPC.InputPeer peer = MessagesController.getInstance(currentAccount).getInputPeer(dialogId);
            if (peer == null) {
                stepDone();
                next();
                return;
            }
            TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
            req.peer = peer;
            req.q = "";
            req.filter = new TLRPC.TL_inputMessagesFilterMyMentions();
            req.limit = SEARCH_PAGE;
            req.offset_id = 0;
            currentRequest = ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> {
                currentRequest = 0;
                if (cancelled) {
                    return;
                }
                if (error != null || !(response instanceof TLRPC.messages_Messages)) {
                    stepDone();
                    next();
                    return;
                }
                TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                putPeers(res);
                storeMessages(res.messages, RadarHit.KIND_MENTION, null, () -> {
                    if (cancelled) {
                        return;
                    }
                    stepDone();
                    next();
                });
            });
        }

        // ---------------- shared ----------------

        private void putPeers(TLRPC.messages_Messages res) {
            try {
                MessagesStorage.getInstance(currentAccount).putUsersAndChats(res.users, res.chats, true, true);
                AndroidUtilities.runOnUIThread(() -> {
                    MessagesController.getInstance(currentAccount).putUsers(res.users, false);
                    MessagesController.getInstance(currentAccount).putChats(res.chats, false);
                });
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }

        private void storeMessages(ArrayList<TLRPC.Message> messages, int fallbackKind, String matched, Runnable whenDone) {
            if (messages == null || messages.isEmpty()) {
                AndroidUtilities.runOnUIThread(whenDone);
                return;
            }
            final ArrayList<TLRPC.Message> copy = new ArrayList<>(messages);
            RadarStorage.getQueue().postRunnable(() -> {
                RadarStorage storage = RadarStorage.getInstance(currentAccount);
                ArrayList<RadarHit> hits = new ArrayList<>();
                for (int i = 0; i < copy.size(); i++) {
                    TLRPC.Message message = copy.get(i);
                    if (message == null || message.id <= 0 || message.action != null) {
                        continue;
                    }
                    long dialogId = MessageObject.getDialogId(message);
                    if (!isDialogWatched(dialogId)) {
                        continue;
                    }
                    if (RadarConfig.skipOutgoing && (message.out || MessageObject.getFromChatId(message) == context.myId)) {
                        continue;
                    }
                    Candidate candidate = new Candidate();
                    candidate.dialogId = dialogId;
                    candidate.message = message;
                    // no blocking storage lookups during a scan: keep it responsive
                    RadarHit hit = match(candidate, context, false);
                    if (hit == null && fallbackKind >= 0) {
                        String text = message.message == null ? "" : message.message;
                        hit = build(candidate, fallbackKind, matched, text, -1, -1);
                    }
                    if (hit == null) {
                        continue;
                    }
                    if (fallbackKind == RadarHit.KIND_KEYWORD && hit.matched == null) {
                        hit.matched = matched;
                    }
                    if (!storage.exists(hit.dialogId, hit.msgId, hit.kind)) {
                        hits.add(hit);
                    }
                }
                int added = storage.putAll(hits);
                storage.trim(RadarConfig.maxRows);
                unreadCount = storage.getUnreadCount();
                unreadCountLoaded = true;
                found += added;
                if (added > 0) {
                    notifyHitsChanged(added);
                }
                AndroidUtilities.runOnUIThread(whenDone);
            });
        }
    }

    // ------------------------------------------------------------------ models

    private static class Candidate {
        static final int REPLY_UNKNOWN = 0;
        static final int REPLY_MINE = 1;
        static final int REPLY_FOREIGN = 2;

        long dialogId;
        TLRPC.Message message;
        int replyState = REPLY_UNKNOWN;
    }

    private static class MatchContext {
        long myId;
        String primaryUsername;
        ArrayList<String> usernames;
        /** already lower-cased */
        ArrayList<String> names;
        ArrayList<RadarKeyword> keywords;
    }
}
