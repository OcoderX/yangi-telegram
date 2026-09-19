package org.telegram.messenger.ayu.netdiag;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * AyuGram network diagnostics: a single reference-counted sampler that measures the live state of
 * the MTProto connection and the device's throughput once a second.
 * <p>
 * What each metric really comes from:
 * <ul>
 *     <li><b>connection state</b> &ndash; {@code ConnectionsManager.getConnectionState()}</li>
 *     <li><b>datacenter</b> &ndash; {@code ConnectionsManager.getCurrentDatacenterId()}, which is
 *         the tgnet native {@code native_getCurrentDatacenterId}</li>
 *     <li><b>ping</b> &ndash; the tgnet native {@code native_getCurrentPingTime}; when that is not
 *         available yet (0) the round-trip time of our own probe is used instead</li>
 *     <li><b>probe / packet loss</b> &ndash; a {@code help.getNearestDc} round trip sent every
 *         {@link NetDiagConfig#probeIntervalMs}. A probe counts as lost when it does not come back
 *         within {@link #PROBE_TIMEOUT_MS} or fails with a transport error. A server-side error
 *         still means the packet made it there and back, so it counts as delivered. Loss is the
 *         percentage of lost probes in a sliding window of the last {@link #LOSS_WINDOW}.</li>
 *     <li><b>proxy ping</b> &ndash; {@code ConnectionsManager.checkProxy(...)}, the same native
 *         call the proxy list uses, re-run every {@link NetDiagConfig#proxyCheckIntervalMs}</li>
 *     <li><b>download / upload speed</b> &ndash; {@code TrafficStats.getUidRxBytes/getUidTxBytes}
 *         deltas for our own uid: whole-app throughput including media, calls and MTProto</li>
 *     <li><b>active transfer speed</b> &ndash; the deltas reported by
 *         {@code NotificationCenter.fileLoadProgressChanged} / {@code fileUploadProgressChanged}
 *         summed per second, i.e. only the files the file loader is moving right now</li>
 * </ul>
 * Sampling runs on {@link Utilities#globalQueue}; samples are delivered on the UI thread.
 */
public class NetworkDiagnostics {

    // ---------------------------------------------------------------- constants

    public static final int HISTORY_SIZE = 60;
    public static final int LOSS_WINDOW = 20;
    public static final long PROBE_TIMEOUT_MS = 3000;

    /** ping (ms) above which the connection is called slow */
    public static final int PING_WARN = 300;
    public static final int PING_BAD = 800;
    /** packet loss (%) above which the connection is called lossy */
    public static final float LOSS_WARN = 10f;
    public static final float LOSS_BAD = 30f;
    /** Wi-Fi RSSI (dBm) below which the signal is called weak */
    public static final int RSSI_WEAK = -75;

    public static final int REASON_OK = 0;
    public static final int REASON_NO_NETWORK = 1;
    public static final int REASON_CONNECTING = 2;
    public static final int REASON_UPDATING = 3;
    public static final int REASON_PROXY_CONNECTING = 4;
    public static final int REASON_PROXY_SLOW = 5;
    public static final int REASON_HIGH_PING = 6;
    public static final int REASON_PACKET_LOSS = 7;
    public static final int REASON_DATA_SAVER = 8;
    public static final int REASON_METERED = 9;
    public static final int REASON_BATTERY_SAVER = 10;
    public static final int REASON_WEAK_WIFI = 11;

    public static final int SEVERITY_OK = 0;
    public static final int SEVERITY_WARN = 1;
    public static final int SEVERITY_BAD = 2;

    // ---------------------------------------------------------------- sample

    /** One immutable-ish snapshot of the network. Produced on the sampler thread, read on the UI thread. */
    public static class Sample {
        public int account;
        public int connectionState = ConnectionsManager.ConnectionStateConnected;
        public int datacenterId;
        /** ms, -1 when unknown */
        public int ping = -1;
        /** ms, -1 when the last check failed, 0 when there is no proxy */
        public long proxyPing;
        public boolean proxyActive;
        public String proxyAddress = "";
        /** 0..100 */
        public float loss;
        public int probesSent;
        public int probesLost;
        /** whole-app throughput, bytes per second */
        public long downSpeed;
        public long upSpeed;
        /** file-loader throughput, bytes per second */
        public long activeDownSpeed;
        public long activeUpSpeed;
        public int activeDownloads;
        public int activeUploads;
        public boolean networkOnline = true;
        public boolean metered;
        public boolean dataSaver;
        public boolean powerSave;
        public boolean wifi;
        /** dBm, 0 when unknown / not on Wi-Fi */
        public int wifiRssi;
        public int reason = REASON_OK;
        public long timestamp;

        public int severity() {
            switch (reason) {
                case REASON_OK:
                    return SEVERITY_OK;
                case REASON_NO_NETWORK:
                case REASON_CONNECTING:
                case REASON_PROXY_CONNECTING:
                    return SEVERITY_BAD;
                case REASON_PACKET_LOSS:
                    return loss >= LOSS_BAD ? SEVERITY_BAD : SEVERITY_WARN;
                case REASON_PROXY_SLOW:
                    return proxyPing < 0 ? SEVERITY_BAD : SEVERITY_WARN;
                case REASON_HIGH_PING:
                    return ping >= PING_BAD ? SEVERITY_BAD : SEVERITY_WARN;
                default:
                    return SEVERITY_WARN;
            }
        }
    }

    public interface Listener {
        void onNetworkSample(Sample sample);
    }

    // ---------------------------------------------------------------- ring buffer

    /** Fixed-size float ring; {@link #copyTo} writes oldest &rarr; newest. */
    public static class Ring {
        private final float[] data;
        private int count;
        private int head;

        Ring(int size) {
            data = new float[size];
        }

        synchronized void push(float v) {
            data[head] = v;
            head = (head + 1) % data.length;
            if (count < data.length) {
                count++;
            }
        }

        synchronized void clear() {
            count = 0;
            head = 0;
        }

        public synchronized int size() {
            return count;
        }

        /** Copies up to {@code out.length} newest values into {@code out}, oldest first. Returns how many. */
        public synchronized int copyTo(float[] out) {
            int n = Math.min(count, out.length);
            int start = ((head - n) % data.length + data.length) % data.length;
            for (int i = 0; i < n; i++) {
                out[i] = data[(start + i) % data.length];
            }
            return n;
        }

        public synchronized float max() {
            float m = 0;
            for (int i = 0; i < count; i++) {
                m = Math.max(m, data[i]);
            }
            return m;
        }
    }

    // ---------------------------------------------------------------- state

    private static volatile NetworkDiagnostics instance;

    public static NetworkDiagnostics getInstance() {
        NetworkDiagnostics local = instance;
        if (local == null) {
            synchronized (NetworkDiagnostics.class) {
                local = instance;
                if (local == null) {
                    instance = local = new NetworkDiagnostics();
                }
            }
        }
        return local;
    }

    public final Ring pingHistory = new Ring(HISTORY_SIZE);
    public final Ring lossHistory = new Ring(HISTORY_SIZE);
    public final Ring downHistory = new Ring(HISTORY_SIZE);
    public final Ring upHistory = new Ring(HISTORY_SIZE);

    private final ArrayList<Listener> listeners = new ArrayList<>();
    private final Object lock = new Object();

    private int refCount;
    private volatile boolean running;
    private volatile Sample lastSample;

    private int boundAccount = -1;

    // traffic stats
    private long lastRx = -1, lastTx = -1, lastTrafficTime;

    // probes
    private final boolean[] probeWindow = new boolean[LOSS_WINDOW]; // true = lost
    private int probeWindowCount, probeWindowHead;
    private long lastProbeTime;
    private long probeStartTime;
    private boolean probePending;
    private int probeGeneration;
    private volatile int lastProbeRtt = -1;

    // proxy
    private long lastProxyCheckTime;
    private volatile boolean proxyCheckPending;
    private volatile long proxyPingValue;

    // per-file progress, guarded by lock
    private final HashMap<String, long[]> downProgress = new HashMap<>(); // location -> {lastBytes, lastTouchMs}
    private final HashMap<String, long[]> upProgress = new HashMap<>();
    private long activeDownBytes, activeUpBytes;

    private final Runnable tickRunnable = this::tick;
    private ProgressObserver observer;

    private NetworkDiagnostics() {
        NetDiagConfig.load();
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Starts sampling. Reference counted: every {@link #start()} must be matched by exactly one
     * {@link #stop()}. Safe to call from any thread.
     */
    public void start() {
        boolean begin = false;
        synchronized (lock) {
            refCount++;
            if (refCount == 1 && !running) {
                running = true;
                begin = true;
            }
        }
        if (begin) {
            NetDiagConfig.load();
            lastRx = lastTx = -1;
            lastTrafficTime = 0;
            AndroidUtilities.runOnUIThread(this::bindObserver);
            Utilities.globalQueue.postRunnable(tickRunnable);
        }
    }

    public void stop() {
        boolean end = false;
        synchronized (lock) {
            if (refCount > 0) {
                refCount--;
            }
            if (refCount == 0 && running) {
                running = false;
                end = true;
            }
        }
        if (end) {
            Utilities.globalQueue.cancelRunnable(tickRunnable);
            AndroidUtilities.runOnUIThread(this::unbindObserver);
            synchronized (lock) {
                downProgress.clear();
                upProgress.clear();
                activeDownBytes = 0;
                activeUpBytes = 0;
                probePending = false;
                probeGeneration++;
            }
        }
    }

    public boolean isRunning() {
        return running;
    }

    public Sample getLastSample() {
        return lastSample;
    }

    public void addListener(Listener listener) {
        if (listener == null) {
            return;
        }
        synchronized (listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        }
    }

    public void removeListener(Listener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    // ---------------------------------------------------------------- notification binding

    private void bindObserver() {
        if (observer == null) {
            observer = new ProgressObserver();
        }
        int account = UserConfig.selectedAccount;
        if (boundAccount == account) {
            return;
        }
        unbindAccountObservers();
        boundAccount = account;
        NotificationCenter nc = NotificationCenter.getInstance(account);
        nc.addObserver(observer, NotificationCenter.fileLoadProgressChanged);
        nc.addObserver(observer, NotificationCenter.fileUploadProgressChanged);
        nc.addObserver(observer, NotificationCenter.didUpdateConnectionState);
        NotificationCenter.getGlobalInstance().addObserver(observer, NotificationCenter.activeAccountChanged);
        NotificationCenter.getGlobalInstance().addObserver(observer, NotificationCenter.proxySettingsChanged);
    }

    private void unbindAccountObservers() {
        if (observer == null || boundAccount < 0) {
            return;
        }
        NotificationCenter nc = NotificationCenter.getInstance(boundAccount);
        nc.removeObserver(observer, NotificationCenter.fileLoadProgressChanged);
        nc.removeObserver(observer, NotificationCenter.fileUploadProgressChanged);
        nc.removeObserver(observer, NotificationCenter.didUpdateConnectionState);
        boundAccount = -1;
    }

    private void unbindObserver() {
        if (observer == null) {
            return;
        }
        unbindAccountObservers();
        NotificationCenter.getGlobalInstance().removeObserver(observer, NotificationCenter.activeAccountChanged);
        NotificationCenter.getGlobalInstance().removeObserver(observer, NotificationCenter.proxySettingsChanged);
    }

    private class ProgressObserver implements NotificationCenter.NotificationCenterDelegate {
        @Override
        public void didReceivedNotification(int id, int account, Object... args) {
            if (id == NotificationCenter.fileLoadProgressChanged) {
                if (args.length >= 3 && args[0] instanceof String && args[1] instanceof Long) {
                    accumulate(downProgress, (String) args[0], (Long) args[1], true);
                }
            } else if (id == NotificationCenter.fileUploadProgressChanged) {
                if (args.length >= 3 && args[0] instanceof String && args[1] instanceof Long) {
                    accumulate(upProgress, (String) args[0], (Long) args[1], false);
                }
            } else if (id == NotificationCenter.activeAccountChanged) {
                if (running) {
                    synchronized (lock) {
                        downProgress.clear();
                        upProgress.clear();
                        activeDownBytes = 0;
                        activeUpBytes = 0;
                        probePending = false;
                        probeGeneration++;
                        probeWindowCount = 0;
                        probeWindowHead = 0;
                    }
                    pingHistory.clear();
                    lossHistory.clear();
                    downHistory.clear();
                    upHistory.clear();
                    bindObserver();
                }
            } else if (id == NotificationCenter.proxySettingsChanged) {
                proxyPingValue = 0;
                lastProxyCheckTime = 0;
            }
        }
    }

    private void accumulate(HashMap<String, long[]> map, String location, long uploaded, boolean download) {
        if (location == null || uploaded < 0) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        synchronized (lock) {
            long[] prev = map.get(location);
            if (prev == null) {
                map.put(location, new long[]{uploaded, now});
                return;
            }
            long delta = uploaded - prev[0];
            prev[0] = uploaded;
            prev[1] = now;
            if (delta > 0) {
                if (download) {
                    activeDownBytes += delta;
                } else {
                    activeUpBytes += delta;
                }
            }
        }
    }

    // ---------------------------------------------------------------- sampling

    private void tick() {
        if (!running) {
            return;
        }
        try {
            Sample sample = sampleNow();
            lastSample = sample;
            pingHistory.push(sample.ping < 0 ? 0 : sample.ping);
            lossHistory.push(sample.loss);
            downHistory.push(sample.downSpeed);
            upHistory.push(sample.upSpeed);
            dispatch(sample);
        } catch (Throwable ignore) {
        }
        if (running) {
            Utilities.globalQueue.postRunnable(tickRunnable, Math.max(250, NetDiagConfig.samplingIntervalMs));
        }
    }

    private void dispatch(final Sample sample) {
        AndroidUtilities.runOnUIThread(() -> {
            ArrayList<Listener> copy;
            synchronized (listeners) {
                if (listeners.isEmpty()) {
                    return;
                }
                copy = new ArrayList<>(listeners);
            }
            for (int i = 0; i < copy.size(); i++) {
                try {
                    copy.get(i).onNetworkSample(sample);
                } catch (Throwable ignore) {
                }
            }
        });
    }

    private Sample sampleNow() {
        final int account = UserConfig.selectedAccount;
        final long now = SystemClock.elapsedRealtime();
        Sample s = new Sample();
        s.account = account;
        s.timestamp = System.currentTimeMillis();

        ConnectionsManager cm = ConnectionsManager.getInstance(account);
        try {
            s.connectionState = cm.getConnectionState();
        } catch (Throwable ignore) {
        }
        try {
            s.datacenterId = cm.getCurrentDatacenterId();
        } catch (Throwable ignore) {
        }
        try {
            int p = ConnectionsManager.native_getCurrentPingTime(account);
            s.ping = p > 0 ? p : lastProbeRtt;
        } catch (Throwable ignore) {
            s.ping = lastProbeRtt;
        }

        // ---- throughput from TrafficStats ----
        long rx = -1, tx = -1;
        try {
            int uid = Process.myUid();
            rx = TrafficStats.getUidRxBytes(uid);
            tx = TrafficStats.getUidTxBytes(uid);
            if (rx == TrafficStats.UNSUPPORTED || tx == TrafficStats.UNSUPPORTED) {
                rx = TrafficStats.getTotalRxBytes();
                tx = TrafficStats.getTotalTxBytes();
            }
        } catch (Throwable ignore) {
        }
        if (rx >= 0 && tx >= 0) {
            if (lastRx >= 0 && lastTrafficTime > 0) {
                float elapsedSec = (now - lastTrafficTime) / 1000f;
                if (elapsedSec > 0.05f) {
                    s.downSpeed = Math.max(0, (long) ((rx - lastRx) / elapsedSec));
                    s.upSpeed = Math.max(0, (long) ((tx - lastTx) / elapsedSec));
                }
            }
            lastRx = rx;
            lastTx = tx;
            lastTrafficTime = now;
        }

        // ---- throughput from the file loader ----
        long downBytes, upBytes;
        int activeDown = 0, activeUp = 0;
        synchronized (lock) {
            downBytes = activeDownBytes;
            upBytes = activeUpBytes;
            activeDownBytes = 0;
            activeUpBytes = 0;
            activeDown = pruneAndCount(downProgress, now);
            activeUp = pruneAndCount(upProgress, now);
        }
        float seconds = Math.max(0.25f, NetDiagConfig.samplingIntervalMs / 1000f);
        s.activeDownSpeed = (long) (downBytes / seconds);
        s.activeUpSpeed = (long) (upBytes / seconds);
        s.activeDownloads = activeDown;
        s.activeUploads = activeUp;

        // ---- probes ----
        checkProbe(account, now);
        s.loss = currentLoss();
        synchronized (lock) {
            s.probesSent = probeWindowCount;
            int lost = 0;
            for (int i = 0; i < probeWindowCount; i++) {
                if (probeWindow[i]) {
                    lost++;
                }
            }
            s.probesLost = lost;
        }

        // ---- proxy ----
        SharedConfig.ProxyInfo proxy = SharedConfig.currentProxy;
        boolean proxyOn = false;
        try {
            proxyOn = SharedConfig.isProxyEnabled() && proxy != null;
        } catch (Throwable ignore) {
        }
        s.proxyActive = proxyOn;
        if (proxyOn) {
            s.proxyAddress = proxy.address == null ? "" : (proxy.address + ":" + proxy.port);
            s.proxyPing = proxyPingValue;
            checkProxyPing(account, proxy, now);
        } else {
            s.proxyPing = 0;
            proxyPingValue = 0;
        }

        // ---- device / radio state ----
        fillDeviceState(s);
        s.reason = computeReason(s);
        return s;
    }

    private static int pruneAndCount(Map<String, long[]> map, long now) {
        int active = 0;
        Iterator<Map.Entry<String, long[]>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            long[] v = it.next().getValue();
            if (now - v[1] > 10000) {
                it.remove();
            } else if (now - v[1] <= 2500) {
                active++;
            }
        }
        return active;
    }

    // ---------------------------------------------------------------- probes

    private void checkProbe(int account, long now) {
        boolean sendNew = false;
        int generation = 0;
        synchronized (lock) {
            if (probePending && now - probeStartTime > PROBE_TIMEOUT_MS) {
                probePending = false;
                probeGeneration++;
                pushProbe(true);
            }
            if (!probePending && now - lastProbeTime >= Math.max(1000, NetDiagConfig.probeIntervalMs)) {
                probePending = true;
                probeStartTime = now;
                lastProbeTime = now;
                generation = probeGeneration;
                sendNew = true;
            }
        }
        if (sendNew) {
            sendProbe(account, generation);
        }
    }

    private void sendProbe(int account, final int generation) {
        final long started = SystemClock.elapsedRealtime();
        try {
            TLRPC.TL_help_getNearestDc req = new TLRPC.TL_help_getNearestDc();
            ConnectionsManager.getInstance(account).sendRequest(req, (TLObject response, TLRPC.TL_error error) -> {
                long rtt = SystemClock.elapsedRealtime() - started;
                boolean lost;
                if (response != null) {
                    lost = false;
                } else if (error != null && error.code > 0) {
                    // the server answered (flood wait, auth error, ...) - the round trip happened
                    lost = false;
                } else {
                    lost = true;
                }
                synchronized (lock) {
                    if (generation != probeGeneration || !probePending) {
                        return; // already counted as a timeout, or sampling stopped
                    }
                    probePending = false;
                    pushProbe(lost || rtt > PROBE_TIMEOUT_MS);
                }
                if (!lost && rtt <= PROBE_TIMEOUT_MS) {
                    lastProbeRtt = (int) rtt;
                }
            }, ConnectionsManager.RequestFlagWithoutLogin | ConnectionsManager.RequestFlagFailOnServerErrors | ConnectionsManager.RequestFlagDoNotWaitFloodWait);
        } catch (Throwable e) {
            synchronized (lock) {
                if (generation == probeGeneration && probePending) {
                    probePending = false;
                    pushProbe(true);
                }
            }
        }
    }

    /** Must be called while holding {@link #lock}. */
    private void pushProbe(boolean lost) {
        probeWindow[probeWindowHead] = lost;
        probeWindowHead = (probeWindowHead + 1) % LOSS_WINDOW;
        if (probeWindowCount < LOSS_WINDOW) {
            probeWindowCount++;
        }
    }

    private float currentLoss() {
        synchronized (lock) {
            if (probeWindowCount == 0) {
                return 0f;
            }
            int lost = 0;
            for (int i = 0; i < probeWindowCount; i++) {
                if (probeWindow[i]) {
                    lost++;
                }
            }
            return lost * 100f / probeWindowCount;
        }
    }

    // ---------------------------------------------------------------- proxy

    private void checkProxyPing(int account, SharedConfig.ProxyInfo proxy, long now) {
        if (proxyCheckPending || now - lastProxyCheckTime < Math.max(3000, NetDiagConfig.proxyCheckIntervalMs)) {
            return;
        }
        lastProxyCheckTime = now;
        proxyCheckPending = true;
        try {
            ConnectionsManager.getInstance(account).checkProxy(
                    proxy.address, proxy.port, proxy.username, proxy.password, proxy.secret,
                    time -> {
                        proxyCheckPending = false;
                        proxyPingValue = time;
                    });
        } catch (Throwable e) {
            proxyCheckPending = false;
            proxyPingValue = -1;
        }
    }

    // ---------------------------------------------------------------- device state

    private void fillDeviceState(Sample s) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        try {
            s.networkOnline = ApplicationLoader.isNetworkOnline();
        } catch (Throwable ignore) {
        }
        ConnectivityManager cm = null;
        try {
            cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        } catch (Throwable ignore) {
        }
        if (cm != null) {
            try {
                s.metered = cm.isActiveNetworkMetered();
            } catch (Throwable ignore) {
            }
            if (Build.VERSION.SDK_INT >= 24) {
                try {
                    s.dataSaver = s.metered
                            && cm.getRestrictBackgroundStatus() == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED;
                } catch (Throwable ignore) {
                }
            }
            try {
                NetworkInfo info = cm.getActiveNetworkInfo();
                s.wifi = info != null && info.getType() == ConnectivityManager.TYPE_WIFI;
            } catch (Throwable ignore) {
            }
        }
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                s.powerSave = pm.isPowerSaveMode();
            }
        } catch (Throwable ignore) {
        }
        if (s.wifi) {
            try {
                WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    WifiInfo info = wm.getConnectionInfo();
                    if (info != null) {
                        int rssi = info.getRssi();
                        // -127 / 0 are the "unknown" markers used by the framework
                        s.wifiRssi = (rssi < 0 && rssi > -127) ? rssi : 0;
                    }
                }
            } catch (Throwable ignore) {
            }
        }
    }

    private int computeReason(Sample s) {
        if (!s.networkOnline || s.connectionState == ConnectionsManager.ConnectionStateWaitingForNetwork) {
            return REASON_NO_NETWORK;
        }
        if (s.connectionState == ConnectionsManager.ConnectionStateConnecting) {
            return REASON_CONNECTING;
        }
        if (s.connectionState == ConnectionsManager.ConnectionStateConnectingToProxy) {
            return REASON_PROXY_CONNECTING;
        }
        if (s.connectionState == ConnectionsManager.ConnectionStateUpdating) {
            return REASON_UPDATING;
        }
        if (s.proxyActive && (s.proxyPing < 0 || s.proxyPing > PING_WARN)) {
            return REASON_PROXY_SLOW;
        }
        if (s.ping > PING_WARN) {
            return REASON_HIGH_PING;
        }
        if (s.loss > LOSS_WARN) {
            return REASON_PACKET_LOSS;
        }
        if (s.dataSaver) {
            return REASON_DATA_SAVER;
        }
        if (s.metered) {
            return REASON_METERED;
        }
        if (s.powerSave) {
            return REASON_BATTERY_SAVER;
        }
        if (s.wifi && s.wifiRssi != 0 && s.wifiRssi <= RSSI_WEAK) {
            return REASON_WEAK_WIFI;
        }
        return REASON_OK;
    }

    // ---------------------------------------------------------------- run test

    public interface TestCallback {
        /** @param progress 0..1 */
        void onProgress(float progress);

        void onFinished(TestResult result);
    }

    public static class TestResult {
        public int probes;
        public int lost;
        public int minPing = -1;
        public int maxPing = -1;
        public int avgPing = -1;
        public float jitter;
        public long proxyPing;
        public boolean proxyChecked;
        /** bytes per second measured over the request burst via TrafficStats */
        public long burstSpeed;
        public int burstRequests;
        public long burstDurationMs;
    }

    private volatile boolean testRunning;

    public boolean isTestRunning() {
        return testRunning;
    }

    /**
     * Runs a one-off diagnostic: 10 {@code help.getNearestDc} round trips, a proxy check when a
     * proxy is configured, and a burst of {@code help.getConfig} requests whose real byte
     * throughput is read from {@link TrafficStats}. No external hosts are contacted.
     */
    public void runTest(final int account, final TestCallback callback) {
        if (testRunning) {
            return;
        }
        testRunning = true;
        // Deliberately not Utilities.globalQueue: this worker blocks for up to ~45 s waiting on
        // round trips, and the shared queue is used by the rest of the app.
        Thread worker = new Thread(() -> {
            TestResult result = new TestResult();
            final int total = 10;
            long sum = 0;
            int ok = 0;
            final ArrayList<Integer> pings = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                final int rtt = blockingProbe(account);
                if (rtt >= 0) {
                    ok++;
                    sum += rtt;
                    pings.add(rtt);
                    if (result.minPing < 0 || rtt < result.minPing) {
                        result.minPing = rtt;
                    }
                    if (rtt > result.maxPing) {
                        result.maxPing = rtt;
                    }
                } else {
                    result.lost++;
                }
                result.probes++;
                final float p = (i + 1) / (float) (total + 2);
                AndroidUtilities.runOnUIThread(() -> callback.onProgress(p));
            }
            if (ok > 0) {
                result.avgPing = (int) (sum / ok);
                float acc = 0;
                for (int i = 1; i < pings.size(); i++) {
                    acc += Math.abs(pings.get(i) - pings.get(i - 1));
                }
                result.jitter = pings.size() > 1 ? acc / (pings.size() - 1) : 0;
            }
            AndroidUtilities.runOnUIThread(() -> callback.onProgress(total / (float) (total + 2)));

            // proxy
            SharedConfig.ProxyInfo proxy = SharedConfig.currentProxy;
            boolean proxyOn = false;
            try {
                proxyOn = SharedConfig.isProxyEnabled() && proxy != null;
            } catch (Throwable ignore) {
            }
            if (proxyOn) {
                result.proxyChecked = true;
                result.proxyPing = blockingProxyCheck(account, proxy);
            }
            AndroidUtilities.runOnUIThread(() -> callback.onProgress((total + 1) / (float) (total + 2)));

            // throughput burst
            fillBurst(account, result);

            testRunning = false;
            AndroidUtilities.runOnUIThread(() -> {
                callback.onProgress(1f);
                callback.onFinished(result);
            });
        }, "ayuNetDiagTest");
        worker.setDaemon(true);
        worker.start();
    }

    /** @return round-trip time in ms, or -1 when the probe was lost */
    private int blockingProbe(int account) {
        final Object waiter = new Object();
        final int[] out = {-1};
        final boolean[] done = {false};
        final long started = SystemClock.elapsedRealtime();
        try {
            TLRPC.TL_help_getNearestDc req = new TLRPC.TL_help_getNearestDc();
            ConnectionsManager.getInstance(account).sendRequest(req, (TLObject response, TLRPC.TL_error error) -> {
                synchronized (waiter) {
                    if (response != null || (error != null && error.code > 0)) {
                        out[0] = (int) (SystemClock.elapsedRealtime() - started);
                    }
                    done[0] = true;
                    waiter.notifyAll();
                }
            }, ConnectionsManager.RequestFlagWithoutLogin | ConnectionsManager.RequestFlagFailOnServerErrors | ConnectionsManager.RequestFlagDoNotWaitFloodWait);
        } catch (Throwable e) {
            return -1;
        }
        synchronized (waiter) {
            long deadline = SystemClock.elapsedRealtime() + PROBE_TIMEOUT_MS;
            while (!done[0]) {
                long wait = deadline - SystemClock.elapsedRealtime();
                if (wait <= 0) {
                    break;
                }
                try {
                    waiter.wait(wait);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        return out[0];
    }

    private long blockingProxyCheck(int account, SharedConfig.ProxyInfo proxy) {
        final Object waiter = new Object();
        final long[] out = {-1};
        final boolean[] done = {false};
        try {
            ConnectionsManager.getInstance(account).checkProxy(
                    proxy.address, proxy.port, proxy.username, proxy.password, proxy.secret,
                    time -> {
                        synchronized (waiter) {
                            out[0] = time;
                            done[0] = true;
                            waiter.notifyAll();
                        }
                    });
        } catch (Throwable e) {
            return -1;
        }
        synchronized (waiter) {
            long deadline = SystemClock.elapsedRealtime() + 8000;
            while (!done[0]) {
                long wait = deadline - SystemClock.elapsedRealtime();
                if (wait <= 0) {
                    break;
                }
                try {
                    waiter.wait(wait);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        return out[0];
    }

    /** Fires a small burst of config requests and measures the real bytes moved with TrafficStats. */
    private void fillBurst(int account, TestResult result) {
        final int count = 8;
        long rxBefore = safeRx();
        long started = SystemClock.elapsedRealtime();
        final Object waiter = new Object();
        final int[] remaining = {count};
        final int[] answered = {0};
        try {
            for (int i = 0; i < count; i++) {
                TLRPC.TL_help_getConfig req = new TLRPC.TL_help_getConfig();
                ConnectionsManager.getInstance(account).sendRequest(req, (TLObject response, TLRPC.TL_error error) -> {
                    synchronized (waiter) {
                        if (response != null) {
                            answered[0]++;
                        }
                        remaining[0]--;
                        waiter.notifyAll();
                    }
                }, ConnectionsManager.RequestFlagWithoutLogin | ConnectionsManager.RequestFlagFailOnServerErrors | ConnectionsManager.RequestFlagDoNotWaitFloodWait);
            }
        } catch (Throwable ignore) {
        }
        synchronized (waiter) {
            long deadline = SystemClock.elapsedRealtime() + 10000;
            while (remaining[0] > 0) {
                long wait = deadline - SystemClock.elapsedRealtime();
                if (wait <= 0) {
                    break;
                }
                try {
                    waiter.wait(wait);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        long elapsed = Math.max(1, SystemClock.elapsedRealtime() - started);
        long rxAfter = safeRx();
        result.burstRequests = answered[0];
        result.burstDurationMs = elapsed;
        if (rxBefore >= 0 && rxAfter >= rxBefore) {
            result.burstSpeed = (rxAfter - rxBefore) * 1000L / elapsed;
        }
    }

    private static long safeRx() {
        try {
            long rx = TrafficStats.getUidRxBytes(Process.myUid());
            if (rx == TrafficStats.UNSUPPORTED) {
                rx = TrafficStats.getTotalRxBytes();
            }
            return rx;
        } catch (Throwable e) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- formatting

    public static String formatSpeed(long bytesPerSecond) {
        if (bytesPerSecond <= 0) {
            return "0 KB/s";
        }
        return AndroidUtilities.formatFileSize(bytesPerSecond, true, true) + "/s";
    }

    public static String formatPing(int ping) {
        if (ping <= 0) {
            return "—";
        }
        return LocaleController.formatString(R.string.AyuNetDiagMs, ping);
    }

    public static String formatProxyPing(long ping) {
        if (ping < 0) {
            return LocaleController.getString(R.string.AyuNetDiagUnreachable);
        }
        if (ping == 0) {
            return "—";
        }
        return LocaleController.formatString(R.string.AyuNetDiagMs, (int) ping);
    }

    public static String formatLoss(float loss) {
        return String.format(Locale.US, "%.0f%%", loss);
    }

    public static String getStateText(int connectionState) {
        switch (connectionState) {
            case ConnectionsManager.ConnectionStateConnecting:
                return LocaleController.getString(R.string.AyuNetDiagStateConnecting);
            case ConnectionsManager.ConnectionStateWaitingForNetwork:
                return LocaleController.getString(R.string.AyuNetDiagStateNoNetwork);
            case ConnectionsManager.ConnectionStateConnectingToProxy:
                return LocaleController.getString(R.string.AyuNetDiagStateProxy);
            case ConnectionsManager.ConnectionStateUpdating:
                return LocaleController.getString(R.string.AyuNetDiagStateUpdating);
            default:
                return LocaleController.getString(R.string.AyuNetDiagStateConnected);
        }
    }

    public static String getReasonText(int reason) {
        switch (reason) {
            case REASON_NO_NETWORK:
                return LocaleController.getString(R.string.AyuNetDiagReasonNoNetwork);
            case REASON_CONNECTING:
                return LocaleController.getString(R.string.AyuNetDiagReasonConnecting);
            case REASON_UPDATING:
                return LocaleController.getString(R.string.AyuNetDiagReasonUpdating);
            case REASON_PROXY_CONNECTING:
                return LocaleController.getString(R.string.AyuNetDiagReasonProxyConnecting);
            case REASON_PROXY_SLOW:
                return LocaleController.getString(R.string.AyuNetDiagReasonProxySlow);
            case REASON_HIGH_PING:
                return LocaleController.getString(R.string.AyuNetDiagReasonHighPing);
            case REASON_PACKET_LOSS:
                return LocaleController.getString(R.string.AyuNetDiagReasonPacketLoss);
            case REASON_DATA_SAVER:
                return LocaleController.getString(R.string.AyuNetDiagReasonDataSaver);
            case REASON_METERED:
                return LocaleController.getString(R.string.AyuNetDiagReasonMetered);
            case REASON_BATTERY_SAVER:
                return LocaleController.getString(R.string.AyuNetDiagReasonBatterySaver);
            case REASON_WEAK_WIFI:
                return LocaleController.getString(R.string.AyuNetDiagReasonWeakWifi);
            default:
                return LocaleController.getString(R.string.AyuNetDiagReasonOk);
        }
    }

    public static String getReasonHint(int reason) {
        switch (reason) {
            case REASON_NO_NETWORK:
                return LocaleController.getString(R.string.AyuNetDiagHintNoNetwork);
            case REASON_CONNECTING:
            case REASON_PROXY_CONNECTING:
                return LocaleController.getString(R.string.AyuNetDiagHintConnecting);
            case REASON_UPDATING:
                return LocaleController.getString(R.string.AyuNetDiagHintUpdating);
            case REASON_PROXY_SLOW:
                return LocaleController.getString(R.string.AyuNetDiagHintProxySlow);
            case REASON_HIGH_PING:
                return LocaleController.getString(R.string.AyuNetDiagHintHighPing);
            case REASON_PACKET_LOSS:
                return LocaleController.getString(R.string.AyuNetDiagHintPacketLoss);
            case REASON_DATA_SAVER:
                return LocaleController.getString(R.string.AyuNetDiagHintDataSaver);
            case REASON_METERED:
                return LocaleController.getString(R.string.AyuNetDiagHintMetered);
            case REASON_BATTERY_SAVER:
                return LocaleController.getString(R.string.AyuNetDiagHintBatterySaver);
            case REASON_WEAK_WIFI:
                return LocaleController.getString(R.string.AyuNetDiagHintWeakWifi);
            default:
                return LocaleController.getString(R.string.AyuNetDiagHintOk);
        }
    }
}
