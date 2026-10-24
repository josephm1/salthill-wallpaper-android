# Native Android wallpaper and screensaver

This project is a working Kotlin implementation using Android's hardware Canvas. It contains a live wallpaper, a charging/docked screensaver, an in-app preview and a settings screen. It does not embed a browser or video player.

## Install and use

Build/install the optimized preview APK as described below, then open **Salthill**:

1. Choose **My location** for a one-off approximate device location, or **Salthill** for the Blackrock/Salthill, Galway preset. Latitude/longitude entry is no longer necessary. Time updates automatically; the Salthill clock uses Europe/Dublin, while My location uses the device’s local time. Sunrise and sunset are calculated from the selected place’s forecast.
2. Leave animation at **15 fps · balanced**, choose **10 fps · economy** for fewer draws, or **30 fps · smooth**.
3. Use **Preview** to inspect the composition.
4. Choose **Set live wallpaper** and confirm through Android's own wallpaper picker. For charging/docked use, choose **Open Android screensaver settings**, select Salthill and use the device's screensaver scheduling controls.

The screensaver is Android's [DreamService](https://developer.android.com/reference/android/service/dreams/DreamService), whose availability and scheduling depend on the device. The wallpaper runs behind launcher icons using [WallpaperService](https://developer.android.com/reference/android/service/wallpaper/WallpaperService). Neither silently changes the device's wallpaper nor forces the screen to stay awake.

Weather works without a provider key through [Open-Meteo](https://open-meteo.com/en/docs). Sea level now loads automatically from [Open-Meteo Marine](https://open-meteo.com/en/docs/marine-weather-api), including modelled tides, and drives both crest height and rising/falling direction. The selected weather coordinates are used to request the nearby marine grid. Attribution is in the app.

Hourly sea-level samples interpolate locally between requests and are cached for six hours. Null samples are skipped; gaps longer than three hours are rejected instead of inventing a tide curve. The model uses global mean sea level as its datum and has limited coastal accuracy; this is a visual effect, not navigational tide information. Missing coverage or a failed first request leaves the crest at a neutral height.

An optional advanced HTTPS endpoint can override the automatic source with Stormglass shaped JSON:

```json
{"data":[{"time":"2026-10-22T08:00:00Z","height":1.2},{"time":"2026-10-22T14:00:00Z","height":3.1}]}
```

Times above are an example; actual responses must bracket the current time. Keep provider credentials on that server. Custom high/low extrema use smooth interpolation; automatic hourly marine levels use linear interpolation. Without valid data from either source, the sea uses its neutral level.

The location button requests approximate location permission only when tapped. Its one-off lookup is cancelled after 20 seconds or when the settings screen is paused. There is no background location permission or continuous tracking; use **Update my location** when you travel. No wake lock, foreground service or background polling is used. Saved forecasts appear immediately; stale weather refreshes when visible. The settings screen distinguishes saved weather, failed loading and the initial preview.

## Build

Requirements: Android SDK 36 and JDK 17 or 21. Gradle 8.9 is pinned by the checked-in wrapper. Set `ANDROID_HOME` to your SDK and `JAVA_HOME` to the JDK as needed.

```sh
./gradlew :app:assemblePreview :app:lintPreview :app:testDebugUnitTest
```

The output is `app/build/outputs/apk/preview/app-preview.apk`. This variant applies R8 optimization and resource shrinking. It uses a development signing key so it can be installed for testing; store publication will need a proper release signing key.

For development/debugging:

```sh
./gradlew :app:assembleDebug :app:lintDebug
# Select an emulator/device explicitly if more than one is connected:
ANDROID_SERIAL=emulator-5580 ./gradlew :app:connectedDebugAndroidTest
```

On Linux, `adb -s DEVICE_SERIAL install -r app/build/outputs/apk/preview/app-preview.apk` installs the preview. The included build does not install itself on physical devices.

Artwork is prepared from the original assets, at appropriate sizes, as lossless WebP copies and SVG path data. To regenerate it after editing the source artwork:

```sh
python3 tools/prepare-art.py
```

Run that command from the repository root; it requires Pillow. The prepared assets are checked in, so Pillow is unnecessary for a normal Android build.

## Drawing and appearance

`SceneRenderer` draws the sky, clouds, water and distant coast; then the diving tower, foreground masked water, foam, foreground rocks and weather props. Static artwork is decoded off the rendering thread and reused. There are no per-frame bitmap decodes or full-screen mask allocations.

Both water draws sample **one repeating BitmapShader with one phase and one projective transform**. Tide moves the foreground paths, rather than independently translating that layer's texture. The shader compresses wave width and height toward the horizon. Version 0.2 increases vertical texture scale 2.5× over the earlier preview, restoring more visible wave height near the diving board while retaining distant compression. Haze fades distant contrast; the boat is smaller and slightly faded to suit its distance. Water reverses by changing the signed velocity of the same bounded phase, so it keeps running indefinitely in either direction.

Foam is extracted from the upper contour of the original water path. It uses the same transform as the water mask, so tide/scaling changes cannot detach it. This replaces the old independently positioned crest bitmap.

Day/night, grey skies, sunrise/sunset transitions, rain, snow, wind-driven water/spinner, cold-weather hot chocolate and tide height/direction are supported. Visibility and wind direction remain in the weather model. The original unimplemented alert/season/bee/robin/spray ideas are not claimed as completed features.

## Power behavior

- Default 15 fps; 10 fps economy and 30 fps smooth settings. System battery saver caps drawing at 10 fps.
- Surface availability and visibility both gate rendering. Hide, pause, surface destruction and engine destruction remove pending frame callbacks. Resuming resets the elapsed-time baseline so hidden time does not jump the wave animation.
- Minute-scale sun/tide updates do not fetch weather on every frame. Weather is cached for one hour; tide forecasts for six hours while they cover the current time. Failed requests back off for 5/10/20/40/60 minutes (capped at one hour), with persisted deadlines and HTTP Retry-After support. Preview, wallpaper and screensaver share a process-wide cache and coalesced requests.
- All periodic app work stops when no scene/settings session is visible. An already-started network request can finish, bounded by connection/read timeouts; no new request is scheduled while hidden.
- Hardware Canvas draws the entire surface, as required by [lockHardwareCanvas](https://developer.android.com/reference/android/view/SurfaceHolder#lockHardwareCanvas()). Images and paths are reused. Cloud rendering culls offscreen sprites.

This follows Android's instruction that a wallpaper should only use CPU for animation while visible in [Engine.onVisibilityChanged](https://developer.android.com/reference/android/service/wallpaper/WallpaperService.Engine#onVisibilityChanged(boolean)). It avoids the earlier web repaint work, but actual battery consumption also depends on display brightness, refresh rate, wallpaper visibility and device GPU. No battery percentage or improvement factor is claimed without a physical-device comparison.

## Validation

The settings screen uses Material 3 View components, with location and motion choices, an automatic clock, and advanced tide settings tucked below the main actions. The Salthill preset (53.25682, -9.0910) is based on [Inland Fisheries Ireland’s Galway guide](https://www.fisheriesireland.ie/sites/default/files/migrated/docman/Galway%20Sea%20Angling%20Guide%20Web.pdf). Current-location queries use [LocationManagerCompat](https://developer.android.com/reference/androidx/core/location/LocationManagerCompat) and require only approximate access.

Nineteen JVM tests cover indefinite reverse phase wrapping, pause behavior, sun transitions, short/missing tide data and hourly/daily forecast selection. An Android emulator instrumentation test verifies real surface frames, a bounded default frame rate, **zero further frames while hidden**, and resumed drawing afterwards. Three additional instrumentation checks cover migration of the original default coordinates while preserving manually chosen places, saving Material location/motion controls with the Dublin clock, and a mock approximate-location lookup followed by switching back to Salthill. Two marine-data tests additionally check null sample parsing, legacy feed compatibility, and restoration of automatic tide data from disk into the renderer without a custom endpoint. Android lint and APK builds are also checked.

Remaining distribution work: long-duration measurements on representative physical phones, manufacturer-specific launcher/screensaver checks, minimum-SDK device coverage, and release signing. The current build is a testable preview rather than a Play Store release.


### Grey-day cloud colour correction (0.2.2)

Grey clouds now use the web palette (`#c2c2c2` through 50%, `#dfdfdf` at 75%, `#eeeeee` at the bottom), preserving each source image's alpha mask. The previous flat blue-grey `SRC_IN` tint discarded that vertical shading. The grey sky now holds `#a3a3a3` through 50%, followed by `#c3c3c3` at 75% and `#e2e2e2` at the horizon, matching the web gradient stops. Other themes and cloud placement are unchanged.

The nine grey cloud variants are generated offline by `scripts/prepare-android-art.py` and decoded once with the other artwork. Rendering uses the same culled bitmap draws, without a new pass, gradient filter or bitmap allocation per frame. This matches the web colour ramps; the native watercolour compositing and full-scene appearance are not claimed to be pixel-identical to CSS.


### Sunrise/sunset cloud colour correction (0.2.3)

Both sunrise and sunset use the web's golden-hour palette. Clouds hold neutral `#c3c3c3` through 70% of their height, then transition to pink `#ff5d8f` undersides. This replaces the flat pale-pink Android tint. The sky holds `#ff6392` through 70%, transitions to `#ffe45e` at 90%, and keeps that golden colour to the horizon. Its 80% opacity over the blue backdrop also follows the CSS sky. Cloud placement and motion remain the same.

Nine additional sunset cloud variants are generated offline and decoded alongside the existing day/grey artwork. This adds bitmap memory, with no additional per-frame drawing passes, image generation or cloud colour filters. The CSS cloud ramps and alpha masks were checked for all nine assets, and the preview APK, Android lint and JVM tests were checked. The palette comparison image shows source assets and sky ramps; it is not a physical-device screenshot or a claim of pixel-identical watercolour compositing.


### Reduced API calls and persistent cache (0.2.4)

Weather refreshes at most once per hour while the scene/settings are visible. Marine data keeps its six-hour interval. The existing three-day responses still drive local weather selection, sunrise/sunset and interpolated tides; a missing current-time forecast may refresh earlier. No new background polling, location tracking, server, account or API key is introduced.

The disk cache stores up to eight endpoint responses (normally four places), keyed separately by complete weather/marine request identities. Returning to a place reuses its forecast across process restarts. A custom tide-URL change does not invalidate weather. An interrupted location change cannot overwrite another endpoint's tide data. The previous single-place cache is imported when its source identity matches; newer cache data and failure backoff take precedence. Endpoint identities are hashed, avoiding storing custom URL credentials as cache keys.

Failures preserve the last usable forecast. Retry delays increase from five minutes to an hour and survive process restarts; HTTP Retry-After may extend that delay. Permanent 400/401/403/404 responses and valid marine responses with no local sea-level coverage defer retries for six hours. Cached forecast coverage is checked as well as download age, and clock rollback does not leave a future backoff deadline stuck.

The deterministic request-count test runs seven days with twelve hours visible per day and confirms 84 weather requests plus 14 marine requests: 98/week versus the previous estimate of 182/week (about 46% fewer). This assumes successful requests, one location, and no expired/missing coverage; real usage may be lower when the wallpaper is hidden, or higher with errors/location changes. Tests also exercise multi-place cache reuse after restart, independent endpoints, partial failures, backoff and rate limits, hidden-session gating, unsupported marine coverage, bounded eviction, corruption recovery and legacy import. These are local simulated requests, not live API traffic.

Version 0.2.4 validation also passed three instrumentation checks on an isolated Android 16 emulator with Wi-Fi and mobile data disabled: marine parser compatibility, restoring the legacy cached marine forecast into the repository/renderer, and native surface rendering with bounded frame rate, pause while hidden, and resume. The emulator and build daemon were shut down after validation.


### Shooting star (0.2.5)

A short shooting star appears at night after four visible seconds, then every thirty visible seconds. It uses a preallocated gradient paint and two tiny native Canvas draws during a 0.65-second streak. It shares the existing animation clock, stops while hidden, resets for daytime, and makes no network requests. Its position follows the visible sky width so it remains visible in portrait.

Remaining visual differences from the web scene include shoreline colour grading, small prop orientations and the native water/rain/snow rendering. Bee, robin and wave-spray assets are not drawn on Android; those extras are also hidden in the current web scene. Cloud motion remains fixed-direction in both versions.

The distant boat uses a theme-cached atmosphere filter: restrained saturation by day, stronger grey-day desaturation and dim blue-grey at night. Wind speed now drives smoothly varying bob/roll strength and swell period, with bounded motion that keeps the boat distant. Wind updates preserve phase; hidden time does not advance it. This reuses the existing bitmap draw and adds no API calls or independent timer.
