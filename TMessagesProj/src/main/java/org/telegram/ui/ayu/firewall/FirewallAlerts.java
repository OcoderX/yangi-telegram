package org.telegram.ui.ayu.firewall;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.CharacterStyle;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.ayu.firewall.Firewall;
import org.telegram.messenger.ayu.firewall.FirewallArchiveInspector;
import org.telegram.messenger.ayu.firewall.FirewallConfig;
import org.telegram.messenger.ayu.firewall.FirewallRules;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.HashSet;

/**
 * The user facing half of the Personal Firewall: the red "this is a virus" alert, the amber warning
 * with "Open anyway", and the guard helpers the ChatActivity / ChatMessageCell hooks call.
 * <p>
 * Every guard returns true when it took over (the caller must return immediately) and false when the
 * action may continue inline.
 */
public class FirewallAlerts {

    /** red used for the virus alert title */
    public static final int COLOR_RED = 0xFFE53935;
    /** amber used for the warning alert title */
    public static final int COLOR_AMBER = 0xFFFFA000;

    /** things the user explicitly let through in this session; cleared when the app dies */
    private static final HashSet<String> allowedOnce = new HashSet<>();

    private FirewallAlerts() {
    }

    // ------------------------------------------------------------------ keys

    private static String documentKey(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return null;
        }
        return "doc:" + messageObject.getDialogId() + ":" + messageObject.getId();
    }

    private static String urlKey(MessageObject messageObject, String url) {
        long dialogId = messageObject == null ? 0 : messageObject.getDialogId();
        int id = messageObject == null ? 0 : messageObject.getId();
        return "url:" + dialogId + ":" + id + ":" + url;
    }

    public static synchronized void allowOnce(String key) {
        if (key != null) {
            allowedOnce.add(key);
        }
    }

    private static synchronized boolean isAllowedOnce(String key) {
        return key != null && allowedOnce.contains(key);
    }

    public static synchronized void resetAllowances() {
        allowedOnce.clear();
    }

    // ------------------------------------------------------------------ guards

    /**
     * Guard for a tapped document (open or start of download).
     *
     * @param proceed what to run when the user is allowed through; may be null
     * @return true when the firewall handled the tap and the caller must return
     */
    public static boolean guardDocumentTap(Context context, Theme.ResourcesProvider resourcesProvider,
                                           int currentAccount, MessageObject messageObject, Runnable proceed) {
        return guardDocumentTap(context, resourcesProvider, currentAccount, messageObject, proceed, true);
    }

    private static boolean guardDocumentTap(Context context, Theme.ResourcesProvider resourcesProvider,
                                            int currentAccount, MessageObject messageObject, Runnable proceed,
                                            boolean mayInspectArchive) {
        try {
            if (context == null || messageObject == null || messageObject.messageOwner == null) {
                return false;
            }
            if (!Firewall.isEnabled()) {
                return false;
            }
            final TLRPC.Document document = messageObject.getDocument();
            if (document == null) {
                return false;
            }
            final String key = documentKey(messageObject);
            if (isAllowedOnce(key)) {
                return false;
            }

            Firewall.Verdict verdict = Firewall.evaluate(currentAccount, messageObject);
            if (verdict.isAllow()) {
                // an archive already on disk still has to be looked into before it may be opened;
                // mayInspectArchive is false on the way back so this can never loop
                final String fileName = Firewall.fileNameOf(document);
                if (mayInspectArchive && FirewallConfig.blockDangerousArchives && FirewallRules.isZipLike(fileName)
                        && (messageObject.mediaExists || messageObject.attachPathExists)
                        && FirewallArchiveInspector.getCachedResult(currentAccount, messageObject.messageOwner, document) == FirewallArchiveInspector.RESULT_UNKNOWN) {
                    boolean started = FirewallArchiveInspector.inspectAsync(currentAccount, messageObject.messageOwner, document, () -> {
                        if (!guardDocumentTap(context, resourcesProvider, currentAccount, messageObject, proceed, false)) {
                            // the archive came back clean: remember it so re-entering the caller
                            // cannot start another inspection pass
                            allowOnce(key);
                            if (proceed != null) {
                                proceed.run();
                            }
                        }
                    });
                    if (started) {
                        return true;
                    }
                }
                return false;
            }

            showVerdict(context, resourcesProvider, currentAccount, messageObject, verdict, key, proceed);
            return true;
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
            return false;
        }
    }

    /** convenience overload for callers that have a fragment */
    public static boolean guardDocumentTap(BaseFragment fragment, MessageObject messageObject, Runnable proceed) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return false;
        }
        return guardDocumentTap(fragment.getParentActivity(), fragment.getResourceProvider(),
                fragment.getCurrentAccount(), messageObject, proceed);
    }

    /**
     * Guard for a tapped link inside a message.
     *
     * @return true when the firewall handled the tap and the caller must return
     */
    public static boolean guardUrlOpen(BaseFragment fragment, MessageObject messageObject, String url,
                                       CharacterStyle span, Runnable proceed) {
        try {
            if (fragment == null || fragment.getParentActivity() == null || url == null) {
                return false;
            }
            if (!Firewall.isEnabled() || !FirewallConfig.blockSuspiciousLinks) {
                return false;
            }
            final String key = urlKey(messageObject, url);
            if (isAllowedOnce(key)) {
                return false;
            }
            final String visibleText = getVisibleText(messageObject, span);
            Firewall.Verdict verdict = Firewall.evaluateUrl(fragment.getCurrentAccount(), messageObject, url, visibleText);
            if (verdict.isAllow()) {
                return false;
            }
            showVerdict(fragment.getParentActivity(), fragment.getResourceProvider(),
                    fragment.getCurrentAccount(), messageObject, verdict, key, proceed);
            return true;
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
            return false;
        }
    }

    /** the text the link is drawn as, used for the "visible domain differs from href" rule */
    public static String getVisibleText(MessageObject messageObject, CharacterStyle span) {
        if (messageObject == null || span == null) {
            return null;
        }
        try {
            String fromText = spanText(messageObject.messageText, span);
            if (fromText != null) {
                return fromText;
            }
            return spanText(messageObject.caption, span);
        } catch (Exception e) {
            return null;
        }
    }

    private static String spanText(CharSequence cs, CharacterStyle span) {
        if (!(cs instanceof Spanned)) {
            return null;
        }
        Spanned spanned = (Spanned) cs;
        int start = spanned.getSpanStart(span);
        int end = spanned.getSpanEnd(span);
        if (start < 0 || end <= start || end > cs.length()) {
            return null;
        }
        return cs.subSequence(start, end).toString();
    }

    // ------------------------------------------------------------------ dialogs

    private static void showVerdict(Context context, Theme.ResourcesProvider resourcesProvider, int currentAccount,
                                    MessageObject messageObject, Firewall.Verdict verdict, String key, Runnable proceed) {
        if (verdict.isBlock()) {
            FirewallConfig.logEvent(verdict.dialogId, verdict.senderId, verdict.reasonKey, verdict.detail);
            showBlockedAlert(context, resourcesProvider, verdict);
        } else {
            showWarningAlert(context, resourcesProvider, verdict, () -> {
                allowOnce(key);
                if (proceed != null) {
                    proceed.run();
                }
            });
        }
    }

    /** the red, no-way-through alert */
    public static void showBlockedAlert(Context context, Theme.ResourcesProvider resourcesProvider, Firewall.Verdict verdict) {
        if (context == null) {
            return;
        }
        final boolean virus = verdict.hardRule;
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(colored(LocaleController.getString(virus ? R.string.AyuFirewallVirusTitle : R.string.AyuFirewallBlockedTitle), COLOR_RED));
        builder.setMessage(verdict.detail == null ? LocaleController.getString(R.string.AyuFirewallBlockedGeneric) : verdict.detail);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), (dialog, which) -> dialog.dismiss());
        if (verdict.canWhitelist()) {
            builder.setNeutralButton(LocaleController.getString(R.string.AyuFirewallTrustSender), (dialog, which) -> {
                FirewallConfig.addToWhitelist(verdict.senderId);
                dialog.dismiss();
            });
        }
        builder.show();
    }

    /** the amber "are you sure" alert with an explicit way through */
    public static void showWarningAlert(Context context, Theme.ResourcesProvider resourcesProvider, Firewall.Verdict verdict, Runnable onOpenAnyway) {
        if (context == null) {
            if (onOpenAnyway != null) {
                onOpenAnyway.run();
            }
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(colored(LocaleController.getString(R.string.AyuFirewallWarnTitle), COLOR_AMBER));
        builder.setMessage(verdict.detail == null ? LocaleController.getString(R.string.AyuFirewallBlockedGeneric) : verdict.detail);
        builder.setPositiveButton(LocaleController.getString(R.string.AyuFirewallOpenAnyway), (dialog, which) -> {
            dialog.dismiss();
            if (onOpenAnyway != null) {
                onOpenAnyway.run();
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (dialog, which) -> dialog.dismiss());
        builder.makeRed(AlertDialog.BUTTON_POSITIVE);
        builder.show();
    }

    private static CharSequence colored(String text, int color) {
        SpannableStringBuilder sb = new SpannableStringBuilder(text == null ? "" : text);
        sb.setSpan(new ForegroundColorSpan(color), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }
}
