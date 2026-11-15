package org.telegram.ui.ayu;

import android.util.LongSparseArray;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

/** Session-only presentation state. No preferences, account-stack enumeration or network work. */
public final class OxUnreadByThemConfig {
    public static final long NO_SCOPE = -1;
    private static final long ALL_SCOPE = 0;
    private static final long CATEGORY_SCOPE = 1L << 32;
    private static final long FOLDER_SCOPE = 2L << 32;
    private static final HashMap<Integer, State> states = new HashMap<>();

    private static final class State {
        final long owner;
        final LongSparseArray<Boolean> active = new LongSparseArray<>();

        State(long owner) {
            this.owner = owner;
        }
    }

    public static long scope(int type, MessagesController.DialogFilter filter) {
        if (type == 0 || (type == 7 || type == 8) && filter != null && filter.isDefault()) return ALL_SCOPE;
        if (OxChatCategories.isCategory(type)) return CATEGORY_SCOPE | (type - OxChatCategories.PERSONAL);
        if ((type == 7 || type == 8) && filter != null) return FOLDER_SCOPE | (filter.id & 0xffffffffL);
        return NO_SCOPE;
    }

    private static State state(int account, boolean create) {
        long owner = UserConfig.getInstance(account).getClientUserId();
        State state = states.get(account);
        if (state != null && state.owner != owner) {
            states.remove(account);
            state = null;
        }
        if (state == null && create && owner != 0) {
            states.put(account, state = new State(owner));
        }
        return state;
    }

    public static boolean isActive(int account, long scope) {
        State state = state(account, false);
        return scope != NO_SCOPE && state != null && state.active.get(scope) != null;
    }

    public static boolean hasActive(int account) {
        State state = state(account, false);
        return state != null && state.active.size() != 0;
    }

    public static void toggle(int account, long scope) {
        if (scope == NO_SCOPE) return;
        State state = state(account, true);
        if (state != null) {
            if (state.active.get(scope) != null) state.active.remove(scope);
            else state.active.put(scope, Boolean.TRUE);
        }
    }

    public static void clear(int account) {
        states.remove(account);
    }

    /** Owned by one DialogsActivity. Rebuild only at adapter/source update boundaries. */
    public static final class Projection {
        private final LongSparseArray<ArrayList<TLRPC.Dialog>> lists = new LongSparseArray<>();
        private final ArrayList<TLRPC.Dialog> empty = new ArrayList<>();
        private int account = -1;
        private long owner;

        private void ensureOwner(int account) {
            long owner = UserConfig.getInstance(account).getClientUserId();
            if (this.account != account || this.owner != owner) {
                lists.clear();
                this.account = account;
                this.owner = owner;
            }
        }

        public void rebuild(int account, long scope, ArrayList<TLRPC.Dialog> source) {
            ensureOwner(account);
            if (isActive(account, scope)) lists.put(scope, filter(account, source));
            else lists.remove(scope);
        }

        public ArrayList<TLRPC.Dialog> get(int account, long scope) {
            ensureOwner(account);
            ArrayList<TLRPC.Dialog> list = lists.get(scope);
            return list == null ? empty : list;
        }
    }

    /** Same outgoing/sent/unread rule used by the dialog's single tick; newest first. */
    public static ArrayList<TLRPC.Dialog> filter(int account, ArrayList<TLRPC.Dialog> source) {
        ArrayList<TLRPC.Dialog> result = new ArrayList<>();
        if (source == null) return result;
        MessagesController controller = MessagesController.getInstance(account);
        long selfId = UserConfig.getInstance(account).getClientUserId();
        for (int i = 0, count = source.size(); i < count; i++) {
            TLRPC.Dialog dialog = source.get(i);
            if (dialog == null || dialog.id == selfId || dialog.isFolder || DialogObject.isEncryptedDialog(dialog.id)) continue;
            if (DialogObject.isChatDialog(dialog.id)) {
                TLRPC.Chat chat = controller.getChat(-dialog.id);
                if (chat == null || ChatObject.isChannel(chat) && !chat.megagroup) continue;
            }
            ArrayList<MessageObject> messages = controller.dialogMessage.get(dialog.id);
            MessageObject message = messages == null || messages.isEmpty() ? null : messages.get(0);
            if (message == null || !message.isOut() || !message.isSent() || message.messageOwner == null || message.messageOwner.action != null) continue;
            boolean read = (dialog.read_outbox_max_id > 0 && dialog.read_outbox_max_id >= message.getId()) || !message.isUnread();
            if (!read) result.add(dialog);
        }
        Collections.sort(result, (a, b) -> Integer.compare(b.last_message_date, a.last_message_date));
        return result;
    }
}
