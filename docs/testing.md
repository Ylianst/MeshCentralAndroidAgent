# Testing

Use JDK 17 and the Android SDK required by `app/build.gradle`. Configure the SDK
through Android Studio, `ANDROID_HOME`, or an untracked `local.properties` file.

## Run the checks

From the repository root on Linux or macOS:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

On Windows:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

The unit tests in `app/src/test/` run on the JVM. They need no connected device,
emulator, MeshCentral server, or account. Their dependencies use
`testImplementation` and are excluded from the application APK.

Coverage includes clipboard JSON encoding, desktop input and packet validation,
session consent and permissions, file-command ordering, upload storage, tile
hashing, and pending frame refreshes.

Gradle writes the unit-test report to
`app/build/reports/tests/testDebugUnitTest/index.html` and the lint report to
`app/build/reports/lint-results-debug.html`.

## Device validation

JVM tests do not validate Android clipboard access, accessibility capture,
MediaProjection approval, bitmap codecs, or communication with a live server.
Those paths also need validation on an Android device or emulator connected to
a test MeshCentral instance.

Keep deployment-specific provisioning, credentials, device identifiers, and run
artifacts outside the repository. Shared tests should be reproducible using
documented dependencies and configuration available to other contributors.
