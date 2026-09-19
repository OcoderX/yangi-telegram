package org.telegram.ui.ayu;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.ayu.AyuHistoryStorage;
import org.telegram.messenger.ayu.edithistory.AyuDiffUtil;
import org.telegram.messenger.ayu.edithistory.AyuEditHistoryConfig;
import org.telegram.messenger.ayu.edithistory.AyuRevision;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BottomSheetWithRecyclerListView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.Date;

/**
 * AyuGram: bottom sheet listing every stored version of a message, newest first.
 * <p>
 * Every entry shows a word level diff against the version right below it (removed words in red with
 * a strike through, added words highlighted in green), the timestamp of the change, a "show full
 * text" toggle and a copy button. Versions whose media was replaced also show a thumbnail of the
 * media we preserved before the edit landed.
 */
public class EditHistorySheet extends BottomSheetWithRecyclerListView {

    private static final int VIEW_TYPE_REVISION = 0;
    private static final int VIEW_TYPE_EMPTY = 1;

    private final int currentAccount;
    private final MessageObject messageObject;
    private final ArrayList<Item> items = new ArrayList<>();
    private boolean loaded;

    private static class Item {
        AyuRevision revision;
        CharSequence label;
        CharSequence date;
        /** diff against the next (older) entry; for the oldest entry it is just its own text */
        ArrayList<AyuDiffUtil.Op> diffOps;
        boolean canCollapse;
        boolean mediaReplaced;
        String previewPath;
        boolean expanded;
    }

    /** opens the sheet from anywhere that has no fragment at hand (ChatMessageCell) */
    public static void showFor(int currentAccount, MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return;
        }
        try {
            final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
            if (fragment == null || fragment.getParentActivity() == null) {
                return;
            }
            fragment.showDialog(new EditHistorySheet(fragment, currentAccount, messageObject));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public EditHistorySheet(BaseFragment fragment, int currentAccount, MessageObject messageObject) {
        super(fragment, false, false);
        this.currentAccount = currentAccount;
        this.messageObject = messageObject;
        AyuEditHistoryConfig.ensureLoaded();
        fixNavigationBar();
        load();
    }

    // ------------------------------------------------------------------ data

    private void load() {
        if (messageObject == null || messageObject.messageOwner == null) {
            loaded = true;
            return;
        }
        final int account = currentAccount;
        final MessageObject object = messageObject;
        AyuHistoryStorage.getQueue().postRunnable(() -> {
            final ArrayList<Item> built = new ArrayList<>();
            try {
                final ArrayList<AyuRevision> revisions = AyuRevision.buildList(account, object);
                for (int a = 0; a < revisions.size(); a++) {
                    final AyuRevision revision = revisions.get(a);
                    final AyuRevision older = a + 1 < revisions.size() ? revisions.get(a + 1) : null;
                    final Item item = new Item();
                    item.revision = revision;
                    if (revision.current) {
                        item.label = getString(R.string.AyuEditHistoryCurrent);
                    } else if (revision.original) {
                        item.label = getString(R.string.AyuEditHistoryOriginal);
                    } else {
                        item.label = LocaleController.formatString(R.string.AyuEditHistoryRevision, revision.index);
                    }
                    item.date = formatDate(revision.getDisplayDate());
                    final String text = revision.text == null ? "" : revision.text;
                    if (older != null && AyuEditHistoryConfig.wordDiff) {
                        item.diffOps = AyuDiffUtil.diffWords(older.text == null ? "" : older.text, text);
                        item.mediaReplaced = older.mediaId != revision.mediaId;
                    } else {
                        item.diffOps = AyuDiffUtil.diffWords(text, text);
                    }
                    item.canCollapse = AyuDiffUtil.canCollapse(item.diffOps);
                    item.expanded = AyuEditHistoryConfig.expandFullTextByDefault || !item.canCollapse;
                    item.previewPath = revision.getPreviewPath();
                    built.add(item);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            AndroidUtilities.runOnUIThread(() -> {
                items.clear();
                items.addAll(built);
                loaded = true;
                try {
                    notifyDataSetChanged();
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            });
        });
    }

    private static CharSequence formatDate(int date) {
        if (date == 0) {
            return "";
        }
        try {
            return LocaleController.getInstance().getFormatterYear().format(new Date((long) date * 1000))
                    + ", " + LocaleController.getInstance().getFormatterDayWithSeconds().format(new Date((long) date * 1000));
        } catch (Exception e) {
            return String.valueOf(date);
        }
    }

    private void copy(Item item) {
        if (item == null || item.revision == null) {
            return;
        }
        final String text = item.revision.text;
        if (TextUtils.isEmpty(text)) {
            return;
        }
        AndroidUtilities.addToClipboard(text);
        try {
            BulletinFactory.of(container, resourcesProvider)
                    .createSimpleBulletin(R.raw.copy, getString(R.string.AyuCopiedToClipboard))
                    .show();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.AyuEditHistory);
    }

    @Override
    protected RecyclerListView.SelectionAdapter createAdapter(RecyclerListView listView) {
        return new Adapter();
    }

    // ------------------------------------------------------------------ cell

    private class RevisionCell extends FrameLayout {

        private final LinearLayout content;
        private final TextView labelView;
        private final TextView dateView;
        private final LinearLayout mediaRow;
        private final BackupImageView thumbView;
        private final TextView mediaLabel;
        private final TextView textView;
        private final LinearLayout buttonsRow;
        private final TextView toggleButton;
        private final TextView copyButton;
        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        private Item item;
        private boolean current;

        public RevisionCell(Context context) {
            super(context);
            setWillNotDraw(false);
            setPadding(dp(14), dp(4), dp(14), dp(4));

            content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(13), dp(9), dp(13), dp(7));

            final LinearLayout headerRow = new LinearLayout(context);
            headerRow.setOrientation(LinearLayout.HORIZONTAL);

            labelView = new TextView(context);
            labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            labelView.setTypeface(AndroidUtilities.bold());
            labelView.setSingleLine(true);
            headerRow.addView(labelView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

            dateView = new TextView(context);
            dateView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            dateView.setSingleLine(true);
            dateView.setGravity(LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT);
            headerRow.addView(dateView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));

            content.addView(headerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            mediaRow = new LinearLayout(context);
            mediaRow.setOrientation(LinearLayout.HORIZONTAL);
            mediaRow.setVisibility(GONE);

            thumbView = new BackupImageView(context);
            thumbView.setRoundRadius(dp(6));
            mediaRow.addView(thumbView, LayoutHelper.createLinear(48, 48, Gravity.CENTER_VERTICAL));

            mediaLabel = new TextView(context);
            mediaLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            mediaRow.addView(mediaLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 10, 0, 0, 0));

            content.addView(mediaRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 7, 0, 0));

            textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            textView.setLineSpacing(dp(1), 1f);
            content.addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));

            buttonsRow = new LinearLayout(context);
            buttonsRow.setOrientation(LinearLayout.HORIZONTAL);

            toggleButton = new TextView(context);
            toggleButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            toggleButton.setTypeface(AndroidUtilities.bold());
            toggleButton.setPadding(0, dp(7), dp(12), dp(3));
            toggleButton.setOnClickListener(v -> {
                if (item == null) {
                    return;
                }
                item.expanded = !item.expanded;
                bindText();
            });
            buttonsRow.addView(toggleButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL));

            final View spacer = new View(context);
            buttonsRow.addView(spacer, LayoutHelper.createLinear(0, 1, 1f));

            copyButton = new TextView(context);
            copyButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            copyButton.setTypeface(AndroidUtilities.bold());
            copyButton.setPadding(dp(12), dp(7), 0, dp(3));
            copyButton.setText(getString(R.string.AyuEditHistoryCopyVersion));
            copyButton.setOnClickListener(v -> copy(item));
            buttonsRow.addView(copyButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, Gravity.CENTER_VERTICAL));

            content.addView(buttonsRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        public void set(Item item) {
            this.item = item;
            this.current = item != null && item.revision != null && item.revision.current;
            if (item == null) {
                return;
            }
            labelView.setText(item.label);
            dateView.setText(item.date);

            if (item.previewPath != null) {
                mediaRow.setVisibility(VISIBLE);
                thumbView.setVisibility(VISIBLE);
                thumbView.setImage(item.previewPath, "80_80", (android.graphics.drawable.Drawable) null);
                mediaLabel.setText(getString(item.mediaReplaced ? R.string.AyuEditHistoryMediaReplaced : R.string.AyuEditHistoryOldMedia));
            } else if (item.mediaReplaced) {
                mediaRow.setVisibility(VISIBLE);
                thumbView.setVisibility(GONE);
                mediaLabel.setText(getString(R.string.AyuEditHistoryMediaReplaced));
            } else {
                mediaRow.setVisibility(GONE);
                thumbView.clearImage();
            }

            bindText();
            updateColors();
        }

        private void bindText() {
            if (item == null) {
                return;
            }
            final int deleteColor = Theme.getColor(Theme.key_text_RedRegular, resourcesProvider);
            final int insertColor = Theme.getColor(Theme.key_windowBackgroundWhiteGreenText, resourcesProvider);
            final int insertBg = ColorUtils.setAlphaComponent(insertColor, 40);
            final int equalColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
            CharSequence text = AyuDiffUtil.buildSpannable(item.diffOps, item.expanded, deleteColor, insertColor, insertBg, equalColor);
            if (text.length() == 0) {
                text = getString(R.string.AyuEditHistoryEmptyText);
            }
            textView.setText(text);

            if (item.canCollapse) {
                toggleButton.setVisibility(VISIBLE);
                toggleButton.setText(getString(item.expanded ? R.string.AyuEditHistoryShowChangesOnly : R.string.AyuEditHistoryShowFullText));
            } else {
                toggleButton.setVisibility(GONE);
            }
            copyButton.setVisibility(TextUtils.isEmpty(item.revision == null ? null : item.revision.text) ? GONE : VISIBLE);
            buttonsRow.setVisibility(toggleButton.getVisibility() == GONE && copyButton.getVisibility() == GONE ? GONE : VISIBLE);
        }

        private void updateColors() {
            labelView.setTextColor(Theme.getColor(current ? Theme.key_windowBackgroundWhiteBlueHeader : Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            dateView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            mediaLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            toggleButton.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider));
            copyButton.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider));
            backgroundPaint.setColor(Theme.getColor(Theme.key_graySection, resourcesProvider));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            rect.set(getPaddingLeft(), getPaddingTop(), getMeasuredWidth() - getPaddingRight(), getMeasuredHeight() - getPaddingBottom());
            canvas.drawRoundRect(rect, dp(14), dp(14), backgroundPaint);
        }
    }

    // ------------------------------------------------------------------ adapter

    private class Adapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final Context context = parent.getContext();
            View view;
            if (viewType == VIEW_TYPE_EMPTY) {
                final TextView textView = new TextView(context);
                textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                textView.setGravity(Gravity.CENTER);
                textView.setPadding(dp(20), dp(30), dp(20), dp(30));
                textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
                textView.setText(getString(loaded ? R.string.AyuEditHistoryEmpty : R.string.AyuEditHistoryLoading));
                view = textView;
            } else {
                view = new RevisionCell(context);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == VIEW_TYPE_REVISION && items != null && position >= 0 && position < items.size()) {
                ((RevisionCell) holder.itemView).set(items.get(position));
            }
        }

        @Override
        public int getItemViewType(int position) {
            return items == null || items.isEmpty() ? VIEW_TYPE_EMPTY : VIEW_TYPE_REVISION;
        }

        @Override
        public int getItemCount() {
            return items == null || items.isEmpty() ? 1 : items.size();
        }
    }
}
