package org.telegram.messenger.ayu.antidelete;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Preferences owned by the AyuGram "anti delete" feature.
 * <p>
 * Deliberately kept out of {@link org.telegram.messenger.ayu.AyuConfig} (which other features own):
 * everything here only influences how deleted messages are kept / rendered.
 * <p>
 * Every field is a plain static so that the hot paths (the {@code shouldSave()} check that runs for
 * every deleted message, and {@code ChatMessageCell} which runs for every visible bubble) never have
 * to touch {@link SharedPreferences}. Writes go through the setters below.
 */
public class AyuAntiDeleteConfig {

    public static final String PREFS_NAME = "ayu_antidelete";

    private static SharedPreferences preferences;
    private static volatile boolean loaded;

    /** draw the localized "deleted by author" label next to the deleted mark instead of the mark alone */
    public static boolean showDeletedLabel = true;
    /** render the whole bubble at {@link #DELETED_MESSAGE_ALPHA} opacity for messages kept via anti-delete */
    public static boolean dimDeletedMessages = true;
    /** opacity applied to a bubble when {@link #dimDeletedMessages} is on */
    public static final float DELETED_MESSAGE_ALPHA = 0.6f;
    /**
     * keep the tail of a dialog when its history is cleared ("clear history").
     * <p>
     * ayu: defaults to <b>off</b>. "Clear history" is always started by the user on this device, so
     * keeping 300 ghosts of the history the user just wiped is the opposite of what was asked for.
     * When this is off the already stored rows of the dialog are dropped too, so nothing resurrects.
     */
    public static boolean keepOnClearHistory = false;
    /** keep the tail of a dialog when the whole dialog is deleted / left (ayu: off by default, see above) */
    public static boolean keepOnDeleteDialog = false;
    /**
     * keep our own outgoing messages when they are deleted somewhere else (typically "delete for
     * everyone" from another device or another client).
     * <p>
     * ayu: off by default - a message the user wrote themselves coming back as
     * "🧹 deleted by author" is confusing and can not be explained by the feature's purpose
     * (seeing what the <i>other</i> side deleted).
     */
    public static boolean keepOwnDeleted = false;
    /**
     * put the saved photo copies back into the place {@code FileLoader} looks at, so a restored photo
     * still opens after Telegram evicted it from its cache. Documents do not need this (they are
     * resolved through {@code TLRPC.Document.localPath} / {@code attachPath}).
     */
    public static boolean restoreMediaToCache = true;

    /** dialogs the user explicitly excluded from saving; copy-on-write so readers need no lock */
    private static volatile Set<Long> excludedDialogs = Collections.emptySet();

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        try {
            preferences = ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            showDeletedLabel = preferences.getBoolean("showDeletedLabel", true);
            dimDeletedMessages = preferences.getBoolean("dimDeletedMessages", true);
            keepOnClearHistory = preferences.getBoolean("keepOnClearHistory", false);
            keepOnDeleteDialog = preferences.getBoolean("keepOnDeleteDialog", false);
            keepOwnDeleted = preferences.getBoolean("keepOwnDeleted", false);
            restoreMediaToCache = preferences.getBoolean("restoreMediaToCache", true);
            excludedDialogs = parse(preferences.getString("excludedDialogs", ""));
        } catch (Throwable e) {
            FileLog.e(e);
        }
        loaded = true;
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    private static Set<Long> parse(String value) {
        if (TextUtils.isEmpty(value)) {
            return Collections.emptySet();
        }
        HashSet<Long> result = new HashSet<>();
        String[] parts = value.split(",");
        for (int a = 0; a < parts.length; a++) {
            String part = parts[a].trim();
            if (part.isEmpty()) {
                continue;
            }
            try {
                result.add(Long.parseLong(part));
            } catch (NumberFormatException ignore) {
            }
        }
        return result;
    }

    private static String join(Set<Long> value) {
        StringBuilder sb = new StringBuilder();
        for (Long id : value) {
            if (sb.length() != 0) {
                sb.append(',');
            }
            sb.append(id.longValue());
        }
        return sb.toString();
    }

    private static void putBoolean(String key, boolean value) {
        ensureLoaded();
        if (preferences != null) {
            preferences.edit().putBoolean(key, value).apply();
        }
    }

    public static void setShowDeletedLabel(boolean v) {
        showDeletedLabel = v;
        putBoolean("showDeletedLabel", v);
    }

    public static void setDimDeletedMessages(boolean v) {
        dimDeletedMessages = v;
        putBoolean("dimDeletedMessages", v);
    }

    public static void setKeepOnClearHistory(boolean v) {
        keepOnClearHistory = v;
        putBoolean("keepOnClearHistory", v);
    }

    public static void setKeepOnDeleteDialog(boolean v) {
        keepOnDeleteDialog = v;
        putBoolean("keepOnDeleteDialog", v);
    }

    /** ayu: "also keep my own messages when they are deleted elsewhere" */
    public static void setKeepOwnDeleted(boolean v) {
        keepOwnDeleted = v;
        putBoolean("keepOwnDeleted", v);
    }

    public static void setRestoreMediaToCache(boolean v) {
        restoreMediaToCache = v;
        putBoolean("restoreMediaToCache", v);
    }

    // ---------------- per dialog exclusions ----------------

    /** true when the user asked AyuGram not to keep anything from this dialog */
    public static boolean isExcluded(long dialogId) {
        if (dialogId == 0) {
            return false;
        }
        ensureLoaded();
        return excludedDialogs.contains(dialogId);
    }

    public static synchronized void setExcluded(long dialogId, boolean excluded) {
        if (dialogId == 0) {
            return;
        }
        ensureLoaded();
        HashSet<Long> copy = new HashSet<>(excludedDialogs);
        if (excluded) {
            if (!copy.add(dialogId)) {
                return;
            }
        } else if (!copy.remove(dialogId)) {
            return;
        }
        excludedDialogs = copy;
        if (preferences != null) {
            preferences.edit().putString("excludedDialogs", join(copy)).apply();
        }
    }

    /** flips the exclusion of a dialog and returns the new state */
    public static synchronized boolean toggleExcluded(long dialogId) {
        boolean value = !isExcluded(dialogId);
        setExcluded(dialogId, value);
        return value;
    }

    /** snapshot of the excluded dialogs, safe to iterate */
    public static long[] getExcludedDialogs() {
        ensureLoaded();
        Set<Long> snapshot = excludedDialogs;
        long[] result = new long[snapshot.size()];
        int index = 0;
        for (Long id : snapshot) {
            result[index++] = id;
        }
        return result;
    }

    public static int getExcludedCount() {
        ensureLoaded();
        return excludedDialogs.size();
    }

    public static synchronized void clearExcluded() {
        ensureLoaded();
        excludedDialogs = Collections.emptySet();
        if (preferences != null) {
            preferences.edit().putString("excludedDialogs", "").apply();
        }
    }
}
