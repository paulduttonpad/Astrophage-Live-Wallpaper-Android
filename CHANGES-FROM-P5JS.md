# Port notes

The original sketch is preserved under `original-p5js/`. The wallpaper itself is not a WebView: its particle renderer has been ported to Kotlin and draws directly into the Android wallpaper surface.

Key mapping from the supplied files:

- `sketch.js` -> render loop, trail fade, Petrova flow, attractor handling and density logic in `AstrophageWallpaperService.kt`
- `ball.js` -> native `Ball` class
- `backgroundAstrophage.js` -> native `BackgroundAstrophage` class
- radial glow sprites -> pre-rendered Android `Bitmap` radial gradients
- p5 Perlin flow -> lightweight native 3D value-noise field, staggered at the same 1/5 cadence
- browser touch list -> Android `MotionEvent` multi-pointer handling
- browser resize -> wallpaper `SurfaceHolder` resize handling
- page movement -> Android wallpaper `onOffsetsChanged()` parallax impulse
