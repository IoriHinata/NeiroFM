# NeiroFM

Offline-first Android radio application.

## Product requirements

- Kotlin Android app with a radio-style interface.
- Local music library and configurable music folder.
- Random broadcast entry point on each explicit start; pause/resume preserves position.
- Multiple presenter profiles and an offline speech engine abstraction.
- Scheduled presenter links, hourly time announcements, local advertisements and podcasts.
- Cached weather bulletin; live weather is unavailable without a network connection.
- Background playback through a MediaSession foreground service with lock-screen and Bluetooth controls.
- Broad Android device compatibility; adaptive performance for memory-constrained phones.

## Build

Open the project in Android Studio or run `./gradlew assembleDebug` with JDK 17 and Android SDK installed.

The project is under active development. A successful CI build and a distributable APK will be reported only after verification.
