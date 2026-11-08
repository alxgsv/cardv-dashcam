# Release guide

> **Current distribution decision:** Android APK from GitHub Releases is the only published channel.
> iOS and macOS are built from source by users (Xcode over the cable for iOS). TestFlight, a notarized
> macOS DMG, F-Droid and Google Play are intentionally not part of the current plan; the sections
> below remain as optional paths if that changes.

Everything here is prepared so a release can be built and published from the repository. Nothing is
published automatically by a pull request, a branch push or a fork. Publishing requires a `v*` tag or a
manual workflow dispatch and the account-side secrets listed below.

## Release channels at a glance

| Channel | Build path | Ready in the repository | Account-side work still needed |
|---|---|---|---|
| GitHub Releases — signed Android APK + AAB | `.github/workflows/release-android.yml` | Yes; 1.0.0 published | Signing secrets configured; rotate only if the keystore changes |
| F-Droid | `fastlane/metadata/android/` description/icon/changelog | **Not planned for now**; metadata remains available | None unless this changes |
| TestFlight / unlisted App Store — iOS | `.github/workflows/release-apple.yml`, `tools/apple/archive-ios.sh` | **Not planned for now**; optional path documented | Developer Program, App Store Connect record, distribution certificate + profile |
| Notarized macOS DMG | `.github/workflows/release-apple.yml`, `tools/apple/notarize-macos.sh` | **Not planned for now**; optional path documented | Developer ID Application certificate and notary credentials |
| Apple live view | — | **Merged on `master`**; `v1.0.0` predates it | Ship it in the next release |
| Google Play, public App Store listing | — | Not planned | None |

The Android release can be produced entirely from this repository once the secrets exist. iOS and
macOS users build from source; the Apple archive/notarization sections below are optional.

## One version source

[`version.xcconfig`](../version.xcconfig) is the only version source:

```text
MARKETING_VERSION = 1.0.0       # app version, release tag v1.0.0
CURRENT_PROJECT_VERSION = 1     # App Store / TestFlight build number
ANDROID_VERSION_CODE = 10000    # Android versionCode for 1.0.0
```

- Android reads `MARKETING_VERSION` and `ANDROID_VERSION_CODE` in `androidApp/build.gradle.kts`.
- The shared core generates `BuildInfo.VERSION` from `MARKETING_VERSION` in
  `:shared:generateBuildInfo`, so the Android and Apple in-app version pills show the same string.
- Xcode reads the file through `appleApp/Signing.xcconfig` and uses `MARKETING_VERSION` /
  `CURRENT_PROJECT_VERSION` for `CFBundleShortVersionString` / `CFBundleVersion`.

### Bumping the version

1. Change `MARKETING_VERSION` in `version.xcconfig`.
2. Increase `CURRENT_PROJECT_VERSION` for every upload to App Store Connect. It must always grow.
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
| APK | `androidApp/build/outputs/apk/release/androidApp-release.apk` (sideload / GitHub Release) |
| AAB | `androidApp/build/outputs/bundle/release/androidApp-release.aab` (store upload) |

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

### F-Droid

`fastlane/metadata/android/en-US/` contains the title, short/full description, icon and the `10000`
changelog that F-Droid can import. The package id is the real one, `com.cardv.dashcam`.

No `fdroiddata` recipe is committed here: that recipe belongs in the F-Droid repository and needs a
tagged commit, a `Builds` entry and F-Droid's own anti-feature review. Do not invent anti-features. After
the first tagged build, submit the app to `fdroiddata` with the tag, the Gradle build command
(`assembleRelease`), `versionCode` 10000 and the MIT licence, and let F-Droid decide the anti-feature
list.

## Apple — iOS

### Signing

The team lives in the gitignored `appleApp/Signing.local.xcconfig`:

```sh
cp appleApp/Signing.local.xcconfig.example appleApp/Signing.local.xcconfig
# set DEVELOPMENT_TEAM
```

The bundle id is `com.cardv.dashcam`. The Android application id is the same string, but the stores and
signing identities are separate.

### Release archive

```sh
tools/apple/archive-ios.sh            # archive only
tools/apple/archive-ios.sh --export   # also export an IPA for App Store Connect
```

The archive lands in `build/apple/ios/CardvDashcam.xcarchive`; the IPA (with `--export`) lands in
`build/apple/ios/export/`. The script checks the archived bundle version against `version.xcconfig`.
Upload the IPA through **Xcode → Window → Organizer → Distribute App**, or with **Transporter**. Do not
use a development profile for TestFlight; use an App Store distribution certificate and profile.

For CI, `.github/workflows/release-apple.yml` imports the certificate and profile from:

| Secret | Value |
|---|---|
| `APPLE_TEAM_ID` | ten-character team id |
| `APPLE_CERT_P12_BASE64` | base64 of the App Store distribution `.p12` |
| `APPLE_CERT_PASSWORD` | password for that `.p12` |
| `APPLE_IOS_PROVISION_BASE64` | base64 of the App Store `.mobileprovision` |

It archives and exports the IPA as an artifact. It does not upload to App Store Connect by itself; use
Organizer/Transporter after reviewing the build, or add an upload step once the account is set up.
The workflow is **manual-only** until the Apple secrets exist; add the `push: tags: ["v*"]` trigger in
`.github/workflows/release-apple.yml` when they do, and it will also attach the IPA to a tag's GitHub
Release.

### App Store Connect / TestFlight checklist

- [ ] Paid Apple Developer Program membership and the team in `Signing.local.xcconfig`.
- [ ] App Store Connect app record for `com.cardv.dashcam` (name "CarDV Dashcam", primary language,
      category Utilities, SKU).
- [ ] App privacy: **no data collected**, **no tracking**. The privacy manifest
      (`appleApp/CardvDashcam/PrivacyInfo.xcprivacy`) declares no tracking/collected data and the
      required-reason APIs the app uses (UserDefaults, file attributes).
- [ ] Purpose strings reviewed:
      `NSLocalNetworkUsageDescription` (local Wi-Fi camera) and
      `NSPhotoLibraryAddUsageDescription` (add-only Photos save).
- [ ] Export compliance: `ITSAppUsesNonExemptEncryption = NO`; the app uses only the local network and
      no non-exempt cryptography.
- [ ] Screenshots for the current device sizes, description, keywords, support URL and (if required)
      privacy policy URL. The app has no account, analytics or server.
- [ ] Build uploaded with `CURRENT_PROJECT_VERSION` from `version.xcconfig`; wait for processing.
- [ ] Internal TestFlight testers first; then external TestFlight with test information and review.
- [ ] For an unlisted App Store release, set the distribution method to **Unlisted App** in App Store
      Connect after the app is approved. The App Store link is then available only to people who have it;
      it is not searchable. TestFlight remains the pre-release route.
- [ ] `v1.0.0` notes must not claim Apple live view; it predates M4. Releases after `v1.0.0` can list
      Apple live view, which is merged on `master`.

## Apple — macOS notarization

`ENABLE_HARDENED_RUNTIME = YES` is already set for both configurations. The macOS target is sandboxed and
has `com.apple.security.network.client` and user-selected file access.

```sh
export DEVELOPMENT_TEAM=XXXXXXXXXX
export MACOS_SIGN_IDENTITY="Developer ID Application: Your Name (XXXXXXXXXX)"
export NOTARY_PROFILE=cardv-notary      # or ASC_API_KEY_PATH/ID/ISSUER

tools/apple/notarize-macos.sh
```

The script archives Release for `generic/platform=macOS`, verifies the app signature and version, builds
`build/apple/macos/CardvDashcam.dmg`, signs the DMG with a timestamp, submits it with `notarytool --wait`,
staples it, validates the staple with `spctl`, and writes `CardvDashcam.dmg.sha256`. It uses the signed
app from the archive directly; `tools/apple/ExportOptions-developer-id.plist` is there if you prefer a
`xcodebuild -exportArchive` Developer ID bundle instead.

Create the reusable notary profile once:

```sh
xcrun notarytool store-credentials cardv-notary \
  --apple-id you@example.com --team-id XXXXXXXXXX --password <app-specific-password>
```

Or use an App Store Connect API key (`.p8`) with `ASC_API_KEY_PATH`, `ASC_API_KEY_ID`, `ASC_API_ISSUER`.
The CI workflow uses the API-key route and these secrets:

| Secret | Value |
|---|---|
| `APPLE_TEAM_ID` | team id |
| `APPLE_DEVELOPER_ID_P12_BASE64` | base64 of the Developer ID Application `.p12` |
| `APPLE_DEVELOPER_ID_PASSWORD` | password for that `.p12` |
| `APPLE_DEVELOPER_ID_IDENTITY` | optional full identity string; defaults to "Developer ID Application" |
| `APPLE_NOTARY_KEY_P8_BASE64` | base64 of `AuthKey_<KEYID>.p8` |
| `APPLE_NOTARY_KEY_ID` | API key id |
| `APPLE_NOTARY_ISSUER` | API issuer UUID |

The workflow is manual-only until the Apple secrets exist; after adding the tag trigger it attaches the
DMG and its checksum to a tag's GitHub Release. For a local smoke test without a Developer ID certificate,
use `xcodebuild ... CODE_SIGNING_ALLOWED=NO build` or the Debug build; those are not notarizable release
artifacts.

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
- The release workflows only trigger on `v*` tags or manual dispatch, and fail early with a readable
  message if the repository secrets are absent. A manual dispatch also expects the secrets; for an
  unsigned smoke build use the local `xcodebuild`/Gradle commands above.
- Do not add secrets to a fork. Fork pull requests run the secret-free CI only.

## Release checklist

Before tagging:

- [ ] `tools/release/check-version.sh` passes, the version was bumped and `ANDROID_VERSION_CODE` grew.
- [ ] `./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest :androidApp:lintDebug` passes.
- [ ] `./gradlew :androidApp:assembleRelease :androidApp:bundleRelease` passes with R8 + shrinking.
- [ ] `xcodebuild ... -configuration Release` builds for the iOS Simulator and macOS.
- [ ] `tools/apple/archive-ios.sh` succeeds for the device archive.
- [ ] `docs/COMPATIBILITY.md` reflects the cars/firmware tested for this release.
- [ ] Release notes for `v1.0.0` do not claim Apple live view; later releases can include it.
- [ ] Keystore, `.p12`, `.mobileprovision`, `.p8` and passwords are outside the repository and not in
      `git status`.

After tagging:

- [ ] Android workflow uploaded APK + AAB + `SHA256SUMS` to the GitHub Release.
- [ ] iOS IPA attached (and uploaded to TestFlight if applicable).
- [ ] macOS DMG + `.sha256` attached and `spctl`/`stapler validate` pass.
- [ ] Download each artifact once, verify the checksum and install it.
- [ ] Update `docs/COMPATIBILITY.md` with any new real-car reports.

## Rolling back

Keep the previous tag and artifacts. To withdraw a bad build, delete or mark the GitHub Release as a
draft, stop the TestFlight build, and publish a new patch version with a higher `CURRENT_PROJECT_VERSION`
and `ANDROID_VERSION_CODE`. Never re-tag an existing version.
