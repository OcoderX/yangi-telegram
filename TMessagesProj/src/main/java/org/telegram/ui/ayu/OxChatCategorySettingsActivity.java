package org.telegram.ui.ayu;

import static org.telegram.messenger.LocaleController.getString;

import android.view.View;
import android.view.Gravity;
import android.widget.LinearLayout;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

public class OxChatCategorySettingsActivity extends UniversalFragment {
    private static final int ENABLE = 1;
    private static final int ENABLE_FORWARD = 2;
    private OxChatCategoryConfig config;

    public OxChatCategorySettingsActivity(int account) {
        setCurrentAccount(account);
        config = OxChatCategoryConfig.forAccount(account);
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.OxChatTabSettings);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asCheck(ENABLE, getString(R.string.OxChatCategoriesMain)).setChecked(config.isEnabled(false)));
        items.add(UItem.asCheck(ENABLE_FORWARD, getString(R.string.OxChatCategoriesForward)).setChecked(config.isEnabled(true)));
        items.add(UItem.asShadow(getString(R.string.OxChatCategoriesInfo)));
        items.add(UItem.asHeader(getString(R.string.OxChatVisibleCategories)));
        for (int category = OxChatCategories.PERSONAL; category <= OxChatCategories.BOTS; category++) {
            items.add(UItem.asButton(category, iconForCategory(category), getString(titleForCategory(category)), visibilitySummary(config, category)));
        }
        items.add(UItem.asShadow(getString(R.string.OxChatAllAlwaysAvailable)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        // Refresh identity at the interaction boundary in case a local account slot was reused.
        config = OxChatCategoryConfig.forAccount(currentAccount);
        if (item.id == ENABLE) {
            config.setEnabled(!config.isEnabled());
        } else if (item.id == ENABLE_FORWARD) {
            config.setEnabled(true, !config.isEnabled(true));
        } else if (OxChatCategories.isCategory(item.id)) {
            showVisibility(this, currentAccount, item.id, () -> listView.adapter.update(true));
        }
        listView.adapter.update(true);
    }

    @Override
    public void onResume() {
        super.onResume();
        config = OxChatCategoryConfig.forAccount(currentAccount);
        if (listView != null) listView.adapter.update(false);
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    public static int titleForCategory(int category) {
        switch (category) {
            case OxChatCategories.PERSONAL: return R.string.OxChatsPersonal;
            case OxChatCategories.GROUPS: return R.string.OxChatsGroups;
            case OxChatCategories.CHANNELS: return R.string.OxChatsChannels;
            default: return R.string.OxChatsBots;
        }
    }

    public static int iconForCategory(int category) {
        switch (category) {
            case OxChatCategories.PERSONAL: return R.drawable.msg_contacts;
            case OxChatCategories.GROUPS: return R.drawable.msg_groups;
            case OxChatCategories.CHANNELS: return R.drawable.msg_channel;
            default: return R.drawable.msg_bot;
        }
    }

    /** Memory-only summary for settings rows. */
    public static String visibilitySummary(OxChatCategoryConfig config, int category) {
        boolean main = config.isEnabled(false) && config.isVisible(category, false);
        boolean forward = config.isEnabled(true) && config.isVisible(category, true);
        return getString(main && forward ? R.string.OxChatVisibleBoth : main ? R.string.OxChatVisibleMain
                : forward ? R.string.OxChatVisibleForward : R.string.OxChatVisibleNowhere);
    }

    public static void showVisibility(BaseFragment fragment, int account, int category, Runnable onChanged) {
        if (fragment.getContext() == null || !OxChatCategories.isCategory(category)) return;
        final OxChatCategoryConfig config = OxChatCategoryConfig.forAccount(account);
        final LinearLayout container = new LinearLayout(fragment.getContext());
        container.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < 2; i++) {
            final boolean forward = i == 1;
            final CheckBoxCell cell = new CheckBoxCell(fragment.getContext(), CheckBoxCell.TYPE_CHECK_BOX_ROUND, 21, fragment.getResourceProvider());
            cell.setText(getString(forward ? R.string.OxChatShowInForward : R.string.OxChatShowInMain), "", config.isEnabled(forward) && config.isVisible(category, forward), false);
            cell.setBackground(Theme.getSelectorDrawable(false));
            cell.setOnClickListener(v -> {
                if (OxChatCategoryConfig.forAccount(account) != config) return;
                boolean show = !(config.isEnabled(forward) && config.isVisible(category, forward));
                config.setVisible(category, forward, show);
                if (show) config.setEnabled(forward, true);
                cell.setChecked(config.isEnabled(forward) && config.isVisible(category, forward), true);
                if (onChanged != null) onChanged.run();
            });
            container.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
        }
        container.addView(new View(fragment.getContext()), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 12));
        BottomSheet.Builder builder = new BottomSheet.Builder(fragment.getContext(), false, fragment.getResourceProvider());
        builder.setTitle(getString(titleForCategory(category)), true);
        builder.setCustomView(container, Gravity.LEFT | Gravity.TOP);
        fragment.showDialog(builder.create());
    }
}
