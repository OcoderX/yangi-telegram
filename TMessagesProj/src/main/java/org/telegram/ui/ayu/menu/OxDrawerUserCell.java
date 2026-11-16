package org.telegram.ui.ayu.menu;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

/**
 * One logged in account inside the expanded account list of the OcoderX menu screen: avatar, name
 * and a check mark on the account that is currently selected. Rebuild of the classic Telegram
 * {@code DrawerUserCell}, which this fork removed together with the side drawer.
 */
public class OxDrawerUserCell extends FrameLayout {

    private final BackupImageView avatarImageView;
    private final TextView nameTextView;
    private final ImageView checkView;
    private final AvatarDrawable avatarDrawable = new AvatarDrawable();

    private int accountNumber;

    public OxDrawerUserCell(Context context) {
        super(context);

        avatarImageView = new BackupImageView(context);
        avatarImageView.setRoundRadius(dp(18));
        addView(avatarImageView, LayoutHelper.createFrame(36, 36, Gravity.LEFT | Gravity.CENTER_VERTICAL, 14, 0, 0, 0));

        nameTextView = new TextView(context);
        nameTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        nameTextView.setTypeface(AndroidUtilities.bold());
        nameTextView.setMaxLines(1);
        nameTextView.setSingleLine(true);
        nameTextView.setEllipsize(TextUtils.TruncateAt.END);
        nameTextView.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        addView(nameTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.CENTER_VERTICAL, 64, 0, 52, 0));

        checkView = new ImageView(context);
        checkView.setScaleType(ImageView.ScaleType.CENTER);
        checkView.setImageResource(R.drawable.account_check);
        addView(checkView, LayoutHelper.createFrame(24, 24, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 18, 0));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(56), MeasureSpec.EXACTLY));
    }

    public void setAccount(int account, boolean selected) {
        accountNumber = account;
        final TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();

        avatarDrawable.setInfo(account, user);
        avatarImageView.getImageReceiver().setCurrentAccount(account);
        if (user != null) {
            avatarImageView.setForUserOrChat(user, avatarDrawable);
        } else {
            avatarImageView.setImageDrawable(avatarDrawable);
        }

        final CharSequence name = user != null ? UserObject.getUserName(user) : "";
        nameTextView.setText(Emoji.replaceEmoji(name, nameTextView.getPaint().getFontMetricsInt(), false));
        nameTextView.setTextColor(Theme.getColor(Theme.key_chats_menuItemText));

        checkView.setVisibility(selected ? VISIBLE : GONE);
        checkView.setColorFilter(new PorterDuffColorFilter(
                Theme.getColor(Theme.key_chats_menuItemIcon), PorterDuff.Mode.SRC_IN));
    }

    public int getAccountNumber() {
        return accountNumber;
    }
}
