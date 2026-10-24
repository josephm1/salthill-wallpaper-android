# Salthill Wallpaper for Android

A native live wallpaper and screensaver showing Blackrock, Salthill, Galway, with animated waves, weather, sunrise/sunset colours and modelled tides. Built in Kotlin using hardware Canvas drawing.

## Download and install

**[Download Salthill 0.2.5 preview APK](https://github.com/josephm1/salthill-wallpaper-android/releases/download/v0.2.5-preview/Salthill-0.2.5-preview.apk)** · [All releases](https://github.com/josephm1/salthill-wallpaper-android/releases)

Requires Android 8.0 or later. Download the APK, open it on Android, and allow installation from that browser/file manager if prompted. These are development-signed preview builds, not a Play Store release.

Open **Salthill**, choose **My location** or the **Salthill** preset, then use **Set live wallpaper** to open Android's wallpaper picker. The app also supports Android's charging/docked screensaver settings. Installing an update preserves the existing app settings.

## Features

- Native bitmap/path rendering, shared phase for front/back waves, and a foam crest aligned with the water edge.
- Distant, atmosphere-tinted boat with wind-driven bobbing and rocking.
- Day, overcast, sunrise/sunset and night scenes, with clouds, stars, an occasional shooting star, rain, snow and weather-dependent props.
- Approximate location only when requested, or a Salthill preset; no background location tracking.
- Local clock and sunrise/sunset selection; locally interpolated, rising/falling sea level.
- 10/15/30fps options, power-saver limiting, and animation stopped while hidden.
- One-hour weather and six-hour marine refreshes, shared across wallpaper, preview and screensaver.
- Persistent caches for multiple locations, offline forecast reuse and increasing retry delays with rate-limit handling.

A deterministic seven-day simulation with twelve hours visible each day made 98 successful API calls (84 weather + 14 marine). Errors and location changes can add calls; less home-screen visibility reduces them. This is a request-count test, not a battery measurement.

## Build and check

Install Android SDK platform 36 and JDK 17 or 21. Set `ANDROID_HOME` to your SDK, or specify `sdk.dir` in an untracked `local.properties` file.

```sh
./gradlew :app:assemblePreview :app:lintPreview :app:testDebugUnitTest
```

The APK is written to `app/build/outputs/apk/preview/app-preview.apk`. The preview variant enables R8 optimisation and resource shrinking. Local preview builds use the local development signing key; signing keys are not included in this repository. Published APKs should retain a consistent signing certificate for in-place updates.

With an emulator or device connected:

```sh
./gradlew :app:connectedDebugAndroidTest
adb install -r app/build/outputs/apk/preview/app-preview.apk
```

Prepared artwork is included, so Python is not required to build. To regenerate it after changing `artwork/`, install Pillow and run:

```sh
python3 tools/prepare-art.py
```

The original asset masks and cloud colour ramps are prepared offline; no image generation runs in the animation loop.

## Data and credits

Weather and modelled sea levels: [Open-Meteo](https://open-meteo.com/) and its [Marine API](https://open-meteo.com/en/docs/marine-weather-api). Attribution is also shown in the app. Marine sea level includes tides and weather effects; coastal accuracy is limited and the wallpaper is not for navigation.

Android app extracted from [josephm1/josephm1](https://github.com/josephm1/josephm1), including the original Blackrock artwork. This repository contains the Android app and its artwork/build tools; the web and Linux versions remain in the original project.

See [implementation and validation notes](docs/android-app.md) for the rendering, caching and location behaviour.
