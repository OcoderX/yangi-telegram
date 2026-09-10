package org.telegram.ui.ayu;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.ayu.AyuMessagesController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextDetailCell;
import org.telegram.ui.Components.BottomSheetWithRecyclerListView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * AyuGram: "Message details" bottom sheet. Shows raw TLRPC.Message metadata, every row is copyable
 * on tap, plus a "Copy JSON" button that reflectively dumps the whole message.
 */
public class MessageDetailsSheet extends BottomSheetWithRecyclerListView {

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_DETAIL = 1;
    private static final int VIEW_TYPE_BUTTON = 2;

    private static class Row {
        final int viewType;
        final CharSequence title;
        final CharSequence value;

        Row(int viewType, CharSequence title, CharSequence value) {
            this.viewType = viewType;
            this.title = title;
            this.value = value;
        }
    }

    private final int currentAccount;
    private final MessageObject messageObject;
    private final TLRPC.Message message;
    private final ArrayList<Row> rows;

    {
        rows = new ArrayList<>();
    }

    public MessageDetailsSheet(BaseFragment fragment, int currentAccount, MessageObject messageObject) {
        super(fragment, false, false);
        this.currentAccount = currentAccount;
        this.messageObject = messageObject;
        this.message = messageObject != null ? messageObject.messageOwner : null;
        buildRows();
        if (recyclerListView != null) {
            recyclerListView.setOnItemClickListener((view, position) -> {
                int index = position - 1;
                if (index < 0 || index >= rows.size()) {
                    return;
                }
                Row row = rows.get(index);
                if (row.viewType == VIEW_TYPE_BUTTON) {
                    copy(buildJson());
                } else if (row.viewType == VIEW_TYPE_DETAIL) {
                    copy(row.title);
                }
            });
        }
        notifyDataSetChanged();
        fixNavigationBar();
    }

    private void copy(CharSequence text) {
        if (text == null) {
            return;
        }
        AndroidUtilities.addToClipboard(text.toString());
        try {
            BulletinFactory.of(container, resourcesProvider)
                    .createSimpleBulletin(R.raw.copy, getString(R.string.AyuCopiedToClipboard))
                    .show();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void addHeader(int resId) {
        rows.add(new Row(VIEW_TYPE_HEADER, getString(resId), null));
    }

    private void addRow(int labelResId, CharSequence value) {
        if (value == null) {
            return;
        }
        rows.add(new Row(VIEW_TYPE_DETAIL, value, getString(labelResId)));
    }

    private void buildRows() {
        rows.clear();
        if (message == null) {
            return;
        }

        addHeader(R.string.AyuDetailsSectionGeneral);
        addRow(R.string.AyuDetailsMessageId, String.valueOf(message.id));
        final long dialogId = messageObject.getDialogId();
        addRow(R.string.AyuDetailsDialogId, String.valueOf(dialogId));
        long fromId = message.from_id != null ? MessageObject.getPeerId(message.from_id) : 0;
        if (fromId != 0) {
            addRow(R.string.AyuDetailsFromId, String.valueOf(fromId));
            CharSequence name = resolveName(fromId);
            if (name != null) {
                addRow(R.string.AyuDetailsFromName, name);
            }
        }
        long peerId = message.peer_id != null ? MessageObject.getPeerId(message.peer_id) : 0;
        if (peerId != 0 && peerId != dialogId) {
            addRow(R.string.AyuDetailsPeerId, String.valueOf(peerId));
        }
        addRow(R.string.AyuDetailsDate, formatFullDate(message.date));
        if (message.edit_date != 0) {
            addRow(R.string.AyuDetailsEditDate, formatFullDate(message.edit_date));
        }
        if (message.grouped_id != 0) {
            addRow(R.string.AyuDetailsGroupedId, String.valueOf(message.grouped_id));
        }
        if (message.reply_to != null) {
            int replyId = message.reply_to.reply_to_msg_id != 0 ? message.reply_to.reply_to_msg_id : message.reply_to.reply_to_top_id;
            if (replyId != 0) {
                addRow(R.string.AyuDetailsReplyTo, String.valueOf(replyId));
            }
        }

        if (message.fwd_from != null) {
            addHeader(R.string.AyuDetailsSectionForward);
            long fwdId = message.fwd_from.from_id != null ? MessageObject.getPeerId(message.fwd_from.from_id) : 0;
            StringBuilder fwd = new StringBuilder();
            if (fwdId != 0) {
                fwd.append(fwdId);
                CharSequence name = resolveName(fwdId);
                if (name != null) {
                    fwd.append(" (").append(name).append(")");
                }
            } else if (message.fwd_from.from_name != null) {
                fwd.append(message.fwd_from.from_name);
            }
            if (fwd.length() > 0) {
                addRow(R.string.AyuDetailsForwardFrom, fwd.toString());
            }
            if (message.fwd_from.date != 0) {
                addRow(R.string.AyuDetailsForwardDate, formatFullDate(message.fwd_from.date));
            }
            if (message.fwd_from.post_author != null) {
                addRow(R.string.AyuDetailsPostAuthor, message.fwd_from.post_author);
            }
        } else if (message.post_author != null) {
            addRow(R.string.AyuDetailsPostAuthor, message.post_author);
        }

        final TLRPC.MessageMedia media = MessageObject.getMedia(message);
        if (media != null && !(media instanceof TLRPC.TL_messageMediaEmpty)) {
            addHeader(R.string.AyuDetailsSectionMedia);
            addRow(R.string.AyuDetailsMediaType, media.getClass().getSimpleName());
            final TLRPC.Document document = MessageObject.getDocument(message);
            if (document != null) {
                addRow(R.string.AyuDetailsFileSize, AndroidUtilities.formatFileSize(document.size) + " (" + document.size + ")");
                if (document.mime_type != null) {
                    addRow(R.string.AyuDetailsMimeType, document.mime_type);
                }
                addRow(R.string.AyuDetailsDcId, String.valueOf(document.dc_id));
                addRow(R.string.AyuDetailsFileId, String.valueOf(document.id));
            } else {
                final TLRPC.Photo photo = MessageObject.getPhoto(message);
                if (photo != null) {
                    addRow(R.string.AyuDetailsDcId, String.valueOf(photo.dc_id));
                    addRow(R.string.AyuDetailsFileId, String.valueOf(photo.id));
                    long size = 0;
                    if (photo.sizes != null) {
                        for (int a = 0; a < photo.sizes.size(); a++) {
                            size = Math.max(size, photo.sizes.get(a).size);
                        }
                    }
                    if (size > 0) {
                        addRow(R.string.AyuDetailsFileSize, AndroidUtilities.formatFileSize(size) + " (" + size + ")");
                    }
                }
            }
        }

        addHeader(R.string.AyuDetailsSectionMisc);
        if ((message.flags & TLRPC.MESSAGE_FLAG_HAS_VIEWS) != 0) {
            addRow(R.string.AyuDetailsViews, String.valueOf(message.views));
            addRow(R.string.AyuDetailsForwards, String.valueOf(message.forwards));
        }
        if (message.ttl_period != 0) {
            addRow(R.string.AyuDetailsTtl, LocaleController.formatTTLString(message.ttl_period));
        } else if (media != null && media.ttl_seconds != 0) {
            addRow(R.string.AyuDetailsTtl, LocaleController.formatTTLString(media.ttl_seconds));
        }
        addRow(R.string.AyuDetailsEntities, String.valueOf(message.entities == null ? 0 : message.entities.size()));
        addRow(R.string.AyuDetailsDeleted, String.valueOf(message.ayuDeleted));
        int revisions = message.ayuEditedCount;
        if (revisions <= 0) {
            try {
                revisions = AyuMessagesController.getInstance().getRevisions(currentAccount, dialogId, message.id).size();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        if (revisions > 0) {
            addRow(R.string.AyuDetailsRevisions, String.valueOf(revisions));
        }

        rows.add(new Row(VIEW_TYPE_BUTTON, getString(R.string.AyuCopyJson), null));
    }

    private CharSequence resolveName(long peerId) {
        try {
            if (peerId > 0) {
                TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(peerId);
                if (user != null) {
                    return UserObject.getUserName(user);
                }
            } else if (peerId < 0) {
                TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-peerId);
                if (chat != null) {
                    return chat.title;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    private static String formatFullDate(int date) {
        if (date == 0) {
            return "0";
        }
        try {
            return LocaleController.getInstance().getFormatterYear().format(new Date((long) date * 1000))
                    + ", " + LocaleController.getInstance().getFormatterDayWithSeconds().format(new Date((long) date * 1000))
                    + " (" + date + ")";
        } catch (Exception e) {
            return String.valueOf(date);
        }
    }

    // ---------------- reflective JSON dump ----------------

    private String buildJson() {
        StringBuilder sb = new StringBuilder();
        try {
            dump(message, sb, 0, new IdentityHashMap<>());
        } catch (Exception e) {
            FileLog.e(e);
            sb.setLength(0);
            sb.append("{}");
        }
        return sb.toString();
    }

    private static void indent(StringBuilder sb, int level) {
        for (int a = 0; a < level; a++) {
            sb.append("  ");
        }
    }

    private static void dump(Object object, StringBuilder sb, int level, IdentityHashMap<Object, Object> seen) throws IllegalAccessException {
        if (object == null) {
            sb.append("null");
            return;
        }
        if (level > 8) {
            sb.append("\"...\"");
            return;
        }
        if (object instanceof CharSequence) {
            sb.append('"').append(escape(object.toString())).append('"');
            return;
        }
        if (object instanceof Number || object instanceof Boolean || object instanceof Character) {
            sb.append(object);
            return;
        }
        if (object instanceof byte[]) {
            sb.append('"').append(((byte[]) object).length).append(" bytes\"");
            return;
        }
        if (object instanceof List) {
            List<?> list = (List<?>) object;
            if (list.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            for (int a = 0; a < list.size(); a++) {
                indent(sb, level + 1);
                dump(list.get(a), sb, level + 1, seen);
                if (a != list.size() - 1) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            indent(sb, level);
            sb.append(']');
            return;
        }
        if (!object.getClass().getName().startsWith("org.telegram")) {
            sb.append('"').append(escape(String.valueOf(object))).append('"');
            return;
        }
        if (seen.containsKey(object)) {
            sb.append("\"<recursion>\"");
            return;
        }
        seen.put(object, object);
        sb.append("{\n");
        indent(sb, level + 1);
        sb.append("\"_\": \"").append(object.getClass().getSimpleName()).append('"');
        Class<?> cls = object.getClass();
        while (cls != null && cls != Object.class) {
            Field[] fields = cls.getDeclaredFields();
            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(object);
                } catch (Throwable ignore) {
                    continue;
                }
                if (value == null) {
                    continue;
                }
                if (value instanceof List && ((List<?>) value).isEmpty()) {
                    continue;
                }
                sb.append(",\n");
                indent(sb, level + 1);
                sb.append('"').append(field.getName()).append("\": ");
                dump(value, sb, level + 1, seen);
            }
            cls = cls.getSuperclass();
        }
        sb.append('\n');
        indent(sb, level);
        sb.append('}');
        seen.remove(object);
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int a = 0; a < s.length(); a++) {
            char c = s.charAt(a);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.AyuMessageDetails);
    }

    @Override
    protected RecyclerListView.SelectionAdapter createAdapter(RecyclerListView listView) {
        return new Adapter();
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int viewType = holder.getItemViewType();
            return viewType == VIEW_TYPE_DETAIL || viewType == VIEW_TYPE_BUTTON;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final Context context = parent.getContext();
            View view;
            switch (viewType) {
                case VIEW_TYPE_HEADER: {
                    HeaderCell cell = new HeaderCell(context, resourcesProvider);
                    cell.setBackgroundColor(Theme.getColor(Theme.key_dialogBackground, resourcesProvider));
                    view = cell;
                    break;
                }
                case VIEW_TYPE_BUTTON: {
                    TextView textView = new TextView(context);
                    textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                    textView.setTypeface(AndroidUtilities.bold());
                    textView.setGravity(Gravity.CENTER);
                    textView.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText, resourcesProvider));
                    textView.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(8), Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider), Theme.getColor(Theme.key_featuredStickers_addButtonPressed, resourcesProvider)));
                    FrameLayout container = new FrameLayout(context);
                    container.setBackgroundColor(Theme.getColor(Theme.key_dialogBackground, resourcesProvider));
                    container.addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.CENTER, 16, 10, 16, 16));
                    view = container;
                    break;
                }
                default: {
                    TextDetailCell cell = new TextDetailCell(context, resourcesProvider, true, false);
                    cell.setBackgroundColor(Theme.getColor(Theme.key_dialogBackground, resourcesProvider));
                    view = cell;
                    break;
                }
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            int index = position;
            if (rows == null || index < 0 || index >= rows.size()) {
                return;
            }
            Row row = rows.get(index);
            switch (holder.getItemViewType()) {
                case VIEW_TYPE_HEADER:
                    ((HeaderCell) holder.itemView).setText(row.title);
                    break;
                case VIEW_TYPE_BUTTON: {
                    FrameLayout container = (FrameLayout) holder.itemView;
                    ((TextView) container.getChildAt(0)).setText(row.title);
                    break;
                }
                default: {
                    boolean divider = index + 1 < rows.size() && rows.get(index + 1).viewType == VIEW_TYPE_DETAIL;
                    ((TextDetailCell) holder.itemView).setTextAndValue(row.title, row.value, divider);
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (rows == null || position < 0 || position >= rows.size()) {
                return VIEW_TYPE_HEADER;
            }
            return rows.get(position).viewType;
        }

        @Override
        public int getItemCount() {
            return rows == null ? 0 : rows.size();
        }
    }
}
