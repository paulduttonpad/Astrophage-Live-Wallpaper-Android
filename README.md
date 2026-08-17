# Astrophage Android Live Wallpaper v2

Application ID / namespace: `com.paddigital.astrophagelivewallpaper`

Version: `2.0` (`versionCode 2` in this reference project)

## What changed in v2

V2 replaces the V1 software Canvas/Bitmap rendering path with a native OpenGL ES 2.0 renderer.

- GPU procedural Astrophage glow; no per-particle CPU glow Bitmap drawing.
- GPU additive particle composition.
- GPU ping-pong framebuffer trails: one texture is faded while the next frame is composed into the other.
- Adaptive frame pacing instead of a permanent 30/60 FPS loop.
- Android `Surface.setFrameRate()` hint on API 30+.
- Time-based particle motion so lower idle FPS does not slow the simulation.
- Reduced trail render resolution in Battery Saver / Balanced modes.
- Power-mode particle-count scaling.
- Render loop still stops when the live wallpaper is hidden.
- Existing V1 touch/multi-touch and launcher-page offset interaction retained.
- Up to 10 touch attractors, with nearby touches merged to avoid redundant work.
- V1 preferences retained. The old FPS preference is migrated automatically.

## Power modes

### Battery Saver
- 15 FPS idle
- 30 FPS during/recently after interaction
- 50% preferred trail scale (also pixel-capped)
- reduced foreground/background population

### Balanced (default)
- 20 FPS idle
- up to 60 FPS during/recently after interaction
- 70% preferred trail scale (also pixel-capped)
- modest particle reduction

### Maximum Quality
- 30 FPS idle
- up to 60 FPS during/recently after interaction
- up to full-resolution GPU trails, subject to a high pixel cap
- full particle population

## V1 preference migration

The SharedPreferences file remains `astrophage_wallpaper`.

- existing foreground density: preserved
- existing background density: preserved
- existing trail strength: preserved
- touch setting: preserved
- launcher parallax setting: preserved
- old 30 FPS selection: migrates to Battery Saver
- old 60 FPS selection: migrates to Balanced

## Upgrade recommendation

If you already have V1 in Android Studio, use the separate `Astrophage-v2-upgrade-files.zip` package. It avoids replacing icons, signing setup, themes, or any other project-specific resources.
