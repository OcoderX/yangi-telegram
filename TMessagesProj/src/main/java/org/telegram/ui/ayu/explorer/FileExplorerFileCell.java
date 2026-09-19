package org.telegram.ui.ayu.explorer;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.ayu.explorer.FileEntry;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.LayoutHelper;

import java.util.Locale;

/**
 * AyuGram File Explorer: one file row (thumbnail or extension placeholder, name, size / date /
 * chat, downloaded badge and the multi-selection checkbox).
 */
public class FileExplorerFileCell extends FrameLayout {

    private final Theme.ResourcesProvider resourcesProvider;

    private final ImageView placeholderImageView;
    private final TextView extTextView;
    private final BackupImageView thumbImageView;
    private final TextView nameTextView;
    private final TextView subtitleTextView;
    private final ImageView statusImageView;
    private final CheckBox2 checkBox;

    private FileEntry entry;
    private boolean needDivider;

    public FileExplorerFileCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;

        placeholderImageView = new ImageView(context);
        addView(placeholderImageView, LayoutHelper.createFrame(40, 40, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, LocaleController.isRTL ? 0 : 12, 8, LocaleController.isRTL ? 12 : 0, 0));

        extTextView = new TextView(context);
        extTextView.setTextColor(Theme.getColor(Theme.key_files_iconText, resourcesProvider));
        extTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        extTextView.setTypeface(AndroidUtilities.bold());
        extTextView.setLines(1);
        extTextView.setMaxLines(1);
        extTextView.setSingleLine(true);
        extTextView.setGravity(Gravity.CENTER);
        addView(extTextView, LayoutHelper.createFrame(32, 20, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, LocaleController.isRTL ? 0 : 16, 22, LocaleController.isRTL ? 16 : 0, 0));

        thumbImageView = new BackupImageView(context);
        thumbImageView.setRoundRadius(dp(4));
        addView(thumbImageView, LayoutHelper.createFrame(40, 40, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, LocaleController.isRTL ? 0 : 12, 8, LocaleController.isRTL ? 12 : 0, 0));

        nameTextView = new TextView(context);
        nameTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        nameTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        nameTextView.setTypeface(AndroidUtilities.bold());
        nameTextView.setLines(1);
        nameTextView.setMaxLines(1);
        nameTextView.setSingleLine(true);
        nameTextView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        nameTextView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        addView(nameTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, LocaleController.isRTL ? 40 : 72, 6, LocaleController.isRTL ? 72 : 40, 0));

        subtitleTextView = new TextView(context);
        subtitleTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3, resourcesProvider));
        subtitleTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitleTextView.setLines(1);
        subtitleTextView.setMaxLines(1);
        subtitleTextView.setSingleLine(true);
        subtitleTextView.setEllipsize(TextUtils.TruncateAt.END);
        subtitleTextView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        addView(subtitleTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, LocaleController.isRTL ? 40 : 72, 29, LocaleController.isRTL ? 72 : 40, 0));

        statusImageView = new ImageView(context);
        statusImageView.setScaleType(ImageView.ScaleType.CENTER);
        addView(statusImageView, LayoutHelper.createFrame(24, 24, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP, LocaleController.isRTL ? 12 : 0, 16, LocaleController.isRTL ? 0 : 12, 0));

        checkBox = new CheckBox2(context, 21, resourcesProvider);
        checkBox.setVisibility(INVISIBLE);
        checkBox.setColor(-1, Theme.key_windowBackgroundWhite, Theme.key_checkboxCheck);
        checkBox.setDrawUnchecked(false);
        checkBox.setDrawBackgroundAsArc(2);
        addView(checkBox, LayoutHelper.createFrame(24, 24, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, LocaleController.isRTL ? 0 : 33, 28, LocaleController.isRTL ? 33 : 0, 0));
    }

    public FileEntry getEntry() {
        return entry;
    }

    public void setChecked(boolean checked, boolean animated) {
        if (checkBox.getVisibility() != VISIBLE) {
            checkBox.setVisibility(VISIBLE);
        }
        checkBox.setChecked(checked, animated);
    }

    public void clearChecked() {
        checkBox.setChecked(false, false);
        checkBox.setVisibility(INVISIBLE);
    }

    /**
     * @param showChat when true the chat title is appended to the subtitle (used by every screen
     *                 except the per-chat folder, where it would be redundant)
     */
    public void setEntry(int currentAccount, FileEntry entry, boolean showChat, boolean divider) {
        this.entry = entry;
        this.needDivider = divider;
        setWillNotDraw(!divider);
        if (entry == null) {
            nameTextView.setText("");
            subtitleTextView.setText("");
            thumbImageView.setVisibility(INVISIBLE);
            thumbImageView.setImageDrawable(null);
            placeholderImageView.setVisibility(VISIBLE);
            extTextView.setText("");
            statusImageView.setVisibility(GONE);
            return;
        }

        nameTextView.setText(entry.name);

        final StringBuilder subtitle = new StringBuilder();
        subtitle.append(AndroidUtilities.formatFileSize(entry.sizeForSorting()));
        if (entry.date > 0) {
            subtitle.append(", ").append(LocaleController.formatDateChat(entry.date, true));
        }
        if (showChat && entry.dialogId != 0) {
            final String chat = FileExplorerUtils.getDialogTitle(currentAccount, entry.dialogId);
            if (!TextUtils.isEmpty(chat)) {
                subtitle.append(" • ").append(chat);
            }
        }
        subtitleTextView.setText(subtitle.toString());

        placeholderImageView.setVisibility(VISIBLE);
        placeholderImageView.setImageResource(AndroidUtilities.getThumbForNameOrMime(entry.name, entry.mimeType, false));
        extTextView.setVisibility(VISIBLE);
        extTextView.setText(TextUtils.isEmpty(entry.extension) ? "" : entry.extension.toUpperCase(Locale.US));

        ImageLocation thumbLocation = null;
        if (entry.thumbSize != null && !(entry.thumbSize instanceof TLRPC.TL_photoSizeEmpty)) {
            if (entry.document != null) {
                thumbLocation = ImageLocation.getForDocument(entry.thumbSize, entry.document);
            } else if (entry.photo != null) {
                thumbLocation = ImageLocation.getForPhoto(entry.thumbSize, entry.photo);
            }
        }
        if (thumbLocation != null) {
            thumbImageView.setVisibility(VISIBLE);
            thumbImageView.setImage(thumbLocation, "40_40", (Drawable) null, entry.document != null ? entry.document : entry.photo);
            extTextView.setVisibility(INVISIBLE);
            placeholderImageView.setVisibility(INVISIBLE);
        } else {
            thumbImageView.setVisibility(INVISIBLE);
            thumbImageView.setImageDrawable(null);
        }

        statusImageView.setVisibility(VISIBLE);
        if (entry.downloaded) {
            statusImageView.setImageResource(R.drawable.msg_check_s);
            statusImageView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGreenText2, resourcesProvider), PorterDuff.Mode.SRC_IN));
        } else {
            statusImageView.setImageResource(R.drawable.msg_download);
            statusImageView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3, resourcesProvider), PorterDuff.Mode.SRC_IN));
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(56) + (needDivider ? 1 : 0), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (needDivider) {
            canvas.drawLine(
                    LocaleController.isRTL ? 0 : dp(72),
                    getMeasuredHeight() - 1,
                    getMeasuredWidth() - (LocaleController.isRTL ? dp(72) : 0),
                    getMeasuredHeight() - 1,
                    Theme.dividerPaint);
        }
    }
}
