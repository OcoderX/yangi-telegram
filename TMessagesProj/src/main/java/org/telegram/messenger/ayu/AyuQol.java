package org.telegram.messenger.ayu;

import org.telegram.messenger.ChatObject;
import org.telegram.tgnet.TLRPC;

/**
 * Small helpers for the AyuGram "quality of life" patches.
 */
public class AyuQol {

    /**
     * Drop-in replacement for {@link ChatObject#isNotInChat(TLRPC.Chat)} at the call sites that
     * decide whether a dialog has to be removed from the local dialog list (kicked / left / forbidden).
     * <p>
     * With {@link AyuConfig#keepKickedChats} enabled the dialog is kept locally, so the cached
     * history stays readable; the chat itself is still read-only because the server flags are untouched.
     */
    public static boolean isNotInChat(TLRPC.Chat chat) {
        if (AyuConfig.keepKickedChats) {
            return false;
        }
        return ChatObject.isNotInChat(chat);
    }

    /** true when the dialog for a kicked/left chat must be kept in the list */
    public static boolean keepDialogOfKickedChat(TLRPC.Chat chat) {
        return AyuConfig.keepKickedChats && chat != null && ChatObject.isNotInChat(chat);
    }
}
