package org.telegram.ui.ayu;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.ayu.AyuConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * Ox-gram "AI Tools" settings: provider, API key, model and base url used by the message context
 * menu. The key is stored in the AyuGram preferences and is never written to the log.
 */
public class AiToolsPreferencesActivity extends UniversalFragment {

    private static final int AI_ENABLED = 1;
    private static final int AI_PROVIDER = 2;
    private static final int AI_API_KEY = 3;
    private static final int AI_MODEL = 4;
    private static final int AI_BASE_URL = 5;

    public AiToolsPreferencesActivity() {
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
        return getString(R.string.AyuAiTools);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(getString(R.string.AyuAiTools)));
        items.add(UItem.asCheck(AI_ENABLED, getString(R.string.AyuAiEnable)).setChecked(AyuConfig.aiToolsEnabled));
        items.add(UItem.asShadow(getString(R.string.AyuAiEnableInfo)));

        items.add(UItem.asHeader(getString(R.string.AyuAiProvider)));
        items.add(UItem.asSettingsCell(AI_PROVIDER, 0, getString(R.string.AyuAiProvider), providerName()));
        items.add(UItem.asSettingsCell(AI_API_KEY, 0, getString(R.string.AyuAiApiKey), maskedKey()));
        items.add(UItem.asSettingsCell(AI_MODEL, 0, getString(R.string.AyuAiModel), AyuConfig.getAiModel()));
        items.add(UItem.asSettingsCell(AI_BASE_URL, 0, getString(R.string.AyuAiBaseUrl), AyuConfig.getAiBaseUrl()));
        items.add(UItem.asShadow(getString(R.string.AyuAiProviderInfo)));
    }

    private String providerName() {
        return getString(AyuConfig.aiProvider == AyuConfig.AI_PROVIDER_OPENAI
                ? R.string.AyuAiProviderOpenAi : R.string.AyuAiProviderClaude);
    }

    private String maskedKey() {
        final String key = AyuConfig.aiApiKey;
        if (TextUtils.isEmpty(key)) {
            return getString(R.string.AyuAiApiKeyNotSet);
        }
        // never show (or log) the key itself
        return "••••" + (key.length() > 4 ? key.substring(key.length() - 4) : "");
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case AI_ENABLED:
                AyuConfig.setAiToolsEnabled(!AyuConfig.aiToolsEnabled);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(AyuConfig.aiToolsEnabled);
                }
                update();
                break;
            case AI_PROVIDER:
                showProviderDialog();
                break;
            case AI_API_KEY:
                showTextDialog(R.string.AyuAiApiKey, AyuConfig.aiApiKey, "", true, AyuConfig::setAiApiKey);
                break;
            case AI_MODEL:
                showTextDialog(R.string.AyuAiModel, AyuConfig.aiModel, AyuConfig.getAiModel(), false, AyuConfig::setAiModel);
                break;
            case AI_BASE_URL:
                showTextDialog(R.string.AyuAiBaseUrl, AyuConfig.aiBaseUrl, AyuConfig.getAiBaseUrl(), false, AyuConfig::setAiBaseUrl);
                break;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void update() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.ayuConfigChanged);
    }

    private void showProviderDialog() {
        final Context context = getContext();
        if (context == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(context, resourceProvider);
        builder.setTitle(getString(R.string.AyuAiProvider));
        builder.setItems(new CharSequence[]{
                getString(R.string.AyuAiProviderClaude),
                getString(R.string.AyuAiProviderOpenAi)
        }, (dialog, which) -> {
            AyuConfig.setAiProvider(which == 1 ? AyuConfig.AI_PROVIDER_OPENAI : AyuConfig.AI_PROVIDER_CLAUDE);
            update();
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private interface ValueSetter {
        void set(String value);
    }

    private void showTextDialog(int titleRes, String current, String hint, boolean secret, ValueSetter setter) {
        final Context context = getContext();
        if (context == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(context, resourceProvider);
        builder.setTitle(getString(titleRes));

        final EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourceProvider));
        editText.setHintTextColor(Theme.getColor(Theme.key_groupcreate_hintText, resourceProvider));
        editText.setHint(hint);
        editText.setInputType(InputType.TYPE_CLASS_TEXT
                | (secret ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_URI));
        editText.setImeOptions(EditorInfo.IME_ACTION_DONE);
        editText.setSingleLine(true);
        editText.setPadding(dp(16), dp(13), dp(16), dp(13));
        editText.setCursorWidth(1.5f);
        editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, resourceProvider));
        editText.setText(current == null ? "" : current);
        editText.setSelection(editText.getText().length());

        final GradientDrawable fieldBackground = new GradientDrawable();
        fieldBackground.setCornerRadius(dp(22));
        fieldBackground.setColor(Theme.multAlpha(Theme.getColor(Theme.key_dialogTextBlack, resourceProvider), 0.06f));
        editText.setBackground(fieldBackground);

        final LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 9));

        builder.makeCustomMaxHeight();
        builder.setView(container);
        builder.setPositiveButton(getString(R.string.Save), (dialog, which) -> {
            setter.set(editText.getText().toString());
            AndroidUtilities.hideKeyboard(editText);
            update();
        });
        builder.setNegativeButton(getString(R.string.Cancel), (dialog, which) -> {
            AndroidUtilities.hideKeyboard(editText);
            dialog.dismiss();
        });

        final AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(d -> AndroidUtilities.hideKeyboard(editText));
        dialog.setOnShowListener(d -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        });
        showDialog(dialog);
    }
}
