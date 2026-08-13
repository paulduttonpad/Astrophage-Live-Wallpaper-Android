package com.paddigital.astrophagelivewallpaper

import android.app.WallpaperManager
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import java.util.Random
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class AstrophageWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = AstrophageEngine()

    private inner class AstrophageEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {
        private val renderThread = HandlerThread("AstrophageWallpaperRenderer").apply { start() }
        private val renderHandler = Handler(renderThread.looper)
        private lateinit var renderer: AstrophageRenderer
        private val prefs = WallpaperPreferences.prefs(this@AstrophageWallpaperService)

        @Volatile private var visible = false
        @Volatile private var surfaceReady = false
        @Volatile private var config = WallpaperPreferences.load(this@AstrophageWallpaperService)

        private val renderRunnable = object : Runnable {
            override fun run() {
                if (!visible || !surfaceReady) return

                val started = SystemClock.uptimeMillis()
                renderer.render()
                val frameMs = if (config.fpsLimit >= 60) 16L else 33L
                val elapsed = SystemClock.uptimeMillis() - started
                renderHandler.postDelayed(this, max(1L, frameMs - elapsed))
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            renderer = AstrophageRenderer(surfaceHolder)
            renderer.updateConfig(config)
            setTouchEventsEnabled(config.touchEnabled)
            setOffsetNotificationsEnabled(true)
            prefs.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (!visible && ::renderer.isInitialized) {
                renderer.setAttractors(emptyList())
            }
            scheduleRendering()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceReady = true
            scheduleRendering()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceReady = true
            renderHandler.post { renderer.resize(width, height) }
            scheduleRendering()
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            renderHandler.post { renderer.render(force = true) }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            renderHandler.removeCallbacks(renderRunnable)
            renderHandler.post { renderer.releaseSurfaceBuffer() }
            super.onSurfaceDestroyed(holder)
        }

        override fun onTouchEvent(event: MotionEvent) {
            super.onTouchEvent(event)
            if (!config.touchEnabled) return

            val action = event.actionMasked
            if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
                renderer.setAttractors(emptyList())
                return
            }

            val excludedIndex = if (action == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
            val points = ArrayList<Attractor>(event.pointerCount)
            for (i in 0 until event.pointerCount) {
                if (i == excludedIndex) continue
                val x = event.getX(i)
                val y = event.getY(i)
                if (!x.isFinite() || !y.isFinite()) continue
                val pointerId = event.getPointerId(i)
                points += Attractor(x, y, if (pointerId % 2 == 0) 1f else -1f)
            }
            renderer.setAttractors(points)
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
            // Some launchers report a tap through wallpaper commands in addition to
            // raw MotionEvents. Give that tap a short pulse without intercepting it.
            if (config.touchEnabled && action == WallpaperManager.COMMAND_TAP) {
                renderer.pulseAt(x.toFloat(), y.toFloat())
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            config = WallpaperPreferences.load(this@AstrophageWallpaperService)
            setTouchEventsEnabled(config.touchEnabled)
            if (!config.touchEnabled) renderer.setAttractors(emptyList())
            renderHandler.post { renderer.updateConfig(config) }
            scheduleRendering()
        }

        private fun scheduleRendering() {
            renderHandler.removeCallbacks(renderRunnable)
            if (visible && surfaceReady) renderHandler.post(renderRunnable)
        }

        override fun onDestroy() {
            visible = false
            surfaceReady = false
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            renderHandler.removeCallbacksAndMessages(null)
            if (::renderer.isInitialized) {
                renderHandler.post {
                    renderer.release()
                    renderThread.quitSafely()
                }
            } else {
                renderThread.quitSafely()
            }
            super.onDestroy()
        }
    }
}

private data class Attractor(val x: Float, val y: Float, val direction: Float, val strength: Float = 1f)

private class AstrophageRenderer(private val holder: SurfaceHolder) {
    companion object {
        private const val FOREGROUND_MIN = 120
        private const val FOREGROUND_MAX = 420
        private const val BACKGROUND_MIN = 600
        private const val BACKGROUND_MAX = 3000
        private const val INTERACTION_RADIUS = 0.40f
        private const val FLOW_SPEED = 0.002f
        private const val FLOW_RECALC_FRAMES = 5
        private const val BACKGROUND_INTERACTION_SKIP = 2
        private const val MAX_TRAIL_PIXELS = 2_200_000f
        private const val TWO_PI = (PI * 2.0).toFloat()
    }

    private val random = Random()
    private val attractors = AtomicReference<List<Attractor>>(emptyList())
    private val particles = ArrayList<Ball>()
    private val backgroundParticles = ArrayList<BackgroundAstrophage>()

    private var config = WallpaperPreferences.Config()
    private var width = 1
    private var height = 1
    private var maxDistance = 1f
    private var maxDistanceSq = 1f
    private var frameCount = 0L
    private var flowTime = 0f
    private var launcherOffset: Float? = null
    @Volatile private var pendingOffset: Float? = null
    @Volatile private var pendingPulse: Pulse? = null
    private var pulse: Pulse? = null

    private var trailBitmap: Bitmap? = null
    private var trailCanvas: Canvas? = null
    private var renderScale = 1f

    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint = Paint().apply {
        color = Color.rgb(255, 18, 24)
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val backgroundSparkPaint = Paint().apply {
        color = Color.rgb(255, 245, 245)
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val spritePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        isDither = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val outputPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val astrophageSprite = makeGlowSprite(96, SpriteKind.ASTROPHAGE)
    private val bokehSprite = makeGlowSprite(128, SpriteKind.BOKEH)
    private val sparkSprite = makeGlowSprite(48, SpriteKind.SPARK)

    fun updateConfig(newConfig: WallpaperPreferences.Config) {
        val densityChanged = newConfig.foregroundDensity != config.foregroundDensity ||
            newConfig.backgroundDensity != config.backgroundDensity
        config = newConfig
        if (densityChanged) rebuildParticleCounts()
    }

    fun resize(newWidth: Int, newHeight: Int) {
        if (newWidth <= 0 || newHeight <= 0) return
        width = newWidth
        height = newHeight
        maxDistance = max(1f, height * INTERACTION_RADIUS)
        maxDistanceSq = maxDistance * maxDistance
        recreateTrailBuffer()
        rebuildParticleCounts()
    }

    fun setAttractors(points: List<Attractor>) {
        attractors.set(points)
    }

    fun setLauncherOffset(offset: Float) {
        pendingOffset = offset
    }

    fun pulseAt(x: Float, y: Float) {
        pendingPulse = Pulse(x, y, 1f)
    }

    fun render(force: Boolean = false) {
        pendingPulse?.let {
            pulse = it
            pendingPulse = null
        }
        val bounds = holder.surfaceFrame
        if ((width <= 1 || height <= 1) && bounds.width() > 0 && bounds.height() > 0) {
            resize(bounds.width(), bounds.height())
        }
        val offscreen = trailCanvas ?: return
        val bitmap = trailBitmap ?: return

        applyLauncherOffsetImpulse()

        // Equivalent to p5 background(0, 0, 0, TRAIL_ALPHA): fade previous frame.
        fadePaint.color = Color.BLACK
        fadePaint.alpha = (config.trailAlpha * 255f).toInt().coerceIn(0, 255)
        offscreen.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), fadePaint)

        val canvasScale = renderScale
        offscreen.save()
        offscreen.scale(canvasScale, canvasScale)

        val activeAttractors = buildActiveAttractors()
        val interactionStep = frameCount % BACKGROUND_INTERACTION_SKIP == 0L

        for (p in backgroundParticles) {
            p.update(activeAttractors, interactionStep, maxDistance, maxDistanceSq, flowTime, width, height)
        }

        for (ball in particles) {
            ball.applyGravity = true
            applyPetrovaFlow(ball)
            for (a in activeAttractors) interactWithPoint(ball, a)
            ball.update(width, height)
        }

        drawBackground(offscreen)
        for (ball in particles) ball.show(offscreen, astrophageSprite, bokehSprite, sparkSprite, spritePaint, frameCount)

        offscreen.restore()

        var surfaceCanvas: Canvas? = null
        try {
            surfaceCanvas = holder.lockCanvas()
            surfaceCanvas?.drawColor(Color.BLACK)
            surfaceCanvas?.drawBitmap(
                bitmap,
                null,
                Rect(0, 0, width, height),
                outputPaint
            )
        } catch (_: IllegalArgumentException) {
            // Surface may disappear between visibility and render callbacks.
        } finally {
            if (surfaceCanvas != null) {
                try { holder.unlockCanvasAndPost(surfaceCanvas) } catch (_: Exception) { }
            }
        }

        frameCount++
        flowTime += FLOW_SPEED
        pulse?.let {
            it.life *= 0.84f
            if (it.life < 0.05f) pulse = null
        }
    }

    private fun buildActiveAttractors(): List<Attractor> {
        val current = attractors.get()
        val p = pulse ?: return current
        if (current.isEmpty()) return listOf(Attractor(p.x, p.y, 1f, p.life))
        return current + Attractor(p.x, p.y, 1f, p.life)
    }

    private fun applyLauncherOffsetImpulse() {
        val next = pendingOffset ?: return
        pendingOffset = null
        val previous = launcherOffset
        launcherOffset = next
        if (previous == null) return
        val delta = (next - previous).coerceIn(-0.35f, 0.35f)
        if (abs(delta) < 0.0001f) return

        // Home-screen page motion pushes the 3D field in the opposite direction,
        // with nearer particles moving more strongly than distant ones.
        for (ball in particles) {
            ball.vx -= delta * (4.5f + ball.depth * 7f)
            ball.energy = max(ball.energy, min(1f, abs(delta) * 5f))
        }
        for (p in backgroundParticles) {
            p.vx -= delta * (0.12f + p.depth * 0.25f)
        }
    }

    private fun applyPetrovaFlow(ball: Ball) {
        if ((frameCount + ball.flowSlot) % FLOW_RECALC_FRAMES == 0L) {
            val scale = 0.002f
            val n = SimpleNoise.noise(
                ball.x * scale,
                ball.y * scale,
                flowTime + ball.depth * 10f
            )
            ball.flowAngle = n * TWO_PI * 3f
        }

        val strength = 0.05f + ball.depth * 0.08f
        ball.applyForce(cos(ball.flowAngle) * strength, sin(ball.flowAngle) * strength)
    }

    private fun interactWithPoint(ball: Ball, a: Attractor) {
        val dx = a.x - ball.x
        val dy = a.y - ball.y
        val distanceSq = dx * dx + dy * dy
        if (!distanceSq.isFinite() || distanceSq > maxDistanceSq) return
        if (distanceSq < 0.000001f) {
            ball.energy = 1f
            ball.applyGravity = false
            return
        }

        val distance = sqrt(distanceSq)
        val influence = ((1f - distance / maxDistance).coerceIn(0f, 1f) * a.strength).coerceIn(0f, 1f)
        if (influence <= 0f) return

        ball.applyGravity = influence < 0.1f
        val nx = dx / distance
        val ny = dy / distance

        val attractionStrength = influence.toDouble().pow(1.8).toFloat() * 2f
        ball.applyForce(nx * attractionStrength, ny * attractionStrength)

        val swirlStrength = influence * 0.8f * if (a.direction < 0f) -1f else 1f
        ball.applyForce(-ny * swirlStrength, nx * swirlStrength)

        if (distance < 35f) {
            val repulsion = (1f - distance / 35f) * 2f
            ball.applyForce(-nx * repulsion, -ny * repulsion)
        }

        ball.energy = max(ball.energy, influence)
    }

    private fun drawBackground(canvas: Canvas) {
        for (p in backgroundParticles) {
            backgroundPaint.alpha = (p.alpha(frameCount) * 255f).toInt().coerceIn(0, 255)
            val size = p.size.toFloat()
            canvas.drawRect(p.x - size * 0.5f, p.y - size * 0.5f, p.x + size * 0.5f, p.y + size * 0.5f, backgroundPaint)
        }
        for (p in backgroundParticles) {
            if (!p.spark) continue
            backgroundSparkPaint.alpha = (min(0.75f, p.alpha(frameCount) * 1.4f) * 255f).toInt().coerceIn(0, 255)
            canvas.drawRect(p.x, p.y, p.x + 1f, p.y + 1f, backgroundSparkPaint)
        }
    }

    private fun rebuildParticleCounts() {
        if (width <= 1 || height <= 1) return
        val foregroundCount = (((width / config.foregroundDensity.toFloat()) *
            (height / config.foregroundDensity.toFloat())).toInt()).coerceIn(FOREGROUND_MIN, FOREGROUND_MAX)
        val backgroundCount = ((width.toLong() * height.toLong()) / config.backgroundDensity)
            .toInt().coerceIn(BACKGROUND_MIN, BACKGROUND_MAX)

        while (particles.size < foregroundCount) {
            val radius = if (random.nextFloat() < 0.80f) randomRange(0.7f, 2.5f) else randomRange(2f, 5f)
            particles += Ball(randomRange(0f, width.toFloat()), randomRange(0f, height.toFloat()), radius, random)
        }
        while (particles.size > foregroundCount) particles.removeAt(particles.lastIndex)

        while (backgroundParticles.size < backgroundCount) {
            backgroundParticles += BackgroundAstrophage(
                randomRange(0f, width.toFloat()),
                randomRange(0f, height.toFloat()),
                random
            )
        }
        while (backgroundParticles.size > backgroundCount) backgroundParticles.removeAt(backgroundParticles.lastIndex)
    }

    private fun recreateTrailBuffer() {
        trailBitmap?.recycle()
        val pixelCount = width.toFloat() * height.toFloat()
        renderScale = min(1f, sqrt(MAX_TRAIL_PIXELS / max(1f, pixelCount))).coerceAtLeast(0.55f)
        val bw = max(1, (width * renderScale).toInt())
        val bh = max(1, (height * renderScale).toInt())
        trailBitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.BLACK) }
        trailCanvas = Canvas(trailBitmap!!)
    }

    fun releaseSurfaceBuffer() {
        trailBitmap?.recycle()
        trailBitmap = null
        trailCanvas = null
    }

    fun release() {
        releaseSurfaceBuffer()
        astrophageSprite.recycle()
        bokehSprite.recycle()
        sparkSprite.recycle()
    }

    private fun randomRange(min: Float, max: Float): Float = min + random.nextFloat() * (max - min)

    private enum class SpriteKind { ASTROPHAGE, BOKEH, SPARK }

    private fun makeGlowSprite(size: Int, kind: SpriteKind): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val c = size * 0.5f
        val radius = size * 0.48f
        val colors: IntArray
        val stops: FloatArray

        when (kind) {
            SpriteKind.SPARK -> {
                colors = intArrayOf(
                    Color.argb(255, 255, 255, 255),
                    Color.argb(242, 255, 245, 245),
                    Color.argb(178, 255, 70, 70),
                    Color.argb(46, 255, 0, 18),
                    Color.argb(0, 255, 0, 0)
                )
                stops = floatArrayOf(0f, 0.08f, 0.24f, 0.58f, 1f)
            }
            SpriteKind.BOKEH -> {
                colors = intArrayOf(
                    Color.argb(140, 255, 235, 235),
                    Color.argb(87, 255, 90, 90),
                    Color.argb(56, 255, 10, 25),
                    Color.argb(26, 255, 0, 15),
                    Color.argb(0, 255, 0, 0)
                )
                stops = floatArrayOf(0f, 0.10f, 0.42f, 0.72f, 1f)
            }
            SpriteKind.ASTROPHAGE -> {
                colors = intArrayOf(
                    Color.argb(255, 255, 255, 255),
                    Color.argb(250, 255, 235, 235),
                    Color.argb(224, 255, 75, 75),
                    Color.argb(115, 255, 0, 20),
                    Color.argb(26, 255, 0, 10),
                    Color.argb(0, 255, 0, 0)
                )
                stops = floatArrayOf(0f, 0.06f, 0.17f, 0.40f, 0.72f, 1f)
            }
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(c, c, radius, colors, stops, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        return bitmap
    }

    private data class Pulse(val x: Float, val y: Float, var life: Float)
}

private class Ball(x0: Float, y0: Float, radius: Float, random: Random) {
    var x = x0
    var y = y0
    var vx = randomRange(random, -0.15f, 0.15f)
    var vy = randomRange(random, -0.15f, 0.15f)
    private var ax = 0f
    private var ay = 0f
    private val r = if (radius.isFinite()) radius else 1f
    private val massScale = max(0.01f, ((PI.toFloat() * 2f * r) * 0.5f) * 0.01f)

    var applyGravity = true
    val depth = random.nextFloat().toDouble().pow(1.8).toFloat()
    private val bokeh = random.nextFloat() < 0.12f
    private val spark = random.nextFloat() < 0.06f
    var energy = 0f
    private val pulseOffset = randomRange(random, 0f, PI.toFloat() * 2f)
    private val pulseSpeed = randomRange(random, 0.01f, 0.035f)
    var flowAngle = randomRange(random, 0f, PI.toFloat() * 2f)
    val flowSlot = random.nextInt(5)

    fun applyForce(fx: Float, fy: Float) {
        if (!fx.isFinite() || !fy.isFinite()) return
        ax += fx
        ay += fy
        val magnitude = hypot(fx, fy)
        if (magnitude.isFinite()) energy = max(energy, (magnitude / 2.5f).coerceIn(0f, 1f))
    }

    fun update(width: Int, height: Int) {
        vx = (vx + ax * massScale) * 0.992f
        vy = (vy + ay * massScale) * 0.992f

        val maxSpeed = 4f + energy * 3f
        val speedSq = vx * vx + vy * vy
        if (speedSq > maxSpeed * maxSpeed && speedSq > 0f) {
            val scale = maxSpeed / sqrt(speedSq)
            vx *= scale
            vy *= scale
        }

        x += vx
        y += vy
        if (!x.isFinite() || !y.isFinite()) {
            x = width * 0.5f
            y = height * 0.5f
            vx = 0f
            vy = 0f
        }

        ax = 0f
        ay = 0f
        energy = (energy * 0.94f).coerceIn(0f, 1f)
        edges(width, height)
    }

    fun show(
        canvas: Canvas,
        astrophageSprite: Bitmap,
        bokehSprite: Bitmap,
        sparkSprite: Bitmap,
        paint: Paint,
        frameCount: Long
    ) {
        val pulse = 0.85f + sin(frameCount * pulseSpeed + pulseOffset) * 0.15f
        if (bokeh) showBokeh(canvas, bokehSprite, paint, pulse)
        else showAstrophage(canvas, astrophageSprite, sparkSprite, paint, pulse)
    }

    private fun showAstrophage(canvas: Canvas, sprite: Bitmap, sparkSprite: Bitmap, paint: Paint, pulse: Float) {
        var size = r * lerp(0.35f, 0.9f, depth)
        size *= 1f + energy * 0.8f
        val drawSize = max(4f, size * (7.5f + energy * 3.5f) * pulse)
        paint.alpha = ((0.25f + depth * 0.45f + energy * 0.30f).coerceIn(0.15f, 1f) * 255f).toInt()
        canvas.drawBitmap(sprite, null, rectAround(x, y, drawSize), paint)

        if (spark) {
            val sparkSize = max(2f, drawSize * 0.28f)
            paint.alpha = ((0.25f + energy * 0.55f).coerceIn(0f, 0.9f) * 255f).toInt()
            canvas.drawBitmap(sparkSprite, null, rectAround(x, y, sparkSize), paint)
        }
    }

    private fun showBokeh(canvas: Canvas, sprite: Bitmap, paint: Paint, pulse: Float) {
        var size = r * lerp(2.5f, 7f, depth)
        size *= pulse * (1f + energy * 0.5f)
        val drawSize = max(18f, size * 3.1f)
        paint.alpha = ((0.10f + depth * 0.12f + energy * 0.12f).coerceIn(0.07f, 0.40f) * 255f).toInt()
        canvas.drawBitmap(sprite, null, rectAround(x, y, drawSize), paint)
    }

    private fun edges(width: Int, height: Int) {
        val margin = max(5f, r * 5f)
        if (x > width + margin) x = -margin else if (x < -margin) x = width + margin
        if (y > height + margin) y = -margin else if (y < -margin) y = height + margin
    }

    private fun rectAround(cx: Float, cy: Float, size: Float) =
        RectF(cx - size * 0.5f, cy - size * 0.5f, cx + size * 0.5f, cy + size * 0.5f)

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    companion object {
        private fun randomRange(random: Random, min: Float, max: Float) = min + random.nextFloat() * (max - min)
    }
}

private class BackgroundAstrophage(x0: Float, y0: Float, random: Random) {
    var x = x0
    var y = y0
    val depth = random.nextFloat().toDouble().pow(2.2).toFloat()
    var size = if (depth > 0.72f) 2 else 1
    val spark = random.nextFloat() < 0.025f
    var vx: Float
    var vy: Float
    private val phase = randomRange(random, 0f, PI.toFloat() * 2f)
    private val wobbleSpeed = randomRange(random, 0.10f, 0.28f)
    private val wobble = randomRange(random, 0.003f, 0.015f) * lerp(0.4f, 1f, depth)
    private val baseAlpha = randomRange(random, 0.12f, 0.45f) * lerp(0.55f, 1f, depth)
    private val twinkleOffset = randomRange(random, 0f, PI.toFloat() * 2f)
    private val twinkleSpeed = randomRange(random, 0.004f, 0.018f)

    init {
        if (random.nextFloat() < 0.02f) size = 3
        val angle = randomRange(random, 0f, PI.toFloat() * 2f)
        val speed = randomRange(random, 0.015f, 0.055f) * lerp(0.35f, 1f, depth)
        vx = cos(angle) * speed
        vy = sin(angle) * speed
    }

    fun update(
        attractors: List<Attractor>,
        interactionStep: Boolean,
        maxDistance: Float,
        maxDistanceSq: Float,
        flowTime: Float,
        width: Int,
        height: Int
    ) {
        val wobbleAngle = flowTime * wobbleSpeed + phase
        x += vx + cos(wobbleAngle) * wobble
        y += vy + sin(wobbleAngle * 1.13f) * wobble

        if (interactionStep && attractors.isNotEmpty()) {
            for (a in attractors) {
                val dx = a.x - x
                val dy = a.y - y
                val distanceSq = dx * dx + dy * dy
                if (distanceSq <= 0f || distanceSq > maxDistanceSq) continue
                val distance = sqrt(distanceSq)
                val influence = (1f - distance / maxDistance) * a.strength
                if (influence <= 0f) continue
                val nx = dx / distance
                val ny = dy / distance
                val attraction = influence * 0.004f
                val swirl = influence * 0.0018f * a.direction
                vx += nx * attraction - ny * swirl
                vy += ny * attraction + nx * swirl
            }

            val speedSq = vx * vx + vy * vy
            val maxSpeed = 0.14f + depth * 0.22f
            if (speedSq > maxSpeed * maxSpeed && speedSq > 0f) {
                val scale = maxSpeed / sqrt(speedSq)
                vx *= scale
                vy *= scale
            }
        }

        val margin = 4f
        if (x > width + margin) x = -margin else if (x < -margin) x = width + margin
        if (y > height + margin) y = -margin else if (y < -margin) y = height + margin
    }

    fun alpha(frameCount: Long): Float {
        val twinkle = 0.78f + sin(frameCount * twinkleSpeed + twinkleOffset) * 0.22f
        return (baseAlpha * twinkle).coerceIn(0.04f, 0.60f)
    }

    companion object {
        private fun randomRange(random: Random, min: Float, max: Float) = min + random.nextFloat() * (max - min)
        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    }
}

private object SimpleNoise {
    // Compact deterministic 3D value noise. It is intentionally cached only at the
    // particle level (1/5 of foreground particles per frame), matching the p5 sketch's
    // performance strategy without depending on p5.js inside Android's wallpaper surface.
    fun noise(x: Float, y: Float, z: Float): Float {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val zi = floor(z).toInt()
        val xf = x - floor(x)
        val yf = y - floor(y)
        val zf = z - floor(z)
        val u = smooth(xf)
        val v = smooth(yf)
        val w = smooth(zf)

        val c000 = hash(xi, yi, zi)
        val c100 = hash(xi + 1, yi, zi)
        val c010 = hash(xi, yi + 1, zi)
        val c110 = hash(xi + 1, yi + 1, zi)
        val c001 = hash(xi, yi, zi + 1)
        val c101 = hash(xi + 1, yi, zi + 1)
        val c011 = hash(xi, yi + 1, zi + 1)
        val c111 = hash(xi + 1, yi + 1, zi + 1)

        val x00 = lerp(c000, c100, u)
        val x10 = lerp(c010, c110, u)
        val x01 = lerp(c001, c101, u)
        val x11 = lerp(c011, c111, u)
        return lerp(lerp(x00, x10, v), lerp(x01, x11, v), w)
    }

    private fun smooth(t: Float) = t * t * (3f - 2f * t)
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun hash(x: Int, y: Int, z: Int): Float {
        var n = x * 374761393 + y * 668265263 + z * 2147483647
        n = (n xor (n ushr 13)) * 1274126177
        n = n xor (n ushr 16)
        return (n and 0x7fffffff) / 2147483647f
    }
}
