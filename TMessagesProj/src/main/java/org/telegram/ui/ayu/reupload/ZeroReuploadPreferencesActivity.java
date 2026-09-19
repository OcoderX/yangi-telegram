package org.telegram.ui.ayu.reupload;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.ayu.reupload.ReuploadIndex;
import org.telegram.messenger.ayu.reupload.ZeroReupload;
import org.telegram.messenger.ayu.reupload.ZeroReuploadConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * AyuGram Zero-Reupload settings: master toggle, the optional extras and the index statistics.
 */
public class ZeroReuploadPreferencesActivity extends UniversalFragment {

    private static final int ENABLE = 1;
    private static final int INDEX_RECEIVED = 10;
    private static final int SHOW_BULLETIN = 11;
    private static final int STAT_INDEXED = 20;
    private static final int STAT_SAVED = 21;
    private static final int CLEAR_INDEX = 30;

    private long indexedFiles;
    private long indexedBytes;
    private long databaseSize;

    public ZeroReuploadPreferencesActivity() {
        super();
        ZeroReuploadConfig.load();
    }

    @Override
    public boolean onFragmentCreate() {
        reloadStats();
        ZeroReupload.ensureObserver(currentAccount);
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        super.createView(context);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        return fragmentView;
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.AyuZeroReupload);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asCheck(ENABLE, getString(R.string.AyuZeroReuploadEnable)).setChecked(ZeroReuploadConfig.enabled));
        items.add(UItem.asShadow(getString(R.string.AyuZeroReuploadInfo)));

        items.add(UItem.asHeader(getString(R.string.AyuZeroReuploadOptions)));
        items.add(UItem.asCheck(INDEX_RECEIVED, getString(R.string.AyuZeroReuploadIndexReceived)).setChecked(ZeroReuploadConfig.indexReceived));
        items.add(UItem.asCheck(SHOW_BULLETIN, getString(R.string.AyuZeroReuploadShowBulletin)).setChecked(ZeroReuploadConfig.showBulletin));
        items.add(UItem.asShadow(getString(R.string.AyuZeroReuploadOptionsInfo)));

        items.add(UItem.asHeader(getString(R.string.AyuZeroReuploadStats)));
        items.add(UItem.asButton(STAT_INDEXED, getString(R.string.AyuZeroReuploadIndexedFiles),
                LocaleController.formatString(R.string.AyuZeroReuploadIndexedValue, indexedFiles, AndroidUtilities.formatFileSize(indexedBytes))));
        items.add(UItem.asButton(STAT_SAVED, getString(R.string.AyuZeroReuploadSaved),
                LocaleController.formatString(R.string.AyuZeroReuploadSavedValue, ZeroReuploadConfig.reusedCount, AndroidUtilities.formatFileSize(ZeroReuploadConfig.bytesSaved))));
        items.add(UItem.asButton(CLEAR_INDEX, getString(R.string.AyuZeroReuploadClearIndex), AndroidUtilities.formatFileSize(databaseSize)).red());
        items.add(UItem.asShadow(getString(R.string.AyuZeroReuploadStatsInfo)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ENABLE:
                ZeroReuploadConfig.setEnabled(!ZeroReuploadConfig.enabled);
                if (ZeroReuploadConfig.enabled) {
                    ZeroReupload.ensureObserver(currentAccount);
                }
                toggleSwitch(view, ZeroReuploadConfig.enabled);
                break;
            case INDEX_RECEIVED:
                ZeroReuploadConfig.setIndexReceived(!ZeroReuploadConfig.indexReceived);
                if (ZeroReuploadConfig.indexReceived) {
                    ZeroReupload.ensureObserver(currentAccount);
                }
                toggleSwitch(view, ZeroReuploadConfig.indexReceived);
                break;
            case SHOW_BULLETIN:
                ZeroReuploadConfig.setShowBulletin(!ZeroReuploadConfig.showBulletin);
                toggleSwitch(view, ZeroReuploadConfig.showBulletin);
                break;
            case CLEAR_INDEX:
                showClearIndexDialog();
                break;
            case STAT_INDEXED:
            case STAT_SAVED:
                reloadStats();
                break;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void toggleSwitch(View view, boolean value) {
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(value);
        }
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.ayuConfigChanged);
    }

    private void reloadStats() {
        final int account = currentAccount;
        ReuploadIndex.getQueue().postRunnable(() -> {
            final long files = ReuploadIndex.getInstance(account).getEntryCount();
            final long bytes = ReuploadIndex.getInstance(account).getIndexedBytes();
            final long dbSize = ReuploadIndex.getInstance(account).getDatabaseSize();
            AndroidUtilities.runOnUIThread(() -> {
                indexedFiles = files;
                indexedBytes = bytes;
                databaseSize = dbSize;
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
            });
        });
    }

    private void showClearIndexDialog() {
        if (getContext() == null) {
            return;
        }
        new AlertDialog.Builder(getContext(), resourceProvider)
                .setTitle(getString(R.string.AyuZeroReuploadClearIndex))
                .setMessage(getString(R.string.AyuZeroReuploadClearIndexMessage))
                .setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
                    ZeroReupload.clear(currentAccount);
                    ZeroReuploadConfig.resetStats();
                    AndroidUtilities.runOnUIThread(this::reloadStats, 150);
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }
}
