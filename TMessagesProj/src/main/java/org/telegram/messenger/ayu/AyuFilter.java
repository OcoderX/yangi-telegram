package org.telegram.messenger.ayu;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;
import android.util.SparseBooleanArray;

import androidx.collection.LongSparseArray;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * AyuGram regex message filters.
 * <p>
 * Filters are stored as a JSON array inside {@link AyuConfig#regexFilters}. Every filter owns a
 * lazily compiled {@link Pattern}; broken regular expressions disable the filter and keep the
 * compilation error in {@link Filter#error} so the UI can show it.
 * <p>
 * Results are cached per dialog/message id and dropped on any configuration change
 * ({@link #rebuildCache()}), which also posts {@link NotificationCenter#ayuRegexFiltersUpdated}.
 */
public class AyuFilter {

    public static final String SHARE_LINK_PREFIX = "tg://ayu/filters?data=";

    // ---------------------------------------------------------------- model

    public static class Filter {

        public String id;
        /** the regular expression itself */
        public String text = "";
        public boolean enabled = true;
        /** null = follow {@link AyuConfig#regexFiltersCaseInsensitive} */
        public Boolean caseInsensitive;
        /** 0 = global, otherwise the filter only applies to this dialog */
        public long dialogId;
        public ArrayList<Long> excludedDialogs = new ArrayList<>();
        public boolean matchCaption = true;
        public boolean matchSenderName = false;

        /** last compilation error, null when the pattern is valid */
        public String error;

        private Pattern pattern;
        private boolean compiled;
        private boolean compiledCaseInsensitive;

        public Filter() {
            id = Long.toString(System.currentTimeMillis()) + "_" + Integer.toString((int) (Math.random() * 0x7fffffff), 36);
        }

        public boolean isCaseInsensitive() {
            return caseInsensitive != null ? caseInsensitive : AyuConfig.regexFiltersCaseInsensitive;
        }

        public void invalidate() {
            compiled = false;
            pattern = null;
            error = null;
        }

        /** compiles the pattern lazily; returns null (and fills {@link #error}) for invalid regex */
        public Pattern getPattern() {
            boolean ci = isCaseInsensitive();
            if (compiled && compiledCaseInsensitive == ci) {
                return pattern;
            }
            compiled = true;
            compiledCaseInsensitive = ci;
            pattern = null;
            error = null;
            if (TextUtils.isEmpty(text)) {
                error = "empty";
                return null;
            }
            try {
                int flags = Pattern.MULTILINE;
                if (ci) {
                    flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                }
                pattern = Pattern.compile(text, flags);
            } catch (PatternSyntaxException e) {
                error = e.getDescription() != null ? e.getDescription() : e.getMessage();
                pattern = null;
            } catch (Exception e) {
                error = e.getMessage();
                pattern = null;
            }
            return pattern;
        }

        public boolean isValid() {
            getPattern();
            return pattern != null;
        }

        public String getError() {
            getPattern();
            return error;
        }

        public Filter copy() {
            Filter f = new Filter();
            f.id = id;
            f.text = text;
            f.enabled = enabled;
            f.caseInsensitive = caseInsensitive;
            f.dialogId = dialogId;
            f.excludedDialogs = new ArrayList<>(excludedDialogs);
            f.matchCaption = matchCaption;
            f.matchSenderName = matchSenderName;
            return f;
        }

        public JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("text", text == null ? "" : text);
            o.put("enabled", enabled);
            if (caseInsensitive != null) {
                o.put("ci", caseInsensitive.booleanValue());
            }
            o.put("dialogId", dialogId);
            o.put("matchCaption", matchCaption);
            o.put("matchSenderName", matchSenderName);
            if (excludedDialogs != null && !excludedDialogs.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (int i = 0; i < excludedDialogs.size(); i++) {
                    arr.put(excludedDialogs.get(i).longValue());
                }
                o.put("excluded", arr);
            }
            return o;
        }

        public static Filter fromJson(JSONObject o) {
            Filter f = new Filter();
            String id = o.optString("id", null);
            if (!TextUtils.isEmpty(id)) {
                f.id = id;
            }
            f.text = o.optString("text", "");
            f.enabled = o.optBoolean("enabled", true);
            if (o.has("ci") && !o.isNull("ci")) {
                f.caseInsensitive = o.optBoolean("ci", true);
            }
            f.dialogId = o.optLong("dialogId", 0);
            f.matchCaption = o.optBoolean("matchCaption", true);
            f.matchSenderName = o.optBoolean("matchSenderName", false);
            JSONArray arr = o.optJSONArray("excluded");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    long did = arr.optLong(i, 0);
                    if (did != 0) {
                        f.excludedDialogs.add(did);
                    }
                }
            }
            return f;
        }
    }

    // ---------------------------------------------------------------- state

    private static ArrayList<Filter> filters;
    private static String parsedFrom;

    private static final LongSparseArray<SparseBooleanArray> cache = new LongSparseArray<>();
    private static final LongSparseArray<Boolean> groupCache = new LongSparseArray<>();

    public static synchronized ArrayList<Filter> getFilters() {
        String raw = AyuConfig.regexFilters;
        if (filters == null || !TextUtils.equals(raw, parsedFrom)) {
            filters = parse(raw);
            parsedFrom = raw;
        }
        return filters;
    }

    private static ArrayList<Filter> parse(String json) {
        ArrayList<Filter> result = new ArrayList<>();
        if (TextUtils.isEmpty(json)) {
            return result;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    result.add(Filter.fromJson(o));
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result;
    }

    public static String serialize(List<Filter> list) {
        JSONArray arr = new JSONArray();
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                try {
                    arr.put(list.get(i).toJson());
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }
        return arr.toString();
    }

    /** persists the current list, drops the cache and notifies the UI */
    public static synchronized void save() {
        ArrayList<Filter> list = getFilters();
        String json = serialize(list);
        parsedFrom = json;
        AyuConfig.setRegexFilters(json);
        rebuildCache();
    }

    public static void rebuildCache() {
        synchronized (cache) {
            cache.clear();
            groupCache.clear();
        }
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.ayuRegexFiltersUpdated));
    }

    // ---------------------------------------------------------------- editing

    public static synchronized Filter getById(String id) {
        if (id == null) {
            return null;
        }
        ArrayList<Filter> list = getFilters();
        for (int i = 0; i < list.size(); i++) {
            if (id.equals(list.get(i).id)) {
                return list.get(i);
            }
        }
        return null;
    }

    public static synchronized void add(Filter filter) {
        if (filter == null) {
            return;
        }
        getFilters().add(filter);
        save();
    }

    public static synchronized void update(Filter filter) {
        if (filter == null) {
            return;
        }
        filter.invalidate();
        ArrayList<Filter> list = getFilters();
        for (int i = 0; i < list.size(); i++) {
            if (filter.id.equals(list.get(i).id)) {
                list.set(i, filter);
                save();
                return;
            }
        }
        list.add(filter);
        save();
    }

    public static synchronized void remove(Filter filter) {
        if (filter == null) {
            return;
        }
        ArrayList<Filter> list = getFilters();
        for (int i = 0; i < list.size(); i++) {
            if (filter.id.equals(list.get(i).id)) {
                list.remove(i);
                save();
                return;
            }
        }
    }

    public static synchronized void move(int from, int to) {
        ArrayList<Filter> list = getFilters();
        if (from < 0 || to < 0 || from >= list.size() || to >= list.size() || from == to) {
            return;
        }
        Collections.swap(list, from, to);
        save();
    }

    // ---------------------------------------------------------------- matching

    /** true when at least one of the filtering features is turned on */
    public static boolean isEnabled() {
        return AyuConfig.regexFiltersEnabled || AyuConfig.filterBlockedUsers;
    }

    /**
     * Entry point used by ChatActivity: honors {@link AyuConfig#regexFiltersInChats} for the regex
     * part while the blocked-users part only depends on {@link AyuConfig#filterBlockedUsers}.
     */
    public static boolean isFilteredInChat(MessageObject msg) {
        if (msg == null || msg.messageOwner == null) {
            return false;
        }
        // never hide synthetic rows (date separators, thread headers, unread markers)
        if (msg.isDateObject || msg.getId() == 0) {
            return false;
        }
        if (AyuConfig.filterBlockedUsers && isFilteredBySender(msg)) {
            return true;
        }
        if (!AyuConfig.regexFiltersEnabled || !AyuConfig.regexFiltersInChats) {
            return false;
        }
        return isFiltered(msg);
    }

    public static boolean isFiltered(MessageObject msg) {
        if (msg == null || msg.messageOwner == null) {
            return false;
        }
        if (!isEnabled()) {
            return false;
        }
        if (AyuConfig.filterBlockedUsers && isFilteredBySender(msg)) {
            return true;
        }
        if (!AyuConfig.regexFiltersEnabled) {
            return false;
        }
        final long dialogId = msg.getDialogId();
        final int messageId = msg.getId();
        final long groupId = msg.hasValidGroupId() ? msg.getGroupId() : 0;

        if (groupId == 0) {
            Boolean cached = getCached(dialogId, messageId);
            if (cached != null) {
                return cached;
            }
        }
        boolean result = matches(msg.messageOwner, dialogId, msg.currentAccount);
        if (groupId != 0) {
            synchronized (cache) {
                Boolean groupResult = groupCache.get(groupId);
                if (result) {
                    groupCache.put(groupId, Boolean.TRUE);
                } else if (groupResult != null && groupResult) {
                    result = true;
                }
            }
            return result;
        }
        putCached(dialogId, messageId, result);
        return result;
    }

    public static boolean isFiltered(TLRPC.Message msg, long dialogId) {
        return isFiltered(msg, dialogId, UserConfig.selectedAccount);
    }

    public static boolean isFiltered(TLRPC.Message msg, long dialogId, int account) {
        if (msg == null) {
            return false;
        }
        if (!isEnabled()) {
            return false;
        }
        if (dialogId == 0) {
            dialogId = MessageObject.getDialogId(msg);
        }
        if (AyuConfig.filterBlockedUsers && isFilteredBySender(msg, dialogId, account)) {
            return true;
        }
        if (!AyuConfig.regexFiltersEnabled) {
            return false;
        }
        Boolean cached = getCached(dialogId, msg.id);
        if (cached != null) {
            return cached;
        }
        boolean result = matches(msg, dialogId, account);
        putCached(dialogId, msg.id, result);
        return result;
    }

    /**
     * Pre-scans a list of messages so that every member of an album gets the verdict of the one
     * message that actually carries the group caption.
     */
    public static void prescanGroups(List<MessageObject> list) {
        if (list == null || list.isEmpty() || !AyuConfig.regexFiltersEnabled) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            MessageObject msg = list.get(i);
            if (msg == null || msg.messageOwner == null || !msg.hasValidGroupId()) {
                continue;
            }
            if (TextUtils.isEmpty(msg.messageOwner.message)) {
                continue;
            }
            long groupId = msg.getGroupId();
            synchronized (cache) {
                if (groupCache.get(groupId) != null) {
                    continue;
                }
            }
            if (matches(msg.messageOwner, msg.getDialogId(), msg.currentAccount)) {
                synchronized (cache) {
                    groupCache.put(groupId, Boolean.TRUE);
                }
            }
        }
    }

    public static boolean isFilteredBySender(MessageObject msg) {
        if (msg == null || msg.messageOwner == null) {
            return false;
        }
        return isFilteredBySender(msg.messageOwner, msg.getDialogId(), msg.currentAccount);
    }

    public static boolean isFilteredBySender(TLRPC.Message msg, long dialogId, int account) {
        if (!AyuConfig.filterBlockedUsers || msg == null || msg.out) {
            return false;
        }
        long from = MessageObject.getFromChatId(msg);
        if (from == 0) {
            from = dialogId;
        }
        if (from == 0) {
            return false;
        }
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return false;
        }
        if (from == UserConfig.getInstance(account).getClientUserId()) {
            return false;
        }
        MessagesController controller = MessagesController.getInstance(account);
        return controller != null && controller.blockePeers.indexOfKey(from) >= 0;
    }

    private static boolean matches(TLRPC.Message message, long dialogId, int account) {
        if (message == null) {
            return false;
        }
        ArrayList<Filter> list = getFilters();
        if (list.isEmpty()) {
            return false;
        }
        final String text = message.message;
        final boolean isCaption = hasMedia(message);
        String senderName = null;
        for (int i = 0; i < list.size(); i++) {
            Filter f = list.get(i);
            if (f == null || !f.enabled) {
                continue;
            }
            if (f.dialogId != 0 && f.dialogId != dialogId) {
                continue;
            }
            if (f.excludedDialogs != null && f.excludedDialogs.contains(dialogId)) {
                continue;
            }
            Pattern pattern = f.getPattern();
            if (pattern == null) {
                continue;
            }
            if (!TextUtils.isEmpty(text) && (!isCaption || f.matchCaption)) {
                try {
                    if (pattern.matcher(text).find()) {
                        return true;
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            if (f.matchSenderName) {
                if (senderName == null) {
                    senderName = getSenderName(message, dialogId, account);
                    if (senderName == null) {
                        senderName = "";
                    }
                }
                if (!TextUtils.isEmpty(senderName)) {
                    try {
                        if (pattern.matcher(senderName).find()) {
                            return true;
                        }
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasMedia(TLRPC.Message message) {
        return message.media != null
                && !(message.media instanceof TLRPC.TL_messageMediaEmpty)
                && !(message.media instanceof TLRPC.TL_messageMediaWebPage);
    }

    private static String getSenderName(TLRPC.Message message, long dialogId, int account) {
        try {
            long from = MessageObject.getFromChatId(message);
            if (from == 0) {
                from = dialogId;
            }
            if (from == 0) {
                return null;
            }
            return DialogObject.getName(account, from);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- cache

    private static Boolean getCached(long dialogId, int messageId) {
        synchronized (cache) {
            SparseBooleanArray dialogCache = cache.get(dialogId);
            if (dialogCache == null) {
                return null;
            }
            int index = dialogCache.indexOfKey(messageId);
            if (index < 0) {
                return null;
            }
            return dialogCache.valueAt(index);
        }
    }

    private static void putCached(long dialogId, int messageId, boolean value) {
        synchronized (cache) {
            SparseBooleanArray dialogCache = cache.get(dialogId);
            if (dialogCache == null) {
                dialogCache = new SparseBooleanArray();
                cache.put(dialogId, dialogCache);
            }
            if (dialogCache.size() > 4000) {
                dialogCache.clear();
            }
            dialogCache.put(messageId, value);
        }
    }

    // ---------------------------------------------------------------- share / import

    /** {@code tg://ayu/filters?data=<base64 json>} */
    public static String exportShareLink() {
        return exportShareLink(getFilters());
    }

    public static String exportShareLink(List<Filter> list) {
        try {
            String json = serialize(list);
            byte[] bytes = json.getBytes("UTF-8");
            return SHARE_LINK_PREFIX + Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (UnsupportedEncodingException e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * Accepts a full {@code tg://ayu/filters?data=...} link, a bare base64 payload or a raw JSON
     * array and merges the filters into the current list.
     *
     * @return the number of imported filters, -1 when the input could not be parsed
     */
    public static int importFromLink(String link) {
        if (TextUtils.isEmpty(link)) {
            return -1;
        }
        link = link.trim();
        String payload = null;
        if (link.startsWith("[")) {
            payload = link;
        } else {
            String data = null;
            int idx = link.indexOf("data=");
            if (idx >= 0) {
                try {
                    Uri uri = Uri.parse(link);
                    data = uri.getQueryParameter("data");
                } catch (Exception ignore) {
                }
                if (TextUtils.isEmpty(data)) {
                    data = link.substring(idx + 5);
                    int amp = data.indexOf('&');
                    if (amp >= 0) {
                        data = data.substring(0, amp);
                    }
                }
            } else {
                data = link;
            }
            if (TextUtils.isEmpty(data)) {
                return -1;
            }
            try {
                byte[] bytes = Base64.decode(data, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                payload = new String(bytes, "UTF-8");
            } catch (Exception e) {
                return -1;
            }
        }
        ArrayList<Filter> imported = parse(payload);
        if (imported.isEmpty()) {
            return -1;
        }
        int added = 0;
        synchronized (AyuFilter.class) {
            ArrayList<Filter> list = getFilters();
            for (int i = 0; i < imported.size(); i++) {
                Filter f = imported.get(i);
                if (f == null || TextUtils.isEmpty(f.text)) {
                    continue;
                }
                boolean duplicate = false;
                for (int j = 0; j < list.size(); j++) {
                    Filter e = list.get(j);
                    if (TextUtils.equals(e.text, f.text) && e.dialogId == f.dialogId) {
                        duplicate = true;
                        break;
                    }
                }
                if (duplicate) {
                    continue;
                }
                list.add(f);
                added++;
            }
            if (added > 0) {
                save();
            }
        }
        return added;
    }

    /** true when the uri is an AyuGram filters deep link */
    public static boolean isFiltersLink(String link) {
        return !TextUtils.isEmpty(link) && link.startsWith(SHARE_LINK_PREFIX);
    }
}
