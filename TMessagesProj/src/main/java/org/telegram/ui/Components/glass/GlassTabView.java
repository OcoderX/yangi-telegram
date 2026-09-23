package org.telegram.ui.Components.glass;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.AndroidUtilities.lerp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.RawRes;
import androidx.annotation.StringRes;
import androidx.core.graphics.ColorUtils;
import androidx.core.math.MathUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DocumentObject;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Premium.PremiumGradient;
import org.telegram.ui.Components.RLottieDrawable;
import org.telegram.ui.Components.RLottieImageView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.MainTabsLayout;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;

public class GlassTabView extends FrameLayout implements MainTabsLayout.Tab, FactorAnimator.Target {

    // ---- Default (non-compact) sizing ----
    // Used by GlassTabView.createMainTab()/createAvatar() when compact==false: the
    // StatisticActivity tab strip (Statistics/Boosts/Monetization), the StarGiftPreviewSheet
    // model/backdrop/symbol tabs, and any other caller that does not opt into compact mode.
    // Do not change these without checking those call sites first
    // (grep -rn "createMainTab\|createAvatar" TMessagesProj/src/main/java).
    private static final int DEFAULT_ICON_SIZE_DP = 24;
    private static final int DEFAULT_ICON_TOP_MARGIN_DP = 4;
    private static final float DEFAULT_TEXT_SIZE_SP = 12f;
    private static final float DEFAULT_TEXT_TOP_MARGIN_DP = 28.33f;
    private static final int DEFAULT_AVATAR_SIZE_DP = 22;
    private static final int DEFAULT_AVATAR_TOP_MARGIN_DP = 5;
    private static final float DEFAULT_AVATAR_ROUND_RADIUS_DP = 11f;

    // ---- Compact sizing ----
    // Opt-in via createMainTab(..., true) / createAvatar(..., true). Used only by the main tab
    // bar (Chatlar / OcoderX / Sozlamalar / Profil, wired in MainTabsActivity). Tune these to
    // resize the pill's contents. The pill's own height / corner radius / outer margins are set
    // by DialogsActivity.MAIN_TABS_HEIGHT and MAIN_TABS_MARGIN (consumed from MainTabsActivity),
    // not here — see this class's caller for the exact line to change.
    // Content budget at these values: 2 (top) + 18 (icon) + 1 (gap) + ~12 (9sp bold text line)
    // + ~3 (bottom) ≈ 36dp, meant to fit inside a 36dp per-tab content height (paired with a
    // 44dp visible pill, i.e. MAIN_TABS_HEIGHT=44; content = MAIN_TABS_HEIGHT - 8).
    private static final int COMPACT_ICON_SIZE_DP = 18;
    private static final int COMPACT_ICON_TOP_MARGIN_DP = 2;
    private static final float COMPACT_ICON_TEXT_GAP_DP = 1f;
    private static final float COMPACT_TEXT_SIZE_SP = 9f;
    private static final int COMPACT_AVATAR_SIZE_DP = 18;
    private static final float COMPACT_AVATAR_ROUND_RADIUS_DP = 9f;
    private static final int COMPACT_HIGHLIGHT_HORIZONTAL_PADDING_DP = 6;
    // Recommended overall pill height (dp) to pair with this compact sizing; see report for the
    // exact DialogsActivity.java line to change to reach it.
    private static final int COMPACT_BAR_HEIGHT_DP = 44;
    // Minimum touch target height enforced in onMeasure() when compact, regardless of the
    // (shorter) exact height the container hands down. See onMeasure()/dispatchDraw().
    private static final int MIN_TOUCH_TARGET_DP = 48;

    private boolean compact;
    // The real (short) content-band height compact mode was measured at, before onMeasure()
    // pads it up to MIN_TOUCH_TARGET_DP; used to keep the selected-tab highlight sized to the
    // visible content instead of the enlarged touch area. 0 until first measured.
    private int compactVisibleHeightPx;

    private final TextView textView;
    private final RLottieImageView imageView;
    private BackupImageView backupImageView;
    private Theme.ResourcesProvider resourcesProvider;
    private final Paint paintCounterBackground = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final AnimatedTextView.AnimatedTextDrawable counter;

    private static final int ANIMATOR_ID_IS_SELECTED = 0;
    private static final int ANIMATOR_ID_COUNTER_VISIBLE = 1;
    private static final int ANIMATOR_ID_COUNTER_ERROR = 2;

    private final BoolAnimator isSelectedAnimator = new BoolAnimator(ANIMATOR_ID_IS_SELECTED, this, AnimatorUtils.DECELERATE_INTERPOLATOR, 320);
    private final BoolAnimator isHasCounterAnimator = new BoolAnimator(ANIMATOR_ID_COUNTER_VISIBLE, this, CubicBezierInterpolator.EASE_OUT_QUINT, 380);
    private final BoolAnimator isHasCounterErrorAnimator = new BoolAnimator(ANIMATOR_ID_COUNTER_ERROR, this, CubicBezierInterpolator.EASE_OUT_QUINT, 380);
    private int colorSelected;
    private int colorSelectedText;
    private int colorDefault;
    private boolean usePremiumCounter;

    private TabAnimation tabAnimation;
    private TLRPC.TL_attachMenuBot tabAnimationBot;

    private final TextPaint defaultTextPaint;

    public GlassTabView(@NonNull Context context) {
        super(context);
        imageView = new RLottieImageView(context);
        addView(imageView, LayoutHelper.createFrame(44, 44, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, -6, 0, 0));

        imageView.setColorFilter(new PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN));

        textView = new TextView(context);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, DEFAULT_TEXT_SIZE_SP);
        textView.setSingleLine();
        textView.setLines(1);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        textView.setTypeface(AndroidUtilities.bold());
        textView.setGravity(Gravity.CENTER);

        defaultTextPaint = new TextPaint(textView.getPaint());
        addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, DEFAULT_TEXT_TOP_MARGIN_DP, 0, 0));

        counter = new AnimatedTextView.AnimatedTextDrawable();
        counter.setTypeface(AndroidUtilities.bold());
        counter.setCallback(this);
        counter.setGravity(Gravity.CENTER);
        counter.setTextColor(Color.WHITE);
        counter.setTextSize(dp(10));
    }

    private boolean hasVisualWidth;
    private float visualWidth;
    public void setVisualWidth(float width) {
        hasVisualWidth = true;
        if (visualWidth != width) {
            visualWidth = width;
            checkVisualWidth();
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        checkVisualWidth();
    }

    private void checkVisualWidth() {
        if (hasVisualWidth) {
            final float offset = (visualWidth - getMeasuredWidth()) / 2f;
            imageView.setTranslationX(offset);
            textView.setTranslationX(offset);
        }
    }

    private static final RectF tmpRectF = new RectF();

    private boolean hasGestureSelectedOverride;
    private float gestureSelectedOverride;
    private boolean skipDrawSelector;

    public void setGestureSelectedOverride(float gestureSelectedOverride, boolean allow) {
        this.gestureSelectedOverride = gestureSelectedOverride;
        this.hasGestureSelectedOverride = allow;
        invalidate();
    }

    public void setSkipDrawSelector(boolean skipDrawSelector) {
        if (this.skipDrawSelector != skipDrawSelector) {
            this.skipDrawSelector = skipDrawSelector;
            invalidate();
        }
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        final float viewWidth = hasVisualWidth ? visualWidth : getWidth();
        final float selectedFactor = hasGestureSelectedOverride ? gestureSelectedOverride : isSelectedAnimator.getFloatValue();
        if (selectedFactor > 0 && !skipDrawSelector) {
            final float alpha = AnimatorUtils.DECELERATE_INTERPOLATOR.getInterpolation(selectedFactor);

            paintCounterBackground.setColor(Theme.multAlpha(colorSelected, 0.09f * alpha));
            if (compact) {
                final float pad = dp(COMPACT_HIGHLIGHT_HORIZONTAL_PADDING_DP);
                // Use the real content-band height, not getHeight() — onMeasure() may have
                // padded the view's actual bounds up to MIN_TOUCH_TARGET_DP for touch purposes,
                // and the highlight must stay sized to the visible compact content only.
                final float highlightHeight = compactVisibleHeightPx > 0 ? compactVisibleHeightPx : getHeight();
                tmpRectF.set(pad, 0, Math.max(pad, viewWidth - pad), highlightHeight);
            } else {
                tmpRectF.set(0, 0, viewWidth, getHeight());
            }
            final float r = Math.min(tmpRectF.width(), tmpRectF.height()) / 2f;
            final float s = lerp(0.6f, 1, selectedFactor) * MathUtils.clamp(attachScale, 0, 1);
            canvas.save();
            canvas.scale(s, s, tmpRectF.centerX(), tmpRectF.centerY());
            canvas.drawRoundRect(tmpRectF, r, r, paintCounterBackground);
            canvas.restore();
        }

        final float hasCounter = (usePremiumCounter ? 1f : isHasCounterAnimator.getFloatValue()) * attachScale;
        final boolean saveLayer = hasCounter > 0;
        if (saveLayer) {
            canvas.saveLayer(0, 0, viewWidth, getHeight(), null);
        }

        super.dispatchDraw(canvas);

        if (hasCounter > 0) {
            canvas.save();

            final float gap = dpf2(1.33f);
            final float cx = viewWidth / 2f + dpf2(11);
            final float cy = dpf2(10);
            final float height = dpf2(16);
            final float width = Math.max(height, counter.getCurrentWidth() + dp(8));
            final float rOuter = dpf2(9.333f);
            final float rInner = dpf2(8f);
            tmpRectF.set(
                    cx - width / 2f - gap,
                    cy - height / 2f - gap,
                    cx + width / 2f + gap,
                    cy + height / 2f + gap
            );

            canvas.scale(hasCounter, hasCounter, cx, cy);
            canvas.drawRoundRect(tmpRectF, rOuter, rOuter, Theme.PAINT_CLEAR);
            tmpRectF.inset(gap, gap);

            if (usePremiumCounter) {
                if (premiumStarDrawable == null) {
                    premiumStarDrawable = getContext().getResources().getDrawable(R.drawable.star).mutate();
                }

                PremiumGradient.getInstance().updateMainGradientMatrix(0, 0, dp(96), dp(16), 0, 0);
                canvas.drawRoundRect(tmpRectF, rInner, rInner, PremiumGradient.getInstance().getMainGradientPaint());
                int x = (int)(cx - dpf2(7f));
                int y = (int)(cy - dpf2(7f));
                premiumStarDrawable.setBounds(x, y, x + dp(14), y + dp(14));
                premiumStarDrawable.draw(canvas);
            } else {
                paintCounterBackground.setColor(ColorUtils.blendARGB(Theme.getColor(Theme.key_telegram_color), Theme.getColor(Theme.key_fill_RedNormal), isHasCounterErrorAnimator.getFloatValue()));
                canvas.drawRoundRect(tmpRectF, rInner, rInner, paintCounterBackground);
                counter.setBounds(tmpRectF);
                counter.draw(canvas);
            }
            canvas.restore();
        }

        if (saveLayer) {
            canvas.restore();
        }
    }

    private Drawable premiumStarDrawable;

    public void setCounter(String text, boolean isError, boolean animated) {
        counter.setText(text, animated);
        isHasCounterAnimator.setValue(!TextUtils.isEmpty(text), animated);
        isHasCounterErrorAnimator.setValue(isError, animated);
    }

    public void setPremiumBadge(boolean usePremiumBadge) {
        usePremiumCounter = usePremiumBadge;
    }

    public void setSelected(boolean selected, boolean animated) {
        isSelectedAnimator.setValue(selected, animated);
        checkPlayAnimation(animated);

        textView.setTypeface(selected ? AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_EXTRA_BOLD) : AndroidUtilities.bold());
    }

    public boolean isTabSelected() {
        return isSelectedAnimator.getValue();
    }

    @Override
    public void onFactorChanged(int id, float factor, float fraction, FactorAnimator callee) {
        if (id == ANIMATOR_ID_IS_SELECTED) {
            updateColors();
        }
        invalidate();
    }

    private boolean needUpdateBackupViewColor;

    private void updateColors() {
        final int color = ColorUtils.blendARGB(colorDefault, colorSelected, isSelectedAnimator.getFloatValue());
        final int colorText = ColorUtils.blendARGB(colorDefault, colorSelectedText, isSelectedAnimator.getFloatValue());

        final PorterDuffColorFilter filter = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
        if (backupImageView != null && needUpdateBackupViewColor) {
            backupImageView.setColorFilter(filter);
            backupImageView.invalidate();
        }
        imageView.setColorFilter(filter);
        textView.setTextColor(colorText);
    }

    public void updateColorsLottie() {
        colorDefault = Theme.getColor(Theme.key_glass_tabUnselected, resourcesProvider);
        colorSelected = Theme.getColor(Theme.key_glass_tabSelected, resourcesProvider);
        colorSelectedText = Theme.getColor(Theme.key_glass_tabSelectedText, resourcesProvider);
        updateColors();
        invalidate();
    }


    private boolean lastIsSelected;
    private int lastIconAnimationRaw;
    private long lastBotIconId;

    private void checkPlayAnimation(boolean animated) {
        final boolean isSelected = isSelectedAnimator.getValue();

        if (tabAnimationBot !=  null) {
            boolean animatedIcon = true;
            TLRPC.TL_attachMenuBotIcon icon = MediaDataController.getAnimatedAttachMenuBotIcon(tabAnimationBot, isSelected);
            if (icon == null) {
                icon = MediaDataController.getStaticAttachMenuBotIcon(tabAnimationBot);
                animatedIcon = false;
            }
            if (icon != null && icon.icon != null) {
                TLRPC.Document iconDoc = icon.icon;
                if (lastBotIconId != icon.icon.id) {
                    String filter = "24_24" + (animatedIcon && !animated || true ? "_lastframe" : "");
                    backupImageView.setImage(
                        ImageLocation.getForDocument(iconDoc), filter,
                        ImageLocation.getForDocument(iconDoc), filter,
                        animatedIcon ? null : DocumentObject.getSvgThumb(iconDoc, Theme.key_windowBackgroundGray, 1f),
                        tabAnimationBot
                    );
                    lastBotIconId = iconDoc.id;
                }
            } else {
                backupImageView.clearImage();
            }
            updateColors();
            return;
        }

        if (tabAnimation == null) {
            return;
        }

        if (tabAnimation.iconStatic != -1) {
            imageView.setImageResource(tabAnimation.iconStatic);
            updateColors();
            return;
        }

        final int animationToSet = isSelected ?
            tabAnimation.iconToFilled : tabAnimation.iconToOutline;

        if (tabAnimation.endFrameMid != -1) {
            boolean update = lastIsSelected != isSelected;
            if (lastIconAnimationRaw != animationToSet) {
                lastIconAnimationRaw = animationToSet;
                imageView.setAnimation(animationToSet, 24, 24);
                update = true;
            }

            if (update) {
                final RLottieDrawable drawable = imageView.getAnimatedDrawable();
                if (drawable == null) {
                    return;
                }

                if (isSelected) {
                    drawable.setCustomEndFrame(tabAnimation.endFrameMid);
                    if (drawable.getCurrentFrame() >= tabAnimation.endFrameEnd - 2) {
                        drawable.setCurrentFrame(0, false);
                    }
                    if (drawable.getCurrentFrame() <= tabAnimation.endFrameMid) {
                        drawable.start();
                    } else {
                        drawable.setCurrentFrame(tabAnimation.endFrameMid);
                    }
                } else {
                    if (drawable.getCurrentFrame() >= tabAnimation.endFrameMid - 1) {
                        drawable.setCustomEndFrame(tabAnimation.endFrameEnd - 1);
                        drawable.start();
                    } else {
                        drawable.setCustomEndFrame(0);
                        drawable.setCurrentFrame(0);
                    }
                }
            }
            lastIsSelected = isSelected;
            return;
        }

        if (tabAnimation.iconToFilled != tabAnimation.iconToOutline) {
            if (lastIconAnimationRaw != animationToSet) {
                lastIconAnimationRaw = animationToSet;

                imageView.setAnimation(animationToSet, 24, 24);
                imageView.getAnimatedDrawable().setPlayInDirectionOfCustomEndFrame(false);
                if (animated) {
                    imageView.getAnimatedDrawable().setCurrentFrame(0);
                    imageView.playAnimation();
                } else {
                    imageView.getAnimatedDrawable().setProgress(0.99f);
                }
            }
            return;
        }

        if (imageView.getAnimatedDrawable() == null) {
            imageView.setAnimation(tabAnimation.iconToFilled, 24, 24);
        }

        final RLottieDrawable drawable = imageView.getAnimatedDrawable();
        if (drawable == null) {
            return;
        }

        if (lastIsSelected != isSelected) {
            lastIsSelected = isSelected;
            if (isSelected) {
                drawable.setPlayInDirectionOfCustomEndFrame(false);
                drawable.setCurrentFrame(0);
                drawable.setCustomEndFrame(drawable.getFramesCount());
            } else {
                drawable.setPlayInDirectionOfCustomEndFrame(true);
                drawable.setCurrentFrame(drawable.getFramesCount());
                drawable.setCustomEndFrame(0);
            }
            imageView.playAnimation();
        }
    }

    public static GlassTabView createMainTab(Context context, Theme.ResourcesProvider resourcesProvider, TabAnimation tabAnimation, @StringRes int stringRes) {
        return createMainTab(context, resourcesProvider, tabAnimation, stringRes, false);
    }

    /**
     * @param compact when true, uses the smaller COMPACT_* icon/text/gap constants meant for the
     *                main tab bar (Chatlar / OcoderX / Sozlamalar / Profil). Pass false (or use the
     *                4-arg overload) to keep the original sizing used elsewhere (StatisticActivity,
     *                StarGiftPreviewSheet).
     */
    public static GlassTabView createMainTab(Context context, Theme.ResourcesProvider resourcesProvider, TabAnimation tabAnimation, @StringRes int stringRes, boolean compact) {
        GlassTabView tab = new GlassTabView(context);
        tab.compact = compact;
        tab.resourcesProvider = resourcesProvider;
        tab.tabAnimation = tabAnimation;
        tab.textView.setText(LocaleController.getString(stringRes));
        tab.checkPlayAnimation(false);
        if (compact) {
            tab.applyCompactTextMetrics();
            tab.imageView.setLayoutParams(LayoutHelper.createFrame(COMPACT_ICON_SIZE_DP, COMPACT_ICON_SIZE_DP, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, COMPACT_ICON_TOP_MARGIN_DP, 0, 0));
        } else {
            tab.imageView.setLayoutParams(LayoutHelper.createFrame(DEFAULT_ICON_SIZE_DP, DEFAULT_ICON_SIZE_DP, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, DEFAULT_ICON_TOP_MARGIN_DP, 0, 0));
        }
        tab.colorDefault = Theme.getColor(Theme.key_glass_tabUnselected, resourcesProvider);
        tab.colorSelected = Theme.getColor(Theme.key_glass_tabSelected, resourcesProvider);
        tab.colorSelectedText = Theme.getColor(Theme.key_glass_tabSelectedText, resourcesProvider);
        tab.updateColors();
        return tab;
    }

    public static GlassTabView createAvatar(Context context, Theme.ResourcesProvider resourcesProvider, int currentAccount, @StringRes int stringRes) {
        return createAvatar(context, resourcesProvider, currentAccount, stringRes, false);
    }

    /** @param compact see {@link #createMainTab(Context, Theme.ResourcesProvider, TabAnimation, int, boolean)}. */
    public static GlassTabView createAvatar(Context context, Theme.ResourcesProvider resourcesProvider, int currentAccount, @StringRes int stringRes, boolean compact) {
        GlassTabView tab = new GlassTabView(context);
        tab.compact = compact;
        tab.textView.setText(LocaleController.getString(stringRes));
        tab.imageView.setVisibility(GONE);

        TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(UserConfig.getInstance(currentAccount).getClientUserId());
        AvatarDrawable avatarDrawable = new AvatarDrawable(user);

        BackupImageView backupImageView = new BackupImageView(context);
        backupImageView.setForUserOrChat(user, avatarDrawable);

        final int avatarSizeDp = compact ? COMPACT_AVATAR_SIZE_DP : DEFAULT_AVATAR_SIZE_DP;
        final float avatarRoundRadiusDp = compact ? COMPACT_AVATAR_ROUND_RADIUS_DP : DEFAULT_AVATAR_ROUND_RADIUS_DP;
        final int avatarTopMarginDp = compact ? COMPACT_ICON_TOP_MARGIN_DP : DEFAULT_AVATAR_TOP_MARGIN_DP;
        backupImageView.setRoundRadius(dp(avatarRoundRadiusDp));
        tab.backupImageView = backupImageView;

        tab.addView(backupImageView, LayoutHelper.createFrame(avatarSizeDp, avatarSizeDp, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, avatarTopMarginDp, 0, 0));
        if (compact) {
            tab.applyCompactTextMetrics();
        }
        tab.colorDefault = Theme.getColor(Theme.key_glass_tabUnselected, resourcesProvider);
        tab.colorSelected = Theme.getColor(Theme.key_glass_tabSelected, resourcesProvider);
        tab.colorSelectedText = Theme.getColor(Theme.key_glass_tabSelectedText, resourcesProvider);
        tab.updateColors();
        return tab;
    }

    /** Applies COMPACT_TEXT_SIZE_SP and repositions the label directly under the compact icon/avatar. */
    private void applyCompactTextMetrics() {
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, COMPACT_TEXT_SIZE_SP);
        defaultTextPaint.setTextSize(dp(COMPACT_TEXT_SIZE_SP));
        final float textTopMarginDp = COMPACT_ICON_TOP_MARGIN_DP + COMPACT_ICON_SIZE_DP + COMPACT_ICON_TEXT_GAP_DP;
        textView.setLayoutParams(LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, textTopMarginDp, 0, 0));
    }

    public void updateUserAvatar(int currentAccount) {
        TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(UserConfig.getInstance(currentAccount).getClientUserId());
        AvatarDrawable avatarDrawable = new AvatarDrawable(user);
        backupImageView.setForUserOrChat(user, avatarDrawable);
    }

    public static GlassTabView createAttachTab(Context context, Theme.ResourcesProvider resourcesProvider) {
        GlassTabView tab = new GlassTabView(context);
        tab.resourcesProvider = resourcesProvider;
        tab.selfMeasure = true;
        tab.textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        tab.textView.setPadding(dp(8), 0, dp(8), 0);
        tab.checkPlayAnimation(false);
        tab.imageView.setLayoutParams(LayoutHelper.createFrame(24, 24, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 4, 0, 0));
        tab.colorDefault = Theme.getColor(Theme.key_glass_tabUnselected, resourcesProvider);
        tab.colorSelected = Theme.getColor(Theme.key_glass_tabSelected, resourcesProvider);
        tab.colorSelectedText = Theme.getColor(Theme.key_glass_tabSelectedText, resourcesProvider);
        tab.updateColors();
        return tab;
    }

    public static GlassTabView createAttachBotTab(Context context, Theme.ResourcesProvider resourcesProvider) {
        GlassTabView tab = new GlassTabView(context);
        tab.resourcesProvider = resourcesProvider;
        tab.selfMeasure = true;
        tab.textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        tab.textView.setPadding(dp(8), 0, dp(8), 0);
        tab.imageView.setVisibility(GONE);
        tab.checkPlayAnimation(false);
        tab.backupImageView = new BackupImageView(context);
        tab.addView(tab.backupImageView, LayoutHelper.createFrame(24, 24, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 4, 0, 0));
        tab.colorDefault = Theme.getColor(Theme.key_glass_tabUnselected, resourcesProvider);
        tab.colorSelected = Theme.getColor(Theme.key_glass_tabSelected, resourcesProvider);
        tab.colorSelectedText = Theme.getColor(Theme.key_glass_tabSelectedText, resourcesProvider);
        tab.updateColors();
        return tab;
    }

    public BackupImageView getBackupImageView() {
        return backupImageView;
    }

    private boolean selfMeasure;
    private int additionalWidth;

    public void setAdditionalWidth(int additionalWidth) {
        this.additionalWidth = additionalWidth;
        this.selfMeasure = true;
    }

    public float measureAttachTabWidth() {
        final float textWidth = measureTextWidth();
        final float padding = lerp(dpf2(16), dp(8), MathUtils.clamp((textWidth - dp(40)) / dp(16), 0, 1));
        return Math.min(dp(84), (int) (textWidth + padding * 2));
    }

    public float attachScale = 1;
    public void setAttachScale(float scale) {
        textView.setScaleX(scale);
        textView.setScaleY(scale);
        imageView.setScaleX(scale);
        imageView.setScaleY(scale);
        if (backupImageView != null) {
            backupImageView.setScaleX(scale);
            backupImageView.setScaleY(scale);
        }
        attachScale = scale;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (selfMeasure) {
            final int width = (int) (measureAttachTabWidth()) + additionalWidth;
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec);
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            if (compact) {
                // The container (MainTabsLayout) hands each tab an EXACT height equal to the
                // compact content band (e.g. 36dp for a 44dp pill), which is shorter than the
                // 48dp minimum touch target. Report a taller measured height so the standard
                // Android click/touch dispatch (used by the tab's own OnClickListener) sees a
                // >=48dp tall view. The extra height is transparent and grows downward past the
                // visible content — MainTabsActivity already sets tabsView.setClipChildren(false)
                // and reserves >= this much slack as the LinearLayout's own bottom padding, so
                // nothing is clipped and no other content sits in that space. dispatchDraw below
                // keeps the visible selected-tab highlight sized to the real content band
                // (compactVisibleHeightPx), not to this enlarged touch height.
                compactVisibleHeightPx = getMeasuredHeight();
                final int minTouchTargetPx = dp(MIN_TOUCH_TARGET_DP);
                if (compactVisibleHeightPx < minTouchTargetPx) {
                    setMeasuredDimension(getMeasuredWidth(), minTouchTargetPx);
                }
            }
        }
    }

    @Override
    public float measureTextWidth() {
        return defaultTextPaint.measureText(textView.getText().toString());
    }

    private TextPaint scaledTextPaint;

    @Override
    public float measureTextWidth(float textSizeDp) {
        if (scaledTextPaint == null) {
            scaledTextPaint = new TextPaint(defaultTextPaint);
        }
        scaledTextPaint.setTextSize(dp(textSizeDp));
        return scaledTextPaint.measureText(textView.getText().toString());
    }

    @Override
    public void setTextSizeDp(float textSizeDp) {
        final float px = dp(textSizeDp);
        if (textView.getTextSize() != px) {
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textSizeDp);
            defaultTextPaint.setTextSize(px);
        }
    }

    private enum TabAnimationType {
        LOTTIE,
        STATIC
    }

    public enum TabAnimation {
        CONTACTS(R.raw.tab_contacts),
        CALLS(R.raw.tab_calls),
        CHATS(R.raw.tab_chats),
        SETTINGS(R.raw.tab_settings),

        CHECKLIST(R.raw.tab_checklist, R.raw.tab_checklist_reverse),
        COLORS(R.raw.tab_colors, R.raw.tab_colors_reverse),
        FILES(R.raw.tab_files, R.raw.tab_files_reverse),
        GALLERY(R.raw.tab_gallery, R.raw.tab_gallery_reverse),
        GIFT(R.raw.tab_gift, R.raw.tab_gift_reverse),
        LOCATION(R.raw.tab_location, R.raw.tab_location_reverse),
        STICKER(R.raw.tab_sticker, R.raw.tab_sticker_reverse),
        EMOJI(R.raw.tab_emoji, R.raw.tab_emoji_reverse),
        MODELS(R.raw.tab_models, R.raw.tab_models_reverse),
        MUSIC(R.raw.tab_music, R.raw.tab_music_reverse),
        POLL(R.raw.tab_poll, R.raw.tab_poll_reverse),
        SYMBOLS(R.raw.tab_symbols, R.raw.tab_symbols_reverse),
        REPLIES(R.raw.tab_reply, R.raw.tab_reply_reverse),
        WALLET(R.raw.tab_wallet, R.raw.tab_wallet_reverse),
        LINK(TabAnimationType.STATIC, R.drawable.tabs_link_24),
        MENU(TabAnimationType.STATIC, R.drawable.ic_ab_other),
        ARTICLE(R.raw.tab_article, R.raw.tab_article_reverse),

        BOOSTS(R.raw.boosts, 25, 49),
        MONETIZATION(R.raw.monetize, 19, 45);

        public final @RawRes int iconToFilled;
        public final @RawRes int iconToOutline;
        public final @DrawableRes int iconStatic;
        public final int endFrameMid, endFrameEnd;

        TabAnimation(int iconRes, int endFrameMid, int endFrameEnd) {
            this.iconToFilled = iconRes;
            this.iconToOutline = iconRes;
            this.endFrameMid = endFrameMid;
            this.endFrameEnd = endFrameEnd;
            this.iconStatic = -1;
        }

        TabAnimation(TabAnimationType type, int icon) {
            if (type == TabAnimationType.LOTTIE) {
                this.iconToFilled = icon;
                this.iconToOutline = icon;
                this.iconStatic = -1;
            } else {
                this.iconStatic = icon;
                this.iconToFilled = -1;
                this.iconToOutline = -1;
            }
            this.endFrameMid = -1;
            this.endFrameEnd = -1;
        }

        TabAnimation(int iconRes) {
            this.iconToFilled = iconRes;
            this.iconToOutline = iconRes;
            this.endFrameMid = -1;
            this.endFrameEnd = -1;
            this.iconStatic = -1;
        }

        TabAnimation(int iconToFilled, int iconToOutline) {
            this.iconToFilled = iconToFilled;
            this.iconToOutline = iconToOutline;
            this.endFrameMid = -1;
            this.endFrameEnd = -1;
            this.iconStatic = -1;
        }
    }

    public void setTabAnimation(TabAnimation animation) {
        tabAnimation = animation;
        tabAnimationBot = null;
        lastIconAnimationRaw = 0;
        lastBotIconId = 0;
        imageView.clearAnimationDrawable();
        checkPlayAnimation(false);
    }

    public void setText(CharSequence text) {
        textView.setText(text);
    }


    private AvatarDrawable avatarDrawable;

    public void setAttachBot(TLRPC.User user, TLRPC.TL_attachMenuBot bot, int currentAccount) {
        if (user == null || bot == null) {
            return;
        }
        tabAnimation = null;
        tabAnimationBot = bot;
        lastIconAnimationRaw = 0;
        lastBotIconId = 0;
        textView.setText(bot.short_name);

        backupImageView.setRoundRadius(0);
        backupImageView.setSize(dp(24), dp(24));
        backupImageView.setLayoutParams(LayoutHelper.createFrame(24, 24, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 4, 0, 0));
        needUpdateBackupViewColor = true;
        checkPlayAnimation(false);
        updateColors();
        invalidate();
    }

    public void setAttachBotUser(TLRPC.User user, int currentAccount) {
        if (user == null) {
            return;
        }
        tabAnimation = null;
        tabAnimationBot = null;
        lastIconAnimationRaw = 0;
        lastBotIconId = 0;

        textView.setText(ContactsController.formatName(user.first_name, user.last_name));
        if (avatarDrawable == null) {
            avatarDrawable = new AvatarDrawable();
        }
        avatarDrawable.setInfo(currentAccount, user);
        backupImageView.setForUserOrChat(user, avatarDrawable);
        backupImageView.setSize(-1, -1);
        backupImageView.setRoundRadius(dp(11.33f));
        backupImageView.setLayoutParams(LayoutHelper.createFrame(22, 22, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 5, 0, 0));
        backupImageView.setColorFilter(null);
        needUpdateBackupViewColor = false;
        invalidate();
    }

    public void onPreBind() {

    }
}