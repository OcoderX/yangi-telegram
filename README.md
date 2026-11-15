## Ox-gram messenger for Android

[Telegram](https://telegram.org) is a messaging app with a focus on speed and security. It’s superfast, simple and free.
This repo contains the official source code for [Telegram App for Android](https://play.google.com/store/apps/details?id=org.telegram.messenger).

## Creating your Telegram Application

We welcome all developers to use our API and source code to create applications on our platform.
There are several things we require from **all developers** for the moment.

1. [**Obtain your own api_id**](https://core.telegram.org/api/obtaining_api_id) for your application.
2. Please **do not** use the name Telegram for your app — or make sure your users understand that it is unofficial.
3. Kindly **do not** use our standard logo (white paper plane in a blue circle) as your app's logo.
3. Please study our [**security guidelines**](https://core.telegram.org/mtproto/security_guidelines) and take good care of your users' data and privacy.
4. Please remember to publish **your** code too in order to comply with the licences.

### API, Protocol documentation

Telegram API manuals: https://core.telegram.org/api

MTproto protocol manuals: https://core.telegram.org/mtproto

### Compilation Guide

**Note**: In order to support [reproducible builds](https://core.telegram.org/reproducible-builds), this repo contains dummy release.keystore,  google-services.json and filled variables inside BuildVars.java. Before publishing your own APKs please make sure to replace all these files with your own.

You will require Android Studio 2025.1.4, Android NDK 27.2.12479018 and Android SDK 35.

1. Clone the Telegram source code with its submodules:
   ```bash
   git clone --recursive --shallow-submodules https://github.com/DrKLO/Telegram.git Telegram
   ```
   In case you forgot the `--recursive` flag, change to the `Telegram` directory and run:
   ```bash
   git submodule init && git submodule update --init --recursive --depth=1
   ```
2. Copy your release.keystore into TMessagesProj/config
3. Fill out RELEASE_KEY_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_STORE_PASSWORD in gradle.properties to access your  release.keystore
4.  Go to https://console.firebase.google.com/, create two android apps with application IDs org.telegram.messenger and org.telegram.messenger.beta, turn on firebase messaging and download google-services.json, which should be copied to the same folder as TMessagesProj.
5. Open the project in the Studio (note that it should be opened, NOT imported).
6. Fill out values in TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java – there’s a link for each of the variables showing where and which data to obtain.
7. You are ready to compile Telegram.

### Bot login API credentials

If bot login reports `API_ID_INVALID` or `API_ID_PUBLISHED_FLOOD`, obtain your application's `api_id` and `api_hash` from [my.telegram.org/apps](https://my.telegram.org/apps). Add them to the root `local.properties` as `BOT_LOGIN_APP_ID=<api_id>` and `BOT_LOGIN_APP_HASH=<api_hash>`, replacing the placeholders, then rebuild and install the APK. These are application credentials; enter the separate bot token from BotFather on the bot login screen.

Both properties must be set together: the ID must be a positive decimal integer no greater than 2147483647, and the hash must have 32 hexadecimal characters. Omitting both (or setting the ID to `0` and leaving the hash empty) keeps the existing fallback behavior. `local.properties` is ignored by Git, but configured values are embedded in the APK.

### Optimized local APK

Run `./gradlew :TMessagesProj_App:assembleAfatPerformance` to build an APK with debugging disabled and R8 code optimization enabled. The `performance` variant uses the same `.beta` application ID and signing configuration as `afatDebug`, so it can update an existing local debug installation. Its output is `TMessagesProj_App/build/outputs/apk/afat/performance/app.apk`.

The library remains unminified until the application optimization pass. Resource shrinking stays disabled for Telegram's dynamic resource lookups, and TL class names are preserved for bot method guards and message details. Keep the generated R8 mapping file alongside each distributed APK for crash diagnosis.

### Current release label

The current Ox-gram version label is `13.0.3-ox.7`, following the latest upstream Telegram release label. This is a display/version-label update, not a full Telegram 13.0.3 or layer 231 migration; the rich-message compatibility work below remains based on the layer 229 backport, and the phone login fixes are retained.

### Rich message compatibility

Ox-gram `12.9.2-ox.1` backports rich-message compatibility from the official Android source at `f32ffeec763f0bdbf4bec25b52d654f77bff733d` (13.0.2). This includes buttons inside text, button rows, inline documents, compact tables and collapsible quotations. The protocol integration targets layer 229 and preserves the existing bot login and Ox-gram features. It is not a full migration to Telegram 13.0.2 or layer 231.

The code before this update is saved at `f617f2a01f224392b1abb2b29fc4795e692f9fff` on `myfork/main`. Compatibility work is on `codex/message-compatibility`. The local test APK is delivered separately as `ox-gram-message-compatibility.apk`; `ox-gram-performance.apk` remains available as the earlier build.

### APK signing for distribution

The bundled `TMessagesProj/config/release.keystore` is a public dummy key. APKs signed with it are suitable only for local development. The existing `debug`, `performance` and `release` signing configurations remain unchanged for compatibility; use the separate `distribution` variant for an APK signed with your private key.

Set these four values in the ignored root `local.properties`, or as environment variables. Environment variables take precedence. Keep the private keystore outside the repository, and do not commit or share its passwords.

```properties
OX_RELEASE_STORE_FILE=C:/private/ox-gram-release.jks
OX_RELEASE_STORE_PASSWORD=<store password>
OX_RELEASE_KEY_ALIAS=<private key alias>
OX_RELEASE_KEY_PASSWORD=<key password>
```

Absolute paths are recommended; relative keystore paths resolve from the repository root. JKS and PKCS12 keystores are supported. Validate the configuration with `./gradlew :TMessagesProj_App:validateDistributionSigning`, then build with `./gradlew :TMessagesProj_App:assembleAfatDistribution`. Validation also runs before the distribution build and signing tasks. Missing credentials, an inaccessible private key, or the bundled public certificate cause the build to fail; renaming or copying the dummy keystore does not bypass the certificate check. No signing key is generated automatically.

The APK is written to `TMessagesProj_App/build/outputs/apk/afat/distribution/app.apk`. This variant retains the optimized `performance` settings, `.beta` application ID, and SDK23 manifest permissions. It uses the library's `performance` variant and does not change Firebase identifiers. Preserve `TMessagesProj_App/build/outputs/mapping/afatDistribution/mapping.txt` with the APK.

An installation signed with the old public key cannot normally be updated by an APK signed with a new private key. Decide the account and local-data migration before distributing or installing it; uninstalling the old app can remove local data. Changing the package name also creates a separate app and requires reviewing its service configuration.

A private signing key fixes the public-key distribution problem, but does not by itself establish why Google Play Protect blocked an APK or guarantee removal of the warning. If a reviewed APK is still classified as harmful, submit the exact signed APK through the [Google Play Protect appeal process](https://support.google.com/googleplay/android-developer/contact/protectappeals).

### In-app updates

Ox-gram checks its fixed public release feed at <https://github.com/OcoderX/yangi-telegram/releases/latest/download/ox-gram-update.json>. Publish the APK and `ox-gram-update.json` together as assets of a release in the verified `OcoderX/yangi-telegram` repository. Users can check under **Settings > App updates**; Android shows its native confirmation before installation. The update keeps the `org.telegram.messenger.beta` package and the existing performance signing certificate. A newly installed updater APK must be installed once to bootstrap this feature; older builds without the updater cannot receive that first update automatically. Afterward, users update in the app without manually downloading APKs, and Telegram channel announcements can remain text notices.

For each release, build and sign the APK once, then use `Tools/publish_ox_update.py` to prepare and publish its verified APK and manifest assets. The helper supports `prepare` (local manifest), `draft` (upload and verify a draft), `publish` (publish that exact verified draft), and `verify-public` (check the public assets). Pass `--metadata <path>` and `--changelog <path>` when the verification pipeline's files are outside the defaults under `build/in-app-update-apk/`. Release metadata, its matching `verification-result.json`, and both `apk-mapping.txt` and `previous-apk-mapping.txt` must come from the build verification pipeline; the helper does not generate them from a fresh clone. The publisher reads GitHub credentials non-interactively from Git Credential Manager. Do not store tokens, credentials, or session files in the repository.

### Localization

We moved all translations to https://translations.telegram.org/en/android/. Please use it.
