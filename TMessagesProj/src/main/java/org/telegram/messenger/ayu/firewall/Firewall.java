package org.telegram.messenger.ayu.firewall;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

/**
 * AyuGram Personal Firewall.
 * <p>
 * Decides whether a document or a link a message carries may be opened / downloaded at all.
 * Everything here is synchronous and cheap enough for the UI thread; the only expensive check
 * (looking inside a downloaded zip) is done by {@link FirewallArchiveInspector} on a background
 * queue and only its cached result is consulted here.
 * <p>
 * The one rule that never bends: an .exe or .apk smaller than 5 MB is blocked, from anyone,
 * with a red "this is a virus" alert and no way through while the firewall is enabled.
 */
public class Firewall {

    public static final int ALLOW = 0;
    public static final int WARN = 1;
    public static final int BLOCK = 2;

    // reason keys, persisted in the event log
    public static final String REASON_VIRUS = "virus";
    public static final String REASON_EXECUTABLE = "exec";
    public static final String REASON_ARCHIVE = "archive";
    public static final String REASON_ARCHIVE_EXEC = "archive_exec";
    public static final String REASON_LINK = "link";

    public static class Verdict {
        public final int action;
        /** stable key for the persisted log, one of the REASON_* constants */
        public final String reasonKey;
        /** short human label resource for lists / badges */
        public final int reasonStringKey;
        /** ready to show body text explaining the block */
        public final String detail;
        /** true for the 5 MB exe/apk rule: no bypass, no whitelist offer */
        public final boolean hardRule;
        public final long senderId;
        public final long dialogId;

        Verdict(int action, String reasonKey, int reasonStringKey, String detail, boolean hardRule, long senderId, long dialogId) {
            this.action = action;
            this.reasonKey = reasonKey;
            this.reasonStringKey = reasonStringKey;
            this.detail = detail;
            this.hardRule = hardRule;
            this.senderId = senderId;
            this.dialogId = dialogId;
        }

        public boolean isBlock() {
            return action == BLOCK;
        }

        public boolean isWarn() {
            return action == WARN;
        }

        public boolean isAllow() {
            return action == ALLOW;
        }

        /** the whitelist button only makes sense for soft rules and for a real peer */
        public boolean canWhitelist() {
            return !hardRule && senderId != 0;
        }
    }

    private static final Verdict ALLOWED = new Verdict(ALLOW, null, 0, null, false, 0, 0);

    private Firewall() {
    }

    public static Verdict allowed() {
        return ALLOWED;
    }

    public static boolean isEnabled() {
        FirewallConfig.ensureLoaded();
        return FirewallConfig.enabled;
    }

    // ------------------------------------------------------------------ sender classification

    /**
     * "Unknown account": not a contact, or a bot, or an unverified channel, or a scam/fake peer.
     * A whitelisted id is always known. My own messages are always known.
     */
    public static boolean isUnknownSender(int currentAccount, TLRPC.Message message) {
        if (message == null) {
            return true;
        }
        if (message.out) {
            return false;
        }
        long senderId = MessageObject.getFromChatId(message);
        long dialogId = MessageObject.getDialogId(message);
        if (senderId == 0) {
            senderId = dialogId;
        }
        if (FirewallConfig.isWhitelisted(senderId) || FirewallConfig.isWhitelisted(dialogId)) {
            return false;
        }
        if (senderId == 0) {
            return true;
        }
        if (senderId > 0) {
            if (UserObject.isUserSelf(MessagesController.getInstance(currentAccount).getUser(senderId))) {
                return false;
            }
            TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(senderId);
            if (user == null) {
                return true;
            }
            if (user.bot || user.scam || user.fake || user.deleted) {
                return true;
            }
            boolean contact = user.contact || user.mutual_contact
                    || ContactsController.getInstance(currentAccount).contactsDict.containsKey(senderId);
            if (!contact) {
                return true;
            }
            // in a private chat a contact I have never written to is still treated as unknown
            if (dialogId > 0 && !user.mutual_contact) {
                try {
                    TLRPC.Dialog dialog = MessagesController.getInstance(currentAccount).getDialog(dialogId);
                    if (dialog == null || dialog.read_outbox_max_id <= 0) {
                        return true;
                    }
                } catch (Exception ignore) {
                    // dialogs_dict is not thread safe; a failed lookup must not break the rule
                }
            }
            return false;
        }
        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-senderId);
        if (chat == null) {
            return true;
        }
        if (chat.scam || chat.fake) {
            return true;
        }
        // channel posts: trusted only when the channel is verified
        if (chat.broadcast && !chat.megagroup) {
            return !chat.verified;
        }
        return false;
    }

    public static boolean isUnknownSender(int currentAccount, MessageObject messageObject) {
        return messageObject == null || isUnknownSender(currentAccount, messageObject.messageOwner);
    }

    // ------------------------------------------------------------------ documents

    public static Verdict evaluate(int currentAccount, MessageObject messageObject) {
        if (messageObject == null) {
            return ALLOWED;
        }
        return evaluate(currentAccount, messageObject.messageOwner, messageObject.getDocument());
    }

    public static Verdict evaluate(int currentAccount, TLRPC.Message message) {
        if (message == null) {
            return ALLOWED;
        }
        return evaluate(currentAccount, message, MessageObject.getDocument(message));
    }

    public static Verdict evaluate(int currentAccount, TLRPC.Message message, TLRPC.Document document) {
        FirewallConfig.ensureLoaded();
        if (!FirewallConfig.enabled || message == null || document == null) {
            return ALLOWED;
        }
        if (message.out) {
            return ALLOWED;
        }

        // senderIdOf is a plain field read (no controller / dict lookups), cheap enough to compute
        // unconditionally so it can double as the memoization cache's sender guard
        final long senderId = senderIdOf(message);
        final long docId = document.id;
        if (docId != 0) {
            Verdict cached = FirewallVerdictCache.get(docId, senderId);
            if (cached != null) {
                return cached;
            }
        }

        Verdict result = evaluateUncached(currentAccount, message, document, senderId);
        if (docId != 0) {
            FirewallVerdictCache.put(docId, senderId, result);
        }
        return result;
    }

    private static Verdict evaluateUncached(int currentAccount, TLRPC.Message message, TLRPC.Document document, long senderId) {
        final String mime = document.mime_type;
        final long size = document.size;

        // cheap early out on mime alone, before ever touching the file name: a real image / video /
        // audio mime can never be an executable, a hard-rule file, or (per our name-only detection)
        // an archive - this covers the overwhelming majority of documents bound in a chat
        if (FirewallRules.isDefinitelySafeMime(mime)) {
            return ALLOWED;
        }

        final String fileName = fileNameOf(document);

        // cheap early out: stickers, photos, videos, voice messages and plain documents never match
        final String matchedExec = FirewallRules.matchedExecutableExtension(fileName);
        final boolean execMime = FirewallRules.isExecutableMime(mime);
        final boolean archive = FirewallRules.isArchiveName(fileName);
        if (matchedExec == null && !execMime && !archive && !FirewallRules.isHardRuleMime(mime)) {
            return ALLOWED;
        }

        final long dialogId = MessageObject.getDialogId(message);

        // ---- hard rule: exe / apk under 5 MB, from anyone, no bypass
        final boolean hardExt = FirewallRules.hasHardRuleExtension(fileName) || FirewallRules.isHardRuleMime(mime);
        if (hardExt && size > 0 && size < FirewallConfig.HARD_RULE_MAX_SIZE) {
            return new Verdict(BLOCK, REASON_VIRUS, R.string.AyuFirewallShortVirus,
                    LocaleController.formatString(R.string.AyuFirewallReasonVirus,
                            displayName(fileName), AndroidUtilities.formatFileSize(size)),
                    true, senderId, dialogId);
        }

        final boolean unknown = isUnknownSender(currentAccount, message);
        final boolean applySoftRules = !FirewallConfig.unknownSendersOnly || unknown;

        // ---- executables
        if (FirewallConfig.blockExecutables) {
            String matched = matchedExec;
            boolean masked = FirewallRules.hasDoubleExtension(fileName);
            if ((matched != null || execMime) && (applySoftRules || masked)) {
                String ext = matched != null ? matched : FirewallRules.lastExtension(fileName);
                String detail = masked
                        ? LocaleController.formatString(R.string.AyuFirewallReasonDoubleExtension, displayName(fileName), ext)
                        : LocaleController.formatString(R.string.AyuFirewallReasonExecutable, displayName(fileName), ext);
                detail = detail + "\n" + senderLine(unknown) + "\n"
                        + LocaleController.formatString(R.string.AyuFirewallFileSize, AndroidUtilities.formatFileSize(size));
                // a masked double extension is always a block, nobody sends photo.jpg.exe by accident
                int action = (unknown || masked) ? BLOCK : WARN;
                return new Verdict(action, REASON_EXECUTABLE, R.string.AyuFirewallShortExecutable, detail, false, senderId, dialogId);
            }
        }

        // ---- archives
        if (FirewallConfig.blockDangerousArchives && archive) {
            // a zip we already have on disk that carries executables is blocked no matter who sent it
            int inner = FirewallArchiveInspector.getCachedResult(currentAccount, message, document);
            if (inner == FirewallArchiveInspector.RESULT_EXECUTABLE) {
                String detail = LocaleController.formatString(R.string.AyuFirewallReasonArchiveExecutable,
                        displayName(fileName), FirewallArchiveInspector.getCachedEntryName(currentAccount, message, document));
                return new Verdict(BLOCK, REASON_ARCHIVE_EXEC, R.string.AyuFirewallShortArchiveExec, detail, false, senderId, dialogId);
            }
            if (applySoftRules && unknown) {
                String detail = LocaleController.formatString(R.string.AyuFirewallReasonArchive,
                        displayName(fileName), FirewallRules.lastExtension(fileName))
                        + "\n" + senderLine(true) + "\n"
                        + LocaleController.formatString(R.string.AyuFirewallFileSize, AndroidUtilities.formatFileSize(size));
                return new Verdict(BLOCK, REASON_ARCHIVE, R.string.AyuFirewallShortArchive, detail, false, senderId, dialogId);
            }
        }

        return ALLOWED;
    }

    // ------------------------------------------------------------------ links

    public static Verdict evaluateUrl(int currentAccount, MessageObject messageObject, String url, String visibleText) {
        FirewallConfig.ensureLoaded();
        if (!FirewallConfig.enabled || !FirewallConfig.blockSuspiciousLinks || url == null) {
            return ALLOWED;
        }
        final TLRPC.Message message = messageObject == null ? null : messageObject.messageOwner;
        if (message != null && message.out) {
            return ALLOWED;
        }
        final String scheme = FirewallRules.schemeOf(url);
        if (!scheme.isEmpty() && !"http".equals(scheme) && !"https".equals(scheme)) {
            return ALLOWED;
        }
        final String host = FirewallRules.hostOf(url);
        if (host == null) {
            return ALLOWED;
        }

        final long senderId = message == null ? 0 : senderIdOf(message);
        final long dialogId = message == null ? 0 : MessageObject.getDialogId(message);
        // no message context (bot button, inline result): treat as unknown for the rules, but only warn
        final boolean unknown = message == null || isUnknownSender(currentAccount, message);
        final boolean hasSenderContext = message != null;

        int reason = 0;
        boolean alwaysSuspicious = false;
        // a real look-alike domain (fake t.me / telegram.org etc.) is the only link case worth a hard
        // BLOCK; everything else downgrades to WARN below so a flagged link inside a channel post -
        // whose sender is "unknown" almost by definition - stays tappable through "Open anyway"
        // instead of being permanently stuck
        final boolean isLookalikeHost = FirewallRules.isTelegramLookalike(host);

        if (isLookalikeHost) {
            reason = R.string.AyuFirewallReasonLinkLookalike;
            alwaysSuspicious = true;
        } else if (FirewallRules.isPunycode(host)) {
            reason = R.string.AyuFirewallReasonLinkPunycode;
            alwaysSuspicious = true;
        } else if (FirewallRules.hasMixedScript(host)) {
            reason = R.string.AyuFirewallReasonLinkMixedScript;
            alwaysSuspicious = true;
        } else if (FirewallRules.visibleTextDomainMismatch(visibleText, url)) {
            reason = R.string.AyuFirewallReasonLinkMismatch;
            alwaysSuspicious = true;
        } else if (FirewallRules.isRawIp(host)) {
            reason = R.string.AyuFirewallReasonLinkRawIp;
            alwaysSuspicious = true;
        } else if (FirewallRules.isInsecurePhishyPath(url)) {
            reason = R.string.AyuFirewallReasonLinkInsecure;
            alwaysSuspicious = true;
        } else if (unknown && FirewallRules.isShortener(host)) {
            reason = R.string.AyuFirewallReasonLinkShortener;
        }

        if (reason == 0) {
            return ALLOWED;
        }
        if (!alwaysSuspicious && !unknown) {
            return ALLOWED;
        }
        if (FirewallConfig.unknownSendersOnly && !unknown && !alwaysSuspicious) {
            return ALLOWED;
        }

        StringBuilder detail = new StringBuilder();
        detail.append(LocaleController.getString(reason));
        detail.append('\n').append(LocaleController.formatString(R.string.AyuFirewallLinkLine, shorten(url, 160)));
        if (visibleText != null && !visibleText.trim().isEmpty() && !visibleText.trim().equals(url)) {
            detail.append('\n').append(LocaleController.formatString(R.string.AyuFirewallLinkVisibleLine, shorten(visibleText.trim(), 80)));
        }
        detail.append('\n').append(senderLine(unknown));

        // BLOCK is reserved for an unknown sender's link to an actual Telegram look-alike domain;
        // every other unknown-sender reason (punycode, mixed script, mismatch, raw ip, insecure
        // phishy path, shortener) is only a WARN with an "Open anyway" way through
        final int action = hasSenderContext && unknown && isLookalikeHost ? BLOCK : WARN;
        return new Verdict(action, REASON_LINK, R.string.AyuFirewallShortLink,
                detail.toString(), false, senderId, dialogId);
    }

    // ------------------------------------------------------------------ auto download

    /** true when the firewall forbids this document from silently landing on disk */
    public static boolean blocksAutoDownload(int currentAccount, TLRPC.Message message) {
        FirewallConfig.ensureLoaded();
        if (!FirewallConfig.enabled || message == null || message.out) {
            return false;
        }
        TLRPC.Document document = MessageObject.getDocument(message);
        if (document == null) {
            return false;
        }
        try {
            return evaluate(currentAccount, message, document).isBlock();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean blocksAutoDownload(int currentAccount, MessageObject messageObject) {
        return messageObject != null && blocksAutoDownload(currentAccount, messageObject.messageOwner);
    }

    /** same, but for a document that does not come from {@code message.media} (poll attached media) */
    public static boolean blocksAutoDownload(int currentAccount, TLRPC.Message message, TLRPC.Document document) {
        FirewallConfig.ensureLoaded();
        if (!FirewallConfig.enabled || message == null || message.out || document == null) {
            return false;
        }
        try {
            return evaluate(currentAccount, message, document).isBlock();
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ helpers

    public static long senderIdOf(TLRPC.Message message) {
        if (message == null) {
            return 0;
        }
        long senderId = MessageObject.getFromChatId(message);
        return senderId != 0 ? senderId : MessageObject.getDialogId(message);
    }

    public static String fileNameOf(TLRPC.Document document) {
        if (document == null) {
            return "";
        }
        String name = FileLoader.getDocumentFileName(document);
        return name == null ? "" : name;
    }

    private static String displayName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return LocaleController.getString(R.string.AyuFirewallUnnamedFile);
        }
        return shorten(fileName, 64);
    }

    private static String senderLine(boolean unknown) {
        return LocaleController.getString(unknown ? R.string.AyuFirewallSenderUnknown : R.string.AyuFirewallSenderKnown);
    }

    private static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + (char) 0x2026;
    }

    /** short label for the persisted log */
    public static int reasonLabel(String reasonKey) {
        if (REASON_VIRUS.equals(reasonKey)) {
            return R.string.AyuFirewallShortVirus;
        }
        if (REASON_EXECUTABLE.equals(reasonKey)) {
            return R.string.AyuFirewallShortExecutable;
        }
        if (REASON_ARCHIVE.equals(reasonKey)) {
            return R.string.AyuFirewallShortArchive;
        }
        if (REASON_ARCHIVE_EXEC.equals(reasonKey)) {
            return R.string.AyuFirewallShortArchiveExec;
        }
        return R.string.AyuFirewallShortLink;
    }
}
