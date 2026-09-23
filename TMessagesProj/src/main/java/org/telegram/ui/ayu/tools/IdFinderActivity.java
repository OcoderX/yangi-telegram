package org.telegram.ui.ayu.tools;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.DialogsActivity;

/**
 * OcoderX-gram: resolves a @username / phone number / t.me link (or a chat picked from the
 * dialogs list) to its Telegram numeric id, shows the resulting user/chat card and keeps a small
 * history of the last {@link OxToolsConfig#MAX_ID_HISTORY} lookups.
 */
public class IdFinderActivity extends BaseFragment {

    private EditTextBoldCursor inputField;

    private LinearLayout resultSection;
    private BackupImageView resultAvatar;
    private final AvatarDrawable resultAvatarDrawable = new AvatarDrawable();
    private TextSettingsCell resultTitleCell;
    private TextSettingsCell resultTypeCell;
    private TextSettingsCell resultIdCell;
    private TextSettingsCell resultChatIdCell;
    private TextSettingsCell resultUsernameCell;
    private TextSettingsCell resultDcCell;

    private LinearLayout historySection;
    private LinearLayout historyList;

    private Runnable pendingResolveCancel;

    @Override
    public View createView(Context context) {
        OxToolsConfig.load();

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.OxToolsIdFinderTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        // ---- search ----
        LinearLayout search = section(context);
        search.addView(header(context, getString(R.string.OxToolsIdFinderTitle)));

        inputField = new EditTextBoldCursor(context);
        inputField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        inputField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        inputField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        inputField.setHint(getString(R.string.OxToolsIdFinderHint));
        inputField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        inputField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        inputField.setSingleLine(true);
        inputField.setPadding(dp(16), dp(13), dp(16), dp(13));
        inputField.setCursorWidth(1.5f);
        inputField.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        inputField.setOnEditorActionListener((v, actionId, event) -> {
            onFindClicked();
            return true;
        });
        GradientDrawable fieldBackground = new GradientDrawable();
        fieldBackground.setCornerRadius(dp(10));
        fieldBackground.setColor(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), 0.06f));
        inputField.setBackground(fieldBackground);
        search.addView(inputField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 4, 16, 8));

        TextSettingsCell findCell = cell(context);
        findCell.setText(getString(R.string.OxToolsIdFinderFind), false);
        findCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        findCell.setOnClickListener(v -> onFindClicked());
        search.addView(findCell);

        TextSettingsCell pickChatCell = cell(context);
        pickChatCell.setText(getString(R.string.OxToolsIdFinderPickChat), true);
        pickChatCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        pickChatCell.setOnClickListener(v -> openChatPicker());
        search.addView(pickChatCell);

        TextSettingsCell myIdCell = cell(context);
        myIdCell.setText(getString(R.string.OxToolsIdFinderMyId), false);
        myIdCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        myIdCell.setOnClickListener(v -> showMyId());
        search.addView(myIdCell);

        root.addView(search, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        root.addView(spacer(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 12));

        // ---- result ----
        resultSection = section(context);
        resultSection.addView(header(context, getString(R.string.OxToolsIdFinderResult)));

        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        resultAvatar = new BackupImageView(context);
        resultAvatar.setRoundRadius(dp(23));
        titleRow.addView(resultAvatar, LayoutHelper.createLinear(46, 46, 16, 10, 12, 10));
        resultTitleCell = new TextSettingsCell(context);
        titleRow.addView(resultTitleCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 1f));
        resultTitleCell.setOnClickListener(v -> copyCellValue(resultTitleCell));
        resultSection.addView(titleRow);

        resultTypeCell = cell(context);
        resultIdCell = cell(context);
        resultChatIdCell = cell(context);
        resultUsernameCell = cell(context);
        resultDcCell = cell(context);
        for (TextSettingsCell c : new TextSettingsCell[]{resultTypeCell, resultIdCell, resultChatIdCell, resultUsernameCell, resultDcCell}) {
            c.setOnClickListener(v -> copyCellValue(c));
        }
        resultSection.addView(resultTypeCell);
        resultSection.addView(resultIdCell);
        resultSection.addView(resultChatIdCell);
        resultSection.addView(resultUsernameCell);
        resultSection.addView(resultDcCell);

        TextInfoPrivacyCell resultHint = new TextInfoPrivacyCell(context);
        resultHint.setText(getString(R.string.OxToolsIdFinderCopyHint));
        resultSection.addView(resultHint);

        resultSection.setVisibility(View.GONE);
        root.addView(resultSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        root.addView(spacer(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 12));

        // ---- history ----
        historySection = section(context);
        historySection.addView(header(context, getString(R.string.OxToolsIdFinderRecent)));
        historyList = new LinearLayout(context);
        historyList.setOrientation(LinearLayout.VERTICAL);
        historySection.addView(historyList);
        historySection.setVisibility(OxToolsConfig.idFinderHistory.isEmpty() ? View.GONE : View.VISIBLE);
        root.addView(historySection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        scrollView.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        fragmentView = scrollView;
        rebuildHistory(context);
        return fragmentView;
    }

    private LinearLayout section(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        return layout;
    }

    private View spacer(Context context) {
        return new View(context);
    }

    private HeaderCell header(Context context, String text) {
        HeaderCell cell = new HeaderCell(context);
        cell.setText(text);
        return cell;
    }

    private TextSettingsCell cell(Context context) {
        TextSettingsCell cell = new TextSettingsCell(context);
        cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        return cell;
    }

    // ------------------------------------------------------------------ search

    private void onFindClicked() {
        if (inputField == null) {
            return;
        }
        String raw = inputField.getText() != null ? inputField.getText().toString() : "";
        String cleaned = cleanInput(raw);
        if (TextUtils.isEmpty(cleaned)) {
            return;
        }
        AndroidUtilities.hideKeyboard(inputField);
        cancelPendingResolve();

        pendingResolveCancel = MessagesController.getInstance(currentAccount).getUserNameResolver().resolve(cleaned, peerId -> {
            pendingResolveCancel = null;
            if (peerId == null || peerId == Long.MAX_VALUE) {
                showNotFound();
                return;
            }
            showResultForPeer(peerId);
        });
    }

    private void cancelPendingResolve() {
        if (pendingResolveCancel != null) {
            pendingResolveCancel.run();
            pendingResolveCancel = null;
        }
    }

    private static String cleanInput(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            return s;
        }
        s = s.replaceFirst("(?i)^https?://", "");
        s = s.replaceFirst("(?i)^(www\\.)?(t\\.me|telegram\\.me)/", "");
        if (s.startsWith("@")) {
            s = s.substring(1);
        }
        if (s.startsWith("+")) {
            s = s.substring(1);
        }
        int cut = s.length();
        for (char stop : new char[]{'?', '#', '/'}) {
            int idx = s.indexOf(stop);
            if (idx >= 0) {
                cut = Math.min(cut, idx);
            }
        }
        return s.substring(0, cut).trim();
    }

    private void openChatPicker() {
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_DEFAULT);
        args.putBoolean("allowGlobalSearch", true);
        DialogsActivity dialogsActivity = new DialogsActivity(args);
        dialogsActivity.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            fragment.finishFragment();
            if (dids != null && !dids.isEmpty()) {
                showResultForPeer(dids.get(0).dialogId);
            }
            return true;
        });
        presentFragment(dialogsActivity);
    }

    private void showMyId() {
        TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
        if (me != null) {
            showUserResult(me, false);
        }
    }

    // ------------------------------------------------------------------ result

    private void showResultForPeer(long peerId) {
        if (peerId >= 0) {
            TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(peerId);
            if (user == null) {
                showNotFound();
                return;
            }
            showUserResult(user, true);
        } else {
            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-peerId);
            if (chat == null) {
                showNotFound();
                return;
            }
            showChatResult(chat);
        }
    }

    private void showUserResult(TLRPC.User user, boolean addToHistory) {
        if (resultSection == null) {
            return;
        }
        resultAvatarDrawable.setInfo(user);
        resultAvatar.setForUserOrChat(user, resultAvatarDrawable);

        String title = ContactsController.formatName(user.first_name, user.last_name);
        String typeText = getString(user.bot ? R.string.OxToolsTypeBot : R.string.OxToolsTypeUser);
        String idText = String.valueOf(user.id);
        String username = user.username != null && user.username.length() > 0 ? "@" + user.username : "—";
        String dc = user.photo != null && user.photo.dc_id > 0 ? String.valueOf(user.photo.dc_id) : "—";

        resultTitleCell.setText(title, false);
        resultTypeCell.setTextAndValue(getString(R.string.OxToolsResultType), typeText, true, true);
        resultIdCell.setTextAndValue(getString(R.string.OxToolsResultId), idText, true, true);
        resultChatIdCell.setVisibility(View.GONE);
        resultUsernameCell.setTextAndValue(getString(R.string.OxToolsResultUsername), username, true, true);
        resultDcCell.setTextAndValue(getString(R.string.OxToolsResultDc), dc, true, false);
        resultSection.setVisibility(View.VISIBLE);

        if (addToHistory) {
            OxToolsConfig.addIdFinderHistory(user.bot ? "bot" : "user", user.id, title, user.username);
            rebuildHistory(getContext());
        }
    }

    private void showChatResult(TLRPC.Chat chat) {
        if (resultSection == null) {
            return;
        }
        resultAvatarDrawable.setInfo(chat);
        resultAvatar.setForUserOrChat(chat, resultAvatarDrawable);

        String title = chat.title != null ? chat.title : "";
        String typeKey;
        String typeText;
        if (ChatObject.isChannelAndNotMegaGroup(chat)) {
            typeKey = "channel";
            typeText = getString(R.string.OxToolsTypeChannel);
        } else if (chat.megagroup) {
            typeKey = "supergroup";
            typeText = getString(R.string.OxToolsTypeSupergroup);
        } else {
            typeKey = "group";
            typeText = getString(R.string.OxToolsTypeGroup);
        }
        String idText = String.valueOf(chat.id);
        String chatIdText = (ChatObject.isChannel(chat) ? "-100" : "-") + chat.id;
        String username = chat.username != null && chat.username.length() > 0 ? "@" + chat.username : "—";
        String dc = chat.photo != null && chat.photo.dc_id > 0 ? String.valueOf(chat.photo.dc_id) : "—";

        resultTitleCell.setText(title, false);
        resultTypeCell.setTextAndValue(getString(R.string.OxToolsResultType), typeText, true, true);
        resultIdCell.setTextAndValue(getString(R.string.OxToolsResultId), idText, true, true);
        resultChatIdCell.setTextAndValue(getString(R.string.OxToolsResultChatId), chatIdText, true, true);
        resultChatIdCell.setVisibility(View.VISIBLE);
        resultUsernameCell.setTextAndValue(getString(R.string.OxToolsResultUsername), username, true, true);
        resultDcCell.setTextAndValue(getString(R.string.OxToolsResultDc), dc, true, false);
        resultSection.setVisibility(View.VISIBLE);

        OxToolsConfig.addIdFinderHistory(typeKey, chat.id, title, chat.username);
        rebuildHistory(getContext());
    }

    private void showNotFound() {
        if (BulletinFactory.canShowBulletin(this)) {
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.OxToolsIdFinderNotFound)).show();
        }
    }

    private void copyCellValue(TextSettingsCell cell) {
        if (cell == null) {
            return;
        }
        CharSequence value = cell.getValueTextView() != null ? cell.getValueTextView().getText() : null;
        if (TextUtils.isEmpty(value)) {
            value = cell.getTextView() != null ? cell.getTextView().getText() : null;
        }
        if (TextUtils.isEmpty(value) || "—".contentEquals(value)) {
            return;
        }
        AndroidUtilities.addToClipboard(value);
        if (BulletinFactory.canShowBulletin(this)) {
            BulletinFactory.of(this).createCopyBulletin(getString(R.string.OxToolsCopied)).show();
        }
    }

    // ------------------------------------------------------------------ history

    private void rebuildHistory(Context context) {
        if (historyList == null || context == null) {
            return;
        }
        historyList.removeAllViews();
        for (OxToolsConfig.IdFinderEntry entry : OxToolsConfig.idFinderHistory) {
            TextSettingsCell row = cell(context);
            String typeText = typeTextFor(entry.type);
            String label = entry.title != null && entry.title.length() > 0 ? entry.title : typeText;
            row.setTextAndValue(label, String.valueOf(entry.id), true, true);
            row.setOnClickListener(v -> {
                boolean isUser = "user".equals(entry.type) || "bot".equals(entry.type);
                showResultForPeer(isUser ? entry.id : -entry.id);
            });
            historyList.addView(row);
        }
        historySection.setVisibility(OxToolsConfig.idFinderHistory.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private String typeTextFor(String typeKey) {
        if ("bot".equals(typeKey)) {
            return getString(R.string.OxToolsTypeBot);
        } else if ("group".equals(typeKey)) {
            return getString(R.string.OxToolsTypeGroup);
        } else if ("supergroup".equals(typeKey)) {
            return getString(R.string.OxToolsTypeSupergroup);
        } else if ("channel".equals(typeKey)) {
            return getString(R.string.OxToolsTypeChannel);
        }
        return getString(R.string.OxToolsTypeUser);
    }

    @Override
    public void onFragmentDestroy() {
        cancelPendingResolve();
        super.onFragmentDestroy();
    }
}
