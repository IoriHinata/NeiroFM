# NeiroFM

NeiroFM is an offline-first Android radio player with presenter-led transitions.

## Included in this release candidate

- Radio-style dark interface and custom adaptive launcher icon.
- Local music, podcast, and advertisement libraries selected from folders on the device.
- Random entry point on each explicit start; pause/resume keeps the current playback position.
- Three presenter delivery profiles: Alex, Maria, and Max.
- Presenter introductions before podcast and advertising audio; the player pauses for the introduction and resumes after speech completion.
- Hourly time announcements and periodic presenter links.
- Media3 background playback, media session, lock-screen controls, and Bluetooth media controls.
- Offline playback of all imported audio.

## Voice requirements

This build currently uses Android's installed TextToSpeech engine. Russian speech works offline only when an offline Russian voice is installed on the device. The app includes a shortcut to Android TTS voice installation/settings.

The sherpa-onnx neural voice runtime and bundled Russian model are **not yet part of this release candidate**. Do not interpret the presenter profiles as three independently trained neural voices. That integration is a required follow-up before claiming the fully self-contained neural-voice edition.

## Weather

A cached forecast can be stored locally; fresh weather cannot be fetched without internet. Weather caching is currently a data-layer feature, not a fully configured automatic forecast service.

## Build and tests

- JDK 17
- Android SDK platform 35
- Gradle 8.11.1
- `gradle testDebugUnitTest`
- `gradle assembleDebug`

GitHub Actions runs unit tests, builds an installable APK, and uploads the artifact. Pushing a `v*` tag publishes a GitHub Release with the APK attached.

## Compatibility

Minimum Android version: 8.0 (API 26). The APK is built as a universal package for common Android ABIs.
