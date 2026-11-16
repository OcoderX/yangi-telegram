package org.telegram.messenger.ayu.update;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BetaUpdate;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** App-wide updater; immutable UI snapshots, serialized disk/network work and no polling timer. */
public final class OxAppUpdater {
    public enum State { IDLE, CHECKING, AVAILABLE, DOWNLOADING, VERIFYING, READY, ERROR }
    public enum Error { NONE, NETWORK, INVALID_MANIFEST, INVALID_APK, STORAGE, CANCELED, NO_UPDATE }

    public interface Listener { void onChanged(Snapshot snapshot); }
    public interface InstallCallback { void onResult(File file, Error error); }

    public static final class Snapshot {
        public final State state;
        public final Error error;
        public final BetaUpdate update;
        public final long downloadedBytes;
        public final long totalBytes;
        public final File verifiedFile;

        private Snapshot(State state, Error error, OxUpdateManifest manifest, long downloaded, File file) {
            this.state = state;
            this.error = error;
            update = manifest == null ? null : new BetaUpdate(manifest.version, manifest.versionCode, manifest.changelog);
            downloadedBytes = downloaded;
            totalBytes = manifest == null ? 0 : manifest.size;
            verifiedFile = file;
        }

        public float progress() {
            return totalBytes <= 0 ? 0 : Math.max(0f, Math.min(1f, (float) downloadedBytes / totalBytes));
        }
    }

    private static volatile OxAppUpdater instance;
    private static final long SUCCESS_INTERVAL = 6 * 60 * 60 * 1000L;
    private static final long RETRY_INTERVAL = 15 * 60 * 1000L;
    private static final long MANUAL_INTERVAL = 30 * 1000L;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "OxAppUpdater");
        thread.setDaemon(true);
        return thread;
    });
    private final ArrayList<WeakReference<Listener>> listeners = new ArrayList<>();
    private final ArrayList<Runnable> checkCallbacks = new ArrayList<>();
    private volatile Snapshot current = new Snapshot(State.CHECKING, Error.NONE, null, 0, null);
    // Fields below are owned by the main thread. Workers only receive captured immutable values.
    private OxUpdateManifest manifest;
    private File verifiedFile;
    private OxUpdateIo.Cancellation activeCancellation;
    private boolean initializing = true;
    private boolean initialCheckRequested;
    private boolean initialCheckForced;
    private boolean checking;
    private long generation;
    private long installedVersion;
    private long lastAttempt;
    private boolean lastSucceeded;

    public static OxAppUpdater getInstance() {
        OxAppUpdater value = instance;
        if (value == null) {
            synchronized (OxAppUpdater.class) {
                value = instance;
                if (value == null) instance = value = new OxAppUpdater(ApplicationLoader.applicationContext);
            }
        }
        return value;
    }

    private OxAppUpdater(Context context) {
        this.context = context.getApplicationContext();
        worker.execute(this::restore);
    }

    public Snapshot snapshot() { return current; }

    public void addListener(Listener listener) {
        if (listener == null) return;
        onMain(() -> {
            for (WeakReference<Listener> reference : listeners) if (reference.get() == listener) return;
            listeners.add(new WeakReference<>(listener));
            listener.onChanged(current);
        });
    }

    public void removeListener(Listener listener) {
        onMain(() -> {
            for (int i = listeners.size() - 1; i >= 0; i--) {
                Listener value = listeners.get(i).get();
                if (value == null || value == listener) listeners.remove(i);
            }
        });
    }

    public void check(boolean force, Runnable done) {
        onMain(() -> {
            if (done != null) checkCallbacks.add(done);
            if (initializing) {
                initialCheckRequested = true;
                initialCheckForced |= force;
                return;
            }
            if (checking) return;
            if (current.state == State.DOWNLOADING || current.state == State.VERIFYING) {
                completeChecks();
                return;
            }
            long now = System.currentTimeMillis();
            long interval = force ? MANUAL_INTERVAL : lastSucceeded ? SUCCESS_INTERVAL : RETRY_INTERVAL;
            if (lastAttempt > 0 && now >= lastAttempt && now - lastAttempt < interval) {
                completeChecks();
                return;
            }
            checking = true;
            lastAttempt = now;
            long operation = ++generation;
            File previousVerified = verifiedFile;
            OxUpdateIo.Cancellation cancellation = new OxUpdateIo.Cancellation();
            activeCancellation = cancellation;
            publish(State.CHECKING, Error.NONE, 0);
            worker.execute(() -> {
                OxUpdateManifest release = null;
                Error error = Error.NONE;
                long actualInstalled = installedVersion;
                try {
                    actualInstalled = OxUpdateVerifier.installedVersion(context);
                    String json = OxUpdateIo.fetchManifest(cancellation);
                    try {
                        release = OxUpdateManifest.parse(json, context.getPackageName());
                    } catch (Exception e) {
                        throw new OxUpdateIo.Failure(OxUpdateIo.Kind.INVALID_MANIFEST);
                    }
                    if (release.versionCode > actualInstalled) saveManifest(json);
                    else clearStoredRelease();
                } catch (Exception e) {
                    error = errorOf(e);
                }
                try {
                    boolean successfulFetch = error == Error.NONE;
                    if (!preferences().edit().putLong("last_attempt", now).putBoolean("last_succeeded", successfulFetch)
                            .putString("last_error", error.name()).putLong("checked_version", actualInstalled)
                            .putLong("available_version", successfulFetch && release != null ? release.versionCode : 0).commit()) {
                        error = Error.STORAGE;
                    }
                    if (error == Error.NONE && release != null && release.versionCode > actualInstalled) pruneApks(release);
                } catch (Exception e) {
                    error = Error.STORAGE;
                }
                boolean previousFilePresent = false;
                try {
                    previousFilePresent = previousVerified != null && previousVerified.isFile();
                } catch (Exception ignored) {
                    error = Error.STORAGE;
                }
                boolean success = error == Error.NONE;
                boolean keepPrevious = previousFilePresent;
                OxUpdateManifest result = release;
                Error resultError = error;
                long actualVersion = actualInstalled;
                main.post(() -> {
                    if (operation != generation) return;
                    installedVersion = actualVersion;
                    checking = false;
                    activeCancellation = null;
                    lastSucceeded = success;
                    if (success) {
                        if (result.versionCode <= installedVersion) {
                            manifest = null;
                            verifiedFile = null;
                            publish(State.IDLE, Error.NO_UPDATE, 0);
                        } else {
                            if (manifest == null || !sameRelease(manifest, result)) verifiedFile = null;
                            manifest = result;
                            publish(verifiedFile == null ? State.AVAILABLE : State.READY, Error.NONE,
                                    verifiedFile == null ? 0 : manifest.size);
                        }
                    } else {
                        // Keep a previously verified update usable when a later network check fails.
                        if (!keepPrevious) verifiedFile = null;
                        publish(verifiedFile != null ? State.READY : State.ERROR, resultError,
                                verifiedFile == null || manifest == null ? 0 : manifest.size);
                    }
                    completeChecks();
                });
            });
        });
    }

    /** Explicit user action only. Checks never start an APK download. */
    public void download() {
        onMain(() -> {
            if (initializing || checking || manifest == null || current.state == State.DOWNLOADING
                    || current.state == State.VERIFYING || current.state == State.READY) return;
            OxUpdateManifest release = manifest;
            long operation = ++generation;
            OxUpdateIo.Cancellation cancellation = new OxUpdateIo.Cancellation();
            activeCancellation = cancellation;
            verifiedFile = null;
            publish(State.DOWNLOADING, Error.NONE, 0);
            worker.execute(() -> {
                File target = apkFile(release);
                File partial = new File(directory(), target.getName() + ".part");
                Error error = Error.NONE;
                try {
                    ensureDirectory();
                    if (directory().getUsableSpace() < release.size + 8L * 1024 * 1024) {
                        throw new OxUpdateIo.Failure(OxUpdateIo.Kind.STORAGE);
                    }
                    OxUpdateIo.download(release, partial, cancellation, bytes -> main.post(() -> {
                        if (operation == generation && !cancellation.canceled) publish(State.DOWNLOADING, Error.NONE, bytes);
                    }));
                    main.post(() -> {
                        if (operation == generation && !cancellation.canceled) publish(State.VERIFYING, Error.NONE, release.size);
                    });
                    OxUpdateVerifier.verify(context, partial, release, cancellation);
                    cancellation.check();
                    if (target.exists() && !target.delete()) throw new OxUpdateIo.Failure(OxUpdateIo.Kind.STORAGE);
                    if (!partial.renameTo(target)) throw new OxUpdateIo.Failure(OxUpdateIo.Kind.STORAGE);
                    cancellation.check();
                } catch (Exception e) {
                    error = errorOf(e);
                    partial.delete();
                    target.delete();
                }
                Error resultError = error;
                main.post(() -> {
                    if (operation != generation) return;
                    activeCancellation = null;
                    if (resultError == Error.NONE) {
                        verifiedFile = target;
                        publish(State.READY, Error.NONE, release.size);
                    } else {
                        verifiedFile = null;
                        publish(resultError == Error.CANCELED ? State.AVAILABLE : State.ERROR, resultError, 0);
                    }
                });
            });
        });
    }

    public void cancelDownload() {
        onMain(() -> {
            if (current.state != State.DOWNLOADING && current.state != State.VERIFYING) return;
            if (activeCancellation != null) activeCancellation.cancel();
            activeCancellation = null;
            ++generation;
            verifiedFile = null;
            publish(manifest == null ? State.IDLE : State.AVAILABLE, Error.CANCELED, 0);
        });
    }

    /** Rechecks bytes, package, installed version and signers immediately before an installer intent. */
    public void verifyForInstall(InstallCallback callback) {
        if (callback == null) return;
        onMain(() -> {
            if (initializing || checking || current.state != State.READY || verifiedFile == null || manifest == null) {
                callback.onResult(null, Error.NO_UPDATE);
                return;
            }
            File file = verifiedFile;
            OxUpdateManifest release = manifest;
            long operation = ++generation;
            OxUpdateIo.Cancellation cancellation = new OxUpdateIo.Cancellation();
            activeCancellation = cancellation;
            publish(State.VERIFYING, Error.NONE, release.size);
            worker.execute(() -> {
                Error error = Error.NONE;
                try {
                    OxUpdateVerifier.verify(context, file, release, cancellation);
                } catch (Exception e) {
                    error = errorOf(e);
                    if (error != Error.CANCELED) file.delete();
                }
                Error resultError = error;
                main.post(() -> {
                    if (operation != generation) {
                        callback.onResult(null, Error.CANCELED);
                        return;
                    }
                    activeCancellation = null;
                    if (resultError != Error.NONE) verifiedFile = null;
                    publish(resultError == Error.NONE ? State.READY : State.ERROR, resultError,
                            resultError == Error.NONE ? release.size : 0);
                    callback.onResult(resultError == Error.NONE ? file : null, resultError);
                });
            });
        });
    }

    private void restore() {
        OxUpdateManifest release = null;
        File verified = null;
        long version = 0;
        long attempt = 0;
        boolean success = false;
        Error error = Error.NONE;
        try {
            attempt = preferences().getLong("last_attempt", 0);
            success = preferences().getBoolean("last_succeeded", false);
            version = OxUpdateVerifier.installedVersion(context);
            if (preferences().getLong("checked_version", 0) != version) {
                attempt = 0;
                success = false;
            }
            File folder = directory();
            File[] files = folder.listFiles();
            if (files != null) for (File file : files) if (file.getName().endsWith(".part")) file.delete();
            AtomicFile stored = metadataFile();
            if (stored.getBaseFile().exists() || new File(stored.getBaseFile() + ".bak").exists()) {
                String json;
                try (FileInputStream stream = stored.openRead()) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = stream.read(buffer)) != -1) {
                        if (bytes.size() + read > OxUpdateManifest.MAX_MANIFEST_BYTES) throw new IOException();
                        bytes.write(buffer, 0, read);
                    }
                    json = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                }
                release = OxUpdateManifest.parse(json, context.getPackageName());
                if (release.versionCode <= version) {
                    release = null;
                    clearStoredRelease();
                } else {
                    File apk = apkFile(release);
                    if (apk.exists()) {
                        try {
                            OxUpdateVerifier.verify(context, apk, release, new OxUpdateIo.Cancellation());
                            verified = apk;
                        } catch (Exception e) {
                            apk.delete();
                        }
                    }
                }
            }
            if (release == null && preferences().getLong("available_version", 0) > version) {
                // Android may clear cache files. A cached network timestamp cannot hide an available release.
                attempt = 0;
                success = false;
            }
            if (release == null && attempt > 0) {
                if (success) error = Error.NO_UPDATE;
                else {
                    try {
                        error = Error.valueOf(preferences().getString("last_error", Error.NETWORK.name()));
                    } catch (Exception ignored) {
                        error = Error.NETWORK;
                    }
                    if (error == Error.NONE) error = Error.NETWORK;
                }
            }
            // Only retain the active release. Interrupted/previous installations cannot fill the cache.
            if (files != null) {
                File active = release == null ? null : apkFile(release);
                for (File file : files) if (file.getName().endsWith(".apk") && !file.equals(active)) file.delete();
            }
        } catch (Exception e) {
            attempt = 0;
            success = false;
            release = null;
            verified = null;
            error = Error.STORAGE;
            try {
                metadataFile().delete();
            } catch (Exception ignored) {
                // Still publish completion if Android refuses cache access.
            }
        }
        OxUpdateManifest result = release;
        File resultFile = verified;
        long installed = version;
        long lastCheck = attempt;
        boolean checkSucceeded = success;
        Error resultError = error;
        main.post(() -> {
            manifest = result;
            verifiedFile = resultFile;
            installedVersion = installed;
            lastAttempt = lastCheck;
            lastSucceeded = checkSucceeded;
            initializing = false;
            publish(resultError != Error.NONE && resultError != Error.NO_UPDATE ? State.ERROR : resultFile != null ? State.READY
                    : result != null ? State.AVAILABLE : State.IDLE, resultError, resultFile == null ? 0 : result.size);
            if (initialCheckRequested) {
                boolean force = initialCheckForced;
                initialCheckRequested = false;
                initialCheckForced = false;
                check(force, null);
            } else {
                completeChecks();
            }
        });
    }

    private static boolean sameRelease(OxUpdateManifest left, OxUpdateManifest right) {
        return left.versionCode == right.versionCode && left.size == right.size && left.sha256.equals(right.sha256);
    }

    private void publish(State state, Error error, long bytes) {
        current = new Snapshot(state, error, manifest, bytes, verifiedFile);
        for (int i = listeners.size() - 1; i >= 0; i--) if (listeners.get(i).get() == null) listeners.remove(i);
        for (WeakReference<Listener> reference : new ArrayList<>(listeners)) {
            Listener listener = reference.get();
            if (listener != null) listener.onChanged(current);
        }
    }

    private void completeChecks() {
        ArrayList<Runnable> callbacks = new ArrayList<>(checkCallbacks);
        checkCallbacks.clear();
        for (Runnable callback : callbacks) callback.run();
    }

    private void onMain(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) runnable.run(); else main.post(runnable);
    }

    private File directory() { return new File(context.getCacheDir(), "ox-updates"); }
    private File apkFile(OxUpdateManifest release) {
        return new File(directory(), "update-" + release.versionCode + "-" + release.sha256 + ".apk");
    }
    private AtomicFile metadataFile() { return new AtomicFile(new File(directory(), "release.json")); }
    private SharedPreferences preferences() { return context.getSharedPreferences("ox_app_updater", Context.MODE_PRIVATE); }

    private void ensureDirectory() throws OxUpdateIo.Failure {
        File folder = directory();
        if (!folder.isDirectory() && !folder.mkdirs()) throw new OxUpdateIo.Failure(OxUpdateIo.Kind.STORAGE);
    }

    private void saveManifest(String json) throws IOException {
        ensureDirectory();
        AtomicFile atomic = metadataFile();
        FileOutputStream stream = null;
        try {
            stream = atomic.startWrite();
            stream.write(json.getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(stream);
        } catch (IOException e) {
            if (stream != null) atomic.failWrite(stream);
            throw new OxUpdateIo.Failure(OxUpdateIo.Kind.STORAGE);
        }
    }

    private void clearStoredRelease() {
        metadataFile().delete();
        File[] files = directory().listFiles();
        if (files != null) for (File file : files) if (file.getName().endsWith(".apk") || file.getName().endsWith(".part")) file.delete();
    }

    private void pruneApks(OxUpdateManifest activeRelease) {
        File active = apkFile(activeRelease);
        File[] files = directory().listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().startsWith("update-") && file.getName().endsWith(".apk") && !file.equals(active)) file.delete();
            }
        }
    }

    private static Error errorOf(Exception exception) {
        if (exception instanceof OxUpdateIo.Failure) {
            return Error.valueOf(((OxUpdateIo.Failure) exception).kind.name());
        }
        if (exception instanceof IOException) return Error.NETWORK;
        return Error.INVALID_APK;
    }
}
