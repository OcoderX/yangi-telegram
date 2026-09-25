package org.telegram.messenger.ayu;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;

import java.util.Map;

/**
 * ayu: Android 12+ only hands a http(s) link to an app when the link's domain is either verified
 * through Digital Asset Links or approved by the user under "Open by default". Ox-gram declares the
 * t.me / telegram.me / telegram.dog intent filters (AndroidManifest.xml, LaunchActivity), but the
 * APK is signed with our own key, so t.me/.well-known/assetlinks.json - which only lists the
 * official certificate - can never verify us. Without the user approving the domains by hand every
 * https://t.me/channel/123 tapped outside the app opens in the browser instead of Ox-gram.
 * <p>
 * This helper detects that state and offers to jump straight to the system screen where the
 * "supported web addresses" can be switched on. Nothing here changes how links are handled inside
 * the app: those never leave {@link org.telegram.messenger.browser.Browser#openAsInternalIntent}.
 */
public class AyuLinkHandling {

    /** the web hosts LaunchActivity declares intent filters for */
    private static final String[] HOSTS = {"t.me", "telegram.me", "telegram.dog"};

    private static final String PREF_LAST_ASKED = "ox_applinks_prompt_last_asked";
    private static final String PREF_NEVER = "ox_applinks_prompt_never";
    private static final long ASK_INTERVAL = 14L * 24 * 60 * 60 * 1000; // at most once every two weeks
    private static final long START_DELAY = 2500; // let the main screen settle first

    private static volatile boolean scheduled;
    /**
     * //perf: {@link #handlesTelegramLinks} is a binder round trip to the system server. It used to
     * run synchronously in LaunchActivity.onResume() on every resume; now it runs on the global
     * queue and at most once per {@link #RECHECK_INTERVAL} per process.
     */
    private static long lastCheckUptime;
    private static final long RECHECK_INTERVAL = 30L * 60 * 1000;

    private AyuLinkHandling() {
    }

    /**
     * False only when we positively know Android will not route t.me links here. Anything we cannot
     * determine (older Android, no state for our hosts, a throwing service) counts as "fine", so the
     * user is never nagged on a guess.
     */
    public static boolean handlesTelegramLinks(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // before Android 12 the app is simply offered in the chooser for t.me links
            return true;
        }
        return handlesTelegramLinksS(context);
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.S)
    private static boolean handlesTelegramLinksS(Context context) {
        try {
            final android.content.pm.verify.domain.DomainVerificationManager manager =
                    context.getSystemService(android.content.pm.verify.domain.DomainVerificationManager.class);
            if (manager == null) {
                return true;
            }
            final android.content.pm.verify.domain.DomainVerificationUserState state =
                    manager.getDomainVerificationUserState(context.getPackageName());
            if (state == null) {
                return true;
            }
            if (!state.isLinkHandlingAllowed()) {
                return false;
            }
            final Map<String, Integer> hostToState = state.getHostToStateMap();
            if (hostToState == null || hostToState.isEmpty()) {
                return true;
            }
            boolean known = false;
            for (int i = 0; i < HOSTS.length; i++) {
                final Integer hostState = hostToState.get(HOSTS[i]);
                if (hostState == null) {
                    continue;
                }
                known = true;
                if (hostState == android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_VERIFIED
                        || hostState == android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_SELECTED) {
                    return true;
                }
            }
            // our hosts are not in the map at all: nothing can be said, do not ask
            return !known;
        } catch (Throwable e) {
            FileLog.e(e);
            return true;
        }
    }

    /** Opens Settings -> Open by default (Android 12+), or the app info screen below that. */
    public static boolean openLinkSettings(Context context) {
        if (context == null) {
            return false;
        }
        final Uri uri = Uri.parse("package:" + context.getPackageName());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                final Intent intent = new Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, uri);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return true;
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        try {
            final Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    /** Called from LaunchActivity.onResume(). Cheap when nothing has to be done. */
    public static void checkAndShow(LaunchActivity activity) {
        if (activity == null || scheduled) {
            return;
        }
        final UserConfig userConfig = UserConfig.getInstance(UserConfig.selectedAccount);
        if (!userConfig.isClientActivated()) {
            return;
        }
        final SharedPreferences prefs = MessagesController.getGlobalMainSettings();
        if (prefs.getBoolean(PREF_NEVER, false)) {
            return;
        }
        if (System.currentTimeMillis() - prefs.getLong(PREF_LAST_ASKED, 0) < ASK_INTERVAL) {
            return;
        }
        final long now = android.os.SystemClock.elapsedRealtime();
        if (lastCheckUptime != 0 && now - lastCheckUptime < RECHECK_INTERVAL) {
            return;
        }
        lastCheckUptime = now;
        scheduled = true;
        final Context appContext = activity.getApplicationContext();
        // the system-server query runs off the main thread; only the dialog is posted back
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            final boolean handled = handlesTelegramLinks(appContext);
            if (handled) {
                scheduled = false;
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                scheduled = false;
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                if (SharedConfig.passcodeHash.length() > 0 && SharedConfig.appLocked) {
                    return;
                }
                final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
                final Activity parent = fragment != null ? fragment.getParentActivity() : null;
                if (fragment == null || parent == null || parent.isFinishing()) {
                    return;
                }
                prefs.edit().putLong(PREF_LAST_ASKED, System.currentTimeMillis()).apply();

                final AlertDialog.Builder builder = new AlertDialog.Builder(parent);
                builder.setTitle(LocaleController.getString(R.string.OxOpenLinksTitle));
                builder.setMessage(LocaleController.getString(R.string.OxOpenLinksText));
                builder.setPositiveButton(LocaleController.getString(R.string.OxOpenLinksOpen), (dialog, which) -> openLinkSettings(parent));
                builder.setNegativeButton(LocaleController.getString(R.string.OxOpenLinksLater), null);
                builder.setNeutralButton(LocaleController.getString(R.string.OxOpenLinksNever), (dialog, which) ->
                        prefs.edit().putBoolean(PREF_NEVER, true).apply());
                fragment.showDialog(builder.create());
            }, START_DELAY);
        });
    }
}
