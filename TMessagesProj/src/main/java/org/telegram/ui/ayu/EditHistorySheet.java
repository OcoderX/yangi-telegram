package org.telegram.ui.ayu;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.ayu.AyuMessagesController;
import org.telegram.messenger.ayu.entities.EditedMessage;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BottomSheetWithRecyclerListView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * AyuGram: bottom sheet listing every stored revision of a message (oldest first, current version
 * last). Tapping a revision copies its text.
 */
public class EditHistorySheet extends BottomSheetWithRecyclerListView {

    private static final int VIEW_TYPE_REVISION = 0;
    private static final int VIEW_TYPE_EMPTY = 1;

    private static class Revision {
        CharSequence label;
        CharSequence text;
        CharSequence date;
        boolean current;
    }

    private final ArrayList<Revision> revisions;

    {
        revisions = new ArrayList<>();
    }

    public EditHistorySheet(BaseFragment fragment, int currentAccount, MessageObject messageObject) {
        super(fragment, false, false);
        load(currentAccount, messageObject);
        if (recyclerListView != null) {
            recyclerListView.setOnItemClickListener((view, position) -> {
                int index = position - 1;
                if (index < 0 || index >= revisions.size()) {
                    return;
                }
                Revision revision = revisions.get(index);
                if (revision.text == null) {
                    return;
                }
                AndroidUtilities.addToClipboard(revision.text.toString());
                try {
                    BulletinFactory.of(container, resourcesProvider)
                            .createSimpleBulletin(R.raw.copy, getString(R.string.AyuCopiedToClipboard))
                            .show();
                } catch (Exception e) {
                    FileLog.e(e);
                }
            });
        }
        notifyDataSetChanged();
        fixNavigationBar();
    }

    private void load(int currentAccount, MessageObject messageObject) {
        revisions.clear();
        if (messageObject == null || messageObject.messageOwner == null) {
            return;
        }
        final long dialogId = messageObject.getDialogId();
        List<EditedMessage> rows = null;
        try {
            rows = AyuMessagesController.getInstance().getRevisions(currentAccount, dialogId, messageObject.getId());
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (rows != null) {
            for (int a = 0; a < rows.size(); a++) {
                EditedMessage row = rows.get(a);
                if (row == null) {
                    continue;
                }
                Revision revision = new Revision();
                revision.label = a == 0
                        ? getString(R.string.AyuEditHistoryOriginal)
                        : LocaleController.formatString(R.string.AyuEditHistoryRevision, a);
                CharSequence text = row.text;
                if (text == null || text.length() == 0) {
                    TLRPC.Message tl = null;
                    try {
                        tl = AyuMessagesController.getInstance().toTLMessage(row);
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    if (tl != null) {
                        text = tl.message;
                    }
                }
                revision.text = text;
                revision.date = formatDate(row.editDate != 0 ? row.editDate : row.date);
                revisions.add(revision);
            }
        }

        Revision current = new Revision();
        current.label = getString(R.string.AyuEditHistoryCurrent);
        current.current = true;
        CharSequence text = messageObject.messageOwner.message;
        if (text == null || text.length() == 0) {
            text = messageObject.caption;
        }
        current.text = text;
        current.date = formatDate(messageObject.messageOwner.edit_date != 0 ? messageObject.messageOwner.edit_date : messageObject.messageOwner.date);
        revisions.add(current);
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

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.AyuEditHistory);
    }

    @Override
    protected RecyclerListView.SelectionAdapter createAdapter(RecyclerListView listView) {
        return new Adapter();
    }

    private class RevisionCell extends FrameLayout {

        private final LinearLayout content;
        private final TextView labelView;
        private final TextView textView;
        private final TextView dateView;
        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private boolean current;

        public RevisionCell(Context context) {
            super(context);
            setWillNotDraw(false);
            setPadding(dp(14), dp(4), dp(14), dp(4));

            content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(13), dp(9), dp(13), dp(9));

            labelView = new TextView(context);
            labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            labelView.setTypeface(AndroidUtilities.bold());
            content.addView(labelView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            content.addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

            dateView = new TextView(context);
            dateView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            dateView.setGravity(LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT);
            content.addView(dateView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 5, 0, 0));

            addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        public void set(Revision revision) {
            current = revision.current;
            labelView.setText(revision.label);
            CharSequence text = revision.text;
            if (text == null || text.length() == 0) {
                text = getString(R.string.AyuEditHistoryEmptyText);
            }
            textView.setText(text);
            dateView.setText(revision.date);
            updateColors();
        }

        private void updateColors() {
            labelView.setTextColor(Theme.getColor(current ? Theme.key_windowBackgroundWhiteBlueHeader : Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            dateView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            backgroundPaint.setColor(Theme.getColor(Theme.key_graySection, resourcesProvider));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            rect.set(getPaddingLeft(), getPaddingTop(), getMeasuredWidth() - getPaddingRight(), getMeasuredHeight() - getPaddingBottom());
            canvas.drawRoundRect(rect, dp(14), dp(14), backgroundPaint);
        }
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == VIEW_TYPE_REVISION;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final Context context = parent.getContext();
            View view;
            if (viewType == VIEW_TYPE_EMPTY) {
                TextView textView = new TextView(context);
                textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                textView.setGravity(Gravity.CENTER);
                textView.setPadding(dp(20), dp(30), dp(20), dp(30));
                textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
                textView.setText(getString(R.string.AyuEditHistoryEmpty));
                view = textView;
            } else {
                view = new RevisionCell(context);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == VIEW_TYPE_REVISION && revisions != null && position >= 0 && position < revisions.size()) {
                ((RevisionCell) holder.itemView).set(revisions.get(position));
            }
        }

        @Override
        public int getItemViewType(int position) {
            return revisions == null || revisions.isEmpty() ? VIEW_TYPE_EMPTY : VIEW_TYPE_REVISION;
        }

        @Override
        public int getItemCount() {
            return revisions == null || revisions.isEmpty() ? 1 : revisions.size();
        }
    }
}
