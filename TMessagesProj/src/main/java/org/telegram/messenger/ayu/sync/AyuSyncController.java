package org.telegram.messenger.ayu.sync;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;

/**
 * Compatibility API for the retired AyuSync server client.
 *
 * No observer, device identifier, background queue or network connection is created here.
 * The OcoderX-sync credentials screen uses OxSyncConfig independently of this class.
 */
public class AyuSyncController implements NotificationCenter.NotificationCenterDelegate {

    public enum Status {
        DISABLED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    public interface AyuSyncListener {
        void onAyuSyncStatusChanged(Status status, String lastError);
    }

    public interface RegisterCallback {
        void onResult(boolean success, String tokenOrError);
    }

    public static final String EVENT_READ = "read";
    public static final String EVENT_DELETED = "deleted";
    public static final String EVENT_EDITED = "edited";
    public static final String EVENT_GHOST = "ghost";

    private static final AyuSyncController instance = new AyuSyncController();

    private AyuSyncController() {
    }

    public static AyuSyncController getInstance() {
        return instance;
    }

    public void checkState() {
    }

    public void start() {
    }

    public void stop() {
    }

    public void forceSync() {
    }

    public Status getStatus() {
        return Status.DISABLED;
    }

    public String getLastError() {
        return null;
    }

    public void addListener(AyuSyncListener listener) {
    }

    public void removeListener(AyuSyncListener listener) {
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
    }

    /** Completes old callers without registering or uploading device data. */
    public void registerDevice(RegisterCallback callback) {
        if (callback != null) {
            AndroidUtilities.runOnUIThread(() -> callback.onResult(false, "Legacy AyuSync is unavailable"));
        }
    }

    public static String normalizedBaseUrl() {
        return null;
    }

    public String getDeviceId() {
        return "";
    }
}
