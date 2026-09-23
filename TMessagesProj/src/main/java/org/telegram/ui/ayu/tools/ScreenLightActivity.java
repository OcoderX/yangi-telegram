package org.telegram.ui.ayu.tools;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SeekBarView;

/**
 * OcoderX-gram: turns the screen into a plain, maximum-brightness colour panel so the phone can be
 * used as an improvised lamp / reflector for photos and video calls. Tapping anywhere toggles the
 * bottom control panel; the chosen colour and brightness are remembered in {@link OxToolsConfig}.
 */
public class ScreenLightActivity extends BaseFragment {

    private static final int[] COLORS = new int[]{
            0xFFFFFFFF, // white
            0xFFFFF4E0, // warm white
            0xFFFFEB3B, // yellow
            0xFFFF3B30, // red
            0xFF34C759, // green
            0xFF3390EC, // blue
            0xFFAF52DE, // purple
    };

    private View colorView;
    private LinearLayout controlPanel;
    private SeekBarView brightnessSeekBar;
    private LinearLayout swatchRow;

    private int currentColor = 0xFFFFFFFF;
    private float currentBrightness = 1f;
    private boolean panelVisible = true;
    private boolean updatingSeekBarInternally;

    private boolean windowFlagsApplied;
    private float previousBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
    private boolean previousKeepScreenOn;
    private boolean systemBarsCaptured;
    private int previousStatusBarColor;
    private int previousNavigationBarColor;

    @Override
    public View createView(Context context) {
        OxToolsConfig.load();
        currentColor = OxToolsConfig.screenLightColor;
        currentBrightness = OxToolsConfig.screenLightBrightness;

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        actionBar.setCastShadows(false);
        actionBar.setAlpha(0.55f);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout root = new FrameLayout(context);

        colorView = new View(context);
        colorView.setOnClickListener(v -> togglePanel());
        root.addView(colorView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        controlPanel = buildControlPanel(context);
        root.addView(controlPanel, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.BOTTOM, 16, 0, 16, 24));

        fragmentView = root;

        applyColor(currentColor);
        updateSeekBarFromBrightness(currentBrightness);
        applyWindowFlags();

        return fragmentView;
    }

    private LinearLayout buildControlPanel(Context context) {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Theme.createRoundRectDrawable(dp(18), 0xAA000000));
        panel.setPadding(dp(16), dp(14), dp(16), dp(10));

        TextView brightnessLabel = new TextView(context);
        brightnessLabel.setText(getString(R.string.OxToolsScreenLightBrightness));
        brightnessLabel.setTextColor(0xFFFFFFFF);
        brightnessLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        panel.addView(brightnessLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        brightnessSeekBar = new SeekBarView(context, true, getResourceProvider());
        brightnessSeekBar.setReportChanges(true);
        brightnessSeekBar.setDelegate(new SeekBarView.SeekBarViewDelegate() {
            @Override
            public void onSeekBarDrag(boolean stop, float progress) {
                if (updatingSeekBarInternally) {
                    return;
                }
                float brightness = 0.1f + progress * 0.9f;
                currentBrightness = brightness;
                applyBrightness(brightness);
                OxToolsConfig.setScreenLightBrightness(brightness);
            }

            @Override
            public void onSeekBarPressed(boolean pressed) {
            }

            @Override
            public CharSequence getContentDescription() {
                return getString(R.string.OxToolsScreenLightBrightness);
            }
        });
        panel.addView(brightnessSeekBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 38, 0, 4, 0, 6));

        swatchRow = new LinearLayout(context);
        swatchRow.setOrientation(LinearLayout.HORIZONTAL);
        swatchRow.setGravity(Gravity.CENTER);
        for (int color : COLORS) {
            View swatch = createSwatch(context, color);
            swatchRow.addView(swatch, LayoutHelper.createLinear(32, 32, 0, 6, 4, 6, 4));
        }
        panel.addView(swatchRow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 6, 0, 0));

        return panel;
    }

    private View createSwatch(Context context, int color) {
        View swatch = new View(context) {
            @Override
            protected void onDraw(Canvas canvas) {
                Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
                fill.setColor(color);
                float cx = getWidth() / 2f;
                float cy = getHeight() / 2f;
                float r = Math.min(cx, cy) - dp(3);
                canvas.drawCircle(cx, cy, r, fill);
                if (currentColor == color) {
                    Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
                    ring.setStyle(Paint.Style.STROKE);
                    ring.setStrokeWidth(dp(2));
                    ring.setColor(0xFFFFFFFF);
                    canvas.drawCircle(cx, cy, r + dp(2), ring);
                }
            }
        };
        swatch.setOnClickListener(v -> {
            currentColor = color;
            applyColor(color);
            OxToolsConfig.setScreenLightColor(color);
            if (swatchRow != null) {
                for (int i = 0; i < swatchRow.getChildCount(); i++) {
                    swatchRow.getChildAt(i).invalidate();
                }
            }
        });
        return swatch;
    }

    private void togglePanel() {
        if (controlPanel == null) {
            return;
        }
        panelVisible = !panelVisible;
        controlPanel.animate().cancel();
        if (panelVisible) {
            controlPanel.setVisibility(View.VISIBLE);
            controlPanel.animate().alpha(1f).setDuration(180).start();
        } else {
            controlPanel.animate().alpha(0f).setDuration(180).withEndAction(() -> {
                if (!panelVisible && controlPanel != null) {
                    controlPanel.setVisibility(View.INVISIBLE);
                }
            }).start();
        }
    }

    private void updateSeekBarFromBrightness(float brightness) {
        if (brightnessSeekBar == null) {
            return;
        }
        updatingSeekBarInternally = true;
        float progress = (brightness - 0.1f) / 0.9f;
        if (progress < 0f) {
            progress = 0f;
        } else if (progress > 1f) {
            progress = 1f;
        }
        brightnessSeekBar.setProgress(progress);
        updatingSeekBarInternally = false;
        applyBrightness(brightness);
    }

    private void applyColor(int color) {
        if (colorView != null) {
            colorView.setBackgroundColor(color);
        }
        if (systemBarsCaptured) {
            setSystemBarColors(color);
        }
    }

    private void applyBrightness(float brightness) {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        WindowManager.LayoutParams params = window.getAttributes();
        params.screenBrightness = brightness;
        window.setAttributes(params);
    }

    private void applyWindowFlags() {
        if (windowFlagsApplied) {
            return;
        }
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }

        WindowManager.LayoutParams params = window.getAttributes();
        previousBrightness = params.screenBrightness;
        previousKeepScreenOn = (params.flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0;

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                previousStatusBarColor = window.getStatusBarColor();
                previousNavigationBarColor = window.getNavigationBarColor();
                systemBarsCaptured = true;
                setSystemBarColors(currentColor);
            } catch (Exception ignored) {
                systemBarsCaptured = false;
            }
        }

        windowFlagsApplied = true;
    }

    private void setSystemBarColors(int color) {
        Activity activity = getParentActivity();
        if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        try {
            window.setStatusBarColor(color);
            window.setNavigationBarColor(color);
        } catch (Exception ignored) {
        }
    }

    private void restoreWindowFlags() {
        if (!windowFlagsApplied) {
            return;
        }
        Activity activity = getParentActivity();
        if (activity != null) {
            Window window = activity.getWindow();
            if (window != null) {
                WindowManager.LayoutParams params = window.getAttributes();
                params.screenBrightness = previousBrightness;
                if (!previousKeepScreenOn) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
                window.setAttributes(params);
                if (systemBarsCaptured && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    try {
                        window.setStatusBarColor(previousStatusBarColor);
                        window.setNavigationBarColor(previousNavigationBarColor);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        windowFlagsApplied = false;
    }

    @Override
    public void onResume() {
        super.onResume();
        applyWindowFlags();
    }

    @Override
    public void onFragmentDestroy() {
        restoreWindowFlags();
        super.onFragmentDestroy();
    }
}
