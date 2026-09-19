package org.telegram.ui.ayu.firewall;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.ayu.firewall.Firewall;
import org.telegram.messenger.ayu.firewall.FirewallConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * AyuGram Personal Firewall settings: master switch, the three rule switches, the trusted-sender
 * whitelist and the log of the last blocked events.
 */
public class FirewallPreferencesActivity extends UniversalFragment {

    private static final int BUTTON_ENABLE = 1;
    private static final int BUTTON_EXECUTABLES = 2;
    private static final int BUTTON_LINKS = 3;
    private static final int BUTTON_ARCHIVES = 4;
    private static final int BUTTON_UNKNOWN_ONLY = 5;
    private static final int BUTTON_CLEAR_WHITELIST = 6;
    private static final int BUTTON_CLEAR_LOG = 7;

    private static final int WHITELIST_ITEM_OFFSET = 1000;
    private static final int LOG_ITEM_OFFSET = 100000;

    private ArrayList<Long> whitelistSnapshot = new ArrayList<>();
    private ArrayList<FirewallConfig.Event> eventsSnapshot = new ArrayList<>();

    public FirewallPreferencesActivity() {
        super();
        FirewallConfig.load();
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
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(false);
        }
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.AyuFirewallTitle);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        FirewallConfig.ensureLoaded();
        whitelistSnapshot = FirewallConfig.getWhitelist();
        eventsSnapshot = FirewallConfig.getEvents();

        final boolean on = FirewallConfig.enabled;

        items.add(UItem.asHeader(getString(R.string.AyuFirewallTitle)));
        items.add(UItem.asCheck(BUTTON_ENABLE, getString(R.string.AyuFirewallEnable)).setChecked(on));
        items.add(UItem.asShadow(getString(R.string.AyuFirewallEnableInfo)));

        items.add(UItem.asHeader(getString(R.string.AyuFirewallRulesHeader)));
        items.add(UItem.asCheck(BUTTON_EXECUTABLES, getString(R.string.AyuFirewallBlockExecutables))
                .setChecked(FirewallConfig.blockExecutables).setEnabled(on));
        items.add(UItem.asCheck(BUTTON_LINKS, getString(R.string.AyuFirewallBlockLinks))
                .setChecked(FirewallConfig.blockSuspiciousLinks).setEnabled(on));
        items.add(UItem.asCheck(BUTTON_ARCHIVES, getString(R.string.AyuFirewallBlockArchives))
                .setChecked(FirewallConfig.blockDangerousArchives).setEnabled(on));
        items.add(UItem.asCheck(BUTTON_UNKNOWN_ONLY, getString(R.string.AyuFirewallUnknownOnly))
                .setChecked(FirewallConfig.unknownSendersOnly).setEnabled(on));
        items.add(UItem.asShadow(getString(R.string.AyuFirewallRulesInfo)));

        // the one rule the user cannot turn off
        items.add(UItem.asShadow(getString(R.string.AyuFirewallHardRuleInfo)));

        items.add(UItem.asHeader(getString(R.string.AyuFirewallWhitelistHeader)));
        if (whitelistSnapshot.isEmpty()) {
            items.add(UItem.asShadow(getString(R.string.AyuFirewallWhitelistEmpty)));
        } else {
            for (int i = 0; i < whitelistSnapshot.size(); i++) {
                long id = whitelistSnapshot.get(i);
                String name = DialogObject.getName(currentAccount, id);
                if (TextUtils.isEmpty(name)) {
                    name = String.valueOf(id);
                }
                items.add(UItem.asButton(WHITELIST_ITEM_OFFSET + i, name, String.valueOf(id)));
            }
            items.add(UItem.asButton(BUTTON_CLEAR_WHITELIST, R.drawable.msg_delete, getString(R.string.AyuFirewallWhitelistClear)).red());
            items.add(UItem.asShadow(getString(R.string.AyuFirewallWhitelistInfo)));
        }

        items.add(UItem.asHeader(getString(R.string.AyuFirewallLogHeader)));
        if (eventsSnapshot.isEmpty()) {
            items.add(UItem.asShadow(getString(R.string.AyuFirewallLogEmpty)));
        } else {
            for (int i = 0; i < eventsSnapshot.size(); i++) {
                FirewallConfig.Event event = eventsSnapshot.get(i);
                items.add(UItem.asButton(LOG_ITEM_OFFSET + i, describeEventTitle(event), describeEventValue(event)));
            }
            items.add(UItem.asButton(BUTTON_CLEAR_LOG, R.drawable.msg_delete, getString(R.string.AyuFirewallLogClear)).red());
            items.add(UItem.asShadow(getString(R.string.AyuFirewallLogInfo)));
        }
    }

    private CharSequence describeEventTitle(FirewallConfig.Event event) {
        String peer = null;
        if (event.dialogId != 0) {
            peer = DialogObject.getName(currentAccount, event.dialogId);
        }
        if (TextUtils.isEmpty(peer) && event.senderId != 0) {
            peer = DialogObject.getName(currentAccount, event.senderId);
        }
        if (TextUtils.isEmpty(peer)) {
            peer = getString(R.string.AyuFirewallUnknownChat);
        }
        return getString(Firewall.reasonLabel(event.reasonKey)) + " " + (char) 0x2022 + " " + peer;
    }

    private CharSequence describeEventValue(FirewallConfig.Event event) {
        return LocaleController.formatDateTime(event.date / 1000L, true);
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case BUTTON_ENABLE:
                FirewallConfig.setEnabled(!FirewallConfig.enabled);
                toggleSwitch(view, FirewallConfig.enabled);
                return;
            case BUTTON_EXECUTABLES:
                if (!FirewallConfig.enabled) return;
                FirewallConfig.setBlockExecutables(!FirewallConfig.blockExecutables);
                toggleSwitch(view, FirewallConfig.blockExecutables);
                return;
            case BUTTON_LINKS:
                if (!FirewallConfig.enabled) return;
                FirewallConfig.setBlockSuspiciousLinks(!FirewallConfig.blockSuspiciousLinks);
                toggleSwitch(view, FirewallConfig.blockSuspiciousLinks);
                return;
            case BUTTON_ARCHIVES:
                if (!FirewallConfig.enabled) return;
                FirewallConfig.setBlockDangerousArchives(!FirewallConfig.blockDangerousArchives);
                toggleSwitch(view, FirewallConfig.blockDangerousArchives);
                return;
            case BUTTON_UNKNOWN_ONLY:
                if (!FirewallConfig.enabled) return;
                FirewallConfig.setUnknownSendersOnly(!FirewallConfig.unknownSendersOnly);
                toggleSwitch(view, FirewallConfig.unknownSendersOnly);
                return;
            case BUTTON_CLEAR_WHITELIST:
                showConfirm(getString(R.string.AyuFirewallWhitelistClear), getString(R.string.AyuFirewallWhitelistClearInfo), () -> {
                    FirewallConfig.clearWhitelist();
                    update();
                });
                return;
            case BUTTON_CLEAR_LOG:
                showConfirm(getString(R.string.AyuFirewallLogClear), getString(R.string.AyuFirewallLogClearInfo), () -> {
                    FirewallConfig.clearEvents();
                    update();
                });
                return;
        }
        if (item.id >= LOG_ITEM_OFFSET) {
            int index = item.id - LOG_ITEM_OFFSET;
            if (index >= 0 && index < eventsSnapshot.size()) {
                showEventDetails(eventsSnapshot.get(index));
            }
        } else if (item.id >= WHITELIST_ITEM_OFFSET) {
            int index = item.id - WHITELIST_ITEM_OFFSET;
            if (index >= 0 && index < whitelistSnapshot.size()) {
                showWhitelistOptions(view, whitelistSnapshot.get(index));
            }
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (item.id >= WHITELIST_ITEM_OFFSET && item.id < LOG_ITEM_OFFSET) {
            int index = item.id - WHITELIST_ITEM_OFFSET;
            if (index >= 0 && index < whitelistSnapshot.size()) {
                showWhitelistOptions(view, whitelistSnapshot.get(index));
                return true;
            }
        }
        return false;
    }

    private void showWhitelistOptions(View view, long peerId) {
        ItemOptions options = ItemOptions.makeOptions(this, view);
        options.add(R.drawable.msg_delete, getString(R.string.AyuFirewallWhitelistRemove), true, () -> {
            FirewallConfig.removeFromWhitelist(peerId);
            update();
            BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip, getString(R.string.AyuFirewallWhitelistRemoved)).show();
        });
        options.setGravity(Gravity.RIGHT);
        options.show();
    }

    private void showEventDetails(FirewallConfig.Event event) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(getString(Firewall.reasonLabel(event.reasonKey)));
        StringBuilder sb = new StringBuilder();
        sb.append(LocaleController.formatDateTime(event.date / 1000L, true));
        if (event.detail != null && !event.detail.isEmpty()) {
            sb.append("\n\n").append(event.detail);
        }
        builder.setMessage(sb.toString());
        builder.setPositiveButton(getString(R.string.OK), (dialog, which) -> dialog.dismiss());
        if (event.senderId != 0 && !FirewallConfig.isWhitelisted(event.senderId)) {
            builder.setNeutralButton(getString(R.string.AyuFirewallTrustSender), (dialog, which) -> {
                FirewallConfig.addToWhitelist(event.senderId);
                dialog.dismiss();
                update();
            });
        }
        builder.show();
    }

    private void showConfirm(CharSequence title, CharSequence message, Runnable onConfirm) {
        if (getParentActivity() == null) {
            onConfirm.run();
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(title);
        builder.setMessage(message);
        builder.setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
            dialog.dismiss();
            onConfirm.run();
        });
        builder.setNegativeButton(getString(R.string.Cancel), (dialog, which) -> dialog.dismiss());
        builder.makeRed(AlertDialog.BUTTON_POSITIVE);
        builder.show();
    }

    private void update() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void toggleSwitch(View view, boolean value) {
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(value);
        }
        update();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.ayuConfigChanged);
    }
}
