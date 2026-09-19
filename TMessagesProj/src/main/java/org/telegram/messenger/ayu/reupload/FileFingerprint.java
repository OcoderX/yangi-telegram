package org.telegram.messenger.ayu.reupload;

import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Streaming SHA-256 of a whole file, used as the content key of the Zero-Reupload index.
 * <p>
 * Never call this from the UI thread: every caller inside {@code SendMessagesHelper} already runs on
 * {@code Utilities.globalQueue} or on a dedicated sending thread, and the received-file indexer posts
 * to {@link ReuploadIndex#getQueue()}.
 * <p>
 * Results are memoised in a small LRU keyed by {@code path|length|lastModified} so that sending the
 * same file to several chats in a row hashes it only once.
 */
public final class FileFingerprint {

    private static final int BUFFER_SIZE = 256 * 1024;
    private static final int CACHE_SIZE = 64;

    private static final LinkedHashMap<String, String> cache = new LinkedHashMap<String, String>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private FileFingerprint() {
    }

    /**
     * @return lowercase hex SHA-256 of the file content, or null when the path is unusable.
     */
    public static String of(String path) {
        if (path == null || path.length() == 0) {
            return null;
        }
        try {
            return of(new File(path));
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * @return lowercase hex SHA-256 of the file content, or null when the file cannot be read.
     */
    public static String of(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return null;
        }
        final long length = file.length();
        if (length <= 0) {
            return null;
        }
        final String key = file.getAbsolutePath() + "|" + length + "|" + file.lastModified();
        synchronized (cache) {
            String cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
        }

        InputStream stream = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            stream = new FileInputStream(file);
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = stream.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            String hex = toHex(digest.digest());
            synchronized (cache) {
                cache.put(key, hex);
            }
            return hex;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /** drops every memoised hash; used after the index is cleared */
    public static void invalidateCache() {
        synchronized (cache) {
            cache.clear();
        }
    }

    private static String toHex(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        final char[] hexDigits = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};
        char[] out = new char[bytes.length * 2];
        for (int a = 0; a < bytes.length; a++) {
            int v = bytes[a] & 0xFF;
            out[a * 2] = hexDigits[v >>> 4];
            out[a * 2 + 1] = hexDigits[v & 0x0F];
        }
        return new String(out);
    }
}
