package org.telegram.ui.ayu.duplicates;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.ayu.duplicates.DuplicateCleaner;
import org.telegram.messenger.ayu.duplicates.DuplicateScanner;
import org.telegram.messenger.ayu.duplicates.DuplicatesConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LineProgressView;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;

/**
 * AyuGram Duplicate Cleaner screen.
 * <p>
 * Scans the local Telegram media folders for byte-identical files, groups them and lets the user
 * keep exactly one copy per group. Deletion goes through {@link DuplicateCleaner}, which reuses the
 * same code path as the built-in cache screen.
 */
public class DuplicateCleanerActivity extends BaseFragment {

    // ---------------- view types ----------------
    private static final int VIEW_TYPE_BUTTON = 0;
    private static final int VIEW_TYPE_INFO = 1;
    private static final int VIEW_TYPE_HEADER = 2;
    private static final int VIEW_TYPE_VALUE = 3;
    private static final int VIEW_TYPE_GROUP = 4;
    private static final int VIEW_TYPE_PROGRESS = 5;
    private static final int VIEW_TYPE_CHECK = 6;

    private ListAdapter listAdapter;
    private RecyclerListView listView;

    private DuplicateScanner scanner;
    private DuplicateScanner.ScanResult scanResult;
    private final ArrayList<DuplicateScanner.DuplicateGroup> groups = new ArrayList<>();

    private boolean scanning;
    private boolean scannedInThisSession;
    private int progressStage;
    private int progressFilesDone;
    private int progressFilesTotal;
    private long progressBytesDone;
    private long progressBytesTotal;

    // ---------------- rows ----------------
    private int scanRow;
    private int scanInfoRow;
    private int optionsHeaderRow;
    private int stickerCacheRow;
    private int optionsInfoRow;
    private int summaryHeaderRow;
    private int summarySetsRow;
    private int summaryCopiesRow;
    private int summaryReclaimRow;
    private int summaryCheckedRow;
    private int summaryLastScanRow;
    private int summaryFreedRow;
    private int summaryInfoRow;
    private int groupsHeaderRow;
    private int groupsStartRow;
    private int groupsEndRow;
    private int cleanAllRow;
    private int groupsInfoRow;
    private int rowCount;

    @Override
    public boolean onFragmentCreate() {
        DuplicatesConfig.load();
        updateRows(false);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        if (scanner != null) {
            scanner.cancel();
            scanner = null;
        }
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.AyuDuplicates));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        listAdapter = new ListAdapter(context);

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setVerticalScrollBarEnabled(false);
        listView.setItemAnimator(null);
        listView.setLayoutAnimation(null);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(listAdapter);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position) -> onRowClick(view, position));
        return fragmentView;
    }

    // ---------------- rows ----------------

    private void updateRows(boolean notify) {
        rowCount = 0;
        scanRow = rowCount++;
        scanInfoRow = rowCount++;

        optionsHeaderRow = -1;
        stickerCacheRow = -1;
        optionsInfoRow = -1;
        if (!scanning) {
            optionsHeaderRow = rowCount++;
            stickerCacheRow = rowCount++;
            optionsInfoRow = rowCount++;
        }

        summaryHeaderRow = -1;
        summarySetsRow = -1;
        summaryCopiesRow = -1;
        summaryReclaimRow = -1;
        summaryCheckedRow = -1;
        summaryLastScanRow = -1;
        summaryFreedRow = -1;
        summaryInfoRow = -1;
        if (!scanning && DuplicatesConfig.hasLastScan()) {
            summaryHeaderRow = rowCount++;
            summarySetsRow = rowCount++;
            summaryCopiesRow = rowCount++;
            summaryReclaimRow = rowCount++;
            summaryCheckedRow = rowCount++;
            summaryLastScanRow = rowCount++;
            if (DuplicatesConfig.totalFreedBytes > 0) {
                summaryFreedRow = rowCount++;
            }
            summaryInfoRow = rowCount++;
        }

        groupsHeaderRow = -1;
        groupsStartRow = -1;
        groupsEndRow = -1;
        cleanAllRow = -1;
        groupsInfoRow = -1;
        if (!scanning && !groups.isEmpty()) {
            groupsHeaderRow = rowCount++;
            groupsStartRow = rowCount;
            rowCount += groups.size();
            groupsEndRow = rowCount;
            cleanAllRow = rowCount++;
            groupsInfoRow = rowCount++;
        }

        if (notify && listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private CharSequence getScanInfoText() {
        if (scanning) {
            return getString(R.string.AyuDuplicatesScanInfo);
        }
        if (scannedInThisSession && groups.isEmpty()) {
            return getString(R.string.AyuDuplicatesNothingFound);
        }
        if (!DuplicatesConfig.hasLastScan()) {
            return getString(R.string.AyuDuplicatesNotScannedYet);
        }
        return getString(R.string.AyuDuplicatesScanInfo);
    }

    private String getStageText() {
        switch (progressStage) {
            case DuplicateScanner.STAGE_HASHING:
                return getString(R.string.AyuDuplicatesStageCompare);
            case DuplicateScanner.STAGE_RESOLVING:
                return getString(R.string.AyuDuplicatesStageChats);
            case DuplicateScanner.STAGE_ENUMERATING:
            default:
                return getString(R.string.AyuDuplicatesStageFiles);
        }
    }

    private String getProgressText() {
        if (progressFilesTotal > 0) {
            return LocaleController.formatString(R.string.AyuDuplicatesProgressFiles,
                    Math.min(progressFilesDone, progressFilesTotal), progressFilesTotal,
                    AndroidUtilities.formatFileSize(progressBytesDone));
        }
        return LocaleController.formatString(R.string.AyuDuplicatesProgressFilesUnknown,
                progressFilesDone, AndroidUtilities.formatFileSize(progressBytesDone));
    }

    private float getProgressFraction() {
        if (progressBytesTotal > 0) {
            return Math.min(1f, (float) progressBytesDone / progressBytesTotal);
        }
        if (progressFilesTotal > 0) {
            return Math.min(1f, (float) progressFilesDone / progressFilesTotal);
        }
        return 0f;
    }

    // ---------------- scanning ----------------

    private void startScan() {
        if (scanning) {
            return;
        }
        scanning = true;
        scannedInThisSession = false;
        progressStage = DuplicateScanner.STAGE_ENUMERATING;
        progressFilesDone = 0;
        progressFilesTotal = 0;
        progressBytesDone = 0;
        progressBytesTotal = 0;
        groups.clear();
        scanResult = null;
        updateRows(true);

        scanner = new DuplicateScanner(currentAccount);
        scanner.start(new DuplicateScanner.Callback() {
            @Override
            public void onProgress(int stage, int filesDone, int filesTotal, long bytesDone, long bytesTotal) {
                if (!scanning) {
                    return;
                }
                progressStage = stage;
                progressFilesDone = filesDone;
                progressFilesTotal = filesTotal;
                progressBytesDone = bytesDone;
                progressBytesTotal = bytesTotal;
                updateProgressCell();
            }

            @Override
            public void onFinished(DuplicateScanner.ScanResult result) {
                scanning = false;
                scanner = null;
                scannedInThisSession = true;
                scanResult = result;
                getMessagesController().putUsers(result.users, true);
                getMessagesController().putChats(result.chats, true);
                resolveChatNames(result);
                groups.clear();
                groups.addAll(result.groups);
                updateRows(true);
            }

            @Override
            public void onCancelled() {
                scanning = false;
                scanner = null;
                updateRows(true);
                if (getParentActivity() != null) {
                    BulletinFactory.of(DuplicateCleanerActivity.this)
                            .createSimpleBulletin(R.raw.chats_infotip, getString(R.string.AyuDuplicatesScanCancelled))
                            .show();
                }
            }
        });
    }

    private void cancelScan() {
        if (scanner != null) {
            scanner.cancel();
        }
    }

    private void resolveChatNames(DuplicateScanner.ScanResult result) {
        final String unknown = getString(R.string.AyuDuplicatesUnknownChat);
        for (int i = 0; i < result.groups.size(); i++) {
            DuplicateScanner.DuplicateGroup group = result.groups.get(i);
            for (int j = 0; j < group.files.size(); j++) {
                DuplicateScanner.DuplicateFile file = group.files.get(j);
                if (file.dialogId == 0) {
                    file.chatName = unknown;
                    continue;
                }
                String name = DialogObject.getName(currentAccount, file.dialogId);
                file.chatName = TextUtils.isEmpty(name) ? unknown : name;
            }
        }
    }

    private void updateProgressCell() {
        if (listView == null) {
            return;
        }
        RecyclerView.ViewHolder holder = listView.findViewHolderForAdapterPosition(scanRow);
        if (holder != null && holder.itemView instanceof ProgressCell) {
            ((ProgressCell) holder.itemView).update(getStageText(), getProgressText(), getProgressFraction());
        } else if (listAdapter != null) {
            listAdapter.notifyItemChanged(scanRow);
        }
    }

    // ---------------- clicks ----------------

    private void onRowClick(View view, int position) {
        if (position == scanRow) {
            if (scanning) {
                cancelScan();
            } else {
                startScan();
            }
            return;
        }
        if (position == stickerCacheRow) {
            DuplicatesConfig.setIncludeStickerCache(!DuplicatesConfig.includeStickerCache);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(DuplicatesConfig.includeStickerCache);
            }
            return;
        }
        if (position == cleanAllRow) {
            confirmCleanAll();
            return;
        }
        if (groupsStartRow >= 0 && position >= groupsStartRow && position < groupsEndRow) {
            showGroupOptions(view, groups.get(position - groupsStartRow));
        }
    }

    private void showGroupOptions(View view, DuplicateScanner.DuplicateGroup group) {
        if (getParentActivity() == null || group == null) {
            return;
        }
        ItemOptions options = ItemOptions.makeOptions(this, view);

        DuplicateScanner.DuplicateFile openable = group.findOpenable();
        if (openable != null) {
            options.add(R.drawable.msg_viewintopic, getString(R.string.AyuDuplicatesOpenMessage), () -> openMessage(openable));
        }

        options.add(R.drawable.msg_clear, getString(R.string.AyuDuplicatesKeepOldest),
                () -> confirmDeleteGroup(group, group.getOldest()));
        options.add(R.drawable.msg_clear, getString(R.string.AyuDuplicatesKeepNewest),
                () -> confirmDeleteGroup(group, group.getNewest()));

        HashSet<Long> seen = new HashSet<>();
        for (int i = 0; i < group.files.size(); i++) {
            final DuplicateScanner.DuplicateFile file = group.files.get(i);
            if (file.dialogId == 0 || TextUtils.isEmpty(file.chatName) || !seen.add(file.dialogId)) {
                continue;
            }
            options.add(R.drawable.msg_media,
                    LocaleController.formatString(R.string.AyuDuplicatesKeepInChat, file.chatName),
                    () -> confirmDeleteGroup(group, file));
        }

        options.setGravity(Gravity.RIGHT);
        options.show();
    }

    private void openMessage(DuplicateScanner.DuplicateFile file) {
        if (file == null || !file.canOpenMessage()) {
            return;
        }
        Bundle args = new Bundle();
        if (file.dialogId > 0) {
            args.putLong("user_id", file.dialogId);
        } else {
            args.putLong("chat_id", -file.dialogId);
        }
        args.putInt("message_id", file.messageId);
        presentFragment(new ChatActivity(args));
    }

    // ---------------- deletion ----------------

    private void confirmDeleteGroup(DuplicateScanner.DuplicateGroup group, DuplicateScanner.DuplicateFile keep) {
        if (group == null || keep == null || getParentActivity() == null) {
            return;
        }
        final ArrayList<DuplicateScanner.DuplicateFile> toDelete = new ArrayList<>();
        long bytes = 0;
        for (int i = 0; i < group.files.size(); i++) {
            DuplicateScanner.DuplicateFile file = group.files.get(i);
            if (file == keep) {
                continue;
            }
            toDelete.add(file);
            bytes += file.size;
        }
        if (toDelete.isEmpty()) {
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.AyuDuplicatesNothingToDelete)).show();
            return;
        }
        showDeleteConfirmation(
                LocaleController.formatString(R.string.AyuDuplicatesCleanGroupMessage,
                        toDelete.size(), AndroidUtilities.formatFileSize(bytes)),
                toDelete, keep);
    }

    private void confirmCleanAll() {
        if (getParentActivity() == null || groups.isEmpty()) {
            return;
        }
        final ArrayList<DuplicateScanner.DuplicateFile> toDelete = new ArrayList<>();
        long bytes = 0;
        for (int i = 0; i < groups.size(); i++) {
            DuplicateScanner.DuplicateGroup group = groups.get(i);
            DuplicateScanner.DuplicateFile keep = group.getOldest();
            for (int j = 0; j < group.files.size(); j++) {
                DuplicateScanner.DuplicateFile file = group.files.get(j);
                if (file == keep) {
                    continue;
                }
                toDelete.add(file);
                bytes += file.size;
            }
        }
        if (toDelete.isEmpty()) {
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.AyuDuplicatesNothingToDelete)).show();
            return;
        }
        showDeleteConfirmation(
                LocaleController.formatString(R.string.AyuDuplicatesCleanAllMessage,
                        toDelete.size(), AndroidUtilities.formatFileSize(bytes)),
                toDelete, null);
    }

    private void showDeleteConfirmation(CharSequence message,
                                        ArrayList<DuplicateScanner.DuplicateFile> toDelete,
                                        DuplicateScanner.DuplicateFile survivor) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.AyuDuplicatesCleanAllTitle));
        builder.setMessage(message);
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.setPositiveButton(getString(R.string.AyuDuplicatesDelete), (dialog, which) -> performDelete(toDelete, survivor));
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        View button = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (button instanceof TextView) {
            ((TextView) button).setTextColor(getThemedColor(Theme.key_text_RedBold));
        }
    }

    private void performDelete(ArrayList<DuplicateScanner.DuplicateFile> toDelete,
                               DuplicateScanner.DuplicateFile survivor) {
        if (toDelete == null || toDelete.isEmpty()) {
            return;
        }
        final AlertDialog progressDialog = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progressDialog.setCanCancel(false);
        progressDialog.showDelayed(300);

        final HashSet<DuplicateScanner.DuplicateFile> removed = new HashSet<>(toDelete);
        DuplicateCleaner.delete(currentAccount, toDelete, false, (deletedFiles, freedBytes) -> {
            try {
                progressDialog.dismiss();
            } catch (Throwable ignore) {
            }
            applyRemoval(removed);
            updateRows(true);
            showDeletedBulletin(deletedFiles, freedBytes, survivor);
        });
    }

    private void applyRemoval(HashSet<DuplicateScanner.DuplicateFile> removed) {
        for (int i = groups.size() - 1; i >= 0; i--) {
            DuplicateScanner.DuplicateGroup group = groups.get(i);
            group.files.removeAll(removed);
            if (group.files.size() < 2) {
                groups.remove(i);
            }
        }
        if (scanResult != null) {
            scanResult.groups.clear();
            scanResult.groups.addAll(groups);
            scanResult.duplicateFiles = 0;
            scanResult.reclaimableBytes = 0;
            for (int i = 0; i < groups.size(); i++) {
                scanResult.duplicateFiles += groups.get(i).getCopies() - 1;
                scanResult.reclaimableBytes += groups.get(i).getReclaimableBytes();
            }
            DuplicatesConfig.saveLastScan(groups.size(), scanResult.duplicateFiles,
                    scanResult.reclaimableBytes, scanResult.totalFilesScanned, scanResult.totalBytesScanned);
        }
    }

    private void showDeletedBulletin(int deletedFiles, long freedBytes, DuplicateScanner.DuplicateFile survivor) {
        if (getParentActivity() == null) {
            return;
        }
        final CharSequence text = LocaleController.formatString(R.string.AyuDuplicatesDeletedBulletin,
                deletedFiles, AndroidUtilities.formatFileSize(freedBytes));
        if (survivor != null && survivor.canOpenMessage()) {
            BulletinFactory.of(this)
                    .createSimpleBulletin(R.raw.ic_delete, text, getString(R.string.AyuDuplicatesOpenKept),
                            () -> openMessage(survivor))
                    .show();
        } else {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_delete, text).show();
        }
    }

    // ---------------- adapter ----------------

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context mContext;

        ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = holder.getItemViewType();
            return type == VIEW_TYPE_BUTTON || type == VIEW_TYPE_GROUP || type == VIEW_TYPE_CHECK || type == VIEW_TYPE_PROGRESS;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == scanRow) {
                return scanning ? VIEW_TYPE_PROGRESS : VIEW_TYPE_BUTTON;
            }
            if (position == cleanAllRow) {
                return VIEW_TYPE_BUTTON;
            }
            if (position == scanInfoRow || position == summaryInfoRow || position == groupsInfoRow || position == optionsInfoRow) {
                return VIEW_TYPE_INFO;
            }
            if (position == summaryHeaderRow || position == groupsHeaderRow || position == optionsHeaderRow) {
                return VIEW_TYPE_HEADER;
            }
            if (position == stickerCacheRow) {
                return VIEW_TYPE_CHECK;
            }
            if (groupsStartRow >= 0 && position >= groupsStartRow && position < groupsEndRow) {
                return VIEW_TYPE_GROUP;
            }
            return VIEW_TYPE_VALUE;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case VIEW_TYPE_INFO:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                case VIEW_TYPE_HEADER:
                    view = new HeaderCell(mContext);
                    view.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_VALUE:
                    view = new TextSettingsCell(mContext);
                    view.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_GROUP:
                    view = new GroupCell(mContext);
                    view.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_PROGRESS:
                    view = new ProgressCell(mContext);
                    view.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_CHECK:
                    view = new TextCheckCell(mContext);
                    view.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_BUTTON:
                default:
                    view = new TextCell(mContext);
                    view.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case VIEW_TYPE_BUTTON: {
                    TextCell cell = (TextCell) holder.itemView;
                    if (position == scanRow) {
                        cell.setTextAndIcon(getString(DuplicatesConfig.hasLastScan()
                                ? R.string.AyuDuplicatesScanAgain : R.string.AyuDuplicatesScan), R.drawable.msg_media, false);
                        cell.setColors(Theme.key_windowBackgroundWhiteBlueText4, Theme.key_windowBackgroundWhiteBlueText4);
                    } else {
                        cell.setTextAndIcon(getString(R.string.AyuDuplicatesCleanAll), R.drawable.msg_delete, false);
                        cell.setColors(Theme.key_text_RedRegular, Theme.key_text_RedRegular);
                    }
                    break;
                }
                case VIEW_TYPE_PROGRESS: {
                    ProgressCell cell = (ProgressCell) holder.itemView;
                    cell.update(getStageText(), getProgressText(), getProgressFraction());
                    break;
                }
                case VIEW_TYPE_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == scanInfoRow) {
                        cell.setText(getScanInfoText());
                    } else if (position == optionsInfoRow) {
                        cell.setText(getString(R.string.AyuDuplicatesIncludeStickerCacheInfo));
                    } else if (position == groupsInfoRow) {
                        cell.setText(getString(R.string.AyuDuplicatesFoundInfo));
                    } else {
                        cell.setText(null);
                    }
                    if (position == rowCount - 1) {
                        cell.setBackground(Theme.getThemedDrawableByKey(mContext, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                    } else {
                        cell.setBackground(Theme.getThemedDrawableByKey(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    }
                    break;
                }
                case VIEW_TYPE_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    if (position == summaryHeaderRow) {
                        cell.setText(getString(R.string.AyuDuplicatesSummary));
                    } else if (position == optionsHeaderRow) {
                        cell.setText(getString(R.string.AyuDuplicatesOptions));
                    } else {
                        cell.setText(getString(R.string.AyuDuplicatesFound));
                    }
                    break;
                }
                case VIEW_TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    cell.setTextAndCheck(getString(R.string.AyuDuplicatesIncludeStickerCache),
                            DuplicatesConfig.includeStickerCache, false);
                    break;
                }
                case VIEW_TYPE_VALUE: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (position == summarySetsRow) {
                        cell.setTextAndValue(getString(R.string.AyuDuplicatesSets),
                                String.format(Locale.US, "%d", DuplicatesConfig.lastScanGroups), false, true);
                    } else if (position == summaryCopiesRow) {
                        cell.setTextAndValue(getString(R.string.AyuDuplicatesExtraCopies),
                                String.format(Locale.US, "%d", DuplicatesConfig.lastScanDuplicateFiles), false, true);
                    } else if (position == summaryReclaimRow) {
                        cell.setTextAndValue(getString(R.string.AyuDuplicatesReclaimable),
                                AndroidUtilities.formatFileSize(DuplicatesConfig.lastScanReclaimable), false, true);
                    } else if (position == summaryCheckedRow) {
                        cell.setTextAndValue(getString(R.string.AyuDuplicatesChecked),
                                String.format(Locale.US, "%d", DuplicatesConfig.lastScanTotalFiles), false, true);
                    } else if (position == summaryLastScanRow) {
                        String value = DuplicatesConfig.lastScanTime > 0
                                ? LocaleController.formatDateAudio(DuplicatesConfig.lastScanTime / 1000L, true)
                                : getString(R.string.AyuDuplicatesNever);
                        cell.setTextAndValue(getString(R.string.AyuDuplicatesLastScan), value, false, summaryFreedRow >= 0);
                    } else if (position == summaryFreedRow) {
                        cell.setTextAndValue(getString(R.string.AyuDuplicatesFreedTotal),
                                AndroidUtilities.formatFileSize(DuplicatesConfig.totalFreedBytes), false, false);
                    }
                    break;
                }
                case VIEW_TYPE_GROUP: {
                    GroupCell cell = (GroupCell) holder.itemView;
                    int index = position - groupsStartRow;
                    cell.setGroup(groups.get(index), index != groups.size() - 1);
                    break;
                }
            }
        }
    }

    // ---------------- cells ----------------

    /** scan progress row: stage text, counters and a thin progress line */
    private class ProgressCell extends FrameLayout {

        private final TextView titleView;
        private final TextView subtitleView;
        private final LineProgressView progressView;

        ProgressCell(Context context) {
            super(context);

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.TOP | Gravity.LEFT, 21, 10, 21, 0));

            subtitleView = new TextView(context);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
            subtitleView.setSingleLine(true);
            subtitleView.setEllipsize(TextUtils.TruncateAt.END);
            addView(subtitleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.TOP | Gravity.LEFT, 21, 33, 21, 0));

            progressView = new LineProgressView(context);
            progressView.setProgressColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText4));
            progressView.setBackColor(getThemedColor(Theme.key_windowBackgroundGray));
            addView(progressView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 2,
                    Gravity.TOP | Gravity.LEFT, 21, 58, 21, 0));

            TextView cancelView = new TextView(context);
            cancelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            cancelView.setTextColor(getThemedColor(Theme.key_text_RedRegular));
            cancelView.setText(getString(R.string.AyuDuplicatesCancelScan));
            addView(cancelView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.TOP | Gravity.LEFT, 21, 66, 21, 0));

            setMinimumHeight(dp(94));
        }

        void update(String title, String subtitle, float progress) {
            titleView.setText(title);
            subtitleView.setText(subtitle);
            progressView.setProgress(progress, true);
        }
    }

    /** one duplicate set: thumbnail, name, size + copies, chats */
    private class GroupCell extends FrameLayout {

        private final BackupImageView imageView;
        private final TextView nameView;
        private final TextView infoView;
        private final TextView chatsView;
        private final TextView sizeView;

        private final RectF placeholderRect = new RectF();
        private final Paint placeholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint extPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private boolean needDivider;
        private boolean showPlaceholder;
        private String extension = "";

        GroupCell(Context context) {
            super(context);
            setWillNotDraw(false);

            imageView = new BackupImageView(context);
            imageView.setRoundRadius(dp(6));
            addView(imageView, LayoutHelper.createFrame(46, 46, Gravity.TOP | Gravity.LEFT, 15, 10, 0, 0));

            nameView = new TextView(context);
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            nameView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            addView(nameView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.TOP | Gravity.LEFT, 73, 8, 80, 0));

            infoView = new TextView(context);
            infoView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            infoView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
            infoView.setSingleLine(true);
            infoView.setEllipsize(TextUtils.TruncateAt.END);
            addView(infoView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.TOP | Gravity.LEFT, 73, 30, 80, 0));

            chatsView = new TextView(context);
            chatsView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            chatsView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            chatsView.setSingleLine(true);
            chatsView.setEllipsize(TextUtils.TruncateAt.END);
            addView(chatsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.TOP | Gravity.LEFT, 73, 48, 16, 0));

            sizeView = new TextView(context);
            sizeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            sizeView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText4));
            sizeView.setSingleLine(true);
            sizeView.setGravity(Gravity.RIGHT);
            addView(sizeView, LayoutHelper.createFrame(76, LayoutHelper.WRAP_CONTENT,
                    Gravity.TOP | Gravity.RIGHT, 0, 9, 16, 0));

            extPaint.setColor(0xFFFFFFFF);
            extPaint.setTextSize(dp(11));
            extPaint.setTextAlign(Paint.Align.CENTER);
            extPaint.setTypeface(AndroidUtilities.bold());

            setMinimumHeight(dp(74));
        }

        void setGroup(DuplicateScanner.DuplicateGroup group, boolean divider) {
            needDivider = divider;

            nameView.setText(group.getDisplayName());
            infoView.setText(String.format(Locale.US, "%s · %s",
                    AndroidUtilities.formatFileSize(group.size),
                    LocaleController.formatString(R.string.AyuDuplicatesCopiesCount, group.getCopies())));
            chatsView.setText(buildChatsText(group));
            sizeView.setText(AndroidUtilities.formatFileSize(group.getReclaimableBytes()));

            final String path = group.files.get(0).path;
            extension = Utilities.getExtension(group.files.get(0).file.getName());
            if (extension == null) {
                extension = "";
            }
            final String lower = extension.toLowerCase(Locale.US);
            ColorDrawable placeholder = new ColorDrawable(getThemedColor(Theme.key_chat_attachPhotoBackground));
            if (isVideoExtension(lower)) {
                showPlaceholder = false;
                imageView.setImage(ImageLocation.getForPath("vthumb://0:" + path), "56_56", placeholder, null);
            } else if (isImageExtension(lower)) {
                showPlaceholder = false;
                imageView.setImage(ImageLocation.getForPath("thumb://0:" + path), "56_56", placeholder, null);
            } else {
                showPlaceholder = true;
                imageView.setImageDrawable(null);
            }
            placeholderPaint.setColor(getThemedColor(Theme.key_chat_attachPhotoBackground));
            invalidate();
        }

        private CharSequence buildChatsText(DuplicateScanner.DuplicateGroup group) {
            ArrayList<String> names = new ArrayList<>();
            for (int i = 0; i < group.files.size(); i++) {
                String name = group.files.get(i).chatName;
                if (TextUtils.isEmpty(name)) {
                    name = getString(R.string.AyuDuplicatesUnknownChat);
                }
                if (!names.contains(name)) {
                    names.add(name);
                }
            }
            if (names.isEmpty()) {
                return getString(R.string.AyuDuplicatesUnknownChat);
            }
            if (names.size() <= 2) {
                return TextUtils.join(", ", names);
            }
            return TextUtils.join(", ", names.subList(0, 2)) + ", "
                    + LocaleController.formatString(R.string.AyuDuplicatesMoreChats, names.size() - 2);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (showPlaceholder) {
                placeholderRect.set(dp(15), dp(10), dp(15 + 46), dp(10 + 46));
                canvas.drawRoundRect(placeholderRect, dp(6), dp(6), placeholderPaint);
                if (!TextUtils.isEmpty(extension)) {
                    String label = extension.toUpperCase(Locale.US);
                    if (label.length() > 4) {
                        label = label.substring(0, 4);
                    }
                    canvas.drawText(label, placeholderRect.centerX(), placeholderRect.centerY() + dp(4), extPaint);
                }
            }
            if (needDivider) {
                canvas.drawLine(dp(73), getMeasuredHeight() - 1, getMeasuredWidth(), getMeasuredHeight() - 1, Theme.dividerPaint);
            }
        }
    }

    private static boolean isVideoExtension(String ext) {
        return "mp4".equals(ext) || "mov".equals(ext) || "mkv".equals(ext) || "webm".equals(ext) || "avi".equals(ext);
    }

    private static boolean isImageExtension(String ext) {
        return "jpg".equals(ext) || "jpeg".equals(ext) || "png".equals(ext) || "webp".equals(ext)
                || "gif".equals(ext) || "bmp".equals(ext) || "heic".equals(ext);
    }

    // ---------------- theme ----------------

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> descriptions = new ArrayList<>();
        descriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_actionBarDefault));
        descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));
        descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{TextSettingsCell.class, TextCell.class, TextCheckCell.class, HeaderCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
        descriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUNDFILTER, new Class[]{TextInfoPrivacyCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));
        descriptions.add(new ThemeDescription(listView, 0, new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider));
        return descriptions;
    }

    /** exposed so the integration agent can prefill the hub row subtitle */
    public static CharSequence getHubValue() {
        DuplicatesConfig.load();
        if (!DuplicatesConfig.hasLastScan() || DuplicatesConfig.lastScanReclaimable <= 0) {
            return null;
        }
        return AndroidUtilities.formatFileSize(DuplicatesConfig.lastScanReclaimable);
    }
}
