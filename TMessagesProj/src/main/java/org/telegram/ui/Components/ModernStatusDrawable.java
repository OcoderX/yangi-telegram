package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.SharedConfig;

/**
 * ModernStatusDrawable handles drawing modern, vector-crisp message ticks (sent, read, pending)
 * with customizable styles (Minimalist, iOS glow, Classic).
 */
public class ModernStatusDrawable extends Drawable {

    public static final int TYPE_SENT = 0; // Single tick
    public static final int TYPE_READ = 1; // Double tick
    public static final int TYPE_PENDING = 2; // Animated progress / clock

    private final int type;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path1 = new Path();
    private final Path path2 = new Path();
    private final RectF arcRect = new RectF();

    private int color = Color.WHITE;
    private int alpha = 255;
    private int colorAlpha = 255;
    private long startTime;
    private int intrinsicWidth;
    private int intrinsicHeight;

    public ModernStatusDrawable(int type) {
        this.type = type;
        this.startTime = System.currentTimeMillis();

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(AndroidUtilities.dp(1.6f));

        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setStrokeJoin(Paint.Join.ROUND);
        glowPaint.setStrokeWidth(AndroidUtilities.dp(2.4f));

        if (type == TYPE_SENT) {
            intrinsicWidth = AndroidUtilities.dp(13);
            intrinsicHeight = AndroidUtilities.dp(11);
        } else if (type == TYPE_READ) {
            intrinsicWidth = AndroidUtilities.dp(17);
            intrinsicHeight = AndroidUtilities.dp(11);
        } else {
            intrinsicWidth = AndroidUtilities.dp(12);
            intrinsicHeight = AndroidUtilities.dp(12);
        }
    }

    @Override
    public int getIntrinsicWidth() {
        return intrinsicWidth;
    }

    @Override
    public int getIntrinsicHeight() {
        return intrinsicHeight;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        int cx = bounds.centerX();
        int cy = bounds.centerY();

        if (type == TYPE_PENDING) {
            // Modern smooth circular spinner / clock
            long currentTime = System.currentTimeMillis();
            float progress = ((currentTime - startTime) % 1200) / 1200f;
            float radius = (Math.min(bounds.width(), bounds.height()) / 2f) - AndroidUtilities.dp(1.2f);

            arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
            paint.setStrokeWidth(AndroidUtilities.dp(1.4f));
            canvas.drawArc(arcRect, progress * 360f, 270f, false, paint);
            invalidateSelf();
            return;
        }

        // Modern checkmark coordinates relative to bounds
        int style = SharedConfig.messageStatusStyle;
        boolean glow = (style == 2) && (type == TYPE_READ);

        if (glow) {
            glowPaint.setColor(ColorUtils.setAlphaComponent(color, 60));
        }

        float stroke = AndroidUtilities.dp(1.6f);
        paint.setStrokeWidth(stroke);

        if (type == TYPE_SENT) {
            // Single Check
            float startX = bounds.left + AndroidUtilities.dp(2f);
            float startY = cy - AndroidUtilities.dp(0.5f);
            float midX = startX + AndroidUtilities.dp(3.8f);
            float midY = startY + AndroidUtilities.dp(4.2f);
            float endX = bounds.right - AndroidUtilities.dp(2f);
            float endY = startY - AndroidUtilities.dp(4.2f);

            path1.reset();
            path1.moveTo(startX, startY);
            path1.lineTo(midX, midY);
            path1.lineTo(endX, endY);

            if (glow) {
                canvas.drawPath(path1, glowPaint);
            }
            canvas.drawPath(path1, paint);
        } else if (type == TYPE_READ) {
            // Double Check (First check on the left, second on the right)
            float offset = AndroidUtilities.dp(4.5f);

            // Check 1 (Left / background check)
            float startX1 = bounds.left + AndroidUtilities.dp(1.5f);
            float startY1 = cy - AndroidUtilities.dp(0.5f);
            float midX1 = startX1 + AndroidUtilities.dp(3.8f);
            float midY1 = startY1 + AndroidUtilities.dp(4.2f);
            float endX1 = startX1 + AndroidUtilities.dp(8.5f);
            float endY1 = startY1 - AndroidUtilities.dp(4.2f);

            path1.reset();
            path1.moveTo(startX1, startY1);
            path1.lineTo(midX1, midY1);
            path1.lineTo(endX1, endY1);

            // Check 2 (Right / foreground check)
            float startX2 = startX1 + offset;
            float startY2 = startY1;
            float midX2 = midX1 + offset;
            float midY2 = midY1;
            float endX2 = bounds.right - AndroidUtilities.dp(1.5f);
            float endY2 = endY1;

            path2.reset();
            path2.moveTo(startX2, startY2);
            path2.lineTo(midX2, midY2);
            path2.lineTo(endX2, endY2);

            if (glow) {
                canvas.drawPath(path1, glowPaint);
                canvas.drawPath(path2, glowPaint);
            }
            canvas.drawPath(path1, paint);
            canvas.drawPath(path2, paint);
        }
    }

    public void setColor(int color) {
        if (this.color != color) {
            this.color = color;
            this.colorAlpha = Color.alpha(color);
            updatePaintAlpha();
            invalidateSelf();
        }
    }

    private void updatePaintAlpha() {
        int finalAlpha = (int) (alpha * (colorAlpha / 255f));
        paint.setColor(ColorUtils.setAlphaComponent(color, finalAlpha));
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        updatePaintAlpha();
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
