package org.telegram.ui.ayu.menu;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.telegram.PhoneFormat.PhoneFormat;
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
 * Header of the OcoderX menu screen ({@link org.telegram.ui.ayu.OcoderXMenuFragment}), a rebuild of
 * the classic Telegram {@code DrawerProfileCell}: avatar, name and phone of the current account plus
 * the arrow that expands the list of the other logged in accounts.
 * <p>
 * The original cell was removed from this fork together with the side drawer, so only the pieces the
 * menu screen actually needs are recreated here (no wallpaper/snowflakes/premium status drawing).
 */
public class OxDrawerProfileCell extends FrameLayout {

    private final BackupImageView avatarImageView;
    private final TextView nameTextView;
    private final TextView phoneTextView;
    private final ImageView arrowView;
    private final AvatarDrawable avatarDrawable = new AvatarDrawable();

    private int accountNumber = UserConfig.selectedAccount;

    public OxDrawerProfileCell(Context context) {
        super(context);

        avatarImageView = new BackupImageView(context);
        avatarImageView.setRoundRadius(dp(32));
        addView(avatarImageView, LayoutHelper.createFrame(64, 64, Gravity.LEFT | Gravity.TOP, 16, 16, 0, 0));

        nameTextView = new TextView(context);
        nameTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        nameTextView.setTypeface(AndroidUtilities.bold());
        nameTextView.setMaxLines(1);
        nameTextView.setSingleLine(true);
        nameTextView.setEllipsize(TextUtils.TruncateAt.END);
        nameTextView.setGravity(Gravity.LEFT);
        addView(nameTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.TOP, 16, 90, 60, 0));

        phoneTextView = new TextView(context);
        phoneTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        phoneTextView.setMaxLines(1);
        phoneTextView.setSingleLine(true);
        phoneTextView.setEllipsize(TextUtils.TruncateAt.END);
        phoneTextView.setGravity(Gravity.LEFT);
        addView(phoneTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.TOP, 16, 112, 60, 0));

        arrowView = new ImageView(context);
        arrowView.setScaleType(ImageView.ScaleType.CENTER);
        arrowView.setImageResource(R.drawable.ic_arrow_drop_down);
        addView(arrowView, LayoutHelper.createFrame(48, 48, Gravity.RIGHT | Gravity.TOP, 0, 88, 6, 0));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(148), MeasureSpec.EXACTLY));
    }

    /** Repaints every piece with the current theme; call it from {@code updateThemeColors()} too. */
    public void updateColors() {
        setBackgroundColor(Theme.getColor(Theme.key_chats_menuBackground));
        nameTextView.setTextColor(Theme.getColor(Theme.key_chats_menuName));
        phoneTextView.setTextColor(Theme.getColor(Theme.key_chats_menuPhone));
        arrowView.setColorFilter(new PorterDuffColorFilter(
                Theme.getColor(Theme.key_chats_menuName), PorterDuff.Mode.SRC_IN));
    }

    /** Binds the cell to {@code account} and points the arrow up when the account list is open. */
    public void setAccount(int account, boolean accountsExpanded) {
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

        String subtitle = null;
        if (user != null) {
            if (user.phone != null && user.phone.length() != 0) {
                subtitle = PhoneFormat.getInstance().format("+" + user.phone);
            } else if (UserObject.getPublicUsername(user) != null) {
                subtitle = "@" + UserObject.getPublicUsername(user);
            }
        }
        phoneTextView.setText(subtitle != null ? subtitle : "");

        arrowView.setRotation(accountsExpanded ? 180 : 0);
        updateColors();
    }

    public int getAccountNumber() {
        return accountNumber;
    }

    public View getArrowView() {
        return arrowView;
    }
}
