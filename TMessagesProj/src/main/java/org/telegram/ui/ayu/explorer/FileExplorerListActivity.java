package org.telegram.ui.ayu.explorer;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.ayu.explorer.FileEntry;
import org.telegram.messenger.ayu.explorer.FileExplorerConfig;
import org.telegram.messenger.ayu.explorer.FileIndexer;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.GraySectionCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.EmptyTextProgressView;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LineProgressView;
import org.telegram.ui.Components.NumberTextView;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;

/**
 * AyuGram File Explorer: the list screens.
 * <p>
 * The same fragment renders every "way in": the chat folders, the type folders, the document
 * extensions and the flat file lists. Which one is shown is decided by {@link #mode} and the
 * filters given to the static factory methods.
 */
public class FileExplorerListActivity extends BaseFragment implements FileIndexer.Listener {

    public static final int MODE_CHATS = 0;
    public static final int MODE_TYPES = 1;
    public static final int MODE_EXTENSIONS = 2;
    public static final int MODE_FILES = 3;

    private static final int VIEW_TYPE_FOLDER = 0;
    private static final int VIEW_TYPE_FILE = 1;
    private static final int VIEW_TYPE_SECTION = 2;
    private static final int VIEW_TYPE_INFO = 3;

    private static final int MENU_SEARCH = 1;
    private static final int MENU_OTHER = 2;
    private static final int MENU_SORT_DATE = 10;
    private static final int MENU_SORT_SIZE = 11;
    private static final int MENU_SORT_NAME = 12;
    private static final int MENU_ONLY_DOWNLOADED = 13;
    private static final int MENU_SELECT = 14;
    private static final int ACTION_SHARE = 20;
    private static final int ACTION_DELETE = 21;

    /** no dialog filter */
    public static final long ANY_DIALOG = Long.MIN_VALUE;

    private final int mode;
    private final long dialogFilter;
    private final int categoryFilter;
    private final String extensionFilter;
    private final boolean groupByDate;
    private final int forcedSort;
    private final CharSequence screenTitle;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private EmptyTextProgressView emptyView;
    private LineProgressView progressView;
    private ActionBarMenuItem searchItem;
    private ActionBarMenuItem otherItem;
    private NumberTextView selectedCountView;

    private String searchQuery;
    private final ArrayList<Item> items = new ArrayList<>();
    private final HashSet<FileEntry> selected = new HashSet<>();

    // ------------------------------------------------------------------ factories

    public static FileExplorerListActivity chats() {
        return new FileExplorerListActivity(MODE_CHATS, ANY_DIALOG, -1, null, false, -1, getString(R.string.AyuFeByChat));
    }

    public static FileExplorerListActivity types() {
        return new FileExplorerListActivity(MODE_TYPES, ANY_DIALOG, -1, null, false, -1, getString(R.string.AyuFeByType));
    }

    public static FileExplorerListActivity documentExtensions() {
        return new FileExplorerListActivity(MODE_EXTENSIONS, ANY_DIALOG, FileEntry.CATEGORY_DOCUMENTS, null, false, -1, getString(R.string.AyuFeCategoryDocuments));
    }

    public static FileExplorerListActivity filesOfDialog(int currentAccount, long dialogId) {
        return new FileExplorerListActivity(MODE_FILES, dialogId, -1, null, false, -1, FileExplorerUtils.getDialogTitle(currentAccount, dialogId));
    }

    public static FileExplorerListActivity filesOfCategory(int category) {
        return new FileExplorerListActivity(MODE_FILES, ANY_DIALOG, category, null, false, -1, FileExplorerUtils.getCategoryName(category));
    }

    public static FileExplorerListActivity filesOfExtension(String extension) {
        final String title = TextUtils.isEmpty(extension)
                ? getString(R.string.AyuFeNoExtension)
                : extension.toUpperCase(Locale.US);
        return new FileExplorerListActivity(MODE_FILES, ANY_DIALOG, FileEntry.CATEGORY_DOCUMENTS, extension, false, -1, title);
    }

    public static FileExplorerListActivity allFilesBySize() {
        return new FileExplorerListActivity(MODE_FILES, ANY_DIALOG, -1, null, false, FileExplorerConfig.SORT_SIZE_DESC, getString(R.string.AyuFeBySize));
    }

    public static FileExplorerListActivity allFilesByDate() {
        return new FileExplorerListActivity(MODE_FILES, ANY_DIALOG, -1, null, true, FileExplorerConfig.SORT_DATE_DESC, getString(R.string.AyuFeByDate));
    }

    private FileExplorerListActivity(int mode, long dialogFilter, int categoryFilter, String extensionFilter, boolean groupByDate, int forcedSort, CharSequence title) {
        super();
        this.mode = mode;
        this.dialogFilter = dialogFilter;
        this.categoryFilter = categoryFilter;
        this.extensionFilter = extensionFilter;
        this.groupByDate = groupByDate;
        this.forcedSort = forcedSort;
        this.screenTitle = title;
        FileExplorerConfig.load();
    }

    // ------------------------------------------------------------------ lifecycle

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
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(screenTitle);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (actionBar.isActionModeShowed()) {
                        clearSelection();
                    } else {
                        finishFragment();
                    }
                    return;
                }
                onMenuItemClick(id);
            }
        });

        final ActionBarMenu menu = actionBar.createMenu();
        searchItem = menu.addItem(MENU_SEARCH, R.drawable.outline_header_search).setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override
            public void onSearchCollapse() {
                searchQuery = null;
                updateRows();
            }

            @Override
            public void onTextChanged(EditText editText) {
                searchQuery = editText.getText() == null ? null : editText.getText().toString();
                updateRows();
            }
        });
        searchItem.setSearchFieldHint(getString(R.string.Search));

        otherItem = menu.addItem(MENU_OTHER, R.drawable.ic_ab_other);
        if (mode == MODE_FILES) {
            otherItem.addSubItem(MENU_SELECT, R.drawable.msg_select, getString(R.string.AyuFeSelect));
        }
        otherItem.addSubItem(MENU_ONLY_DOWNLOADED, R.drawable.msg_download, getString(R.string.AyuFeOnlyDownloaded));
        if (mode == MODE_FILES) {
            otherItem.addSubItem(MENU_SORT_DATE, R.drawable.msg_calendar2, getString(R.string.AyuFeSortByDate));
            otherItem.addSubItem(MENU_SORT_SIZE, R.drawable.msg_filled_storageusage, getString(R.string.AyuFeSortBySize));
            otherItem.addSubItem(MENU_SORT_NAME, R.drawable.msg_customize, getString(R.string.AyuFeSortByName));
        }

        final ActionBarMenu actionMode = actionBar.createActionMode();
        selectedCountView = new NumberTextView(actionMode.getContext());
        selectedCountView.setTextSize(18);
        selectedCountView.setTypeface(AndroidUtilities.bold());
        selectedCountView.setTextColor(Theme.getColor(Theme.key_actionBarActionModeDefaultIcon));
        actionMode.addView(selectedCountView, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1.0f, 72, 0, 0, 0));
        selectedCountView.setOnTouchListener((v, event) -> true);
        actionMode.addItemWithWidth(ACTION_SHARE, R.drawable.msg_share, dp(54), getString(R.string.ShareFile));
        actionMode.addItemWithWidth(ACTION_DELETE, R.drawable.msg_delete, dp(54), getString(R.string.Delete));

        final FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        emptyView = new EmptyTextProgressView(context);
        emptyView.setText(getString(R.string.AyuFeEmpty));
        emptyView.showProgress();
        frameLayout.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setEmptyView(emptyView);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        adapter = new ListAdapter();
        listView.setAdapter(adapter);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        progressView = new LineProgressView(context);
        progressView.setProgressColor(Theme.getColor(Theme.key_sharedMedia_startStopLoadIcon));
        progressView.setVisibility(View.GONE);
        frameLayout.addView(progressView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 2, Gravity.TOP | Gravity.LEFT));

        listView.setOnItemClickListener((view, position) -> onItemClick(view, position));
        listView.setOnItemLongClickListener((view, position) -> onItemLongClick(view, position));

        updateRows();
        FileIndexer.getInstance(currentAccount).load(false);
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        updateRows();
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (actionBar != null && actionBar.isActionModeShowed()) {
            if (invoked) {
                clearSelection();
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }

    // ------------------------------------------------------------------ indexer callbacks

    @Override
    public void onFileIndexProgress(float progress) {
        if (progressView == null) {
            return;
        }
        progressView.setVisibility(View.VISIBLE);
        progressView.setProgress(progress, true);
    }

    @Override
    public void onFileIndexUpdated(FileIndexer.Index index) {
        if (progressView != null) {
            progressView.setProgress(1f, true);
            progressView.setVisibility(View.GONE);
        }
        if (emptyView != null) {
            emptyView.showTextView();
        }
        updateRows();
    }

    // ------------------------------------------------------------------ rows

    private static class Item {
        int viewType;
        CharSequence text;
        CharSequence value;
        FileIndexer.Folder folder;
        FileEntry entry;
        int category = -1;
        String extension;
        boolean divider;
    }

    private int sortMode() {
        return forcedSort >= 0 ? forcedSort : FileExplorerConfig.sortMode;
    }

    private ArrayList<FileEntry> collectEntries() {
        final FileIndexer.Index index = FileIndexer.getInstance(currentAccount).getIndex();
        if (index == null) {
            return new ArrayList<>();
        }
        ArrayList<FileEntry> source = index.entries;
        if (dialogFilter != ANY_DIALOG) {
            final FileIndexer.Folder folder = index.foldersByDialog.get(dialogFilter);
            source = folder == null ? new ArrayList<>() : folder.entries;
        }
        return FileIndexer.filter(source, searchQuery, categoryFilter, extensionFilter, sortMode(), FileExplorerConfig.onlyDownloaded);
    }

    private void updateRows() {
        items.clear();
        final FileIndexer.Index index = FileIndexer.getInstance(currentAccount).getIndex();
        if (index == null) {
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            return;
        }
        final String query = TextUtils.isEmpty(searchQuery) ? null : searchQuery.toLowerCase(Locale.US).trim();
        switch (mode) {
            case MODE_CHATS: {
                final ArrayList<Item> folderItems = new ArrayList<>();
                for (int i = 0; i < index.folders.size(); i++) {
                    final FileIndexer.Folder folder = index.folders.get(i);
                    final int count = FileExplorerConfig.onlyDownloaded ? folder.downloadedCount : folder.entries.size();
                    if (count == 0) {
                        continue;
                    }
                    final String title = FileExplorerUtils.getDialogTitle(currentAccount, folder.dialogId);
                    if (query != null && !query.isEmpty() && (title == null || !title.toLowerCase(Locale.US).contains(query))) {
                        continue;
                    }
                    final Item item = new Item();
                    item.viewType = VIEW_TYPE_FOLDER;
                    item.folder = folder;
                    item.value = FileExplorerUtils.formatFilesCount(count, FileExplorerConfig.onlyDownloaded ? folder.downloadedSize : folder.totalSize);
                    folderItems.add(item);
                }
                for (int i = 0; i < folderItems.size(); i++) {
                    folderItems.get(i).divider = i != folderItems.size() - 1;
                }
                items.addAll(folderItems);
                break;
            }
            case MODE_TYPES: {
                final ArrayList<Item> categoryItems = new ArrayList<>();
                for (int category = 0; category < FileEntry.CATEGORY_COUNT; category++) {
                    final int count = FileExplorerConfig.onlyDownloaded ? index.categoryDownloadedCount[category] : index.categoryCount[category];
                    if (count == 0) {
                        continue;
                    }
                    final String title = FileExplorerUtils.getCategoryName(category);
                    if (query != null && !query.isEmpty() && !title.toLowerCase(Locale.US).contains(query)) {
                        continue;
                    }
                    final Item item = new Item();
                    item.viewType = VIEW_TYPE_FOLDER;
                    item.category = category;
                    item.text = title;
                    item.value = FileExplorerUtils.formatFilesCount(count,
                            FileExplorerConfig.onlyDownloaded ? index.categoryDownloadedSize[category] : index.categorySize[category]);
                    categoryItems.add(item);
                }
                for (int i = 0; i < categoryItems.size(); i++) {
                    categoryItems.get(i).divider = i != categoryItems.size() - 1;
                }
                items.addAll(categoryItems);
                break;
            }
            case MODE_EXTENSIONS: {
                final ArrayList<String> extensions = FileIndexer.documentExtensions(index, FileExplorerConfig.onlyDownloaded);
                final ArrayList<Item> extensionItems = new ArrayList<>();
                for (int i = 0; i < extensions.size(); i++) {
                    final String extension = extensions.get(i);
                    final String title = TextUtils.isEmpty(extension) ? getString(R.string.AyuFeNoExtension) : extension.toUpperCase(Locale.US);
                    if (query != null && !query.isEmpty() && !title.toLowerCase(Locale.US).contains(query)) {
                        continue;
                    }
                    final long[] stats = FileIndexer.extensionStats(index, extension, FileExplorerConfig.onlyDownloaded);
                    final Item item = new Item();
                    item.viewType = VIEW_TYPE_FOLDER;
                    item.category = FileEntry.CATEGORY_DOCUMENTS;
                    item.extension = extension;
                    item.text = title;
                    item.value = FileExplorerUtils.formatFilesCount((int) stats[0], stats[1]);
                    extensionItems.add(item);
                }
                for (int i = 0; i < extensionItems.size(); i++) {
                    extensionItems.get(i).divider = i != extensionItems.size() - 1;
                }
                items.addAll(extensionItems);
                break;
            }
            default: {
                final ArrayList<FileEntry> entries = collectEntries();
                String currentSection = null;
                final ArrayList<Item> pending = new ArrayList<>();
                for (int i = 0; i < entries.size(); i++) {
                    final FileEntry entry = entries.get(i);
                    if (groupByDate) {
                        final String section = entry.date > 0
                                ? LocaleController.formatYearMont(entry.date, true)
                                : getString(R.string.AyuFeUnknownDate);
                        if (!TextUtils.equals(section, currentSection)) {
                            markLast(pending);
                            currentSection = section;
                            final Item header = new Item();
                            header.viewType = VIEW_TYPE_SECTION;
                            header.text = section;
                            items.add(header);
                        }
                    }
                    final Item item = new Item();
                    item.viewType = VIEW_TYPE_FILE;
                    item.entry = entry;
                    item.divider = true;
                    items.add(item);
                    pending.add(item);
                }
                markLast(pending);
                if (!items.isEmpty()) {
                    final Item info = new Item();
                    info.viewType = VIEW_TYPE_INFO;
                    long size = 0;
                    for (int i = 0; i < entries.size(); i++) {
                        size += entries.get(i).sizeForSorting();
                    }
                    info.text = FileExplorerUtils.formatFilesCount(entries.size(), size);
                    items.add(info);
                }
                break;
            }
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        if (emptyView != null && !FileIndexer.getInstance(currentAccount).isLoading()) {
            emptyView.showTextView();
        }
    }

    private void markLast(ArrayList<Item> pending) {
        if (!pending.isEmpty()) {
            pending.get(pending.size() - 1).divider = false;
            pending.clear();
        }
    }

    // ------------------------------------------------------------------ interaction

    private void onMenuItemClick(int id) {
        if (id == ACTION_SHARE) {
            FileExplorerUtils.share(this, new ArrayList<>(selected));
            clearSelection();
        } else if (id == ACTION_DELETE) {
            final ArrayList<FileEntry> list = new ArrayList<>(selected);
            FileExplorerUtils.confirmDelete(this, currentAccount, list, () -> {
                clearSelection();
                updateRows();
            });
        } else if (id == MENU_ONLY_DOWNLOADED) {
            FileExplorerConfig.setOnlyDownloaded(!FileExplorerConfig.onlyDownloaded);
            updateRows();
        } else if (id == MENU_SELECT) {
            if (!actionBar.isActionModeShowed()) {
                actionBar.showActionMode();
                selectedCountView.setNumber(0, false);
            }
        } else if (id == MENU_SORT_DATE) {
            setSortMode(FileExplorerConfig.sortMode == FileExplorerConfig.SORT_DATE_DESC
                    ? FileExplorerConfig.SORT_DATE_ASC : FileExplorerConfig.SORT_DATE_DESC);
        } else if (id == MENU_SORT_SIZE) {
            setSortMode(FileExplorerConfig.sortMode == FileExplorerConfig.SORT_SIZE_DESC
                    ? FileExplorerConfig.SORT_SIZE_ASC : FileExplorerConfig.SORT_SIZE_DESC);
        } else if (id == MENU_SORT_NAME) {
            setSortMode(FileExplorerConfig.sortMode == FileExplorerConfig.SORT_NAME_ASC
                    ? FileExplorerConfig.SORT_NAME_DESC : FileExplorerConfig.SORT_NAME_ASC);
        }
    }

    private void setSortMode(int value) {
        FileExplorerConfig.setSortMode(value);
        updateRows();
    }

    private void onItemClick(View view, int position) {
        if (position < 0 || position >= items.size()) {
            return;
        }
        final Item item = items.get(position);
        if (item.viewType == VIEW_TYPE_FOLDER) {
            if (item.folder != null) {
                presentFragment(filesOfDialog(currentAccount, item.folder.dialogId));
            } else if (item.extension != null) {
                presentFragment(filesOfExtension(item.extension));
            } else if (item.category == FileEntry.CATEGORY_DOCUMENTS && mode == MODE_TYPES) {
                presentFragment(documentExtensions());
            } else if (item.category >= 0) {
                presentFragment(filesOfCategory(item.category));
            }
            return;
        }
        if (item.viewType != VIEW_TYPE_FILE || item.entry == null) {
            return;
        }
        if (actionBar.isActionModeShowed()) {
            toggleSelection(item.entry, view);
            return;
        }
        FileExplorerUtils.open(this, currentAccount, item.entry);
    }

    private boolean onItemLongClick(View view, int position) {
        if (position < 0 || position >= items.size()) {
            return false;
        }
        final Item item = items.get(position);
        if (item.viewType != VIEW_TYPE_FILE || item.entry == null) {
            return false;
        }
        if (actionBar.isActionModeShowed()) {
            toggleSelection(item.entry, view);
            return true;
        }
        final FileEntry entry = item.entry;
        final ItemOptions options = ItemOptions.makeOptions(this, view);
        if (entry.downloaded) {
            options.add(R.drawable.msg_openin, getString(R.string.AyuFeOpen), () -> FileExplorerUtils.open(this, currentAccount, entry));
        } else if (entry.document != null) {
            options.add(R.drawable.msg_download, getString(R.string.AyuFeDownload), () -> FileExplorerUtils.download(this, currentAccount, entry));
        }
        if (entry.hasMessage()) {
            options.add(R.drawable.msg_message, getString(R.string.AyuFeGoToMessage), () -> FileExplorerUtils.goToMessage(this, currentAccount, entry));
        }
        if (entry.downloaded) {
            options.add(R.drawable.msg_share, getString(R.string.ShareFile), () -> {
                final ArrayList<FileEntry> list = new ArrayList<>();
                list.add(entry);
                FileExplorerUtils.share(this, list);
            });
        }
        options.add(R.drawable.msg_select, getString(R.string.AyuFeSelect), () -> {
            if (!actionBar.isActionModeShowed()) {
                actionBar.showActionMode();
            }
            toggleSelection(entry, null);
        });
        if (entry.downloaded) {
            options.add(R.drawable.msg_delete, getString(R.string.AyuFeDeleteLocal), true, () -> {
                final ArrayList<FileEntry> list = new ArrayList<>();
                list.add(entry);
                FileExplorerUtils.confirmDelete(this, currentAccount, list, this::updateRows);
            });
        }
        options.setGravity(Gravity.RIGHT);
        options.show();
        return true;
    }

    private void toggleSelection(FileEntry entry, View view) {
        if (!selected.remove(entry)) {
            selected.add(entry);
        }
        if (view instanceof FileExplorerFileCell) {
            ((FileExplorerFileCell) view).setChecked(selected.contains(entry), true);
        } else if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        if (selected.isEmpty()) {
            clearSelection();
        } else {
            if (!actionBar.isActionModeShowed()) {
                actionBar.showActionMode();
            }
            selectedCountView.setNumber(selected.size(), true);
        }
    }

    private void clearSelection() {
        selected.clear();
        if (actionBar.isActionModeShowed()) {
            actionBar.hideActionMode();
        }
        if (listView != null) {
            for (int i = 0; i < listView.getChildCount(); i++) {
                final View child = listView.getChildAt(i);
                if (child instanceof FileExplorerFileCell) {
                    ((FileExplorerFileCell) child).clearChecked();
                }
            }
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    // ------------------------------------------------------------------ adapter

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            final int viewType = holder.getItemViewType();
            return viewType == VIEW_TYPE_FOLDER || viewType == VIEW_TYPE_FILE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final Context context = parent.getContext();
            final View view;
            switch (viewType) {
                case VIEW_TYPE_FOLDER:
                    view = new FileExplorerFolderCell(context, getResourceProvider());
                    break;
                case VIEW_TYPE_SECTION:
                    view = new GraySectionCell(context, getResourceProvider());
                    break;
                case VIEW_TYPE_INFO:
                    view = new TextInfoPrivacyCell(context, getResourceProvider());
                    break;
                case VIEW_TYPE_FILE:
                default:
                    view = new FileExplorerFileCell(context, getResourceProvider());
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final Item item = items.get(position);
            switch (holder.getItemViewType()) {
                case VIEW_TYPE_FOLDER: {
                    final FileExplorerFolderCell cell = (FileExplorerFolderCell) holder.itemView;
                    if (item.folder != null) {
                        cell.setDialog(currentAccount, item.folder.dialogId, item.value, item.divider);
                    } else {
                        final int iconCategory = item.extension != null ? FileEntry.CATEGORY_DOCUMENTS : item.category;
                        cell.setCategory(item.text, item.value,
                                FileExplorerUtils.getCategoryIcon(iconCategory),
                                FileExplorerUtils.getCategoryColorKey(iconCategory), item.divider);
                    }
                    break;
                }
                case VIEW_TYPE_FILE: {
                    final FileExplorerFileCell cell = (FileExplorerFileCell) holder.itemView;
                    cell.setEntry(currentAccount, item.entry, dialogFilter == ANY_DIALOG, item.divider);
                    if (actionBar.isActionModeShowed()) {
                        cell.setChecked(selected.contains(item.entry), false);
                    } else {
                        cell.clearChecked();
                    }
                    break;
                }
                case VIEW_TYPE_SECTION: {
                    ((GraySectionCell) holder.itemView).setText(item.text);
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
            return items.get(position).viewType;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
