package org.telegram.ui.ayu.upload;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.ayu.upload.UploadQueueItem;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LineProgressView;

/**
 * One row of {@link UploadQueueActivity}: thumbnail, file name, state / size / speed and a
 * pause-resume button, with a thin progress line at the bottom.
 */
public class UploadItemCell extends FrameLayout {

    public interface Delegate {
        void onPauseResumeClick(UploadQueueItem item);
    }

    private final BackupImageView imageView;
    private final TextView nameTextView;
    private final TextView statusTextView;
    private final ImageView actionButton;
    private final LineProgressView progressView;

    private UploadQueueItem currentItem;
    private boolean needDivider;
    private Delegate delegate;

    public UploadItemCell(Context context) {
        super(context);

        setWillNotDraw(false);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        imageView = new BackupImageView(context);
        imageView.setRoundRadius(dp(8));
        addView(imageView, LayoutHelper.createFrame(42, 42, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, 14, 10, 14, 0));

        nameTextView = new TextView(context);
        nameTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        nameTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        nameTextView.setLines(1);
        nameTextView.setMaxLines(1);
        nameTextView.setSingleLine(true);
        nameTextView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        nameTextView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        addView(nameTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, LocaleController.isRTL ? 56 : 70, 8, LocaleController.isRTL ? 70 : 56, 0));

        statusTextView = new TextView(context);
        statusTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        statusTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        statusTextView.setLines(1);
        statusTextView.setMaxLines(1);
        statusTextView.setSingleLine(true);
        statusTextView.setEllipsize(TextUtils.TruncateAt.END);
        statusTextView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        addView(statusTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, LocaleController.isRTL ? 56 : 70, 30, LocaleController.isRTL ? 70 : 56, 0));

        progressView = new LineProgressView(context);
        progressView.setProgressColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        progressView.setBackColor(Theme.getColor(Theme.key_divider));
        addView(progressView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 2, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, LocaleController.isRTL ? 56 : 70, 52, LocaleController.isRTL ? 70 : 56, 0));

        actionButton = new ImageView(context);
        actionButton.setScaleType(ImageView.ScaleType.CENTER);
        actionButton.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1));
        actionButton.setOnClickListener(v -> {
            if (delegate != null && currentItem != null) {
                delegate.onPauseResumeClick(currentItem);
            }
        });
        addView(actionButton, LayoutHelper.createFrame(40, 40, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP, 8, 11, 8, 0));
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    public UploadQueueItem getItem() {
        return currentItem;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(64) + (needDivider ? 1 : 0), MeasureSpec.EXACTLY));
    }

    public void setItem(UploadQueueItem item, boolean divider) {
        currentItem = item;
        needDivider = divider;
        if (item == null) {
            return;
        }

        nameTextView.setText(TextUtils.isEmpty(item.name) ? item.path : item.name);
        statusTextView.setText(buildStatus(item));

        if (item.type == ConnectionsManager.FileTypePhoto || item.type == ConnectionsManager.FileTypeVideo) {
            imageView.setImage(ImageLocation.getForPath(item.path), "42_42", null, null, null);
        } else {
            imageView.setImageDrawable(Theme.createRoundRectDrawable(dp(8), Theme.getColor(Theme.key_divider)));
        }

        progressView.setProgress(item.getProgress(), true);
        progressView.setVisibility(item.state == UploadQueueItem.STATE_ERROR ? INVISIBLE : VISIBLE);

        if (item.state == UploadQueueItem.STATE_PAUSED || item.state == UploadQueueItem.STATE_ERROR) {
            actionButton.setImageResource(R.drawable.msg_retry);
            actionButton.setContentDescription(LocaleController.getString(R.string.AyuUploadResume));
        } else {
            actionButton.setImageResource(R.drawable.ic_action_pause);
            actionButton.setContentDescription(LocaleController.getString(R.string.AyuUploadPause));
        }
        actionButton.setColorFilter(new android.graphics.PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2), android.graphics.PorterDuff.Mode.MULTIPLY));

        requestLayout();
    }

    private CharSequence buildStatus(UploadQueueItem item) {
        final String size = AndroidUtilities.formatFileSize(Math.max(item.totalBytes, 0));
        switch (item.state) {
            case UploadQueueItem.STATE_PAUSED:
                if (item.pausedByNetwork) {
                    return LocaleController.getString(R.string.AyuUploadStateWaitingNetwork) + " · " + size;
                }
                return LocaleController.getString(R.string.AyuUploadStatePaused) + " · " + size;
            case UploadQueueItem.STATE_ERROR:
                return LocaleController.getString(R.string.AyuUploadStateFailed) + " · " + size;
            case UploadQueueItem.STATE_DONE:
                return LocaleController.getString(R.string.AyuUploadStateDone) + " · " + size;
            case UploadQueueItem.STATE_ACTIVE: {
                final String done = AndroidUtilities.formatFileSize(Math.max(item.uploadedBytes, 0));
                if (item.speed > 0) {
                    return done + " / " + size + " · " + LocaleController.formatString(R.string.AyuUploadSpeed, AndroidUtilities.formatFileSize(item.speed));
                }
                return done + " / " + size;
            }
            case UploadQueueItem.STATE_WAITING:
            default:
                return LocaleController.getString(R.string.AyuUploadStateQueued) + " · " + size;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (needDivider) {
            canvas.drawLine(
                    LocaleController.isRTL ? 0 : dp(70),
                    getMeasuredHeight() - 1,
                    getMeasuredWidth() - (LocaleController.isRTL ? dp(70) : 0),
                    getMeasuredHeight() - 1,
                    Theme.dividerPaint);
        }
    }

    @Override
    public void invalidate() {
        super.invalidate();
    }

    public View getActionButton() {
        return actionButton;
    }
}
