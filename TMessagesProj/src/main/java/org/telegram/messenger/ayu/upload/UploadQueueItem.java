package org.telegram.messenger.ayu.upload;

import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

/**
 * One user visible entry of the AyuGram upload queue. A queue entry always maps 1:1 to a
 * {@code FileUploadOperation}, i.e. to one file path + "encrypted" flag, because that is the
 * granularity {@code FileLoader} works with.
 */
public class UploadQueueItem {

    public static final int STATE_WAITING = 0;
    public static final int STATE_ACTIVE = 1;
    public static final int STATE_PAUSED = 2;
    public static final int STATE_ERROR = 3;
    public static final int STATE_DONE = 4;

    /** absolute path of the file being uploaded; also the key FileLoader uses */
    public String path;
    public boolean encrypted;
    /** "small" uploads are thumbnails / tiny files, they use their own FileLoader slot */
    public boolean small;
    /** ConnectionsManager.FileType* */
    public int type;
    public long estimatedSize;

    public String name;
    public long totalBytes;
    public long uploadedBytes;

    public int state = STATE_WAITING;
    /** true when the item was paused automatically because the connection dropped */
    public boolean pausedByNetwork;
    /** ordering key, lower goes first; move-to-top just lowers it below every other item */
    public long order;
    public long addedTime;

    // --- runtime only, never persisted ---
    public transient long speed;
    public transient long lastSpeedTime;
    public transient long lastSpeedBytes;
    /** true when the item exists in this process' FileLoader (i.e. it was really enqueued) */
    public transient boolean live;
    /** true while this paused item still occupies one of FileLoader's upload slots */
    public transient boolean holdsPausedSlot;

    public UploadQueueItem() {
    }

    public UploadQueueItem(String path, boolean encrypted, boolean small, int type, long estimatedSize) {
        this.path = path;
        this.encrypted = encrypted;
        this.small = small;
        this.type = type;
        this.estimatedSize = estimatedSize;
        this.addedTime = System.currentTimeMillis();
        this.order = this.addedTime;
        this.name = fileNameOf(path);
        this.totalBytes = estimatedSize;
        if (this.totalBytes <= 0) {
            try {
                File file = new File(path);
                if (file.exists()) {
                    this.totalBytes = file.length();
                }
            } catch (Throwable ignore) {
            }
        }
    }

    public String key() {
        return key(path, encrypted);
    }

    public static String key(String path, boolean encrypted) {
        return (encrypted ? "e:" : "p:") + path;
    }

    public static String fileNameOf(String path) {
        if (TextUtils.isEmpty(path)) {
            return "";
        }
        int index = path.lastIndexOf('/');
        if (index >= 0 && index + 1 < path.length()) {
            return path.substring(index + 1);
        }
        return path;
    }

    public boolean isFinished() {
        return state == STATE_DONE;
    }

    public boolean isPending() {
        return state == STATE_WAITING || state == STATE_ACTIVE || state == STATE_PAUSED || state == STATE_ERROR;
    }

    public float getProgress() {
        if (totalBytes <= 0) {
            return 0f;
        }
        float p = uploadedBytes / (float) totalBytes;
        if (p < 0f) {
            return 0f;
        }
        if (p > 1f) {
            return 1f;
        }
        return p;
    }

    public JSONObject toJson() {
        JSONObject object = new JSONObject();
        try {
            object.put("path", path);
            object.put("enc", encrypted);
            object.put("small", small);
            object.put("type", type);
            object.put("est", estimatedSize);
            object.put("name", name);
            object.put("total", totalBytes);
            object.put("uploaded", uploadedBytes);
            // an active item is persisted as "waiting": nothing is running after a restart
            object.put("state", state == STATE_ACTIVE ? STATE_WAITING : state);
            object.put("order", order);
            object.put("added", addedTime);
        } catch (JSONException ignore) {
        }
        return object;
    }

    public static UploadQueueItem fromJson(JSONObject object) {
        if (object == null) {
            return null;
        }
        final String path = object.optString("path", null);
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        UploadQueueItem item = new UploadQueueItem();
        item.path = path;
        item.encrypted = object.optBoolean("enc", false);
        item.small = object.optBoolean("small", false);
        item.type = object.optInt("type", 0);
        item.estimatedSize = object.optLong("est", 0);
        item.name = object.optString("name", fileNameOf(path));
        item.totalBytes = object.optLong("total", 0);
        item.uploadedBytes = object.optLong("uploaded", 0);
        item.state = object.optInt("state", STATE_WAITING);
        if (item.state == STATE_ACTIVE) {
            item.state = STATE_WAITING;
        }
        item.order = object.optLong("order", 0);
        item.addedTime = object.optLong("added", 0);
        return item;
    }
}
