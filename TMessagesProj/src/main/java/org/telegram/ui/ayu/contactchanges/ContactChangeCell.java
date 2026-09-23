package org.telegram.ui.ayu.contactchanges;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.ayu.contactchanges.ContactChange;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.ForegroundColorSpanThemable;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;

/**
 * OcoderX "Contacts Changes": one row of the history — contact avatar, contact name, the change
 * itself ("Name changed: Old → New") and the time. Photo changes additionally show the previous
 * avatar we saved, or a placeholder when nothing was cached at the time.
 */
public class ContactChangeCell extends FrameLayout {

    private static final int MAX_VALUE_LENGTH = 60;
    public static final int CELL_HEIGHT = 76;

    private final int currentAccount;

    private final BackupImageView avatarImageView;
    private final AvatarDrawable avatarDrawable = new AvatarDrawable();
    private final TextView nameTextView;
    private final TextView timeTextView;
    private final TextView detailTextView;

    private final FrameLayout photoContainer;
    private final ImageView photoPlaceholderView;
    private final BackupImageView photoImageView;
    private final GradientDrawable photoPlaceholderBackground = new GradientDrawable();

    private final Paint dividerPaint = new Paint();

    public boolean needDivider = true;
    private ContactChange change;

    public ContactChangeCell(Context context, int currentAccount) {
        super(context);
        this.currentAccount = currentAccount;
        setWillNotDraw(false);

        final boolean rtl = LocaleController.isRTL;
        final int gravity = Gravity.TOP | (rtl ? Gravity.RIGHT : Gravity.LEFT);

        avatarImageView = new BackupImageView(context);
        avatarImageView.setRoundRadius(dp(24));
        addView(avatarImageView, LayoutHelper.createFrame(48, 48, gravity, rtl ? 0 : 12, 14, rtl ? 12 : 0, 0));

        LinearLayout topRow = new LinearLayout(context);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        addView(topRow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 22, gravity, rtl ? 14 : 74, 12, rtl ? 74 : 14, 0));

        nameTextView = new TextView(context);
        nameTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        nameTextView.setTypeface(AndroidUtilities.bold());
        nameTextView.setSingleLine(true);
        nameTextView.setEllipsize(TextUtils.TruncateAt.END);
        nameTextView.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
        topRow.addView(nameTextView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        timeTextView = new TextView(context);
        timeTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        timeTextView.setSingleLine(true);
        topRow.addView(timeTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 6, 0, 0, 0));

        detailTextView = new TextView(context);
        detailTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        detailTextView.setMaxLines(2);
        detailTextView.setEllipsize(TextUtils.TruncateAt.END);
        detailTextView.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
        addView(detailTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, gravity, rtl ? 58 : 74, 34, rtl ? 74 : 58, 0));

        photoContainer = new FrameLayout(context);
        photoContainer.setVisibility(GONE);
        addView(photoContainer, LayoutHelper.createFrame(40, 40, Gravity.CENTER_VERTICAL | (rtl ? Gravity.LEFT : Gravity.RIGHT), rtl ? 12 : 0, 0, rtl ? 0 : 12, 0));

        photoPlaceholderBackground.setShape(GradientDrawable.OVAL);

        photoPlaceholderView = new ImageView(context);
        photoPlaceholderView.setScaleType(ImageView.ScaleType.CENTER);
        photoPlaceholderView.setImageResource(R.drawable.msg_photos);
        photoPlaceholderView.setBackground(photoPlaceholderBackground);
        photoContainer.addView(photoPlaceholderView, LayoutHelper.createFrame(40, 40, Gravity.CENTER));

        photoImageView = new BackupImageView(context);
        photoImageView.setRoundRadius(dp(20));
        photoContainer.addView(photoImageView, LayoutHelper.createFrame(40, 40, Gravity.CENTER));

        updateColors();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(CELL_HEIGHT), MeasureSpec.EXACTLY));
    }

    public ContactChange getChange() {
        return change;
    }

    public void setChange(ContactChange change, boolean divider) {
        this.change = change;
        this.needDivider = divider;
        if (change == null) {
            return;
        }

        TLRPC.User user = null;
        try {
            user = MessagesController.getInstance(currentAccount).getUser(change.userId);
        } catch (Throwable ignore) {
        }
        if (user != null) {
            avatarDrawable.setInfo(currentAccount, user);
        } else {
            // unknown / not cached user: keep the row from inheriting the recycled avatar
            avatarDrawable.setInfo(change.userId, null, null);
        }
        avatarImageView.setForUserOrChat(user, avatarDrawable);

        String name = user != null ? UserObject.getUserName(user) : null;
        if (TextUtils.isEmpty(name)) {
            name = String.valueOf(change.userId);
        }
        nameTextView.setText(name);
        timeTextView.setText(LocaleController.getInstance().getFormatterDay().format(change.timestamp));
        detailTextView.setText(buildDetail(change));

        if (change.isPhoto()) {
            photoContainer.setVisibility(VISIBLE);
            File file = null;
            if (!TextUtils.isEmpty(change.extra)) {
                file = new File(change.extra);
            }
            if (file != null && file.exists() && file.length() > 0) {
                photoPlaceholderView.setVisibility(GONE);
                photoImageView.setVisibility(VISIBLE);
                photoImageView.setImage(ImageLocation.getForPath(file.getAbsolutePath()), "40_40", null, null, null);
            } else {
                photoImageView.setVisibility(GONE);
                photoImageView.setImageDrawable(null);
                photoPlaceholderView.setVisibility(VISIBLE);
            }
        } else {
            photoContainer.setVisibility(GONE);
            photoImageView.setImageDrawable(null);
        }

        updateColors();
    }

    public void updateColors() {
        nameTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        timeTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        detailTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        dividerPaint.setColor(Theme.getColor(Theme.key_divider));
        photoPlaceholderBackground.setColor(Theme.getColor(Theme.key_windowBackgroundGray));
        photoPlaceholderView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), PorterDuff.Mode.SRC_IN));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!needDivider) {
            return;
        }
        int left = LocaleController.isRTL ? 0 : dp(74);
        int right = getMeasuredWidth() - (LocaleController.isRTL ? dp(74) : 0);
        canvas.drawLine(left, getMeasuredHeight() - 1, right, getMeasuredHeight() - 1, dividerPaint);
    }

    // ------------------------------------------------------------------ text

    public static String getTypeTitle(String type) {
        if (ContactChange.TYPE_USERNAME.equals(type)) {
            return getString(R.string.OxContactChangesUsername);
        }
        if (ContactChange.TYPE_PHONE.equals(type)) {
            return getString(R.string.OxContactChangesPhone);
        }
        if (ContactChange.TYPE_PHOTO.equals(type)) {
            return getString(R.string.OxContactChangesPhoto);
        }
        if (ContactChange.TYPE_BIO.equals(type)) {
            return getString(R.string.OxContactChangesBio);
        }
        return getString(R.string.OxContactChangesName);
    }

    public static CharSequence buildDetail(ContactChange change) {
        String title = getTypeTitle(change.type);
        // the phone number is never shown, and a photo id would mean nothing to the user
        if (ContactChange.TYPE_PHONE.equals(change.type) || ContactChange.TYPE_PHOTO.equals(change.type)) {
            return title;
        }
        boolean username = ContactChange.TYPE_USERNAME.equals(change.type);
        String oldValue = shorten(change.oldValue, username);
        String newValue = shorten(change.newValue, username);
        SpannableStringBuilder builder = new SpannableStringBuilder();
        builder.append(title).append(": ");
        builder.setSpan(new ForegroundColorSpanThemable(Theme.key_windowBackgroundWhiteGrayText), 0, builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        int start = builder.length();
        builder.append(oldValue).append(" → ").append(newValue);
        builder.setSpan(new ForegroundColorSpanThemable(Theme.key_windowBackgroundWhiteBlackText), start, builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return builder;
    }

    private static String shorten(String value, boolean username) {
        if (TextUtils.isEmpty(value)) {
            return "—";
        }
        String result = value.replace('\n', ' ').trim();
        if (result.length() == 0) {
            return "—";
        }
        if (result.length() > MAX_VALUE_LENGTH) {
            result = result.substring(0, MAX_VALUE_LENGTH) + "…";
        }
        return username ? "@" + result : result;
    }
}
