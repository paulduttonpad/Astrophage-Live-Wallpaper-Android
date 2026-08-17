# Upgrade an existing Astrophage V1 Android Studio project to V2

This upgrade assumes your real application ID is:

`com.paddigital.astrophagelivewallpaper`

Do not change that value. Keeping the same application ID and signing key is what allows Android / Google Play to treat V2 as an update of V1.

## 1. Back up the V1 project

Close Android Studio or commit the current project to Git first.

## 2. Confirm the package

In `app/build.gradle.kts`, confirm both values remain:

```kotlin
namespace = "com.paddigital.astrophagelivewallpaper"
...
applicationId = "com.paddigital.astrophagelivewallpaper"
```

Do not substitute the old `com.plvsultra...` package from early development builds.

## 3. Increase the version

In `app/build.gradle.kts`, change only the version values (unless your current Play versionCode is already 2 or higher):

```kotlin
versionCode = 2
versionName = "2.0"
```

For Google Play, `versionCode` must be greater than the highest versionCode you have already uploaded. If your V1 release used a value other than 1, use the next available integer instead.

## 4. Replace these V1 Kotlin files

Under:

`app/src/main/java/com/paddigital/astrophagelivewallpaper/`

replace:

- `AstrophageWallpaperService.kt`
- `WallpaperPreferences.kt`
- `SettingsActivity.kt`
- `MainActivity.kt` (recommended; only UI text/description changes are material)

## 5. Add the new renderer

Add:

- `GpuAstrophageRenderer.kt`

into the same package directory.

This is the new OpenGL ES renderer and contains the GPU trail framebuffer, shaders and time-based simulation.

## 6. Do not remove your existing icons or signing setup

Do not replace the entire `res` directory and do not replace your release signing configuration.

Your existing launcher foreground/background/legacy icons can remain exactly as they are.

## 7. Optional manifest declaration

V2 uses OpenGL ES 2.0. You may add this alongside the live-wallpaper feature in `AndroidManifest.xml`:

```xml
<uses-feature
    android:glEsVersion="0x00020000"
    android:required="true" />
```

The complete V2 reference project includes this declaration.

## 8. Check wallpaper.xml

Your existing `app/src/main/res/xml/wallpaper.xml` should point at the real package:

```xml
android:settingsActivity="com.paddigital.astrophagelivewallpaper.SettingsActivity"
```

If it already does, leave it alone.

## 9. Sync and rebuild

In Android Studio:

1. `File` -> `Sync Project with Gradle Files`
2. `Build` -> `Clean Project`
3. `Build` -> `Rebuild Project`

## 10. Install as an upgrade

Run the `app` configuration against the same phone that has V1 installed.

If you are using a debug build, Android Studio must sign V2 with the same debug key that signed the installed V1.

If you are upgrading a release / Google Play build, it must use the same app-signing identity as V1.

Do not uninstall V1 first if you want to verify that the update and preferences migrate correctly.

## 11. Verify migration

Open `Astrophage Settings` after upgrading.

Expected behavior:

- V1 30 FPS battery saver -> V2 Battery Saver
- V1 60 FPS -> V2 Balanced
- density/trail/touch/parallax choices remain intact

## 12. Test power behavior

Start with `Balanced`.

Test:

- leave the home screen untouched for at least 10 seconds
- touch and drag one finger
- use several fingers
- swipe between home-screen pages
- open an opaque app for a while, then return to the launcher

The wallpaper should stop rendering while hidden, idle at a lower FPS, and temporarily increase its frame rate during interaction.

For the strongest battery reduction, select `Battery Saver`.
