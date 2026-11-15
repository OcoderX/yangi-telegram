package org.telegram.messenger.ayu.update;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.net.URI;
import java.util.HashSet;
import java.util.Locale;

/** Public release metadata only; never accepts API endpoints supplied by a manifest. */
final class OxGithubRelease {
    static final String API = "https://api.github.com/repos/OcoderX/yangi-telegram/releases/";
    static final String WEB = "https://github.com/OcoderX/yangi-telegram/releases/";
    static final String MANIFEST_NAME = "ox-gram-update.json";
    final String tag;
    private final Asset[] assets;

    static final class Asset {
        final String name, url, browserUrl, hash;
        final long size;
        Asset(String name, long id, String browserUrl, long size, String hash) {
            this.name = name;
            this.url = API + "assets/" + id;
            this.browserUrl = browserUrl;
            this.size = size;
            this.hash = hash;
        }
    }

    private OxGithubRelease(String tag, Asset[] assets) {
        this.tag = tag;
        this.assets = assets;
    }

    static OxGithubRelease parse(String json, String expectedTag) throws OxUpdateIo.Failure {
        try {
            JSONTokener tokener = new JSONTokener(json);
            Object root = tokener.nextValue();
            if (!(root instanceof JSONObject) || tokener.nextClean() != 0) throw new IllegalArgumentException();
            JSONObject value = (JSONObject) root;
            String tag = text(value, "tag_name");
            long id = positiveInteger(value, "id");
            if (!tag.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                    || (expectedTag != null && !expectedTag.equals(tag))
                    || !Boolean.FALSE.equals(value.get("draft")) || !Boolean.FALSE.equals(value.get("prerelease"))
                    || !(API + id).equals(text(value, "url"))
                    || !(WEB + "tag/" + tag).equals(text(value, "html_url"))) throw new IllegalArgumentException();
            Object rawAssets = value.get("assets");
            if (!(rawAssets instanceof JSONArray)) throw new IllegalArgumentException();
            JSONArray list = (JSONArray) rawAssets;
            if (list.length() == 0 || list.length() > 100) throw new IllegalArgumentException();
            Asset[] assets = new Asset[list.length()];
            HashSet<String> names = new HashSet<>();
            HashSet<Long> ids = new HashSet<>();
            for (int i = 0; i < assets.length; i++) {
                JSONObject item = list.getJSONObject(i);
                String name = text(item, "name");
                long assetId = positiveInteger(item, "id");
                long size = positiveInteger(item, "size");
                String browser = text(item, "browser_download_url");
                if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,180}") || !names.add(name) || !ids.add(assetId)
                        || !"uploaded".equals(text(item, "state"))
                        || !(API + "assets/" + assetId).equals(text(item, "url"))
                        || !(WEB + "download/" + tag + "/" + name).equals(browser)) throw new IllegalArgumentException();
                String hash = null;
                if (item.has("digest") && !item.isNull("digest")) {
                    String digest = text(item, "digest").toLowerCase(Locale.US);
                    if (!digest.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException();
                    hash = digest.substring(7);
                }
                assets[i] = new Asset(name, assetId, browser, size, hash);
            }
            return new OxGithubRelease(tag, assets);
        } catch (Exception e) {
            throw new OxUpdateIo.Failure(OxUpdateIo.Kind.INVALID_MANIFEST);
        }
    }

    Asset manifestAsset() throws OxUpdateIo.Failure {
        Asset asset = find(MANIFEST_NAME);
        if (asset.size > OxUpdateManifest.MAX_MANIFEST_BYTES) throw invalid();
        return asset;
    }

    Asset apkAsset(OxUpdateManifest manifest) throws OxUpdateIo.Failure {
        Asset asset = find(OxUpdateManifest.releaseAssetName(manifest.apkUrl));
        if (!tag.equals(manifest.version) || !asset.browserUrl.equals(manifest.apkUrl)
                || asset.size != manifest.size || (asset.hash != null && !asset.hash.equals(manifest.sha256))) throw invalid();
        return asset;
    }

    private Asset find(String name) throws OxUpdateIo.Failure {
        for (Asset asset : assets) if (asset.name.equals(name)) return asset;
        throw invalid();
    }

    static boolean isApiUrl(String url) {
        try {
            URI uri = OxUpdateManifest.secureUri(url);
            if (!"api.github.com".equals(uri.getHost()) || uri.getRawQuery() != null || !url.startsWith(API)) return false;
            String route = url.substring(API.length());
            return "latest".equals(route) || route.matches("tags/[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                    || route.matches("assets/[1-9][0-9]{0,18}");
        } catch (Exception e) { return false; }
    }

    private static String text(JSONObject object, String key) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof String)) throw new IllegalArgumentException();
        return (String) value;
    }

    private static long positiveInteger(JSONObject object, String key) throws Exception {
        Object value = object.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException();
        long result = ((Number) value).longValue();
        if (result <= 0) throw new IllegalArgumentException();
        return result;
    }

    private static OxUpdateIo.Failure invalid() { return new OxUpdateIo.Failure(OxUpdateIo.Kind.INVALID_MANIFEST); }
}
