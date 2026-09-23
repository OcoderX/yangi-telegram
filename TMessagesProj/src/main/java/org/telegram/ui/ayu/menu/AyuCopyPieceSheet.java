package org.telegram.ui.ayu.menu;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Ox-gram "Copy piece of text": shows the whole message text in a selectable view so the user can
 * drag-select a range and copy just that part. The button copies the current selection, or the whole
 * text when nothing is selected. Long texts scroll.
 */
public class AyuCopyPieceSheet extends BottomSheet {

    private final TextView textView;
    private final BaseFragment fragment;

    public AyuCopyPieceSheet(BaseFragment fragment, CharSequence text) {
        super(fragment.getParentActivity(), false, fragment.getResourceProvider());
        this.fragment = fragment;
        final Context context = fragment.getParentActivity();
        setApplyBottomPadding(false);
        fixNavigationBar();

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        title.setText(getString(R.string.AyuCopyPiece));
        title.setPadding(dp(22), dp(16), dp(22), dp(4));
        container.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView hint = new TextView(context);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hint.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        hint.setText(getString(R.string.AyuCopyPieceHint));
        hint.setPadding(dp(22), 0, dp(22), dp(10));
        container.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        textView = new TextView(context);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        textView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        textView.setTextIsSelectable(true);
        textView.setPadding(dp(22), dp(2), dp(22), dp(12));
        textView.setText(text == null ? "" : text);

        ScrollView scrollView = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                final int max = (int) (AndroidUtilities.displaySize.y * 0.5f);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.addView(textView, new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        container.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView button = new TextView(context);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        button.setTypeface(AndroidUtilities.bold());
        button.setGravity(Gravity.CENTER);
        button.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
        button.setBackground(Theme.AdaptiveRipple.filledRect(getThemedColor(Theme.key_featuredStickers_addButton), 8));
        button.setText(getString(R.string.Copy));
        button.setOnClickListener(v -> copySelection());
        container.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48,
                Gravity.CENTER_HORIZONTAL, 16, 4, 16, 16));

        setCustomView(container);
    }

    private void copySelection() {
        CharSequence full = textView.getText();
        if (full == null) {
            dismiss();
            return;
        }
        int start = textView.getSelectionStart();
        int end = textView.getSelectionEnd();
        CharSequence selected;
        if (start >= 0 && end >= 0 && start != end) {
            selected = full.subSequence(Math.min(start, end), Math.max(start, end));
        } else {
            selected = full;
        }
        if (TextUtils.isEmpty(selected)) {
            dismiss();
            return;
        }
        AndroidUtilities.addToClipboard(selected.toString());
        dismiss();
        try {
            BulletinFactory.of(fragment)
                    .createSimpleBulletin(R.raw.copy, getString(R.string.AyuCopiedToClipboard))
                    .show();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /** shows the sheet when the message actually has text */
    public static void show(BaseFragment fragment, CharSequence text) {
        if (fragment == null || fragment.getParentActivity() == null || TextUtils.isEmpty(text)) {
            return;
        }
        fragment.showDialog(new AyuCopyPieceSheet(fragment, text));
    }
}
