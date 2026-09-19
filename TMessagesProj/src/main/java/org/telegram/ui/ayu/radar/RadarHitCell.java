package org.telegram.ui.ayu.radar;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.ayu.radar.RadarHit;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.ForegroundColorSpanThemable;
import org.telegram.ui.Components.LayoutHelper;

/**
 * AyuGram Mention Radar: one row of the radar feed — chat avatar, chat name, kind chip, time and the
 * message snippet with the matched fragment highlighted.
 * <p>
 * Hand-rolled instead of reusing {@code DialogCell} because the feed needs a per-row kind chip and an
 * unread state that {@code DialogCell} cannot express (its {@code setCustomMessage} also clears the
 * time column).
 */
public class RadarHitCell extends FrameLayout {

    private final int currentAccount;

    private final BackupImageView avatarImageView;
    private final AvatarDrawable avatarDrawable = new AvatarDrawable();
    private final TextView nameTextView;
    private final TextView chipTextView;
    private final TextView timeTextView;
    private final TextView messageTextView;

    private final Paint dividerPaint = new Paint();
    private final GradientDrawable chipBackground = new GradientDrawable();

    public boolean needDivider = true;
    private RadarHit hit;

    public RadarHitCell(Context context, int currentAccount) {
        super(context);
        this.currentAccount = currentAccount;
        setWillNotDraw(false);

        final boolean rtl = LocaleController.isRTL;
        final int gravity = Gravity.TOP | (rtl ? Gravity.RIGHT : Gravity.LEFT);

        avatarImageView = new BackupImageView(context);
        avatarImageView.setRoundRadius(dp(26));
        addView(avatarImageView, LayoutHelper.createFrame(52, 52, gravity, rtl ? 0 : 12, 12, rtl ? 12 : 0, 0));

        LinearLayout topRow = new LinearLayout(context);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        addView(topRow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 22, gravity, rtl ? 14 : 76, 11, rtl ? 76 : 14, 0));

        nameTextView = new TextView(context);
        nameTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        nameTextView.setTypeface(AndroidUtilities.bold());
        nameTextView.setSingleLine(true);
        nameTextView.setEllipsize(TextUtils.TruncateAt.END);
        nameTextView.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
        topRow.addView(nameTextView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        chipBackground.setShape(GradientDrawable.RECTANGLE);
        chipBackground.setCornerRadius(dp(8));

        chipTextView = new TextView(context);
        chipTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        chipTextView.setTypeface(AndroidUtilities.bold());
        chipTextView.setSingleLine(true);
        chipTextView.setPadding(dp(6), dp(1), dp(6), dp(2));
        chipTextView.setBackground(chipBackground);
        topRow.addView(chipTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 6, 0, 0, 0));

        timeTextView = new TextView(context);
        timeTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        timeTextView.setSingleLine(true);
        topRow.addView(timeTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 6, 0, 0, 0));

        messageTextView = new TextView(context);
        messageTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        messageTextView.setMaxLines(2);
        messageTextView.setEllipsize(TextUtils.TruncateAt.END);
        messageTextView.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
        addView(messageTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, gravity, rtl ? 14 : 76, 34, rtl ? 76 : 14, 0));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(78), MeasureSpec.EXACTLY));
    }

    public RadarHit getHit() {
        return hit;
    }

    public void setHit(RadarHit hit, boolean divider) {
        this.hit = hit;
        this.needDivider = divider;
        if (hit == null) {
            return;
        }

        TLObject object = null;
        try {
            if (hit.dialogId < 0) {
                object = MessagesController.getInstance(currentAccount).getChat(-hit.dialogId);
            } else {
                object = MessagesController.getInstance(currentAccount).getUser(hit.dialogId);
            }
        } catch (Throwable ignore) {
        }
        avatarDrawable.setInfo(currentAccount, object);
        avatarImageView.setForUserOrChat(object, avatarDrawable);

        String chatName = DialogObject.getName(currentAccount, hit.dialogId);
        if (TextUtils.isEmpty(chatName)) {
            chatName = String.valueOf(hit.dialogId);
        }
        nameTextView.setText(chatName);
        timeTextView.setText(LocaleController.stringForMessageListDate(hit.date));
        chipTextView.setText(getKindName(hit.kind));

        CharSequence snippet = buildSnippet(hit);
        String senderName = getSenderName(hit);
        if (!TextUtils.isEmpty(senderName)) {
            SpannableStringBuilder builder = new SpannableStringBuilder();
            builder.append(senderName).append(": ");
            builder.setSpan(new ForegroundColorSpanThemable(Theme.key_windowBackgroundWhiteBlackText), 0, senderName.length() + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            builder.append(snippet);
            messageTextView.setText(builder);
        } else {
            messageTextView.setText(snippet);
        }

        updateColors();
    }

    private String getSenderName(RadarHit hit) {
        if (hit.senderId == 0) {
            return null;
        }
        try {
            if (hit.senderId > 0) {
                TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(hit.senderId);
                if (user != null) {
                    return org.telegram.messenger.UserObject.getFirstName(user);
                }
            } else {
                TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-hit.senderId);
                if (chat != null) {
                    return chat.title;
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    private CharSequence buildSnippet(RadarHit hit) {
        String text = hit.snippet == null ? "" : hit.snippet;
        if (TextUtils.isEmpty(text)) {
            return LocaleController.getString(R.string.RadarNoText);
        }
        if (hit.matchStart >= 0 && hit.matchEnd > hit.matchStart && hit.matchEnd <= text.length()) {
            SpannableStringBuilder builder = new SpannableStringBuilder(text);
            builder.setSpan(new ForegroundColorSpanThemable(Theme.key_windowBackgroundWhiteBlueText), hit.matchStart, hit.matchEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            return builder;
        }
        return text;
    }

    public static String getKindName(int kind) {
        switch (kind) {
            case RadarHit.KIND_REPLY:
                return LocaleController.getString(R.string.RadarKindReply);
            case RadarHit.KIND_NAME:
                return LocaleController.getString(R.string.RadarKindName);
            case RadarHit.KIND_KEYWORD:
                return LocaleController.getString(R.string.RadarKindKeyword);
            case RadarHit.KIND_MENTION:
            default:
                return LocaleController.getString(R.string.RadarKindMention);
        }
    }

    public static int getKindColorKey(int kind) {
        switch (kind) {
            case RadarHit.KIND_REPLY:
                return Theme.key_avatar_backgroundGreen;
            case RadarHit.KIND_NAME:
                return Theme.key_avatar_backgroundViolet;
            case RadarHit.KIND_KEYWORD:
                return Theme.key_avatar_backgroundOrange;
            case RadarHit.KIND_MENTION:
            default:
                return Theme.key_avatar_backgroundBlue;
        }
    }

    public void updateColors() {
        nameTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        timeTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
        messageTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        dividerPaint.setColor(Theme.getColor(Theme.key_divider));

        int accent = Theme.getColor(hit == null ? Theme.key_avatar_backgroundBlue : getKindColorKey(hit.kind));
        chipTextView.setTextColor(accent);
        chipBackground.setColor(Theme.multAlpha(accent, 0.14f));

        if (hit != null && !hit.read) {
            setBackgroundColor(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText), 0.07f));
        } else {
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!needDivider) {
            return;
        }
        int left = LocaleController.isRTL ? 0 : dp(76);
        int right = getMeasuredWidth() - (LocaleController.isRTL ? dp(76) : 0);
        canvas.drawLine(left, getMeasuredHeight() - 1, right, getMeasuredHeight() - 1, dividerPaint);
    }

    /** the accent used for the "unread" pill in the tab strip badge, kept here for reuse */
    public static int getUnreadColor() {
        return Theme.getColor(Theme.key_chats_actionBackground);
    }

    public View getAvatarView() {
        return avatarImageView;
    }
}
