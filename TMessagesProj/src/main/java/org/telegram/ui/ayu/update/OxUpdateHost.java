package org.telegram.ui.ayu.update;

import static org.telegram.messenger.LocaleController.getString;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.messenger.ayu.update.OxAppUpdater;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.MainTabsActivity;

import java.lang.ref.WeakReference;

/** Activity-scoped installer handoff. The download service never retains this host. */
public final class OxUpdateHost {
    public static boolean showOffer(android.content.Context context) {
        if (context instanceof LaunchActivity) {
            ((LaunchActivity) context).offerOxUpdate();
            return true;
        }
        return false;
    }

    private final LaunchActivity activity;
    private boolean resumed;
    private boolean destroyed;
    private boolean verifying;
    private int permissionVersion;
    private long permissionTime;
    private int autoInstallVersion;
    private long autoInstallTime;
    private boolean updatePageVisible;
    private AlertDialog dialog;
    private final OxAppUpdater.Listener permissionListener = snapshot -> retryPermissionInstall();
    private final OxAppUpdater.Listener autoInstallListener = snapshot -> continueUserUpdate();

    public OxUpdateHost(LaunchActivity activity, Bundle state) {
        this.activity = activity;
        if (state != null) {
            permissionVersion = state.getInt("oxUpdatePermissionVersion");
            permissionTime = state.getLong("oxUpdatePermissionTime");
            autoInstallVersion = state.getInt("oxUpdateAutoVersion");
            autoInstallTime = state.getLong("oxUpdateAutoTime");
        }
    }

    public void save(Bundle state) {
        state.putInt("oxUpdatePermissionVersion", permissionVersion);
        state.putLong("oxUpdatePermissionTime", permissionTime);
        state.putInt("oxUpdateAutoVersion", autoInstallVersion);
        state.putLong("oxUpdateAutoTime", autoInstallTime);
    }

    public void onResume() {
        resumed = true;
        if (autoInstallVersion != 0) OxAppUpdater.getInstance().addListener(autoInstallListener);
        if (permissionVersion != 0) {
            final long age = System.currentTimeMillis() - permissionTime;
            if (age >= 0 && age < 10 * 60_000L && canInstall()) {
                OxAppUpdater.getInstance().addListener(permissionListener);
                retryPermissionInstall();
            } else {
                permissionVersion = 0;
            }
        }
    }

    private void retryPermissionInstall() {
        if (!active() || permissionVersion == 0) return;
        long age = System.currentTimeMillis() - permissionTime;
        if (age < 0 || age >= 10 * 60_000L) {
            permissionVersion = 0;
            OxAppUpdater.getInstance().removeListener(permissionListener);
            return;
        }
        OxAppUpdater.Snapshot snapshot = OxAppUpdater.getInstance().snapshot();
        if (snapshot.state == OxAppUpdater.State.CHECKING || snapshot.state == OxAppUpdater.State.VERIFYING) return;
        final boolean install = snapshot.state == OxAppUpdater.State.READY && snapshot.update != null
                && snapshot.update.versionCode == permissionVersion;
        permissionVersion = 0;
        OxAppUpdater.getInstance().removeListener(permissionListener);
        if (install) install();
    }

    public void onPause() {
        resumed = false;
        OxAppUpdater.getInstance().removeListener(permissionListener);
        OxAppUpdater.getInstance().removeListener(autoInstallListener);
    }

    public void destroy() {
        destroyed = true;
        resumed = false;
        OxAppUpdater.getInstance().removeListener(permissionListener);
        OxAppUpdater.getInstance().removeListener(autoInstallListener);
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }

    private boolean active() {
        return resumed && !destroyed && !activity.isFinishing()
                && (Build.VERSION.SDK_INT < 17 || !activity.isDestroyed());
    }

    public void check(boolean force) {
        if (force) {
            activity.presentFragment(new OxUpdatesActivity());
            return;
        }
        final WeakReference<OxUpdateHost> owner = new WeakReference<>(this);
        OxAppUpdater.getInstance().check(false, () -> {
            OxUpdateHost host = owner.get();
            if (host != null) host.activity.offerOxUpdate();
        });
    }

    public void offerUpdate() {
        if (!active() || LaunchActivity.getLastFragment() instanceof OxUpdatesActivity
                || LaunchActivity.getLastFragment() instanceof MainTabsActivity) return;
        final OxAppUpdater.Snapshot snapshot = OxAppUpdater.getInstance().snapshot();
        if (snapshot.update == null || (snapshot.state != OxAppUpdater.State.AVAILABLE
                && snapshot.state != OxAppUpdater.State.READY)) return;
        final android.content.SharedPreferences prefs = activity.getSharedPreferences("ox_updates_ui", 0);
        if (prefs.getInt("offered_version", 0) == snapshot.update.versionCode) return;
        if (dialog != null && dialog.isShowing()) return;
        dialog = new AlertDialog.Builder(activity)
                .setTitle(getString(R.string.OxUpdatesAvailable))
                .setMessage(org.telegram.messenger.LocaleController.formatString(R.string.OxUpdatesNewVersion, snapshot.update.version))
                .setPositiveButton(getString(R.string.OxUpdatesDownload), (d, which) -> {
                    dialog = null;
                    activity.presentFragment(new OxUpdatesActivity(snapshot.update.versionCode));
                })
                .setNegativeButton(getString(R.string.OxUpdatesLater), null)
                .create();
        dialog.show();
        prefs.edit().putInt("offered_version", snapshot.update.versionCode).apply();
    }

    private boolean canInstall() {
        return Build.VERSION.SDK_INT < 26 || activity.getPackageManager().canRequestPackageInstalls();
    }

    public void setUpdatePageVisible(boolean visible) {
        updatePageVisible = visible;
        if (!visible && resumed) {
            // Navigation away is cancellation; Home/rotation pauses the host first.
            clearAutoInstall();
        } else if (visible) {
            continueUserUpdate();
        }
    }

    /** Arm installation only from a current, explicit Update/Retry click. */
    public void startUserUpdate(int version) {
        if (!active() || !updatePageVisible) return;
        OxAppUpdater.Snapshot snapshot = OxAppUpdater.getInstance().snapshot();
        if (snapshot.update == null || snapshot.update.versionCode != version
                || snapshot.state == OxAppUpdater.State.CHECKING || snapshot.state == OxAppUpdater.State.VERIFYING) {
            clearAutoInstall();
            return;
        }
        clearAutoInstall();
        autoInstallVersion = version;
        autoInstallTime = System.currentTimeMillis();
        OxAppUpdater.getInstance().download();
        // Subscribe after download() clears an earlier failure for an explicit Retry.
        // A cached READY release instead consumes this explicit intent immediately.
        OxAppUpdater.getInstance().addListener(autoInstallListener);
    }

    public void cancelUserUpdate() {
        clearAutoInstall();
        OxAppUpdater.getInstance().cancelDownload();
    }

    private void clearAutoInstall() {
        autoInstallVersion = 0;
        autoInstallTime = 0;
        OxAppUpdater.getInstance().removeListener(autoInstallListener);
    }

    private void continueUserUpdate() {
        if (autoInstallVersion == 0) return;
        long age = System.currentTimeMillis() - autoInstallTime;
        OxAppUpdater.Snapshot snapshot = OxAppUpdater.getInstance().snapshot();
        if (age < 0 || age >= 30 * 60_000L || snapshot.state == OxAppUpdater.State.ERROR
                || snapshot.error == OxAppUpdater.Error.CANCELED
                || (snapshot.state != OxAppUpdater.State.READY && snapshot.error != OxAppUpdater.Error.NONE)
                || (snapshot.update != null && snapshot.update.versionCode != autoInstallVersion)
                || (snapshot.update == null && snapshot.state != OxAppUpdater.State.CHECKING)) {
            clearAutoInstall();
            return;
        }
        if (snapshot.state == OxAppUpdater.State.READY && active() && updatePageVisible) {
            clearAutoInstall();
            install();
        }
    }

    public void install() {
        if (!active() || verifying || (dialog != null && dialog.isShowing())) return;
        final OxAppUpdater.Snapshot snapshot = OxAppUpdater.getInstance().snapshot();
        if (snapshot.update == null || snapshot.state != OxAppUpdater.State.READY) return;
        if (!canInstall()) {
            dialog = new AlertDialog.Builder(activity)
                    .setTitle(getString(R.string.OxUpdatesInstallPermission))
                    .setMessage(getString(R.string.OxUpdatesInstallPermissionInfo))
                    .setPositiveButton(getString(R.string.OxUpdatesOpenSettings), (d, which) -> {
                        try {
                            permissionVersion = snapshot.update.versionCode;
                            permissionTime = System.currentTimeMillis();
                            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName())));
                        } catch (Exception e) {
                            permissionVersion = 0;
                            showError(R.string.OxUpdatesInstallerError);
                        }
                    })
                    .setNegativeButton(getString(R.string.Cancel), null).create();
            dialog.show();
            return;
        }
        verifying = true;
        final WeakReference<OxUpdateHost> owner = new WeakReference<>(this);
        OxAppUpdater.getInstance().verifyForInstall((file, error) -> {
            final OxUpdateHost host = owner.get();
            if (host == null) return;
            host.verifying = false;
            if (!host.active() || !host.updatePageVisible) return;
            if (file == null || error != OxAppUpdater.Error.NONE) {
                host.showError(error == OxAppUpdater.Error.NONE ? R.string.OxUpdatesInvalidApk : OxUpdatesActivity.errorString(error));
                return;
            }
            try {
                Uri uri = FileProvider.getUriForFile(host.activity, ApplicationLoader.getApplicationId() + ".provider", file);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, "application/vnd.android.package-archive");
                intent.setClipData(ClipData.newRawUri("APK", uri));
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                host.activity.startActivity(intent);
            } catch (Exception e) {
                host.showError(R.string.OxUpdatesInstallerError);
            }
        });
    }

    private void showError(int message) {
        if (!active()) return;
        dialog = new AlertDialog.Builder(activity).setTitle(getString(R.string.OxUpdatesTitle))
                .setMessage(getString(message)).setPositiveButton(getString(R.string.OK), null).create();
        dialog.show();
    }
}
