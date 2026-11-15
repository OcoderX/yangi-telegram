package org.telegram.ui.ayu.hidden;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FlagSecureReason;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.ayu.hidden.HiddenChatsController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.DialogsActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

/** Cached, separate dialog list that is never populated while the vault is locked. */
public final class HiddenChatsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private final HiddenChatsController controller;
    private final ArrayList<Row> rows = new ArrayList<>();
    private RecyclerListView list;
    private Adapter adapter;
    private FlagSecureReason secure;
    private boolean destroyed;
    private boolean biometricEnabled;
    private final HashSet<Long> hydrationAttempted = new HashSet<>();
    private long[] hydratedMembership = new long[0];
    private boolean hydrationInFlight;
    private int hydrationGeneration;
    private final Runnable refresh = this::refreshRows;
    private final Runnable requestUnlock = this::ensureUnlocked;

    public HiddenChatsActivity(int account) {
        setCurrentAccount(account);
        controller = HiddenChatsController.getInstance(account);
    }

    @Override public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.dialogsNeedReload);
        getNotificationCenter().addObserver(this, NotificationCenter.updateInterfaces);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.ayuConfigChanged);
        return super.onFragmentCreate();
    }

    @Override public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(getString(R.string.OxHiddenTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });
        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        fragmentView = frame;
        list = new RecyclerListView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(adapter = new Adapter());
        list.setVisibility(View.INVISIBLE);
        frame.addView(list, LayoutHelper.createFrame(-1, -1));
        list.setOnItemClickListener((view, position) -> {
            if (!allowed()) return;
            if (position == 0) openPicker();
            else if (position == 1) {
                controller.lock();
                conceal();
                finishFragment();
            } else if (position == 2) showSettings();
            else if (position >= 4 && position - 4 < rows.size()) openChat(rows.get(position - 4).id);
        });
        list.setOnItemLongClickListener((view, position) -> {
            if (!allowed() || position < 4 || position - 4 >= rows.size()) return false;
            HiddenChatsUi.unhideChat(this, rows.get(position - 4).id, this::refreshRows);
            return true;
        });
        if (secure != null) secure.detach();
        secure = new FlagSecureReason(getParentActivity().getWindow(), () -> true, true);
        secure.attach();
        return fragmentView;
    }

    private boolean allowed() {
        return !destroyed && !isFinished && currentAccount == UserConfig.selectedAccount && controller.isUnlocked();
    }

    @Override public void onResume() {
        super.onResume();
        ensureUnlocked();
    }

    @Override public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        super.onTransitionAnimationEnd(isOpen, backward);
        if (isOpen) ensureUnlocked();
    }

    @Override public void onPause() {
        conceal();
        super.onPause();
    }

    private void ensureUnlocked() {
        if (destroyed || isFinished || isPaused() || ApplicationLoader.mainInterfacePaused || getParentActivity() == null) return;
        if (currentAccount != UserConfig.selectedAccount || !controller.isConfigured()) {
            conceal();
            finishFragment();
        } else if (!controller.isUnlocked()) {
            conceal();
            HiddenChatsUi.authorize(this, this::refreshRows, this::finishFragment);
        } else {
            refreshRows();
        }
    }

    private void conceal() {
        if (list != null) list.setVisibility(View.INVISIBLE);
    }

    private void refreshRows() {
        if (!allowed()) {
            conceal();
            rows.clear();
            resetHydration();
            if (adapter != null) adapter.notifyDataSetChanged();
            return;
        }
        rows.clear();
        long[] ids = controller.getHiddenDialogIds();
        if (!Arrays.equals(ids, hydratedMembership)) {
            hydratedMembership = ids;
            hydrationAttempted.clear();
        }
        ArrayList<Long> missing = new ArrayList<>();
        for (long id : ids) {
            String title;
            if (DialogObject.isEncryptedDialog(id)) {
                TLRPC.EncryptedChat encrypted = getMessagesController().getEncryptedChat(DialogObject.getEncryptedChatId(id));
                title = encrypted == null ? "" : DialogObject.getDialogTitle(getMessagesController().getUser(encrypted.user_id));
            } else {
                title = DialogObject.getDialogTitle(id > 0 ? getMessagesController().getUser(id) : getMessagesController().getChat(-id));
            }
            if (title == null || title.length() == 0) {
                title = getString(R.string.OxHiddenUnavailableChat);
                if (!hydrationAttempted.contains(id)) missing.add(id);
            }
            rows.add(new Row(id, title));
        }
        hydrateMissingPeers(missing);
        biometricEnabled = controller.isBiometricEnabled();
        if (adapter != null) adapter.notifyDataSetChanged();
        if (list != null && !isPaused()) list.setVisibility(View.VISIBLE);
    }

    private void resetHydration() {
        hydrationGeneration++;
        hydrationInFlight = false;
        hydrationAttempted.clear();
        hydratedMembership = new long[0];
    }

    private void hydrateMissingPeers(ArrayList<Long> missing) {
        if (missing.isEmpty() || hydrationInFlight || !allowed()) return;
        hydrationAttempted.addAll(missing);
        hydrationInFlight = true;
        final int generation = hydrationGeneration;
        final long owner = getUserConfig().getClientUserId();
        final MessagesStorage storage = getMessagesStorage();
        final ArrayList<Long> usersToLoad = new ArrayList<>();
        final ArrayList<Long> chatsToLoad = new ArrayList<>();
        final ArrayList<Integer> encryptedToLoad = new ArrayList<>();
        for (long id : missing) {
            if (DialogObject.isEncryptedDialog(id)) encryptedToLoad.add(DialogObject.getEncryptedChatId(id));
            else if (id > 0) usersToLoad.add(id);
            else chatsToLoad.add(-id);
        }
        storage.getStorageQueue().postRunnable(() -> {
            ArrayList<TLRPC.User> users = new ArrayList<>();
            ArrayList<TLRPC.Chat> chats = new ArrayList<>();
            ArrayList<TLRPC.EncryptedChat> encrypted = new ArrayList<>();
            try {
                if (!encryptedToLoad.isEmpty()) storage.getEncryptedChatsInternal(TextUtils.join(",", encryptedToLoad), encrypted, usersToLoad);
                if (!usersToLoad.isEmpty()) storage.getUsersInternal(usersToLoad, users);
                if (!chatsToLoad.isEmpty()) storage.getChatsInternal(TextUtils.join(",", chatsToLoad), chats);
            } catch (Exception ignored) {
                // A missing/deleted local peer remains removable through the placeholder row.
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed || generation != hydrationGeneration) return;
                hydrationInFlight = false;
                if (!allowed() || getUserConfig().getClientUserId() != owner) return;
                getMessagesController().putUsers(users, true);
                getMessagesController().putChats(chats, true);
                for (TLRPC.EncryptedChat chat : encrypted) getMessagesController().putEncryptedChat(chat, true);
                refreshRows();
            });
        });
    }

    private void openChat(long id) {
        if (!allowed() || !controller.isHidden(id)) return;
        Bundle args = new Bundle();
        if (DialogObject.isEncryptedDialog(id)) args.putInt("enc_id", DialogObject.getEncryptedChatId(id));
        else if (id > 0) args.putLong("user_id", id);
        else args.putLong("chat_id", -id);
        args.putBoolean(HiddenChatsUi.AUTHORIZED_ARGUMENT, true);
        if (getMessagesController().checkCanOpenChat(args, this)) {
            ChatActivity chat = new ChatActivity(args);
            chat.setCurrentAccount(currentAccount);
            presentFragment(chat);
        }
    }

    private void openPicker() {
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_DEFAULT);
        args.putBoolean("allowGlobalSearch", false);
        args.putBoolean("allowSwitchAccount", false);
        DialogsActivity picker = new DialogsActivity(args);
        picker.setCurrentAccount(currentAccount);
        picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids == null || dids.isEmpty() || destroyed || currentAccount != UserConfig.selectedAccount) return false;
            long id = dids.get(0).dialogId;
            HiddenChatsUi.hideChat(fragment, id, fragment::finishFragment);
            // Confirmation is asynchronous; keep the delegate for another choice after Cancel.
            return false;
        });
        presentFragment(picker);
    }

    private void showSettings() {
        if (!allowed()) return;
        final boolean nextBiometric = !biometricEnabled;
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(getString(R.string.OxHiddenSettings))
                .setItems(new CharSequence[]{getString(R.string.OxHiddenChangePassword),
                        getString(nextBiometric ? R.string.OxHiddenEnableBiometric : R.string.OxHiddenDisableBiometric),
                        getString(R.string.OxHiddenDisable)}, (dialog, index) -> {
                    if (!allowed()) return;
                    if (index == 0) HiddenChatsUi.changePassword(this);
                    else if (index == 1) HiddenChatsUi.setBiometric(this, nextBiometric, this::refreshRows);
                    else HiddenChatsUi.disable(this, this::finishFragment);
                }).setNegativeButton(getString(R.string.Cancel), null).create());
    }

    @Override public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.ayuConfigChanged && account != currentAccount) return;
        if (!controller.isUnlocked()) {
            conceal();
            rows.clear();
            resetHydration();
            if (adapter != null) adapter.notifyDataSetChanged();
        }
        AndroidUtilities.cancelRunOnUIThread(refresh);
        AndroidUtilities.runOnUIThread(refresh, 100);
        if (!controller.isUnlocked() && !isPaused() && !ApplicationLoader.mainInterfacePaused) {
            AndroidUtilities.cancelRunOnUIThread(requestUnlock);
            AndroidUtilities.runOnUIThread(requestUnlock, 200);
        }
    }

    @Override public void onFragmentDestroy() {
        destroyed = true;
        resetHydration();
        HiddenChatsUi.cancelAuthorization(this);
        controller.lock();
        AndroidUtilities.cancelRunOnUIThread(refresh);
        AndroidUtilities.cancelRunOnUIThread(requestUnlock);
        getNotificationCenter().removeObserver(this, NotificationCenter.dialogsNeedReload);
        getNotificationCenter().removeObserver(this, NotificationCenter.updateInterfaces);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.ayuConfigChanged);
        if (secure != null) secure.detach();
        rows.clear();
        super.onFragmentDestroy();
    }

    private static final class Row {
        final long id;
        final String title;
        final String accessibilityTitle;
        Row(long id, String title) {
            this.id = id;
            this.title = title;
            accessibilityTitle = title + ", " + getString(R.string.OxHiddenUnhideHint);
        }
    }

    private final class Adapter extends RecyclerListView.SelectionAdapter {
        @Override public int getItemCount() { return 4 + rows.size(); }
        @Override public int getItemViewType(int position) { return position == 3 ? 1 : 0; }
        @Override public boolean isEnabled(RecyclerView.ViewHolder holder) { return holder.getItemViewType() == 0; }
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            View view;
            if (type == 1) view = new TextInfoPrivacyCell(parent.getContext());
            else {
                view = new TextCell(parent.getContext());
                view.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            return new RecyclerListView.Holder(view);
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (position == 3) {
                ((TextInfoPrivacyCell) holder.itemView).setText(getString(rows.isEmpty() ? R.string.OxHiddenEmpty : R.string.OxHiddenListInfo));
            } else {
                TextCell cell = (TextCell) holder.itemView;
                String title = position == 0 ? getString(R.string.OxHiddenAddChat) : position == 1 ? getString(R.string.OxHiddenLock)
                        : position == 2 ? getString(R.string.OxHiddenSettings) : rows.get(position - 4).title;
                cell.setText(title, position != getItemCount() - 1);
                cell.setContentDescription(position >= 4 ? rows.get(position - 4).accessibilityTitle : title);
            }
        }
    }
}
