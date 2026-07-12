# SnoreMask

An Android app that masks a partner's snoring with adaptive brown noise played
through the non-snoring partner's Bluetooth earbuds.

The phone's microphone listens for snoring. A low, continuous "quiescent" brown
noise plays all night; when snoring is detected the masker **swells gently** to
cover it, then **fades back to the baseline** when snoring stops.

> Note: this is **masking**, not phase cancellation. True out-of-phase
> cancellation is impossible through a phone-mic → Bluetooth → earbuds path
> (Bluetooth latency alone is ~150–300 ms vs. the sub-millisecond alignment
> cancellation requires). Masking needs no phase alignment and works well.

## Status

**v0.1 — toolchain check.** A Compose Hello World that confirms the build/test
environment works. No audio yet. UI shows a Start/Stop placeholder and a
"quiescent brown-noise level" slider to seed the real design.

## Project layout

```
app/src/main/java/com/snoremask/app/MainActivity.kt   # Compose UI
app/src/main/AndroidManifest.xml
app/src/main/res/...                                   # strings, theme, icon
app/build.gradle.kts                                   # module build config
build.gradle.kts / settings.gradle.kts                 # project build config
```

## Toolchain

- Gradle 8.9, Android Gradle Plugin 8.7.2, Kotlin 2.0.21
- compileSdk / targetSdk 35, minSdk 26 (Android 8.0+)
- Requires a JDK 17+ (this machine has Temurin JDK 21)
- Android SDK at `%LOCALAPPDATA%\Android\Sdk`

## Building from the command line

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.7.6-hotspot"
.\gradlew.bat assembleDebug
```

The APK lands at `app\build\outputs\apk\debug\app-debug.apk`.

## Testing

**On a physical phone (recommended — needed for Bluetooth/mic later):**
1. Enable Developer Options (tap Build Number 7× in Settings → About phone).
2. Either copy the APK to the phone and tap to install (allow "install unknown
   apps"), or `adb install -r app-debug.apk` over USB.

**On an emulator:** install Android Studio, open this folder, and Run. Android
Studio's Device Manager creates an emulator for you.

## Roadmap

- v0.2 — foreground service + brown-noise generator + continuous baseline playback
- v0.3 — mic capture + energy-threshold snore trigger (swell/fade envelope)
- v0.4 — spectral + rhythm detection to reduce false triggers
- v0.5 — settings (sensitivity, baseline level, max volume cap), Bluetooth routing
- later — Oboe/NDK audio engine if latency demands it
```
