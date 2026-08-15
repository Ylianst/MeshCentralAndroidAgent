# Creating an Android Release

GitHub Actions builds and publishes an APK and AAB when a version tag is
pushed. The Git tag and GitHub Release name must exactly match the Android
`versionName` in `app/build.gradle`.

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

## 1. Update the Android Version

Open `app/build.gradle` and update both values in `android.defaultConfig`:

```groovy
versionCode 29
versionName "1.0.22"
```

- Increase `versionCode` by at least one for every release. Google Play uses
  this integer to determine whether one build is newer than another, and it
  cannot be reused after an AAB has been uploaded.
- Set `versionName` to the version users should see. This repository uses
  semantic versions such as `1.0.22`.

Also update the version shown in `docs/overview.md` so the project snapshot
remains accurate.

## 2. Verify the Release Build

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
git commit -m "Bump Android version to 1.0.22"
git push origin HEAD
```

Replace `1.0.22` with the new `versionName` throughout these examples.

## 4. Create the Release Tag

Tag the commit using the exact `versionName`, without a `v` prefix:

```powershell
git tag 1.0.22
git push origin 1.0.22
```

For example, `versionName "1.0.22"` requires the tag `1.0.22`. A tag such as
`v1.0.22` will fail the workflow's version check.

The **Android Release** workflow then:

1. Builds the release APK and AAB.
2. Confirms that the tag matches the built application's `versionName`.
3. Creates a GitHub Release named `1.0.22`.
4. Attaches `MeshCentral-Agent-1.0.22.apk` and
   `MeshCentral-Agent-1.0.22.aab` to the release.

## Manual Release

The workflow can also be started from **Actions > Android Release > Run
workflow**. Select the branch or commit containing the desired version bump.
The workflow reads `versionName` from that build and uses it as the release tag
and GitHub Release name.

Do not manually run the workflow for a version that has already been released
unless the existing release assets are intentionally being replaced.