package org.telegram.messenger.ayu.update;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.net.URI;
import java.util.Locale;

/** Bounded release metadata. APK identity and signing certificates are checked separately. */
public final class OxUpdateManifest {
    public static final String FEED_URL = "https://github.com/OcoderX/yangi-telegram/releases/latest/download/ox-gram-update.json";
    public static final int MAX_MANIFEST_BYTES = 512 * 1024;
    public static final long MAX_APK_BYTES = 250L * 1024 * 1024;
    static final String RELEASE_PREFIX = "/OcoderX/yangi-telegram/releases/download/";

    public final String packageName;
    public final String version;
    public final int versionCode;
    public final String apkUrl;
    public final long size;
    public final String sha256;
    public final String changelog;

    private OxUpdateManifest(String packageName, String version, int versionCode, String apkUrl,
                             long size, String sha256, String changelog) {
        this.packageName = packageName;
        this.version = version;
        this.versionCode = versionCode;
        this.apkUrl = apkUrl;
        this.size = size;
        this.sha256 = sha256;
        this.changelog = changelog;
    }

    public static OxUpdateManifest parse(String json, String installedPackage) throws Exception {
        if (json == null || json.length() > MAX_MANIFEST_BYTES || json.indexOf('\0') >= 0) throw new IllegalArgumentException();
        JSONTokener tokener = new JSONTokener(json);
        Object root = tokener.nextValue();
        if (!(root instanceof JSONObject) || tokener.nextClean() != 0) throw new IllegalArgumentException();
        JSONObject value = (JSONObject) root;
        if (integer(value, "schema") != 1) throw new IllegalArgumentException();
        String packageName = string(value, "package_name", 160, false);
        String version = string(value, "version", 80, false);
        long versionCode = integer(value, "version_code");
        long size = integer(value, "size");
        String url = string(value, "apk_url", 2048, false);
        String hash = string(value, "sha256", 64, false).toLowerCase(Locale.US);
        String changelog = string(value, "changelog", 16000, true);
        if (!packageName.equals(installedPackage) || versionCode <= 0 || versionCode > Integer.MAX_VALUE
                || size <= 0 || size > MAX_APK_BYTES || !hash.matches("[0-9a-f]{64}")
                || !isReleaseUrl(url, ".apk") || !releaseTag(url).equals(version)) throw new IllegalArgumentException();
        return new OxUpdateManifest(packageName, version, (int) versionCode, url, size, hash, changelog);
    }

    private static long integer(JSONObject object, String key) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException();
        return ((Number) value).longValue();
    }

    private static String string(JSONObject object, String key, int max, boolean multiline) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof String)) throw new IllegalArgumentException();
        String text = (String) value;
        if (text.length() > max || (!multiline && text.trim().isEmpty())) throw new IllegalArgumentException();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c < 32 || c == 127) && !(multiline && (c == '\n' || c == '\r' || c == '\t'))) {
                throw new IllegalArgumentException();
            }
        }
        return text;
    }

    public static boolean isReleaseUrl(String url, String suffix) {
        try {
            URI uri = secureUri(url);
            if (!"github.com".equalsIgnoreCase(uri.getHost()) || uri.getRawQuery() != null) return false;
            String path = uri.getRawPath();
            if (path == null || !path.startsWith(RELEASE_PREFIX)) return false;
            String[] pieces = path.substring(RELEASE_PREFIX.length()).split("/", -1);
            return pieces.length == 2 && pieces[0].matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                    && pieces[1].matches("[A-Za-z0-9][A-Za-z0-9._-]{0,180}")
                    && pieces[1].endsWith(suffix);
        } catch (Exception e) {
            return false;
        }
    }

    /** Redirects may reach GitHub's signed, short-lived release asset URLs. */
    public static boolean isTrustedTransportUrl(String url) {
        try {
            URI uri = secureUri(url);
            String host = uri.getHost().toLowerCase(Locale.US);
            if (isAssetHost(host)) return true;
            return "github.com".equals(host) && (FEED_URL.equals(url)
                    || isReleaseUrl(url, ".apk") || isReleaseUrl(url, ".json"));
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isAssetHost(String host) {
        return "release-assets.githubusercontent.com".equals(host)
                || "objects.githubusercontent.com".equals(host)
                || "github-releases.githubusercontent.com".equals(host);
    }

    static String releaseTag(String url) {
        return URI.create(url).getRawPath().substring(RELEASE_PREFIX.length()).split("/", -1)[0];
    }

    static String releaseAssetName(String url) {
        return URI.create(url).getRawPath().substring(RELEASE_PREFIX.length()).split("/", -1)[1];
    }

    static URI secureUri(String url) throws Exception {
        URI uri = new URI(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) throw new IllegalArgumentException();
        return uri;
    }
}
