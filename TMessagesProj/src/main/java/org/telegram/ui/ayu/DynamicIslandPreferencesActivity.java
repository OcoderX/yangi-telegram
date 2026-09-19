package org.telegram.ui.ayu;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;

import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.ayu.AyuConfig;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * AyuGram Dynamic Island sub-screen: master toggle, the live sources it shows and the idle pill.
 */
public class DynamicIslandPreferencesActivity extends UniversalFragment {

    private static final int ENABLE = 1;
    private static final int CALLS = 10;
    private static final int MUSIC = 11;
    private static final int RECORDING = 12;
    private static final int DOWNLOADS = 13;
    private static final int GHOST = 14;
    private static final int IDLE = 20;

    public DynamicIslandPreferencesActivity() {
        super();
        AyuConfig.load();
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
        return getString(R.string.AyuDynamicIsland);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asCheck(ENABLE, getString(R.string.AyuIslandEnable)).setChecked(AyuConfig.dynamicIsland));
        items.add(UItem.asShadow(getString(R.string.AyuDynamicIslandInfo)));

        items.add(UItem.asHeader(getString(R.string.AyuIslandSources)));
        items.add(UItem.asCheck(CALLS, getString(R.string.AyuIslandCalls)).setChecked(AyuConfig.islandCalls));
        items.add(UItem.asCheck(MUSIC, getString(R.string.AyuIslandMusic)).setChecked(AyuConfig.islandMusic));
        items.add(UItem.asCheck(RECORDING, getString(R.string.AyuIslandRecording)).setChecked(AyuConfig.islandRecording));
        items.add(UItem.asCheck(DOWNLOADS, getString(R.string.AyuIslandDownloads)).setChecked(AyuConfig.islandDownloads));
        items.add(UItem.asCheck(GHOST, getString(R.string.AyuIslandGhost)).setChecked(AyuConfig.islandGhost));
        items.add(UItem.asShadow(getString(R.string.AyuIslandSourcesInfo)));

        items.add(UItem.asCheck(IDLE, getString(R.string.AyuIslandIdle)).setChecked(AyuConfig.islandIdle));
        items.add(UItem.asShadow(getString(R.string.AyuIslandIdleInfo)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ENABLE:
                AyuConfig.setDynamicIsland(!AyuConfig.dynamicIsland);
                toggleSwitch(view, AyuConfig.dynamicIsland);
                break;
            case CALLS:
                AyuConfig.setIslandCalls(!AyuConfig.islandCalls);
                toggleSwitch(view, AyuConfig.islandCalls);
                break;
            case MUSIC:
                AyuConfig.setIslandMusic(!AyuConfig.islandMusic);
                toggleSwitch(view, AyuConfig.islandMusic);
                break;
            case RECORDING:
                AyuConfig.setIslandRecording(!AyuConfig.islandRecording);
                toggleSwitch(view, AyuConfig.islandRecording);
                break;
            case DOWNLOADS:
                AyuConfig.setIslandDownloads(!AyuConfig.islandDownloads);
                toggleSwitch(view, AyuConfig.islandDownloads);
                break;
            case GHOST:
                AyuConfig.setIslandGhost(!AyuConfig.islandGhost);
                toggleSwitch(view, AyuConfig.islandGhost);
                break;
            case IDLE:
                AyuConfig.setIslandIdle(!AyuConfig.islandIdle);
                toggleSwitch(view, AyuConfig.islandIdle);
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
        DynamicIslandView.updateIfExists();
    }
}
