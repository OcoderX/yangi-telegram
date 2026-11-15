package org.telegram.ui.ayu;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;

import java.util.HashMap;

/** Local presentation preferences, keyed by the signed-in user rather than a reusable slot. */
public final class OxChatCategoryConfig {
    private static final HashMap<Long, OxChatCategoryConfig> instances = new HashMap<>();
    private final SharedPreferences preferences;
    private boolean enabled;
    private int visibleMask;
    private boolean forwardEnabled;
    private int forwardVisibleMask;

    private OxChatCategoryConfig(long userId) {
        preferences = userId == 0 ? null : ApplicationLoader.applicationContext.getSharedPreferences(
                "ox_chat_categories_" + userId, Context.MODE_PRIVATE);
        enabled = preferences == null || preferences.getBoolean("enabled", true);
        visibleMask = preferences == null ? 15 : preferences.getInt("visible_mask", 15) & 15;
        // Start the new surface with the existing choices, then save each surface independently.
        forwardEnabled = preferences == null ? enabled : preferences.getBoolean("forward_enabled", enabled);
        forwardVisibleMask = preferences == null ? visibleMask : preferences.getInt("forward_visible_mask", visibleMask) & 15;
    }

    /** Call at lifecycle/settings boundaries; getters are memory-only. */
    public static synchronized OxChatCategoryConfig forAccount(int account) {
        long userId = UserConfig.getInstance(account).getClientUserId();
        if (userId == 0) return new OxChatCategoryConfig(0);
        OxChatCategoryConfig config = instances.get(userId);
        if (config == null) {
            config = new OxChatCategoryConfig(userId);
            instances.put(userId, config);
        }
        return config;
    }

    public boolean isEnabled() {
        return isEnabled(false);
    }

    public boolean isEnabled(boolean forward) {
        return forward ? forwardEnabled : enabled;
    }

    /** Individual choice is retained while the master switch is off. All is always available. */
    public boolean isVisible(int category) {
        return isVisible(category, false);
    }

    public boolean isVisible(int category, boolean forward) {
        return category == OxChatCategories.ALL || OxChatCategories.isCategory(category)
                && ((forward ? forwardVisibleMask : visibleMask) & (1 << (category - OxChatCategories.PERSONAL))) != 0;
    }

    public int stateKey() {
        return stateKey(false);
    }

    public int stateKey(boolean forward) {
        return (forward ? forwardVisibleMask : visibleMask) | (isEnabled(forward) ? 16 : 0);
    }

    public void setEnabled(boolean value) {
        setEnabled(false, value);
    }

    public void setEnabled(boolean forward, boolean value) {
        if (isEnabled(forward) == value) return;
        if (forward) forwardEnabled = value;
        else enabled = value;
        save();
    }

    public void setVisible(int category, boolean value) {
        setVisible(category, false, value);
    }

    public void setVisible(int category, boolean forward, boolean value) {
        if (!OxChatCategories.isCategory(category)) return;
        int bit = 1 << (category - OxChatCategories.PERSONAL);
        int previous = forward ? forwardVisibleMask : visibleMask;
        int next = value ? previous | bit : previous & ~bit;
        if (previous == next) return;
        if (forward) forwardVisibleMask = next;
        else visibleMask = next;
        save();
    }

    private void save() {
        if (preferences != null) {
            preferences.edit().putBoolean("enabled", enabled).putInt("visible_mask", visibleMask)
                    .putBoolean("forward_enabled", forwardEnabled).putInt("forward_visible_mask", forwardVisibleMask).apply();
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.ayuConfigChanged);
    }
}
