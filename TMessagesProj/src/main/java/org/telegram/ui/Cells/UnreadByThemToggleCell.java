package org.telegram.ui.Cells;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

/**
 * Small pill button shown at the top of a folder tab. Toggles the
 * "unread by them" filter: only chats whose last message is mine and
 * has not been read by the other side.
 * Draws everything itself (no child views, no allocations in draw).
 */
public class UnreadByThemToggleCell extends View {

    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pillRect = new RectF();
    private String text = "";
    private float textWidth;
    private boolean active;
    private boolean pressed;
    private Runnable onToggle;

    public UnreadByThemToggleCell(Context context) {
        super(context);
        textPaint.setTextSize(AndroidUtilities.dp(13));
        textPaint.setTypeface(AndroidUtilities.bold());
        setText(LocaleController.getString(R.string.AyuUnreadByThem));
    }

    private void setText(String value) {
        text = value == null ? "" : value;
        textWidth = textPaint.measureText(text);
    }

    public void setActive(boolean value) {
        if (active != value) {
            active = value;
            invalidate();
        }
    }

    public void setOnToggle(Runnable runnable) {
        onToggle = runnable;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), AndroidUtilities.dp(40));
    }

    private void updateRect() {
        int maxWidth = getMeasuredWidth() - AndroidUtilities.dp(32);
        float w = Math.min(textWidth + AndroidUtilities.dp(24), maxWidth);
        float h = AndroidUtilities.dp(28);
        float left = AndroidUtilities.dp(16);
        float top = (getMeasuredHeight() - h) / 2f;
        pillRect.set(left, top, left + w, top + h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        updateRect();
        int bg;
        int fg;
        if (active) {
            bg = Theme.getColor(Theme.key_featuredStickers_addButton);
            fg = Theme.getColor(Theme.key_featuredStickers_buttonText);
        } else {
            bg = Theme.getColor(Theme.key_graySection);
            fg = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        }
        if (pressed) {
            bg = Theme.blendOver(bg, 0x1A000000);
        }
        pillPaint.setColor(bg);
        float r = pillRect.height() / 2f;
        canvas.drawRoundRect(pillRect, r, r, pillPaint);
        textPaint.setColor(fg);
        CharSequence drawText = text;
        float avail = pillRect.width() - AndroidUtilities.dp(24);
        if (textWidth > avail) {
            drawText = TextUtils.ellipsize(text, textPaint, avail, TextUtils.TruncateAt.END);
        }
        float tx = pillRect.left + AndroidUtilities.dp(12);
        float ty = pillRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f;
        canvas.drawText(drawText, 0, drawText.length(), tx, ty, textPaint);
    }

    private boolean inPill(float x, float y) {
        return x >= pillRect.left - AndroidUtilities.dp(8) && x <= pillRect.right + AndroidUtilities.dp(8) && y >= 0 && y <= getMeasuredHeight();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (inPill(event.getX(), event.getY())) {
                    pressed = true;
                    invalidate();
                    return true;
                }
                return false;
            case MotionEvent.ACTION_MOVE:
                if (pressed && !inPill(event.getX(), event.getY())) {
                    pressed = false;
                    invalidate();
                }
                return pressed;
            case MotionEvent.ACTION_UP:
                if (pressed) {
                    pressed = false;
                    invalidate();
                    if (onToggle != null) {
                        onToggle.run();
                    }
                    return true;
                }
                return false;
            case MotionEvent.ACTION_CANCEL:
                if (pressed) {
                    pressed = false;
                    invalidate();
                }
                return false;
        }
        return super.onTouchEvent(event);
    }
}
