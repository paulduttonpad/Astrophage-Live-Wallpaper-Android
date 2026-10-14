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

        root.addView(
            TextView(this).apply {
                text = getString(R.string.main_title)
                textSize = 32f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        root.addView(
            TextView(this).apply {
                text = getString(R.string.main_description)
                textSize = 16f
                setTextColor(Color.rgb(210, 210, 210))
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(28))
            },
        )

        root.addView(
            Button(this).apply {
                text = getString(R.string.main_preview_button)
                setOnClickListener { openWallpaperPreview() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)),
        )

        root.addView(
            Button(this).apply {
                text = getString(R.string.main_settings_button)
                setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
                topMargin = dp(14)
            },
        )

        root.addView(
            TextView(this).apply {
                text = getString(R.string.main_footer)
                textSize = 14f
                setTextColor(Color.rgb(170, 170, 170))
                setPadding(0, dp(28), 0, 0)
            },
        )

        setContentView(root)
    }

    companion object {
        private const val REQUEST_CODE_SET_WALLPAPER = 1001
    }

    private var wasWallpaperSetBefore = false

    private fun isWallpaperSet(): Boolean {
        val wm = WallpaperManager.getInstance(this)
        return wm.wallpaperInfo?.packageName == packageName
    }

    private fun openWallpaperPreview() {
        wasWallpaperSetBefore = isWallpaperSet()
        val component = ComponentName(this, AstrophageWallpaperService::class.java)
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
        }
        try {
            startActivityForResult(intent, REQUEST_CODE_SET_WALLPAPER)
        } catch (_: Exception) {
            try {
                startActivityForResult(
                    Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER),
                    REQUEST_CODE_SET_WALLPAPER,
                )
            } catch (_: Exception) {
                // fallback
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_SET_WALLPAPER) {
            val isSetNow = isWallpaperSet()
            if ((resultCode == RESULT_OK) || (!wasWallpaperSetBefore && isSetNow)) {
                finishAndRemoveTask()
            }
        }
    }
}
