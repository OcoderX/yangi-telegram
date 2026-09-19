package org.telegram.ui.ayu;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.ayu.AyuConfig;
import org.telegram.messenger.ayu.AyuConstants;
import org.telegram.messenger.ayu.AyuMessagesController;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextCheckCell2;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.Locale;

/**
 * AyuGram main preferences screen.
 */
public class AyuPreferencesActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {

    // ---- ghost ----
    private static final int GHOST_MASTER = 1;
    private static final int GHOST_READ = 2;
    private static final int GHOST_STORIES = 3;
    private static final int GHOST_ONLINE = 4;
    private static final int GHOST_TYPING = 5;
    private static final int GHOST_OFFLINE_AFTER_ONLINE = 6;
    private static final int GHOST_MORE = 7;

    // ---- spy ----
    private static final int SAVE_DELETED = 10;
    private static final int SAVE_HISTORY = 11;
    private static final int MESSAGE_SAVING = 12;

    // ---- filters ----
    private static final int REGEX_FILTERS = 20;

    // ---- qol ----
    private static final int QOL_KEEP_ALIVE = 30;
    private static final int QOL_DISABLE_ADS = 31;
    private static final int QOL_DISABLE_PROXY_SPONSOR = 32;
    private static final int QOL_HIDE_STORIES = 33;
    private static final int QOL_KEEP_KICKED = 34;
    private static final int QOL_SECRET_SCREENSHOTS = 35;
    private static final int QOL_EMULATOR_DETECTION = 36;
    private static final int QOL_EXPIRE_BUTTON = 37;
    private static final int QOL_CRASHLYTICS = 38;

    // ---- customization ----
    private static final int CUSTOM_DELETED_MARK = 40;
    private static final int CUSTOM_EDITED_MARK = 41;
    private static final int CUSTOM_GHOST_TOGGLE = 42;
    private static final int CUSTOM_KILL_BUTTON = 43;
    private static final int CUSTOM_PEER_ID = 44;
    private static final int CUSTOM_MESSAGE_SECONDS = 45;
    private static final int CUSTOM_MESSAGE_DETAILS = 46;
    private static final int CUSTOM_MESSAGE_SHADOW = 47;
    private static final int CUSTOM_SIMPLE_QUOTES = 48;

    // ---- sync ----
    private static final int SYNC = 50;
    private static final int DYNAMIC_ISLAND = 55;
    private static final int FILE_EXPLORER = 56;
    private static final int DUPLICATE_CLEANER = 57;
    private static final int FIREWALL = 58;
    private static final int MENTION_RADAR = 59;
    // 60..63 are taken by the debug section below
    private static final int ZERO_REUPLOAD = 70;
    private static final int NETWORK_DIAGNOSTICS = 71;

    // ---- debug ----
    private static final int DEBUG_WAL = 60;
    private static final int DEBUG_CLEAR_AYU_DB = 61;
    private static final int DEBUG_ERASE_TG_DB = 62;
    private static final int DEBUG_KILL_APP = 63;

    private boolean ghostExpanded;
    private long ayuDatabaseSize;

    public AyuPreferencesActivity() {
        super();
        AyuConfig.load();
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.ayuGhostModeChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.ayuConfigChanged);
        reloadDatabaseSize();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.ayuGhostModeChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.ayuConfigChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.ayuGhostModeChanged || id == NotificationCenter.ayuConfigChanged) {
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
        }
    }

    @Override
    public View createView(Context context) {
        super.createView(context);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        reloadDatabaseSize();
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void reloadDatabaseSize() {
        Utilities.globalQueue.postRunnable(() -> {
            long size;
            try {
                size = AyuMessagesController.getInstance().getDatabaseSize();
            } catch (Throwable e) {
                size = 0;
            }
            final long finalSize = size;
            AndroidUtilities.runOnUIThread(() -> {
                ayuDatabaseSize = finalSize;
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
            });
        });
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.AyuPreferences);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        // ------------- ghost -------------
        items.add(UItem.asHeader(getString(R.string.AyuGhostEssentials)));
        items.add(UItem.asExpandableSwitch(GHOST_MASTER, getString(R.string.AyuGhostMode),
                        String.format(Locale.US, "%d/5", AyuConfig.getGhostModeSelectedCount()))
                .setChecked(AyuConfig.isGhostModeActive())
                .setCollapsed(!ghostExpanded)
                .setClickCallback(v -> {
                    AyuConfig.toggleGhostMode();
                    if (v instanceof TextCheckCell2) {
                        ((TextCheckCell2) v).setChecked(AyuConfig.isGhostModeActive());
                    }
                    if (listView != null && listView.adapter != null) {
                        listView.adapter.update(true);
                    }
                    onConfigChanged();
                }));
        if (ghostExpanded) {
            items.add(UItem.asRoundCheckbox(GHOST_READ, getString(R.string.AyuGhostDontReadMessages))
                    .setChecked(!AyuConfig.sendReadPackets).setPad(1));
            items.add(UItem.asRoundCheckbox(GHOST_STORIES, getString(R.string.AyuGhostDontReadStories))
                    .setChecked(!AyuConfig.sendReadStories).setPad(1));
            items.add(UItem.asRoundCheckbox(GHOST_ONLINE, getString(R.string.AyuGhostDontSendOnline))
                    .setChecked(!AyuConfig.sendOnlinePackets).setPad(1));
            items.add(UItem.asRoundCheckbox(GHOST_TYPING, getString(R.string.AyuGhostDontSendTyping))
                    .setChecked(!AyuConfig.sendUploadProgress).setPad(1));
            items.add(UItem.asRoundCheckbox(GHOST_OFFLINE_AFTER_ONLINE, getString(R.string.AyuGhostSendOfflineAfterOnline))
                    .setChecked(AyuConfig.sendOfflinePacketAfterOnline).setPad(1));
        }
        items.add(UItem.asSettingsCell(GHOST_MORE, 0, getString(R.string.AyuGhostSettings)));
        items.add(UItem.asShadow(getString(R.string.AyuGhostModeInfo)));

        // ------------- spy -------------
        items.add(UItem.asHeader(getString(R.string.AyuSpyEssentials)));
        items.add(UItem.asCheck(SAVE_DELETED, getString(R.string.AyuSaveDeletedMessages))
                .setChecked(AyuConfig.saveDeletedMessages));
        items.add(UItem.asCheck(SAVE_HISTORY, getString(R.string.AyuSaveMessagesHistory))
                .setChecked(AyuConfig.saveMessagesHistory));
        items.add(UItem.asSettingsCell(MESSAGE_SAVING, 0, getString(R.string.AyuMessageSaving)));
        items.add(UItem.asShadow(getString(R.string.AyuSpyEssentialsInfo)));

        // ------------- filters -------------
        items.add(UItem.asHeader(getString(R.string.AyuFilters)));
        items.add(UItem.asSettingsCell(REGEX_FILTERS, 0, getString(R.string.AyuRegexFilters),
                getString(AyuConfig.regexFiltersEnabled ? R.string.AyuEnabled : R.string.AyuDisabled)));
        items.add(UItem.asShadow(getString(R.string.AyuRegexFiltersInfo)));

        // ------------- mention radar -------------
        final int radarUnread = org.telegram.messenger.ayu.radar.MentionRadar.getInstance(currentAccount).getUnreadCount();
        items.add(UItem.asHeader(getString(R.string.RadarTitle)));
        items.add(UItem.asSettingsCell(MENTION_RADAR, 0, getString(R.string.RadarTitle),
                radarUnread > 0 ? LocaleController.formatString(R.string.RadarUnread, radarUnread)
                        : getString(org.telegram.messenger.ayu.radar.RadarConfig.enabled ? R.string.RadarStateOn : R.string.RadarStateOff)));
        items.add(UItem.asShadow(getString(R.string.RadarHubInfo)));

        // ------------- quality of life -------------
        items.add(UItem.asHeader(getString(R.string.AyuQualityOfLife)));
        items.add(UItem.asCheck(QOL_KEEP_ALIVE, getString(R.string.AyuKeepAliveService)).setChecked(AyuConfig.keepAliveService));
        items.add(UItem.asCheck(QOL_DISABLE_ADS, getString(R.string.AyuDisableAds)).setChecked(AyuConfig.disableAds));
        items.add(UItem.asCheck(QOL_DISABLE_PROXY_SPONSOR, getString(R.string.AyuDisableProxySponsor)).setChecked(AyuConfig.disableProxySponsor));
        items.add(UItem.asCheck(QOL_HIDE_STORIES, getString(R.string.AyuHideStories)).setChecked(AyuConfig.hideStories));
        items.add(UItem.asCheck(QOL_KEEP_KICKED, getString(R.string.AyuKeepKickedChats)).setChecked(AyuConfig.keepKickedChats));
        items.add(UItem.asCheck(QOL_SECRET_SCREENSHOTS, getString(R.string.AyuAllowScreenshotsInSecretChats)).setChecked(AyuConfig.allowScreenshotsInSecretChats));
        items.add(UItem.asCheck(QOL_EMULATOR_DETECTION, getString(R.string.AyuDisableEmulatorDetection)).setChecked(AyuConfig.disableEmulatorDetection));
        items.add(UItem.asCheck(QOL_EXPIRE_BUTTON, getString(R.string.AyuShowExpireButton)).setChecked(AyuConfig.showExpireButton));
        items.add(UItem.asCheck(QOL_CRASHLYTICS, getString(R.string.AyuDisableCrashlytics)).setChecked(AyuConfig.disableCrashlytics));
        items.add(UItem.asShadow(getString(R.string.AyuQualityOfLifeInfo)));

        // ------------- customization -------------
        items.add(UItem.asHeader(getString(R.string.AyuCustomization)));
        items.add(UItem.asSettingsCell(CUSTOM_DELETED_MARK, 0, getString(R.string.AyuDeletedMark), AyuConfig.getDeletedMark()));
        items.add(UItem.asSettingsCell(CUSTOM_EDITED_MARK, 0, getString(R.string.AyuEditedMark), AyuConfig.getEditedMark()));
        items.add(UItem.asCheck(CUSTOM_GHOST_TOGGLE, getString(R.string.AyuShowGhostToggle)).setChecked(AyuConfig.showGhostToggleInDrawer));
        items.add(UItem.asCheck(CUSTOM_KILL_BUTTON, getString(R.string.AyuShowKillButton)).setChecked(AyuConfig.showKillButtonInDrawer));
        items.add(UItem.asCheck(CUSTOM_PEER_ID, getString(R.string.AyuShowPeerId)).setChecked(AyuConfig.showPeerId));
        items.add(UItem.asCheck(CUSTOM_MESSAGE_SECONDS, getString(R.string.AyuShowMessageSeconds)).setChecked(AyuConfig.showMessageSeconds));
        items.add(UItem.asCheck(CUSTOM_MESSAGE_DETAILS, getString(R.string.AyuShowMessageDetails)).setChecked(AyuConfig.showMessageDetails));
        items.add(UItem.asCheck(CUSTOM_MESSAGE_SHADOW, getString(R.string.AyuShowMessageShadow)).setChecked(AyuConfig.showMessageShadow));
        items.add(UItem.asCheck(CUSTOM_SIMPLE_QUOTES, getString(R.string.AyuSimpleQuotesAndReplies)).setChecked(AyuConfig.simpleQuotesAndReplies));
        items.add(UItem.asShadow(getString(R.string.AyuCustomizationInfo)));

        // ------------- dynamic island -------------
        items.add(UItem.asHeader(getString(R.string.AyuDynamicIsland)));
        items.add(UItem.asSettingsCell(DYNAMIC_ISLAND, 0, getString(R.string.AyuDynamicIsland),
                getString(AyuConfig.dynamicIsland ? R.string.AyuEnabled : R.string.AyuDisabled)));
        items.add(UItem.asSettingsCell(NETWORK_DIAGNOSTICS, 0, getString(R.string.AyuNetDiagTitle), getString(R.string.AyuNetDiagSubtitle)));
        items.add(UItem.asShadow(getString(R.string.AyuDynamicIslandInfo)));

        // ------------- file explorer -------------
        items.add(UItem.asHeader(getString(R.string.AyuFileExplorer)));
        items.add(UItem.asSettingsCell(FILE_EXPLORER, 0, getString(R.string.AyuFileExplorer)));
        items.add(UItem.asShadow(getString(R.string.AyuFileExplorerInfo)));

        // ------------- duplicate cleaner -------------
        items.add(UItem.asHeader(getString(R.string.AyuDuplicates)));
        CharSequence duplicatesValue = org.telegram.ui.ayu.duplicates.DuplicateCleanerActivity.getHubValue();
        items.add(duplicatesValue != null
                ? UItem.asSettingsCell(DUPLICATE_CLEANER, 0, getString(R.string.AyuDuplicates), duplicatesValue)
                : UItem.asSettingsCell(DUPLICATE_CLEANER, 0, getString(R.string.AyuDuplicates)));
        items.add(UItem.asShadow(getString(R.string.AyuDuplicatesInfo)));

        // ------------- zero-reupload -------------
        items.add(UItem.asHeader(getString(R.string.AyuZeroReupload)));
        items.add(UItem.asSettingsCell(ZERO_REUPLOAD, 0, getString(R.string.AyuZeroReupload),
                getString(org.telegram.messenger.ayu.reupload.ZeroReuploadConfig.isEnabled() ? R.string.AyuEnabled : R.string.AyuDisabled)));
        items.add(UItem.asShadow(getString(R.string.AyuZeroReuploadInfo)));

        // ------------- sync -------------
        items.add(UItem.asHeader(getString(R.string.AyuSyncScreenTitle)));
        items.add(UItem.asSettingsCell(SYNC, 0, getString(R.string.AyuSyncScreenTitle),
                getString(AyuConfig.syncEnabled ? R.string.AyuEnabled : R.string.AyuDisabled)));
        items.add(UItem.asShadow(getString(R.string.AyuSyncInfo)));

        // ------------- personal firewall -------------
        items.add(UItem.asHeader(getString(R.string.AyuFirewallTitle)));
        items.add(UItem.asSettingsCell(FIREWALL, 0, getString(R.string.AyuFirewallTitle),
                getString(org.telegram.messenger.ayu.firewall.Firewall.isEnabled() ? R.string.AyuEnabled : R.string.AyuDisabled)));
        items.add(UItem.asShadow(getString(R.string.AyuFirewallEnableInfo)));

        // ------------- debug -------------
        items.add(UItem.asHeader(getString(R.string.AyuDebug)));
        items.add(UItem.asCheck(DEBUG_WAL, getString(R.string.AyuWalMode)).setChecked(AyuConfig.walMode));
        items.add(UItem.asButton(DEBUG_CLEAR_AYU_DB, getString(R.string.AyuClearDatabase), AndroidUtilities.formatFileSize(ayuDatabaseSize)).red());
        items.add(UItem.asButton(DEBUG_ERASE_TG_DB, getString(R.string.AyuEraseTelegramDatabase)).red());
        items.add(UItem.asButton(DEBUG_KILL_APP, getString(R.string.AyuKillApp)).red());
        items.add(UItem.asShadow(LocaleController.formatString(R.string.AyuFooter, BuildVars.BUILD_VERSION_STRING)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case GHOST_MASTER:
                ghostExpanded = !ghostExpanded;
                if (view instanceof TextCheckCell2) {
                    ((TextCheckCell2) view).setChecked(AyuConfig.isGhostModeActive());
                }
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
                break;
            case GHOST_READ:
                AyuConfig.setSendReadPackets(!AyuConfig.sendReadPackets);
                toggleCheckbox(view, !AyuConfig.sendReadPackets);
                break;
            case GHOST_STORIES:
                AyuConfig.setSendReadStories(!AyuConfig.sendReadStories);
                toggleCheckbox(view, !AyuConfig.sendReadStories);
                break;
            case GHOST_ONLINE:
                AyuConfig.setSendOnlinePackets(!AyuConfig.sendOnlinePackets);
                toggleCheckbox(view, !AyuConfig.sendOnlinePackets);
                break;
            case GHOST_TYPING:
                AyuConfig.setSendUploadProgress(!AyuConfig.sendUploadProgress);
                toggleCheckbox(view, !AyuConfig.sendUploadProgress);
                break;
            case GHOST_OFFLINE_AFTER_ONLINE:
                AyuConfig.setSendOfflinePacketAfterOnline(!AyuConfig.sendOfflinePacketAfterOnline);
                toggleCheckbox(view, AyuConfig.sendOfflinePacketAfterOnline);
                break;
            case GHOST_MORE:
                presentFragment(new GhostPreferencesActivity());
                break;

            case SAVE_DELETED:
                AyuConfig.setSaveDeletedMessages(!AyuConfig.saveDeletedMessages);
                toggleSwitch(view, AyuConfig.saveDeletedMessages);
                break;
            case SAVE_HISTORY:
                AyuConfig.setSaveMessagesHistory(!AyuConfig.saveMessagesHistory);
                toggleSwitch(view, AyuConfig.saveMessagesHistory);
                break;
            case MESSAGE_SAVING:
                presentFragment(new MessageSavingPreferencesActivity());
                break;

            case REGEX_FILTERS:
                presentFragment(new org.telegram.ui.ayu.RegexFiltersActivity());
                break;

            case QOL_KEEP_ALIVE:
                AyuConfig.setKeepAliveService(!AyuConfig.keepAliveService);
                toggleSwitch(view, AyuConfig.keepAliveService);
                break;
            case QOL_DISABLE_ADS:
                AyuConfig.setDisableAds(!AyuConfig.disableAds);
                toggleSwitch(view, AyuConfig.disableAds);
                break;
            case QOL_DISABLE_PROXY_SPONSOR:
                AyuConfig.setDisableProxySponsor(!AyuConfig.disableProxySponsor);
                toggleSwitch(view, AyuConfig.disableProxySponsor);
                break;
            case QOL_HIDE_STORIES:
                AyuConfig.setHideStories(!AyuConfig.hideStories);
                toggleSwitch(view, AyuConfig.hideStories);
                break;
            case QOL_KEEP_KICKED:
                AyuConfig.setKeepKickedChats(!AyuConfig.keepKickedChats);
                toggleSwitch(view, AyuConfig.keepKickedChats);
                break;
            case QOL_SECRET_SCREENSHOTS:
                AyuConfig.setAllowScreenshotsInSecretChats(!AyuConfig.allowScreenshotsInSecretChats);
                toggleSwitch(view, AyuConfig.allowScreenshotsInSecretChats);
                break;
            case QOL_EMULATOR_DETECTION:
                AyuConfig.setDisableEmulatorDetection(!AyuConfig.disableEmulatorDetection);
                toggleSwitch(view, AyuConfig.disableEmulatorDetection);
                break;
            case QOL_EXPIRE_BUTTON:
                AyuConfig.setShowExpireButton(!AyuConfig.showExpireButton);
                toggleSwitch(view, AyuConfig.showExpireButton);
                break;
            case QOL_CRASHLYTICS:
                AyuConfig.setDisableCrashlytics(!AyuConfig.disableCrashlytics);
                toggleSwitch(view, AyuConfig.disableCrashlytics);
                break;

            case CUSTOM_DELETED_MARK:
                showMarkDialog(true);
                break;
            case CUSTOM_EDITED_MARK:
                showMarkDialog(false);
                break;
            case CUSTOM_GHOST_TOGGLE:
                AyuConfig.setShowGhostToggleInDrawer(!AyuConfig.showGhostToggleInDrawer);
                toggleSwitch(view, AyuConfig.showGhostToggleInDrawer);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.ayuGhostModeChanged);
                break;
            case CUSTOM_KILL_BUTTON:
                AyuConfig.setShowKillButtonInDrawer(!AyuConfig.showKillButtonInDrawer);
                toggleSwitch(view, AyuConfig.showKillButtonInDrawer);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.ayuGhostModeChanged);
                break;
            case CUSTOM_PEER_ID:
                AyuConfig.setShowPeerId(!AyuConfig.showPeerId);
                toggleSwitch(view, AyuConfig.showPeerId);
                break;
            case CUSTOM_MESSAGE_SECONDS:
                AyuConfig.setShowMessageSeconds(!AyuConfig.showMessageSeconds);
                toggleSwitch(view, AyuConfig.showMessageSeconds);
                break;
            case CUSTOM_MESSAGE_DETAILS:
                AyuConfig.setShowMessageDetails(!AyuConfig.showMessageDetails);
                toggleSwitch(view, AyuConfig.showMessageDetails);
                break;
            case CUSTOM_MESSAGE_SHADOW:
                AyuConfig.setShowMessageShadow(!AyuConfig.showMessageShadow);
                toggleSwitch(view, AyuConfig.showMessageShadow);
                break;
            case CUSTOM_SIMPLE_QUOTES:
                AyuConfig.setSimpleQuotesAndReplies(!AyuConfig.simpleQuotesAndReplies);
                toggleSwitch(view, AyuConfig.simpleQuotesAndReplies);
                break;

            case SYNC:
                presentFragment(new org.telegram.ui.ayu.AyuSyncPreferencesActivity());
                break;
            case DYNAMIC_ISLAND:
                presentFragment(new DynamicIslandPreferencesActivity());
                break;
            case NETWORK_DIAGNOSTICS:
                presentFragment(new org.telegram.ui.ayu.netdiag.NetworkDiagnosticsActivity());
                break;
            case FILE_EXPLORER:
                presentFragment(new org.telegram.ui.ayu.explorer.FileExplorerActivity());
                break;
            case DUPLICATE_CLEANER:
                presentFragment(new org.telegram.ui.ayu.duplicates.DuplicateCleanerActivity());
                break;
            case ZERO_REUPLOAD:
                presentFragment(new org.telegram.ui.ayu.reupload.ZeroReuploadPreferencesActivity());
                break;
            case MENTION_RADAR:
                presentFragment(new org.telegram.ui.ayu.radar.MentionRadarActivity());
                break;
            case FIREWALL:
                presentFragment(new org.telegram.ui.ayu.firewall.FirewallPreferencesActivity());
                break;

            case DEBUG_WAL:
                AyuConfig.setWalMode(!AyuConfig.walMode);
                toggleSwitch(view, AyuConfig.walMode);
                break;
            case DEBUG_CLEAR_AYU_DB:
                showClearAyuDatabaseDialog();
                break;
            case DEBUG_ERASE_TG_DB:
                showEraseTelegramDatabaseDialog();
                break;
            case DEBUG_KILL_APP:
                showKillAppDialog();
                break;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void toggleSwitch(View view, boolean value) {
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(value);
        }
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
        onConfigChanged();
    }

    private void toggleCheckbox(View view, boolean value) {
        if (view instanceof CheckBoxCell) {
            ((CheckBoxCell) view).setChecked(value, true);
        }
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
        onConfigChanged();
    }

    private void onConfigChanged() {
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.ayuConfigChanged);
    }

    // ---------------------------------------------------------------- dialogs

    private void showMarkDialog(boolean deleted) {
        final Context context = getContext();
        if (context == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(context, resourceProvider);
        builder.setTitle(getString(deleted ? R.string.AyuDeletedMark : R.string.AyuEditedMark));

        final EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourceProvider));
        editText.setHintTextColor(Theme.getColor(Theme.key_groupcreate_hintText, resourceProvider));
        editText.setHint(deleted ? AyuConstants.DEFAULT_DELETED_MARK : getString(R.string.EditedMessage));
        editText.setInputType(InputType.TYPE_CLASS_TEXT);
        editText.setImeOptions(EditorInfo.IME_ACTION_DONE);
        editText.setSingleLine(true);
        editText.setPadding(dp(16), dp(13), dp(16), dp(13));
        editText.setCursorWidth(1.5f);
        editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, resourceProvider));
        editText.setText(deleted ? AyuConfig.deletedMarkText : AyuConfig.editedMarkText);
        editText.setSelection(editText.getText().length());

        final GradientDrawable fieldBackground = new GradientDrawable();
        fieldBackground.setCornerRadius(dp(22));
        fieldBackground.setColor(Theme.multAlpha(Theme.getColor(Theme.key_dialogTextBlack, resourceProvider), 0.06f));
        editText.setBackground(fieldBackground);

        final LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 9));

        builder.makeCustomMaxHeight();
        builder.setView(container);

        builder.setPositiveButton(getString(R.string.Save), (dialog, which) -> {
            final String value = editText.getText().toString();
            if (deleted) {
                AyuConfig.setDeletedMarkText(value);
            } else {
                AyuConfig.setEditedMarkText(value);
            }
            AndroidUtilities.hideKeyboard(editText);
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
            onConfigChanged();
        });
        builder.setNeutralButton(getString(R.string.AyuMarkDefault), (dialog, which) -> {
            if (deleted) {
                AyuConfig.setDeletedMarkText(AyuConstants.DEFAULT_DELETED_MARK);
            } else {
                AyuConfig.setEditedMarkText("");
            }
            AndroidUtilities.hideKeyboard(editText);
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
            onConfigChanged();
        });
        builder.setNegativeButton(getString(R.string.Cancel), (dialog, which) -> {
            AndroidUtilities.hideKeyboard(editText);
            dialog.dismiss();
        });

        final AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(d -> AndroidUtilities.hideKeyboard(editText));
        dialog.setOnShowListener(d -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        });
        showDialog(dialog);
    }

    private void showClearAyuDatabaseDialog() {
        if (getContext() == null) {
            return;
        }
        new AlertDialog.Builder(getContext(), resourceProvider)
                .setTitle(getString(R.string.AyuClearDatabaseTitle))
                .setMessage(getString(R.string.AyuClearDatabaseMessage))
                .setPositiveButton(getString(R.string.Delete), (dialog, which) ->
                        AyuMessagesController.getInstance().clearDatabase(() -> AndroidUtilities.runOnUIThread(() -> {
                            reloadDatabaseSize();
                            if (listView != null && listView.adapter != null) {
                                listView.adapter.update(true);
                            }
                        })))
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    private void showEraseTelegramDatabaseDialog() {
        if (getContext() == null) {
            return;
        }
        new AlertDialog.Builder(getContext(), resourceProvider)
                .setTitle(getString(R.string.AyuEraseTelegramDatabaseTitle))
                .setMessage(getString(R.string.AyuEraseTelegramDatabaseMessage))
                .setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
                    // MessagesStorage#cleanup(true) drops and recreates the local cache database
                    // (same path the client uses on login) and then re-fetches the difference.
                    getMessagesStorage().cleanup(true);
                    AndroidUtilities.runOnUIThread(() -> restartApplication(getParentActivity()), 400);
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    private void showKillAppDialog() {
        if (getContext() == null) {
            return;
        }
        new AlertDialog.Builder(getContext(), resourceProvider)
                .setTitle(getString(R.string.AyuKillAppTitle))
                .setMessage(getString(R.string.AyuKillAppMessage))
                .setPositiveButton(getString(R.string.OK), (dialog, which) -> killApplication(getParentActivity()))
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    /** Terminates the app process. Used by the kill button in the chats list. */
    public static void killApplication(Activity activity) {
        if (activity == null && LaunchActivity.instance != null) {
            activity = LaunchActivity.instance;
        }
        final Activity finalActivity = activity;
        AndroidUtilities.runOnUIThread(() -> {
            try {
                if (finalActivity != null) {
                    finalActivity.finishAffinity();
                }
            } catch (Exception ignore) {
            }
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
        }, 60);
    }

    /** Restarts the app (used after erasing the local database). */
    public static void restartApplication(Activity activity) {
        if (activity == null && LaunchActivity.instance != null) {
            activity = LaunchActivity.instance;
        }
        try {
            if (activity != null) {
                final PackageManager pm = activity.getPackageManager();
                final Intent intent = pm.getLaunchIntentForPackage(activity.getPackageName());
                activity.finishAffinity();
                if (intent != null) {
                    activity.startActivity(intent);
                }
            }
        } catch (Exception ignore) {
        }
        System.exit(0);
    }
}
