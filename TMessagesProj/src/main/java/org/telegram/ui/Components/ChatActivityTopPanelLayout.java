package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;

import me.vkryl.android.animator.ListAnimator;

public class ChatActivityTopPanelLayout extends AnimatedLinearLayout {
    public ChatActivityTopPanelLayout(@NonNull Context context) {
        super(context);

        setOrientation(LinearLayout.VERTICAL);
        updateColors();
    }

    BlurredBackgroundDrawable backgroundDrawable;

    public void setBlurredBackground(BlurredBackgroundDrawable background) {
        backgroundDrawable = background;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        checkBoundsAndClipping();
    }

    @Override
    protected void onItemsChanged() {
        super.onItemsChanged();
        checkBoundsAndClipping();
        invalidate();
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        super.setPadding(left, top, right, bottom);
        checkBoundsAndClipping();
        invalidate();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        return super.dispatchTouchEvent(ev)
                || ev.getAction() == MotionEvent.ACTION_DOWN && backgroundDrawable != null && backgroundDrawable.getBounds().contains((int) ev.getX(), (int) ev.getY());
    }

    private final Path clipPath = new Path();
    private final RectF clipRectF = new RectF();
    private View transparentLeadingSpacer;
    private float leadingSpacerVisibility;
    private boolean hasChrome;

    /** Reserves layout/touch space without treating it as a rounded panel or divider row. */
    public void setTransparentLeadingSpacer(View spacer) {
        transparentLeadingSpacer = spacer;
        if (spacer != null) setPriority(spacer, Integer.MIN_VALUE);
        checkBoundsAndClipping();
        invalidate();
    }

    private void checkBoundsAndClipping() {
        final float bgHeight = getMetadata().getTotalHeight();
        float spacerHeight = 0;
        float bgAlpha = 0;
        leadingSpacerVisibility = 0;
        for (int i = 0, count = getEntriesCount(); i < count; i++) {
            ListAnimator.Entry<AnimatedLinearLayout.Holder> entry = getEntry(i);
            if (entry.item.view == transparentLeadingSpacer) {
                leadingSpacerVisibility = entry.getVisibility();
                spacerHeight = entry.getRectF().height() * leadingSpacerVisibility;
            } else {
                bgAlpha += entry.getVisibility();
            }
        }
        bgAlpha = Math.min(1f, bgAlpha);
        final float chromeHeight = Math.max(0, bgHeight - spacerHeight);
        hasChrome = chromeHeight > 0 && bgAlpha > 0;

        clipRectF.set(getPaddingLeft(), getPaddingTop() + spacerHeight, getMeasuredWidth() - getPaddingRight(), getPaddingTop() + bgHeight);

        final float r = Math.min(dp(18), Math.min(clipRectF.width(), clipRectF.height()) / 2f);
        clipPath.rewind();
        clipPath.addRoundRect(clipRectF, r, r, Path.Direction.CW);

        if (backgroundDrawable != null) {
            backgroundDrawable.setAlpha(hasChrome ? (int) (bgAlpha * 255) : 0);
            if (hasChrome) {
                int backgroundTop = spacerHeight > 0 ? (int) (getPaddingTop() + spacerHeight) : 0;
                backgroundDrawable.setBounds(getPaddingLeft() - dp(7), backgroundTop, getMeasuredWidth() - getPaddingRight() + dp(7), getPaddingTop() + getPaddingBottom() + (int) bgHeight);
            } else {
                backgroundDrawable.setBounds(0, 0, 0, 0);
            }
            backgroundDrawable.setRadius(Math.min(dp(18), chromeHeight / 2));
        }
    }

    public void updateColors() {
        if (backgroundDrawable != null) {
            backgroundDrawable.updateColors();
        }

        final int color = Theme.getColor(Theme.key_windowBackgroundWhite);
        final int alpha = Color.alpha(color);

        invalidate();
    }

    private FragmentContextView callFragmentContextView;

    public void setCallFragmentContextView(FragmentContextView fragmentContextView) {
        callFragmentContextView = fragmentContextView;
        callFragmentContextView.getCapsuleBlobDrawable().setCallback(this);
    }

    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        return super.verifyDrawable(who) || callFragmentContextView != null && callFragmentContextView.getCapsuleBlobDrawable() == who;
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        if (!hasChrome) return;

        if (backgroundDrawable != null) {
            backgroundDrawable.draw(canvas);
        }

        boolean callDrawn = false;
        if (callFragmentContextView != null) {
            final int style = callFragmentContextView.getCurrentStyle();
            if (style == FragmentContextView.STYLE_ACTIVE_GROUP_CALL || style == FragmentContextView.STYLE_CONNECTING_GROUP_CALL) {
                for (int a = 0, N = getEntriesCount(); a < N; a++) {
                    final ListAnimator.Entry<AnimatedLinearLayout.Holder> entry = getEntry(a);
                    final float top = getPaddingTop() + entry.getRectF().top;
                    final View view = entry.item.view;
                    final float alpha = entry.getVisibility();
                    if (alpha <= 0) {
                        continue;
                    }

                    if (callFragmentContextView == view || callFragmentContextView.getParent() == view) {
                        final CapsuleBlobDrawable capsuleBlobDrawable = callFragmentContextView.getCapsuleBlobDrawable();

                        final int p = capsuleBlobDrawable.getRequiredInset();
                        final int h = dp(36) + p * 2;
                        capsuleBlobDrawable.setBounds(getPaddingLeft() - p, -p, getMeasuredWidth() - getPaddingRight() + p, -p + h);
                        capsuleBlobDrawable.setAlpha((int) (255 * alpha));
                        canvas.save();
                        canvas.translate(0, top);
                        capsuleBlobDrawable.draw(canvas);
                        canvas.restore();

                        callDrawn = true;
                    }
                }
            }
        }

        canvas.save();
        canvas.clipPath(clipPath);
        for (int a = 0, N = getEntriesCount(); a < N; a++) {
            final ListAnimator.Entry<AnimatedLinearLayout.Holder> entry = getEntry(a);
            final float top = getPaddingTop() + entry.getRectF().top;
            final View view = entry.item.view;

            if (view == transparentLeadingSpacer) continue;

            final float position = Math.max(0, entry.getPosition() - leadingSpacerVisibility);
            final float alpha = entry.getVisibility() * Math.min(1, position);

            if (alpha <= 0) {
                continue;
            }

            if (callDrawn && callFragmentContextView != null && (callFragmentContextView == view || callFragmentContextView.getParent() == view)) {
                continue;
            }

            final int wasAlpha = Theme.dividerPaint.getAlpha();
            Theme.dividerPaint.setAlpha((int) (wasAlpha * alpha));
            final float offsetL = getPaddingLeft() + dp(16) * (1f - alpha);
            final float offsetR = getPaddingRight() + dp(16) * (1f - alpha);
            canvas.drawLine(offsetL, top, getWidth() - offsetR, top, Theme.dividerPaint);
            Theme.dividerPaint.setAlpha(wasAlpha);
        }

        super.dispatchDraw(canvas);
        canvas.restore();
    }
}
