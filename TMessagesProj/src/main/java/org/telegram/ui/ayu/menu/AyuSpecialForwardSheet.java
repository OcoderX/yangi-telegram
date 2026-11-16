package org.telegram.ui.ayu.menu;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Ox-gram "Special forward": lets the user pick the forward flags before the chat chooser opens.
 * Every option maps onto something the send pipeline really supports:
 * hide sender name -> {@code drop_author}, hide caption -> {@code drop_media_captions},
 * without sound -> {@code silent}.
 */
public class AyuSpecialForwardSheet extends BottomSheet {

    public interface Delegate {
        void forward(boolean hideSenderName, boolean hideCaption, boolean withoutSound);
    }

    private boolean hideSenderName;
    private boolean hideCaption;
    private boolean withoutSound;

    public AyuSpecialForwardSheet(BaseFragment fragment, boolean canHideCaption, Delegate delegate) {
        super(fragment.getParentActivity(), false, fragment.getResourceProvider());
        final Context context = fragment.getParentActivity();
        setApplyBottomPadding(false);
        fixNavigationBar();

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        title.setText(getString(R.string.AyuSpecialForward));
        title.setPadding(dp(22), dp(16), dp(22), dp(8));
        container.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextCheckCell senderCell = new TextCheckCell(context, fragment.getResourceProvider());
        senderCell.setTextAndCheck(getString(R.string.AyuSpecialForwardHideSender), false, true);
        senderCell.setOnClickListener(v -> {
            hideSenderName = !hideSenderName;
            senderCell.setChecked(hideSenderName);
        });
        container.addView(senderCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));

        if (canHideCaption) {
            final TextCheckCell captionCell = new TextCheckCell(context, fragment.getResourceProvider());
            captionCell.setTextAndCheck(getString(R.string.AyuSpecialForwardHideCaption), false, true);
            captionCell.setOnClickListener(v -> {
                hideCaption = !hideCaption;
                captionCell.setChecked(hideCaption);
            });
            container.addView(captionCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
        }

        final TextCheckCell soundCell = new TextCheckCell(context, fragment.getResourceProvider());
        soundCell.setTextAndCheck(getString(R.string.AyuSpecialForwardNoSound), false, false);
        soundCell.setOnClickListener(v -> {
            withoutSound = !withoutSound;
            soundCell.setChecked(withoutSound);
        });
        container.addView(soundCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));

        TextView info = new TextView(context);
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        info.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        info.setText(getString(R.string.AyuSpecialForwardInfo));
        info.setPadding(dp(22), dp(10), dp(22), dp(4));
        container.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView button = new TextView(context);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        button.setTypeface(AndroidUtilities.bold());
        button.setGravity(Gravity.CENTER);
        button.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
        button.setBackground(Theme.AdaptiveRipple.filledRect(getThemedColor(Theme.key_featuredStickers_addButton), 8));
        button.setText(getString(R.string.Forward));
        button.setOnClickListener(v -> {
            dismiss();
            if (delegate != null) {
                delegate.forward(hideSenderName, hideCaption, withoutSound);
            }
        });
        container.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48,
                Gravity.CENTER_HORIZONTAL, 16, 8, 16, 16));

        setCustomView(container);
    }

    public static void show(BaseFragment fragment, boolean canHideCaption, Delegate delegate) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        fragment.showDialog(new AyuSpecialForwardSheet(fragment, canHideCaption, delegate));
    }
}
