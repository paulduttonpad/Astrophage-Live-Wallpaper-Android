package com.paddigital.astrophagelivewallpaper

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = WallpaperPreferences.prefs(this)
        val config = WallpaperPreferences.load(this)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(34), dp(22), dp(34))
            setBackgroundColor(Color.BLACK)
        }

        root.addView(
            TextView(this).apply {
                text = getString(R.string.settings_title)
                textSize = 26f
                setTextColor(Color.WHITE)
            },
        )

        root.addView(
            TextView(this).apply {
                text = getString(R.string.settings_description)
                textSize = 14f
                setTextColor(Color.rgb(175, 175, 175))
                setPadding(0, dp(8), 0, dp(4))
            },
        )

        fun slider(
            title: String,
            minimum: Int,
            maximum: Int,
            initial: Int,
            display: (Int) -> String,
            onChanged: (Int) -> Unit,
        ) {
            val label = TextView(this).apply {
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(0, dp(22), 0, dp(4))
            }
            root.addView(label)
            val bar = SeekBar(this).apply {
                max = maximum - minimum
                progress = initial - minimum
            }
            var selectedValue = initial
            fun refresh(value: Int) {
                label.text = getString(R.string.settings_label_format, title, display(value))
            }
            refresh(initial)
            bar.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    selectedValue = minimum + progress
                    refresh(selectedValue)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    onChanged(selectedValue)
                }
            },
        )
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        fun hueSlider(
            title: String,
            initialShiftDegrees: Int,
            onChanged: (Int) -> Unit,
        ) {
            val label = TextView(this).apply {
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(0, dp(22), 0, dp(4))
            }
            root.addView(label)

            val rainbowColors = intArrayOf(
                Color.HSVToColor(floatArrayOf((358.7f + 0f) % 360f, 0.95f, 1f)),
                Color.HSVToColor(floatArrayOf((358.7f + 60f) % 360f, 0.95f, 1f)),
                Color.HSVToColor(floatArrayOf((358.7f + 120f) % 360f, 0.95f, 1f)),
                Color.HSVToColor(floatArrayOf((358.7f + 180f) % 360f, 0.95f, 1f)),
                Color.HSVToColor(floatArrayOf((358.7f + 240f) % 360f, 0.95f, 1f)),
                Color.HSVToColor(floatArrayOf((358.7f + 300f) % 360f, 0.95f, 1f)),
                Color.HSVToColor(floatArrayOf((358.7f + 360f) % 360f, 0.95f, 1f)),
            )

            val sliderContainer = FrameLayout(this).apply {
                setPadding(0, dp(6), 0, dp(6))
            }

            val trackView = View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    rainbowColors,
                ).apply {
                    cornerRadius = dp(4).toFloat()
                }
            }

            val trackParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(8),
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                leftMargin = dp(12)
                rightMargin = dp(12)
            }
            sliderContainer.addView(trackView, trackParams)

            val bar = SeekBar(this).apply {
                max = 360
                progress = initialShiftDegrees
                progressBackgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                progressTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            }

            fun updateColor(degrees: Int) {
                val effectiveHue = (358.7f + degrees) % 360f
                val currentColor = Color.HSVToColor(floatArrayOf(effectiveHue, 0.95f, 1f))
                val colorStateList = ColorStateList.valueOf(currentColor)
                bar.thumbTintList = colorStateList

                val displayStr = if (degrees == 0) "0° (Red)" else "$degrees°"
                label.text = getString(R.string.settings_label_format, title, displayStr)
            }

            updateColor(initialShiftDegrees)

            bar.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        updateColor(progress)
                        if (fromUser) {
                            onChanged(progress)
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        onChanged(bar.progress)
                    }
                },
            )

            val barParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            sliderContainer.addView(bar, barParams)

            root.addView(
                sliderContainer,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        // Inverted labels: Higher numeric values now represent higher particle density.
        slider(
            getString(R.string.settings_foreground_particles),
            24,
            64,
            config.foregroundDensity,
            { value ->
                when {
                    value >= 58 -> getString(R.string.settings_high)
                    value >= 42 -> getString(R.string.settings_medium)
                    else -> getString(R.string.settings_low)
                }
            },
        ) {
            prefs.edit().putInt(WallpaperPreferences.KEY_FOREGROUND, it).apply()
        }

        slider(
            getString(R.string.settings_background_particles),
            350,
            1400,
            config.backgroundDensity,
            { value ->
                when {
                    value >= 1230 -> getString(R.string.settings_high)
                    value >= 850 -> getString(R.string.settings_medium)
                    else -> getString(R.string.settings_low)
                }
            },
        ) {
            prefs.edit().putInt(WallpaperPreferences.KEY_BACKGROUND, it).apply()
        }

        val trailInitial = (((config.trailAlpha - 0.06f) / (0.45f - 0.06f)) * 100f).toInt()
        slider(
            getString(R.string.settings_trail_fade),
            0,
            100,
            trailInitial,
            { value -> "$value%" },
        ) { value ->
                val alpha = 0.06f + ((value / 100f) * (0.45f - 0.06f))
                prefs.edit().putFloat(WallpaperPreferences.KEY_TRAILS, alpha).apply()
            }

        val hueInitial = (config.hueShift * 360f).toInt().coerceIn(0, 360)
        hueSlider(
            getString(R.string.settings_color_hue),
            hueInitial,
        ) { value ->
            val hue = value / 360f
            prefs.edit().putFloat(WallpaperPreferences.KEY_HUE, hue).apply()
        }

        root.addView(
            CheckBox(this).apply {
                text = getString(R.string.settings_touch_interaction)
                textSize = 16f
                setTextColor(Color.WHITE)
                isChecked = config.touchEnabled
                setPadding(0, dp(18), 0, dp(6))
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean(WallpaperPreferences.KEY_TOUCH, checked).apply()
                }
            },
        )

        root.addView(
            CheckBox(this).apply {
                text = getString(R.string.settings_parallax)
                textSize = 16f
                setTextColor(Color.WHITE)
                isChecked = config.parallaxEnabled
                setPadding(0, dp(6), 0, dp(16))
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean(WallpaperPreferences.KEY_PARALLAX, checked).apply()
                }
            },
        )

        root.addView(
            TextView(this).apply {
                text = getString(R.string.settings_power_mode)
                textSize = 18f
                setTextColor(Color.WHITE)
                setPadding(0, dp(8), 0, dp(4))
            },
        )

        val descriptions = mapOf(
            WallpaperPreferences.PowerMode.BATTERY_SAVER to
                getString(R.string.settings_battery_saver),
            WallpaperPreferences.PowerMode.BALANCED to
                getString(R.string.settings_balanced),
            WallpaperPreferences.PowerMode.MAX_QUALITY to
                getString(R.string.settings_max_quality),
        )

        val radioGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val modeById = HashMap<Int, WallpaperPreferences.PowerMode>()
        for (mode in WallpaperPreferences.PowerMode.entries) {
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = descriptions.getValue(mode)
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(0, dp(4), 0, dp(4))
                isChecked = mode == config.powerMode
            }
            modeById[button.id] = mode
            radioGroup.addView(button)
        }
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = modeById[checkedId] ?: return@setOnCheckedChangeListener
            prefs.edit().putString(WallpaperPreferences.KEY_POWER_MODE, mode.storageValue).apply()
        }
        root.addView(radioGroup)

        root.addView(
            TextView(this).apply {
                text = getString(R.string.settings_tip)
                textSize = 13f
                setTextColor(Color.rgb(160, 160, 160))
                setPadding(0, dp(10), 0, dp(20))
            },
        )

        root.addView(
            Button(this).apply {
                text = getString(R.string.main_preview_button)
                gravity = Gravity.CENTER
                setOnClickListener { openWallpaperPreview() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)),
        )

        root.addView(
            Button(this).apply {
                text = getString(R.string.settings_done)
                gravity = Gravity.CENTER
                setOnClickListener {
                    if (!isWallpaperSet()) {
                        openWallpaperPreview()
                    } else {
                        finishAndRemoveTask()
                    }
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
                topMargin = dp(12)
            },
        )

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
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
