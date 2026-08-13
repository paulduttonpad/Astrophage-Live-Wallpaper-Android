package com.paddigital.astrophagelivewallpaper

import android.content.Context
import android.content.SharedPreferences

object WallpaperPreferences {
    const val PREFS = "astrophage_wallpaper"
    const val KEY_FOREGROUND = "foreground_density"
    const val KEY_BACKGROUND = "background_density"
    const val KEY_TRAILS = "trail_strength"
    const val KEY_TOUCH = "touch_enabled"
    const val KEY_PARALLAX = "parallax_enabled"
    const val KEY_FPS = "fps_limit"

    data class Config(
        val foregroundDensity: Int = 38,
        val backgroundDensity: Int = 700,
        val trailAlpha: Float = 0.18f,
        val touchEnabled: Boolean = true,
        val parallaxEnabled: Boolean = true,
        val fpsLimit: Int = 60
    )

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): Config {
        val p = prefs(context)
        return Config(
            foregroundDensity = p.getInt(KEY_FOREGROUND, 38).coerceIn(24, 64),
            backgroundDensity = p.getInt(KEY_BACKGROUND, 700).coerceIn(350, 1400),
            trailAlpha = p.getFloat(KEY_TRAILS, 0.18f).coerceIn(0.06f, 0.45f),
            touchEnabled = p.getBoolean(KEY_TOUCH, true),
            parallaxEnabled = p.getBoolean(KEY_PARALLAX, true),
            fpsLimit = p.getInt(KEY_FPS, 60).let { if (it <= 30) 30 else 60 }
        )
    }
}
