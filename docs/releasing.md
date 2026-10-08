# Creating an Android Release

GitHub Actions builds an APK and AAB and creates a draft release when a version
tag is pushed. The tag must exactly match the Android `versionName` in
`app/build.gradle`, using three numeric components such as `1.0.24`. A `v`
prefix and prerelease suffixes are not accepted by the Android workflow.

Android versions are independent of MeshCentral and native MeshAgent versions.
For an Android testing release, mark the draft as a prerelease before publishing.

## One-Time Signing Setup

GitHub Actions requires a production signing keystore. Add these repository
secrets under **Settings > Secrets and variables > Actions** before creating a
release:

- `ANDROID_KEYSTORE_BASE64`: the Base64-encoded contents of the keystore.
- `ANDROID_KEYSTORE_PASSWORD`: the keystore password.
- `ANDROID_KEY_ALIAS`: the signing key alias.
- `ANDROID_KEY_PASSWORD`: the signing key password.

On Windows PowerShell, create the Base64 value without modifying the keystore:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.keystore")) |
  Set-Clipboard
```

Keystore files are ignored by Git and must never be committed. Keep a secure
backup: future APK upgrades must be signed with the same key.

This key signs the application package. Each device's MeshCentral identity is
stored separately in Android Keystore; see
[Tunnel authentication](tunnel-authentication.md#pairing-identity).

## 1. Update the Android Version

Open `app/build.gradle` and update both values in `android.defaultConfig`.
The values below are examples; choose a new version and a `versionCode` greater
than the previous release:

```groovy
versionCode 31
versionName "1.0.24"
```

- Increase `versionCode` by at least one for every release. Google Play uses
  this integer to determine whether one build is newer than another, and it
  cannot be reused after an AAB has been uploaded.
- Set `versionName` to the version users should see. This repository uses
  numeric versions such as `1.0.24`.

Also update the version shown in `docs/overview.md` so the project snapshot
remains accurate.

## 2. Verify the Release Build

For production signing, set `ANDROID_KEYSTORE_PATH`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and `ANDROID_KEY_PASSWORD` in
the build environment. `ANDROID_KEYSTORE_PATH` points to the local keystore.
Without all four values, local release builds fall back to debug signing and
must not be published. The release workflow requires the production secrets.

From the repository root on Windows, build both release formats:

```powershell
.\gradlew.bat assembleRelease bundleRelease
```

The generated files are written beneath `app/build/outputs/`. Build outputs
are ignored by Git and must not be committed.

## 3. Commit and Push the Version Change

Commit the version change before creating the tag:

```powershell
git add app/build.gradle docs/overview.md
git commit -m "Bump Android version to 1.0.24"
git push origin HEAD
```

Replace `1.0.24` with the new `versionName` throughout these examples.

## 4. Create the Release Tag

Tag the commit using the exact `versionName`, without a `v` prefix:

```powershell
git tag 1.0.24
git push origin 1.0.24
```

For example, `versionName "1.0.24"` requires the tag `1.0.24`.

The **Android Release** workflow then:

1. Builds the release APK and AAB.
2. Confirms that the tag matches the built application's `versionName`.
3. Creates a draft release using the repository's `GITHUB_TOKEN`.
4. Attaches the versioned APK and AAB, `meshagent_android.apk`, and
   `agent-release.json`.

The manifest records the repository, tag, source commit, byte length and
full-file SHA384 of the signed APK. The fixed `meshagent_android.apk` filename
lets MeshCentral download the selected release without depending on the
versioned filename.

MeshCentral uses this APK for its Android installer download. Android app updates
use Google Play or APK installation; the native agent binary-update and
**Install and pin** controls do not install APKs.

## 5. Review and Publish

Review the assets and release notes, then publish the draft. Public release
downloads through MeshCentral do not require a personal access token. Publishing
the release does not change MeshCentral's default selection or deploy agents.

Keep the prerelease flag enabled for testing releases. MeshCentral's scheduled
checks exclude prereleases.

Do not replace published assets or move published tags. A changed binary needs
a new version, including signing changes. The workflow will not overwrite an
existing release.

## Manual Release

The workflow must be present on the default branch before it can be started
manually. Select an existing version tag, or use the GitHub CLI:

```powershell
gh workflow run android-release.yml --ref 1.0.24
```

Branch runs are rejected. See
[GitHub's manual workflow instructions](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow).

## Existing MeshCentral APK

Run **Actions > Migrate bundled Android agent > Run workflow** with the `legacy`
profile to prepare a draft `legacy-1.2.6` release of the APK previously bundled
with MeshCentral. The workflow handles the migration tag, retrieves the APK
from an immutable MeshCentral commit and verifies the size and SHA384 recorded
in `.github/release-migration.json`. It does not rebuild or sign the APK, so no
Android signing secrets are required for this migration.

Publish this draft before a MeshCentral package that uses it as a default.
Leave it excluded from GitHub's latest-release selection. The migration tag
identifies the packaging commit; the manifest records the source repository,
archive commit and original path of the preserved APK.
