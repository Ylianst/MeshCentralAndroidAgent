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

`DesktopInputInstrumentation` exercises navigation and text input through the
accessibility service against native controls. Run it on a disposable emulator
with the agent's accessibility service enabled. It replaces the activity's
content for the duration of the tests and does not need a server connection.

```sh
./gradlew -PmeshTestRunner=com.meshcentral.agent.DesktopInputInstrumentation assembleDebug assembleDebugAndroidTest
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.meshcentral.agent2.test/com.meshcentral.agent.DesktopInputInstrumentation
```

The checks cover extended arrow keys, Tab/Shift+Tab, Enter/Space, focused
container traversal, geometric fallback, repeated cursor selection, wheel
direction, ambiguous editors, custom-keyboard text input and both launcher entries. They do not establish
compatibility with Fire TV's launcher, vendor keyboards or MediaProjection
consent flow; those still need device testing.

For an end-to-end check, enroll the emulator with a local MeshCentral server
and use the browser's desktop viewer to open Android Settings, search by typing,
edit the query with arrows and Shift+arrows, navigate results with Tab and Enter,
and scroll in both directions. Check the Android navigation buttons, denial of
session consent, and a fresh approval after disconnecting and reconnecting.
Run the emulator with `-no-window -no-audio` and use a headless browser to avoid
taking the host's keyboard or mouse focus. An accessibility inspection probe
must use `UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`; the default
automation mode temporarily disables the service being tested.

Keep deployment-specific provisioning, credentials, device identifiers, and run
artifacts outside the repository. Shared tests should be reproducible using
documented dependencies and configuration available to other contributors.
