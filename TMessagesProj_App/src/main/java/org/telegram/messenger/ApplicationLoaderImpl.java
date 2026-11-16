package org.telegram.messenger;

import org.telegram.messenger.regular.BuildConfig;

public class ApplicationLoaderImpl extends ApplicationLoader {
    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    @Override public boolean isCustomUpdate() { return true; }

    @Override public org.telegram.ui.IUpdateLayout takeUpdateLayout(android.app.Activity activity, android.view.ViewGroup container) {
        return new org.telegram.ui.ayu.update.OxUpdateLayout(activity, container);
    }

    @Override public void checkUpdate(boolean force, Runnable whenDone) {
        org.telegram.messenger.ayu.update.OxAppUpdater.getInstance().check(force, whenDone);
    }

    @Override public BetaUpdate getUpdate() {
        return org.telegram.messenger.ayu.update.OxAppUpdater.getInstance().snapshot().update;
    }

    @Override public void downloadUpdate() {
        org.telegram.messenger.ayu.update.OxAppUpdater.getInstance().download();
    }

    @Override public void cancelDownloadingUpdate() {
        org.telegram.messenger.ayu.update.OxAppUpdater.getInstance().cancelDownload();
    }

    @Override public boolean isDownloadingUpdate() {
        return org.telegram.messenger.ayu.update.OxAppUpdater.getInstance().snapshot().state
                == org.telegram.messenger.ayu.update.OxAppUpdater.State.DOWNLOADING;
    }

    @Override public float getDownloadingUpdateProgress() {
        return org.telegram.messenger.ayu.update.OxAppUpdater.getInstance().snapshot().progress();
    }

    @Override public boolean showCustomUpdateAppPopup(android.content.Context context, BetaUpdate update, int account) {
        return org.telegram.ui.ayu.update.OxUpdateHost.showOffer(context);
    }
}
