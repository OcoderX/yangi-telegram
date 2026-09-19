package org.telegram.ui.ayu.explorer;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

/**
 * AyuGram File Explorer: a "folder" row — one chat (with its avatar) or one file type
 * (with a tinted circle icon), plus the number of files and their total size.
 */
public class FileExplorerFolderCell extends FrameLayout {

    private final Theme.ResourcesProvider resourcesProvider;

    private final BackupImageView imageView;
    private final AvatarDrawable avatarDrawable = new AvatarDrawable();
    private final TextView titleTextView;
    private final TextView subtitleTextView;

    private boolean needDivider;

    public FileExplorerFolderCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;

        imageView = new BackupImageView(context);
        imageView.setRoundRadius(dp(23));
        addView(imageView, LayoutHelper.createFrame(46, 46, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, LocaleController.isRTL ? 0 : 10, 7, LocaleController.isRTL ? 10 : 0, 0));

        titleTextView = new TextView(context);
        titleTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        titleTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleTextView.setTypeface(AndroidUtilities.bold());
        titleTextView.setLines(1);
        titleTextView.setMaxLines(1);
        titleTextView.setSingleLine(true);
        titleTextView.setEllipsize(TextUtils.TruncateAt.END);
        titleTextView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        addView(titleTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, LocaleController.isRTL ? 16 : 72, 10, LocaleController.isRTL ? 72 : 16, 0));

        subtitleTextView = new TextView(context);
        subtitleTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3, resourcesProvider));
        subtitleTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitleTextView.setLines(1);
        subtitleTextView.setMaxLines(1);
        subtitleTextView.setSingleLine(true);
        subtitleTextView.setEllipsize(TextUtils.TruncateAt.END);
        subtitleTextView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        addView(subtitleTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, LocaleController.isRTL ? 16 : 72, 32, LocaleController.isRTL ? 72 : 16, 0));
    }

    public void setDialog(int currentAccount, long dialogId, CharSequence subtitle, boolean divider) {
        needDivider = divider;
        setWillNotDraw(!divider);

        TLObject object = null;
        if (dialogId == 0) {
            avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_OTHER_CHATS);
            titleTextView.setText(FileExplorerUtils.getDialogTitle(currentAccount, 0));
            imageView.setImage(null, null, avatarDrawable, null);
        } else {
            if (DialogObject.isUserDialog(dialogId)) {
                final TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
                if (user != null) {
                    if (UserConfig.getInstance(currentAccount).getClientUserId() == dialogId) {
                        avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_SAVED);
                        imageView.setImage(null, null, avatarDrawable, user);
                    } else {
                        avatarDrawable.setInfo(currentAccount, user);
                        imageView.setForUserOrChat(user, avatarDrawable);
                    }
                    object = user;
                }
            } else {
                final TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
                if (chat != null) {
                    avatarDrawable.setInfo(currentAccount, chat);
                    imageView.setForUserOrChat(chat, avatarDrawable);
                    object = chat;
                }
            }
            if (object == null) {
                avatarDrawable.setInfo(dialogId, null, null);
                imageView.setImage(null, null, avatarDrawable, null);
            }
            titleTextView.setText(FileExplorerUtils.getDialogTitle(currentAccount, dialogId));
        }
        imageView.setRoundRadius(dp(23));
        subtitleTextView.setText(subtitle);
    }

    public void setCategory(CharSequence title, CharSequence subtitle, int iconRes, int colorKey, boolean divider) {
        needDivider = divider;
        setWillNotDraw(!divider);
        final Drawable drawable = Theme.createCircleDrawableWithIcon(dp(46), iconRes);
        Theme.setCombinedDrawableColor(drawable, Theme.getColor(colorKey, resourcesProvider), false);
        Theme.setCombinedDrawableColor(drawable, Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider), true);
        imageView.setImageDrawable(drawable);
        titleTextView.setText(title);
        subtitleTextView.setText(subtitle);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(60) + (needDivider ? 1 : 0), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (needDivider) {
            canvas.drawLine(
                    LocaleController.isRTL ? 0 : dp(72),
                    getMeasuredHeight() - 1,
                    getMeasuredWidth() - (LocaleController.isRTL ? dp(72) : 0),
                    getMeasuredHeight() - 1,
                    Theme.dividerPaint);
        }
    }
}
