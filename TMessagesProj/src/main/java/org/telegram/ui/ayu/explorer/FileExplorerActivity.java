package org.telegram.ui.ayu.explorer;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.ayu.explorer.FileEntry;
import org.telegram.messenger.ayu.explorer.FileExplorerConfig;
import org.telegram.messenger.ayu.explorer.FileIndexer;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LineProgressView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * AyuGram File Explorer: the root screen. Shows how much space the files of this account take and
 * offers the four ways into the index (by chat, by type, by size, by date).
 */
public class FileExplorerActivity extends UniversalFragment implements FileIndexer.Listener {

    private static final int ROW_ON_DEVICE = 1;
    private static final int ROW_TOTAL = 2;
    private static final int ROW_FREE = 3;

    private static final int ROW_BY_CHAT = 10;
    private static final int ROW_BY_TYPE = 11;
    private static final int ROW_BY_SIZE = 12;
    private static final int ROW_BY_DATE = 13;

    private static final int ROW_ONLY_DOWNLOADED = 20;
    private static final int ROW_SCAN_DOWNLOADS = 21;
    private static final int ROW_REFRESH = 22;

    private LineProgressView progressView;
    private long deviceFreeSpace;

    public FileExplorerActivity() {
        super();
        FileExplorerConfig.load();
    }

    @Override
    public boolean onFragmentCreate() {
        FileIndexer.getInstance(currentAccount).addListener(this);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        FileIndexer.getInstance(currentAccount).removeListener(this);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        super.createView(context);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);

        if (fragmentView instanceof FrameLayout) {
            progressView = new LineProgressView(context);
            progressView.setProgressColor(Theme.getColor(Theme.key_sharedMedia_startStopLoadIcon));
            progressView.setVisibility(View.GONE);
            ((FrameLayout) fragmentView).addView(progressView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 2, Gravity.TOP | Gravity.LEFT));
        }

        FileExplorerUtils.loadDeviceFreeSpace(value -> {
            deviceFreeSpace = value;
            update();
        });
        FileIndexer.getInstance(currentAccount).load(false);
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        update();
    }

    private void update() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    // ------------------------------------------------------------------ indexer

    @Override
    public void onFileIndexProgress(float progress) {
        if (progressView != null) {
            progressView.setVisibility(View.VISIBLE);
            progressView.setProgress(progress, true);
        }
    }

    @Override
    public void onFileIndexUpdated(FileIndexer.Index index) {
        if (progressView != null) {
            progressView.setProgress(1f, true);
            progressView.setVisibility(View.GONE);
        }
        update();
    }

    // ------------------------------------------------------------------ list

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.AyuFileExplorer);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final FileIndexer indexer = FileIndexer.getInstance(currentAccount);
        final FileIndexer.Index index = indexer.getIndex();
        final boolean loading = indexer.isLoading() || index == null;

        items.add(UItem.asHeader(getString(R.string.AyuFeStorageHeader)));
        items.add(UItem.asSettingsCell(ROW_ON_DEVICE, R.drawable.msg_filled_storageusage, getString(R.string.AyuFeOnThisDevice),
                loading ? getString(R.string.AyuFeIndexing) : AndroidUtilities.formatFileSize(index.downloadedSize)));
        items.add(UItem.asSettingsCell(ROW_TOTAL, R.drawable.msg_filled_datausage, getString(R.string.AyuFeTotalIndexed),
                loading ? getString(R.string.AyuFeIndexing) : FileExplorerUtils.formatFilesCount(index.entries.size(), index.totalSize)));
        items.add(UItem.asSettingsCell(ROW_FREE, R.drawable.msg_clearcache, getString(R.string.AyuFeFreeSpace),
                deviceFreeSpace > 0 ? AndroidUtilities.formatFileSize(deviceFreeSpace) : ""));
        items.add(UItem.asShadow(getString(R.string.AyuFeStorageInfo)));

        items.add(UItem.asHeader(getString(R.string.AyuFeBrowseHeader)));
        items.add(UItem.asSettingsCell(ROW_BY_CHAT, R.drawable.msg_folders, getString(R.string.AyuFeByChat),
                loading ? "" : LocaleController.formatPluralString("AyuFeChats", countChats(index))));
        items.add(UItem.asSettingsCell(ROW_BY_TYPE, R.drawable.msg_media, getString(R.string.AyuFeByType),
                loading ? "" : LocaleController.formatPluralString("AyuFeTypes", countTypes(index))));
        items.add(UItem.asSettingsCell(ROW_BY_SIZE, R.drawable.msg_filled_data_files, getString(R.string.AyuFeBySize),
                getString(R.string.AyuFeBySizeValue)));
        items.add(UItem.asSettingsCell(ROW_BY_DATE, R.drawable.msg_calendar2, getString(R.string.AyuFeByDate),
                getString(R.string.AyuFeByDateValue)));
        items.add(UItem.asShadow(getString(R.string.AyuFeBrowseInfo)));

        items.add(UItem.asHeader(getString(R.string.AyuFeOptionsHeader)));
        items.add(UItem.asCheck(ROW_ONLY_DOWNLOADED, getString(R.string.AyuFeOnlyDownloaded)).setChecked(FileExplorerConfig.onlyDownloaded));
        items.add(UItem.asCheck(ROW_SCAN_DOWNLOADS, getString(R.string.AyuFeScanDownloads)).setChecked(FileExplorerConfig.includeOrphanFiles));
        items.add(UItem.asButton(ROW_REFRESH, R.drawable.msg_reset, getString(R.string.AyuFeRefresh)));
        items.add(UItem.asShadow(getString(R.string.AyuFeOptionsInfo)));
    }

    private int countChats(FileIndexer.Index index) {
        int count = 0;
        for (int i = 0; i < index.folders.size(); i++) {
            final FileIndexer.Folder folder = index.folders.get(i);
            if (FileExplorerConfig.onlyDownloaded ? folder.downloadedCount > 0 : !folder.entries.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private int countTypes(FileIndexer.Index index) {
        int count = 0;
        for (int category = 0; category < FileEntry.CATEGORY_COUNT; category++) {
            final int c = FileExplorerConfig.onlyDownloaded ? index.categoryDownloadedCount[category] : index.categoryCount[category];
            if (c > 0) {
                count++;
            }
        }
        return count;
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ROW_BY_CHAT:
                presentFragment(FileExplorerListActivity.chats());
                break;
            case ROW_BY_TYPE:
                presentFragment(FileExplorerListActivity.types());
                break;
            case ROW_BY_SIZE:
                presentFragment(FileExplorerListActivity.allFilesBySize());
                break;
            case ROW_BY_DATE:
                presentFragment(FileExplorerListActivity.allFilesByDate());
                break;
            case ROW_ONLY_DOWNLOADED:
                FileExplorerConfig.setOnlyDownloaded(!FileExplorerConfig.onlyDownloaded);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(FileExplorerConfig.onlyDownloaded);
                }
                update();
                break;
            case ROW_SCAN_DOWNLOADS:
                FileExplorerConfig.setIncludeOrphanFiles(!FileExplorerConfig.includeOrphanFiles);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(FileExplorerConfig.includeOrphanFiles);
                }
                FileIndexer.getInstance(currentAccount).invalidate();
                FileIndexer.getInstance(currentAccount).load(true);
                update();
                break;
            case ROW_REFRESH:
                FileIndexer.getInstance(currentAccount).invalidate();
                FileIndexer.getInstance(currentAccount).load(true);
                update();
                break;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
