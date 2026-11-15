package org.telegram.ui.ayu;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Map;

public final class OxChannelMembershipGate {
    private static final String CHANNEL = "OcoderXs";
    private static final String BOT = "OcoderXbot";
    private static final String PREF_NAME = "ox_channel_membership";
    private static final long MEMBERSHIP_CACHE_MS = 30L * 24 * 60 * 60 * 1000L;
    private enum State { IDLE, CHECKING, READY, CONSENT, ERROR, MEMBER }

    private final LaunchActivity activity;
    private final Runnable unlocked;
    private final SharedPreferences preferences;
    private int account = -1;
    private long userId;
    private int generation;
    private int requestId;
    private Runnable timeout;
    private boolean resumed;
    private boolean destroyed;
    private State state = State.IDLE;
    private TLRPC.Chat channel;
    private boolean startBotConsent;
    private boolean botStarted;
    private long verifiedAt;
    private int statusText = R.string.OxChannelChecking;
    private Dialog dialog;
    private android.app.AlertDialog accountDialog;
    private TextView statusView;
    private Button joinButton;
    private Button retryButton;

    public OxChannelMembershipGate(LaunchActivity activity, Runnable unlocked) {
        this.activity = activity;
        this.unlocked = unlocked;
        preferences = ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private static String verifiedKey(long userId) {
        return "verified_" + BOT + "_" + userId;
    }

    private static String markerCreatedKey(long userId) {
        return "marker_created_" + BOT + "_" + userId;
    }

    private static String startedKey(long userId) {
        return "started_" + BOT + "_" + userId;
    }

    private static String randomKey(long userId) {
        return "random_" + BOT + "_" + userId;
    }

    private static File getCacheMarkerFile(long userId) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return null;
        }
        File cacheDir = context.getCacheDir();
        if (cacheDir == null) {
            return null;
        }
        return new File(cacheDir, "ox_gate_" + userId + ".bin");
    }

    private boolean isMembershipCached(long userId) {
        if (userId == 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        long verified = preferences.getLong(verifiedKey(userId), 0);
        if (verified == 0 && preferences.getBoolean(startedKey(userId), false)) {
            saveMembershipVerified(userId);
            return true;
        }
        if (verified <= 0 || verified > now + 60_000L || (now - verified) > MEMBERSHIP_CACHE_MS) {
            return false;
        }
        if (preferences.getBoolean(markerCreatedKey(userId), false)) {
            File marker = getCacheMarkerFile(userId);
            if (marker == null || !marker.exists()) {
                preferences.edit()
                        .remove(verifiedKey(userId))
                        .remove(markerCreatedKey(userId))
                        .apply();
                return false;
            }
        }
        return true;
    }

    private void saveMembershipVerified(long sourceUserId) {
        if (sourceUserId == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        preferences.edit()
                .putLong(verifiedKey(sourceUserId), now)
                .putBoolean(markerCreatedKey(sourceUserId), true)
                .putBoolean(startedKey(sourceUserId), true)
                .apply();
        File marker = getCacheMarkerFile(sourceUserId);
        if (marker != null) {
            try {
                File parent = marker.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                try (FileOutputStream fos = new FileOutputStream(marker)) {
                    fos.write(String.valueOf(now).getBytes());
                    fos.flush();
                }
            } catch (Exception ignored) {
            }
        }
    }

    public static void onCacheCleared() {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return;
            }
            SharedPreferences preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            Map<String, ?> all = preferences.getAll();
            if (all != null && !all.isEmpty()) {
                SharedPreferences.Editor editor = preferences.edit();
                for (String key : all.keySet()) {
                    if (key != null && (key.startsWith("verified_") || key.startsWith("marker_created_"))) {
                        editor.remove(key);
                    }
                }
                editor.apply();
            }

            File cacheDir = context.getCacheDir();
            if (cacheDir != null && cacheDir.exists()) {
                File[] files = cacheDir.listFiles((dir, name) -> name != null && name.startsWith("ox_gate_"));
                if (files != null) {
                    for (File file : files) {
                        try {
                            file.delete();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** Called only at lifecycle, authentication and navigation boundaries. */
    public boolean update() {
        if (destroyed) return false;
        int selected = UserConfig.selectedAccount;
        UserConfig config = UserConfig.getInstance(selected);
        long selectedUser = config.getClientUserId();
        if (account != selected || userId != selectedUser) {
            cancelRequest();
            dismiss();
            account = selected;
            userId = selectedUser;
            channel = null;
            botStarted = preferences.getBoolean(startedKey(userId), false);
            state = isMembershipCached(userId) ? State.MEMBER : State.IDLE;
        }
        // Telegram bots cannot join a channel themselves. Preserve the existing bot login flow.
        if (!config.isClientActivated() || config.isBotAccount()) {
            cancelRequest();
            dismiss();
            return false;
        }
        if (isMembershipCached(userId)) {
            state = State.MEMBER;
            dismiss();
            return false;
        }
        if (state == State.MEMBER) {
            state = State.IDLE;
        }
        if (resumed && !locked() && !activity.isFinishing()) {
            show();
            if (state == State.IDLE) check();
        }
        return true;
    }

    private boolean locked() {
        return SharedConfig.appLocked || SharedConfig.isWaitingForPasscodeEnter;
    }

    public void onResume() {
        resumed = true;
        if (userId != 0) botStarted = preferences.getBoolean(startedKey(userId), false);
        // A short in-memory lease avoids a network gate after every permission dialog/app switch.
        // update() rechecks an expired lease on the next lifecycle/navigation event, without polling.
        update();
    }

    public void onPause() {
        resumed = false;
        cancelRequest();
        if (state != State.MEMBER) state = State.IDLE;
        dismiss();
    }

    public void onPasscodeShown() {
        cancelRequest();
        if (state != State.MEMBER) state = State.IDLE;
        dismiss();
    }

    public void destroy() {
        destroyed = true;
        cancelRequest();
        dismiss();
    }

    private void check() {
        cancelRequest();
        channel = null;
        busy();
        TLRPC.TL_contacts_resolveUsername request = new TLRPC.TL_contacts_resolveUsername();
        request.username = CHANNEL;
        send(request, (response, error) -> {
            if (error != null || !(response instanceof TLRPC.TL_contacts_resolvedPeer)) {
                error(R.string.OxChannelCheckError);
                return;
            }
            TLRPC.TL_contacts_resolvedPeer resolved = (TLRPC.TL_contacts_resolvedPeer) response;
            if (resolved.peer != null && resolved.peer.channel_id != 0) {
                for (TLRPC.Chat chat : resolved.chats) {
                    if (chat.id == resolved.peer.channel_id && isBroadcast(chat) && !chat.min && chat.access_hash != 0) {
                        channel = chat;
                        break;
                    }
                }
            }
            if (channel == null) {
                error(R.string.OxChannelConfigError);
                return;
            }
            MessagesController.getInstance(account).putChats(resolved.chats, false);
            verifyMembership(false);
        });
    }

    private static boolean isBroadcast(TLRPC.Chat chat) {
        return chat != null && ChatObject.isChannel(chat) && chat.broadcast && !chat.megagroup;
    }

    private void verifyMembership(boolean afterJoin) {
        TLRPC.TL_channels_getParticipant request = new TLRPC.TL_channels_getParticipant();
        request.channel = MessagesController.getInputChannel(channel);
        request.participant = new TLRPC.TL_inputPeerSelf();
        send(request, (response, error) -> {
            if (error == null && response instanceof TLRPC.TL_channels_channelParticipant) {
                TLRPC.ChannelParticipant participant = ((TLRPC.TL_channels_channelParticipant) response).participant;
                long participantId = participant == null ? 0 : participant.peer != null ? participant.peer.user_id : participant.user_id;
                boolean member = participantId == userId && !participant.left &&
                        (participant instanceof TLRPC.TL_channelParticipant ||
                         participant instanceof TLRPC.TL_channelParticipantSelf ||
                         participant instanceof TLRPC.TL_channelParticipantAdmin ||
                         participant instanceof TLRPC.TL_channelParticipantCreator);
                if (member) {
                    if (botStarted) {
                        unlock();
                    } else if (afterJoin && startBotConsent) {
                        startBot();
                    } else {
                        state = State.CONSENT;
                        statusText = R.string.OxChannelMemberConfirmed;
                        render();
                    }
                } else if (!afterJoin && participantId == userId &&
                        participant instanceof TLRPC.TL_channelParticipantLeft) {
                    readyToJoin();
                } else {
                    error(R.string.OxChannelCheckError);
                }
            } else if (error != null && "USER_NOT_PARTICIPANT".equals(error.text)) {
                if (afterJoin) error(R.string.OxChannelNotMember);
                else readyToJoin();
            } else {
                // Offline, forbidden and unknown errors never establish nonmembership.
                error(R.string.OxChannelCheckError);
            }
        });
    }

    private void readyToJoin() {
        state = State.READY;
        statusText = R.string.OxChannelRequired;
        render();
    }

    private void accept() {
        if (!validIdentity() || locked()) return;
        if (state == State.CONSENT) {
            startBotConsent = true;
            busy();
            verifyMembership(true);
        } else if (state == State.READY) {
            startBotConsent = true;
            join();
        }
    }

    private void unlock() {
        state = State.MEMBER;
        saveMembershipVerified(userId);
        verifiedAt = SystemClock.elapsedRealtime();
        startBotConsent = false;
        dismiss();
        unlocked.run();
    }

    private void join() {
        if (state != State.READY || !validIdentity() || locked()) return;
        busy();
        TLRPC.TL_channels_joinChannel request = new TLRPC.TL_channels_joinChannel();
        request.channel = MessagesController.getInputChannel(channel);
        send(request, (response, error) -> {
            if (error != null) {
                if ("USER_ALREADY_PARTICIPANT".equals(error.text)) {
                    verifyMembership(true);
                } else if ("INVITE_REQUEST_SENT".equals(error.text)) {
                    error(R.string.OxChannelRequestPending);
                } else {
                    error(R.string.OxChannelJoinError);
                }
                return;
            }
            if (!(response instanceof TLRPC.Updates)) {
                error(R.string.OxChannelJoinError);
                return;
            }
            TLRPC.Updates updates = (TLRPC.Updates) response;
            boolean wrongChannel = false;
            for (TLRPC.Chat chat : updates.chats) {
                if (ChatObject.isChannel(chat) && chat.id != channel.id) wrongChannel = true;
            }
            MessagesController.getInstance(account).processUpdates(updates, false);
            if (wrongChannel) error(R.string.OxChannelConfigError);
            else {
                verifyMembership(true);
            }
        });
    }

    private void startBot() {
        if (!startBotConsent || !validIdentity() || locked()) return;
        busy();
        statusText = R.string.OxChannelBotStarting;
        render();
        TLRPC.TL_contacts_resolveUsername request = new TLRPC.TL_contacts_resolveUsername();
        request.username = BOT;
        send(request, (response, error) -> {
            if (error != null || !(response instanceof TLRPC.TL_contacts_resolvedPeer)) {
                error(R.string.OxChannelBotError);
                return;
            }
            TLRPC.TL_contacts_resolvedPeer resolved = (TLRPC.TL_contacts_resolvedPeer) response;
            TLRPC.User bot = null;
            if (resolved.peer != null && resolved.peer.user_id != 0) {
                for (TLRPC.User user : resolved.users) {
                    if (user.id == resolved.peer.user_id && user.bot && !user.deleted && !user.min && user.access_hash != 0) {
                        bot = user;
                        break;
                    }
                }
            }
            if (bot == null) {
                error(R.string.OxChannelBotError);
                return;
            }
            MessagesController.getInstance(account).putUsers(resolved.users, false);
            prepareBotStart(bot);
        });
    }

    private void prepareBotStart(TLRPC.User bot) {
        final int token = ++generation;
        final long sourceUserId = userId;
        // Persist before sending. Retries and a recreated activity reuse the same command ID.
        // The queue serializes reads/writes even if two LaunchActivity instances briefly overlap.
        Utilities.globalQueue.postRunnable(() -> {
            boolean alreadyStarted = preferences.getBoolean(startedKey(sourceUserId), false);
            long savedRandomId = preferences.getLong(randomKey(sourceUserId), 0);
            long savedBotId = preferences.getLong(randomKey(sourceUserId) + "_bot", bot.id);
            if (savedRandomId == 0) {
                do { savedRandomId = Utilities.random.nextLong(); } while (savedRandomId == 0);
            }
            final long randomId = savedRandomId;
            boolean sameBot = savedBotId == bot.id;
            boolean saved = sameBot && (alreadyStarted || preferences.edit()
                    .putLong(randomKey(sourceUserId), randomId)
                    .putLong(randomKey(sourceUserId) + "_bot", bot.id).commit());
            AndroidUtilities.runOnUIThread(() -> {
                if (token != generation || !validIdentity() || locked() || !startBotConsent) return;
                if (!saved) {
                    error(sameBot ? R.string.OxChannelSaveError : R.string.OxChannelBotError);
                    return;
                }
                if (alreadyStarted) {
                    botStarted = true;
                    unlock();
                    return;
                }
                TLRPC.TL_messages_startBot start = new TLRPC.TL_messages_startBot();
                start.bot = MessagesController.getInstance(account).getInputUser(bot);
                TLRPC.TL_inputPeerUser peer = new TLRPC.TL_inputPeerUser();
                peer.user_id = bot.id;
                peer.access_hash = bot.access_hash;
                start.peer = peer;
                start.random_id = randomId;
                start.start_param = "oxgram";
                send(start, (response, error) -> {
                    if (error != null && "RANDOM_ID_DUPLICATE".equals(error.text)) {
                        // This durable ID belongs only to this account/bot start. It was already
                        // accepted before a lost response or restart; never send a fresh command.
                        saveBotStarted(sourceUserId);
                        return;
                    }
                    if (error != null || !(response instanceof TLRPC.Updates)) {
                        error(error != null && ("USER_IS_BLOCKED".equals(error.text) || "YOU_BLOCKED_USER".equals(error.text))
                                ? R.string.OxChannelBotBlocked : R.string.OxChannelBotError);
                        return;
                    }
                    MessagesController.getInstance(account).processUpdates((TLRPC.Updates) response, false);
                    saveBotStarted(sourceUserId);
                });
            });
        });
    }

    private void saveBotStarted(long sourceUserId) {
        final int token = ++generation;
        Utilities.globalQueue.postRunnable(() -> {
            boolean saved = preferences.edit().putBoolean(startedKey(sourceUserId), true).commit();
            AndroidUtilities.runOnUIThread(() -> {
                if (token != generation || !validIdentity() || locked()) return;
                if (!saved) {
                    error(R.string.OxChannelSaveError);
                    return;
                }
                botStarted = true;
                unlock();
            });
        });
    }

    private boolean validIdentity() {
        return !destroyed && resumed && account == UserConfig.selectedAccount &&
                UserConfig.getInstance(account).isClientActivated() &&
                UserConfig.getInstance(account).getClientUserId() == userId &&
                !UserConfig.getInstance(account).isBotAccount();
    }

    private void send(TLObject request, RequestDelegate callback) {
        final int token = ++generation;
        final int timeoutText = statusText == R.string.OxChannelBotStarting ? R.string.OxChannelBotError : R.string.OxChannelCheckError;
        requestId = ConnectionsManager.getInstance(account).sendRequest(request, (response, error) ->
                AndroidUtilities.runOnUIThread(() -> {
                    if (token != generation || !validIdentity() || locked()) return;
                    requestId = 0;
                    if (timeout != null) AndroidUtilities.cancelRunOnUIThread(timeout);
                    timeout = null;
                    callback.run(response, error);
                }));
        timeout = () -> {
            if (token != generation) return;
            cancelRequest();
            error(timeoutText);
        };
        AndroidUtilities.runOnUIThread(timeout, 20000);
    }

    private void cancelRequest() {
        startBotConsent = false;
        generation++;
        if (timeout != null) AndroidUtilities.cancelRunOnUIThread(timeout);
        timeout = null;
        if (requestId != 0 && account >= 0) ConnectionsManager.getInstance(account).cancelRequest(requestId, true);
        requestId = 0;
    }

    private void busy() {
        state = State.CHECKING;
        statusText = R.string.OxChannelChecking;
        render();
    }

    private void error(int text) {
        startBotConsent = false;
        state = State.ERROR;
        statusText = text;
        render();
    }

    private void show() {
        if (dialog != null) return;
        dialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar);
        dialog.setCancelable(false);
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) activity.finish();
            return true;
        });
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(32), AndroidUtilities.dp(24), AndroidUtilities.dp(24));
        content.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        TextView title = text(LocaleController.getString(R.string.OxChannelTitle), 24);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(title);
        content.addView(text(UserObject.getUserName(UserConfig.getInstance(account).getCurrentUser()), 16));
        content.addView(text(LocaleController.getString(R.string.OxChannelDisclosure), 16));
        statusView = text("", 16);
        content.addView(statusView);
        joinButton = button(content, R.string.OxChannelJoinAndStart, this::accept);
        retryButton = button(content, R.string.OxChannelRetry, this::check);
        button(content, R.string.OxChannelAccounts, this::chooseAccount);
        button(content, R.string.LogOut, this::confirmLogout);
        button(content, R.string.OxChannelExit, activity::finish);
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.addView(content);
        dialog.setContentView(scroll);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            if ((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }
            window.setStatusBarColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            window.setNavigationBarColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        }
        dialog.show();
        render();
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10));
        return view;
    }

    private Button button(LinearLayout parent, int label, Runnable action) {
        Button button = new Button(activity);
        button.setText(LocaleController.getString(label));
        button.setAllCaps(false);
        button.setOnClickListener(v -> {
            if (validIdentity() && !locked()) action.run();
        });
        parent.addView(button, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return button;
    }

    private void render() {
        if (statusView == null) return;
        String status = LocaleController.getString(statusText);
        statusView.setText(status);
        joinButton.setText(LocaleController.getString(state == State.CONSENT ? R.string.OxChannelStartContinue : botStarted ? R.string.OxChannelJoin : R.string.OxChannelJoinAndStart));
        joinButton.setEnabled(state == State.READY || state == State.CONSENT);
        retryButton.setEnabled(state != State.CHECKING);
    }

    private void chooseAccount() {
        ArrayList<Integer> slots = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        // UserConfig is lightweight; do not instantiate controllers for inactive slots.
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            UserConfig config = UserConfig.getInstance(i);
            if (i != account && config.isClientActivated()) {
                slots.add(i);
                names.add(UserObject.getUserName(config.getCurrentUser()));
            }
        }
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(R.string.OxChannelAccounts))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        if (slots.isEmpty()) builder.setMessage(LocaleController.getString(R.string.OxChannelNoAccounts));
        else builder.setItems(names.toArray(new String[0]), (d, which) -> {
            if (validIdentity() && !locked()) activity.switchToAccount(slots.get(which), true);
        });
        accountDialog = builder.show();
    }

    private void confirmLogout() {
        final int selected = account;
        final long selectedUser = userId;
        accountDialog = new android.app.AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(R.string.LogOut))
                .setMessage(LocaleController.getString(R.string.OxChannelLogoutConfirm))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.LogOut), (d, which) -> {
                    if (validIdentity() && !locked() && account == selected && userId == selectedUser) {
                        cancelRequest();
                        MessagesController.getInstance(selected).performLogout(1);
                    }
                }).show();
    }

    private void dismiss() {
        if (accountDialog != null) {
            try {
                accountDialog.dismiss();
            } catch (Exception ignored) {
            }
        }
        accountDialog = null;
        if (dialog != null) {
            try {
                dialog.dismiss();
            } catch (Exception ignored) {
            }
        }
        dialog = null;
        statusView = null;
        joinButton = null;
        retryButton = null;
    }
}
