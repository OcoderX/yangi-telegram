package org.telegram.messenger.ayu.upload;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FileUploadOperation;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.ayu.AyuKeepAliveService;
import org.telegram.tgnet.ConnectionsManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;

/**
 * AyuGram Upload Accelerator: the background upload queue.
 * <p>
 * One instance per account. {@code FileLoader.uploadFile()} reports every enqueued upload here, and
 * everything else is learned from the regular {@code NotificationCenter} upload events, so nothing in
 * the send pipeline had to be duplicated:
 * <ul>
 *     <li>{@code fileUploadProgressChanged} → progress + speed</li>
 *     <li>{@code fileUploaded} → item done</li>
 *     <li>{@code fileUploadFailed} → item failed (the message itself is marked as "send error" by
 *     SendMessagesHelper exactly as before)</li>
 *     <li>{@code didUpdateConnectionState} → auto pause when the network is gone, auto resume when
 *     it is back</li>
 * </ul>
 * The queue is persisted as JSON in the {@code ayu_upload} preferences. It is <b>not</b> used to
 * re-send anything on startup: Telegram's own {@code checkUnsentMessages()} already re-sends unsent
 * messages and calls {@code FileLoader.uploadFile()} again, and the persisted entry is then simply
 * re-attached to that new upload (order and "paused" flag are restored). That way an upload is never
 * started twice.
 * <p>
 * Threading: every mutation of the item list happens on the main thread. {@code getBigUploadSlots()}
 * / {@code getSmallUploadSlots()} are read from FileLoader's own queue thread and therefore only
 * touch volatile counters.
 */
public class AyuUploadManager {

    public interface Listener {
        void onUploadQueueChanged();
    }

    /** items smaller than this are almost always generated thumbnails, they are not worth a row */
    private static final long MIN_TRACKED_SIZE = 32 * 1024;
    /** persisted entries are forgotten after a week */
    private static final long RESTORE_MAX_AGE = 7L * 24 * 60 * 60 * 1000;

    private static final AyuUploadManager[] instances = new AyuUploadManager[UserConfig.MAX_ACCOUNT_COUNT];

    public static AyuUploadManager getInstance(int account) {
        if (account < 0 || account >= instances.length) {
            account = 0;
        }
        AyuUploadManager local = instances[account];
        if (local == null) {
            synchronized (AyuUploadManager.class) {
                local = instances[account];
                if (local == null) {
                    local = new AyuUploadManager(account);
                    instances[account] = local;
                }
            }
        }
        return local;
    }

    private final int currentAccount;

    /** live items, main thread only */
    private final ArrayList<UploadQueueItem> items = new ArrayList<>();
    private final HashMap<String, UploadQueueItem> itemsByKey = new HashMap<>();
    /** persisted items that have not been re-enqueued by the send pipeline (yet) */
    private final HashMap<String, UploadQueueItem> restored = new HashMap<>();

    private final ArrayList<Listener> listeners = new ArrayList<>();

    private volatile int pausedActiveBig;
    private volatile int pausedActiveSmall;

    private boolean observersAdded;
    private boolean restoredLoaded;
    /** //perf: true while the persisted queue is being parsed on a background queue */
    private boolean restorePending;
    private boolean saveScheduled;

    private final Runnable saveRunnable = this::saveNow;

    private AyuUploadManager(int account) {
        currentAccount = account;
        AyuUploadConfig.ensureLoaded();
        AndroidUtilities.runOnUIThread(this::init);
    }

    // ------------------------------------------------------------------ init

    // NOTE: a method reference (not a lambda body) so that javac does not read the blank final
    // currentAccount while this field initializer runs, i.e. before the constructor assigns it.
    private final NotificationCenter.NotificationCenterDelegate delegate = this::onNotificationReceived;

    private void onNotificationReceived(int id, int account, Object... args) {
        if (account != currentAccount) {
            return;
        }
        try {
            if (id == NotificationCenter.fileUploadProgressChanged) {
                onProgress((String) args[0], (Long) args[1], (Long) args[2], (Boolean) args[3]);
            } else if (id == NotificationCenter.fileUploaded) {
                onUploaded((String) args[0]);
            } else if (id == NotificationCenter.fileUploadFailed) {
                onFailed((String) args[0], (Boolean) args[1]);
            } else if (id == NotificationCenter.didUpdateConnectionState) {
                onConnectionStateChanged();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void init() {
        if (observersAdded) {
            return;
        }
        observersAdded = true;
        loadRestored();
        try {
            NotificationCenter center = NotificationCenter.getInstance(currentAccount);
            center.addObserver(delegate, NotificationCenter.fileUploadProgressChanged);
            center.addObserver(delegate, NotificationCenter.fileUploaded);
            center.addObserver(delegate, NotificationCenter.fileUploadFailed);
            center.addObserver(delegate, NotificationCenter.didUpdateConnectionState);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** called once from ApplicationLoader so that the queue is restored before the first re-send */
    public static void initAll() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            getInstance(a);
        }
    }

    // ------------------------------------------------------- FileLoader hooks

    /**
     * Called from {@code FileLoader.uploadFile()} for every upload that is handed to the file loader,
     * no matter whether it starts right away or waits in the queue.
     */
    public static void onUploadEnqueued(int account, String path, boolean encrypted, boolean small, int type, long estimatedSize) {
        if (TextUtils.isEmpty(path)) {
            return;
        }
        getInstance(account).enqueue(path, encrypted, small, type, estimatedSize);
    }

    private void enqueue(String path, boolean encrypted, boolean small, int type, long estimatedSize) {
        AndroidUtilities.runOnUIThread(() -> {
            final String key = UploadQueueItem.key(path, encrypted);
            UploadQueueItem item = itemsByKey.get(key);
            if (item != null) {
                item.live = true;
                if (item.state == UploadQueueItem.STATE_ERROR || item.state == UploadQueueItem.STATE_DONE) {
                    item.state = UploadQueueItem.STATE_WAITING;
                }
                notifyChanged();
                return;
            }
            item = new UploadQueueItem(path, encrypted, small, type, estimatedSize);
            if (item.totalBytes > 0 && item.totalBytes < MIN_TRACKED_SIZE && estimatedSize == 0) {
                // a thumbnail or a tiny sticker: not worth its own queue row
                return;
            }
            final UploadQueueItem previous = restored.remove(key);
            if (previous != null) {
                item.order = previous.order;
                item.addedTime = previous.addedTime != 0 ? previous.addedTime : item.addedTime;
                if (previous.state == UploadQueueItem.STATE_PAUSED) {
                    item.state = UploadQueueItem.STATE_PAUSED;
                }
            }
            item.live = true;
            items.add(item);
            itemsByKey.put(key, item);
            sortItems();
            if (item.state == UploadQueueItem.STATE_PAUSED) {
                applyPause(item);
            }
            notifyChanged();
        });
    }

    /** how many "big" upload operations FileLoader may run at the same time */
    public int getBigUploadSlots() {
        return AyuUploadConfig.getConcurrency() + pausedActiveBig;
    }

    /** how many "small" (thumbnail / photo) upload operations FileLoader may run at the same time */
    public int getSmallUploadSlots() {
        return 1 + pausedActiveSmall;
    }

    // ------------------------------------------------------- upload callbacks

    private void onProgress(String path, long uploaded, long total, boolean encrypted) {
        final UploadQueueItem item = itemsByKey.get(UploadQueueItem.key(path, encrypted));
        if (item == null) {
            return;
        }
        if (uploaded < 0 || total < 0) {
            // SendMessagesHelper posts (-1, -1) when a message is cancelled
            removeItem(item);
            notifyChanged();
            return;
        }
        final long now = System.currentTimeMillis();
        if (item.lastSpeedTime != 0 && now > item.lastSpeedTime + 700) {
            final long delta = uploaded - item.lastSpeedBytes;
            if (delta > 0) {
                final long speed = delta * 1000L / (now - item.lastSpeedTime);
                item.speed = item.speed == 0 ? speed : (item.speed * 2 + speed) / 3;
            }
            item.lastSpeedTime = now;
            item.lastSpeedBytes = uploaded;
        } else if (item.lastSpeedTime == 0) {
            item.lastSpeedTime = now;
            item.lastSpeedBytes = uploaded;
        }
        item.uploadedBytes = uploaded;
        if (total > 0) {
            item.totalBytes = total;
        }
        if (item.state != UploadQueueItem.STATE_PAUSED) {
            item.state = UploadQueueItem.STATE_ACTIVE;
            item.pausedByNetwork = false;
        }
        scheduleSave();
        notifyChanged();
    }

    private void onUploaded(String path) {
        UploadQueueItem item = itemsByKey.get(UploadQueueItem.key(path, false));
        if (item == null) {
            item = itemsByKey.get(UploadQueueItem.key(path, true));
        }
        if (item == null) {
            return;
        }
        item.state = UploadQueueItem.STATE_DONE;
        item.uploadedBytes = item.totalBytes;
        removeItem(item);
        notifyChanged();
    }

    private void onFailed(String path, boolean encrypted) {
        final UploadQueueItem item = itemsByKey.get(UploadQueueItem.key(path, encrypted));
        if (item == null) {
            return;
        }
        if (item.state == UploadQueueItem.STATE_PAUSED) {
            // a paused operation reports a failure when it is cancelled; keep the row
            return;
        }
        item.state = UploadQueueItem.STATE_ERROR;
        item.live = false;
        item.speed = 0;
        releasePausedSlot(item);
        scheduleSave();
        notifyChanged();
    }

    private void onConnectionStateChanged() {
        if (!AyuUploadConfig.isResumeEnabled()) {
            return;
        }
        final int state = ConnectionsManager.getInstance(currentAccount).getConnectionState();
        if (state != ConnectionsManager.ConnectionStateWaitingForNetwork
                && state != ConnectionsManager.ConnectionStateConnected
                && state != ConnectionsManager.ConnectionStateUpdating) {
            return;
        }
        final ArrayList<UploadQueueItem> copy = new ArrayList<>(items);
        if (state == ConnectionsManager.ConnectionStateWaitingForNetwork) {
            for (int a = 0; a < copy.size(); a++) {
                final UploadQueueItem item = copy.get(a);
                if (item.state == UploadQueueItem.STATE_ACTIVE) {
                    item.pausedByNetwork = true;
                    setPaused(item, true, true);
                }
            }
        } else {
            for (int a = 0; a < copy.size(); a++) {
                final UploadQueueItem item = copy.get(a);
                if (item.pausedByNetwork && item.state == UploadQueueItem.STATE_PAUSED) {
                    item.pausedByNetwork = false;
                    setPaused(item, false, true);
                }
            }
        }
        notifyChanged();
    }

    // ------------------------------------------------------------- public API

    /** live queue, main thread only; the returned list must not be modified */
    public ArrayList<UploadQueueItem> getItems() {
        return items;
    }

    public int getActiveCount() {
        int count = 0;
        for (int a = 0; a < items.size(); a++) {
            if (items.get(a).state == UploadQueueItem.STATE_ACTIVE) {
                count++;
            }
        }
        return count;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public void setPaused(UploadQueueItem item, boolean paused, boolean automatic) {
        if (item == null) {
            return;
        }
        if (paused) {
            if (item.state == UploadQueueItem.STATE_PAUSED) {
                return;
            }
            item.state = UploadQueueItem.STATE_PAUSED;
            item.speed = 0;
            item.lastSpeedTime = 0;
            if (!automatic) {
                item.pausedByNetwork = false;
            }
            applyPause(item);
        } else {
            if (item.state != UploadQueueItem.STATE_PAUSED && item.state != UploadQueueItem.STATE_ERROR) {
                return;
            }
            final boolean wasPaused = item.state == UploadQueueItem.STATE_PAUSED;
            item.state = UploadQueueItem.STATE_WAITING;
            item.lastSpeedTime = 0;
            if (wasPaused) {
                applyResume(item);
            } else {
                // the operation is gone (hard failure): ask the file loader to run it again
                retryFailed(item);
            }
        }
        scheduleSave();
        notifyChanged();
    }

    private void applyPause(UploadQueueItem item) {
        final FileUploadOperation operation = FileLoader.getInstance(currentAccount).getUploadOperation(item.path, item.encrypted);
        if (operation == null) {
            return;
        }
        final boolean wasRunning = operation.isStarted();
        operation.pauseUpload();
        if (wasRunning && !item.holdsPausedSlot) {
            // the operation keeps its FileLoader slot while it is paused: widen the limit by one so
            // that the rest of the queue keeps moving
            item.holdsPausedSlot = true;
            if (item.small) {
                pausedActiveSmall++;
            } else {
                pausedActiveBig++;
            }
        }
        FileLoader.getInstance(currentAccount).checkUploadQueue();
    }

    private void applyResume(UploadQueueItem item) {
        final FileUploadOperation operation = FileLoader.getInstance(currentAccount).getUploadOperation(item.path, item.encrypted);
        if (operation == null) {
            releasePausedSlot(item);
            retryFailed(item);
            return;
        }
        operation.resumeUpload();
        releasePausedSlot(item);
        FileLoader.getInstance(currentAccount).checkUploadQueue();
    }

    private void releasePausedSlot(UploadQueueItem item) {
        if (!item.holdsPausedSlot) {
            return;
        }
        item.holdsPausedSlot = false;
        if (item.small) {
            if (pausedActiveSmall > 0) {
                pausedActiveSmall--;
            }
        } else {
            if (pausedActiveBig > 0) {
                pausedActiveBig--;
            }
        }
    }

    private void retryFailed(UploadQueueItem item) {
        final File file = new File(item.path);
        if (!file.exists()) {
            removeItem(item);
            return;
        }
        item.live = true;
        FileLoader.getInstance(currentAccount).uploadFile(item.path, item.encrypted, item.small, item.estimatedSize, item.type, false);
    }

    public void cancel(UploadQueueItem item) {
        if (item == null) {
            return;
        }
        releasePausedSlot(item);
        final FileUploadOperation operation = FileLoader.getInstance(currentAccount).getUploadOperation(item.path, item.encrypted);
        if (operation != null) {
            // un-pause first, otherwise cancel() would leave the operation half stopped
            operation.resumeUpload();
        }
        FileLoader.getInstance(currentAccount).cancelFileUpload(item.path, item.encrypted);
        removeItem(item);
        FileLoader.getInstance(currentAccount).checkUploadQueue();
        notifyChanged();
    }

    public void moveToTop(UploadQueueItem item) {
        if (item == null || items.isEmpty()) {
            return;
        }
        long min = item.order;
        for (int a = 0; a < items.size(); a++) {
            min = Math.min(min, items.get(a).order);
        }
        item.order = min - 1;
        sortItems();
        FileLoader.getInstance(currentAccount).moveUploadOperationToTop(item.path, item.encrypted);
        scheduleSave();
        notifyChanged();
    }

    private void removeItem(UploadQueueItem item) {
        releasePausedSlot(item);
        items.remove(item);
        itemsByKey.remove(item.key());
        restored.remove(item.key());
        scheduleSave();
    }

    private void sortItems() {
        Collections.sort(items, (a, b) -> {
            if (a.order != b.order) {
                return a.order < b.order ? -1 : 1;
            }
            return 0;
        });
    }

    /** the whole queue is dropped (used by "clear finished / cancel all") */
    public void cancelAll() {
        final ArrayList<UploadQueueItem> copy = new ArrayList<>(items);
        for (int a = 0; a < copy.size(); a++) {
            cancel(copy.get(a));
        }
    }

    // ------------------------------------------------------------- listeners

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private boolean notifyScheduled;

    private final Runnable notifyRunnable = () -> {
        notifyScheduled = false;
        for (int a = 0; a < listeners.size(); a++) {
            try {
                listeners.get(a).onUploadQueueChanged();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    };

    /** coalesced: progress events arrive several times a second, a redraw per part is pointless */
    private void notifyChanged() {
        updateKeepAlive();
        if (listeners.isEmpty() || notifyScheduled) {
            return;
        }
        notifyScheduled = true;
        AndroidUtilities.runOnUIThread(notifyRunnable, 200);
    }

    // ----------------------------------------------------------- keep alive

    private void updateKeepAlive() {
        try {
            AyuKeepAliveService.onUploadStatusChanged(ApplicationLoader.applicationContext);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** true when at least one account still has something to upload */
    public static boolean hasActiveUploads() {
        if (!AyuUploadConfig.keepAliveWhileUploading) {
            return false;
        }
        for (int a = 0; a < instances.length; a++) {
            final AyuUploadManager manager = instances[a];
            if (manager == null) {
                continue;
            }
            for (int b = 0; b < manager.items.size(); b++) {
                final int state = manager.items.get(b).state;
                if (state == UploadQueueItem.STATE_ACTIVE || state == UploadQueueItem.STATE_WAITING) {
                    return true;
                }
            }
        }
        return false;
    }

    /** aggregated progress over every account, 0..100, or -1 when nothing is known */
    public static int getOverallProgress() {
        long uploaded = 0, total = 0;
        for (int a = 0; a < instances.length; a++) {
            final AyuUploadManager manager = instances[a];
            if (manager == null) {
                continue;
            }
            for (int b = 0; b < manager.items.size(); b++) {
                final UploadQueueItem item = manager.items.get(b);
                if (item.state == UploadQueueItem.STATE_ACTIVE || item.state == UploadQueueItem.STATE_WAITING) {
                    uploaded += item.uploadedBytes;
                    total += Math.max(item.totalBytes, item.uploadedBytes);
                }
            }
        }
        if (total <= 0) {
            return -1;
        }
        return (int) Math.min(100, uploaded * 100 / total);
    }

    /** number of files still queued or uploading over every account */
    public static int getPendingCount() {
        int count = 0;
        for (int a = 0; a < instances.length; a++) {
            final AyuUploadManager manager = instances[a];
            if (manager == null) {
                continue;
            }
            for (int b = 0; b < manager.items.size(); b++) {
                final int state = manager.items.get(b).state;
                if (state == UploadQueueItem.STATE_ACTIVE || state == UploadQueueItem.STATE_WAITING) {
                    count++;
                }
            }
        }
        return count;
    }

    /** re-checks every account's file loader after the concurrency setting changed */
    public static void onConcurrencyChanged() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            try {
                FileLoader.getInstance(a).checkUploadQueue();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    // ---------------------------------------------------------- persistence

    private String prefsKey() {
        return "queue_" + currentAccount;
    }

    /**
     * //perf: {@code initAll()} reaches this for every account while the app is still starting up.
     * Parsing the persisted JSON and {@code stat()}ing every path is IO, so it runs on the global
     * queue; only the (tiny) result is applied back on the main thread, which keeps the ownership
     * rule "every mutation of the item list happens on the main thread" intact.
     */
    private void loadRestored() {
        if (restoredLoaded) {
            return;
        }
        restoredLoaded = true;
        restorePending = true;
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<UploadQueueItem> parsed;
            try {
                parsed = parseRestored();
            } catch (Throwable e) {
                FileLog.e(e);
                parsed = new ArrayList<>();
            }
            final ArrayList<UploadQueueItem> result = parsed;
            AndroidUtilities.runOnUIThread(() -> applyRestored(result));
        });
    }

    /** background queue: JSON parse + the "is the file still there" check */
    private ArrayList<UploadQueueItem> parseRestored() {
        final ArrayList<UploadQueueItem> result = new ArrayList<>();
        final String json = AyuUploadConfig.getString(prefsKey(), null);
        if (TextUtils.isEmpty(json)) {
            return result;
        }
        try {
            final JSONArray array = new JSONArray(json);
            final long now = System.currentTimeMillis();
            for (int a = 0; a < array.length(); a++) {
                final JSONObject object = array.optJSONObject(a);
                final UploadQueueItem item = UploadQueueItem.fromJson(object);
                if (item == null) {
                    continue;
                }
                if (item.addedTime != 0 && now - item.addedTime > RESTORE_MAX_AGE) {
                    continue;
                }
                if (TextUtils.isEmpty(item.path) || !new File(item.path).exists()) {
                    // the file is gone: the entry is dropped, exactly as before
                    continue;
                }
                result.add(item);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return result;
    }

    /** main thread: publish what {@link #parseRestored()} found */
    private void applyRestored(ArrayList<UploadQueueItem> parsed) {
        restorePending = false;
        if (parsed == null || parsed.isEmpty()) {
            return;
        }
        boolean reattached = false;
        for (int a = 0; a < parsed.size(); a++) {
            final UploadQueueItem item = parsed.get(a);
            final String key = item.key();
            if (restored.containsKey(key)) {
                continue;
            }
            final UploadQueueItem live = itemsByKey.get(key);
            if (live == null) {
                restored.put(key, item);
                continue;
            }
            // the send pipeline re-enqueued this file while the parse was still running: re-attach
            // the persisted order / paused flag here, exactly like enqueue() would have done
            if (!live.isPending()) {
                continue;
            }
            live.order = item.order;
            if (item.addedTime != 0) {
                live.addedTime = item.addedTime;
            }
            if (item.state == UploadQueueItem.STATE_PAUSED && live.state != UploadQueueItem.STATE_PAUSED) {
                live.state = UploadQueueItem.STATE_PAUSED;
                applyPause(live);
            }
            reattached = true;
        }
        if (reattached) {
            sortItems();
            notifyChanged();
        }
    }

    private void scheduleSave() {
        if (saveScheduled) {
            return;
        }
        saveScheduled = true;
        AndroidUtilities.runOnUIThread(saveRunnable, 1000);
    }

    private void saveNow() {
        saveScheduled = false;
        if (restorePending) {
            //perf: the persisted queue has not been parsed back yet, saving now would drop it
            scheduleSave();
            return;
        }
        try {
            final JSONArray array = new JSONArray();
            for (int a = 0; a < items.size(); a++) {
                final UploadQueueItem item = items.get(a);
                if (!item.isPending()) {
                    continue;
                }
                array.put(item.toJson());
            }
            final Iterator<UploadQueueItem> iterator = restored.values().iterator();
            while (iterator.hasNext()) {
                array.put(iterator.next().toJson());
            }
            AyuUploadConfig.putString(prefsKey(), array.toString());
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
