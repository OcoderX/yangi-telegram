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
import org.telegram.messenger.ayu.contacts.ContactTracker;
import org.telegram.messenger.ayu.contacts.ContactTrackerStorage;
import org.telegram.messenger.ayu.contacts.SpecialContactsConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * OcoderX "Contact tracker": the special contacts, each opening its recorded online history
 * ({@link ContactTrackerLogActivity}).
 */
public class ContactTrackerActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate, ContactTracker.ContactTrackerDelegate {

    private static final int MENU_CLEAR_ALL = 1;

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_USER = 1;
    private static final int VIEW_TYPE_INFO = 2;
    private static final int VIEW_TYPE_SHADOW = 3;
    private static final int VIEW_TYPE_MANAGE = 4;

    private RecyclerListView listView;
    private ListAdapter adapter;

    private final ArrayList<Long> userIds = new ArrayList<>();

    private int rowCount;
    private int headerRow;
    private int usersStartRow;
    private int usersEndRow;
    private int emptyInfoRow;
    private int shadowRow;
    private int manageRow;

    private final Runnable updateRunnable = this::updateRows;

    @Override
    public boolean onFragmentCreate() {
        SpecialContactsConfig.load();
        getNotificationCenter().addObserver(this, NotificationCenter.updateInterfaces);
        getNotificationCenter().addObserver(this, NotificationCenter.contactsDidLoad);
        ContactTracker.getInstance().addDelegate(this);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.updateInterfaces);
        getNotificationCenter().removeObserver(this, NotificationCenter.contactsDidLoad);
        ContactTracker.getInstance().removeDelegate(this);
        AndroidUtilities.cancelRunOnUIThread(updateRunnable);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.OxContactsTrackerTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_CLEAR_ALL) {
                    showClearDialog();
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem otherItem = menu.addItem(100, R.drawable.ic_ab_other);
        otherItem.setContentDescription(getString(R.string.AccDescrMoreOptions));
        otherItem.addSubItem(MENU_CLEAR_ALL, R.drawable.msg_delete, getString(R.string.OxContactsClearLog));

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
            if (position == manageRow) {
                presentFragment(new SpecialContactsActivity());
                return;
            }
            long userId = userIdAt(position);
            if (userId != 0) {
                Bundle args = new Bundle();
                args.putLong("user_id", userId);
                presentFragment(new ContactTrackerLogActivity(args));
            }
        });

        updateRows();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        updateRows();
    }

    // ------------------------------------------------------------------ rows

    private void updateRows() {
        userIds.clear();
        userIds.addAll(SpecialContactsConfig.getAll());

        rowCount = 0;
        if (userIds.isEmpty()) {
            headerRow = -1;
            usersStartRow = -1;
            usersEndRow = -1;
            emptyInfoRow = rowCount++;
            shadowRow = -1;
        } else {
            emptyInfoRow = -1;
            headerRow = rowCount++;
            usersStartRow = rowCount;
            rowCount += userIds.size();
            usersEndRow = rowCount;
            shadowRow = rowCount++;
        }
        manageRow = rowCount++;

        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private long userIdAt(int position) {
        if (usersStartRow < 0 || position < usersStartRow || position >= usersEndRow) {
            return 0;
        }
        return userIds.get(position - usersStartRow);
    }

    private void scheduleUpdate() {
        AndroidUtilities.cancelRunOnUIThread(updateRunnable);
        AndroidUtilities.runOnUIThread(updateRunnable, 250);
    }

    private void showClearDialog() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(R.string.OxContactsClearLog));
        builder.setMessage(getString(R.string.OxContactsClearLogAllMessage));
        builder.setPositiveButton(getString(R.string.Delete), (dialog, which) ->
                ContactTrackerStorage.getInstance().clearAll(() -> {
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
        if (account != currentAccount) {
            return;
        }
        if (id == NotificationCenter.updateInterfaces) {
            int mask = args != null && args.length > 0 && args[0] instanceof Integer ? (Integer) args[0] : 0;
            if ((mask & MessagesController.UPDATE_MASK_STATUS) != 0 || (mask & MessagesController.UPDATE_MASK_NAME) != 0
                    || (mask & MessagesController.UPDATE_MASK_AVATAR) != 0) {
                scheduleUpdate();
            }
        } else if (id == NotificationCenter.contactsDidLoad) {
            scheduleUpdate();
        }
    }

    @Override
    public void onSpecialContactStatusChanged(int account, long userId, boolean online) {
        scheduleUpdate();
    }

    // ------------------------------------------------------------------ adapter

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == VIEW_TYPE_USER || type == VIEW_TYPE_MANAGE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final Context context = parent.getContext();
            View view;
            switch (viewType) {
                case VIEW_TYPE_HEADER: {
                    HeaderCell cell = new HeaderCell(context);
                    cell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    view = cell;
                    break;
                }
                case VIEW_TYPE_USER: {
                    ContactStatusCell cell = new ContactStatusCell(context);
                    cell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    view = cell;
                    break;
                }
                case VIEW_TYPE_MANAGE: {
                    TextCell cell = new TextCell(context);
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
            switch (holder.getItemViewType()) {
                case VIEW_TYPE_HEADER: {
                    ((HeaderCell) holder.itemView).setText(getString(R.string.OxContactsSpecialHeader));
                    break;
                }
                case VIEW_TYPE_USER: {
                    long userId = userIdAt(position);
                    TLRPC.User user = userId == 0 ? null : MessagesController.getInstance(currentAccount).getUser(userId);
                    ContactStatusCell cell = (ContactStatusCell) holder.itemView;
                    final boolean online = ContactTracker.isOnline(currentAccount, user);
                    CharSequence status = user != null
                            ? ContactsFormat.statusText(currentAccount, user)
                            : getString(R.string.OxContactsOffline);
                    cell.setUser(currentAccount, user, status, online, position != usersEndRow - 1);
                    cell.setAction(0, null, null);
                    break;
                }
                case VIEW_TYPE_MANAGE: {
                    TextCell cell = (TextCell) holder.itemView;
                    cell.setTextAndIcon(getString(R.string.OxContactsManageSpecial), R.drawable.msg_contacts, false);
                    cell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueText);
                    break;
                }
                case VIEW_TYPE_INFO: {
                    ((TextInfoPrivacyCell) holder.itemView).setText(getString(R.string.OxContactsTrackerEmpty));
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == headerRow) {
                return VIEW_TYPE_HEADER;
            }
            if (position == emptyInfoRow) {
                return VIEW_TYPE_INFO;
            }
            if (position == manageRow) {
                return VIEW_TYPE_MANAGE;
            }
            if (usersStartRow >= 0 && position >= usersStartRow && position < usersEndRow) {
                return VIEW_TYPE_USER;
            }
            return VIEW_TYPE_SHADOW;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }
    }
}
