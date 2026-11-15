package org.telegram.ui.ayu.menu;

import android.app.Dialog;
import android.os.Build;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FlagSecureReason;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.ayu.AyuForwardEditText;
import org.telegram.messenger.ayu.AyuForwarder;
import org.telegram.messenger.ayu.hidden.HiddenChatsController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** A bounded, per-item editor. The draft belongs to this invocation, never to chat history. */
final class AyuSpecialForwardEditor {
    static AlertDialog show(BaseFragment host, ArrayList<MessageObject> sources, AyuForwarder.EditPayload applied,
                            BooleanSupplier alive, Consumer<AyuForwarder.EditPayload> onApply) {
        final int account = host.getCurrentAccount();
        if (UserConfig.selectedAccount != account || ApplicationLoader.mainInterfacePaused || !alive.getAsBoolean()) return null;
        ArrayList<SpannableStringBuilder> drafts = new ArrayList<>();
        ArrayList<String> originalTexts = new ArrayList<>();
        ArrayList<ArrayList<TLRPC.MessageEntity>> originalEntities = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            TLRPC.Message owner = sources.get(i).messageOwner;
            String text = applied == null ? (owner.message == null ? "" : owner.message) : applied.textAt(i);
            ArrayList<TLRPC.MessageEntity> entities = applied == null ? owner.entities : applied.entitiesAt(i);
            drafts.add(AyuForwardEditText.editable(text, entities));
            originalTexts.add(owner.message == null ? "" : owner.message);
            originalEntities.add(owner.entities);
        }
        AyuForwarder.EditPayload identity = new AyuForwarder.EditPayload(account, sources, originalTexts, originalEntities);
        LinearLayout body = new LinearLayout(host.getParentActivity());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setSaveEnabled(false);
        body.setSaveFromParentEnabled(false);
        body.setPadding(AndroidUtilities.dp(20), 0, AndroidUtilities.dp(20), 0);
        TextView info = label(host, LocaleController.getString(R.string.OxSpecialForwardCopyInfo));
        body.addView(info, LayoutHelper.createLinear(-1, -2));
        TextView position = label(host, "");
        body.addView(position, LayoutHelper.createLinear(-1, -2, 0, 12, 0, 4));
        EditText field = new EditText(host.getParentActivity());
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, host.getResourceProvider()));
        field.setHintTextColor(Theme.getColor(Theme.key_dialogTextGray2, host.getResourceProvider()));
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        field.setSaveEnabled(false);
        field.setSaveFromParentEnabled(false);
        if (Build.VERSION.SDK_INT >= 26) field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setVerticalScrollBarEnabled(true);
        int height = Math.max(80, Math.min(220, (int) (AndroidUtilities.displaySize.y / AndroidUtilities.density / 3)));
        body.addView(field, LayoutHelper.createLinear(-1, height));
        LinearLayout navigation = new LinearLayout(host.getParentActivity());
        TextView previous = label(host, LocaleController.getString(R.string.OxSpecialForwardPrevious));
        TextView next = label(host, LocaleController.getString(R.string.OxSpecialForwardNext));
        previous.setGravity(Gravity.CENTER); next.setGravity(Gravity.CENTER);
        previous.setFocusable(true); next.setFocusable(true);
        navigation.addView(previous, LayoutHelper.createLinear(0, 48, 1f));
        navigation.addView(next, LayoutHelper.createLinear(0, 48, 1f));
        if (sources.size() > 1) body.addView(navigation, LayoutHelper.createLinear(-1, 48));
        int[] index = {0};
        Runnable bind = () -> {
            boolean caption = isCaption(sources.get(index[0]));
            position.setText(LocaleController.formatString(R.string.OxSpecialForwardItem, index[0] + 1, sources.size())
                    + " · " + LocaleController.getString(caption ? R.string.OxSpecialForwardCaption : R.string.OxSpecialForwardText));
            field.setHint(LocaleController.getString(caption ? R.string.OxSpecialForwardCaption : R.string.OxSpecialForwardText));
            field.setText(drafts.get(index[0]));
            previous.setEnabled(index[0] > 0); previous.setAlpha(index[0] > 0 ? 1f : .4f);
            next.setEnabled(index[0] + 1 < sources.size()); next.setAlpha(index[0] + 1 < sources.size() ? 1f : .4f);
        };
        previous.setOnClickListener(v -> { if (index[0] > 0) { drafts.set(index[0], new SpannableStringBuilder(field.getText())); index[0]--; bind.run(); } });
        next.setOnClickListener(v -> { if (index[0] + 1 < sources.size()) { drafts.set(index[0], new SpannableStringBuilder(field.getText())); index[0]++; bind.run(); } });
        bind.run();
        AlertDialog dialog = new AlertDialog.Builder(host.getParentActivity(), host.getResourceProvider())
                .setTitle(LocaleController.getString(R.string.OxSpecialForwardEdit))
                .setView(body)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.OxSpecialForwardApply), null).create();
        inheritCaptureSecurity(dialog, host, sources);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (UserConfig.selectedAccount != account || ApplicationLoader.mainInterfacePaused
                    || !alive.getAsBoolean() || !identity.isValidFor(account, sources)) { dialog.dismiss(); return; }
            drafts.set(index[0], new SpannableStringBuilder(field.getText()));
            ArrayList<String> texts = new ArrayList<>();
            ArrayList<ArrayList<TLRPC.MessageEntity>> entities = new ArrayList<>();
            for (int i = 0; i < drafts.size(); i++) {
                String text = drafts.get(i).toString();
                int limit = isCaption(sources.get(i)) ? MessagesController.getInstance(account).getCaptionMaxLengthLimit() : 4096;
                if ((!isCaption(sources.get(i)) && text.trim().isEmpty()) || text.length() > limit) {
                    index[0] = i; bind.run();
                    field.setError(LocaleController.formatString(R.string.OxSpecialForwardLength, limit));
                    return;
                }
                texts.add(text);
                entities.add(AyuForwardEditText.entities(drafts.get(i)));
            }
            onApply.accept(new AyuForwarder.EditPayload(account, sources, texts, entities));
            AndroidUtilities.hideKeyboard(field);
            dialog.dismiss();
        });
        return dialog;
    }

    /** Each dialog is a separate window; keep mandatory capture protection for its whole lifetime. */
    static void inheritCaptureSecurity(Dialog dialog, BaseFragment host, ArrayList<MessageObject> sources) {
        boolean secure = host.getParentActivity() != null
                && (FlagSecureReason.isSecuredNow(host.getParentActivity().getWindow())
                || (host.getParentActivity().getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
        if (!secure && sources != null) {
            for (MessageObject source : sources) {
                if (source != null && HiddenChatsController.getInstance(source.currentAccount).isHidden(source.getDialogId())) {
                    secure = true;
                    break;
                }
            }
        }
        // A direct flag on this owned child window cannot be disabled by the optional screenshot setting.
        if (secure && dialog.getWindow() != null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    private static boolean isCaption(MessageObject object) {
        return object.getDocument() != null || MessageObject.getMedia(object.messageOwner) instanceof TLRPC.TL_messageMediaPhoto;
    }

    private static TextView label(BaseFragment host, String text) {
        TextView view = new TextView(host.getParentActivity());
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, host.getResourceProvider()));
        return view;
    }
}
