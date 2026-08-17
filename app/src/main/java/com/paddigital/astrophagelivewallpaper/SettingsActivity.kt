package com.paddigital.astrophagelivewallpaper

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
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

        root.addView(TextView(this).apply {
            text = "Astrophage Settings"
            textSize = 26f
            setTextColor(Color.WHITE)
        })

        root.addView(TextView(this).apply {
            text = "V2 uses GPU particle rendering, GPU trails and adaptive frame pacing. Balanced is recommended for everyday use."
            textSize = 14f
            setTextColor(Color.rgb(175, 175, 175))
            setPadding(0, dp(8), 0, dp(4))
        })

        fun slider(
            title: String,
            minimum: Int,
            maximum: Int,
            initial: Int,
            display: (Int) -> String,
            onChanged: (Int) -> Unit
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
            fun refresh(value: Int) { label.text = "$title: ${display(value)}" }
            refresh(initial)
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    selectedValue = minimum + progress
                    refresh(selectedValue)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) { onChanged(selectedValue) }
            })
            root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // Inverted labels: Higher numeric values now represent higher particle density.
        slider("Foreground particles", 24, 64, config.foregroundDensity,
            { value -> when { value >= 58 -> "High"; value >= 42 -> "Medium"; else -> "Low" } },
            { prefs.edit().putInt(WallpaperPreferences.KEY_FOREGROUND, it).apply() })

        slider("Background particles", 350, 1400, config.backgroundDensity,
            { value -> when { value >= 1230 -> "High"; value >= 850 -> "Medium"; else -> "Low" } },
            { prefs.edit().putInt(WallpaperPreferences.KEY_BACKGROUND, it).apply() })

        val trailInitial = ((config.trailAlpha - 0.06f) / (0.45f - 0.06f) * 100f).toInt()
        slider("Trail fade", 0, 100, trailInitial,
            { value -> "$value%" },
            { value ->
                val alpha = 0.06f + (value / 100f) * (0.45f - 0.06f)
                prefs.edit().putFloat(WallpaperPreferences.KEY_TRAILS, alpha).apply()
            })

        root.addView(CheckBox(this).apply {
            text = "Touch interaction"
            textSize = 16f
            setTextColor(Color.WHITE)
            isChecked = config.touchEnabled
            setPadding(0, dp(18), 0, dp(6))
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(WallpaperPreferences.KEY_TOUCH, checked).apply()
            }
        })

        root.addView(CheckBox(this).apply {
            text = "React to home-screen page swipes"
            textSize = 16f
            setTextColor(Color.WHITE)
            isChecked = config.parallaxEnabled
            setPadding(0, dp(6), 0, dp(16))
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(WallpaperPreferences.KEY_PARALLAX, checked).apply()
            }
        })

        root.addView(TextView(this).apply {
            text = "Power mode"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(4))
        })

        val descriptions = mapOf(
            WallpaperPreferences.PowerMode.BATTERY_SAVER to
                "Battery Saver — 15 FPS idle / 30 FPS interaction, fewer particles, half-resolution GPU trails.",
            WallpaperPreferences.PowerMode.BALANCED to
                "Balanced — 20 FPS idle / up to 60 FPS interaction, reduced-resolution GPU trails. Recommended.",
            WallpaperPreferences.PowerMode.MAX_QUALITY to
                "Maximum Quality — 30 FPS idle / up to 60 FPS interaction, maximum particle and trail quality."
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

        root.addView(TextView(this).apply {
            text = "Tip: Battery Saver is best if the wallpaper is visible for long periods. The simulation is time-based, so lower idle FPS reduces power without slowing the Astrophage motion."
            textSize = 13f
            setTextColor(Color.rgb(160, 160, 160))
            setPadding(0, dp(10), 0, dp(20))
        })

        root.addView(Button(this).apply {
            text = "Done"
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
    }
}
