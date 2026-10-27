# MeshCentral Android Agent

MeshCentral Android Agent connects an Android device to a
[MeshCentral](https://www.meshcentral.com) server for remote monitoring and
support. It is a native Kotlin application and is separate from the MeshCentral
agents used on Windows, Linux, macOS, and FreeBSD.

Pair a device by scanning a MeshCentral QR code, opening an `mc://` pairing
link, or entering the link manually. After enrollment, the app maintains an
authenticated connection to the server and can:

- Report device, network, storage, and battery information.
- Share the device screen, and control it once the bundled accessibility
  service is enabled.
- Browse and transfer media and files available to the app.
- Receive server notifications and a limited set of console commands.
- Approve or reject MeshCentral push-based two-factor authentication requests.

Remote desktop uses Android's screen-capture consent dialog, or accessibility
screenshots on Android 11 and later. Enabling the bundled Accessibility Remote
Control service adds taps, drags, long presses, scrolling, typing and D-pad
navigation. On older Android versions, accessibility input works alongside
MediaProjection capture, which still needs local approval. Android shows a
persistent notification while a session is active, consent prompts follow the server's
policy and the app's Automatic Consent setting, and the user can deny or stop
sharing at any time.

The app also has an Android TV launcher entry. See the [TV controls and
limitations](docs/remote-desktop.md#tv-controls) before testing on Fire TV or
other Android TV devices.

## Install

Install MeshAgent for Android from [Google Play](https://play.google.com/store/apps/details?id=com.meshcentral.agent2).

Or download the APK or AAB from the
[latest release](https://github.com/Ylianst/MeshCentralAndroidAgent/releases/latest).

## Requirements

- Android 6.0 (API 23) or later.
- A MeshCentral server configured to enroll Android agents.
- Android Studio or JDK 17 and an Android SDK for local development.

## Build

Open the repository in Android Studio, or build and test it from the repository
root:

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

The debug APK is generated under `app/build/outputs/apk/debug/`. Production
releases require a dedicated signing keystore; see the release guide below.

## Documentation

- [Documentation site](https://ylianst.github.io/MeshCentralAndroidAgent/) -
	published project documentation.
- [Documentation home](docs/index.md) - introduction, installation, key
	capabilities, and links to all project resources.
- [Repository overview](docs/overview.md) - architecture, components, project
	configuration, and development notes.
- [Testing](docs/testing.md) - portable checks, reports, and coverage limits.
- [Remote desktop](docs/remote-desktop.md) - screen-capture flow, consent,
	encoding, and Android platform limitations.
- [Tunnel authentication](docs/tunnel-authentication.md) - control-channel
	authentication, certificate pinning, and relay tunnel trust.
- [Push two-factor authentication](docs/two-factor-authentication.md) - FCM
	request validation, approval flow, and lifecycle behavior.
- [Creating a release](docs/releasing.md) - versioning, signing, GitHub Actions,
	and publishing APK and AAB artifacts.

## Community

- [MeshCentral website](https://www.meshcentral.com)
- [MeshCentral subreddit](https://www.reddit.com/r/MeshCentral/)
