package com.paddigital.astrophagelivewallpaper

import android.app.WallpaperManager
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.ceil

class AstrophageWallpaperService : WallpaperService() {
    companion object {
        private const val MAX_ATTRACTORS = 10
        private const val ATTRACTOR_MERGE_DISTANCE = 35f
        private const val RECENT_INTERACTION_MS = 1_400L
    }

    override fun onCreateEngine(): Engine = AstrophageEngine()

    private inner class AstrophageEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private val renderThread = HandlerThread("AstrophageGpuRenderer").apply { start() }
        private val renderHandler = Handler(renderThread.looper)
        private val prefs = WallpaperPreferences.prefs(this@AstrophageWallpaperService)
        private lateinit var renderer: GpuAstrophageRenderer

        @Volatile private var visible = false
        @Volatile private var surfaceReady = false
        @Volatile private var touching = false
        @Volatile private var lastInteractionUptime = 0L
        @Volatile private var config = WallpaperPreferences.load(this@AstrophageWallpaperService)

        private var scheduledFps = 0
        private var hintedFps = -1
        private var nextFrameAtMs = 0.0

        private val renderRunnable = object : Runnable {
            override fun run() {
                if (!visible || !surfaceReady) return

                try {
                    renderer.render()
                } catch (e: Exception) {
                    android.util.Log.e("AstrophageEngine", "Render failed", e)
                }

                val now = SystemClock.uptimeMillis()
                val fps = targetFps(now)
                updateSurfaceFrameRateHint(fps)
                scheduleNextFrame(now, fps)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            renderer = GpuAstrophageRenderer(surfaceHolder)
            renderer.updateConfig(config)
            // Always enable touch events at the system level. 
            // We filter them based on user preference in onTouchEvent.
            // This prevents a 5-second UI thread hang that can occur when 
            // dynamically toggling window flags during activity transitions.
            setTouchEventsEnabled(true)
            setOffsetNotificationsEnabled(true)
            prefs.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                surfaceReady = surfaceHolder.surface.isValid
                if (::renderer.isInitialized) renderHandler.post { renderer.resetFrameClock() }
            } else {
                touching = false
                if (::renderer.isInitialized) {
                    renderer.setAttractors(emptyList())
                    renderHandler.post { renderer.resetFrameClock() }
                }
                updateSurfaceFrameRateHint(0)
            }
            scheduleRendering(immediate = true)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceReady = true
            renderHandler.post { renderer.surfaceCreated() }
            scheduleRendering(immediate = true)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceReady = true
            renderHandler.post { renderer.resize(width, height) }
            scheduleRendering(immediate = true)
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            if (surfaceReady) renderHandler.post { renderer.render(force = true) }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            touching = false
            renderHandler.removeCallbacks(renderRunnable)
            updateSurfaceFrameRateHint(0)
            renderHandler.post { renderer.surfaceDestroyed() }
            super.onSurfaceDestroyed(holder)
        }

        override fun onTouchEvent(event: MotionEvent) {
            super.onTouchEvent(event)
            if (!config.touchEnabled) return

            val action = event.actionMasked
            when (action) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_MOVE -> {
                    touching = true
                    markInteraction()
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    touching = event.pointerCount > 1
                    markInteraction()
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    touching = false
                    markInteraction()
                    renderer.setAttractors(emptyList())
                    scheduleRendering(immediate = true)
                    return
                }
            }

            val excludedIndex = if (action == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
            val points = ArrayList<Attractor>(minOf(event.pointerCount, MAX_ATTRACTORS))
            for (i in 0 until event.pointerCount) {
                if (i == excludedIndex) continue
                val x = event.getX(i)
                val y = event.getY(i)
                if (!x.isFinite() || !y.isFinite()) continue
                val pointerId = event.getPointerId(i)
                addMergedAttractor(points, x, y, if (pointerId % 2 == 0) 1f else -1f)
            }
            renderer.setAttractors(points)
            scheduleRendering(immediate = true)
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int
        ) {
            if (config.parallaxEnabled) {
                renderer.setLauncherOffset(xOffset.coerceIn(0f, 1f))
                markInteraction()
                scheduleRendering(immediate = true)
            }
        }

        override fun onCommand(
            action: String?,
            x: Int,
            y: Int,
            z: Int,
            extras: Bundle?,
            resultRequested: Boolean
        ): Bundle? {
            if (config.touchEnabled && action == WallpaperManager.COMMAND_TAP) {
                renderer.pulseAt(x.toFloat(), y.toFloat())
                markInteraction()
                scheduleRendering(immediate = true)
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            val isRelevant = key == null ||
                key == WallpaperPreferences.KEY_FOREGROUND ||
                key == WallpaperPreferences.KEY_BACKGROUND ||
                key == WallpaperPreferences.KEY_TRAILS ||
                key == WallpaperPreferences.KEY_TOUCH ||
                key == WallpaperPreferences.KEY_PARALLAX ||
                key == WallpaperPreferences.KEY_POWER_MODE

            if (!isRelevant) return

            val newConfig = WallpaperPreferences.load(this@AstrophageWallpaperService)
            if (newConfig == config) return
            config = newConfig

            renderHandler.post {
                if (::renderer.isInitialized) {
                    if (!config.touchEnabled) {
                        touching = false
                        renderer.setAttractors(emptyList())
                    }
                    renderer.updateConfig(config)
                }
                if (this@AstrophageEngine.visible) {
                    scheduleRendering(immediate = true)
                }
            }
        }

        private fun addMergedAttractor(result: MutableList<Attractor>, x: Float, y: Float, direction: Float) {
            for (i in result.indices) {
                val existing = result[i]
                val dx = x - existing.x
                val dy = y - existing.y
                if (dx * dx + dy * dy < ATTRACTOR_MERGE_DISTANCE * ATTRACTOR_MERGE_DISTANCE) {
                    result[i] = existing.copy(x = (existing.x + x) * 0.5f, y = (existing.y + y) * 0.5f)
                    return
                }
            }
            if (result.size < MAX_ATTRACTORS) result += Attractor(x, y, direction)
        }

        private fun markInteraction() {
            lastInteractionUptime = SystemClock.uptimeMillis()
        }

        private fun targetFps(now: Long): Int {
            val recent = now - lastInteractionUptime < RECENT_INTERACTION_MS
            return if (touching || recent) config.powerMode.interactionFps else config.powerMode.idleFps
        }

        private fun scheduleRendering(immediate: Boolean) {
            renderHandler.removeCallbacks(renderRunnable)
            if (!visible || !surfaceReady) return
            if (immediate) {
                nextFrameAtMs = 0.0
                renderHandler.post(renderRunnable)
            } else {
                val fps = targetFps(SystemClock.uptimeMillis())
                scheduleNextFrame(SystemClock.uptimeMillis(), fps)
            }
        }

        private fun scheduleNextFrame(now: Long, fps: Int) {
            if (!visible || !surfaceReady) return
            val interval = 1000.0 / fps.coerceAtLeast(1)
            if (scheduledFps != fps || nextFrameAtMs <= 0.0) {
                scheduledFps = fps
                nextFrameAtMs = now + interval
            } else {
                nextFrameAtMs += interval
                if (nextFrameAtMs < now) nextFrameAtMs = now + interval
            }
            val delay = ceil(nextFrameAtMs - now).toLong().coerceAtLeast(1L)
            renderHandler.postDelayed(renderRunnable, delay)
        }

        private fun updateSurfaceFrameRateHint(fps: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !surfaceReady) return
            if (fps == hintedFps) return
            try {
                val surface = surfaceHolder.surface
                if (surface.isValid) {
                    surface.setFrameRate(
                        fps.toFloat(),
                        Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
                    )
                    hintedFps = fps
                }
            } catch (_: IllegalArgumentException) {
                // Surface can be replaced while the launcher is changing wallpaper state.
            } catch (_: IllegalStateException) {
                // Surface disappeared between the validity check and setFrameRate().
            }
        }

        override fun onDestroy() {
            visible = false
            surfaceReady = false
            touching = false
            prefs.unregisterOnSharedPreferenceChangeListener(this)

            // Ensure no further render runnables are posted.
            renderHandler.removeCallbacksAndMessages(null)

            if (::renderer.isInitialized) {
                // Post at front to ensure immediate cleanup before thread exit.
                renderHandler.postAtFrontOfQueue {
                    try {
                        renderer.release()
                    } finally {
                        renderThread.quitSafely()
                    }
                }
            } else {
                renderThread.quitSafely()
            }
            super.onDestroy()
        }
    }
}

internal data class Attractor(
    val x: Float,
    val y: Float,
    val direction: Float,
    val strength: Float = 1f
)
