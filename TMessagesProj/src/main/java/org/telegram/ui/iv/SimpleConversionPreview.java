package org.telegram.ui.iv;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.text.SpannableString;
import android.text.Spannable;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.QuoteSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.textclassifier.TextClassifier;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.FormattedDateSpan;

public final class SimpleConversionPreview {
    private SimpleConversionPreview() {}

    public static RichMessageSimpleConversion.Labels localizedLabels() {
        return new RichMessageSimpleConversion.Labels(
            LocaleController.getString(R.string.OxSimpleLabelUnknown),
            LocaleController.getString(R.string.OxSimpleLabelMediaNotAttached),
            LocaleController.getString(R.string.OxSimpleLabelFile),
            LocaleController.getString(R.string.OxSimpleLabelAudio),
            LocaleController.getString(R.string.OxSimpleLabelVideo),
            LocaleController.getString(R.string.OxSimpleLabelPhoto),
            LocaleController.getString(R.string.OxSimpleLabelWeb),
            LocaleController.getString(R.string.OxSimpleLabelAnimation),
            LocaleController.getString(R.string.OxSimpleLabelMap),
            LocaleController.getString(R.string.OxSimpleLabelEmoji),
            LocaleController.getString(R.string.OxSimpleLabelRemovedAction),
            LocaleController.getString(R.string.OxSimpleLabelChannel));
    }

    public static BottomSheet show(Context context, RichMessageSimpleConversion.Result result,
                                   Runnable onApply, Theme.ResourcesProvider resourcesProvider) {
        final BottomSheet sheet = new BottomSheet(context, false, resourcesProvider);
        sheet.setApplyTopPadding(false);
        sheet.setCanDismissWithSwipe(false);
        final LinearLayout root = new LinearLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                final int displayLimit = Math.min(dp(640), (int) (AndroidUtilities.displaySize.y * .75f));
                final int parentLimit = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED
                    ? displayLimit : MeasureSpec.getSize(heightMeasureSpec);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
                    Math.max(0, Math.min(parentLimit, displayLimit)), MeasureSpec.EXACTLY));
            }
        };
        root.setOrientation(LinearLayout.VERTICAL);

        final ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(20), dp(20), dp(12));
        scroll.addView(content, new ScrollView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, 0, 1f));

        addText(content, R.string.OxSimpleConversionTitle, 20, true, resourcesProvider, 0);
        addText(content, R.string.OxSimpleConversionDescription, 14, false, resourcesProvider, 8);
        addText(content, R.string.OxSimpleConversionPreviewLabel, 14, true, resourcesProvider, 18);
        final TextView preview = textView(context, 16, false, resourcesProvider);
        preview.setPadding(dp(12), dp(12), dp(12), dp(12));
        preview.setBackground(Theme.createRoundRectDrawable(dp(8),
            Theme.getColor(Theme.key_graySection, resourcesProvider)));
        preview.setTextIsSelectable(true);
        preview.setAutoLinkMask(0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            preview.setTextClassifier(TextClassifier.NO_OP);
        }
        preview.setHorizontallyScrolling(false);
        preview.setHighlightColor(Theme.getColor(Theme.key_chat_inTextSelectionHighlight, resourcesProvider));
        preview.setText(result == null || result.isEmpty()
            ? LocaleController.getString(R.string.OxSimpleConversionEmpty)
            : previewText(result, resourcesProvider));
        content.addView(preview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
            LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        final int flags = result == null ? 0 : result.warningFlags;
        if (flags != 0) {
            addText(content, R.string.OxSimpleConversionChanges, 14, true, resourcesProvider, 18);
            addWarning(content, flags, RichMessageSimpleConversion.STYLE_CHANGED, R.string.OxSimpleConversionStyle, resourcesProvider);
            addWarning(content, flags, RichMessageSimpleConversion.TABLE_AS_TEXT, R.string.OxSimpleConversionTable, resourcesProvider);
            addWarning(content, flags, RichMessageSimpleConversion.BUTTONS_AS_LINKS, R.string.OxSimpleConversionButtons, resourcesProvider);
            addWarning(content, flags, RichMessageSimpleConversion.INTERACTIVE_REMOVED, R.string.OxSimpleConversionInteractive, resourcesProvider);
            addWarning(content, flags, RichMessageSimpleConversion.MEDIA_NOT_ATTACHED, R.string.OxSimpleConversionMedia, resourcesProvider);
            addWarning(content, flags, RichMessageSimpleConversion.UNKNOWN_CONTENT, R.string.OxSimpleConversionUnknown, resourcesProvider);
            addWarning(content, flags, RichMessageSimpleConversion.LIMIT_REACHED, R.string.OxSimpleConversionLimit, resourcesProvider);
        }
        final boolean canApply = result != null && !result.blocked && !result.isEmpty() && onApply != null;
        if (result != null && result.blocked) {
            addText(content, R.string.OxSimpleConversionBlocked, 14, true, resourcesProvider, 12);
        }

        final LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(dp(20), dp(8), dp(20), dp(8));
        final TextView apply = textView(context, 14, true, resourcesProvider);
        apply.setGravity(Gravity.CENTER);
        apply.setMinHeight(dp(48));
        apply.setPadding(dp(12), dp(8), dp(12), dp(8));
        apply.setText(LocaleController.getString(R.string.OxSimpleConversionApply));
        apply.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText, resourcesProvider));
        apply.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(8),
            Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider),
            Theme.getColor(Theme.key_featuredStickers_addButtonPressed, resourcesProvider)));
        apply.setEnabled(canApply);
        apply.setAlpha(canApply ? 1f : .45f);
        actions.addView(apply, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final TextView cancel = textView(context, 14, true, resourcesProvider);
        cancel.setGravity(Gravity.CENTER);
        cancel.setMinHeight(dp(48));
        cancel.setPadding(dp(12), dp(8), dp(12), dp(8));
        cancel.setText(LocaleController.getString(R.string.OxSimpleConversionCancel));
        cancel.setTextColor(Theme.getColor(Theme.key_dialogTextBlue, resourcesProvider));
        cancel.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(8), Color.TRANSPARENT,
            Theme.getColor(Theme.key_listSelector, resourcesProvider)));
        actions.addView(cancel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
            LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        root.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final boolean[] applied = { false };
        apply.setOnClickListener(view -> {
            if (!canApply || applied[0] || sheet.isDismissed() || !sheet.isShowing()) return;
            applied[0] = true;
            apply.setEnabled(false);
            sheet.dismiss();
            onApply.run();
        });
        cancel.setOnClickListener(view -> sheet.dismiss());
        sheet.setCustomView(root);
        sheet.show();
        return sheet;
    }

    private static TextView textView(Context context, int size, boolean bold, Theme.ResourcesProvider resourcesProvider) {
        final TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        view.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        view.setGravity(Gravity.START);
        view.setLineSpacing(dp(2), 1f);
        if (bold) view.setTypeface(AndroidUtilities.bold());
        return view;
    }

    private static void addText(LinearLayout content, int resource, int size, boolean bold,
                                Theme.ResourcesProvider resourcesProvider, int topMargin) {
        final TextView view = textView(content.getContext(), size, bold, resourcesProvider);
        view.setText(LocaleController.getString(resource));
        content.addView(view, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
            LayoutHelper.WRAP_CONTENT, 0, topMargin, 0, 0));
    }

    private static void addWarning(LinearLayout content, int flags, int flag, int resource,
                                   Theme.ResourcesProvider resourcesProvider) {
        if ((flags & flag) == 0) return;
        final TextView view = textView(content.getContext(), 14, false, resourcesProvider);
        view.setText("• " + LocaleController.getString(resource));
        content.addView(view, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
            LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
    }

    private static CharSequence previewText(RichMessageSimpleConversion.Result result,
                                            Theme.ResourcesProvider resourcesProvider) {
        final SpannableString text = new SpannableString(result.text);
        if (result.entities == null) return text;
        final int linkColor = Theme.getColor(Theme.key_dialogTextLink, resourcesProvider);
        for (TLRPC.MessageEntity entity : result.entities) {
            if (entity == null || entity.offset < 0 || entity.length <= 0 || entity.offset >= text.length()) continue;
            final int end = (int) Math.min(text.length(), (long) entity.offset + entity.length);
            final Object span;
            if (entity instanceof TLRPC.TL_messageEntityBold) span = new StyleSpan(Typeface.BOLD);
            else if (entity instanceof TLRPC.TL_messageEntityItalic) span = new StyleSpan(Typeface.ITALIC);
            else if (entity instanceof TLRPC.TL_messageEntityUnderline) span = new UnderlineSpan();
            else if (entity instanceof TLRPC.TL_messageEntityStrike) span = new StrikethroughSpan();
            else if (entity instanceof TLRPC.TL_messageEntityCode || entity instanceof TLRPC.TL_messageEntityPre) span = new TypefaceSpan("monospace");
            else if (entity instanceof TLRPC.TL_messageEntityBlockquote) span = new QuoteSpan(linkColor);
            else if (entity instanceof TLRPC.TL_messageEntitySpoiler) span = new BackgroundColorSpan(Theme.multAlpha(linkColor, .12f));
            else if (entity instanceof TLRPC.TL_messageEntityFormattedDate) {
                span = new FormattedDateSpan(result.text.substring(entity.offset, end), null,
                    (TLRPC.TL_messageEntityFormattedDate) entity);
            }
            else if (entity instanceof TLRPC.TL_messageEntityUrl || entity instanceof TLRPC.TL_messageEntityTextUrl) {
                // Preview links retain styling and selection, with no navigation or link movement.
                span = new ForegroundColorSpan(linkColor);
                text.setSpan(new UnderlineSpan(), entity.offset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else continue;
            text.setSpan(span, entity.offset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        final CharSequence formatted = FormattedDateSpan.applyFormatedDateEntities(text);
        if (formatted instanceof Spannable) {
            final Spannable selectable = (Spannable) formatted;
            for (ClickableSpan span : selectable.getSpans(0, selectable.length(), ClickableSpan.class)) {
                selectable.removeSpan(span);
            }
        }
        return formatted;
    }
}
