package org.telegram.ui.ayu.radar;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.ayu.radar.MentionRadar;
import org.telegram.messenger.ayu.radar.RadarConfig;
import org.telegram.messenger.ayu.radar.RadarKeyword;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.OutlineTextContainerView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * AyuGram Mention Radar: what the radar collects, plus the user keyword list
 * (per keyword: whole-word and regex toggles).
 */
public class RadarKeywordsActivity extends UniversalFragment {

    private static final int BUTTON_ENABLED = 1;
    private static final int BUTTON_MENTIONS = 2;
    private static final int BUTTON_REPLIES = 3;
    private static final int BUTTON_NAME = 4;
    private static final int BUTTON_KEYWORDS = 5;

    private static final int BUTTON_GROUPS = 10;
    private static final int BUTTON_CHANNELS = 11;
    private static final int BUTTON_PRIVATE = 12;
    private static final int BUTTON_MUTED = 13;

    private static final int BUTTON_ADD = 20;
    private static final int BUTTON_NOTIFY = 21;

    private static final int KEYWORD_ITEM_ID_OFFSET = 1000;

    public RadarKeywordsActivity() {
        super();
        RadarConfig.load();
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.RadarKeywords);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(getString(R.string.RadarCollectHeader)));
        items.add(UItem.asCheck(BUTTON_ENABLED, getString(R.string.RadarEnable)).setChecked(RadarConfig.enabled));
        items.add(UItem.asCheck(BUTTON_MENTIONS, getString(R.string.RadarCollectMentions)).setChecked(RadarConfig.matchMentions).setEnabled(RadarConfig.enabled));
        items.add(UItem.asCheck(BUTTON_REPLIES, getString(R.string.RadarCollectReplies)).setChecked(RadarConfig.matchReplies).setEnabled(RadarConfig.enabled));
        items.add(UItem.asCheck(BUTTON_NAME, getString(R.string.RadarCollectName)).setChecked(RadarConfig.matchName).setEnabled(RadarConfig.enabled));
        items.add(UItem.asCheck(BUTTON_KEYWORDS, getString(R.string.RadarCollectKeywords)).setChecked(RadarConfig.matchKeywords).setEnabled(RadarConfig.enabled));
        items.add(UItem.asShadow(getString(R.string.RadarCollectInfo)));

        items.add(UItem.asHeader(getString(R.string.RadarScopeHeader)));
        items.add(UItem.asCheck(BUTTON_GROUPS, getString(R.string.RadarScopeGroups)).setChecked(RadarConfig.includeGroups).setEnabled(RadarConfig.enabled));
        items.add(UItem.asCheck(BUTTON_CHANNELS, getString(R.string.RadarScopeChannels)).setChecked(RadarConfig.includeChannels).setEnabled(RadarConfig.enabled));
        items.add(UItem.asCheck(BUTTON_PRIVATE, getString(R.string.RadarScopePrivate)).setChecked(RadarConfig.includePrivateChats).setEnabled(RadarConfig.enabled));
        items.add(UItem.asCheck(BUTTON_MUTED, getString(R.string.RadarScopeSkipMuted)).setChecked(RadarConfig.skipMutedChats).setEnabled(RadarConfig.enabled));
        items.add(UItem.asShadow(getString(R.string.RadarScopeInfo)));

        items.add(UItem.asHeader(getString(R.string.RadarKeywordsHeader)));
        items.add(UItem.asButton(BUTTON_ADD, R.drawable.msg_add, getString(R.string.RadarKeywordAdd)).accent());

        final ArrayList<RadarKeyword> keywords = RadarConfig.getKeywords();
        for (int i = 0; i < keywords.size(); i++) {
            RadarKeyword keyword = keywords.get(i);
            items.add(UItem.asButtonCheck(KEYWORD_ITEM_ID_OFFSET + i, keyword.text, describe(keyword)).setChecked(keyword.enabled));
        }
        items.add(UItem.asShadow(getString(keywords.isEmpty() ? R.string.RadarKeywordsEmpty : R.string.RadarKeywordsInfo)));

        items.add(UItem.asCheck(BUTTON_NOTIFY, getString(R.string.RadarNotifyKeywords)).setChecked(RadarConfig.notifyOnKeyword));
        items.add(UItem.asShadow(getString(R.string.RadarNotifyKeywordsInfo)));
    }

    private CharSequence describe(RadarKeyword keyword) {
        StringBuilder sb = new StringBuilder();
        if (keyword.regex) {
            sb.append(getString(R.string.RadarKeywordRegex));
            if (!keyword.isValid()) {
                sb.append(", ").append(getString(R.string.RadarKeywordInvalidRegex));
            }
        } else if (keyword.wholeWord) {
            sb.append(getString(R.string.RadarKeywordWholeWord));
        } else {
            sb.append(getString(R.string.RadarKeywordAnywhere));
        }
        if (!keyword.enabled) {
            sb.append(", ").append(getString(R.string.RadarKeywordDisabled));
        }
        return sb.toString();
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == BUTTON_ENABLED) {
            RadarConfig.setEnabled(!RadarConfig.enabled);
            listView.adapter.update(true);
        } else if (item.id == BUTTON_MENTIONS) {
            RadarConfig.setMatchMentions(!RadarConfig.matchMentions);
            listView.adapter.update(true);
        } else if (item.id == BUTTON_REPLIES) {
            RadarConfig.setMatchReplies(!RadarConfig.matchReplies);
            listView.adapter.update(true);
        } else if (item.id == BUTTON_NAME) {
            RadarConfig.setMatchName(!RadarConfig.matchName);
            listView.adapter.update(true);
        } else if (item.id == BUTTON_KEYWORDS) {
            RadarConfig.setMatchKeywords(!RadarConfig.matchKeywords);
            listView.adapter.update(true);
        } else if (item.id == BUTTON_GROUPS) {
            RadarConfig.setIncludeGroups(!RadarConfig.includeGroups);
            listView.adapter.update(true);
        } else if (item.id == BUTTON_CHANNELS) {
            RadarConfig.setIncludeChannels(!RadarConfig.includeChannels);
            listView.adapter.update(true);
        } else if (item.id == BUTTON_PRIVATE) {
            RadarConfig.setIncludePrivateChats(!RadarConfig.includePrivateChats);
            listView.adapter.update(true);
        } else if (item.id == BUTTON_MUTED) {
            RadarConfig.setSkipMutedChats(!RadarConfig.skipMutedChats);
            listView.adapter.update(true);
        } else if (item.id == BUTTON_NOTIFY) {
            RadarConfig.setNotifyOnKeyword(!RadarConfig.notifyOnKeyword);
            listView.adapter.update(true);
        } else if (item.id == BUTTON_ADD) {
            showKeywordDialog(null);
        } else if (item.id >= KEYWORD_ITEM_ID_OFFSET) {
            RadarKeyword keyword = keywordAt(item.id - KEYWORD_ITEM_ID_OFFSET);
            if (keyword != null) {
                showKeywordDialog(keyword);
            }
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (item.id < KEYWORD_ITEM_ID_OFFSET) {
            return false;
        }
        final RadarKeyword keyword = keywordAt(item.id - KEYWORD_ITEM_ID_OFFSET);
        if (keyword == null) {
            return false;
        }
        ItemOptions options = ItemOptions.makeOptions(this, view);
        options.add(keyword.enabled ? R.drawable.msg_block : R.drawable.msg_message,
                getString(keyword.enabled ? R.string.RadarKeywordDisable : R.string.RadarKeywordEnable), () -> {
                    RadarKeyword copy = keyword.copy();
                    copy.enabled = !copy.enabled;
                    RadarConfig.updateKeyword(copy);
                    listView.adapter.update(true);
                });
        options.add(R.drawable.msg_edit, getString(R.string.RadarKeywordEdit), () -> showKeywordDialog(keyword));
        options.add(R.drawable.msg_delete, getString(R.string.RadarKeywordDelete), true, () -> {
            RadarConfig.removeKeyword(keyword.id);
            listView.adapter.update(true);
        });
        options.setGravity(Gravity.RIGHT);
        options.show();
        return true;
    }

    private RadarKeyword keywordAt(int index) {
        ArrayList<RadarKeyword> list = RadarConfig.getKeywords();
        if (index < 0 || index >= list.size()) {
            return null;
        }
        return list.get(index);
    }

    // ------------------------------------------------------------ add / edit dialog

    private void showKeywordDialog(RadarKeyword existing) {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final RadarKeyword keyword = existing != null ? existing.copy() : new RadarKeyword();

        final EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setCursorColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setCursorSize(dp(20));
        editText.setCursorWidth(1.5f);
        editText.setBackground(null);
        editText.setSingleLine(true);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editText.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        editText.setText(keyword.text == null ? "" : keyword.text);
        editText.setSelection(editText.length());

        final OutlineTextContainerView container = new OutlineTextContainerView(context);
        container.setText(getString(R.string.RadarKeywordHint));
        container.setLeftPadding(dp(2));
        container.attachEditText(editText);
        container.addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 12, 4, 12, 4));
        editText.setOnFocusChangeListener((v, hasFocus) -> container.animateSelection(hasFocus, !TextUtils.isEmpty(editText.getText())));

        final TextCheckCell wholeWordCell = new TextCheckCell(context);
        final TextCheckCell regexCell = new TextCheckCell(context);
        wholeWordCell.setTextAndCheck(getString(R.string.RadarKeywordWholeWord), keyword.wholeWord, true);
        regexCell.setTextAndCheck(getString(R.string.RadarKeywordRegex), keyword.regex, false);
        wholeWordCell.setOnClickListener(v -> {
            keyword.wholeWord = !keyword.wholeWord;
            wholeWordCell.setChecked(keyword.wholeWord);
        });
        regexCell.setOnClickListener(v -> {
            keyword.regex = !keyword.regex;
            regexCell.setChecked(keyword.regex);
            wholeWordCell.setEnabled(!keyword.regex);
            wholeWordCell.setAlpha(keyword.regex ? 0.5f : 1f);
        });
        wholeWordCell.setEnabled(!keyword.regex);
        wholeWordCell.setAlpha(keyword.regex ? 0.5f : 1f);

        LinearLayout wrapper = new LinearLayout(context);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(container, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 8, 4, 4));
        wrapper.addView(wholeWordCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        wrapper.addView(regexCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(existing == null ? R.string.RadarKeywordAdd : R.string.RadarKeywordEdit));
        builder.setView(wrapper);
        builder.setPositiveButton(getString(R.string.Done), (dialog, which) -> {
            String value = editText.getText() == null ? "" : editText.getText().toString().trim();
            if (TextUtils.isEmpty(value)) {
                BulletinFactory.of(this).createErrorBulletin(getString(R.string.RadarKeywordEmptyError)).show();
                return;
            }
            keyword.text = value;
            keyword.invalidate();
            if (keyword.regex && !keyword.isValid()) {
                BulletinFactory.of(this).createErrorBulletin(getString(R.string.RadarKeywordInvalidRegex)).show();
                return;
            }
            if (existing == null) {
                RadarConfig.addKeyword(keyword);
            } else {
                RadarConfig.updateKeyword(keyword);
            }
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        if (existing != null) {
            builder.setNeutralButton(getString(R.string.RadarKeywordDelete), (dialog, which) -> {
                RadarConfig.removeKeyword(existing.id);
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
            });
        }
        showDialog(builder.create());
        AndroidUtilities.runOnUIThread(() -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        }, 120);
    }

    @Override
    public void onResume() {
        super.onResume();
        MentionRadar.getInstance(currentAccount).start();
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(false);
        }
    }
}
