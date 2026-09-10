package org.telegram.ui.ayu;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.ayu.AyuConfig;
import org.telegram.messenger.ayu.AyuFilter;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CombinedDrawable;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.OutlineTextContainerView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * AyuGram: list of regex message filters and their global switches.
 */
public class RegexFiltersActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int BUTTON_ENABLE = 1;
    private static final int BUTTON_IN_CHATS = 2;
    private static final int BUTTON_CASE_INSENSITIVE = 3;
    private static final int BUTTON_BLOCKED = 4;
    private static final int BUTTON_ADD = 5;
    private static final int BUTTON_SHARE = 6;
    private static final int BUTTON_IMPORT = 7;
    private static final int FILTER_ITEM_ID_OFFSET = 1000;

    private Drawable addIcon;

    public RegexFiltersActivity() {
        super();
        AyuConfig.load();
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.ayuRegexFiltersUpdated);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.ayuRegexFiltersUpdated);
        super.onFragmentDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(false);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.ayuRegexFiltersUpdated) {
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
        }
    }

    @Override
    public View createView(Context context) {
        try {
            Resources resources = context.getResources();
            Drawable circle = resources.getDrawable(R.drawable.poll_add_circle).mutate();
            Drawable plus = resources.getDrawable(R.drawable.poll_add_plus).mutate();
            circle.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_switchTrackChecked), PorterDuff.Mode.MULTIPLY));
            plus.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_checkboxCheck), PorterDuff.Mode.MULTIPLY));
            addIcon = new CombinedDrawable(circle, plus) {
                @Override
                public void setColorFilter(ColorFilter colorFilter) {
                }
            };
        } catch (Exception e) {
            addIcon = null;
        }
        return super.createView(context);
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.AyuFiltersTitle);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(getString(R.string.AyuFiltersGeneralHeader)));
        items.add(UItem.asCheck(BUTTON_ENABLE, getString(R.string.AyuFiltersEnable)).setChecked(AyuConfig.regexFiltersEnabled));
        items.add(UItem.asCheck(BUTTON_IN_CHATS, getString(R.string.AyuFiltersHideInChats)).setChecked(AyuConfig.regexFiltersInChats).setEnabled(AyuConfig.regexFiltersEnabled));
        items.add(UItem.asCheck(BUTTON_CASE_INSENSITIVE, getString(R.string.AyuFiltersCaseInsensitive)).setChecked(AyuConfig.regexFiltersCaseInsensitive).setEnabled(AyuConfig.regexFiltersEnabled));
        items.add(UItem.asCheck(BUTTON_BLOCKED, getString(R.string.AyuFiltersHideBlocked)).setChecked(AyuConfig.filterBlockedUsers));
        items.add(UItem.asShadow(getString(R.string.AyuFiltersGeneralInfo)));

        items.add(UItem.asHeader(getString(R.string.AyuFiltersListHeader)));
        if (addIcon != null) {
            items.add(UItem.asButton(BUTTON_ADD, addIcon, getString(R.string.AyuFiltersAdd)).accent());
        } else {
            items.add(UItem.asButton(BUTTON_ADD, R.drawable.msg_add, getString(R.string.AyuFiltersAdd)).accent());
        }

        final ArrayList<AyuFilter.Filter> filters = AyuFilter.getFilters();
        for (int i = 0; i < filters.size(); i++) {
            final AyuFilter.Filter filter = filters.get(i);
            items.add(UItem.asButtonCheck(FILTER_ITEM_ID_OFFSET + i, filter.text, describe(filter)).setChecked(filter.enabled));
        }

        items.add(UItem.asShadow(getString(filters.isEmpty() ? R.string.AyuFiltersEmpty : R.string.AyuFiltersListInfo)));

        items.add(UItem.asButton(BUTTON_SHARE, R.drawable.msg_share, getString(R.string.AyuFiltersShare)));
        items.add(UItem.asButton(BUTTON_IMPORT, R.drawable.msg_link, getString(R.string.AyuFiltersImport)));
        items.add(UItem.asShadow(null));
    }

    private CharSequence describe(AyuFilter.Filter filter) {
        StringBuilder sb = new StringBuilder();
        if (filter.dialogId == 0) {
            sb.append(getString(R.string.AyuFiltersScopeGlobal));
        } else {
            String name = DialogObject.getName(currentAccount, filter.dialogId);
            sb.append(LocaleController.formatString(R.string.AyuFiltersScopeChat, TextUtils.isEmpty(name) ? String.valueOf(filter.dialogId) : name));
        }
        if (!filter.isValid()) {
            sb.append(", ").append(getString(R.string.AyuFiltersInvalidRegex));
        } else if (!filter.enabled) {
            sb.append(", ").append(getString(R.string.AyuFiltersDisabled));
        }
        return sb.toString();
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == BUTTON_ENABLE) {
            AyuConfig.setRegexFiltersEnabled(!AyuConfig.regexFiltersEnabled);
            AyuFilter.rebuildCache();
            listView.adapter.update(true);
        } else if (item.id == BUTTON_IN_CHATS) {
            AyuConfig.setRegexFiltersInChats(!AyuConfig.regexFiltersInChats);
            AyuFilter.rebuildCache();
            listView.adapter.update(true);
        } else if (item.id == BUTTON_CASE_INSENSITIVE) {
            AyuConfig.setRegexFiltersCaseInsensitive(!AyuConfig.regexFiltersCaseInsensitive);
            invalidatePatterns();
            AyuFilter.rebuildCache();
            listView.adapter.update(true);
        } else if (item.id == BUTTON_BLOCKED) {
            AyuConfig.setFilterBlockedUsers(!AyuConfig.filterBlockedUsers);
            AyuFilter.rebuildCache();
            listView.adapter.update(true);
        } else if (item.id == BUTTON_ADD) {
            presentFragment(new RegexFilterEditActivity());
        } else if (item.id == BUTTON_SHARE) {
            shareFilters();
        } else if (item.id == BUTTON_IMPORT) {
            showImportDialog();
        } else if (item.id >= FILTER_ITEM_ID_OFFSET) {
            AyuFilter.Filter filter = filterAt(item.id - FILTER_ITEM_ID_OFFSET);
            if (filter != null) {
                presentFragment(new RegexFilterEditActivity(filter.id));
            }
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (item.id < FILTER_ITEM_ID_OFFSET) {
            return false;
        }
        final int index = item.id - FILTER_ITEM_ID_OFFSET;
        final AyuFilter.Filter filter = filterAt(index);
        if (filter == null) {
            return false;
        }
        ItemOptions options = ItemOptions.makeOptions(this, view);
        options.add(filter.enabled ? R.drawable.msg_block : R.drawable.msg_message, getString(filter.enabled ? R.string.AyuFilterDisable : R.string.AyuFilterEnable), () -> {
            AyuFilter.Filter copy = filter.copy();
            copy.enabled = !copy.enabled;
            AyuFilter.update(copy);
            listView.adapter.update(true);
        });
        options.add(R.drawable.msg_edit, getString(R.string.AyuFilterEdit), () -> presentFragment(new RegexFilterEditActivity(filter.id)));
        if (index > 0) {
            options.add(R.drawable.arrows_select, getString(R.string.AyuFilterMoveUp), () -> {
                AyuFilter.move(index, index - 1);
                listView.adapter.update(true);
            });
        }
        if (index < AyuFilter.getFilters().size() - 1) {
            options.add(R.drawable.arrows_select, getString(R.string.AyuFilterMoveDown), () -> {
                AyuFilter.move(index, index + 1);
                listView.adapter.update(true);
            });
        }
        options.add(R.drawable.msg_delete, getString(R.string.AyuFilterDelete), true, () -> {
            AyuFilter.remove(filter);
            listView.adapter.update(true);
        });
        options.setGravity(Gravity.RIGHT);
        options.show();
        return true;
    }

    private AyuFilter.Filter filterAt(int index) {
        ArrayList<AyuFilter.Filter> filters = AyuFilter.getFilters();
        if (index < 0 || index >= filters.size()) {
            return null;
        }
        return filters.get(index);
    }

    private void invalidatePatterns() {
        ArrayList<AyuFilter.Filter> filters = AyuFilter.getFilters();
        for (int i = 0; i < filters.size(); i++) {
            filters.get(i).invalidate();
        }
    }

    // ------------------------------------------------------------ share / import

    private void shareFilters() {
        if (AyuFilter.getFilters().isEmpty()) {
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.AyuFiltersShareEmpty)).show();
            return;
        }
        String link = AyuFilter.exportShareLink();
        if (TextUtils.isEmpty(link)) {
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.AyuFiltersShareEmpty)).show();
            return;
        }
        AndroidUtilities.addToClipboard(link);
        BulletinFactory.of(this).createCopyBulletin(getString(R.string.AyuFiltersLinkCopied)).show();
    }

    private void showImportDialog() {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setCursorColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setCursorSize(dp(20));
        editText.setCursorWidth(1.5f);
        editText.setBackground(null);
        editText.setSingleLine(true);
        editText.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);

        final OutlineTextContainerView container = new OutlineTextContainerView(context);
        container.setText(getString(R.string.AyuFiltersImportHint));
        container.setLeftPadding(dp(2));
        container.attachEditText(editText);
        container.addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 12, 4, 12, 4));
        editText.setOnFocusChangeListener((v, hasFocus) -> container.animateSelection(hasFocus, !TextUtils.isEmpty(editText.getText())));

        LinearLayout wrapper = new LinearLayout(context);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(container, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 8, 4, 0));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(R.string.AyuFiltersImportTitle));
        builder.setMessage(getString(R.string.AyuFiltersImportMessage));
        builder.setView(wrapper);
        builder.setPositiveButton(getString(R.string.Done), (dialog, which) -> {
            String value = editText.getText() == null ? "" : editText.getText().toString();
            int imported = AyuFilter.importFromLink(value);
            if (imported < 0) {
                BulletinFactory.of(this).createErrorBulletin(getString(R.string.AyuFiltersImportFailed)).show();
            } else if (imported == 0) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, getString(R.string.AyuFiltersImportNothing)).show();
            } else {
                BulletinFactory.of(this).createSuccessBulletin(LocaleController.formatString(R.string.AyuFiltersImportDone, imported)).show();
            }
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
        AndroidUtilities.runOnUIThread(() -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        }, 120);
    }
}
