package org.telegram.ui.ayu.menu;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.ayu.AyuForwarder;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;
import java.util.ArrayList;

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

    public interface EditDelegate {
        void forward(boolean hideSenderName, boolean hideCaption, boolean withoutSound, AyuForwarder.EditPayload edits);
    }

    private boolean hideSenderName;
    private boolean hideCaption;
    private boolean withoutSound;
    private AyuForwarder.EditPayload edits;
    private AlertDialog editor;

    public AyuSpecialForwardSheet(BaseFragment fragment, boolean canHideCaption, Delegate delegate) {
        this(fragment, canHideCaption, null, (sender, caption, sound, edits) -> {
            if (delegate != null) delegate.forward(sender, caption, sound);
        });
    }

    private AyuSpecialForwardSheet(BaseFragment fragment, boolean canHideCaption, ArrayList<MessageObject> messages, EditDelegate delegate) {
        super(fragment.getParentActivity(), false, fragment.getResourceProvider());
        final Context context = fragment.getParentActivity();
        final int account = fragment.getCurrentAccount();
        final long userId = UserConfig.getInstance(account).getClientUserId();
        final ArrayList<MessageObject> sources = messages == null ? null : new ArrayList<>(messages);
        AyuSpecialForwardEditor.inheritCaptureSecurity(this, fragment, sources);
        setApplyBottomPadding(false);
        fixNavigationBar();

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setSaveEnabled(false);
        container.setSaveFromParentEnabled(false);

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
            if (edits != null && !edits.isEmpty()) return;
            hideSenderName = !hideSenderName;
            senderCell.setChecked(hideSenderName);
        });
        container.addView(senderCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));

        final TextCheckCell captionCell = canHideCaption ? new TextCheckCell(context, fragment.getResourceProvider()) : null;
        if (captionCell != null) {
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

        if (sources != null && !sources.isEmpty()) {
            TextView editButton = new TextView(context);
            editButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            editButton.setTextColor(getThemedColor(Theme.key_dialogTextBlue));
            editButton.setText(getString(R.string.OxSpecialForwardEdit));
            editButton.setGravity(Gravity.CENTER_VERTICAL);
            editButton.setPadding(dp(22), 0, dp(22), 0);
            editButton.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 2));
            editButton.setOnClickListener(v -> {
                if (fragment.isFinished || fragment.getParentActivity() == null || userId == 0
                        || UserConfig.selectedAccount != account || ApplicationLoader.mainInterfacePaused
                        || fragment.getCurrentAccount() != account
                        || userId != UserConfig.getInstance(account).getClientUserId()) return;
                boolean supported = sources.size() <= 100;
                for (MessageObject source : sources) supported &= source.currentAccount == account && AyuForwarder.canEditMessage(source);
                if (!supported) {
                    editor = new AlertDialog.Builder(context, fragment.getResourceProvider())
                            .setMessage(getString(R.string.OxSpecialForwardUnsupported))
                            .setPositiveButton(getString(R.string.OK), null).create();
                    AyuSpecialForwardEditor.inheritCaptureSecurity(editor, fragment, sources);
                    editor.show();
                    return;
                }
                editor = AyuSpecialForwardEditor.show(fragment, sources, edits,
                        () -> isShowing() && !fragment.isFinished && fragment.getCurrentAccount() == account
                                && UserConfig.selectedAccount == account && !ApplicationLoader.mainInterfacePaused
                                && userId == UserConfig.getInstance(account).getClientUserId(), result -> {
                            edits = result.isEmpty() ? null : result;
                            boolean changed = edits != null;
                            senderCell.setEnabled(!changed);
                            senderCell.setAlpha(changed ? .5f : 1f);
                            senderCell.setChecked(changed || hideSenderName);
                            // Applying an edited caption makes it visible. The explicit hide option can suppress it afterwards.
                            hideCaption = false;
                            if (captionCell != null) captionCell.setChecked(false);
                            info.setText(getString(changed ? R.string.OxSpecialForwardCopyInfo : R.string.AyuSpecialForwardInfo));
                            editButton.setText(getString(changed ? R.string.OxSpecialForwardEditApplied : R.string.OxSpecialForwardEdit));
                        });
            });
            container.addView(editButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52));
        }

        TextView button = new TextView(context);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        button.setTypeface(AndroidUtilities.bold());
        button.setGravity(Gravity.CENTER);
        button.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
        button.setBackground(Theme.AdaptiveRipple.filledRect(getThemedColor(Theme.key_featuredStickers_addButton), 8));
        button.setText(getString(R.string.Forward));
        button.setOnClickListener(v -> {
            if (fragment.isFinished || userId == 0 || userId != UserConfig.getInstance(account).getClientUserId()
                    || UserConfig.selectedAccount != account || ApplicationLoader.mainInterfacePaused
                    || fragment.getCurrentAccount() != account
                    || edits != null && !edits.isValidFor(account, sources)) return;
            dismiss();
            if (delegate != null) {
                delegate.forward(edits != null || hideSenderName, hideCaption, withoutSound, edits);
            }
        });
        container.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48,
                Gravity.CENTER_HORIZONTAL, 16, 8, 16, 16));

        ScrollView scroll = new ScrollView(context);
        scroll.addView(container);
        setCustomView(scroll);
    }

    @Override
    public void dismiss() {
        if (editor != null) { editor.dismiss(); editor = null; }
        super.dismiss();
    }

    public static void show(BaseFragment fragment, boolean canHideCaption, Delegate delegate) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        fragment.showDialog(new AyuSpecialForwardSheet(fragment, canHideCaption, delegate));
    }

    public static void show(BaseFragment fragment, boolean canHideCaption, ArrayList<MessageObject> sources, EditDelegate delegate) {
        if (fragment == null || fragment.getParentActivity() == null) return;
        fragment.showDialog(new AyuSpecialForwardSheet(fragment, canHideCaption, sources, delegate));
    }
}
