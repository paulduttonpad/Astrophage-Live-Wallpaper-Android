package com.paddigital.astrophagelivewallpaper

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.sqrt

object WallpaperPreferences {
    const val PREFS = "astrophage_wallpaper"
    const val KEY_FOREGROUND = "foreground_density"
    const val KEY_BACKGROUND = "background_density"
    const val KEY_TRAILS = "trail_strength"
    const val KEY_TOUCH = "touch_enabled"
    const val KEY_PARALLAX = "parallax_enabled"

    // V1 key retained solely so existing installs can be migrated in-place.
    const val KEY_FPS = "fps_limit"
    const val KEY_POWER_MODE = "power_mode_v2"

    enum class PowerMode(
        val storageValue: String,
        val idleFps: Int,
        val interactionFps: Int,
        val preferredTrailScale: Float,
        val maxTrailPixels: Float,
        val foregroundMultiplier: Float,
        val backgroundMultiplier: Float
    ) {
        BATTERY_SAVER("battery", 15, 30, 0.50f, 900_000f, 0.75f, 0.60f),
        BALANCED("balanced", 20, 60, 0.70f, 1_600_000f, 0.90f, 0.80f),
        MAX_QUALITY("quality", 30, 60, 1.00f, 4_500_000f, 1.00f, 1.00f);

        fun trailScale(width: Int, height: Int): Float {
            val pixelCount = (width.toLong() * height.toLong()).coerceAtLeast(1L).toFloat()
            val capScale = sqrt(maxTrailPixels / pixelCount)
            return minOf(1f, preferredTrailScale, capScale).coerceAtLeast(0.35f)
        }

        companion object {
            fun fromStorage(value: String?): PowerMode =
                entries.firstOrNull { it.storageValue == value } ?: BALANCED
        }
    }

    data class Config(
        val foregroundDensity: Int = 50,
        val backgroundDensity: Int = 1050,
        val trailAlpha: Float = 0.18f,
        val touchEnabled: Boolean = true,
        val parallaxEnabled: Boolean = true,
        val powerMode: PowerMode = PowerMode.BALANCED
    )

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun migratePowerModeIfNeeded(p: SharedPreferences): PowerMode {
        if (p.contains(KEY_POWER_MODE)) {
            return PowerMode.fromStorage(p.getString(KEY_POWER_MODE, PowerMode.BALANCED.storageValue))
        }

        // V1 exposed a single 30/60 FPS switch. Preserve the user's intent:
        // 30 FPS battery-saver -> Battery Saver, 60 FPS -> Balanced.
        val oldFps = p.getInt(KEY_FPS, 60)
        val migrated = if (oldFps <= 30) PowerMode.BATTERY_SAVER else PowerMode.BALANCED
        p.edit().putString(KEY_POWER_MODE, migrated.storageValue).apply()
        return migrated
    }

    fun load(context: Context): Config {
        val p = prefs(context)
        return Config(
            foregroundDensity = p.getInt(KEY_FOREGROUND, 38).coerceIn(24, 64),
            backgroundDensity = p.getInt(KEY_BACKGROUND, 700).coerceIn(350, 1400),
            trailAlpha = p.getFloat(KEY_TRAILS, 0.18f).coerceIn(0.06f, 0.45f),
            touchEnabled = p.getBoolean(KEY_TOUCH, true),
            parallaxEnabled = p.getBoolean(KEY_PARALLAX, true),
            powerMode = migratePowerModeIfNeeded(p)
        )
    }
}
