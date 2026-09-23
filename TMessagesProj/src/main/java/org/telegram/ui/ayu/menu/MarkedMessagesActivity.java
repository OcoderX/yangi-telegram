package org.telegram.ui.ayu.menu;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.ayu.AyuMarkedMessages;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Ox-gram "Marked messages": the bookmarks of one dialog. Tapping a row closes the screen and asks
 * the chat to scroll to that message.
 */
public class MarkedMessagesActivity extends UniversalFragment {

    private static final int BUTTON_CLEAR = 1;
    private static final int ROW_OFFSET = 1000;

    public static class Row {
        public int id;
        public int date;
        public CharSequence text;
    }

    private final long dialogId;
    private final Utilities.Callback<Integer> onMessageSelected;
    private final ArrayList<Row> rows = new ArrayList<>();
    private boolean loading = true;

    public MarkedMessagesActivity(long dialogId, Utilities.Callback<Integer> onMessageSelected) {
        super();
        this.dialogId = dialogId;
        this.onMessageSelected = onMessageSelected;
    }

    @Override
    public View createView(Context context) {
        super.createView(context);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        load();
        return fragmentView;
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.AyuMarkedMessages);
    }

    private void load() {
        final ArrayList<Integer> ids = AyuMarkedMessages.getMarked(currentAccount, dialogId);
        if (ids.isEmpty()) {
            loading = false;
            update();
            return;
        }
        final int account = currentAccount;
        MessagesStorage.getInstance(account).getStorageQueue().postRunnable(() -> {
            final ArrayList<Row> loaded = new ArrayList<>();
            try {
                final long selfId = UserConfig.getInstance(account).clientUserId;
                SQLiteCursor cursor = MessagesStorage.getInstance(account).getDatabase().queryFinalized(
                        String.format(Locale.US, "SELECT data, mid, date FROM messages_v2 WHERE mid IN (%s) AND uid = %d",
                                TextUtils.join(",", ids), dialogId));
                while (cursor.next()) {
                    NativeByteBuffer data = cursor.byteBufferValue(0);
                    if (data == null) {
                        continue;
                    }
                    TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    if (message != null) {
                        message.readAttachPath(data, selfId);
                    }
                    data.reuse();
                    Row row = new Row();
                    row.id = cursor.intValue(1);
                    row.date = cursor.intValue(2);
                    row.text = message != null && !TextUtils.isEmpty(message.message)
                            ? message.message
                            : getString(R.string.AyuMarkedMessageNoText);
                    loaded.add(row);
                }
                cursor.dispose();
            } catch (Exception e) {
                FileLog.e(e);
            }
            // keep the bookmark order (newest message first) and append the ones we could not load
            final ArrayList<Row> ordered = new ArrayList<>();
            for (int a = 0; a < ids.size(); a++) {
                final int id = ids.get(a);
                Row found = null;
                for (int b = 0; b < loaded.size(); b++) {
                    if (loaded.get(b).id == id) {
                        found = loaded.get(b);
                        break;
                    }
                }
                if (found == null) {
                    found = new Row();
                    found.id = id;
                    found.date = 0;
                    found.text = getString(R.string.AyuMarkedMessageNotLoaded);
                }
                ordered.add(found);
            }
            AndroidUtilities.runOnUIThread(() -> {
                rows.clear();
                rows.addAll(ordered);
                loading = false;
                update();
            });
        });
    }

    private void update() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (loading) {
            items.add(UItem.asShadow(getString(R.string.Loading)));
            return;
        }
        if (rows.isEmpty()) {
            items.add(UItem.asShadow(getString(R.string.AyuMarkedMessagesEmpty)));
            return;
        }
        items.add(UItem.asHeader(getString(R.string.AyuMarkedMessages)));
        for (int a = 0; a < rows.size(); a++) {
            final Row row = rows.get(a);
            CharSequence preview = row.text;
            if (preview != null && preview.length() > 90) {
                preview = preview.subSequence(0, 90) + "…";
            }
            final String value = row.date != 0
                    ? LocaleController.formatDateAudio(row.date, true)
                    : "";
            items.add(UItem.asSettingsCell(ROW_OFFSET + a, 0, preview, value));
        }
        items.add(UItem.asShadow(null));
        items.add(UItem.asButton(BUTTON_CLEAR, getString(R.string.AyuMarkedMessagesClear)).red());
        items.add(UItem.asShadow(getString(R.string.AyuMarkedMessagesInfo)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == BUTTON_CLEAR) {
            AyuMarkedMessages.clear(currentAccount, dialogId);
            rows.clear();
            update();
            return;
        }
        final int index = item.id - ROW_OFFSET;
        if (index >= 0 && index < rows.size()) {
            final int messageId = rows.get(index).id;
            finishFragment();
            if (onMessageSelected != null) {
                AndroidUtilities.runOnUIThread(() -> onMessageSelected.run(messageId), 80);
            }
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        final int index = item.id - ROW_OFFSET;
        if (index >= 0 && index < rows.size()) {
            AyuMarkedMessages.remove(currentAccount, dialogId, rows.get(index).id);
            rows.remove(index);
            update();
            return true;
        }
        return false;
    }
}
