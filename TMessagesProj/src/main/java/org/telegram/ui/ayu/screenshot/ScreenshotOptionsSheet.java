package org.telegram.ui.ayu.screenshot;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.RadioCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BottomSheetWithRecyclerListView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SlideChooseView;

import java.util.ArrayList;

/**
 * AyuGram: options for {@link LongScreenshotBuilder}. Everything is persisted in
 * {@link ScreenshotConfig} as soon as it is toggled, so the next screenshot reuses it.
 */
public class ScreenshotOptionsSheet extends BottomSheetWithRecyclerListView {

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_RADIO = 1;
    private static final int VIEW_TYPE_CHECK = 2;
    private static final int VIEW_TYPE_SLIDER = 3;
    private static final int VIEW_TYPE_INFO = 4;
    private static final int VIEW_TYPE_BUTTON = 5;

    // row ids
    private static final int ID_NONE = 0;
    private static final int ID_FORMAT_PNG = 1;
    private static final int ID_FORMAT_PDF = 2;
    private static final int ID_OVERFLOW_SPLIT = 3;
    private static final int ID_OVERFLOW_DOWNSCALE = 4;
    private static final int ID_PDF_CONTINUOUS = 5;
    private static final int ID_PDF_A4 = 6;
    private static final int ID_HIDE_NAMES = 7;
    private static final int ID_HIDE_AVATARS = 8;
    private static final int ID_HIDE_TIMESTAMPS = 9;
    private static final int ID_HEADER_TOGGLE = 10;
    private static final int ID_WATERMARK = 11;
    private static final int ID_BG_WALLPAPER = 12;
    private static final int ID_BG_LIGHT = 13;
    private static final int ID_BG_DARK = 14;
    private static final int ID_SLIDER = 15;
    private static final int ID_START = 16;

    private static class Row {
        final int viewType;
        final int id;
        final CharSequence text;
        boolean checked;
        boolean divider;

        Row(int viewType, int id, CharSequence text) {
            this.viewType = viewType;
            this.id = id;
            this.text = text;
        }
    }

    // not final on purpose: the super constructor already asks for the adapter, so this field can
    // still be null while the sheet is being built (see the null guards in Adapter below)
    private ArrayList<Row> rows;

    {
        rows = new ArrayList<>();
    }

    private final int messagesCount;
    private final Runnable onStart;

    public ScreenshotOptionsSheet(BaseFragment fragment, int messagesCount, Runnable onStart) {
        super(fragment, false, false);
        this.messagesCount = messagesCount;
        this.onStart = onStart;
        ScreenshotConfig.load();
        buildRows();
        if (recyclerListView != null) {
            recyclerListView.setOnItemClickListener((view, position) -> {
                final int index = position - 1;
                if (index < 0 || index >= rows.size()) {
                    return;
                }
                onRowClick(rows.get(index));
            });
        }
        notifyDataSetChanged();
        fixNavigationBar();
    }

    private void addHeader(int resId) {
        rows.add(new Row(VIEW_TYPE_HEADER, ID_NONE, getString(resId)));
    }

    private void addRadio(int id, int resId, boolean checked, boolean divider) {
        Row row = new Row(VIEW_TYPE_RADIO, id, getString(resId));
        row.checked = checked;
        row.divider = divider;
        rows.add(row);
    }

    private void addCheck(int id, int resId, boolean checked, boolean divider) {
        Row row = new Row(VIEW_TYPE_CHECK, id, getString(resId));
        row.checked = checked;
        row.divider = divider;
        rows.add(row);
    }

    private void buildRows() {
        rows.clear();

        addHeader(R.string.AyuScreenshotFormat);
        addRadio(ID_FORMAT_PNG, R.string.AyuScreenshotFormatPng, ScreenshotConfig.format == ScreenshotConfig.FORMAT_PNG, true);
        addRadio(ID_FORMAT_PDF, R.string.AyuScreenshotFormatPdf, ScreenshotConfig.format == ScreenshotConfig.FORMAT_PDF, false);

        if (ScreenshotConfig.format == ScreenshotConfig.FORMAT_PNG) {
            addHeader(R.string.AyuScreenshotOverflow);
            addRadio(ID_OVERFLOW_SPLIT, R.string.AyuScreenshotOverflowSplit, ScreenshotConfig.overflowMode == ScreenshotConfig.OVERFLOW_SPLIT, true);
            addRadio(ID_OVERFLOW_DOWNSCALE, R.string.AyuScreenshotOverflowDownscale, ScreenshotConfig.overflowMode == ScreenshotConfig.OVERFLOW_DOWNSCALE, false);
        } else {
            addHeader(R.string.AyuScreenshotPdfLayout);
            addRadio(ID_PDF_CONTINUOUS, R.string.AyuScreenshotPdfContinuous, ScreenshotConfig.pdfMode == ScreenshotConfig.PDF_CONTINUOUS, true);
            addRadio(ID_PDF_A4, R.string.AyuScreenshotPdfA4, ScreenshotConfig.pdfMode == ScreenshotConfig.PDF_A4, false);
        }

        addHeader(R.string.AyuScreenshotAppearance);
        addCheck(ID_HIDE_NAMES, R.string.AyuScreenshotHideNames, ScreenshotConfig.hideNames, true);
        addCheck(ID_HIDE_AVATARS, R.string.AyuScreenshotHideAvatars, ScreenshotConfig.hideAvatars, true);
        addCheck(ID_HIDE_TIMESTAMPS, R.string.AyuScreenshotHideTimestamps, ScreenshotConfig.hideTimestamps, true);
        addCheck(ID_HEADER_TOGGLE, R.string.AyuScreenshotIncludeHeader, ScreenshotConfig.includeHeader, true);
        addCheck(ID_WATERMARK, R.string.AyuScreenshotIncludeWatermark, ScreenshotConfig.includeWatermark, false);

        addHeader(R.string.AyuScreenshotBackground);
        addRadio(ID_BG_WALLPAPER, R.string.AyuScreenshotBackgroundWallpaper, ScreenshotConfig.background == ScreenshotConfig.BACKGROUND_WALLPAPER, true);
        addRadio(ID_BG_LIGHT, R.string.AyuScreenshotBackgroundLight, ScreenshotConfig.background == ScreenshotConfig.BACKGROUND_LIGHT, true);
        addRadio(ID_BG_DARK, R.string.AyuScreenshotBackgroundDark, ScreenshotConfig.background == ScreenshotConfig.BACKGROUND_DARK, false);

        addHeader(R.string.AyuScreenshotQuality);
        rows.add(new Row(VIEW_TYPE_SLIDER, ID_SLIDER, null));
        rows.add(new Row(VIEW_TYPE_INFO, ID_NONE, getString(R.string.AyuScreenshotAppearanceInfo)));

        rows.add(new Row(VIEW_TYPE_BUTTON, ID_START, LocaleController.formatString(R.string.AyuScreenshotCreate, messagesCount)));
    }

    private void onRowClick(Row row) {
        switch (row.id) {
            case ID_FORMAT_PNG:
                ScreenshotConfig.setFormat(ScreenshotConfig.FORMAT_PNG);
                break;
            case ID_FORMAT_PDF:
                ScreenshotConfig.setFormat(ScreenshotConfig.FORMAT_PDF);
                break;
            case ID_OVERFLOW_SPLIT:
                ScreenshotConfig.setOverflowMode(ScreenshotConfig.OVERFLOW_SPLIT);
                break;
            case ID_OVERFLOW_DOWNSCALE:
                ScreenshotConfig.setOverflowMode(ScreenshotConfig.OVERFLOW_DOWNSCALE);
                break;
            case ID_PDF_CONTINUOUS:
                ScreenshotConfig.setPdfMode(ScreenshotConfig.PDF_CONTINUOUS);
                break;
            case ID_PDF_A4:
                ScreenshotConfig.setPdfMode(ScreenshotConfig.PDF_A4);
                break;
            case ID_HIDE_NAMES:
                ScreenshotConfig.setHideNames(!ScreenshotConfig.hideNames);
                break;
            case ID_HIDE_AVATARS:
                ScreenshotConfig.setHideAvatars(!ScreenshotConfig.hideAvatars);
                break;
            case ID_HIDE_TIMESTAMPS:
                ScreenshotConfig.setHideTimestamps(!ScreenshotConfig.hideTimestamps);
                break;
            case ID_HEADER_TOGGLE:
                ScreenshotConfig.setIncludeHeader(!ScreenshotConfig.includeHeader);
                break;
            case ID_WATERMARK:
                ScreenshotConfig.setIncludeWatermark(!ScreenshotConfig.includeWatermark);
                break;
            case ID_BG_WALLPAPER:
                ScreenshotConfig.setBackground(ScreenshotConfig.BACKGROUND_WALLPAPER);
                break;
            case ID_BG_LIGHT:
                ScreenshotConfig.setBackground(ScreenshotConfig.BACKGROUND_LIGHT);
                break;
            case ID_BG_DARK:
                ScreenshotConfig.setBackground(ScreenshotConfig.BACKGROUND_DARK);
                break;
            case ID_START:
                dismiss();
                if (onStart != null) {
                    onStart.run();
                }
                return;
            default:
                return;
        }
        buildRows();
        notifyDataSetChanged();
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.AyuLongScreenshot);
    }

    @Override
    protected RecyclerListView.SelectionAdapter createAdapter(RecyclerListView listView) {
        return new Adapter();
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            final int viewType = holder.getItemViewType();
            return viewType == VIEW_TYPE_RADIO || viewType == VIEW_TYPE_CHECK || viewType == VIEW_TYPE_BUTTON;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final Context context = parent.getContext();
            final int background = Theme.getColor(Theme.key_dialogBackground, resourcesProvider);
            View view;
            switch (viewType) {
                case VIEW_TYPE_RADIO: {
                    RadioCell cell = new RadioCell(context, resourcesProvider);
                    cell.setBackgroundColor(background);
                    view = cell;
                    break;
                }
                case VIEW_TYPE_CHECK: {
                    TextCheckCell cell = new TextCheckCell(context, resourcesProvider);
                    cell.setBackgroundColor(background);
                    view = cell;
                    break;
                }
                case VIEW_TYPE_SLIDER: {
                    SlideChooseView slideChooseView = new SlideChooseView(context, resourcesProvider);
                    slideChooseView.setBackgroundColor(background);
                    slideChooseView.setOptions(ScreenshotConfig.scaleIndex, scaleOptions());
                    slideChooseView.setCallback(new SlideChooseView.Callback() {
                        @Override
                        public void onOptionSelected(int index) {
                            ScreenshotConfig.setScaleIndex(index);
                        }
                    });
                    view = slideChooseView;
                    break;
                }
                case VIEW_TYPE_INFO: {
                    TextInfoPrivacyCell cell = new TextInfoPrivacyCell(context, resourcesProvider);
                    view = cell;
                    break;
                }
                case VIEW_TYPE_BUTTON: {
                    TextView textView = new TextView(context);
                    textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                    textView.setTypeface(AndroidUtilities.bold());
                    textView.setGravity(Gravity.CENTER);
                    textView.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText, resourcesProvider));
                    textView.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(8),
                            Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider),
                            Theme.getColor(Theme.key_featuredStickers_addButtonPressed, resourcesProvider)));
                    FrameLayout container = new FrameLayout(context);
                    container.setBackgroundColor(background);
                    container.addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.CENTER, 16, 10, 16, 16));
                    view = container;
                    break;
                }
                default: {
                    HeaderCell cell = new HeaderCell(context, resourcesProvider);
                    cell.setBackgroundColor(background);
                    view = cell;
                    break;
                }
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (rows == null || position < 0 || position >= rows.size()) {
                return;
            }
            final Row row = rows.get(position);
            switch (holder.getItemViewType()) {
                case VIEW_TYPE_RADIO:
                    ((RadioCell) holder.itemView).setText(row.text, row.checked, row.divider);
                    break;
                case VIEW_TYPE_CHECK:
                    ((TextCheckCell) holder.itemView).setTextAndCheck(row.text, row.checked, row.divider);
                    break;
                case VIEW_TYPE_INFO:
                    ((TextInfoPrivacyCell) holder.itemView).setText(row.text);
                    break;
                case VIEW_TYPE_BUTTON: {
                    FrameLayout container = (FrameLayout) holder.itemView;
                    ((TextView) container.getChildAt(0)).setText(row.text);
                    break;
                }
                case VIEW_TYPE_SLIDER:
                    ((SlideChooseView) holder.itemView).setOptions(ScreenshotConfig.scaleIndex, scaleOptions());
                    break;
                default:
                    ((HeaderCell) holder.itemView).setText(row.text);
                    break;
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (rows == null || position < 0 || position >= rows.size()) {
                return VIEW_TYPE_HEADER;
            }
            return rows.get(position).viewType;
        }

        @Override
        public int getItemCount() {
            return rows == null ? 0 : rows.size();
        }
    }

    private static String[] scaleOptions() {
        final String[] options = new String[ScreenshotConfig.SCALES.length];
        for (int a = 0; a < options.length; a++) {
            options[a] = formatScale(ScreenshotConfig.SCALES[a]);
        }
        return options;
    }

    private static String formatScale(float scale) {
        if (scale == (int) scale) {
            return ((int) scale) + "x";
        }
        return scale + "x";
    }
}
