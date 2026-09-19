package org.telegram.messenger.ayu.firewall;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads the central directory of an already downloaded zip-like archive and remembers whether it
 * carries executable entries. Never touches the UI thread: {@link #getCachedResult} is a plain map
 * lookup, the actual {@link ZipFile} work runs on {@link Utilities#globalQueue}.
 */
public class FirewallArchiveInspector {

    public static final int RESULT_UNKNOWN = 0;
    public static final int RESULT_CLEAN = 1;
    public static final int RESULT_EXECUTABLE = 2;
    public static final int RESULT_UNREADABLE = 3;

    /** stop after this many entries, a zip bomb central directory can be huge */
    private static final int MAX_ENTRIES = 8000;

    private static final LongSparseArray<Integer> results = new LongSparseArray<>();
    private static final LongSparseArray<String> matchedEntries = new LongSparseArray<>();
    private static final LongSparseArray<Boolean> inFlight = new LongSparseArray<>();

    private FirewallArchiveInspector() {
    }

    private static long keyOf(TLRPC.Document document) {
        return document == null ? 0 : document.id;
    }

    public static synchronized int getCachedResult(int currentAccount, TLRPC.Message message, TLRPC.Document document) {
        Integer v = results.get(keyOf(document));
        return v == null ? RESULT_UNKNOWN : v;
    }

    public static synchronized String getCachedEntryName(int currentAccount, TLRPC.Message message, TLRPC.Document document) {
        String v = matchedEntries.get(keyOf(document));
        return v == null ? "" : v;
    }

    private static synchronized boolean claim(long key) {
        if (results.get(key) != null || inFlight.get(key) != null) {
            return false;
        }
        inFlight.put(key, Boolean.TRUE);
        return true;
    }

    private static synchronized void publish(long key, int result, String entryName) {
        inFlight.remove(key);
        if (result == RESULT_UNKNOWN) {
            // the file was not on disk yet: forget the attempt so it is retried after downloading
            return;
        }
        results.put(key, result);
        if (entryName != null) {
            matchedEntries.put(key, entryName);
        }
    }

    public static synchronized void clearCache() {
        results.clear();
        matchedEntries.clear();
        inFlight.clear();
    }

    /**
     * Inspects the archive on a background queue if it is on disk and has not been inspected yet,
     * then calls {@code onDone} on the UI thread. Returns true when a background pass was started,
     * false when the answer is already cached (or the file is not a zip / not downloaded).
     */
    public static boolean inspectAsync(final int currentAccount, final TLRPC.Message message, final TLRPC.Document document, final Runnable onDone) {
        if (message == null || document == null) {
            return false;
        }
        final String fileName = Firewall.fileNameOf(document);
        if (!FirewallRules.isZipLike(fileName)) {
            return false;
        }
        final long key = keyOf(document);
        if (key == 0 || !claim(key)) {
            return false;
        }
        Utilities.globalQueue.postRunnable(() -> {
            int result = RESULT_UNREADABLE;
            String matched = null;
            ZipFile zip = null;
            try {
                File file = resolveFile(currentAccount, message);
                if (file == null || !file.exists() || file.length() <= 0) {
                    result = RESULT_UNKNOWN;
                } else {
                    zip = new ZipFile(file);
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    int seen = 0;
                    result = RESULT_CLEAN;
                    while (entries.hasMoreElements() && seen < MAX_ENTRIES) {
                        seen++;
                        ZipEntry entry = entries.nextElement();
                        if (entry == null || entry.isDirectory()) {
                            continue;
                        }
                        String name = entry.getName();
                        if (FirewallRules.hasExecutableExtension(name)) {
                            result = RESULT_EXECUTABLE;
                            matched = name;
                            break;
                        }
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e, false);
                result = RESULT_UNREADABLE;
            } finally {
                if (zip != null) {
                    try {
                        zip.close();
                    } catch (Exception ignore) {
                    }
                }
            }
            final int finalResult = result;
            final String finalMatched = matched;
            publish(key, finalResult, finalMatched);
            if (onDone != null) {
                AndroidUtilities.runOnUIThread(onDone);
            }
        });
        return true;
    }

    private static File resolveFile(int currentAccount, TLRPC.Message message) {
        try {
            if (message.attachPath != null && message.attachPath.length() != 0) {
                File f = new File(message.attachPath);
                if (f.exists()) {
                    return f;
                }
            }
            return FileLoader.getInstance(currentAccount).getPathToMessage(message);
        } catch (Exception e) {
            FileLog.e(e, false);
            return null;
        }
    }
}
