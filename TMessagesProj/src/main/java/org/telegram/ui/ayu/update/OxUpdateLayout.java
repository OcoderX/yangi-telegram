package org.telegram.ui.ayu.update;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.ayu.update.OxAppUpdater;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.IUpdateLayout;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.MainTabsActivity;
import org.telegram.ui.UpdateLayoutWrapper;

/** Persistent home-screen update action; observes the existing updater without polling. */
public final class OxUpdateLayout extends IUpdateLayout {
    private final Activity activity;
    private final UpdateBar bar;
    private final TextView label;
    private final TextView size;
    private final ImageView icon;
    private final OxAppUpdater.Listener listener = this::render;
    private boolean listening;
    private int renderedVersion;
    private OxAppUpdater.State renderedState;
    private OxAppUpdater.Error renderedError;
    private long renderedTotal = -1;
    private int renderedPercent = -1;
    private Object renderedLocale;

    public OxUpdateLayout(Activity activity, ViewGroup container) {
        super(activity, container);
        this.activity = activity;
        bar = new UpdateBar(activity);
        bar.setLayoutDirection(LocaleController.isRTL ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        bar.setVisibility(View.GONE);
        bar.setMinimumHeight(dp(UpdateLayoutWrapper.HEIGHT));
        bar.setFocusable(true);
        bar.setBackground(Theme.getSelectorDrawable(0x40ffffff, false));
        bar.setOnClickListener(v -> openUpdate());

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        row.setLayoutDirection(LocaleController.isRTL ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        icon = new ImageView(activity);
        icon.setImageResource(R.drawable.msg_download);
        icon.setColorFilter(0xffffffff);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        label = text(activity, 15);
        label.setTypeface(AndroidUtilities.bold());
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelParams.setMarginStart(dp(8));
        labelParams.setMarginEnd(dp(8));
        row.addView(label, labelParams);
        size = text(activity, 14);
        size.setSingleLine(true);
        size.setAlpha(0.85f);
        row.addView(size, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bar.addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        container.addView(bar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, UpdateLayoutWrapper.HEIGHT, Gravity.BOTTOM));
        render(OxAppUpdater.getInstance().snapshot());
    }

    private TextView text(Context context, int textSize) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize);
        view.setTextColor(0xffffffff);
        view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    @Override public void updateAppUpdateViews(int currentAccount, boolean animated) {
        render(OxAppUpdater.getInstance().snapshot());
    }

    private void openUpdate() {
        if (!(activity instanceof LaunchActivity) || activity.isFinishing() || ApplicationLoader.mainInterfacePaused
                || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())
                || !(LaunchActivity.getLastFragment() instanceof MainTabsActivity)) return;
        OxAppUpdater.Snapshot snapshot = OxAppUpdater.getInstance().snapshot();
        if (snapshot.update == null || snapshot.error == OxAppUpdater.Error.NO_UPDATE
                || snapshot.update.versionCode != renderedVersion || bar.getVisibility() != View.VISIBLE) return;
        boolean start = snapshot.state == OxAppUpdater.State.AVAILABLE || snapshot.state == OxAppUpdater.State.ERROR
                || snapshot.state == OxAppUpdater.State.READY;
        ((LaunchActivity) activity).presentFragment(start ? new OxUpdatesActivity(snapshot.update.versionCode) : new OxUpdatesActivity());
    }

    private void render(OxAppUpdater.Snapshot snapshot) {
        boolean visible = snapshot.update != null && snapshot.error != OxAppUpdater.Error.NO_UPDATE
                && snapshot.state != OxAppUpdater.State.IDLE;
        bar.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) {
            renderedVersion = 0;
            return;
        }
        boolean downloading = snapshot.state == OxAppUpdater.State.DOWNLOADING;
        float progress = downloading ? snapshot.progress() : 0f;
        bar.setProgress(progress);
        int percent = downloading ? Math.round(progress * 100) : -1;
        Object locale = LocaleController.getInstance().getCurrentLocaleInfo();
        if (renderedState == snapshot.state && renderedError == snapshot.error
                && renderedVersion == snapshot.update.versionCode && renderedTotal == snapshot.totalBytes
                && renderedPercent == percent && renderedLocale == locale) return;
        renderedState = snapshot.state;
        renderedError = snapshot.error;
        renderedVersion = snapshot.update.versionCode;
        renderedTotal = snapshot.totalBytes;
        renderedPercent = percent;
        renderedLocale = locale;
        int title = R.string.OxUpdatesBarUpdate;
        switch (snapshot.state) {
            case CHECKING: title = R.string.OxUpdatesChecking; break;
            case VERIFYING: title = R.string.OxUpdatesVerifying; break;
            case READY: title = R.string.OxUpdatesInstall; break;
            case ERROR: title = R.string.OxUpdatesRetry; break;
        }
        String titleText = downloading ? LocaleController.formatString(R.string.OxUpdatesBarProgress, percent) : getString(title);
        String sizeText = snapshot.totalBytes > 0 ? AndroidUtilities.formatFileSize(snapshot.totalBytes) : "";
        label.setText(titleText);
        size.setText(sizeText);
        icon.setImageResource(snapshot.state == OxAppUpdater.State.ERROR ? R.drawable.msg_retry : R.drawable.msg_download);
        String description = titleText + (sizeText.isEmpty() ? "" : ", " + sizeText);
        if (snapshot.error != OxAppUpdater.Error.NONE && snapshot.error != OxAppUpdater.Error.CANCELED) {
            description += ". " + getString(OxUpdatesActivity.errorString(snapshot.error));
        }
        bar.setContentDescription(description);
    }

    private final class UpdateBar extends FrameLayout {
        private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float progress;
        private int touchVersion;
        private boolean canceledTouch;

        UpdateBar(Context context) {
            super(context);
            setWillNotDraw(false);
            progressPaint.setColor(0xccffffff);
        }

        void setProgress(float value) {
            if (progress == value) return;
            progress = value;
            invalidate();
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                touchVersion = renderedVersion;
                canceledTouch = false;
            }
            if (canceledTouch) return true;
            if (event.getPointerCount() > 1 || touchVersion != renderedVersion) {
                canceledTouch = true;
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.onTouchEvent(cancel);
                cancel.recycle();
                return true;
            }
            return super.onTouchEvent(event);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (progress <= 0) return;
            float width = getWidth() * progress;
            boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            canvas.drawRect(rtl ? getWidth() - width : 0, getHeight() - dp(2), rtl ? getWidth() : width, getHeight(), progressPaint);
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (!listening) {
                listening = true;
                OxAppUpdater.getInstance().addListener(listener);
            }
        }

        @Override protected void onDetachedFromWindow() {
            if (listening) OxAppUpdater.getInstance().removeListener(listener);
            listening = false;
            super.onDetachedFromWindow();
        }

        @Override protected void onConfigurationChanged(Configuration newConfig) {
            super.onConfigurationChanged(newConfig);
            renderedLocale = null;
            setLayoutDirection(LocaleController.isRTL ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
            getChildAt(0).setLayoutDirection(getLayoutDirection());
            render(OxAppUpdater.getInstance().snapshot());
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName(Button.class.getName());
        }
    }
}
