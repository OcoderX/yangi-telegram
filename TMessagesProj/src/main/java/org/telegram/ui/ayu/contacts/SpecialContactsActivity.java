package org.telegram.ui.ayu.contacts;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.collection.LongSparseArray;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.ayu.contacts.ContactTracker;
import org.telegram.messenger.ayu.contacts.SpecialContactsConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.ContactsActivity;
import org.telegram.ui.ProfileActivity;

import java.util.ArrayList;

/**
 * OcoderX "Special contact": the watch list behind the online/offline notifications and the
 * {@link org.telegram.messenger.ayu.contacts.ContactTracker} history.
 * <p>
 * The ids live in {@link SpecialContactsConfig} (own SharedPreferences file), the picker is the stock
 * {@link ContactsActivity} in "return as result" mode. Nothing here issues a request.
 */
public class SpecialContactsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate, ContactTracker.ContactTrackerDelegate {

    private static final int VIEW_TYPE_ADD = 0;
    private static final int VIEW_TYPE_SHADOW = 1;
    private static final int VIEW_TYPE_HEADER = 2;
    private static final int VIEW_TYPE_USER = 3;
    private static final int VIEW_TYPE_INFO = 4;
    private static final int VIEW_TYPE_CHECK = 5;

    private RecyclerListView listView;
    private ListAdapter adapter;

    private final ArrayList<Long> userIds = new ArrayList<>();

    private int rowCount;
    private int addRow;
    private int addShadowRow;
    private int headerRow;
    private int usersStartRow;
    private int usersEndRow;
    private int emptyInfoRow;
    private int notifyRow;
    private int notifyInfoRow;

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
        actionBar.setTitle(getString(R.string.OxContactsSpecialTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

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
            if (position == addRow) {
                openPicker();
            } else if (position == notifyRow) {
                SpecialContactsConfig.setNotificationsEnabled(!SpecialContactsConfig.notificationsEnabled);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(SpecialContactsConfig.notificationsEnabled);
                }
            } else {
                TLRPC.User user = userAt(position);
                if (user != null) {
                    Bundle args = new Bundle();
                    args.putLong("user_id", user.id);
                    presentFragment(new ProfileActivity(args));
                }
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            final long userId = userIdAt(position);
            if (userId == 0) {
                return false;
            }
            ItemOptions options = ItemOptions.makeOptions(this, view);
            options.add(R.drawable.msg_delete, getString(R.string.OxContactsRemove), true, () -> removeContact(userId));
            options.setGravity(Gravity.RIGHT);
            options.show();
            return true;
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
        addRow = rowCount++;
        addShadowRow = rowCount++;
        if (userIds.isEmpty()) {
            headerRow = -1;
            usersStartRow = -1;
            usersEndRow = -1;
            emptyInfoRow = rowCount++;
        } else {
            emptyInfoRow = -1;
            headerRow = rowCount++;
            usersStartRow = rowCount;
            rowCount += userIds.size();
            usersEndRow = rowCount;
            rowCount++; // shadow after the list
        }
        notifyRow = rowCount++;
        notifyInfoRow = rowCount++;

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

    private TLRPC.User userAt(int position) {
        long userId = userIdAt(position);
        if (userId == 0) {
            return null;
        }
        return MessagesController.getInstance(currentAccount).getUser(userId);
    }

    private void scheduleUpdate() {
        AndroidUtilities.cancelRunOnUIThread(updateRunnable);
        AndroidUtilities.runOnUIThread(updateRunnable, 250);
    }

    // ------------------------------------------------------------------ actions

    private void openPicker() {
        Bundle args = new Bundle();
        args.putBoolean("onlyUsers", true);
        args.putBoolean("destroyAfterSelect", true);
        args.putBoolean("returnAsResult", true);
        args.putBoolean("needForwardCount", false);
        args.putBoolean("allowSelf", false);
        args.putBoolean("allowBots", false);
        ContactsActivity fragment = new ContactsActivity(args);

        LongSparseArray<TLRPC.User> ignore = new LongSparseArray<>();
        for (int i = 0; i < userIds.size(); i++) {
            long id = userIds.get(i);
            TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(id);
            if (user != null) {
                ignore.put(id, user);
            }
        }
        if (ignore.size() > 0) {
            fragment.setIgnoreUsers(ignore);
        }
        fragment.setDelegate((user, param, activity) -> {
            if (user == null) {
                return;
            }
            SpecialContactsConfig.add(user.id);
            ContactTracker.getInstance().forget(user.id);
            AndroidUtilities.runOnUIThread(() -> {
                updateRows();
                if (getParentActivity() != null) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check,
                            LocaleController.formatString(R.string.OxContactsAddedToSpecial, UserObject.getUserName(user))).show();
                }
            });
        });
        presentFragment(fragment);
    }

    private void removeContact(long userId) {
        TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(userId);
        SpecialContactsConfig.remove(userId);
        ContactTracker.getInstance().forget(userId);
        updateRows();
        if (getParentActivity() != null && user != null) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip,
                    LocaleController.formatString(R.string.OxContactsRemovedFromSpecial, UserObject.getUserName(user))).show();
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
            return type == VIEW_TYPE_ADD || type == VIEW_TYPE_USER || type == VIEW_TYPE_CHECK;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final Context context = parent.getContext();
            View view;
            switch (viewType) {
                case VIEW_TYPE_ADD: {
                    TextCell cell = new TextCell(context);
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
                case VIEW_TYPE_USER: {
                    ContactStatusCell cell = new ContactStatusCell(context);
                    cell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    view = cell;
                    break;
                }
                case VIEW_TYPE_CHECK: {
                    TextCheckCell cell = new TextCheckCell(context);
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
                case VIEW_TYPE_ADD: {
                    TextCell cell = (TextCell) holder.itemView;
                    cell.setTextAndIcon(getString(R.string.OxContactsAdd), R.drawable.msg_addcontact, false);
                    cell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueText);
                    break;
                }
                case VIEW_TYPE_HEADER: {
                    ((HeaderCell) holder.itemView).setText(getString(R.string.OxContactsSpecialHeader));
                    break;
                }
                case VIEW_TYPE_USER: {
                    final long userId = userIdAt(position);
                    TLRPC.User user = userId == 0 ? null : MessagesController.getInstance(currentAccount).getUser(userId);
                    ContactStatusCell cell = (ContactStatusCell) holder.itemView;
                    final boolean online = ContactTracker.isOnline(currentAccount, user);
                    CharSequence status = user != null
                            ? ContactsFormat.statusText(currentAccount, user)
                            : getString(R.string.OxContactsOffline);
                    cell.setUser(currentAccount, user, status, online, position != usersEndRow - 1);
                    cell.setAction(R.drawable.msg_delete, getString(R.string.OxContactsRemove), v -> removeContact(userId));
                    break;
                }
                case VIEW_TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    cell.setTextAndCheck(getString(R.string.OxContactsNotify), SpecialContactsConfig.notificationsEnabled, false);
                    break;
                }
                case VIEW_TYPE_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == emptyInfoRow) {
                        cell.setText(getString(R.string.OxContactsSpecialEmpty));
                    } else {
                        cell.setText(getString(R.string.OxContactsNotifyInfo));
                    }
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == addRow) {
                return VIEW_TYPE_ADD;
            }
            if (position == headerRow) {
                return VIEW_TYPE_HEADER;
            }
            if (position == notifyRow) {
                return VIEW_TYPE_CHECK;
            }
            if (position == emptyInfoRow || position == notifyInfoRow) {
                return VIEW_TYPE_INFO;
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
