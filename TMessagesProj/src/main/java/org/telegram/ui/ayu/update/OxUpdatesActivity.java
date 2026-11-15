package org.telegram.ui.ayu.update;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.ayu.update.OxAppUpdater;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LaunchActivity;

/** Account-independent update status, with explicit download and installer actions. */
public final class OxUpdatesActivity extends BaseFragment {
    private TextView status, details, changelog;
    private ProgressBar progress;
    private TextSettingsCell action, cancel, check;
    private boolean listening;
    private boolean checkedOnOpen;
    private int requestedUpdateVersion;
    private long requestedUpdateTime;
    private final OxAppUpdater.Listener listener = this::render;

    public OxUpdatesActivity() { }

    public OxUpdatesActivity(int version) {
        requestedUpdateVersion = version;
        requestedUpdateTime = System.currentTimeMillis();
    }

    @Override public void saveSelfArgs(Bundle state) {
        super.saveSelfArgs(state);
        state.putInt("oxUpdateStartVersion", requestedUpdateVersion);
        state.putLong("oxUpdateStartTime", requestedUpdateTime);
    }

    @Override public void restoreSelfArgs(Bundle state) {
        super.restoreSelfArgs(state);
        requestedUpdateVersion = state.getInt("oxUpdateStartVersion");
        requestedUpdateTime = state.getLong("oxUpdateStartTime");
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(getString(R.string.OxUpdatesTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(16), 0, dp(24));
        scroll.addView(content);
        status = text(context, 21);
        status.setTypeface(AndroidUtilities.bold());
        content.addView(status, LayoutHelper.createLinear(-1, -2, 20, 8, 20, 8));
        TextView installed = text(context, 14);
        installed.setText(LocaleController.formatString(R.string.OxUpdatesInstalled, BuildVars.BUILD_VERSION_STRING));
        content.addView(installed, LayoutHelper.createLinear(-1, -2, 20, 0, 20, 12));
        details = text(context, 15);
        content.addView(details, LayoutHelper.createLinear(-1, -2, 20, 0, 20, 12));
        progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        content.addView(progress, LayoutHelper.createLinear(-1, 8, 20, 0, 20, 16));
        action = cell(context);
        action.setOnClickListener(v -> {
            OxAppUpdater.Snapshot s = OxAppUpdater.getInstance().snapshot();
            if (s.state == OxAppUpdater.State.READY && getParentActivity() instanceof LaunchActivity) {
                ((LaunchActivity) getParentActivity()).installOxUpdate();
            } else if (s.update != null && getParentActivity() instanceof LaunchActivity) {
                ((LaunchActivity) getParentActivity()).startOxUpdate(s.update.versionCode);
            }
        });
        content.addView(action);
        cancel = cell(context);
        cancel.setOnClickListener(v -> {
            if (getParentActivity() instanceof LaunchActivity) ((LaunchActivity) getParentActivity()).cancelOxUpdate();
        });
        content.addView(cancel);
        check = cell(context);
        check.setText(getString(R.string.OxUpdatesCheck), false);
        check.setOnClickListener(v -> OxAppUpdater.getInstance().check(true, null));
        content.addView(check);
        changelog = text(context, 15);
        changelog.setTextIsSelectable(true);
        content.addView(changelog, LayoutHelper.createLinear(-1, -2, 20, 20, 20, 8));
        fragmentView = scroll;
        render(OxAppUpdater.getInstance().snapshot());
        return fragmentView;
    }

    private TextView text(Context context, int size) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        return view;
    }

    private TextSettingsCell cell(Context context) {
        TextSettingsCell cell = new TextSettingsCell(context);
        cell.setBackground(Theme.createSelectorWithBackgroundDrawable(Theme.getColor(Theme.key_windowBackgroundWhite), Theme.getColor(Theme.key_listSelector)));
        cell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        return cell;
    }

    @Override public void onResume() {
        super.onResume();
        if (getParentActivity() instanceof LaunchActivity) {
            LaunchActivity activity = (LaunchActivity) getParentActivity();
            activity.setOxUpdatePageVisible(true);
            if (requestedUpdateVersion != 0) {
                int version = requestedUpdateVersion;
                requestedUpdateVersion = 0;
                long age = System.currentTimeMillis() - requestedUpdateTime;
                if (age >= 0 && age < 30 * 60_000L) activity.startOxUpdate(version);
            }
        }
        if (!listening) {
            listening = true;
            OxAppUpdater.getInstance().addListener(listener);
        }
        render(OxAppUpdater.getInstance().snapshot());
        if (!checkedOnOpen) {
            checkedOnOpen = true;
            OxAppUpdater.getInstance().check(true, null);
        }
    }

    @Override public void onPause() {
        if (getParentActivity() instanceof LaunchActivity) ((LaunchActivity) getParentActivity()).setOxUpdatePageVisible(false);
        stopListening();
        super.onPause();
    }

    @Override public void onFragmentDestroy() {
        stopListening();
        super.onFragmentDestroy();
    }

    private void stopListening() {
        if (listening) OxAppUpdater.getInstance().removeListener(listener);
        listening = false;
    }

    private void render(OxAppUpdater.Snapshot s) {
        if (status == null) return;
        final boolean downloading = s.state == OxAppUpdater.State.DOWNLOADING;
        final boolean busy = downloading || s.state == OxAppUpdater.State.CHECKING || s.state == OxAppUpdater.State.VERIFYING;
        int label = R.string.OxUpdatesCheck;
        switch (s.state) {
            case CHECKING: label = R.string.OxUpdatesChecking; break;
            case DOWNLOADING: label = R.string.OxUpdatesDownloading; break;
            case VERIFYING: label = R.string.OxUpdatesVerifying; break;
            case READY: label = R.string.OxUpdatesReady; break;
            case AVAILABLE: label = R.string.OxUpdatesAvailable; break;
            case ERROR: label = errorString(s.error); break;
            case IDLE:
                if (s.error == OxAppUpdater.Error.NO_UPDATE) label = R.string.OxUpdatesLatest;
                else if (s.error == OxAppUpdater.Error.CANCELED) label = R.string.OxUpdatesCanceled;
                break;
        }
        status.setText(getString(label));
        details.setText(s.update == null ? getString(R.string.OxUpdatesDescription)
                : LocaleController.formatString(R.string.OxUpdatesNewVersion, s.update.version)
                + (s.totalBytes > 0 ? "\n" + AndroidUtilities.formatFileSize(s.totalBytes) : "")
                + (downloading ? "\n" + LocaleController.formatString(R.string.OxUpdatesProgress,
                Math.round(s.progress() * 100), AndroidUtilities.formatFileSize(s.downloadedBytes)) : "")
                + (s.state == OxAppUpdater.State.READY && s.error != OxAppUpdater.Error.NONE
                ? "\n\n" + getString(errorString(s.error)) : ""));
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        progress.setIndeterminate(!downloading);
        if (downloading) progress.setProgress(Math.round(s.progress() * 1000));
        action.setVisibility(!busy && s.update != null ? View.VISIBLE : View.GONE);
        action.setText(getString(s.state == OxAppUpdater.State.READY ? R.string.OxUpdatesInstall
                : s.state == OxAppUpdater.State.ERROR ? R.string.OxUpdatesRetry : R.string.OxUpdatesDownload), false);
        cancel.setVisibility(downloading ? View.VISIBLE : View.GONE);
        cancel.setText(getString(R.string.OxUpdatesCancelDownload), false);
        check.setEnabled(!busy);
        check.setAlpha(busy ? 0.5f : 1f);
        changelog.setText(s.update == null || s.update.changelog == null || s.update.changelog.trim().isEmpty()
                ? "" : getString(R.string.OxUpdatesChanges) + "\n\n" + s.update.changelog);
    }

    static int errorString(OxAppUpdater.Error error) {
        switch (error) {
            case NETWORK: return R.string.OxUpdatesNetworkError;
            case INVALID_MANIFEST: return R.string.OxUpdatesManifestError;
            case INVALID_APK: return R.string.OxUpdatesInvalidApk;
            case STORAGE: return R.string.OxUpdatesStorageError;
            case CANCELED: return R.string.OxUpdatesCanceled;
            case NO_UPDATE: return R.string.OxUpdatesLatest;
            default: return R.string.OxUpdatesNetworkError;
        }
    }
}
