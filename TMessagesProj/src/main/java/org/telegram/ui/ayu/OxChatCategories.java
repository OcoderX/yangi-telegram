package org.telegram.ui.ayu;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;

/** Local presentation only: these IDs never identify Telegram dialog filters. */
public final class OxChatCategories {
    public static final int ALL = 0x60000000;
    public static final int PERSONAL = ALL + 1;
    public static final int GROUPS = ALL + 2;
    public static final int CHANNELS = ALL + 3;
    public static final int BOTS = ALL + 4;

    private final ArrayList<TLRPC.Dialog>[] lists;
    private final int[] unread = new int[4];
    private final HashSet<Long> seen = new HashSet<>();
    private int totalUnread;

    @SuppressWarnings("unchecked")
    public OxChatCategories() {
        lists = new ArrayList[4];
        for (int i = 0; i < lists.length; i++) lists[i] = new ArrayList<>();
    }

    public static boolean isLocalTab(int id) {
        return id >= ALL && id <= BOTS;
    }

    public static boolean isCategory(int type) {
        return type >= PERSONAL && type <= BOTS;
    }

    public static int classify(long dialogId, long selfId, TLRPC.User user, TLRPC.Chat chat) {
        if (DialogObject.isFolderDialogId(dialogId) || dialogId == 0) return 0;
        if (DialogObject.isEncryptedDialog(dialogId)) return PERSONAL;
        if (DialogObject.isUserDialog(dialogId)) {
            if (dialogId == selfId) return PERSONAL;
            if (user == null || user instanceof TLRPC.TL_userEmpty || user.min && !user.bot) return 0;
            return user.bot ? BOTS : PERSONAL;
        }
        if (chat == null || chat instanceof TLRPC.TL_chatEmpty || chat.migrated_to != null) return 0;
        if (!ChatObject.isChannel(chat) || chat.megagroup) return GROUPS;
        return chat.broadcast ? CHANNELS : 0;
    }

    /** Run at source-update boundaries; list access during layout/binding is O(1). */
    public void rebuild(MessagesController controller, long selfId) {
        rebuild(controller, selfId, 0, null);
    }

    public void rebuild(MessagesController controller, long selfId, int folderId, MediaDataController drafts) {
        clear();
        if (folderId == 1) {
            appendSource(controller, selfId, controller.getDialogs(1), 1, false);
            return;
        }
        if (folderId != 0) return;
        ArrayList<TLRPC.Dialog> main = controller.getDialogs(0);
        ArrayList<TLRPC.Dialog> archive = controller.getDialogs(1);
        // Pin ranks belong to their native folder; never compare ranks across folders.
        appendSource(controller, selfId, main, 0, true);
        appendSource(controller, selfId, archive, 1, true);
        int mainIndex = nextUnpinned(main, 0, 0);
        int archiveIndex = nextUnpinned(archive, 0, 1);
        while (mainIndex < main.size() || archiveIndex < archive.size()) {
            if (archiveIndex >= archive.size() || mainIndex < main.size()
                    && date(main.get(mainIndex), drafts) >= date(archive.get(archiveIndex), drafts)) {
                append(controller, selfId, main.get(mainIndex));
                mainIndex = nextUnpinned(main, mainIndex + 1, 0);
            } else {
                append(controller, selfId, archive.get(archiveIndex));
                archiveIndex = nextUnpinned(archive, archiveIndex + 1, 1);
            }
        }
    }

    private static long date(TLRPC.Dialog dialog, MediaDataController drafts) {
        return DialogObject.getLastMessageOrDraftDate(dialog, drafts == null ? dialog.draft : drafts.getDraft(dialog.id, 0));
    }

    private static int nextUnpinned(ArrayList<TLRPC.Dialog> source, int index, int folderId) {
        while (index < source.size()) {
            TLRPC.Dialog dialog = source.get(index);
            if (dialog != null && dialog.folder_id == folderId && !dialog.pinned && !DialogObject.isFolderDialogId(dialog.id)) break;
            index++;
        }
        return index;
    }

    private void appendSource(MessagesController controller, long selfId, ArrayList<TLRPC.Dialog> source, int folderId, boolean pinnedOnly) {
        for (int i = 0, count = source.size(); i < count; i++) {
            TLRPC.Dialog dialog = source.get(i);
            if (dialog != null && dialog.folder_id == folderId && (!pinnedOnly || dialog.pinned)) append(controller, selfId, dialog);
        }
    }

    private void clear() {
        seen.clear();
        totalUnread = 0;
        for (int i = 0; i < lists.length; i++) {
            lists[i].clear();
            unread[i] = 0;
        }
    }

    /** The caller supplies the native eligible list for this surface, including forward targets. */
    public void rebuild(MessagesController controller, long selfId, ArrayList<TLRPC.Dialog> source) {
        clear();
        for (int i = 0, count = source.size(); i < count; i++) append(controller, selfId, source.get(i));
    }

    private void append(MessagesController controller, long selfId, TLRPC.Dialog dialog) {
        if (dialog == null || !seen.add(dialog.id)) return;
        boolean unreadDialog = dialog.unread_count > 0 || dialog.unread_mark;
        if (dialog.id != 0 && !DialogObject.isFolderDialogId(dialog.id) && unreadDialog) totalUnread++;
        int type = classify(dialog.id, selfId,
                DialogObject.isUserDialog(dialog.id) ? controller.getUser(dialog.id) : null,
                DialogObject.isChatDialog(dialog.id) ? controller.getChat(-dialog.id) : null);
        if (!isCategory(type)) return;
        int index = type - PERSONAL;
        lists[index].add(dialog);
        if (unreadDialog) unread[index]++;
    }

    public ArrayList<TLRPC.Dialog> dialogs(int type) {
        return lists[type - PERSONAL];
    }

    public int unread(int type) {
        if (type == ALL) return totalUnread;
        return isCategory(type) ? unread[type - PERSONAL] : 0;
    }
}
