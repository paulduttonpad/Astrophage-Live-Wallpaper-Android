# Astrophage Android Live Wallpaper v1

This is a native Android Live Wallpaper adaptation of the supplied **App-Astrophage-v2** p5.js project.

## What is included

- Native `WallpaperService` implementation for Android.
- Astrophage foreground particles with soft additive red glow.
- Distant background Astrophage field.
- Foreground bokeh and occasional white-hot sparks.
- Long-exposure style trails.
- Multi-touch attraction, swirl and close-range repulsion.
- Alternating swirl direction for multiple fingers.
- Home-screen page swipe response using wallpaper offset callbacks.
- Fallback tap pulse for launchers that report wallpaper tap commands.
- Renderer automatically stops when the wallpaper is not visible.
- Settings for:
  - foreground density
  - background density
  - trail fade
  - touch interaction
  - home-screen swipe/parallax response
  - 60 FPS / 30 FPS battery saver
- The original p5.js files are retained under `original-p5js/` for reference.

## Important Android touch behaviour

Android live wallpapers can request raw touch events with `WallpaperService.Engine.setTouchEventsEnabled(true)`. The wallpaper also receives launcher page offset changes through `onOffsetsChanged()`.

This means Astrophage can react while the live wallpaper is actually being shown, including supported home-screen launchers and launcher surfaces such as translucent app drawers that continue to show/forward to the wallpaper.

A live wallpaper **cannot legitimately intercept touches inside an ordinary opaque foreground app once Android has hidden the wallpaper**. Those touches belong to the foreground app. Supporting that globally would require an overlay/accessibility-style mechanism and would no longer be normal live-wallpaper behaviour. This project deliberately does not use such a mechanism.

Launcher behaviour varies slightly by device. Android notes that wallpaper touch events are driven by the application the user is interacting with, so some launchers may deliver fewer move events than others.

## Build

Recommended environment:

- Current Android Studio
- Android SDK 36 installed
- JDK 17 or newer configured for Gradle

The project is configured with:

- Android Gradle Plugin 9.4.0
- AGP 9.x built-in Kotlin support (no separate `kotlin-android` plugin)
- `compileSdk = 36`
- `targetSdk = 36`
- `minSdk = 26`

Open this directory as an Android Studio project and allow Gradle sync to complete.

Then build with **Build > Build APK(s)**, or run the `app` configuration on a physical Android device.

If you build from a standalone command line rather than Android Studio, use Gradle 9.6.x or newer compatible with AGP 9.4. A Gradle Wrapper binary is not bundled in this source package.

## Install and activate

1. Install/run the app on the Android phone.
2. Open **Astrophage Live Wallpaper**.
3. Tap **Preview / Set Live Wallpaper**.
4. Confirm Astrophage as the wallpaper.
5. Use **Astrophage Settings** either from the app or the wallpaper preview settings button.

## Main source files

- `app/src/main/java/com/plvsultra/astrophage/wallpaper/AstrophageWallpaperService.kt`
  - live wallpaper engine
  - renderer
  - foreground/background particle physics
  - touch handling
  - home-screen offset interaction
- `MainActivity.kt`
  - launches the system live-wallpaper preview
- `SettingsActivity.kt`
  - wallpaper tuning controls
- `WallpaperPreferences.kt`
  - shared settings

## Performance notes

The supplied p5.js version already used several good optimisations. The Android port keeps the same principles:

- scalar particle physics rather than vector allocations
- cached glow bitmaps rather than per-particle blur operations
- inexpensive rectangle rendering for the distant field
- flow/noise recalculation for only one fifth of foreground particles each frame
- background interaction calculations every second frame
- bounded foreground and background particle populations
- reduced-resolution trail buffer on very high-resolution displays
- no render loop while the wallpaper is invisible

The default is visually close to the supplied p5.js build. On a phone where power use is more important, select the 30 FPS option and/or reduce particle density.
