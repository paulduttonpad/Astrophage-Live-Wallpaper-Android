# Invert Particle Density Sliders and Double Particle Sizes

This plan involves inverting the logic for particle density settings so that higher slider values result in higher particle counts (making them more intuitive), and doubling the visual size of all particles in the renderer.

## Proposed Changes

### [Wallpaper Preferences]

#### [MODIFY] [WallpaperPreferences.kt](file:///C:/GIT/Astrophage-Live-Wallpaper-Android/app/src/main/java/com/paddigital/astrophagelivewallpaper/WallpaperPreferences.kt)
- Update default values for `foregroundDensity` and `backgroundDensity` to reflect the new inverted semantics (where higher values = more particles).
- The new defaults will be the mapped equivalent of the old defaults to maintain similar visual balance for new users.

### [Settings UI]

#### [MODIFY] [SettingsActivity.kt](file:///C:/GIT/Astrophage-Live-Wallpaper-Android/app/src/main/java/com/paddigital/astrophagelivewallpaper/SettingsActivity.kt)
- Update the display labels for "Foreground particles" and "Background particles" sliders.
- "High" will now appear at the right end of the slider (higher numeric value), and "Low" at the left end.

### [Renderer]

#### [MODIFY] [GpuAstrophageRenderer.kt](file:///C:/GIT/Astrophage-Live-Wallpaper-Android/app/src/main/java/com/paddigital/astrophagelivewallpaper/GpuAstrophageRenderer.kt)
- Update `rebuildParticleCounts` to invert the density configuration values before using them in calculations. This ensures that a high setting value results in a smaller divisor, thus more particles.
- Update `drawParticles` to double the size and minimum size thresholds for all particle types (Background, Astrophage, Bokeh, and Sparks).

## Verification Plan

### Automated Tests
- Build the project using `./gradlew assembleDebug` to ensure no syntax errors.

### Manual Verification
- Open Settings:
    - Verify "Foreground particles" slider shows "Low" at the left and "High" at the right.
    - Verify "Background particles" slider shows "Low" at the left and "High" at the right.
- Change settings and observe live wallpaper:
    - Increasing the slider should increase particle density.
    - Particles should appear significantly larger (double their previous diameter).
