package org.telegram.ui.ayu.upload;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.ayu.AyuKeepAliveService;
import org.telegram.messenger.ayu.upload.AyuUploadConfig;
import org.telegram.messenger.ayu.upload.AyuUploadManager;
import org.telegram.messenger.ayu.upload.UploadQueueItem;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

/**
 * AyuGram Upload Accelerator: the background upload queue and its settings.
 * <p>
 * The upper part lists everything the {@link AyuUploadManager} of the current account still has to
 * upload (pause / resume / cancel / move to top), the lower part is the accelerator configuration.
 */
public class UploadQueueActivity extends BaseFragment implements AyuUploadManager.Listener, UploadItemCell.Delegate {

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_ITEM = 1;
    private static final int VIEW_TYPE_CHECK = 2;
    private static final int VIEW_TYPE_SETTINGS = 3;
    private static final int VIEW_TYPE_SHADOW = 4;
    private static final int VIEW_TYPE_EMPTY = 5;

    private static final int ID_QUEUE_ENABLED = 1;
    private static final int ID_CONCURRENCY = 2;
    private static final int ID_KEEP_ALIVE = 3;
    private static final int ID_RETRIES = 4;
    private static final int ID_RESUME = 5;
    private static final int ID_REUSE_PARTS = 6;

    private RecyclerListView listView;
    private ListAdapter adapter;

    private final ArrayList<Row> rows = new ArrayList<>();

    private AyuUploadManager manager;

    private static class Row {
        final int viewType;
        final int id;
        final UploadQueueItem item;
        final CharSequence text;

        Row(int viewType, int id, CharSequence text, UploadQueueItem item) {
            this.viewType = viewType;
            this.id = id;
            this.text = text;
            this.item = item;
        }
    }

    @Override
    public boolean onFragmentCreate() {
        AyuUploadConfig.ensureLoaded();
        manager = AyuUploadManager.getInstance(currentAccount);
        manager.addListener(this);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        if (manager != null) {
            manager.removeListener(this);
        }
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.AyuUploadQueueTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        fragmentView = new FrameLayout(context);
        final FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutAnimation(null);
        listView.setAdapter(adapter = new ListAdapter());
        final DefaultItemAnimator itemAnimator = new DefaultItemAnimator();
        itemAnimator.setSupportsChangeAnimations(false);
        listView.setItemAnimator(itemAnimator);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setOnItemClickListener((view, position) -> onItemClick(view, position));

        updateRows();
        return fragmentView;
    }

    // ------------------------------------------------------------------ rows

    private void updateRows() {
        rows.clear();

        rows.add(new Row(VIEW_TYPE_HEADER, 0, LocaleController.getString(R.string.AyuUploadQueueSection), null));
        final ArrayList<UploadQueueItem> items = manager == null ? new ArrayList<>() : manager.getItems();
        if (items.isEmpty()) {
            rows.add(new Row(VIEW_TYPE_EMPTY, 0, LocaleController.getString(R.string.AyuUploadQueueEmpty), null));
        } else {
            for (int a = 0; a < items.size(); a++) {
                rows.add(new Row(VIEW_TYPE_ITEM, 0, null, items.get(a)));
            }
        }
        rows.add(new Row(VIEW_TYPE_SHADOW, 0, LocaleController.getString(R.string.AyuUploadQueueInfo), null));

        rows.add(new Row(VIEW_TYPE_HEADER, 0, LocaleController.getString(R.string.AyuUploadSettingsSection), null));
        rows.add(new Row(VIEW_TYPE_CHECK, ID_QUEUE_ENABLED, LocaleController.getString(R.string.AyuUploadQueueEnable), null));
        rows.add(new Row(VIEW_TYPE_SETTINGS, ID_CONCURRENCY, LocaleController.getString(R.string.AyuUploadConcurrency), null));
        rows.add(new Row(VIEW_TYPE_SETTINGS, ID_RETRIES, LocaleController.getString(R.string.AyuUploadRetries), null));
        rows.add(new Row(VIEW_TYPE_CHECK, ID_RESUME, LocaleController.getString(R.string.AyuUploadResumeOnNetworkLoss), null));
        rows.add(new Row(VIEW_TYPE_CHECK, ID_REUSE_PARTS, LocaleController.getString(R.string.AyuUploadReuseParts), null));
        rows.add(new Row(VIEW_TYPE_CHECK, ID_KEEP_ALIVE, LocaleController.getString(R.string.AyuUploadKeepAlive), null));
        rows.add(new Row(VIEW_TYPE_SHADOW, 0, LocaleController.getString(R.string.AyuUploadSettingsInfo), null));

        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void onItemClick(View view, int position) {
        if (position < 0 || position >= rows.size()) {
            return;
        }
        final Row row = rows.get(position);
        if (row.viewType == VIEW_TYPE_ITEM) {
            showItemMenu(row.item);
            return;
        }
        switch (row.id) {
            case ID_QUEUE_ENABLED:
                AyuUploadConfig.setQueueEnabled(!AyuUploadConfig.queueEnabled);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(AyuUploadConfig.queueEnabled);
                }
                AyuUploadManager.onConcurrencyChanged();
                updateRows();
                break;
            case ID_RESUME:
                AyuUploadConfig.setResumeAfterNetworkLoss(!AyuUploadConfig.resumeAfterNetworkLoss);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(AyuUploadConfig.resumeAfterNetworkLoss);
                }
                break;
            case ID_REUSE_PARTS:
                AyuUploadConfig.setReuseConfirmedParts(!AyuUploadConfig.reuseConfirmedParts);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(AyuUploadConfig.reuseConfirmedParts);
                }
                break;
            case ID_KEEP_ALIVE:
                AyuUploadConfig.setKeepAliveWhileUploading(!AyuUploadConfig.keepAliveWhileUploading);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(AyuUploadConfig.keepAliveWhileUploading);
                }
                AyuKeepAliveService.onUploadStatusChanged(getParentActivity());
                break;
            case ID_CONCURRENCY:
                showConcurrencyDialog();
                break;
            case ID_RETRIES:
                showRetriesDialog();
                break;
        }
    }

    private void showConcurrencyDialog() {
        if (getParentActivity() == null) {
            return;
        }
        final CharSequence[] values = new CharSequence[AyuUploadConfig.MAX_CONCURRENCY];
        for (int a = 0; a < values.length; a++) {
            values[a] = LocaleController.formatString(R.string.AyuUploadConcurrencyValue, a + 1);
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.AyuUploadConcurrency));
        builder.setItems(values, (dialog, which) -> {
            AyuUploadConfig.setConcurrency(which + 1);
            AyuUploadManager.onConcurrencyChanged();
            updateRows();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void showRetriesDialog() {
        if (getParentActivity() == null) {
            return;
        }
        final int[] options = {0, 3, 5, 8, 10};
        final CharSequence[] values = new CharSequence[options.length];
        for (int a = 0; a < options.length; a++) {
            values[a] = options[a] == 0
                    ? LocaleController.getString(R.string.AyuUploadRetriesOff)
                    : LocaleController.formatString(R.string.AyuUploadRetriesValue, options[a]);
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.AyuUploadRetries));
        builder.setItems(values, (dialog, which) -> {
            AyuUploadConfig.setPartRetries(options[which]);
            updateRows();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void showItemMenu(UploadQueueItem item) {
        if (item == null || getParentActivity() == null || manager == null) {
            return;
        }
        final boolean paused = item.state == UploadQueueItem.STATE_PAUSED || item.state == UploadQueueItem.STATE_ERROR;
        final CharSequence[] options = new CharSequence[]{
                LocaleController.getString(R.string.AyuUploadMoveToTop),
                LocaleController.getString(paused ? R.string.AyuUploadResume : R.string.AyuUploadPause),
                LocaleController.getString(R.string.AyuUploadCancel)
        };
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(item.name);
        builder.setItems(options, (dialog, which) -> {
            if (which == 0) {
                manager.moveToTop(item);
            } else if (which == 1) {
                manager.setPaused(item, !paused, false);
            } else {
                manager.cancel(item);
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    // ------------------------------------------------------------ callbacks

    @Override
    public void onPauseResumeClick(UploadQueueItem item) {
        if (manager == null || item == null) {
            return;
        }
        final boolean paused = item.state == UploadQueueItem.STATE_PAUSED || item.state == UploadQueueItem.STATE_ERROR;
        manager.setPaused(item, !paused, false);
    }

    @Override
    public void onUploadQueueChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            if (listView == null || adapter == null) {
                return;
            }
            updateRows();
        });
    }

    private CharSequence concurrencyValue() {
        return LocaleController.formatString(R.string.AyuUploadConcurrencyValue, AyuUploadConfig.getConcurrency());
    }

    private CharSequence retriesValue() {
        final int retries = AyuUploadConfig.getPartRetries();
        if (retries == 0) {
            return LocaleController.getString(R.string.AyuUploadRetriesOff);
        }
        return LocaleController.formatString(R.string.AyuUploadRetriesValue, retries);
    }

    // -------------------------------------------------------------- adapter

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case VIEW_TYPE_HEADER:
                    view = new HeaderCell(parent.getContext());
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_ITEM: {
                    final UploadItemCell cell = new UploadItemCell(parent.getContext());
                    cell.setDelegate(UploadQueueActivity.this);
                    view = cell;
                    break;
                }
                case VIEW_TYPE_CHECK:
                    view = new TextCheckCell(parent.getContext());
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_SETTINGS:
                    view = new TextSettingsCell(parent.getContext());
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_EMPTY: {
                    final TextInfoPrivacyCell cell = new TextInfoPrivacyCell(parent.getContext());
                    cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    view = cell;
                    break;
                }
                case VIEW_TYPE_SHADOW:
                default:
                    view = new TextInfoPrivacyCell(parent.getContext());
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (position < 0 || position >= rows.size()) {
                return;
            }
            final Row row = rows.get(position);
            final boolean divider = position + 1 < rows.size() && rows.get(position + 1).viewType == row.viewType;
            switch (holder.getItemViewType()) {
                case VIEW_TYPE_HEADER:
                    ((HeaderCell) holder.itemView).setText(row.text);
                    break;
                case VIEW_TYPE_ITEM:
                    ((UploadItemCell) holder.itemView).setItem(row.item, divider);
                    break;
                case VIEW_TYPE_CHECK: {
                    final TextCheckCell cell = (TextCheckCell) holder.itemView;
                    cell.setCheckBoxIcon(0);
                    boolean checked = false;
                    if (row.id == ID_QUEUE_ENABLED) {
                        checked = AyuUploadConfig.queueEnabled;
                    } else if (row.id == ID_RESUME) {
                        checked = AyuUploadConfig.resumeAfterNetworkLoss;
                    } else if (row.id == ID_REUSE_PARTS) {
                        checked = AyuUploadConfig.reuseConfirmedParts;
                    } else if (row.id == ID_KEEP_ALIVE) {
                        checked = AyuUploadConfig.keepAliveWhileUploading;
                    }
                    cell.setTextAndCheck(row.text, checked, true);
                    break;
                }
                case VIEW_TYPE_SETTINGS: {
                    final TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (row.id == ID_CONCURRENCY) {
                        cell.setTextAndValue(row.text, concurrencyValue(), true);
                    } else {
                        cell.setTextAndValue(row.text, retriesValue(), true);
                    }
                    break;
                }
                case VIEW_TYPE_EMPTY:
                case VIEW_TYPE_SHADOW: {
                    final TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setFixedSize(0);
                    cell.setText(row.text);
                    break;
                }
            }
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public int getItemViewType(int position) {
            if (position < 0 || position >= rows.size()) {
                return VIEW_TYPE_SHADOW;
            }
            return rows.get(position).viewType;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            final int type = holder.getItemViewType();
            return type == VIEW_TYPE_ITEM || type == VIEW_TYPE_CHECK || type == VIEW_TYPE_SETTINGS;
        }
    }
}
