package org.telegram.ui.ayu.contactchanges;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.ayu.contactchanges.ContactChange;
import org.telegram.messenger.ayu.contactchanges.ContactChangesConfig;
import org.telegram.messenger.ayu.contactchanges.ContactChangesController;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.GraySectionCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EmptyTextProgressView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.ProfileActivity;

import java.util.ArrayList;
import java.util.Calendar;

/**
 * OcoderX "Contacts Changes" — the history of profile changes (name, username, phone, photo, bio) of
 * the user's contacts, newest first and grouped by day.
 * <p>
 * Recording happens in {@link ContactChangesController}; this screen only reads the database, so it
 * is safe to open and close it at any time.
 */
public class ContactChangesActivity extends BaseFragment implements ContactChangesController.Listener {

    private static final int MENU_OTHER = 100;
    private static final int MENU_TRACK = 1;
    private static final int MENU_CLEAR = 2;

    private static final int PAGE_SIZE = 120;

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_CHANGE = 1;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private EmptyTextProgressView emptyView;
    private LinearLayoutManager layoutManager;
    private ActionBarMenuItem otherItem;
    private ActionBarMenuSubItem trackItem;

    private final ArrayList<ContactChange> changes = new ArrayList<>();
    private final ArrayList<Item> items = new ArrayList<>();

    private boolean loading;
    private boolean endReached;

    private static class Item {
        final String header;
        final ContactChange change;

        Item(String header) {
            this.header = header;
            this.change = null;
        }

        Item(ContactChange change) {
            this.header = null;
            this.change = change;
        }
    }

    private ContactChangesController controller() {
        return ContactChangesController.getInstance(currentAccount);
    }

    @Override
    public boolean onFragmentCreate() {
        ContactChangesConfig.load();
        controller().start();
        controller().addListener(this);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        controller().removeListener(this);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.OxContactChangesTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_TRACK) {
                    toggleTracking();
                } else if (id == MENU_CLEAR) {
                    showClearDialog();
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        otherItem = menu.addItem(MENU_OTHER, R.drawable.ic_ab_other);
        otherItem.setContentDescription(getString(R.string.AccDescrMoreOptions));
        // icon 0: with needCheck the checkbox itself takes the icon slot
        trackItem = otherItem.addSubItem(MENU_TRACK, 0, getString(R.string.OxContactChangesTrack), true);
        otherItem.addSubItem(MENU_CLEAR, R.drawable.msg_delete, getString(R.string.OxContactChangesClear));
        updateTrackItem();

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        fragmentView = frameLayout;

        emptyView = new EmptyTextProgressView(context);
        emptyView.setText(getString(R.string.OxContactChangesEmpty));
        emptyView.showProgress();
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setEmptyView(emptyView);
        listView.setAdapter(adapter = new ListAdapter());
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= items.size()) {
                return;
            }
            ContactChange change = items.get(position).change;
            if (change == null || change.userId == 0) {
                return;
            }
            Bundle args = new Bundle();
            args.putLong("user_id", change.userId);
            presentFragment(new ProfileActivity(args));
        });
        listView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (loading || endReached) {
                    return;
                }
                if (layoutManager.findLastVisibleItemPosition() >= items.size() - 10) {
                    loadPage(false);
                }
            }
        });

        loadPage(true);
        return fragmentView;
    }

    // ------------------------------------------------------------------ data

    private void loadPage(boolean reset) {
        if (loading) {
            return;
        }
        loading = true;
        if (reset) {
            endReached = false;
            if (emptyView != null) {
                emptyView.showProgress();
            }
        }
        final int offset = reset ? 0 : changes.size();
        controller().loadChanges(PAGE_SIZE, offset, result -> {
            loading = false;
            if (offset == 0) {
                changes.clear();
            }
            if (result == null || result.isEmpty()) {
                endReached = true;
            } else {
                changes.addAll(result);
                endReached = result.size() < PAGE_SIZE;
            }
            rebuildItems();
            if (emptyView != null) {
                emptyView.showTextView();
            }
        });
    }

    private void rebuildItems() {
        items.clear();
        long currentDay = Long.MIN_VALUE;
        for (int i = 0; i < changes.size(); i++) {
            ContactChange change = changes.get(i);
            long day = dayStart(change.timestamp);
            if (day != currentDay) {
                currentDay = day;
                items.add(new Item(formatDay(day)));
            }
            items.add(new Item(change));
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private static long dayStart(long timestamp) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private static String formatDay(long dayStart) {
        long today = dayStart(System.currentTimeMillis());
        if (dayStart == today) {
            return getString(R.string.OxContactChangesToday);
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(today);
        calendar.add(Calendar.DAY_OF_YEAR, -1);
        if (dayStart == calendar.getTimeInMillis()) {
            return getString(R.string.OxContactChangesYesterday);
        }
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(dayStart);
        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)) {
            return LocaleController.getInstance().getFormatterDayMonth().format(dayStart);
        }
        return LocaleController.getInstance().getFormatterYear().format(dayStart);
    }

    // ------------------------------------------------------------------ actions

    private void updateTrackItem() {
        if (otherItem == null) {
            return;
        }
        boolean enabled = ContactChangesConfig.isEnabled();
        if (trackItem != null) {
            trackItem.setChecked(enabled);
        }
        if (actionBar != null) {
            actionBar.setSubtitle(enabled ? null : getString(R.string.OxContactChangesTrackDisabled));
        }
    }

    private void toggleTracking() {
        boolean enabled = !ContactChangesConfig.isEnabled();
        ContactChangesConfig.setEnabled(enabled);
        updateTrackItem();
        if (getParentActivity() != null) {
            BulletinFactory.of(this).createSimpleBulletin(
                    R.raw.info,
                    getString(enabled ? R.string.OxContactChangesTrackEnabled : R.string.OxContactChangesTrackDisabled)
            ).show();
        }
    }

    private void showClearDialog() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(R.string.OxContactChangesClear));
        builder.setMessage(getString(R.string.OxContactChangesClearConfirm));
        builder.setPositiveButton(getString(R.string.Delete), (dialog, which) -> controller().clearHistory(() -> {
            changes.clear();
            rebuildItems();
            if (getParentActivity() != null) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, getString(R.string.OxContactChangesCleared)).show();
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

    // ------------------------------------------------------------------ listener

    @Override
    public void onContactChangesUpdated(int account) {
        if (account != currentAccount || loading) {
            return;
        }
        loadPage(true);
    }

    // ------------------------------------------------------------------ adapter

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == VIEW_TYPE_CHANGE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == VIEW_TYPE_HEADER) {
                view = new GraySectionCell(parent.getContext(), getResourceProvider());
            } else {
                ContactChangeCell cell = new ContactChangeCell(parent.getContext(), currentAccount);
                cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ContactChangeCell.CELL_HEIGHT)));
                view = cell;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (position < 0 || position >= items.size()) {
                return;
            }
            Item item = items.get(position);
            if (holder.getItemViewType() == VIEW_TYPE_HEADER) {
                ((GraySectionCell) holder.itemView).setText(item.header);
            } else {
                boolean divider = position + 1 < items.size() && items.get(position + 1).change != null;
                ((ContactChangeCell) holder.itemView).setChange(item.change, divider);
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position < 0 || position >= items.size()) {
                return VIEW_TYPE_CHANGE;
            }
            return items.get(position).change == null ? VIEW_TYPE_HEADER : VIEW_TYPE_CHANGE;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
