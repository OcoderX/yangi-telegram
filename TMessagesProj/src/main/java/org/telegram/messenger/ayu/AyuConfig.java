package org.telegram.messenger.ayu;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;

/**
 * Central AyuGram configuration. All fields are read on startup and updated through the setters
 * below so that every call site can read plain static fields without touching SharedPreferences.
 * <p>
 * Ghost mode semantics (AyuGram): the "send*" flags are TRUE in normal mode, FALSE in ghost mode.
 * {@link #isGhostModeActive()} is true when all core flags are in ghost state.
 */
public class AyuConfig {

    public static SharedPreferences preferences;
    private static boolean loaded;

    // ---------------- Colored message status ----------------
    /** read (double tick) */
    public static final int STATUS_COLOR_READ = 0xFF34C759;
    /** sent / delivered but not read (single tick) */
    public static final int STATUS_COLOR_SENT = 0xFFFF3B30;
    /** sending (clock) */
    public static final int STATUS_COLOR_PENDING = 0xFFFFC107;

    // ---------------- Ghost mode ----------------
    /** false = do not send read receipts (messages_readHistory, readMessageContents, readDiscussion, readReactions) */
    public static boolean sendReadPackets = true;
    /** false = do not mark stories as read (stories_readStories, incrementStoryViews) */
    public static boolean sendReadStories = true;
    /** false = do not send online status (account_updateStatus offline=false / network resume) */
    public static boolean sendOnlinePackets = true;
    /** false = do not send typing / upload progress (messages_setTyping, incl. upload progress actions) */
    public static boolean sendUploadProgress = true;
    /** true = send an offline packet right after going online */
    public static boolean sendOfflinePacketAfterOnline = false;
    /** true = mark chat as read after sending a message (even in ghost mode) */
    public static boolean markReadAfterSend = true;
    /** true = mark chat as read on interaction (reaction, reply, etc.) even in ghost mode */
    public static boolean markReadAfterAction = false;
    /** true = send messages via scheduled messages so no online status leaks */
    public static boolean useScheduledMessages = false;
    /** true = show an alert suggesting ghost mode before opening a story */
    public static boolean alertBeforeOpeningStory = false;

    // ---------------- Message history / "spy" ----------------
    public static boolean saveDeletedMessages = true;
    public static boolean saveMessagesHistory = true;
    public static boolean saveMedia = true;
    public static boolean saveMediaInPrivateChats = true;
    public static boolean saveMediaInPublicChannels = false;
    public static boolean saveMediaInPrivateChannels = true;
    public static boolean saveMediaInPublicGroups = false;
    public static boolean saveMediaInPrivateGroups = true;
    public static boolean saveForBots = true;
    public static boolean saveFormatting = true;
    public static boolean saveReactions = true;
    /** keep self-destructing (TTL / view once) media forever */
    public static boolean saveSelfDestructingMedia = true;

    // ---------------- Quality of life ----------------
    public static boolean keepAliveService = false;
    public static boolean disableAds = true;
    public static boolean disableProxySponsor = true;
    public static boolean localPremium = false;
    public static boolean hideStories = false;
    public static boolean keepKickedChats = true;
    public static boolean allowScreenshotsInSecretChats = true;
    public static boolean disableEmulatorDetection = true;
    public static boolean showExpireButton = true;
    public static boolean disableCrashlytics = false;

    // ---------------- Dynamic Island ----------------
    public static boolean dynamicIsland = true;
    public static boolean islandCalls = true;
    public static boolean islandMusic = true;
    public static boolean islandRecording = true;
    public static boolean islandDownloads = true;
    public static boolean islandGhost = true;
    public static boolean islandIdle = false;
    /** uploads (the AyuGram upload queue) may occupy the island next to downloads */
    public static boolean islandUploads = true;
    /** the Dynamic Island hosts the player, so the stock audio strip stays hidden */
    public static boolean islandReplacePlayer = true;

    // ---------------- Regex filters ----------------
    public static boolean regexFiltersEnabled = false;
    public static boolean regexFiltersInChats = false;
    public static boolean regexFiltersCaseInsensitive = true;
    public static boolean filterBlockedUsers = true;
    /** JSON array of filters; see AyuFilter */
    public static String regexFilters = "[]";

    // ---------------- Customization ----------------
    public static String deletedMarkText = AyuConstants.DEFAULT_DELETED_MARK;
    public static String editedMarkText = "";
    public static boolean showGhostToggleInDrawer = true;
    public static boolean showKillButtonInDrawer = false;
    public static boolean showPeerId = true;
    public static boolean showMessageSeconds = false;
    public static boolean showMessageDetails = true;
    public static boolean showMessageShadow = false;
    public static boolean simpleQuotesAndReplies = false;
    /** true = outgoing message ticks are green (read) / red (delivered) and the sending clock is yellow */
    public static boolean coloredMessageStatus = true;

    /** true = every chat in the main list is drawn as a separate rounded card */
    public static boolean cardChatList = true;

    // ---------------- AI Tools (message context menu) ----------------
    public static final int AI_PROVIDER_CLAUDE = 0;
    public static final int AI_PROVIDER_OPENAI = 1;

    public static final String DEFAULT_AI_BASE_URL_CLAUDE = "https://api.anthropic.com";
    public static final String DEFAULT_AI_BASE_URL_OPENAI = "https://api.openai.com/v1";
    public static final String DEFAULT_AI_MODEL_CLAUDE = "claude-sonnet-5";
    public static final String DEFAULT_AI_MODEL_OPENAI = "gpt-4o-mini";

    /** master toggle of the "AI Tools" context menu entry */
    public static boolean aiToolsEnabled = false;
    /** {@link #AI_PROVIDER_CLAUDE} or {@link #AI_PROVIDER_OPENAI} */
    public static int aiProvider = AI_PROVIDER_CLAUDE;
    /** secret: never logged, never sent anywhere but to the configured provider */
    public static String aiApiKey = "";
    /** empty = provider default, see {@link #getAiModel()} */
    public static String aiModel = "";
    /** empty = provider default, see {@link #getAiBaseUrl()} */
    public static String aiBaseUrl = "";

    // ---------------- Message context menu ----------------
    /** true = a single tap on a message bubble opens the context menu */
    public static boolean contextMenuOnTap = false;
    /** true = show the "Marking message" bookmark entry and the bookmark badge */
    public static boolean markedMessagesEnabled = true;

    // ---------------- AyuSync ----------------
    public static boolean syncEnabled = false;
    public static boolean useSecureConnection = true;
    public static String syncServerURL = AyuConstants.DEFAULT_AYUSYNC_SERVER;
    public static String syncServerToken = "";

    // ---------------- Debug ----------------
    public static boolean walMode = true;

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        preferences = ApplicationLoader.applicationContext.getSharedPreferences(AyuConstants.PREFS_NAME, Context.MODE_PRIVATE);

        sendReadPackets = preferences.getBoolean("sendReadPackets", true);
        sendReadStories = preferences.getBoolean("sendReadStories", true);
        sendOnlinePackets = preferences.getBoolean("sendOnlinePackets", true);
        sendUploadProgress = preferences.getBoolean("sendUploadProgress", true);
        sendOfflinePacketAfterOnline = preferences.getBoolean("sendOfflinePacketAfterOnline", false);
        markReadAfterSend = preferences.getBoolean("markReadAfterSend", true);
        markReadAfterAction = preferences.getBoolean("markReadAfterAction", false);
        useScheduledMessages = preferences.getBoolean("useScheduledMessages", false);
        alertBeforeOpeningStory = preferences.getBoolean("alertBeforeOpeningStory", false);

        saveDeletedMessages = preferences.getBoolean("saveDeletedMessages", true);
        saveMessagesHistory = preferences.getBoolean("saveMessagesHistory", true);
        saveMedia = preferences.getBoolean("saveMedia", true);
        saveMediaInPrivateChats = preferences.getBoolean("saveMediaInPrivateChats", true);
        saveMediaInPublicChannels = preferences.getBoolean("saveMediaInPublicChannels", false);
        saveMediaInPrivateChannels = preferences.getBoolean("saveMediaInPrivateChannels", true);
        saveMediaInPublicGroups = preferences.getBoolean("saveMediaInPublicGroups", false);
        saveMediaInPrivateGroups = preferences.getBoolean("saveMediaInPrivateGroups", true);
        saveForBots = preferences.getBoolean("saveForBots", true);
        saveFormatting = preferences.getBoolean("saveFormatting", true);
        saveReactions = preferences.getBoolean("saveReactions", true);
        saveSelfDestructingMedia = preferences.getBoolean("saveSelfDestructingMedia", true);

        keepAliveService = preferences.getBoolean("keepAliveService", false);
        disableAds = preferences.getBoolean("disableAds", true);
        disableProxySponsor = preferences.getBoolean("disableProxySponsor", true);
        localPremium = preferences.getBoolean("localPremium", false);
        hideStories = preferences.getBoolean("hideStories", false);
        keepKickedChats = preferences.getBoolean("keepKickedChats", true);
        allowScreenshotsInSecretChats = preferences.getBoolean("allowScreenshotsInSecretChats", true);
        disableEmulatorDetection = preferences.getBoolean("disableEmulatorDetection", true);
        showExpireButton = preferences.getBoolean("showExpireButton", true);
        disableCrashlytics = preferences.getBoolean("disableCrashlytics", false);

        dynamicIsland = preferences.getBoolean("dynamicIsland", true);
        islandCalls = preferences.getBoolean("islandCalls", true);
        islandMusic = preferences.getBoolean("islandMusic", true);
        islandRecording = preferences.getBoolean("islandRecording", true);
        islandDownloads = preferences.getBoolean("islandDownloads", true);
        islandGhost = preferences.getBoolean("islandGhost", true);
        islandIdle = preferences.getBoolean("islandIdle", false);
        islandUploads = preferences.getBoolean("islandUploads", true);
        islandReplacePlayer = preferences.getBoolean("islandReplacePlayer", true);

        regexFiltersEnabled = preferences.getBoolean("regexFiltersEnabled", false);
        regexFiltersInChats = preferences.getBoolean("regexFiltersInChats", false);
        regexFiltersCaseInsensitive = preferences.getBoolean("regexFiltersCaseInsensitive", true);
        filterBlockedUsers = preferences.getBoolean("filterBlockedUsers", true);
        regexFilters = preferences.getString("regexFilters", "[]");

        deletedMarkText = preferences.getString("deletedMarkText", AyuConstants.DEFAULT_DELETED_MARK);
        editedMarkText = preferences.getString("editedMarkText", "");
        showGhostToggleInDrawer = preferences.getBoolean("showGhostToggleInDrawer", true);
        showKillButtonInDrawer = preferences.getBoolean("showKillButtonInDrawer", false);
        showPeerId = preferences.getBoolean("showPeerId", true);
        showMessageSeconds = preferences.getBoolean("showMessageSeconds", false);
        showMessageDetails = preferences.getBoolean("showMessageDetails", true);
        showMessageShadow = preferences.getBoolean("showMessageShadow", false);
        simpleQuotesAndReplies = preferences.getBoolean("simpleQuotesAndReplies", false);
        coloredMessageStatus = preferences.getBoolean("coloredMessageStatus", true);

        cardChatList = preferences.getBoolean("cardChatList", true);

        aiToolsEnabled = preferences.getBoolean("aiToolsEnabled", false);
        aiProvider = preferences.getInt("aiProvider", AI_PROVIDER_CLAUDE);
        aiApiKey = preferences.getString("aiApiKey", "");
        aiModel = preferences.getString("aiModel", "");
        aiBaseUrl = preferences.getString("aiBaseUrl", "");

        contextMenuOnTap = preferences.getBoolean("contextMenuOnTap", false);
        markedMessagesEnabled = preferences.getBoolean("markedMessagesEnabled", true);

        syncEnabled = preferences.getBoolean("syncEnabled", false);
        useSecureConnection = preferences.getBoolean("useSecureConnection", true);
        syncServerURL = preferences.getString("syncServerURL", AyuConstants.DEFAULT_AYUSYNC_SERVER);
        syncServerToken = preferences.getString("syncServerToken", "");

        walMode = preferences.getBoolean("walMode", true);

        loaded = true;
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    // ---------------- generic setters ----------------
    public static void putBoolean(String key, boolean value) {
        ensureLoaded();
        preferences.edit().putBoolean(key, value).apply();
    }

    public static void putString(String key, String value) {
        ensureLoaded();
        preferences.edit().putString(key, value).apply();
    }

    public static void putInt(String key, int value) {
        ensureLoaded();
        preferences.edit().putInt(key, value).apply();
    }

    // ---------------- ghost mode helpers ----------------
    public static boolean isGhostModeActive() {
        ensureLoaded();
        return !sendReadPackets && !sendReadStories && !sendOnlinePackets && !sendUploadProgress && sendOfflinePacketAfterOnline;
    }

    /** number of enabled ghost sub-options, 0..5 */
    public static int getGhostModeSelectedCount() {
        ensureLoaded();
        int c = 0;
        if (!sendReadPackets) c++;
        if (!sendReadStories) c++;
        if (!sendOnlinePackets) c++;
        if (!sendUploadProgress) c++;
        if (sendOfflinePacketAfterOnline) c++;
        return c;
    }

    public static void setGhostMode(boolean enabled) {
        ensureLoaded();
        sendReadPackets = !enabled;
        sendReadStories = !enabled;
        sendOnlinePackets = !enabled;
        sendUploadProgress = !enabled;
        sendOfflinePacketAfterOnline = enabled;
        preferences.edit()
                .putBoolean("sendReadPackets", sendReadPackets)
                .putBoolean("sendReadStories", sendReadStories)
                .putBoolean("sendOnlinePackets", sendOnlinePackets)
                .putBoolean("sendUploadProgress", sendUploadProgress)
                .putBoolean("sendOfflinePacketAfterOnline", sendOfflinePacketAfterOnline)
                .apply();
        AyuGhostHelper.onOnlineSettingsChanged();
        AyuState.onGhostModeChanged();
    }

    public static void toggleGhostMode() {
        setGhostMode(!isGhostModeActive());
    }

    public static void setSendReadPackets(boolean v) { sendReadPackets = v; putBoolean("sendReadPackets", v); AyuState.onGhostModeChanged(); }
    public static void setSendReadStories(boolean v) { sendReadStories = v; putBoolean("sendReadStories", v); AyuState.onGhostModeChanged(); }
    public static void setSendOnlinePackets(boolean v) { sendOnlinePackets = v; putBoolean("sendOnlinePackets", v); AyuGhostHelper.onOnlineSettingsChanged(); AyuState.onGhostModeChanged(); }
    public static void setSendUploadProgress(boolean v) { sendUploadProgress = v; putBoolean("sendUploadProgress", v); AyuState.onGhostModeChanged(); }
    public static void setSendOfflinePacketAfterOnline(boolean v) { sendOfflinePacketAfterOnline = v; putBoolean("sendOfflinePacketAfterOnline", v); AyuGhostHelper.onOnlineSettingsChanged(); AyuState.onGhostModeChanged(); }
    public static void setMarkReadAfterSend(boolean v) { markReadAfterSend = v; putBoolean("markReadAfterSend", v); }
    public static void setMarkReadAfterAction(boolean v) { markReadAfterAction = v; putBoolean("markReadAfterAction", v); }
    public static void setUseScheduledMessages(boolean v) { useScheduledMessages = v; putBoolean("useScheduledMessages", v); }
    public static void setAlertBeforeOpeningStory(boolean v) { alertBeforeOpeningStory = v; putBoolean("alertBeforeOpeningStory", v); }

    public static void setSaveDeletedMessages(boolean v) { saveDeletedMessages = v; putBoolean("saveDeletedMessages", v); }
    public static void setSaveMessagesHistory(boolean v) { saveMessagesHistory = v; putBoolean("saveMessagesHistory", v); }
    public static void setSaveMedia(boolean v) { saveMedia = v; putBoolean("saveMedia", v); }
    public static void setSaveMediaInPrivateChats(boolean v) { saveMediaInPrivateChats = v; putBoolean("saveMediaInPrivateChats", v); }
    public static void setSaveMediaInPublicChannels(boolean v) { saveMediaInPublicChannels = v; putBoolean("saveMediaInPublicChannels", v); }
    public static void setSaveMediaInPrivateChannels(boolean v) { saveMediaInPrivateChannels = v; putBoolean("saveMediaInPrivateChannels", v); }
    public static void setSaveMediaInPublicGroups(boolean v) { saveMediaInPublicGroups = v; putBoolean("saveMediaInPublicGroups", v); }
    public static void setSaveMediaInPrivateGroups(boolean v) { saveMediaInPrivateGroups = v; putBoolean("saveMediaInPrivateGroups", v); }
    public static void setSaveForBots(boolean v) { saveForBots = v; putBoolean("saveForBots", v); }
    public static void setSaveFormatting(boolean v) { saveFormatting = v; putBoolean("saveFormatting", v); }
    public static void setSaveReactions(boolean v) { saveReactions = v; putBoolean("saveReactions", v); }
    public static void setSaveSelfDestructingMedia(boolean v) { saveSelfDestructingMedia = v; putBoolean("saveSelfDestructingMedia", v); }

    public static void setKeepAliveService(boolean v) { keepAliveService = v; putBoolean("keepAliveService", v); }
    public static void setDisableAds(boolean v) { disableAds = v; putBoolean("disableAds", v); }
    public static void setDisableProxySponsor(boolean v) { disableProxySponsor = v; putBoolean("disableProxySponsor", v); }
    public static void setLocalPremium(boolean v) { localPremium = v; putBoolean("localPremium", v); }
    public static void setHideStories(boolean v) { hideStories = v; putBoolean("hideStories", v); }
    public static void setKeepKickedChats(boolean v) { keepKickedChats = v; putBoolean("keepKickedChats", v); }
    public static void setAllowScreenshotsInSecretChats(boolean v) { allowScreenshotsInSecretChats = v; putBoolean("allowScreenshotsInSecretChats", v); }
    public static void setDisableEmulatorDetection(boolean v) { disableEmulatorDetection = v; putBoolean("disableEmulatorDetection", v); }
    public static void setShowExpireButton(boolean v) { showExpireButton = v; putBoolean("showExpireButton", v); }
    public static void setDisableCrashlytics(boolean v) { disableCrashlytics = v; putBoolean("disableCrashlytics", v); }

    public static void setDynamicIsland(boolean v) { dynamicIsland = v; putBoolean("dynamicIsland", v); }
    public static void setIslandCalls(boolean v) { islandCalls = v; putBoolean("islandCalls", v); }
    public static void setIslandMusic(boolean v) { islandMusic = v; putBoolean("islandMusic", v); }
    public static void setIslandRecording(boolean v) { islandRecording = v; putBoolean("islandRecording", v); }
    public static void setIslandDownloads(boolean v) { islandDownloads = v; putBoolean("islandDownloads", v); }
    public static void setIslandGhost(boolean v) { islandGhost = v; putBoolean("islandGhost", v); }
    public static void setIslandIdle(boolean v) { islandIdle = v; putBoolean("islandIdle", v); }
    public static void setIslandUploads(boolean v) { islandUploads = v; putBoolean("islandUploads", v); }
    public static void setIslandReplacePlayer(boolean v) { islandReplacePlayer = v; putBoolean("islandReplacePlayer", v); }

    public static void setRegexFiltersEnabled(boolean v) { regexFiltersEnabled = v; putBoolean("regexFiltersEnabled", v); }
    public static void setRegexFiltersInChats(boolean v) { regexFiltersInChats = v; putBoolean("regexFiltersInChats", v); }
    public static void setRegexFiltersCaseInsensitive(boolean v) { regexFiltersCaseInsensitive = v; putBoolean("regexFiltersCaseInsensitive", v); }
    public static void setFilterBlockedUsers(boolean v) { filterBlockedUsers = v; putBoolean("filterBlockedUsers", v); }
    public static void setRegexFilters(String json) { regexFilters = json; putString("regexFilters", json); }

    public static void setDeletedMarkText(String v) { deletedMarkText = v; putString("deletedMarkText", v); }
    public static void setEditedMarkText(String v) { editedMarkText = v; putString("editedMarkText", v); }
    public static void setShowGhostToggleInDrawer(boolean v) { showGhostToggleInDrawer = v; putBoolean("showGhostToggleInDrawer", v); }
    public static void setShowKillButtonInDrawer(boolean v) { showKillButtonInDrawer = v; putBoolean("showKillButtonInDrawer", v); }
    public static void setShowPeerId(boolean v) { showPeerId = v; putBoolean("showPeerId", v); }
    public static void setShowMessageSeconds(boolean v) { showMessageSeconds = v; putBoolean("showMessageSeconds", v); }
    public static void setShowMessageDetails(boolean v) { showMessageDetails = v; putBoolean("showMessageDetails", v); }
    public static void setShowMessageShadow(boolean v) { showMessageShadow = v; putBoolean("showMessageShadow", v); }
    public static void setSimpleQuotesAndReplies(boolean v) { simpleQuotesAndReplies = v; putBoolean("simpleQuotesAndReplies", v); }
    public static void setColoredMessageStatus(boolean v) { coloredMessageStatus = v; putBoolean("coloredMessageStatus", v); }

    public static void setCardChatList(boolean v) {
        cardChatList = v;
        putBoolean("cardChatList", v);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.dialogsNeedReload, true);
    }

    public static void setSyncEnabled(boolean v) { syncEnabled = v; putBoolean("syncEnabled", v); }

    // ---------------- AI Tools ----------------
    public static void setAiToolsEnabled(boolean v) { aiToolsEnabled = v; putBoolean("aiToolsEnabled", v); }
    public static void setAiProvider(int v) { aiProvider = v; putInt("aiProvider", v); }
    public static void setAiApiKey(String v) { aiApiKey = v == null ? "" : v.trim(); putString("aiApiKey", aiApiKey); }
    public static void setAiModel(String v) { aiModel = v == null ? "" : v.trim(); putString("aiModel", aiModel); }
    public static void setAiBaseUrl(String v) { aiBaseUrl = v == null ? "" : v.trim(); putString("aiBaseUrl", aiBaseUrl); }

    /** configured model, falling back to the provider default */
    public static String getAiModel() {
        ensureLoaded();
        if (aiModel != null && aiModel.length() > 0) {
            return aiModel;
        }
        return aiProvider == AI_PROVIDER_OPENAI ? DEFAULT_AI_MODEL_OPENAI : DEFAULT_AI_MODEL_CLAUDE;
    }

    /** configured base url, falling back to the provider default */
    public static String getAiBaseUrl() {
        ensureLoaded();
        if (aiBaseUrl != null && aiBaseUrl.length() > 0) {
            return aiBaseUrl;
        }
        return aiProvider == AI_PROVIDER_OPENAI ? DEFAULT_AI_BASE_URL_OPENAI : DEFAULT_AI_BASE_URL_CLAUDE;
    }

    // ---------------- Message context menu ----------------
    public static void setContextMenuOnTap(boolean v) { contextMenuOnTap = v; putBoolean("contextMenuOnTap", v); }
    public static void setMarkedMessagesEnabled(boolean v) { markedMessagesEnabled = v; putBoolean("markedMessagesEnabled", v); }
    public static void setUseSecureConnection(boolean v) { useSecureConnection = v; putBoolean("useSecureConnection", v); }
    public static void setSyncServerURL(String v) { syncServerURL = v; putString("syncServerURL", v); }
    public static void setSyncServerToken(String v) { syncServerToken = v; putString("syncServerToken", v); }

    public static void setWalMode(boolean v) { walMode = v; putBoolean("walMode", v); }

    // ---------------- derived helpers ----------------
    public static String getDeletedMark() {
        ensureLoaded();
        return deletedMarkText == null || deletedMarkText.isEmpty() ? AyuConstants.DEFAULT_DELETED_MARK : deletedMarkText;
    }

    public static String getEditedMark() {
        ensureLoaded();
        if (editedMarkText == null || editedMarkText.isEmpty()) {
            return LocaleController.getString(R.string.EditedMessage);
        }
        return editedMarkText;
    }

    /** true if the given dialog type allows media saving according to the "save media in ..." options */
    public static boolean isSaveMediaAllowed(boolean isPrivateChat, boolean isBot, boolean isChannel, boolean isPublic) {
        ensureLoaded();
        if (!saveMedia) {
            return false;
        }
        if (isBot) {
            return saveForBots && saveMediaInPrivateChats;
        }
        if (isPrivateChat) {
            return saveMediaInPrivateChats;
        }
        if (isChannel) {
            return isPublic ? saveMediaInPublicChannels : saveMediaInPrivateChannels;
        }
        return isPublic ? saveMediaInPublicGroups : saveMediaInPrivateGroups;
    }
}
