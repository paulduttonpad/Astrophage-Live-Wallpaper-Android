package com.paddigital.astrophagelivewallpaper

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(48), dp(24), dp(32))
            setBackgroundColor(Color.BLACK)
        }

        root.addView(TextView(this).apply {
            text = "ASTROPHAGE"
            textSize = 32f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(TextView(this).apply {
            text = "Interactive Petrova-line Android live wallpaper"
            textSize = 16f
            setTextColor(Color.rgb(210, 210, 210))
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(28))
        })

        root.addView(Button(this).apply {
            text = "Preview / Set Live Wallpaper"
            setOnClickListener { openWallpaperPreview() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        root.addView(Button(this).apply {
            text = "Astrophage Settings"
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            topMargin = dp(14)
        })

        root.addView(TextView(this).apply {
            text = "Touch and multi-touch interaction is enabled on launchers that forward live-wallpaper touch events. Home-screen swipes also drive subtle parallax through the field."
            textSize = 14f
            setTextColor(Color.rgb(170, 170, 170))
            setPadding(0, dp(28), 0, 0)
        })

        setContentView(root)
    }

    private fun openWallpaperPreview() {
        val component = ComponentName(this, AstrophageWallpaperService::class.java)
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }
}
