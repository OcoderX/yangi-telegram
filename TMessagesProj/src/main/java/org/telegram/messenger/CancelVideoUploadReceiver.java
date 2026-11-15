package org.telegram.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class CancelVideoUploadReceiver extends BroadcastReceiver {
    static final String ACTION = "org.telegram.messenger.CANCEL_VIDEO_UPLOAD";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && ACTION.equals(intent.getAction())) {
            VideoEncodingService.cancelFromNotification(intent);
        }
    }
}
