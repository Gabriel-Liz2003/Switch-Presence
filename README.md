# Switch Presence (Android)

Android app for showing **Nintendo Switch / Switch 2** game activity on your **own Discord profile**. No bot, PC or modified console needed. Built for personal sideloading; not affiliated with Nintendo or Discord.

> **Important:** Android Rich Presence requires the official **Discord Social SDK 1.10+** AAR, which is **not committed** to this repository. A standard CI *preview* APK can detect the Switch game but **cannot publish to Discord**. Do not mistake a preview build for a functional Rich Presence build.

## What's implemented

- Dark native Android interface with Nintendo-inspired red accent.
- Reads a user-controlled `nxapi-presence` URL over HTTPS, detects game and Switch generation, handles offline/error states.
- Encrypts the presence URL using Android Keystore / AES-GCM, never uploads credentials or asks for Nintendo/Discord passwords.
- Foreground polling while enabled (45 s), notification with stop action, SDK callback processing, **retries ClearRichPresence on every offline poll (including the first poll after an app restart)** and provides a manual clear button. As the official SDK ClearRichPresence has no completion callback, the app says only “removal requested”, not that Discord has confirmed removal.
- JNI bridge to **official** Discord Social SDK RPC, no OAuth flow required when the Android Discord app is installed and signed in.
- **In-app updates:** checks `releases/latest`, compares versions, fetches APK + **required SHA-256**, verifies integrity, opens Android's standard installer after confirmation.
- GitHub Actions: preview debug builds on pushes/PRs, separately gated release builds on `vX.Y.Z` tags.

## SDK 1.10.19337 inspection (2026-10-13)

The official `discord_partner_sdk.aar` supplied by the project owner was inspected **locally, without uploading it to this public repository**:

- Contains `libs/discord_partner_sdk.jar`, `com.discord.socialsdk.DiscordSocialSdkInit.setEngineActivity(Activity)`, `prefab/modules/discord_partner_sdk/include/discordpp.h` and `cdiscord.h`.
- Includes native binaries for `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`; this app targets `arm64-v8a`.
- The actual JNI bridge in `presence_jni.cpp` (including the SDK RPC methods) passed a C++17 **syntax-only** compilation against the supplied SDK headers and local JNI headers. **This is not an Android APK build nor an on-device functionality test.**
- The AAR also declares microphone/voice permissions/services, which the app does not use. The app manifest removes these from the merged application manifest. Verify the merged manifest in a full build.

For another local AAR, run `python3 scripts/verify_sdk.py /path/to/discord_partner_sdk.aar` to inspect packaging; do not post SDK binaries publicly.

## Set up your accounts

1. Follow [nxapi presence API enrollment](https://github.com/samuelthomas2774/nxapi/discussions/155) at [nxapi-auth](https://nxapi-auth.fancy.org.uk/). Copy your personal **presence URL** (not the event-stream URL). Nintendo presence may be publicly exposed by nxapi — understand this before enrolling.
2. Create a Discord application at [Discord Developer Portal](https://discord.com/developers/applications) and copy its numeric **Application ID**. **Do not use a bot token, client secret or password.**
3. Install the official Android Discord app, sign in, and allow your activities to be displayed.

## Build locally — FULL version

Prerequisites: Android Studio (JDK 17), Android SDK 35, NDK, CMake 3.22.1, ARM64 Android 8+ phone.

1. Download the **Discord Social SDK Android** version 1.10+ from the **Social SDK** section of your Discord application.
2. Extract the file `discord_partner_sdk.aar` from the official Android package and place it at `app/libs/discord_partner_sdk.aar`. Do not upload this proprietary archive into this public repo.
3. Open the repository root in Android Studio and build/install. The Gradle build detects the official AAR and enables C++/JNI + native prefab linking.
4. Paste the nxapi URL and numeric Discord Application ID into the app. Start monitoring from the main screen.

Without the AAR, the app compiles into a **monitor-only preview** and displays a warning. This is by design: it does not pretend to have connected to Discord.

References: [Discord mobile Rich Presence without auth](https://github.com/discord/discord-api-docs/blob/main/developers/discord-social-sdk/development-guides/setting-rich-presence.mdx) and [Android SDK integration](https://github.com/discord/discord-api-docs/blob/main/developers/discord-social-sdk/development-guides/account-linking-on-mobile.mdx).

## Build on GitHub Actions

**Every push to `main` and every pull request:** the `Android CI` workflow builds a **monitor-only debug APK** and uploads it as an Actions artifact. It is suitable for testing the UI and nxapi URL, not Rich Presence.

### Test a full Discord-enabled APK without a PC

To avoid publishing the proprietary AAR in this **public** app repository:

1. In GitHub create a **private** repository, for example `Switch-Presence-SDK`. Upload **both** `discord_partner_sdk.aar.part01` and `discord_partner_sdk.aar.part02` to its root. The Android SDK AAR exceeds GitHub's 25 MiB browser-upload limit, so the project owner divided the verified 1.10.19337 SDK into two parts. Keep this repository private.
2. In GitHub Settings → Developer settings → Personal access tokens → Fine-grained tokens, create a short-lived token with **Contents: Read-only** permission on **only that private SDK repository**.
3. In this **public app repo**, open Settings → Secrets and variables → Actions:
   - Under **Variables**, add `DISCORD_SDK_PRIVATE_REPO` = `YOUR_USERNAME/Switch-Presence-SDK`.
   - Under **Secrets**, add `DISCORD_SDK_READ_TOKEN` = the fine-grained token. **Never put the token or the AAR in the public repository, PRs, screenshots, logs or chat.**
4. Every push to `main` automatically builds `SwitchPresence-FULL-debug-Discord` with the private SDK. Alternatively open **Actions → Android CI → Run workflow** on `main` to build it manually. The full preview job downloads both pieces, verifies its pinned SHA-256 and compiles the APK. Only trusted `main` pushes or manual runs can access private SDK secrets; pull requests cannot.
5. Download that Actions artifact ZIP and install the `app-debug.apk` on Android. This build has the SDK, but **Discord RPC behavior must still be tested on your device**. GitHub-hosted debug builds often get **different debug signing keys on each workflow run**. When Android rejects a new debug APK as an update, save your private nxapi URL and Discord Application ID somewhere safe, **uninstall the old debug APK**, then install the new one and re-enter settings. This resets the encrypted URL stored locally. Production release updates require a persistent signing key.

The exact original SDK is pinned to SHA-256 `b1b2491f1e1848c79fd6f1986d5aa1e0c8019e88a6e62c7be06b89e8e4870933` for safe reconstruction. Do **not** replace it with a different SDK version without updating the verification script. The public pull-request workflow continues to build **monitor-only** APKs and cannot fetch the private SDK. Trusted main-branch push / manual full-preview and tagged release workflows obtain the SDK only from the secret-protected private repository. GitHub-hosted full debug artifacts have seven-day retention.
## Production releases and permanent in-app updates

The Android `Verificar atualizações` button checks GitHub **Releases**, not Actions artifacts. A preview/debug APK is only for testing and **cannot be automatically upgraded to a production-signed APK**. Production needs one permanent signing key and two GitHub Actions secrets.

### One-time maintainer setup (from mobile)

1. Back up the owner-generated **private signing ZIP** outside GitHub. It contains `switchpresence-release.jks`, a Base64 copy and the randomly generated password. **Never commit the key, Base64 or password, and never share the ZIP.** Losing the key prevents updating installed users under the same Android package.
2. In `Switch-Presence` → **Settings → Secrets and variables → Actions → Secrets**, create:
   - `ANDROID_KEYSTORE_B64`: the complete **single-line** contents of `ANDROID_KEYSTORE_B64.txt` in the private ZIP.
   - `ANDROID_STORE_PASSWORD`: the value in `ANDROID_STORE_PASSWORD.txt`.
3. Keep the existing `DISCORD_SDK_READ_TOKEN` secret and `DISCORD_SDK_PRIVATE_REPO` variable pointing to the private AAR chunks (GitHub already used them to compile a working Discord debug APK).
4. In the same **Actions → Variables** screen, add `RELEASE_ENABLED` = `true`.
5. Go to **Actions → Publish signed Android updates → Run workflow**, branch `main`, to create the very first stable Release (or let the next main push trigger it automatically).

The separate `.github/workflows/release.yml` job builds with the licensed SDK, imports the **same** PKCS12 signing key every time, verifies the signed APK using `apksigner`, and publishes a **stable GitHub Release** with exactly `SwitchPresence.apk` and `SwitchPresence.sha256`. Every trusted `main` push after enabling releases generates a later version (`1.0.N`, monotonically increasing `versionCode`). Pull requests never have access to private SDK or signing secrets.

### One-time migration from a debug APK

If you previously installed `app-debug.apk`, **save your private nxapi presence URL and Discord Application ID locally**, uninstall that debug version, then install `SwitchPresence.apk` from the first stable Release. The package name stays the same, but Android will reject installing the production-signed app over an app signed with a different debug certificate. Uninstalling erases the locally encrypted URL and other app settings.

**All subsequent stable Releases** use the same key, so the in-app updater can install them over the previous stable version without erasing settings. Android still asks the user to approve installation. Never promise an automatic silent installation.

## Updates directly in the app

Tap **VERIFICAR ATUALIZAÇÕES**. The app checks only this repo's latest **stable** GitHub Release, downloads `SwitchPresence.apk` and its `SwitchPresence.sha256`, verifies it, then requests installation through the Android system. Android 8+ may require enabling “Install unknown apps” for Switch Presence on the first update. Installation is **never silent**. The app does not use a remote self-executing update system.

When a newer release does not exist, it shows the currently installed version. In-app updates require at least one correctly signed production APK published under GitHub Releases.

## Privacy and limitations

- The private presence URL remains encrypted locally but is a bearer-like value. Never post it in GitHub issues.
- nxapi is community-maintained and may become unavailable or change; we do not ask for or store Nintendo session tokens.
- Discord's RPC on Android requires the official Discord mobile app installed and logged in. Result also depends on Discord accepting RPC from your app; **not tested with a real account or device yet**.
- Android background limits / battery optimization can terminate syncing. You may need to allow background operation on MIUI/HyperOS.
- Elapsed time starts when monitoring first detects the current game, not necessarily when the console was turned on.
- `specialUse` foreground service and sideloading are intended for personal installs; publication on the Play Store may require additional compliance.
- No runtime or end-to-end test against Nintendo or Discord has been performed in this environment.

## Roadmap

- [x] Android app, nxapi reader, foreground polling
- [x] Official SDK bridge (requires official AAR to link)
- [x] GitHub Releases update checks, checksum and Android installer (including first-time sideload permission return)
- [x] Stable GitHub Releases publishing workflow with permanent signing (activation requires owner-provided encrypted GitHub Secrets)
- [x] CI preview APK and guarded signed release workflow
- [ ] Full Android NDK/Gradle build with official SDK and on-device RPC validation
- [x] Validate SDK binary structure and JNI C++ syntax against owner-supplied 1.10.19337 AAR
- [x] End-to-end activity publication demonstrated on device (user report, The Witcher 3)
- [ ] Confirm Discord actually removes stale activity after offline; SDK ClearRichPresence has no result callback
- [ ] Verify v0.1.2 stop button on Android: running flag clears immediately, Nintendo polling stops and the service exits even if native SDK stalls; Discord may take longer to remove the activity.
