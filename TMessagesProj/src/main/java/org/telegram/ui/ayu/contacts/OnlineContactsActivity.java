package org.telegram.ui.ayu.contacts;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
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
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.ProfileActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * OcoderX "Online contacts": every contact of the current account whose status is
 * {@code TL_userStatusOnline} right now, sorted by name, with a live header.
 * <p>
 * Read only and request free (safe in ghost mode): the list is rebuilt from the already cached
 * contact list and user statuses, and refreshed whenever Telegram posts
 * {@link NotificationCenter#updateInterfaces} with {@link MessagesController#UPDATE_MASK_STATUS} or
 * {@link NotificationCenter#contactsDidLoad}. A one minute ticker takes care of statuses that simply
 * expire without an update arriving.
 */
public class OnlineContactsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_USER = 1;
    private static final int VIEW_TYPE_INFO = 2;

    private static final long REFRESH_INTERVAL = 60_000L;

    private RecyclerListView listView;
    private ListAdapter adapter;

    private final ArrayList<TLRPC.User> onlineUsers = new ArrayList<>();
    private int totalContacts;

    private final Runnable updateRunnable = this::rebuild;
    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            rebuild();
            AndroidUtilities.runOnUIThread(this, REFRESH_INTERVAL);
        }
    };

    @Override
    public boolean onFragmentCreate() {
        SpecialContactsConfig.load();
        getNotificationCenter().addObserver(this, NotificationCenter.updateInterfaces);
        getNotificationCenter().addObserver(this, NotificationCenter.contactsDidLoad);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.updateInterfaces);
        getNotificationCenter().removeObserver(this, NotificationCenter.contactsDidLoad);
        AndroidUtilities.cancelRunOnUIThread(updateRunnable);
        AndroidUtilities.cancelRunOnUIThread(tickRunnable);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.OxContactsOnlineTitle));
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
            TLRPC.User user = userAt(position);
            if (user != null) {
                openProfile(user);
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            TLRPC.User user = userAt(position);
            if (user == null) {
                return false;
            }
            showOptions(view, user);
            return true;
        });

        rebuild();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        rebuild();
        AndroidUtilities.cancelRunOnUIThread(tickRunnable);
        AndroidUtilities.runOnUIThread(tickRunnable, REFRESH_INTERVAL);
    }

    @Override
    public void onPause() {
        super.onPause();
        AndroidUtilities.cancelRunOnUIThread(tickRunnable);
    }

    // ------------------------------------------------------------------ data

    private TLRPC.User userAt(int position) {
        int index = position - 1; // row 0 is the header
        if (index < 0 || index >= onlineUsers.size()) {
            return null;
        }
        return onlineUsers.get(index);
    }

    private void scheduleUpdate() {
        AndroidUtilities.cancelRunOnUIThread(updateRunnable);
        AndroidUtilities.runOnUIThread(updateRunnable, 250);
    }

    private void rebuild() {
        onlineUsers.clear();
        final MessagesController controller = MessagesController.getInstance(currentAccount);
        ArrayList<TLRPC.TL_contact> contacts;
        try {
            contacts = new ArrayList<>(ContactsController.getInstance(currentAccount).contacts);
        } catch (Throwable ignore) {
            contacts = new ArrayList<>();
        }
        totalContacts = contacts.size();
        for (int i = 0; i < contacts.size(); i++) {
            TLRPC.TL_contact contact = contacts.get(i);
            if (contact == null) {
                continue;
            }
            TLRPC.User user = controller.getUser(contact.user_id);
            if (ContactTracker.isOnline(currentAccount, user)) {
                onlineUsers.add(user);
            }
        }
        Collections.sort(onlineUsers, new Comparator<TLRPC.User>() {
            @Override
            public int compare(TLRPC.User a, TLRPC.User b) {
                String nameA = UserObject.getUserName(a);
                String nameB = UserObject.getUserName(b);
                if (nameA == null) {
                    nameA = "";
                }
                if (nameB == null) {
                    nameB = "";
                }
                return nameA.compareToIgnoreCase(nameB);
            }
        });
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private CharSequence headerText() {
        return LocaleController.formatString(R.string.OxContactsOnlineOfTotal, onlineUsers.size(), totalContacts);
    }

    // ------------------------------------------------------------------ actions

    private void openProfile(TLRPC.User user) {
        Bundle args = new Bundle();
        args.putLong("user_id", user.id);
        presentFragment(new ProfileActivity(args));
    }

    private void toggleSpecial(TLRPC.User user) {
        boolean special = SpecialContactsConfig.toggle(user.id);
        ContactTracker.getInstance().forget(user.id);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        if (getParentActivity() == null) {
            return;
        }
        BulletinFactory.of(this).createSimpleBulletin(
                special ? R.raw.contact_check : R.raw.chats_infotip,
                LocaleController.formatString(special ? R.string.OxContactsAddedToSpecial : R.string.OxContactsRemovedFromSpecial,
                        UserObject.getUserName(user))).show();
    }

    private void showOptions(View view, TLRPC.User user) {
        final boolean special = SpecialContactsConfig.isSpecial(user.id);
        ItemOptions options = ItemOptions.makeOptions(this, view);
        options.add(R.drawable.msg_openprofile, getString(R.string.OxContactsOpenProfile), () -> openProfile(user));
        options.add(special ? R.drawable.msg_unfave : R.drawable.msg_fave,
                getString(special ? R.string.OxContactsUnmarkSpecial : R.string.OxContactsMarkSpecial),
                () -> toggleSpecial(user));
        options.setGravity(Gravity.RIGHT);
        options.show();
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

    // ------------------------------------------------------------------ adapter

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == VIEW_TYPE_USER;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == VIEW_TYPE_HEADER) {
                HeaderCell cell = new HeaderCell(parent.getContext());
                cell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                view = cell;
            } else if (viewType == VIEW_TYPE_INFO) {
                TextInfoPrivacyCell cell = new TextInfoPrivacyCell(parent.getContext());
                cell.setText(getString(R.string.OxContactsOnlineEmpty));
                view = cell;
            } else {
                ContactStatusCell cell = new ContactStatusCell(parent.getContext());
                cell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                view = cell;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == VIEW_TYPE_HEADER) {
                ((HeaderCell) holder.itemView).setText(headerText());
                return;
            }
            if (holder.getItemViewType() == VIEW_TYPE_INFO) {
                return;
            }
            final TLRPC.User user = userAt(position);
            if (user == null) {
                return;
            }
            ContactStatusCell cell = (ContactStatusCell) holder.itemView;
            cell.setUser(currentAccount, user, ContactsFormat.statusText(currentAccount, user), true, position != onlineUsers.size());
            final boolean special = SpecialContactsConfig.isSpecial(user.id);
            cell.setAction(special ? R.drawable.msg_fave : R.drawable.msg_unfave,
                    getString(special ? R.string.OxContactsUnmarkSpecial : R.string.OxContactsMarkSpecial),
                    v -> toggleSpecial(user));
            cell.setActionActive(special);
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) {
                return VIEW_TYPE_HEADER;
            }
            return onlineUsers.isEmpty() ? VIEW_TYPE_INFO : VIEW_TYPE_USER;
        }

        @Override
        public int getItemCount() {
            return 1 + (onlineUsers.isEmpty() ? 1 : onlineUsers.size());
        }
    }
}
