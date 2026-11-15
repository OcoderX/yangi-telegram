package org.telegram.messenger.ayu.upload;

import android.text.TextUtils;
import android.os.SystemClock;

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
 * native upload callbacks retain the operation and account owner identity:
 * <ul>
 *     <li>upload progress → progress + speed</li>
 *     <li>upload completion → item done</li>
 *     <li>upload failure → item failed (the message itself is marked as "send error" by
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
    private long ownerId = Long.MIN_VALUE;

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
            ensureOwner();
            if (id == NotificationCenter.didUpdateConnectionState) {
                onConnectionStateChanged();
            } else if (id == NotificationCenter.appDidLogout) {
                notifyChanged();
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
        ensureOwner();
        if (ownerId != 0) {
            loadRestored();
        }
        try {
            NotificationCenter center = NotificationCenter.getInstance(currentAccount);
            center.addObserver(delegate, NotificationCenter.didUpdateConnectionState);
            center.addObserver(delegate, NotificationCenter.appDidLogout);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Main-thread owner boundary; account slots can be logged out and reused without a restart. */
    private void ensureOwner() {
        final long currentOwner = UserConfig.getInstance(currentAccount).getClientUserId();
        if (ownerId == currentOwner) {
            return;
        }
        ownerId = currentOwner;
        AndroidUtilities.cancelRunOnUIThread(saveRunnable);
        saveScheduled = false;
        for (int i = 0; i < items.size(); i++) {
            items.get(i).live = false;
        }
        items.clear();
        itemsByKey.clear();
        restored.clear();
        pausedActiveBig = pausedActiveSmall = 0;
        restoredLoaded = restorePending = false;
        if (observersAdded && ownerId != 0) {
            loadRestored();
        }
    }

    private boolean isCurrentItem(UploadQueueItem item) {
        ensureOwner();
        return item != null && ownerId != 0 && item.ownerId == ownerId
                && itemsByKey.get(item.operationKey) == item;
    }

    private boolean canControlItem(UploadQueueItem item) {
        if (!isCurrentItem(item)) {
            return false;
        }
        final FileUploadOperation operation = FileLoader.getInstance(currentAccount).getUploadOperation(item.path, item.encrypted);
        return operation == null || operation == item.operation;
    }

    /** Runtime queue membership, independent of ACK speed or time spent waiting for a slot. */
    public boolean isLiveUpload(UploadQueueItem item) {
        if (!isCurrentItem(item) || !item.live || item.operation == null) {
            return false;
        }
        if (item.state != UploadQueueItem.STATE_WAITING && item.state != UploadQueueItem.STATE_ACTIVE
                && item.state != UploadQueueItem.STATE_PAUSED) {
            return false;
        }
        final FileUploadOperation currentOp = FileLoader.getInstance(currentAccount).getUploadOperation(item.path, item.encrypted);
        if (currentOp != item.operation || item.operation.isFinishedOrFailed()) {
            item.live = false;
            return false;
        }
        return true;
    }

    public boolean hasLiveUploads() {
        ensureOwner();
        for (int i = 0; i < items.size(); i++) {
            if (isLiveUpload(items.get(i))) {
                return true;
            }
        }
        return false;
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
    public static void onUploadEnqueued(int account, String path, boolean encrypted, boolean small, int type,
                                        long estimatedSize, FileUploadOperation operation, long ownerId) {
        if (TextUtils.isEmpty(path) || operation == null || ownerId == 0) {
            return;
        }
        getInstance(account).enqueue(path, encrypted, small, type, estimatedSize, operation, ownerId);
    }

    public static void onUploadProgress(int account, String path, boolean encrypted, FileUploadOperation operation,
                                       long ownerId, long uploaded, long total) {
        AndroidUtilities.runOnUIThread(() -> {
            final AyuUploadManager manager = getInstance(account);
            final UploadQueueItem item = manager.findOperation(path, encrypted, operation, ownerId);
            if (item != null) {
                manager.onProgress(item, uploaded, total);
            }
        });
    }

    /** A queued upload may have been paused before it acquired its native running slot. */
    public static void onUploadStarted(int account, String path, boolean encrypted, FileUploadOperation operation,
                                       long ownerId) {
        AndroidUtilities.runOnUIThread(() -> {
            final AyuUploadManager manager = getInstance(account);
            final UploadQueueItem item = manager.findOperation(path, encrypted, operation, ownerId);
            if (item != null && item.state == UploadQueueItem.STATE_PAUSED) {
                manager.applyPause(item);
            }
        });
    }

    public static void onUploadFinished(int account, String path, boolean encrypted, FileUploadOperation operation,
                                       long ownerId, boolean success) {
        AndroidUtilities.runOnUIThread(() -> {
            final AyuUploadManager manager = getInstance(account);
            final UploadQueueItem item = manager.findOperation(path, encrypted, operation, ownerId);
            if (item != null) {
                if (success) {
                    manager.onUploaded(item);
                } else {
                    manager.onFailed(item);
                }
            }
        });
    }

    private UploadQueueItem findOperation(String path, boolean encrypted, FileUploadOperation operation, long expectedOwner) {
        ensureOwner();
        final UploadQueueItem item = itemsByKey.get(UploadQueueItem.key(path, encrypted));
        if (item == null) {
            return null;
        }
        if (operation != null && item.operation != null && item.operation != operation) {
            return null;
        }
        if (expectedOwner != 0 && item.ownerId != 0 && expectedOwner != item.ownerId) {
            return null;
        }
        return item;
    }

    private void enqueue(String path, boolean encrypted, boolean small, int type, long estimatedSize,
                         FileUploadOperation operation, long expectedOwner) {
        AndroidUtilities.runOnUIThread(() -> {
            ensureOwner();
            if (expectedOwner != ownerId || ownerId == 0) {
                return;
            }
            final String key = UploadQueueItem.key(path, encrypted);
            UploadQueueItem item = itemsByKey.get(key);
            if (item != null && item.operation == operation) {
                return;
            }
            final UploadQueueItem replaced = item;
            if (replaced != null) {
                removeItem(replaced);
            }
            item = new UploadQueueItem(path, encrypted, small, type, estimatedSize);
            if (small && item.totalBytes > 0 && item.totalBytes < MIN_TRACKED_SIZE && estimatedSize == 0) {
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
            item.ownerId = ownerId;
            item.operation = operation;
            item.operationKey = key;
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

    private void onProgress(UploadQueueItem item, long uploaded, long total) {
        if (uploaded < 0 || total < 0) {
            // Invalidated native progress is terminal; never retain a phantom live operation.
            removeItem(item);
            notifyChanged();
            return;
        }
        item.lastProgressTime = SystemClock.elapsedRealtime();
        final long now = System.currentTimeMillis();
        if (item.lastSpeedTime != 0 && now > item.lastSpeedTime + 700) {
            final long delta = uploaded - item.lastSpeedBytes;
            if (delta > 0) {
                final long speed = delta * 1000L / (now - item.lastSpeedTime);
                item.speed = item.speed == 0 ? speed : (item.speed * 2 + speed) / 3;
            } else {
                item.speed = 0;
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

    private void onUploaded(UploadQueueItem item) {
        item.state = UploadQueueItem.STATE_DONE;
        item.uploadedBytes = item.totalBytes;
        removeItem(item);
        notifyChanged();
    }

    private void onFailed(UploadQueueItem item) {
        if (item.state != UploadQueueItem.STATE_PAUSED) {
            item.state = UploadQueueItem.STATE_ERROR;
        }
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
        ensureOwner();
        return items;
    }

    public int getActiveCount() {
        ensureOwner();
        int count = 0;
        for (int a = 0; a < items.size(); a++) {
            if (items.get(a).state == UploadQueueItem.STATE_ACTIVE) {
                count++;
            }
        }
        return count;
    }

    public boolean isEmpty() {
        ensureOwner();
        return items.isEmpty();
    }

    public void setPaused(UploadQueueItem item, boolean paused, boolean automatic) {
        if (!canControlItem(item)) {
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
        if (operation == null || operation != item.operation) {
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
        if (operation != null && operation != item.operation) {
            return;
        }
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
        FileLoader.getInstance(currentAccount).uploadFile(item.path, item.encrypted, item.small, item.estimatedSize, item.type, false);
    }

    public void cancel(UploadQueueItem item) {
        if (!canControlItem(item)) {
            return;
        }
        releasePausedSlot(item);
        final FileUploadOperation operation = FileLoader.getInstance(currentAccount).getUploadOperation(item.path, item.encrypted);
        if (operation != null) {
            // un-pause first, otherwise cancel() would leave the operation half stopped
            operation.resumeUpload();
        }
        FileLoader.getInstance(currentAccount).cancelFileUpload(item.path, item.encrypted, item.operation);
        removeItem(item);
        FileLoader.getInstance(currentAccount).checkUploadQueue();
        notifyChanged();
    }

    public void moveToTop(UploadQueueItem item) {
        if (!canControlItem(item) || items.isEmpty()) {
            return;
        }
        long min = item.order;
        for (int a = 0; a < items.size(); a++) {
            min = Math.min(min, items.get(a).order);
        }
        item.order = min - 1;
        sortItems();
        FileLoader.getInstance(currentAccount).moveUploadOperationToTop(item.path, item.encrypted, item.operation);
        scheduleSave();
        notifyChanged();
    }

    private void removeItem(UploadQueueItem item) {
        item.live = false;
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
        ensureOwner();
        final ArrayList<UploadQueueItem> copy = new ArrayList<>(items);
        for (int a = 0; a < copy.size(); a++) {
            cancel(copy.get(a));
        }
    }

    // ------------------------------------------------------------- listeners

    public void addListener(Listener listener) {
        ensureOwner();
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

    private static boolean isCleaningZombies;

    /** true when at least one account still has something to upload */
    public static boolean hasActiveUploads() {
        if (!AyuUploadConfig.keepAliveWhileUploading) {
            return false;
        }
        boolean hasActive = false;
        for (int a = 0; a < instances.length; a++) {
            final AyuUploadManager manager = instances[a];
            if (manager == null || !UserConfig.getInstance(a).isClientActivated()) {
                continue;
            }
            ArrayList<UploadQueueItem> zombies = null;
            for (int b = 0; b < manager.items.size(); b++) {
                final UploadQueueItem item = manager.items.get(b);
                final int state = item.state;
                if (state == UploadQueueItem.STATE_ACTIVE || state == UploadQueueItem.STATE_WAITING) {
                    if (manager.isLiveUpload(item)) {
                        hasActive = true;
                    } else {
                        if (zombies == null) {
                            zombies = new ArrayList<>();
                        }
                        zombies.add(item);
                    }
                }
            }
            cleanupZombies(manager, zombies);
        }
        return hasActive;
    }

    /** aggregated progress over every account, 0..100, or -1 when nothing is known */
    public static int getOverallProgress() {
        long uploaded = 0, total = 0;
        for (int a = 0; a < instances.length; a++) {
            final AyuUploadManager manager = instances[a];
            if (manager == null || !UserConfig.getInstance(a).isClientActivated()) {
                continue;
            }
            for (int b = 0; b < manager.items.size(); b++) {
                final UploadQueueItem item = manager.items.get(b);
                if (manager.isLiveUpload(item) && (item.state == UploadQueueItem.STATE_ACTIVE || item.state == UploadQueueItem.STATE_WAITING)) {
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
            if (manager == null || !UserConfig.getInstance(a).isClientActivated()) {
                continue;
            }
            ArrayList<UploadQueueItem> zombies = null;
            for (int b = 0; b < manager.items.size(); b++) {
                final UploadQueueItem item = manager.items.get(b);
                final int state = item.state;
                if (state == UploadQueueItem.STATE_ACTIVE || state == UploadQueueItem.STATE_WAITING) {
                    if (manager.isLiveUpload(item)) {
                        count++;
                    } else {
                        if (zombies == null) {
                            zombies = new ArrayList<>();
                        }
                        zombies.add(item);
                    }
                }
            }
            cleanupZombies(manager, zombies);
        }
        return count;
    }

    private static void cleanupZombies(final AyuUploadManager manager, final ArrayList<UploadQueueItem> zombies) {
        if (manager == null || zombies == null || zombies.isEmpty() || isCleaningZombies) {
            return;
        }
        final Runnable cleanup = () -> {
            if (isCleaningZombies) {
                return;
            }
            isCleaningZombies = true;
            try {
                for (int i = 0; i < zombies.size(); i++) {
                    manager.removeItem(zombies.get(i));
                }
                manager.notifyChanged();
            } finally {
                isCleaningZombies = false;
            }
        };
        if (AndroidUtilities.isMainThread()) {
            cleanup.run();
        } else {
            AndroidUtilities.runOnUIThread(cleanup);
        }
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
        return "queue_" + currentAccount + "_" + ownerId;
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
        final long expectedOwner = ownerId;
        final String key = prefsKey();
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<UploadQueueItem> parsed;
            try {
                parsed = parseRestored(key);
            } catch (Throwable e) {
                FileLog.e(e);
                parsed = new ArrayList<>();
            }
            final ArrayList<UploadQueueItem> result = parsed;
            AndroidUtilities.runOnUIThread(() -> applyRestored(result, expectedOwner));
        });
    }

    /** background queue: JSON parse + the "is the file still there" check */
    private ArrayList<UploadQueueItem> parseRestored(String key) {
        final ArrayList<UploadQueueItem> result = new ArrayList<>();
        final String json = AyuUploadConfig.getString(key, null);
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
    private void applyRestored(ArrayList<UploadQueueItem> parsed, long expectedOwner) {
        ensureOwner();
        if (expectedOwner != ownerId || ownerId == 0) {
            return;
        }
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
        ensureOwner();
        saveScheduled = false;
        if (ownerId == 0) {
            return;
        }
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
