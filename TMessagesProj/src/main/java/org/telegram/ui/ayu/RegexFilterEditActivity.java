package org.telegram.ui.ayu;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.ayu.AyuConfig;
import org.telegram.messenger.ayu.AyuFilter;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.EditTextSuggestionsFix;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.OutlineTextContainerView;
import org.telegram.ui.DialogsActivity;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Editor for a single AyuGram regex message filter.
 */
public class RegexFilterEditActivity extends BaseFragment {

    private static final int done_button = 1;

    private final AyuFilter.Filter filter;
    private final boolean isNew;

    private EditTextBoldCursor regexField;
    private OutlineTextContainerView regexContainer;
    private TextInfoPrivacyCell regexInfoCell;

    private EditTextBoldCursor testField;
    private OutlineTextContainerView testContainer;
    private TextInfoPrivacyCell testInfoCell;

    private TextSettingsCell scopeCell;
    private TextCheckCell enabledCell;
    private TextCheckCell caseInsensitiveCell;
    private TextCheckCell matchCaptionCell;
    private TextCheckCell matchSenderNameCell;

    public RegexFilterEditActivity() {
        this(null);
    }

    public RegexFilterEditActivity(String filterId) {
        AyuFilter.Filter existing = filterId == null ? null : AyuFilter.getById(filterId);
        if (existing == null) {
            filter = new AyuFilter.Filter();
            isNew = true;
        } else {
            filter = existing.copy();
            isNew = false;
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(isNew ? R.string.AyuFilterNewTitle : R.string.AyuFilterEditTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == done_button) {
                    saveAndFinish();
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        menu.addItemWithWidth(done_button, R.drawable.ic_ab_done, dp(56), getString(R.string.Done));

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));

        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(linearLayout, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        // ---- regex ----
        linearLayout.addView(createHeader(context, getString(R.string.AyuFilterRegexHeader)));

        FrameLayout regexWrap = new FrameLayout(context);
        regexWrap.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        regexField = createEditText(context);
        regexField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        regexContainer = createContainer(context, regexField, getString(R.string.AyuFilterRegexHint));
        regexWrap.addView(regexContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 17, 6, 17, 14));
        linearLayout.addView(regexWrap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        regexInfoCell = new TextInfoPrivacyCell(context);
        regexInfoCell.setBackground(Theme.getThemedDrawableByKey(context, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
        linearLayout.addView(regexInfoCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // ---- test ----
        linearLayout.addView(createHeader(context, getString(R.string.AyuFilterTestHeader)));

        FrameLayout testWrap = new FrameLayout(context);
        testWrap.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        testField = createEditText(context);
        testField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        testContainer = createContainer(context, testField, getString(R.string.AyuFilterTestHint));
        testWrap.addView(testContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 17, 6, 17, 14));
        linearLayout.addView(testWrap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        testInfoCell = new TextInfoPrivacyCell(context);
        testInfoCell.setBackground(Theme.getThemedDrawableByKey(context, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
        linearLayout.addView(testInfoCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // ---- scope ----
        linearLayout.addView(createHeader(context, getString(R.string.AyuFilterScopeHeader)));

        scopeCell = new TextSettingsCell(context);
        scopeCell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        scopeCell.setOnClickListener(v -> chooseScope());
        linearLayout.addView(scopeCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextInfoPrivacyCell scopeInfo = new TextInfoPrivacyCell(context);
        scopeInfo.setBackground(Theme.getThemedDrawableByKey(context, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
        scopeInfo.setText(getString(R.string.AyuFilterRegexInfo));
        linearLayout.addView(scopeInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // ---- options ----
        linearLayout.addView(createHeader(context, getString(R.string.AyuFilterOptionsHeader)));

        enabledCell = createCheckCell(context, linearLayout, v -> {
            filter.enabled = !filter.enabled;
            enabledCell.setChecked(filter.enabled);
        });
        caseInsensitiveCell = createCheckCell(context, linearLayout, v -> {
            boolean now = filter.caseInsensitive != null ? filter.caseInsensitive : AyuConfig.regexFiltersCaseInsensitive;
            filter.caseInsensitive = !now;
            filter.invalidate();
            caseInsensitiveCell.setChecked(!now);
            updateValidation();
        });
        matchCaptionCell = createCheckCell(context, linearLayout, v -> {
            filter.matchCaption = !filter.matchCaption;
            matchCaptionCell.setChecked(filter.matchCaption);
        });
        matchSenderNameCell = createCheckCell(context, linearLayout, v -> {
            filter.matchSenderName = !filter.matchSenderName;
            matchSenderNameCell.setChecked(filter.matchSenderName);
        });

        TextInfoPrivacyCell optionsInfo = new TextInfoPrivacyCell(context);
        optionsInfo.setBackground(Theme.getThemedDrawableByKey(context, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
        optionsInfo.setText(getString(R.string.AyuFiltersGeneralInfo));
        linearLayout.addView(optionsInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // ---- delete ----
        if (!isNew) {
            TextSettingsCell deleteCell = new TextSettingsCell(context);
            deleteCell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
            deleteCell.setText(getString(R.string.AyuFilterDelete), false);
            deleteCell.setTextColor(getThemedColor(Theme.key_text_RedRegular));
            deleteCell.setOnClickListener(v -> confirmDelete());
            linearLayout.addView(deleteCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            TextInfoPrivacyCell deleteInfo = new TextInfoPrivacyCell(context);
            deleteInfo.setBackground(Theme.getThemedDrawableByKey(context, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
            linearLayout.addView(deleteInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        regexField.setText(filter.text == null ? "" : filter.text);
        regexField.setSelection(regexField.length());

        updateScopeCell();
        updateCheckCells();
        updateValidation();

        fragmentView = scrollView;
        return fragmentView;
    }

    private HeaderCell createHeader(Context context, CharSequence text) {
        HeaderCell cell = new HeaderCell(context);
        cell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        cell.setText(text);
        return cell;
    }

    private TextCheckCell createCheckCell(Context context, LinearLayout parent, View.OnClickListener listener) {
        TextCheckCell cell = new TextCheckCell(context);
        cell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        cell.setOnClickListener(listener);
        parent.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return cell;
    }

    private EditTextBoldCursor createEditText(Context context) {
        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setCursorColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setCursorSize(dp(20));
        editText.setCursorWidth(1.5f);
        editText.setBackground(null);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setMaxLines(6);
        editText.setTypeface(Typeface.DEFAULT);
        editText.setHighlightColor(getThemedColor(Theme.key_chat_inTextSelectionHighlight));
        editText.setHandlesColor(getThemedColor(Theme.key_chat_TextSelectionCursor));
        editText.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        return editText;
    }

    private OutlineTextContainerView createContainer(Context context, EditTextBoldCursor editText, String title) {
        OutlineTextContainerView container = new OutlineTextContainerView(context);
        container.setText(title);
        container.setLeftPadding(dp(2));
        container.attachEditText(editText);
        container.addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 12, 4, 12, 4));
        editText.setOnFocusChangeListener((v, hasFocus) -> container.animateSelection(hasFocus, !TextUtils.isEmpty(editText.getText())));
        editText.addTextChangedListener(new EditTextSuggestionsFix());
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                container.animateSelection(editText.isFocused(), !TextUtils.isEmpty(s));
                if (editText == regexField) {
                    filter.text = s == null ? "" : s.toString();
                    filter.invalidate();
                }
                updateValidation();
            }
        });
        return container;
    }

    // ------------------------------------------------------------ state

    private void updateScopeCell() {
        if (scopeCell == null) {
            return;
        }
        String value;
        if (filter.dialogId == 0) {
            value = getString(R.string.AyuFiltersScopeGlobal);
        } else {
            String name = DialogObject.getName(currentAccount, filter.dialogId);
            value = TextUtils.isEmpty(name) ? String.valueOf(filter.dialogId) : name;
        }
        scopeCell.setTextAndValue(getString(R.string.AyuFilterScope), value, false);
    }

    private void updateCheckCells() {
        if (enabledCell == null) {
            return;
        }
        enabledCell.setTextAndCheck(getString(R.string.AyuFilterEnabled), filter.enabled, true);
        boolean ci = filter.caseInsensitive != null ? filter.caseInsensitive : AyuConfig.regexFiltersCaseInsensitive;
        caseInsensitiveCell.setTextAndCheck(getString(R.string.AyuFilterCaseInsensitiveOverride), ci, true);
        matchCaptionCell.setTextAndCheck(getString(R.string.AyuFilterMatchCaption), filter.matchCaption, true);
        matchSenderNameCell.setTextAndCheck(getString(R.string.AyuFilterMatchSenderName), filter.matchSenderName, false);
    }

    private void updateValidation() {
        if (regexInfoCell == null || testInfoCell == null) {
            return;
        }
        final String text = regexField == null || regexField.getText() == null ? "" : regexField.getText().toString();
        Pattern pattern = null;
        String error = null;
        if (TextUtils.isEmpty(text)) {
            error = null;
        } else {
            try {
                int flags = Pattern.MULTILINE;
                boolean ci = filter.caseInsensitive != null ? filter.caseInsensitive : AyuConfig.regexFiltersCaseInsensitive;
                if (ci) {
                    flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                }
                pattern = Pattern.compile(text, flags);
            } catch (PatternSyntaxException e) {
                error = e.getDescription() != null ? e.getDescription() : e.getMessage();
            } catch (Exception e) {
                error = e.getMessage();
            }
        }

        if (error != null) {
            regexInfoCell.setText(getString(R.string.AyuFiltersInvalidRegex) + ": " + error);
            if (regexInfoCell.getTextView() != null) {
                regexInfoCell.getTextView().setTextColor(getThemedColor(Theme.key_text_RedRegular));
            }
        } else {
            regexInfoCell.setText(TextUtils.isEmpty(text) ? getString(R.string.AyuFilterRegexInfo) : getString(R.string.AyuFilterRegexValid));
            if (regexInfoCell.getTextView() != null) {
                regexInfoCell.getTextView().setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText4));
            }
        }

        final String testText = testField == null || testField.getText() == null ? "" : testField.getText().toString();
        if (TextUtils.isEmpty(testText) || pattern == null) {
            testInfoCell.setText(getString(R.string.AyuFilterTestEmpty));
            if (testInfoCell.getTextView() != null) {
                testInfoCell.getTextView().setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText4));
            }
        } else {
            boolean matches;
            try {
                matches = pattern.matcher(testText).find();
            } catch (Exception e) {
                matches = false;
            }
            testInfoCell.setText(getString(matches ? R.string.AyuFilterTestMatches : R.string.AyuFilterTestNoMatch));
            if (testInfoCell.getTextView() != null) {
                testInfoCell.getTextView().setTextColor(getThemedColor(matches ? Theme.key_windowBackgroundWhiteGreenText : Theme.key_windowBackgroundWhiteGrayText4));
            }
        }
    }

    // ------------------------------------------------------------ actions

    private void chooseScope() {
        if (filter.dialogId != 0) {
            filter.dialogId = 0;
            updateScopeCell();
            return;
        }
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putBoolean("checkCanWrite", false);
        args.putBoolean("allowGlobalSearch", false);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        DialogsActivity activity = new DialogsActivity(args);
        activity.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids != null && !dids.isEmpty()) {
                filter.dialogId = dids.get(0).dialogId;
                updateScopeCell();
            }
            fragment.finishFragment();
            return true;
        });
        presentFragment(activity);
    }

    private void confirmDelete() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.AyuFilterDeleteTitle));
        builder.setMessage(getString(R.string.AyuFilterDeleteMessage));
        builder.setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
            AyuFilter.remove(filter);
            finishFragment();
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void saveAndFinish() {
        final String text = regexField == null || regexField.getText() == null ? "" : regexField.getText().toString();
        if (TextUtils.isEmpty(text)) {
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.AyuFilterEmptyError)).show();
            AndroidUtilities.shakeView(regexContainer);
            return;
        }
        filter.text = text;
        filter.invalidate();
        if (!filter.isValid()) {
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.AyuFiltersInvalidRegex)).show();
            AndroidUtilities.shakeView(regexContainer);
            return;
        }
        if (isNew) {
            AyuFilter.add(filter);
        } else {
            AyuFilter.update(filter);
        }
        finishFragment();
    }

}
