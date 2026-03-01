# AGENTS.md

## Cursor Cloud specific instructions

This is an Android/Kotlin project ("Simple Computer Use") — an Android client for remote PC voice control. It has two Gradle modules: `:app` (main app) and `:vncviewer` (VNC library).

### Environment prerequisites

- **JDK 17** must be active (`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`). The VM ships with JDK 21 by default; JDK 17 is installed and set as default via `update-alternatives`.
- **Android SDK** at `/opt/android-sdk` with platform 34 and build-tools 34.0.0. Environment variables `ANDROID_HOME` and `ANDROID_SDK_ROOT` are set in `~/.bashrc`.
- `local.properties` (gitignored) must contain `sdk.dir=/opt/android-sdk`. The update script recreates it.

### Key commands

| Action | Command |
|---|---|
| Build debug APK | `./gradlew assembleDebug` |
| Run unit tests | `./gradlew test` |
| Run lint | `./gradlew lint` |
| Build release APK | `./gradlew assembleRelease` |

### Gotchas

- `config.properties` in the repo contains a hardcoded `sdk.dir` pointing to the original developer's machine. This file is overridden by `local.properties` (which is gitignored). Always ensure `local.properties` exists with the correct SDK path.
- `./gradlew lint` will fail with exit code 1 due to **49 pre-existing lint errors** in the codebase (e.g. `MissingSuperCall`). The lint tool itself works correctly.
- This is a pure Android app — there is no backend server in this repo. The companion server (`simple-computer-use`) is a separate project. End-to-end testing of the full product requires a real Android device/emulator and the external server, which are not available in the cloud VM.
- The debug APK is output at `app/build/outputs/apk/debug/app-debug.apk` (~10MB).
