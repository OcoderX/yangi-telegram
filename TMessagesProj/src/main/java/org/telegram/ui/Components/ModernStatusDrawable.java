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
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.SharedConfig;

/**
 * ModernStatusDrawable handles drawing modern, vector-crisp message ticks (sent, read, pending)
 * with customizable styles (Minimalist, iOS glow, Classic).
 * <p>
 * perf: the tick paths are built once, relative to the drawable's own size, and the canvas is
 * translated per draw; nothing is allocated or re-tessellated while a chat scrolls. The pending
 * spinner repaints on a fixed ~30 fps schedule instead of invalidating itself every frame.
 */
public class ModernStatusDrawable extends Drawable {

    public static final int TYPE_SENT = 0; // Single tick
    public static final int TYPE_READ = 1; // Double tick
    public static final int TYPE_PENDING = 2; // Animated progress / clock

    private static final long PENDING_FRAME_MS = 32;

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

    /** size the cached paths were built for (-1 = not built yet) */
    private int pathsWidth = -1;
    private int pathsHeight = -1;
    private int glowColor;
    private int glowColorSource = 1; // any value != color so the first draw sets it

    private final Runnable pendingFrame = this::invalidateSelf;

    public ModernStatusDrawable(int type) {
        this.type = type;
        this.startTime = System.currentTimeMillis();

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(AndroidUtilities.dp(type == TYPE_PENDING ? 1.4f : 1.6f));

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

    /** builds the tick paths for the given size, with (0,0) at the top-left of the bounds */
    private void ensurePaths(int width, int height) {
        if (pathsWidth == width && pathsHeight == height) {
            return;
        }
        pathsWidth = width;
        pathsHeight = height;
        final float cy = height / 2f;
        if (type == TYPE_SENT) {
            // Single Check
            float startX = AndroidUtilities.dp(2f);
            float startY = cy - AndroidUtilities.dp(0.5f);
            float midX = startX + AndroidUtilities.dp(3.8f);
            float midY = startY + AndroidUtilities.dp(4.2f);
            float endX = width - AndroidUtilities.dp(2f);
            float endY = startY - AndroidUtilities.dp(4.2f);

            path1.rewind();
            path1.moveTo(startX, startY);
            path1.lineTo(midX, midY);
            path1.lineTo(endX, endY);
        } else if (type == TYPE_READ) {
            // Double Check (First check on the left, second on the right)
            float offset = AndroidUtilities.dp(4.5f);

            // Check 1 (Left / background check)
            float startX1 = AndroidUtilities.dp(1.5f);
            float startY1 = cy - AndroidUtilities.dp(0.5f);
            float midX1 = startX1 + AndroidUtilities.dp(3.8f);
            float midY1 = startY1 + AndroidUtilities.dp(4.2f);
            float endX1 = startX1 + AndroidUtilities.dp(8.5f);
            float endY1 = startY1 - AndroidUtilities.dp(4.2f);

            path1.rewind();
            path1.moveTo(startX1, startY1);
            path1.lineTo(midX1, midY1);
            path1.lineTo(endX1, endY1);

            // Check 2 (Right / foreground check)
            float startX2 = startX1 + offset;
            float midX2 = midX1 + offset;
            float endX2 = width - AndroidUtilities.dp(1.5f);

            path2.rewind();
            path2.moveTo(startX2, startY1);
            path2.lineTo(midX2, midY1);
            path2.lineTo(endX2, endY1);
        }
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        final Rect bounds = getBounds();

        if (type == TYPE_PENDING) {
            // Modern smooth circular spinner / clock
            final int cx = bounds.centerX();
            final int cy = bounds.centerY();
            final long currentTime = System.currentTimeMillis();
            final float progress = ((currentTime - startTime) % 1200) / 1200f;
            final float radius = (Math.min(bounds.width(), bounds.height()) / 2f) - AndroidUtilities.dp(1.2f);

            arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
            canvas.drawArc(arcRect, progress * 360f, 270f, false, paint);
            //perf: fixed-rate repaint instead of an unbounded invalidateSelf() on every frame
            unscheduleSelf(pendingFrame);
            scheduleSelf(pendingFrame, SystemClock.uptimeMillis() + PENDING_FRAME_MS);
            return;
        }

        final boolean glow = SharedConfig.messageStatusStyle == 2; //ayu: check slots are single ticks (TYPE_SENT) now, glow must not depend on TYPE_READ
        if (glow && glowColorSource != color) {
            glowColorSource = color;
            glowColor = ColorUtils.setAlphaComponent(color, 60);
            glowPaint.setColor(glowColor);
        }

        ensurePaths(bounds.width(), bounds.height());

        canvas.save();
        canvas.translate(bounds.left, bounds.top);
        if (type == TYPE_SENT) {
            if (glow) {
                canvas.drawPath(path1, glowPaint);
            }
            canvas.drawPath(path1, paint);
        } else if (type == TYPE_READ) {
            if (glow) {
                canvas.drawPath(path1, glowPaint);
                canvas.drawPath(path2, glowPaint);
            }
            canvas.drawPath(path1, paint);
            canvas.drawPath(path2, paint);
        }
        canvas.restore();
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
        if (this.alpha == alpha) {
            return;
        }
        this.alpha = alpha;
        updatePaintAlpha();
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        if (paint.getColorFilter() == colorFilter) {
            return;
        }
        paint.setColorFilter(colorFilter);
        glowPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
