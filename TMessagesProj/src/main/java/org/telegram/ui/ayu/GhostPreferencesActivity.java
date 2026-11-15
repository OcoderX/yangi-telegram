package org.telegram.ui.ayu;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;

import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.ayu.AyuConfig;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * AyuGram ghost mode sub-screen: the five core ghost options plus the ghost extras.
 */
public class GhostPreferencesActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int GHOST_READ = 1;
    private static final int GHOST_STORIES = 2;
    private static final int GHOST_ONLINE = 3;
    private static final int GHOST_TYPING = 4;
    private static final int GHOST_OFFLINE_AFTER_ONLINE = 5;

    private static final int MARK_READ_AFTER_SEND = 10;
    private static final int MARK_READ_AFTER_ACTION = 11;
    private static final int USE_SCHEDULED_MESSAGES = 12;
    private static final int ALERT_BEFORE_STORY = 13;
    private static final int LOCAL_PREMIUM = 14;

    public GhostPreferencesActivity() {
        super();
        AyuConfig.load();
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.ayuGhostModeChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.ayuGhostModeChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.ayuGhostModeChanged && args.length > 0
                && args[0] instanceof Integer && (Integer) args[0] != currentAccount) return;
        if (id == NotificationCenter.ayuGhostModeChanged) {
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
    protected CharSequence getTitle() {
        return getString(R.string.AyuGhostMode);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(getString(R.string.AyuGhostMode)));
        items.add(UItem.asCheck(GHOST_READ, getString(R.string.AyuGhostDontReadMessages)).setChecked(!org.telegram.messenger.ayu.AyuGhostConfig.get(currentAccount).sendReadPackets));
        items.add(UItem.asCheck(GHOST_STORIES, getString(R.string.AyuGhostDontReadStories)).setChecked(!org.telegram.messenger.ayu.AyuGhostConfig.get(currentAccount).sendReadStories));
        items.add(UItem.asCheck(GHOST_ONLINE, getString(R.string.AyuGhostDontSendOnline)).setChecked(!org.telegram.messenger.ayu.AyuGhostConfig.get(currentAccount).sendOnlinePackets));
        items.add(UItem.asCheck(GHOST_TYPING, getString(R.string.AyuGhostDontSendTyping)).setChecked(!org.telegram.messenger.ayu.AyuGhostConfig.get(currentAccount).sendUploadProgress));
        items.add(UItem.asCheck(GHOST_OFFLINE_AFTER_ONLINE, getString(R.string.AyuGhostSendOfflineAfterOnline)).setChecked(org.telegram.messenger.ayu.AyuGhostConfig.get(currentAccount).sendOfflinePacketAfterOnline));
        items.add(UItem.asShadow(getString(R.string.AyuGhostModeInfo)));

        items.add(UItem.asHeader(getString(R.string.AyuGhostExtras)));
        items.add(UItem.asCheck(MARK_READ_AFTER_SEND, getString(R.string.AyuGhostMarkReadAfterSend)).setChecked(org.telegram.messenger.ayu.AyuGhostConfig.get(currentAccount).markReadAfterSend));
        items.add(UItem.asCheck(MARK_READ_AFTER_ACTION, getString(R.string.AyuGhostMarkReadAfterAction)).setChecked(org.telegram.messenger.ayu.AyuGhostConfig.get(currentAccount).markReadAfterAction));
        items.add(UItem.asCheck(USE_SCHEDULED_MESSAGES, getString(R.string.AyuGhostUseScheduledMessages)).setChecked(org.telegram.messenger.ayu.AyuGhostConfig.get(currentAccount).useScheduledMessages));
        items.add(UItem.asShadow(getString(R.string.AyuGhostUseScheduledMessagesInfo)));

        items.add(UItem.asCheck(ALERT_BEFORE_STORY, getString(R.string.AyuGhostAlertBeforeStory)).setChecked(org.telegram.messenger.ayu.AyuGhostConfig.get(currentAccount).alertBeforeOpeningStory));
        items.add(UItem.asShadow(getString(R.string.AyuGhostAlertBeforeStoryInfo)));

        items.add(UItem.asCheck(LOCAL_PREMIUM, getString(R.string.AyuLocalPremium)).setChecked(AyuConfig.localPremium));
        items.add(UItem.asShadow(getString(R.string.AyuLocalPremiumInfo)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case GHOST_READ:
                AyuConfig.setSendReadPackets(currentAccount, !org.telegram.messenger.ayu.AyuGhostConfig.get(currentAccount).sendReadPackets);
                toggleSwitch(view, !org.telegram.messenger.ayu.AyuGhostConfig.get(currentAccount).sendReadPackets);
                break;
            case GHOST_STORIES:
                AyuConfig.setSendReadStories(currentAccount, !org.telegram.messenger.ayu.AyuGhostConfig.get(currentAccount).sendReadStories);
                toggleSwitch(view, !org.telegram.messenger.ayu.AyuGhostConfig.get(currentAccount).sendReadStories);
                break;
            case GHOST_ONLINE:
                AyuConfig.setSendOnlinePackets(currentAccount, !org.telegram.messenger.ayu.AyuGhostConfig.get(currentAccount).sendOnlinePackets);
                toggleSwitch(view, !org.telegram.messenger.ayu.AyuGhostConfig.get(currentAccount).sendOnlinePackets);
                break;
            case GHOST_TYPING:
                AyuConfig.setSendUploadProgress(currentAccount, !org.telegram.messenger.ayu.AyuGhostConfig.get(currentAccount).sendUploadProgress);
                toggleSwitch(view, !org.telegram.messenger.ayu.AyuGhostConfig.get(currentAccount).sendUploadProgress);
                break;
            case GHOST_OFFLINE_AFTER_ONLINE:
                AyuConfig.setSendOfflinePacketAfterOnline(currentAccount, !org.telegram.messenger.ayu.AyuGhostConfig.get(currentAccount).sendOfflinePacketAfterOnline);
                toggleSwitch(view, org.telegram.messenger.ayu.AyuGhostConfig.get(currentAccount).sendOfflinePacketAfterOnline);
                break;

            case MARK_READ_AFTER_SEND: {
                final boolean enabled = !org.telegram.messenger.ayu.AyuGhostConfig.get(currentAccount).markReadAfterSend;
                AyuConfig.setMarkReadAfterSend(currentAccount, enabled);
                if (enabled && org.telegram.messenger.ayu.AyuGhostConfig.get(currentAccount).useScheduledMessages) {
                    // the two options are mutually exclusive
                    AyuConfig.setUseScheduledMessages(currentAccount, false);
                }
                toggleSwitch(view, org.telegram.messenger.ayu.AyuGhostConfig.get(currentAccount).markReadAfterSend);
                break;
            }
            case MARK_READ_AFTER_ACTION:
                AyuConfig.setMarkReadAfterAction(currentAccount, !org.telegram.messenger.ayu.AyuGhostConfig.get(currentAccount).markReadAfterAction);
                toggleSwitch(view, org.telegram.messenger.ayu.AyuGhostConfig.get(currentAccount).markReadAfterAction);
                break;
            case USE_SCHEDULED_MESSAGES: {
                final boolean enabled = !org.telegram.messenger.ayu.AyuGhostConfig.get(currentAccount).useScheduledMessages;
                AyuConfig.setUseScheduledMessages(currentAccount, enabled);
                if (enabled && org.telegram.messenger.ayu.AyuGhostConfig.get(currentAccount).markReadAfterSend) {
                    // the two options are mutually exclusive
                    AyuConfig.setMarkReadAfterSend(currentAccount, false);
                }
                toggleSwitch(view, org.telegram.messenger.ayu.AyuGhostConfig.get(currentAccount).useScheduledMessages);
                break;
            }
            case ALERT_BEFORE_STORY:
                AyuConfig.setAlertBeforeOpeningStory(currentAccount, !org.telegram.messenger.ayu.AyuGhostConfig.get(currentAccount).alertBeforeOpeningStory);
                toggleSwitch(view, org.telegram.messenger.ayu.AyuGhostConfig.get(currentAccount).alertBeforeOpeningStory);
                break;
            case LOCAL_PREMIUM:
                AyuConfig.setLocalPremium(!AyuConfig.localPremium);
                toggleSwitch(view, AyuConfig.localPremium);
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
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.ayuConfigChanged);
    }
}
