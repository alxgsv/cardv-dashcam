# Release guide

> **Distribution:** the signed Android APK + AAB from GitHub Releases is the only published channel.
> iOS and macOS are built from source by users.

Everything here is prepared so an Android release can be built and published from the repository. Nothing
is published automatically by a pull request, a branch push or a fork. Publishing requires a `v*` tag or a
manual workflow dispatch and the account-side secrets listed below.

## Release channel

| Channel | Build path | Account-side work still needed |
|---|---|---|
| GitHub Releases — signed Android APK + AAB | `.github/workflows/release-android.yml` | Signing secrets configured; rotate only if the keystore changes |

iOS and macOS users build from source ([README](../README.md#run-it)) and install with Xcode; this guide
does not produce Apple artifacts.

## One version source

[`version.xcconfig`](../version.xcconfig) is the only version source:

```text
MARKETING_VERSION = 1.0.0       # app version, release tag v1.0.0
CURRENT_PROJECT_VERSION = 1     # Apple bundle build number
ANDROID_VERSION_CODE = 10000    # Android versionCode for 1.0.0
```

- Android reads `MARKETING_VERSION` and `ANDROID_VERSION_CODE` in `androidApp/build.gradle.kts`.
- The shared core generates `BuildInfo.VERSION` from `MARKETING_VERSION` in
  `:shared:generateBuildInfo`, so the Android and Apple in-app version pills show the same string.
- Xcode reads the file through `appleApp/Signing.xcconfig` and uses `MARKETING_VERSION` /
  `CURRENT_PROJECT_VERSION` for `CFBundleShortVersionString` / `CFBundleVersion`.

### Bumping the version

1. Change `MARKETING_VERSION` in `version.xcconfig`.
2. Increase `CURRENT_PROJECT_VERSION` whenever a new Apple build is distributed. It must always grow.
3. Increase `ANDROID_VERSION_CODE`. The current scheme is `major * 10000 + minor * 100 + patch`, so
   `1.2.3` is `10203`. Never lower it for a published build.
4. Run `tools/release/check-version.sh` and the normal build/test commands.
5. Add `docs/releases/v<TAG>.md` if the release needs hand-written notes. The Android workflow uses that
   file when it exists and otherwise asks GitHub to generate notes from commits.
6. Tag the commit and push the tag:

   ```sh
   git tag -a v1.0.0 -m "CarDV Dashcam 1.0.0"
   git push origin v1.0.0
   ```

The tag is rejected by the release workflow if it does not match `vMARKETING_VERSION`.

## Android

### Signing material

Create one upload keystore **outside the repository** (for example in `~/keystores/`) and keep a backup:

```sh
keytool -genkeypair -v \
  -keystore ~/keystores/cardv-release.jks \
  -alias cardv \
  -keyalg RSA -keysize 4096 -validity 10000
```

Never commit the keystore, its passwords, `local.properties`, or `Signing.local.xcconfig`. They are all
gitignored or outside the tree. The signing config reads either the gitignored `local.properties`:

```properties
cardv.release.storeFile=/absolute/path/to/cardv-release.jks
cardv.release.storePassword=...
cardv.release.keyAlias=cardv
cardv.release.keyPassword=...
```

or the environment variables used by CI:

| Environment variable | Meaning |
|---|---|
| `CARDV_RELEASE_STORE_FILE` | path to the `.jks` |
| `CARDV_RELEASE_STORE_PASSWORD` | keystore password |
| `CARDV_RELEASE_KEY_ALIAS` | key alias |
| `CARDV_RELEASE_KEY_PASSWORD` | key password (may differ from the keystore password) |

Without them `assembleRelease` still builds, but the APK is unsigned. `:androidApp:requireReleaseSigning`
fails with a clear message, and the release workflow runs it first.

### Building

```sh
./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest
./gradlew :androidApp:lintDebug :androidApp:assembleRelease :androidApp:bundleRelease
```

Outputs:

| File | Path |
|---|---|
| APK | `androidApp/build/outputs/apk/release/androidApp-release.apk` (GitHub Release install) |
| AAB | `androidApp/build/outputs/bundle/release/androidApp-release.aab` (same GitHub Release) |

Release builds enable R8 code shrinking and Android resource shrinking
(`isMinifyEnabled = true`, `isShrinkResources = true`). App-specific keep rules are in
`androidApp/proguard-rules.pro`. Verify the APK signature and contents before uploading:

```sh
APK=androidApp/build/outputs/apk/release/androidApp-release.apk
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs "$APK"
"$ANDROID_HOME/build-tools/36.0.0/aapt2" dump badging "$APK"
```

The workflow also runs `apksigner verify` and writes `SHA256SUMS` next to the APK/AAB.

### GitHub Release workflow

`.github/workflows/release-android.yml` runs only on a `v*` tag or `workflow_dispatch`. Add these repository
secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -i ~/keystores/cardv-release.jks` output |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEY_PASSWORD` | key password |

The workflow decodes the keystore into `$RUNNER_TEMP`, builds signed APK + AAB, verifies the signature,
writes `SHA256SUMS`, uploads an artifact, and on a tag creates or updates the GitHub Release. It never
runs for pull requests or forks, so no secrets are required there.

Checksum verification for users:

```sh
shasum -a 256 -c SHA256SUMS        # macOS
sha256sum -c SHA256SUMS            # Linux
```

## Apple apps

iOS and macOS have no published artifacts. Users clone the repository and build with Xcode; on an
iPhone `tools/apple/install-device.sh` signs a Release build with a development profile, installs and
launches it. The team lives in the gitignored `appleApp/Signing.local.xcconfig`:

```sh
cp appleApp/Signing.local.xcconfig.example appleApp/Signing.local.xcconfig
# set DEVELOPMENT_TEAM
```

The bundle id is `com.cardv.dashcam` (the same string as the Android application id). Xcode reads
`MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` from `version.xcconfig` through `appleApp/Signing.xcconfig`,
so a source build carries the release version. The macOS target is sandboxed with
`com.apple.security.network.client` and user-selected file access; `ENABLE_HARDENED_RUNTIME = YES` is set
for both configurations.

## Privacy declarations

- iOS/macOS collect nothing. `NSPrivacyTracking = false`, no tracking domains, no collected data types.
- Photos are **add-only** (`NSPhotoLibraryAddUsageDescription`); the app does not read the library.
- Local network is used only to reach the camera at `192.168.42.1`; the purpose string is in `Info.plist`.
- Android declares only internet/network/Wi-Fi permissions and legacy write access for Android 8–9
  exports. There is no analytics or crash-reporting SDK.
- The macOS download saves to a folder chosen by the user; the sandbox uses
  `files.user-selected.read-write`.

## Pull requests, forks and CI

- `.github/workflows/ci.yml` builds and tests on every push and pull request with **no secrets**:
  shared tests, Android tests/build/lint, iOS Simulator and macOS Debug builds.
- `.github/workflows/release-android.yml` only triggers on a `v*` tag or manual dispatch and fails early
  with a readable message if the repository secrets are absent. For an unsigned smoke build use the
  local Gradle commands above.
- Do not add secrets to a fork. Fork pull requests run the secret-free CI only.

## Release checklist

Before tagging:

- [ ] `tools/release/check-version.sh` passes, the version was bumped and `ANDROID_VERSION_CODE` grew.
- [ ] `./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:lintDebug` passes.
- [ ] `./gradlew :androidApp:assembleRelease :androidApp:bundleRelease` passes with R8 + shrinking.
- [ ] `xcodebuild ... -configuration Release` builds for the iOS Simulator and macOS.
- [ ] `docs/COMPATIBILITY.md` reflects the cars/firmware tested for this release.
- [ ] Keystore, `Signing.local.xcconfig` and passwords are outside the repository and not in
      `git status`.

After tagging:

- [ ] Android workflow uploaded APK + AAB + `SHA256SUMS` to the GitHub Release.
- [ ] Download each artifact once, verify the checksum and install it.
- [ ] Update `docs/COMPATIBILITY.md` with any new real-car reports.

## Rolling back

Keep the previous tag and artifacts. To withdraw a bad build, delete or mark the GitHub Release as a
draft and publish a new patch version with a higher `CURRENT_PROJECT_VERSION` and `ANDROID_VERSION_CODE`.
Never re-tag an existing version.
