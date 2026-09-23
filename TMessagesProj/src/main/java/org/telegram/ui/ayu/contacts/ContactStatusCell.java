package org.telegram.ui.ayu.contacts;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Ox-gram contacts: one row of the online / special / tracker lists — avatar, name, status line and
 * an optional trailing action button (the "special contact" star or a trash icon).
 * <p>
 * Hand-rolled instead of reusing {@code UserCell} because that cell keeps its icon on the leading
 * side and has no tappable trailing button, and because the status text has to be rendered for an
 * explicit account (UserCell always uses {@code UserConfig.selectedAccount}).
 */
public class ContactStatusCell extends FrameLayout {

    private final BackupImageView avatarImageView;
    private final AvatarDrawable avatarDrawable = new AvatarDrawable();
    private final TextView nameTextView;
    private final TextView statusTextView;
    private final ImageView actionButton;

    private final Paint dividerPaint = new Paint();

    public boolean needDivider;
    private boolean statusOnline;
    private long userId;

    public ContactStatusCell(Context context) {
        super(context);
        setWillNotDraw(false);

        final boolean rtl = LocaleController.isRTL;
        final int gravity = Gravity.TOP | (rtl ? Gravity.RIGHT : Gravity.LEFT);

        avatarImageView = new BackupImageView(context);
        avatarImageView.setRoundRadius(dp(23));
        addView(avatarImageView, LayoutHelper.createFrame(46, 46, gravity, rtl ? 0 : 13, 7, rtl ? 13 : 0, 0));

        nameTextView = new TextView(context);
        nameTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        nameTextView.setTypeface(AndroidUtilities.bold());
        nameTextView.setSingleLine(true);
        nameTextView.setEllipsize(TextUtils.TruncateAt.END);
        nameTextView.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
        addView(nameTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, gravity, rtl ? 56 : 72, 9, rtl ? 72 : 56, 0));

        statusTextView = new TextView(context);
        statusTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        statusTextView.setSingleLine(true);
        statusTextView.setEllipsize(TextUtils.TruncateAt.END);
        statusTextView.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
        addView(statusTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, gravity, rtl ? 56 : 72, 32, rtl ? 72 : 56, 0));

        actionButton = new ImageView(context);
        actionButton.setScaleType(ImageView.ScaleType.CENTER);
        actionButton.setVisibility(GONE);
        actionButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1));
        addView(actionButton, LayoutHelper.createFrame(48, 48, Gravity.CENTER_VERTICAL | (rtl ? Gravity.LEFT : Gravity.RIGHT), rtl ? 6 : 0, 0, rtl ? 0 : 6, 0));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(60), MeasureSpec.EXACTLY));
    }

    public long getUserId() {
        return userId;
    }

    public void setUser(int account, TLRPC.User user, CharSequence status, boolean online, boolean divider) {
        this.needDivider = divider;
        this.statusOnline = online;
        this.userId = user == null ? 0 : user.id;

        if (user == null) {
            avatarDrawable.setInfo(0L, null, null);
            avatarImageView.setImageDrawable(avatarDrawable);
        } else {
            avatarDrawable.setInfo(account, user);
            avatarImageView.setForUserOrChat(user, avatarDrawable);
        }

        CharSequence name = user == null ? "" : UserObject.getUserName(user);
        if (TextUtils.isEmpty(name)) {
            name = String.valueOf(userId);
        }
        nameTextView.setText(name);
        statusTextView.setText(status == null ? "" : status);

        updateColors();
    }

    /** shows the trailing button; {@code resId == 0} hides it */
    public void setAction(int resId, CharSequence contentDescription, OnClickListener listener) {
        if (resId == 0) {
            actionButton.setVisibility(GONE);
            actionButton.setOnClickListener(null);
            return;
        }
        actionButton.setVisibility(VISIBLE);
        actionButton.setImageResource(resId);
        actionButton.setContentDescription(contentDescription);
        actionButton.setOnClickListener(listener);
        actionButton.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
    }

    /** tints the trailing button with the accent color (used for an active star) */
    public void setActionActive(boolean active) {
        actionButton.setColorFilter(new PorterDuffColorFilter(
                Theme.getColor(active ? Theme.key_windowBackgroundWhiteBlueIcon : Theme.key_windowBackgroundWhiteGrayIcon),
                PorterDuff.Mode.MULTIPLY));
    }

    public View getActionButton() {
        return actionButton;
    }

    public void updateColors() {
        nameTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        statusTextView.setTextColor(Theme.getColor(statusOnline ? Theme.key_windowBackgroundWhiteBlueText : Theme.key_windowBackgroundWhiteGrayText));
        dividerPaint.setColor(Theme.getColor(Theme.key_divider));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!needDivider) {
            return;
        }
        int left = LocaleController.isRTL ? 0 : dp(72);
        int right = getMeasuredWidth() - (LocaleController.isRTL ? dp(72) : 0);
        canvas.drawLine(left, getMeasuredHeight() - 1, right, getMeasuredHeight() - 1, dividerPaint);
    }
}
