package org.telegram.ui.ayu;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.ayu.AyuConfig;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * AyuGram "Message saving" sub-screen.
 */
public class MessageSavingPreferencesActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int SAVE_MEDIA = 1;
    private static final int SAVE_FORMATTING = 2;
    private static final int SAVE_REACTIONS = 3;
    private static final int SAVE_FOR_BOTS = 4;
    private static final int SAVE_SELF_DESTRUCTING = 5;

    public MessageSavingPreferencesActivity() {
        super();
        AyuConfig.load();
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.ayuConfigChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.ayuConfigChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.ayuConfigChanged) {
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
        }
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
        return getString(R.string.AyuMessageSaving);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(getString(R.string.AyuMessageSaving)));
        items.add(UItem.asCheck(SAVE_MEDIA, getString(R.string.AyuSaveMedia)).setChecked(AyuConfig.saveMedia));
        items.add(UItem.asCheck(SAVE_FORMATTING, getString(R.string.AyuSaveFormatting)).setChecked(AyuConfig.saveFormatting));
        items.add(UItem.asCheck(SAVE_REACTIONS, getString(R.string.AyuSaveReactions)).setChecked(AyuConfig.saveReactions));
        items.add(UItem.asCheck(SAVE_FOR_BOTS, getString(R.string.AyuSaveForBots)).setChecked(AyuConfig.saveForBots));
        items.add(UItem.asShadow(getString(R.string.AyuSaveMediaInfo)));

        items.add(UItem.asCheck(SAVE_SELF_DESTRUCTING, getString(R.string.AyuSaveSelfDestructingMedia)).setChecked(AyuConfig.saveSelfDestructingMedia));
        items.add(UItem.asShadow(getString(R.string.AyuSaveSelfDestructingMediaInfo)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case SAVE_MEDIA:
                if (AyuConfig.saveMedia) {
                    AyuConfig.setSaveMedia(false);
                    toggleSwitch(view, false);
                } else {
                    AyuConfig.setSaveMedia(true);
                    toggleSwitch(view, true);
                    showSaveMediaSheet();
                }
                break;
            case SAVE_FORMATTING:
                AyuConfig.setSaveFormatting(!AyuConfig.saveFormatting);
                toggleSwitch(view, AyuConfig.saveFormatting);
                break;
            case SAVE_REACTIONS:
                AyuConfig.setSaveReactions(!AyuConfig.saveReactions);
                toggleSwitch(view, AyuConfig.saveReactions);
                break;
            case SAVE_FOR_BOTS:
                AyuConfig.setSaveForBots(!AyuConfig.saveForBots);
                toggleSwitch(view, AyuConfig.saveForBots);
                break;
            case SAVE_SELF_DESTRUCTING:
                AyuConfig.setSaveSelfDestructingMedia(!AyuConfig.saveSelfDestructingMedia);
                toggleSwitch(view, AyuConfig.saveSelfDestructingMedia);
                break;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (item.id == SAVE_MEDIA) {
            showSaveMediaSheet();
            return true;
        }
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

    /** bottom sheet with the five "save media in ..." scopes */
    private void showSaveMediaSheet() {
        final Context context = getContext();
        if (context == null) {
            return;
        }
        final LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);

        final TextView info = new TextView(context);
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        info.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, resourceProvider));
        info.setText(getString(R.string.AyuSaveMediaInfo));
        info.setPadding(dp(22), dp(4), dp(22), dp(8));
        container.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        addMediaScope(context, container, getString(R.string.AyuSaveMediaPrivateChats), 0);
        addMediaScope(context, container, getString(R.string.AyuSaveMediaPublicChannels), 1);
        addMediaScope(context, container, getString(R.string.AyuSaveMediaPrivateChannels), 2);
        addMediaScope(context, container, getString(R.string.AyuSaveMediaPublicGroups), 3);
        addMediaScope(context, container, getString(R.string.AyuSaveMediaPrivateGroups), 4);

        container.addView(new View(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 12));

        final BottomSheet.Builder builder = new BottomSheet.Builder(context, false, resourceProvider);
        builder.setTitle(getString(R.string.AyuSaveMedia), true);
        builder.setCustomView(container, Gravity.LEFT | Gravity.TOP);
        showDialog(builder.create());
    }

    private void addMediaScope(Context context, LinearLayout container, CharSequence text, int index) {
        final CheckBoxCell cell = new CheckBoxCell(context, CheckBoxCell.TYPE_CHECK_BOX_ROUND, 21, resourceProvider);
        cell.setText(text, "", getMediaScope(index), false);
        cell.setBackground(Theme.getSelectorDrawable(false));
        cell.setOnClickListener(v -> {
            final boolean value = !getMediaScope(index);
            setMediaScope(index, value);
            cell.setChecked(value, true);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.ayuConfigChanged);
        });
        container.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
    }

    private static boolean getMediaScope(int index) {
        switch (index) {
            case 0: return AyuConfig.saveMediaInPrivateChats;
            case 1: return AyuConfig.saveMediaInPublicChannels;
            case 2: return AyuConfig.saveMediaInPrivateChannels;
            case 3: return AyuConfig.saveMediaInPublicGroups;
            default: return AyuConfig.saveMediaInPrivateGroups;
        }
    }

    private static void setMediaScope(int index, boolean value) {
        switch (index) {
            case 0: AyuConfig.setSaveMediaInPrivateChats(value); break;
            case 1: AyuConfig.setSaveMediaInPublicChannels(value); break;
            case 2: AyuConfig.setSaveMediaInPrivateChannels(value); break;
            case 3: AyuConfig.setSaveMediaInPublicGroups(value); break;
            default: AyuConfig.setSaveMediaInPrivateGroups(value); break;
        }
    }
}
