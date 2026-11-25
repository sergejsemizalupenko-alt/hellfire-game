# INTERNET WAKE Lite

Android application for periodic network/server availability monitoring.

## Current baseline

- Stable development branch: `internet-wake-lite`
- App version: `2.5.3-lite`
- Android application ID: `com.internetwake.app.lite.v253`
- Minimum Android version: Android 8.0 (API 26)
- Build: Android Gradle project, Java 17, Gradle 8.9

## Build APK

The GitHub Actions workflow **Build INTERNET WAKE Lite 2.5.3 APK** runs only when
`internet-wake-lite` is updated, or when started manually from the Actions tab.

The generated debug APK is uploaded as the artifact
`internet-wake-lite-2.5.3-apk`.

## Repository rules

Use `internet-wake-lite` as the only active development line for the Lite app.
Do not base new changes on the legacy Test or 2.4 branches.

A frozen copy of the starting 2.5.3 baseline is kept in
`archive/lite-2.5.3-baseline-2026-11-25`.
