# NeiroFM

Android offline-first radio with a neon-purple radio interface and three presenter profiles.

## Implemented

- Compose interface based on the supplied NeiroFM visual reference: near-black background, purple glow/waves, large headphone artwork, central transport controls, volume slider, current broadcast card, and bottom navigation.
- Custom purple launcher icon.
- Media3 playback service and MediaSession. Current media metadata includes NeiroFM artwork for Android lock-screen/media controls.
- Local music, podcast, and ad audio libraries.
- Random start position on a new broadcast start; pause/resume preserves the current queue position.
- Time-of-day shows:
  - 06:00–11:00 Neiro Утро — Maria
  - 11:00–17:00 Дневная волна — Alex
  - 17:00–22:00 Вечерний маршрут — Maria
  - 22:00–06:00 После полуночи — Max
- Automatically generated topic-based presenter monologues at randomized intervals.
- TXT import using Android document picker. Script type is inferred from filename keywords:
  - `podcast_*.txt`, `topic_*.txt`, `story_*.txt` → podcast
  - `reklama_*.txt`, `advert_*.txt`, `promo_*.txt` → advertisement
  - `pogoda_*.txt`, `weather_*.txt` → weather
  - `utro_*.txt`, `morning_*.txt` → morning show
  - `noch_*.txt`, `night_*.txt` → night show
  - `greeting_*.txt`, `hello_*.txt` → greeting
  - `time_*.txt`, `hour_*.txt` → time announcement
- Imported podcast, advertising, and weather scripts are scheduled into the broadcast. Advertisements and weather bulletins are only spoken when matching TXT scripts have been imported.
- Presenter speech pauses music and resumes it after the speech ends.
- Three optional Russian offline neural voice models powered by sherpa-onnx/Piper (Ruslan, Irina, Dmitri). Install them from Settings while connected to the internet once; after download, synthesis runs locally without cloud services. Model download is optional and is not bundled in the APK.
- Android system TTS remains as fallback until neural models are installed.

## Neural voice setup

Open Settings → Локальные нейроголоса. The app downloads three Russian VITS/Piper medium models into private app storage. The models are loaded locally after installation and persist across app restarts. A first download requires internet and sufficient free storage. The sherpa-onnx runtime is integrated into the APK; model weights are downloaded separately.

Model cards and sample recordings:
- https://k2-fsa.github.io/sherpa/onnx/tts/all/Russian/vits-piper-ru_RU-ruslan-medium.html
- https://k2-fsa.github.io/sherpa/onnx/tts/all/Russian/vits-piper-ru_RU-irina-medium.html
- https://k2-fsa.github.io/sherpa/onnx/tts/all/Russian/vits-piper-ru_RU-dmitri-medium.html

Check the license terms of each selected voice model before redistribution.

## Build and test

- JDK 17
- Android SDK platform 35
- Gradle 8.11.1
- `gradle testDebugUnitTest`
- `gradle clean assembleDebug`

GitHub Actions runs unit tests and builds an installable debug APK. A `v*` tag publishes the APK as a GitHub Release.

## Known limitations

- Weather bulletins are user-provided TXT content; live weather updates cannot be refreshed offline.
- Generated podcast scripts use an offline topic/template generator, not an internet-connected fact-checking model. They are conversational entertainment, not a source of verified current facts.
- The lock-screen UI is provided through Android's standard MediaSession/media notification presentation. Its exact visual appearance depends on the Android version and manufacturer skin.
- The first neural voice model download requires internet; all later synthesis is local.
