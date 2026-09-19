package org.telegram.messenger.ayu.duplicates;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * Small self-contained streaming hasher used by the duplicate cleaner.
 * <p>
 * Intentionally does not depend on any other helper in the project so this feature can be dropped
 * in or removed without touching anything else.
 */
public class DuplicateHasher {

    public static final int BUFFER_SIZE = 128 * 1024;
    /** files above this size get a cheap head+tail signature before the full hash */
    public static final long QUICK_SIGNATURE_THRESHOLD = 4L * 1024 * 1024;
    /** how many bytes are read from the head and from the tail for the quick signature */
    public static final int QUICK_SIGNATURE_CHUNK = 96 * 1024;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** lets the caller report progress and abort a long hash */
    public interface Listener {
        /** called after every chunk that was read from disk */
        void onBytesHashed(long bytes);

        /** return true to abort hashing as soon as possible */
        boolean isCancelled();
    }

    private DuplicateHasher() {
    }

    public static byte[] allocateBuffer() {
        return new byte[BUFFER_SIZE];
    }

    /**
     * Full streaming SHA-256 of the file contents.
     *
     * @return lowercase hex digest, or null when the file could not be read or the listener cancelled
     */
    public static String hashFile(File file, byte[] buffer, Listener listener) {
        if (file == null || !file.exists()) {
            return null;
        }
        if (buffer == null) {
            buffer = allocateBuffer();
        }
        InputStream stream = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            stream = new FileInputStream(file);
            int read;
            while ((read = stream.read(buffer)) > 0) {
                if (listener != null && listener.isCancelled()) {
                    return null;
                }
                digest.update(buffer, 0, read);
                if (listener != null) {
                    listener.onBytesHashed(read);
                }
            }
            return toHex(digest.digest());
        } catch (Throwable e) {
            return null;
        } finally {
            closeQuietly(stream);
        }
    }

    /**
     * Cheap signature over the first and last {@link #QUICK_SIGNATURE_CHUNK} bytes plus the length.
     * Two files with different quick signatures can never be identical, so this is used to avoid
     * reading whole multi-gigabyte videos that only happen to share a size.
     *
     * @return lowercase hex digest, or null on error / cancel
     */
    public static String quickSignature(File file, byte[] buffer, Listener listener) {
        if (file == null || !file.exists()) {
            return null;
        }
        final long length = file.length();
        if (length <= QUICK_SIGNATURE_THRESHOLD) {
            // small enough that the full hash is cheaper than two seeks
            return hashFile(file, buffer, listener);
        }
        if (buffer == null) {
            buffer = allocateBuffer();
        }
        FileInputStream stream = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(longToBytes(length));
            stream = new FileInputStream(file);
            if (!readInto(stream, digest, buffer, QUICK_SIGNATURE_CHUNK, listener)) {
                return null;
            }
            long skipTo = length - QUICK_SIGNATURE_CHUNK;
            long skipped = 0;
            while (skipped < skipTo - QUICK_SIGNATURE_CHUNK) {
                long n = stream.skip(skipTo - QUICK_SIGNATURE_CHUNK - skipped);
                if (n <= 0) {
                    break;
                }
                skipped += n;
            }
            if (!readInto(stream, digest, buffer, QUICK_SIGNATURE_CHUNK, listener)) {
                return null;
            }
            return "q" + toHex(digest.digest());
        } catch (Throwable e) {
            return null;
        } finally {
            closeQuietly(stream);
        }
    }

    private static boolean readInto(InputStream stream, MessageDigest digest, byte[] buffer, int wanted, Listener listener) throws Exception {
        int left = wanted;
        while (left > 0) {
            if (listener != null && listener.isCancelled()) {
                return false;
            }
            int read = stream.read(buffer, 0, Math.min(buffer.length, left));
            if (read <= 0) {
                break;
            }
            digest.update(buffer, 0, read);
            left -= read;
            if (listener != null) {
                listener.onBytesHashed(read);
            }
        }
        return true;
    }

    private static byte[] longToBytes(long value) {
        byte[] out = new byte[8];
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) (value >>> (8 * i));
        }
        return out;
    }

    public static String toHex(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    private static void closeQuietly(InputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (Throwable ignore) {
            }
        }
    }
}
