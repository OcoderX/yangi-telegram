package org.telegram.messenger.ayu.update;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import org.json.JSONObject;

/** All methods run on the updater worker. No credentials, cookies or authorization are sent. */
final class OxUpdateIo {
    enum Kind { NETWORK, INVALID_APK, STORAGE, CANCELED, INVALID_MANIFEST }

    static final class Failure extends IOException {
        final Kind kind;
        Failure(Kind kind) { this.kind = kind; }
    }

    static final class Cancellation {
        volatile boolean canceled;
        volatile HttpURLConnection connection;
        void cancel() {
            canceled = true;
            // Disconnect can block on some Android TLS implementations; the read timeout is bounded.
        }
        void check() throws Failure {
            if (canceled || Thread.currentThread().isInterrupted()) throw new Failure(Kind.CANCELED);
        }
    }

    interface Progress { void onProgress(long bytes); }

    // The existing web route costs no API quota. Try it once, then the public API once.
    // All redirect and fallback work shares an operation deadline; reads stay cancelable.
    private static final int SOCKET_TIMEOUT_MS = 6000;
    private static final int APK_READ_TIMEOUT_MS = 20000;
    private static volatile long apiRetryAfterNanos;

    private static long deadline(long millis) { return System.nanoTime() + millis * 1_000_000L; }

    private static int remaining(long deadline, Cancellation cancellation) throws Failure {
        return remaining(deadline, cancellation, SOCKET_TIMEOUT_MS);
    }

    private static int remaining(long deadline, Cancellation cancellation, int socketLimit) throws Failure {
        cancellation.check();
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new Failure(Kind.NETWORK);
        return (int) Math.max(1, Math.min(socketLimit, nanos / 1_000_000L));
    }

    private static boolean canFallback(IOException error, Cancellation cancellation) throws Failure {
        cancellation.check();
        return !(error instanceof Failure) || ((Failure) error).kind == Kind.NETWORK;
    }

    static String fetchManifest(Cancellation cancellation) throws IOException {
        long end = deadline(45_000);
        try {
            return new String(readBytes(OxUpdateManifest.FEED_URL, false, OxUpdateManifest.MAX_MANIFEST_BYTES,
                    cancellation, Math.min(end, deadline(12_000))), StandardCharsets.UTF_8);
        } catch (IOException error) {
            if (!canFallback(error, cancellation)) throw error;
        }
        OxGithubRelease release = fetchRelease(null, cancellation, end);
        OxGithubRelease.Asset asset = release.manifestAsset();
        byte[] bytes = readBytes(asset.url, true, OxUpdateManifest.MAX_MANIFEST_BYTES, cancellation, end);
        if (bytes.length != asset.size) throw new Failure(Kind.INVALID_MANIFEST);
        try {
            if (asset.hash != null && !hex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(asset.hash)) {
                throw new Failure(Kind.INVALID_MANIFEST);
            }
            String json = new String(bytes, StandardCharsets.UTF_8);
            // The updater checks the actual installed package again before publishing/caching this result.
            Object packageName = new JSONObject(json).get("package_name");
            if (!(packageName instanceof String)) throw new Failure(Kind.INVALID_MANIFEST);
            release.apkAsset(OxUpdateManifest.parse(json, (String) packageName));
            remaining(end, cancellation);
            return json;
        } catch (Failure error) {
            throw error;
        } catch (Exception error) {
            throw new Failure(Kind.INVALID_MANIFEST);
        }
    }

    private static OxGithubRelease fetchRelease(String tag, Cancellation cancellation, long end) throws IOException {
        String url = OxGithubRelease.API + (tag == null ? "latest" : "tags/" + tag);
        byte[] bytes = readBytes(url, false, OxUpdateManifest.MAX_MANIFEST_BYTES, cancellation,
                Math.min(end, deadline(15_000)));
        return OxGithubRelease.parse(new String(bytes, StandardCharsets.UTF_8), tag);
    }

    private static byte[] readBytes(String url, boolean binary, int limit, Cancellation cancellation, long end) throws IOException {
        HttpURLConnection connection = open(url, cancellation, binary, end, Kind.INVALID_MANIFEST);
        try (InputStream stream = connection.getInputStream()) {
            long length = contentLength(connection);
            if (length > limit) throw new Failure(Kind.INVALID_MANIFEST);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (true) {
                connection.setReadTimeout(remaining(end, cancellation));
                int count = stream.read(buffer);
                remaining(end, cancellation);
                if (count == -1) break;
                if (output.size() + count > limit) throw new Failure(Kind.INVALID_MANIFEST);
                output.write(buffer, 0, count);
            }
            if (length != -1 && length != output.size()) throw new Failure(Kind.NETWORK);
            return output.toByteArray();
        } finally {
            cancellation.connection = null;
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String initialUrl, Cancellation cancellation, boolean binary, long end,
                                           Kind invalidContent) throws IOException {
        String address = initialUrl;
        boolean api = OxGithubRelease.isApiUrl(initialUrl);
        boolean metadata = api && !initialUrl.startsWith(OxGithubRelease.API + "assets/");
        for (int redirects = 0; redirects <= 5; redirects++) {
            remaining(end, cancellation);
            // API metadata never redirects. Binary API responses may only leave for pinned asset hosts.
            boolean allowed = address.equals(initialUrl) && (api || OxUpdateManifest.isTrustedTransportUrl(address));
            if (!allowed && !metadata) {
                try {
                    URI uri = OxUpdateManifest.secureUri(address);
                    allowed = OxUpdateManifest.isAssetHost(uri.getHost().toLowerCase(Locale.US))
                            || (!api && OxUpdateManifest.FEED_URL.equals(initialUrl)
                            && OxUpdateManifest.isReleaseUrl(address, ".json")
                            && OxGithubRelease.MANIFEST_NAME.equals(OxUpdateManifest.releaseAssetName(address)));
                } catch (Exception ignored) { }
            }
            if (!allowed) throw new Failure(Kind.INVALID_MANIFEST);
            if (OxGithubRelease.isApiUrl(address) && apiRetryAfterNanos != 0
                    && System.nanoTime() < apiRetryAfterNanos) throw new Failure(Kind.NETWORK);
            HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
            cancellation.connection = connection;
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(remaining(end, cancellation));
                connection.setReadTimeout(remaining(end, cancellation));
                connection.setUseCaches(false);
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("Accept", binary ? "application/octet-stream" : "application/json");
                connection.setRequestProperty("User-Agent", "Ox-gram-Updater/1");
                connection.connect();
                connection.setReadTimeout(remaining(end, cancellation));
                int status = connection.getResponseCode();
                remaining(end, cancellation);
                if (OxGithubRelease.isApiUrl(address) && (status == 403 || status == 429)) {
                    // No immediate retry on a shared IP's exhausted quota or secondary rate limit.
                    long seconds = 3600;
                    try {
                        String retry = connection.getHeaderField("Retry-After");
                        String reset = connection.getHeaderField("X-RateLimit-Reset");
                        if (retry != null) seconds = Long.parseLong(retry);
                        else if (reset != null) seconds = Long.parseLong(reset) - System.currentTimeMillis() / 1000;
                    } catch (NumberFormatException ignored) { }
                    apiRetryAfterNanos = deadline(Math.max(60, Math.min(86400, seconds)) * 1000);
                }
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (redirects == 5 || location == null) throw new Failure(Kind.NETWORK);
                    try { address = URI.create(address).resolve(location).toString(); }
                    catch (IllegalArgumentException error) { throw new Failure(Kind.INVALID_MANIFEST); }
                    connection.disconnect();
                    cancellation.connection = null;
                    continue;
                }
                if (status != HttpURLConnection.HTTP_OK) throw new Failure(Kind.NETWORK);
                String encoding = connection.getContentEncoding();
                String type = connection.getContentType();
                if ((encoding != null && !"identity".equalsIgnoreCase(encoding))
                        || (type != null && type.toLowerCase(Locale.US).contains("text/html"))) {
                    throw new Failure(invalidContent);
                }
                return connection;
            } catch (IOException | RuntimeException e) {
                connection.disconnect();
                cancellation.connection = null;
                throw e;
            }
        }
        throw new Failure(Kind.NETWORK);
    }

    static void download(OxUpdateManifest manifest, File target, Cancellation cancellation, Progress progress) throws Exception {
        long end = deadline(900_000);
        boolean success = false;
        try {
            try {
                downloadFrom(manifest.apkUrl, manifest, target, cancellation, progress, end);
            } catch (IOException error) {
                if (!canFallback(error, cancellation)) throw error;
                deletePartial(target);
                progress.onProgress(0);
                OxGithubRelease release = fetchRelease(manifest.version, cancellation, end);
                downloadFrom(release.apkAsset(manifest).url, manifest, target, cancellation, progress, end);
            }
            remaining(end, cancellation);
            success = true;
        } finally {
            if (!success) deletePartial(target);
        }
    }

    private static void deletePartial(File file) throws Failure {
        if (file.exists() && !file.delete()) throw new Failure(Kind.STORAGE);
    }

    private static void downloadFrom(String url, OxUpdateManifest manifest, File target,
                                     Cancellation cancellation, Progress progress, long end) throws Exception {
        HttpURLConnection connection = open(url, cancellation, true, Math.min(end, deadline(15_000)), Kind.INVALID_APK);
        try (InputStream stream = connection.getInputStream()) {
            long length = contentLength(connection);
            if (length != -1 && length != manifest.size) throw new Failure(Kind.INVALID_APK);
            FileOutputStream output;
            try {
                output = new FileOutputStream(target);
            } catch (IOException e) {
                throw new Failure(Kind.STORAGE);
            }
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                copyVerified(stream, output, manifest.size, manifest.sha256, digest, cancellation, progress, end, connection);
                try {
                    output.getFD().sync();
                } catch (IOException e) {
                    throw new Failure(Kind.STORAGE);
                }
            } finally {
                try { output.close(); }
                catch (IOException error) { throw new Failure(Kind.STORAGE); }
            }
        } finally {
            cancellation.connection = null;
            connection.disconnect();
        }
    }

    private static long contentLength(HttpURLConnection connection) throws Failure {
        String value = connection.getHeaderField("Content-Length");
        if (value == null) return -1;
        try {
            long length = Long.parseLong(value);
            if (length < 0) throw new NumberFormatException();
            return length;
        } catch (NumberFormatException e) {
            throw new Failure(Kind.NETWORK);
        }
    }

    static void copyVerified(InputStream input, FileOutputStream output, long size, String expectedHash,
                             MessageDigest digest, Cancellation cancellation, Progress progress) throws IOException {
        copyVerified(input, output, size, expectedHash, digest, cancellation, progress, deadline(900_000), null);
    }

    private static void copyVerified(InputStream input, FileOutputStream output, long size, String expectedHash,
                             MessageDigest digest, Cancellation cancellation, Progress progress, long end,
                             HttpURLConnection connection) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long count = 0;
        long lastProgress = 0;
        while (true) {
            int timeout = remaining(end, cancellation, APK_READ_TIMEOUT_MS);
            if (connection != null) connection.setReadTimeout(timeout);
            int read = input.read(buffer);
            remaining(end, cancellation);
            if (read == -1) break;
            if (read == 0) continue;
            count += read;
            if (count > size || count > OxUpdateManifest.MAX_APK_BYTES) throw new Failure(Kind.INVALID_APK);
            try {
                output.write(buffer, 0, read);
            } catch (IOException e) {
                throw new Failure(Kind.STORAGE);
            }
            digest.update(buffer, 0, read);
            long now = System.nanoTime();
            if (now - lastProgress >= 200_000_000L || count == size) {
                lastProgress = now;
                progress.onProgress(count);
            }
        }
        cancellation.check();
        if (count != size || !hex(digest.digest()).equals(expectedHash)) throw new Failure(Kind.INVALID_APK);
    }

    static void verifyHash(File file, OxUpdateManifest manifest, Cancellation cancellation) throws Exception {
        if (!file.isFile() || file.length() != manifest.size) throw new Failure(Kind.INVALID_APK);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long count = 0;
        try (FileInputStream stream = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                cancellation.check();
                count += read;
                if (count > manifest.size) throw new Failure(Kind.INVALID_APK);
                digest.update(buffer, 0, read);
            }
        }
        cancellation.check();
        if (count != manifest.size || !hex(digest.digest()).equals(manifest.sha256)) throw new Failure(Kind.INVALID_APK);
    }

    private static String hex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            chars[i * 2] = digits[(bytes[i] >>> 4) & 15];
            chars[i * 2 + 1] = digits[bytes[i] & 15];
        }
        return new String(chars);
    }
}
