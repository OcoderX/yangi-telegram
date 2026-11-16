package org.telegram.ui.ayu.contacts;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.ayu.contacts.ContactSession;
import org.telegram.messenger.ayu.contacts.ContactStatusEntry;
import org.telegram.messenger.ayu.contacts.ContactTracker;
import org.telegram.messenger.ayu.contacts.ContactTrackerStorage;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.ProfileActivity;

import java.util.ArrayList;

/**
 * OcoderX contact tracker: the recorded online history of one special contact — a summary (today /
 * this week) plus every online interval, grouped by day, newest first.
 * <p>
 * The rows are rebuilt from the transitions stored by
 * {@link org.telegram.messenger.ayu.contacts.ContactTracker} — reading happens on
 * {@code Utilities.globalQueue} inside {@link ContactTrackerStorage} and the result arrives back on
 * the main thread.
 */
public class ContactTrackerLogActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate, ContactTracker.ContactTrackerDelegate {

    private static final int MENU_CLEAR = 1;
    private static final int LOAD_LIMIT = ContactTrackerStorage.MAX_ROWS_PER_USER;

    private static final int VIEW_TYPE_USER = 0;
    private static final int VIEW_TYPE_HEADER = 1;
    private static final int VIEW_TYPE_VALUE = 2;
    private static final int VIEW_TYPE_SESSION = 3;
    private static final int VIEW_TYPE_SHADOW = 4;
    private static final int VIEW_TYPE_INFO = 5;

    private final long userId;

    private RecyclerListView listView;
    private ListAdapter adapter;

    private final ArrayList<Item> items = new ArrayList<>();
    private ArrayList<ContactSession> sessions = new ArrayList<>();
    private boolean loading;

    private static class Item {
        final int viewType;
        final CharSequence text;
        final CharSequence value;
        final boolean divider;

        Item(int viewType, CharSequence text, CharSequence value, boolean divider) {
            this.viewType = viewType;
            this.text = text;
            this.value = value;
            this.divider = divider;
        }
    }

    public ContactTrackerLogActivity(Bundle args) {
        super(args);
        userId = args == null ? 0 : args.getLong("user_id", 0);
    }

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.updateInterfaces);
        ContactTracker.getInstance().addDelegate(this);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.updateInterfaces);
        ContactTracker.getInstance().removeDelegate(this);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.OxContactsTrackerTitle));
        TLRPC.User user = getUser();
        if (user != null) {
            actionBar.setSubtitle(UserObject.getUserName(user));
        }
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_CLEAR) {
                    showClearDialog();
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem otherItem = menu.addItem(100, R.drawable.ic_ab_other);
        otherItem.setContentDescription(getString(R.string.AccDescrMoreOptions));
        otherItem.addSubItem(MENU_CLEAR, R.drawable.msg_delete, getString(R.string.OxContactsClearLog));

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        final int gravity = Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter = new ListAdapter());
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, gravity));

        listView.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < items.size() && items.get(position).viewType == VIEW_TYPE_USER) {
                Bundle args = new Bundle();
                args.putLong("user_id", userId);
                presentFragment(new ProfileActivity(args));
            }
        });

        loadLog();
        return fragmentView;
    }

    private TLRPC.User getUser() {
        return userId == 0 ? null : MessagesController.getInstance(currentAccount).getUser(userId);
    }

    // ------------------------------------------------------------------ data

    private void loadLog() {
        if (loading || userId == 0) {
            return;
        }
        loading = true;
        ContactTrackerStorage.getInstance().loadForUser(userId, currentAccount, LOAD_LIMIT, entries -> {
            loading = false;
            sessions = ContactSession.build(entries, System.currentTimeMillis());
            buildItems();
        });
    }

    private void buildItems() {
        items.clear();

        items.add(new Item(VIEW_TYPE_USER, null, null, false));

        final long now = System.currentTimeMillis();
        final long dayStart = ContactSession.startOfDay(now);
        final long weekStart = ContactSession.startOfWeek(now);

        items.add(new Item(VIEW_TYPE_HEADER, getString(R.string.OxContactsSummaryHeader), null, false));
        items.add(new Item(VIEW_TYPE_VALUE, getString(R.string.OxContactsToday),
                ContactsFormat.formatDuration(ContactSession.totalBetween(sessions, dayStart, now)), true));
        items.add(new Item(VIEW_TYPE_VALUE, getString(R.string.OxContactsThisWeek),
                ContactsFormat.formatDuration(ContactSession.totalBetween(sessions, weekStart, now)), false));
        items.add(new Item(VIEW_TYPE_SHADOW, null, null, false));

        if (sessions.isEmpty()) {
            items.add(new Item(VIEW_TYPE_INFO, getString(R.string.OxContactsNoLog), null, false));
        } else {
            long currentDay = Long.MIN_VALUE;
            int dayStartIndex = -1;
            for (int i = 0; i < sessions.size(); i++) {
                ContactSession session = sessions.get(i);
                long day = ContactSession.startOfDay(session.start);
                if (day != currentDay) {
                    if (dayStartIndex >= 0) {
                        // the last row of the previous day must not draw a divider
                        Item last = items.get(items.size() - 1);
                        items.set(items.size() - 1, new Item(last.viewType, last.text, last.value, false));
                        items.add(new Item(VIEW_TYPE_SHADOW, null, null, false));
                    }
                    currentDay = day;
                    items.add(new Item(VIEW_TYPE_HEADER, ContactsFormat.formatDayHeader(session.start), null, false));
                    dayStartIndex = items.size();
                }
                items.add(new Item(VIEW_TYPE_SESSION, ContactsFormat.formatSession(session),
                        session.ongoing ? getString(R.string.OxContactsOnline) : null, true));
            }
            if (!items.isEmpty()) {
                Item last = items.get(items.size() - 1);
                if (last.viewType == VIEW_TYPE_SESSION) {
                    items.set(items.size() - 1, new Item(last.viewType, last.text, last.value, false));
                }
            }
            items.add(new Item(VIEW_TYPE_INFO, LocaleController.formatString(R.string.OxContactsTotalOnline,
                    ContactsFormat.formatDuration(ContactSession.totalBetween(sessions, 0, now))), null, false));
        }

        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void showClearDialog() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        TLRPC.User user = getUser();
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(R.string.OxContactsClearLog));
        builder.setMessage(LocaleController.formatString(R.string.OxContactsClearLogMessage,
                user == null ? "" : UserObject.getUserName(user)));
        builder.setPositiveButton(getString(R.string.Delete), (dialog, which) ->
                ContactTrackerStorage.getInstance().clearUser(userId, currentAccount, () -> {
                    sessions = new ArrayList<>();
                    buildItems();
                    if (getParentActivity() != null) {
                        BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip, getString(R.string.OxContactsLogCleared)).show();
                    }
                }));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        View button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button instanceof TextView) {
            ((TextView) button).setTextColor(getThemedColor(Theme.key_text_RedBold));
        }
    }

    // ------------------------------------------------------------------ notifications

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (account != currentAccount || id != NotificationCenter.updateInterfaces) {
            return;
        }
        int mask = args != null && args.length > 0 && args[0] instanceof Integer ? (Integer) args[0] : 0;
        if ((mask & MessagesController.UPDATE_MASK_STATUS) != 0 && adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    @Override
    public void onSpecialContactStatusChanged(int account, long changedUserId, boolean online) {
        if (account != currentAccount || changedUserId != userId) {
            return;
        }
        AndroidUtilities.runOnUIThread(this::loadLog, 400);
    }

    // ------------------------------------------------------------------ adapter

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == VIEW_TYPE_USER;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final Context context = parent.getContext();
            View view;
            switch (viewType) {
                case VIEW_TYPE_USER: {
                    ContactStatusCell cell = new ContactStatusCell(context);
                    cell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    view = cell;
                    break;
                }
                case VIEW_TYPE_HEADER: {
                    HeaderCell cell = new HeaderCell(context);
                    cell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    view = cell;
                    break;
                }
                case VIEW_TYPE_VALUE:
                case VIEW_TYPE_SESSION: {
                    TextSettingsCell cell = new TextSettingsCell(context);
                    cell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    view = cell;
                    break;
                }
                case VIEW_TYPE_INFO: {
                    view = new TextInfoPrivacyCell(context);
                    break;
                }
                case VIEW_TYPE_SHADOW:
                default: {
                    view = new ShadowSectionCell(context);
                    break;
                }
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (position < 0 || position >= items.size()) {
                return;
            }
            final Item item = items.get(position);
            switch (item.viewType) {
                case VIEW_TYPE_USER: {
                    TLRPC.User user = getUser();
                    ContactStatusCell cell = (ContactStatusCell) holder.itemView;
                    final boolean online = ContactTracker.isOnline(currentAccount, user);
                    CharSequence status = user != null
                            ? ContactsFormat.statusText(currentAccount, user)
                            : getString(R.string.OxContactsOffline);
                    cell.setUser(currentAccount, user, status, online, false);
                    cell.setAction(0, null, null);
                    break;
                }
                case VIEW_TYPE_HEADER: {
                    ((HeaderCell) holder.itemView).setText(item.text);
                    break;
                }
                case VIEW_TYPE_VALUE:
                case VIEW_TYPE_SESSION: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (item.value == null) {
                        cell.setText(item.text, item.divider);
                    } else {
                        cell.setTextAndValue(item.text, item.value, item.divider);
                    }
                    break;
                }
                case VIEW_TYPE_INFO: {
                    ((TextInfoPrivacyCell) holder.itemView).setText(item.text);
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position < 0 || position >= items.size()) {
                return VIEW_TYPE_SHADOW;
            }
            return items.get(position).viewType;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
