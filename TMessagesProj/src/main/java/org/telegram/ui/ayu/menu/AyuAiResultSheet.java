package org.telegram.ui.ayu.menu;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
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
import org.telegram.ui.Components.RadialProgressView;

/**
 * Ox-gram AI Tools: result sheet. Opens in a loading state and is filled once the model answered.
 * "Copy" is always available, "Insert into input field" only for the tools that produce text meant
 * to be sent (rephrase, reply suggestions).
 */
public class AyuAiResultSheet extends BottomSheet {

    public interface InsertDelegate {
        void insert(CharSequence text);
    }

    private final BaseFragment fragment;
    private final TextView textView;
    private final RadialProgressView progressView;
    private final TextView copyButton;
    private final TextView insertButton;
    private final InsertDelegate insertDelegate;

    private CharSequence result;

    public AyuAiResultSheet(BaseFragment fragment, CharSequence title, InsertDelegate insertDelegate) {
        super(fragment.getParentActivity(), false, fragment.getResourceProvider());
        this.fragment = fragment;
        this.insertDelegate = insertDelegate;
        final Context context = fragment.getParentActivity();
        setApplyBottomPadding(false);
        fixNavigationBar();

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        titleView.setText(title);
        titleView.setPadding(dp(22), dp(16), dp(22), dp(10));
        container.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        FrameLayout content = new FrameLayout(context);

        textView = new TextView(context);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        textView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        textView.setTextIsSelectable(true);
        textView.setPadding(dp(22), dp(2), dp(22), dp(12));
        textView.setVisibility(View.GONE);

        ScrollView scrollView = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                final int max = (int) (AndroidUtilities.displaySize.y * 0.5f);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        scrollView.addView(textView, new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        content.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        progressView = new RadialProgressView(context, fragment.getResourceProvider());
        progressView.setSize(dp(30));
        content.addView(progressView, LayoutHelper.createFrame(50, 50, Gravity.CENTER, 0, 24, 0, 24));

        container.addView(content, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        insertButton = makeButton(context, getString(R.string.AyuAiInsert), false);
        insertButton.setVisibility(View.GONE);
        insertButton.setOnClickListener(v -> {
            if (insertDelegate != null && !TextUtils.isEmpty(result)) {
                insertDelegate.insert(result);
            }
            dismiss();
        });
        buttons.addView(insertButton, LayoutHelper.createLinear(0, 48, 1f, 16, 4, 4, 16));

        copyButton = makeButton(context, getString(R.string.Copy), true);
        copyButton.setVisibility(View.GONE);
        copyButton.setOnClickListener(v -> copy());
        buttons.addView(copyButton, LayoutHelper.createLinear(0, 48, 1f, 4, 4, 16, 16));

        container.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        setCustomView(container);
    }

    private TextView makeButton(Context context, CharSequence text, boolean filled) {
        TextView button = new TextView(context);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        button.setTypeface(AndroidUtilities.bold());
        button.setGravity(Gravity.CENTER);
        if (filled) {
            button.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
            button.setBackground(Theme.AdaptiveRipple.filledRect(getThemedColor(Theme.key_featuredStickers_addButton), 8));
        } else {
            button.setTextColor(getThemedColor(Theme.key_featuredStickers_addButton));
            button.setBackground(Theme.AdaptiveRipple.filledRect(getThemedColor(Theme.key_listSelector), 8));
        }
        button.setText(text);
        return button;
    }

    /** fills the sheet with the model answer */
    public void setResult(CharSequence text, boolean insertable) {
        result = text;
        progressView.setVisibility(View.GONE);
        textView.setVisibility(View.VISIBLE);
        textView.setText(text);
        copyButton.setVisibility(View.VISIBLE);
        if (insertable && insertDelegate != null) {
            insertButton.setVisibility(View.VISIBLE);
        }
    }

    /** shows the failure inline and keeps the sheet open so the message stays readable */
    public void setError(CharSequence error) {
        result = null;
        progressView.setVisibility(View.GONE);
        textView.setVisibility(View.VISIBLE);
        textView.setTextColor(getThemedColor(Theme.key_text_RedBold));
        textView.setText(error);
    }

    private void copy() {
        if (TextUtils.isEmpty(result)) {
            dismiss();
            return;
        }
        AndroidUtilities.addToClipboard(result.toString());
        dismiss();
        try {
            BulletinFactory.of(fragment)
                    .createSimpleBulletin(R.raw.copy, getString(R.string.AyuCopiedToClipboard))
                    .show();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
