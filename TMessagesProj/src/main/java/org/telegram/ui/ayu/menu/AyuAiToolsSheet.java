package org.telegram.ui.ayu.menu;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.ayu.AyuConfig;
import org.telegram.messenger.ayu.ai.AyuAiClient;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.ayu.AiToolsPreferencesActivity;

/**
 * Ox-gram "AI Tools" submenu for the message context menu: summarize / explain / rephrase /
 * translate / reply suggestions / ask about this message.
 * <p>
 * When no API key is configured every entry opens {@link AiToolsPreferencesActivity} instead and a
 * bulletin explains why.
 */
public class AyuAiToolsSheet extends BottomSheet {

    private static final int TOOL_SUMMARIZE = 0;
    private static final int TOOL_EXPLAIN = 1;
    private static final int TOOL_REPHRASE = 2;
    private static final int TOOL_TRANSLATE = 3;
    private static final int TOOL_REPLIES = 4;
    private static final int TOOL_ASK = 5;

    private static final String SYSTEM_PROMPT =
            "You are an assistant embedded in a Telegram client. The user selected one message in a chat. "
                    + "Answer concisely and in plain text, without markdown headings or preambles.";

    private final BaseFragment fragment;
    private final CharSequence messageText;
    private final AyuAiResultSheet.InsertDelegate insertDelegate;

    public AyuAiToolsSheet(BaseFragment fragment, CharSequence messageText, AyuAiResultSheet.InsertDelegate insertDelegate) {
        super(fragment.getParentActivity(), false, fragment.getResourceProvider());
        this.fragment = fragment;
        this.messageText = messageText;
        this.insertDelegate = insertDelegate;
        final Context context = fragment.getParentActivity();
        setApplyBottomPadding(false);
        fixNavigationBar();

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        title.setText(getString(R.string.AyuAiTools));
        title.setPadding(dp(22), dp(16), dp(22), dp(10));
        container.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        addRow(context, container, TOOL_SUMMARIZE, R.drawable.msg_viewreplies, R.string.AyuAiSummarize);
        addRow(context, container, TOOL_EXPLAIN, R.drawable.msg_info, R.string.AyuAiExplain);
        addRow(context, container, TOOL_REPHRASE, R.drawable.msg_edit, R.string.AyuAiRephrase);
        addRow(context, container, TOOL_TRANSLATE, R.drawable.msg_translate, R.string.AyuAiTranslate);
        addRow(context, container, TOOL_REPLIES, R.drawable.menu_quickreply, R.string.AyuAiReplySuggestions);
        addRow(context, container, TOOL_ASK, R.drawable.msg_bot, R.string.AyuAiAsk);

        container.addView(new android.view.View(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 12));
        setCustomView(container);
    }

    private void addRow(Context context, LinearLayout container, int tool, int icon, int titleRes) {
        TextCell cell = new TextCell(context, fragment.getResourceProvider());
        cell.setTextAndIcon(getString(titleRes), icon, false);
        cell.setOnClickListener(v -> {
            dismiss();
            onToolSelected(tool);
        });
        container.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
    }

    private void onToolSelected(int tool) {
        if (!AyuAiClient.isConfigured()) {
            try {
                BulletinFactory.of(fragment)
                        .createSimpleBulletin(R.raw.info, getString(R.string.AyuAiNotConfigured))
                        .show();
            } catch (Exception ignore) {
            }
            fragment.presentFragment(new AiToolsPreferencesActivity());
            return;
        }
        if (tool == TOOL_ASK) {
            askQuestion();
            return;
        }
        run(tool, null);
    }

    private void askQuestion() {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final EditText editText = new EditText(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, fragment.getResourceProvider()));
        editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, fragment.getResourceProvider()));
        editText.setHint(getString(R.string.AyuAiAskHint));
        editText.setSingleLine(false);
        editText.setMaxLines(4);

        LinearLayout wrapper = new LinearLayout(context);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(dp(22), dp(4), dp(22), 0);
        wrapper.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        AlertDialog.Builder builder = new AlertDialog.Builder(context, fragment.getResourceProvider());
        builder.setTitle(getString(R.string.AyuAiAsk));
        builder.setView(wrapper);
        builder.setPositiveButton(getString(R.string.Send), (dialog, which) -> {
            String question = editText.getText() == null ? "" : editText.getText().toString().trim();
            if (!TextUtils.isEmpty(question)) {
                run(TOOL_ASK, question);
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }

    private void run(int tool, String question) {
        final boolean insertable = tool == TOOL_REPHRASE || tool == TOOL_REPLIES;
        final AyuAiResultSheet sheet = new AyuAiResultSheet(fragment, getString(titleOf(tool)),
                insertable ? insertDelegate : null);
        fragment.showDialog(sheet);
        AyuAiClient.request(SYSTEM_PROMPT, buildPrompt(tool, question), new AyuAiClient.Callback() {
            @Override
            public void onResult(String text) {
                sheet.setResult(text, insertable);
            }

            @Override
            public void onError(String error) {
                sheet.setError(LocaleController.formatString(R.string.AyuAiError, error));
            }
        });
    }

    private static int titleOf(int tool) {
        switch (tool) {
            case TOOL_EXPLAIN:
                return R.string.AyuAiExplain;
            case TOOL_REPHRASE:
                return R.string.AyuAiRephrase;
            case TOOL_TRANSLATE:
                return R.string.AyuAiTranslate;
            case TOOL_REPLIES:
                return R.string.AyuAiReplySuggestions;
            case TOOL_ASK:
                return R.string.AyuAiAsk;
            case TOOL_SUMMARIZE:
            default:
                return R.string.AyuAiSummarize;
        }
    }

    private String buildPrompt(int tool, String question) {
        final String text = messageText == null ? "" : messageText.toString();
        final String language = LocaleController.getInstance().getCurrentLocaleInfo() != null
                ? LocaleController.getInstance().getCurrentLocaleInfo().name
                : "English";
        switch (tool) {
            case TOOL_EXPLAIN:
                return "Explain this message, including any slang, references or implied meaning. Answer in "
                        + language + ".\n\n---\n" + text;
            case TOOL_REPHRASE:
                return "Rewrite this message so it reads clearly and politely, keeping the same language and "
                        + "meaning. Reply with the rewritten text only.\n\n---\n" + text;
            case TOOL_TRANSLATE:
                return "Translate this message into " + language
                        + ". Reply with the translation only.\n\n---\n" + text;
            case TOOL_REPLIES:
                return "Suggest three short replies to this message, in the language of the message. "
                        + "Reply with the three options, one per line, without numbering.\n\n---\n" + text;
            case TOOL_ASK:
                return "Message:\n---\n" + text + "\n---\nQuestion about it: " + question
                        + "\nAnswer in " + language + ".";
            case TOOL_SUMMARIZE:
            default:
                return "Summarize this message in at most three sentences. Answer in " + language
                        + ".\n\n---\n" + text;
        }
    }

    /** entry point used by the message context menu */
    public static void show(BaseFragment fragment, CharSequence messageText, AyuAiResultSheet.InsertDelegate insertDelegate) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        if (!AyuConfig.aiToolsEnabled || TextUtils.isEmpty(AyuConfig.aiApiKey)) {
            try {
                BulletinFactory.of(fragment)
                        .createSimpleBulletin(R.raw.info, getString(R.string.AyuAiNotConfigured))
                        .show();
            } catch (Exception ignore) {
            }
            fragment.presentFragment(new AiToolsPreferencesActivity());
            return;
        }
        fragment.showDialog(new AyuAiToolsSheet(fragment, messageText, insertDelegate));
    }
}
