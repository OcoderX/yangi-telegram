"""Prepare, stage and publish verified Ox-gram update assets in the existing repository.

`prepare` is local only. `draft` uploads a draft; `publish` makes that exact
verified draft public. No source commit or push is performed. Git credentials
are read from the configured helper without interactive prompts or token files.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
CHECKS = ROOT / 'build' / 'in-app-update-apk'
REPO = 'OcoderX/yangi-telegram'
OWNER = 'OcoderX'
PACKAGE = 'org.telegram.messenger.beta'
MANIFEST_NAME = 'ox-gram-update.json'
CERTIFICATE = 'a08d7dc323ddf71ef3201944397e0d3cce7d40847263e11f328b68bbe19229ab'
ABIS = {'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'}


class GuardError(Exception):
    """Messages are fixed reason codes, never network or credential data."""


def require(condition, code):
    if not condition:
        raise GuardError(code)


def read_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def sha256(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8')


def write_once(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        require(path.read_bytes() == data, 'LOCAL_ARTIFACT_ALREADY_DIFFERS')
    else:
        with path.open('xb') as stream:
            stream.write(data)


def verified_apk(metadata_path):
    metadata_path = Path(metadata_path).resolve()
    metadata = read_json(metadata_path)
    verification = read_json(metadata_path.with_name('verification-result.json'))
    apk = Path(metadata['apk']).resolve()
    require(metadata.get('status') == verification.get('status') == 'passed', 'APK_NOT_VERIFIED')
    require(Path(verification['metadata']).resolve() == metadata_path, 'VERIFICATION_PATH_MISMATCH')
    require(metadata['package'] == PACKAGE, 'APK_PACKAGE_MISMATCH')
    version = metadata['version']
    require(re.fullmatch(r'\d+\.\d+\.\d+-ox\.\d+', version) is not None, 'INVALID_VERSION')
    require(type(metadata['versionCode']) is int and metadata['versionCode'] > 0, 'INVALID_VERSION_CODE')
    require(apk == ROOT / ('ox-gram-' + version + '.apk') and apk.is_file(), 'APK_PATH_MISMATCH')
    require(metadata.get('debuggable') is False
            and metadata.get('updateCertificateMatchesPrevious') is True
            and metadata.get('previousApksUnchanged') is True
            and metadata.get('previousMappingUnchanged') is True
            and metadata.get('zipAlignment16KBPassed') is True
            and metadata.get('archivedAssemblyStartInputDrift') == 0
            and metadata.get('currentWorkingTreeDriftCount') == 0
            and metadata.get('sourceFilesChecked', 0) > 0
            and metadata.get('packagedNativeLibrariesChecked') == 8
            and set(metadata.get('nativeAbis', [])) == ABIS, 'APK_CHECKS_INCOMPLETE')
    require(metadata['certificateSha256'].lower() == CERTIFICATE
            and metadata['previousCertificateSha256'].lower() == CERTIFICATE, 'CERTIFICATE_MISMATCH')
    require(len(metadata.get('nativeLibraries', [])) == 8
            and all(item.get('matches') is True for item in metadata['nativeLibraries']), 'NATIVE_CHECKS_INCOMPLETE')
    require(metadata['bytes'] == apk.stat().st_size
            and metadata['sha256'].lower() == verification['sha256'].lower() == sha256(apk), 'APK_HASH_OR_SIZE_CHANGED')
    require(sha256(metadata_path.parent / 'previous-apk-mapping.txt') == metadata['previousMappingSha256'].lower()
            and sha256(metadata_path.parent / 'apk-mapping.txt') == metadata['mappingSha256'].lower(), 'MAPPING_HASH_CHANGED')
    properties = (ROOT / 'gradle.properties').read_text(encoding='utf-8-sig')
    source_version = re.search(r'^APP_VERSION_NAME=(.+)$', properties, re.M)
    source_code = re.search(r'^APP_VERSION_CODE=(\d+)$', properties, re.M)
    require(source_version is not None and source_code is not None
            and source_version.group(1).strip() == version
            and int(source_code.group(1)) * 10 + 9 == metadata['versionCode'], 'CURRENT_SOURCE_VERSION_MISMATCH')
    return apk, metadata


def prepare(metadata_path, changelog_path):
    apk, metadata = verified_apk(metadata_path)
    changelog = Path(changelog_path).read_text(encoding='utf-8-sig').strip()
    require(0 < len(changelog.encode('utf-8')) <= 16000, 'CHANGELOG_MISSING_OR_TOO_LONG')
    tag = metadata['version']
    base = 'https://github.com/' + REPO + '/releases/download/' + tag + '/'
    manifest = {'schema': 1, 'package_name': PACKAGE, 'version': tag,
                'version_code': metadata['versionCode'], 'apk_url': base + apk.name,
                'size': metadata['bytes'], 'sha256': metadata['sha256'].lower(), 'changelog': changelog}
    manifest_path = Path(metadata_path).resolve().parent / MANIFEST_NAME
    write_once(manifest_path, json_bytes(manifest))
    return apk, metadata, manifest_path, manifest


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        result = super().redirect_request(request, fp, code, message, headers, new_url)
        require(urllib.parse.urlparse(new_url).scheme == 'https', 'INSECURE_REDIRECT')
        if result is not None and urllib.parse.urlparse(new_url).hostname != 'api.github.com':
            result.remove_header('Authorization')
        return result


class GitHub:
    def __init__(self):
        environment = dict(os.environ, GIT_TERMINAL_PROMPT='0', GCM_INTERACTIVE='Never')
        result = subprocess.run(['git', '-C', str(ROOT), 'credential', 'fill'],
                                input='protocol=https\nhost=github.com\npath=' + REPO + '.git\n\n',
                                capture_output=True, text=True, env=environment, timeout=35)
        values = dict(line.split('=', 1) for line in result.stdout.splitlines() if '=' in line)
        result = None
        self.token = values.pop('password', None)
        values.clear()
        require(bool(self.token), 'NO_STORED_GITHUB_CREDENTIAL')
        self.opener = urllib.request.build_opener(SafeRedirect())

    def close(self):
        self.token = None

    def api(self, path, method='GET', value=None, upload=None):
        url = path if path.startswith('https://') else 'https://api.github.com' + path
        parsed = urllib.parse.urlparse(url)
        require(parsed.scheme == 'https' and parsed.hostname in {'api.github.com', 'uploads.github.com'}, 'UNEXPECTED_API_HOST')
        headers = {'Accept': 'application/vnd.github+json', 'Authorization': 'Bearer ' + self.token,
                   'User-Agent': 'Ox-gram-release', 'X-GitHub-Api-Version': '2022-11-28'}
        stream = None
        if upload is not None:
            stream = Path(upload).open('rb')
            data = stream
            headers.update({'Content-Type': 'application/octet-stream', 'Content-Length': str(Path(upload).stat().st_size)})
        elif value is not None:
            data = json_bytes(value)
            headers['Content-Type'] = 'application/json'
        else:
            data = None
        try:
            request = urllib.request.Request(url, data=data, headers=headers, method=method)
            with self.opener.open(request, timeout=180) as response:
                return json.load(response)
        finally:
            if stream is not None:
                stream.close()
            headers.clear()

    def preflight(self):
        remote = subprocess.run(['git', '-C', str(ROOT), 'remote', 'get-url', 'myfork'],
                                capture_output=True, text=True, timeout=15)
        require(remote.returncode == 0 and remote.stdout.strip() == 'https://github.com/' + REPO + '.git', 'REMOTE_REPOSITORY_MISMATCH')
        user = self.api('/user')
        repo = self.api('/repos/' + REPO)
        require(user.get('login', '').lower() == OWNER.lower(), 'GITHUB_ACCOUNT_MISMATCH')
        require(repo.get('full_name') == REPO and repo.get('private') is False
                and repo.get('default_branch') == 'main' and repo.get('permissions', {}).get('push') is True,
                'GITHUB_REPOSITORY_OR_PERMISSION_MISMATCH')
        branch = self.api('/repos/' + REPO + '/branches/main')
        commit = branch['commit']['sha']
        require(re.fullmatch('[a-f0-9]{40}', commit) is not None, 'INVALID_REMOTE_COMMIT')
        return commit

    def release(self, tag):
        try:
            release = self.api('/repos/' + REPO + '/releases/tags/' + urllib.parse.quote(tag, safe=''))
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
            # The tag endpoint documents published releases. Authenticated lists
            # include drafts, so an exact retry must search them before creating.
            matches = []
            for page in range(1, 6):
                releases = self.api('/repos/' + REPO + '/releases?per_page=100&page=' + str(page))
                require(isinstance(releases, list), 'INVALID_RELEASE_LIST')
                matches.extend(item for item in releases if item.get('tag_name') == tag)
                require(len(matches) <= 1, 'DUPLICATE_RELEASE_TAG')
                if len(releases) < 100:
                    if not matches:
                        return None
                    release = matches[0]
                    break
            else:
                raise GuardError('RELEASE_LIST_PAGE_LIMIT_EXCEEDED')
        require(type(release.get('id')) is int and release['id'] > 0
                and release.get('tag_name') == tag, 'RELEASE_IDENTITY_MISMATCH')
        return release

    def release_id(self, release_id, tag):
        require(type(release_id) is int and release_id > 0, 'INVALID_RELEASE_ID')
        release = self.api('/repos/' + REPO + '/releases/' + str(release_id))
        require(type(release.get('id')) is int and release['id'] == release_id
                and release.get('tag_name') == tag, 'RELEASE_IDENTITY_MISMATCH')
        return release

    def tag_target(self, tag):
        try:
            reference = self.api('/repos/' + REPO + '/git/ref/tags/' + urllib.parse.quote(tag, safe=''))
        except urllib.error.HTTPError as error:
            if error.code == 404:
                return None
            raise
        target = reference['object']
        for _ in range(5):
            require(re.fullmatch('[a-f0-9]{40}', target.get('sha', '')) is not None, 'INVALID_TAG_TARGET')
            if target.get('type') == 'commit':
                return target['sha']
            require(target.get('type') == 'tag', 'UNEXPECTED_TAG_TARGET_TYPE')
            target = self.api('/repos/' + REPO + '/git/tags/' + target['sha'])['object']
        raise GuardError('ANNOTATED_TAG_DEPTH_EXCEEDED')


def verify_assets(release, files):
    assets = release.get('assets', [])
    require(len(assets) == len(files), 'UNEXPECTED_RELEASE_ASSET_COUNT')
    for path in files:
        matches = [item for item in assets if item['name'] == path.name]
        require(len(matches) == 1, 'MISSING_OR_DUPLICATE_RELEASE_ASSET')
        asset = matches[0]
        require(asset.get('digest') is not None, 'REMOTE_ASSET_DIGEST_UNAVAILABLE')
        require(asset.get('state') == 'uploaded' and asset.get('size') == path.stat().st_size
                and isinstance(asset.get('digest'), str)
                and asset['digest'].lower() == 'sha256:' + sha256(path), 'REMOTE_ASSET_DIGEST_OR_SIZE_MISMATCH')


def verify_remote_assets(github, release, files):
    assets = [dict(item) for item in release.get('assets', [])]
    require(len(assets) == len(files), 'UNEXPECTED_RELEASE_ASSET_COUNT')
    for asset in assets:
        require(asset.get('name') in {path.name for path in files}, 'UNEXPECTED_RELEASE_ASSET')
        # Digests may briefly be null while GitHub finalizes an upload. Re-read
        # that same asset ID; never delete or replace a pending/unexpected asset.
        for delay in (1, 2, None):
            if asset.get('digest') is not None:
                break
            require(type(asset.get('id')) is int and asset['id'] > 0, 'INVALID_ASSET_ID')
            fresh = github.api('/repos/' + REPO + '/releases/assets/' + str(asset['id']))
            require(fresh.get('id') == asset['id'] and fresh.get('name') == asset.get('name'), 'ASSET_IDENTITY_MISMATCH')
            asset.update(fresh)
            if asset.get('digest') is None and delay is not None:
                time.sleep(delay)
    result = dict(release, assets=assets)
    verify_assets(result, files)
    return result


def release_body(manifest):
    return 'Ox-gram ' + manifest['version'] + '\n\n' + manifest['changelog']


def stage(github, apk, manifest_path, manifest, metadata_path):
    require(manifest_path.read_bytes() == json_bytes(manifest), 'LOCAL_MANIFEST_CHANGED')
    remote_sha = github.preflight()
    require(github.tag_target(manifest['version']) in (None, remote_sha), 'EXISTING_TAG_TARGET_MISMATCH')
    release = github.release(manifest['version'])
    expected = {'tag_name': manifest['version'], 'name': 'Ox-gram ' + manifest['version'],
                'body': release_body(manifest), 'draft': True, 'prerelease': False}
    if release is None:
        release = github.api('/repos/' + REPO + '/releases', 'POST', dict(expected, target_commitish=remote_sha))
    require(type(release.get('id')) is int and release['id'] > 0
            and release.get('tag_name') == manifest['version'], 'RELEASE_IDENTITY_MISMATCH')
    release_id = release['id']
    release = github.release_id(release_id, manifest['version'])
    require(release.get('draft') is True, 'RELEASE_ALREADY_PUBLISHED')
    require(all(release.get(key) == value for key, value in expected.items()), 'EXISTING_DRAFT_DIFFERS')
    require(release.get('target_commitish') == remote_sha, 'DRAFT_TARGET_DIFFERS_FROM_REMOTE_MAIN')
    files = (apk, manifest_path)
    require(all(item.get('name') in {path.name for path in files} for item in release.get('assets', [])), 'UNEXPECTED_DRAFT_ASSET')
    upload_base = release['upload_url'].split('{', 1)[0]
    require(upload_base == 'https://uploads.github.com/repos/' + REPO + '/releases/' + str(release['id']) + '/assets', 'UPLOAD_URL_MISMATCH')
    for path in files:
        present = [item for item in release.get('assets', []) if item.get('name') == path.name]
        if present:
            verify_remote_assets(github, dict(assets=present), (path,))
        else:
            uploaded = github.api(upload_base + '?name=' + urllib.parse.quote(path.name, safe=''), 'POST', upload=path)
            verify_remote_assets(github, dict(assets=[uploaded]), (path,))
    verified_apk(metadata_path)
    require(manifest_path.read_bytes() == json_bytes(manifest), 'LOCAL_MANIFEST_CHANGED')
    release = github.release_id(release_id, manifest['version'])
    require(all(release.get(key) == value for key, value in expected.items())
            and release.get('target_commitish') == remote_sha, 'DRAFT_CHANGED_DURING_UPLOAD')
    release = verify_remote_assets(github, release, files)
    state = {'repository': REPO, 'release_id': release['id'], 'tag': manifest['version'],
             'target_commitish': remote_sha, 'apk_sha256': sha256(apk),
             'manifest_sha256': sha256(manifest_path), 'body': release_body(manifest)}
    write_once(Path(metadata_path).resolve().parent / 'github-draft-state.json', json_bytes(state))
    return release


def verify_public(apk, manifest_path, manifest):
    urls = [('https://github.com/' + REPO + '/releases/latest/download/' + MANIFEST_NAME, manifest_path),
            (manifest['apk_url'], apk)]
    for url, path in urls:
        # Newly public assets can briefly return CDN 404. Only transient HTTP
        # statuses retry; integrity failures stop immediately. Total backoff is
        # bounded to 14 seconds per asset and emits no request/error details.
        for delay in (2, 4, 8, None):
            request = urllib.request.Request(url, headers={'User-Agent': 'Ox-gram-release-check', 'Cache-Control': 'no-cache'})
            try:
                with urllib.request.build_opener(SafeRedirect()).open(request, timeout=180) as response:
                    digest = hashlib.sha256()
                    size = 0
                    while chunk := response.read(1024 * 1024):
                        size += len(chunk)
                        require(size <= path.stat().st_size, 'PUBLIC_ASSET_EXCEEDS_EXPECTED_SIZE')
                        digest.update(chunk)
                require(size == path.stat().st_size and digest.hexdigest() == sha256(path), 'PUBLIC_ASSET_HASH_OR_SIZE_MISMATCH')
                break
            except urllib.error.HTTPError as error:
                if delay is None or not (error.code in (404, 429) or 500 <= error.code <= 599):
                    raise
                time.sleep(delay)


def publish(github, apk, manifest_path, manifest, metadata_path):
    require(manifest_path.read_bytes() == json_bytes(manifest), 'LOCAL_MANIFEST_CHANGED')
    remote_sha = github.preflight()
    require(github.tag_target(manifest['version']) in (None, remote_sha), 'EXISTING_TAG_TARGET_MISMATCH')
    state = read_json(Path(metadata_path).resolve().parent / 'github-draft-state.json')
    release = github.release_id(state['release_id'], manifest['version'])
    require(release is not None and release.get('id') == state['release_id']
            and state['repository'] == REPO and state['tag'] == manifest['version']
            and release.get('target_commitish') == state['target_commitish'] == remote_sha
            and release.get('body') == state['body'] == release_body(manifest)
            and release.get('name') == 'Ox-gram ' + manifest['version']
            and release.get('prerelease') is False
            and state['apk_sha256'] == sha256(apk)
            and state['manifest_sha256'] == sha256(manifest_path), 'PREPARED_RELEASE_CHANGED')
    release = verify_remote_assets(github, release, (apk, manifest_path))
    verified_apk(metadata_path)
    if release['draft']:
        release = github.api('/repos/' + REPO + '/releases/' + str(release['id']), 'PATCH',
                             {'draft': False, 'make_latest': 'true'})
    release = github.release_id(state['release_id'], manifest['version'])
    require(release.get('draft') is False, 'RELEASE_NOT_PUBLIC')
    require(github.tag_target(manifest['version']) == remote_sha, 'PUBLISHED_TAG_TARGET_MISMATCH')
    release = verify_remote_assets(github, release, (apk, manifest_path))
    verify_public(apk, manifest_path, manifest)
    receipt = {'status': 'published_and_verified', 'repository': REPO, 'release_id': release['id'],
               'tag': manifest['version'], 'url': 'https://github.com/' + REPO + '/releases/tag/' + manifest['version'],
               'version_code': manifest['version_code'], 'apk_sha256': sha256(apk),
               'manifest_sha256': sha256(manifest_path), 'public_assets_verified': True}
    write_once(Path(metadata_path).resolve().parent / 'github-publish-receipt.json', json_bytes(receipt))
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('prepare', 'draft', 'publish', 'verify-public'))
    parser.add_argument('--metadata', type=Path, default=CHECKS / 'apk-metadata.json')
    parser.add_argument('--changelog', type=Path, default=CHECKS / 'changelog.txt')
    args = parser.parse_args()
    apk, metadata, manifest_path, manifest = prepare(args.metadata, args.changelog)
    if args.mode == 'prepare':
        print(json.dumps({'status': 'prepared_locally', 'version': metadata['version'], 'version_code': metadata['versionCode']}))
    elif args.mode == 'verify-public':
        verify_public(apk, manifest_path, manifest)
        print(json.dumps({'status': 'public_assets_verified', 'version': metadata['version']}))
    else:
        github = GitHub()
        try:
            if args.mode == 'draft':
                release = stage(github, apk, manifest_path, manifest, args.metadata)
                print(json.dumps({'status': 'draft_assets_verified', 'release_id': release['id'], 'tag': manifest['version']}))
            else:
                print(json.dumps(publish(github, apk, manifest_path, manifest, args.metadata)))
        finally:
            github.close()


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # HTTP exception messages may contain request details. Emit fixed codes only.
        code = str(error) if isinstance(error, GuardError) else type(error).__name__
        print('RELEASE_OPERATION_FAILED ' + code, file=sys.stderr)
        sys.exit(1)
