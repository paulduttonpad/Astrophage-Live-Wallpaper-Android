package com.paddigital.astrophagelivewallpaper

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Random
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * V2 GPU renderer.
 *
 * CPU responsibilities are deliberately limited to particle physics and touch response.
 * Glow sprites, additive compositing, trail fading and final scaling happen in OpenGL ES.
 */
internal class GpuAstrophageRenderer(private val holder: SurfaceHolder) {
    companion object {
        private const val TAG = "AstrophageGPU"
        private const val FLOATS_PER_PARTICLE = 6
        private const val MAX_RENDER_VERTICES = 10_000
        private const val FOREGROUND_MIN = 120
        private const val FOREGROUND_MAX = 420
        private const val BACKGROUND_MIN = 600
        private const val BACKGROUND_MAX = 2500
        private const val INTERACTION_RADIUS = 0.40f
        private const val FLOW_SPEED_PER_60HZ_FRAME = 0.002f
        private const val FLOW_RECALC_STEPS = 5
        private const val TWO_PI = (PI * 2.0).toFloat()

        private const val KIND_BACKGROUND = 0f
        private const val KIND_ASTROPHAGE = 1f
        private const val KIND_BOKEH = 2f
        private const val KIND_SPARK = 3f
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
    private var simTimeSeconds = 0f
    private var flowTime = 0f
    private var simulationStepCount = 0L
    private var lastFrameNs = 0L

    private var launcherOffset: Float? = null
    @Volatile private var pendingOffset: Float? = null
    @Volatile private var pendingPulse: Pulse? = null
    private var pulse: Pulse? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null

    private var particleProgram = 0
    private var textureProgram = 0
    private var glReady = false

    private val trailTextures = IntArray(2)
    private val trailFramebuffers = IntArray(2)
    private var trailIndex = 0
    private var trailWidth = 1
    private var trailHeight = 1
    private var trailScale = 1f
    private var buffersDirty = true

    private val particleBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(MAX_RENDER_VERTICES * FLOATS_PER_PARTICLE * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val quadBuffer: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(
                floatArrayOf(
                    -1f, -1f, 0f, 0f,
                     1f, -1f, 1f, 0f,
                    -1f,  1f, 0f, 1f,
                     1f,  1f, 1f, 1f
                )
            )
            position(0)
        }

    fun updateConfig(newConfig: WallpaperPreferences.Config) {
        if (newConfig == config) return
        val densityChanged = newConfig.foregroundDensity != config.foregroundDensity ||
            newConfig.backgroundDensity != config.backgroundDensity
        val powerModeChanged = newConfig.powerMode != config.powerMode
        config = newConfig
        if (densityChanged || powerModeChanged) rebuildParticleCounts()
        if (powerModeChanged) buffersDirty = true
    }

    fun surfaceCreated() {
        // EGL window creation is deferred until render() so every GL call stays on renderThread.
    }

    fun surfaceDestroyed() {
        releaseEglSurface()
        resetFrameClock()
    }

    fun resize(newWidth: Int, newHeight: Int) {
        if (newWidth <= 0 || newHeight <= 0) return
        width = newWidth
        height = newHeight
        maxDistance = max(1f, height * INTERACTION_RADIUS)
        maxDistanceSq = maxDistance * maxDistance
        buffersDirty = true
        rebuildParticleCounts()
    }

    fun setAttractors(points: List<Attractor>) {
        attractors.set(points.take(10))
    }

    fun setLauncherOffset(offset: Float) {
        pendingOffset = offset
    }

    fun pulseAt(x: Float, y: Float) {
        pendingPulse = Pulse(x, y, 1f)
    }

    fun resetFrameClock() {
        lastFrameNs = 0L
    }

    fun render(force: Boolean = false) {
        if (force) {
            Log.d(TAG, "Forcing EGL surface recreation")
            releaseEglSurface()
        }

        if (!holder.surface.isValid) {
            releaseEglSurface()
            return
        }

        if (!ensureEgl()) return
        if (!ensureGlResources()) return

        val bounds = holder.surfaceFrame
        var currentWidth = width
        var currentHeight = height
        if ((currentWidth <= 1 || currentHeight <= 1) && bounds.width() > 0 && bounds.height() > 0) {
            resize(bounds.width(), bounds.height())
            currentWidth = width
            currentHeight = height
        }
        if (currentWidth <= 1 || currentHeight <= 1) return

        if (buffersDirty && !recreateTrailBuffers()) return

        pendingPulse?.let {
            pulse = it
            pendingPulse = null
        }
        applyLauncherOffsetImpulse()

        val nowNs = SystemClock.elapsedRealtimeNanos()
        val dtSeconds = if (lastFrameNs == 0L) {
            1f / 60f
        } else {
            ((nowNs - lastFrameNs) / 1_000_000_000.0).toFloat().coerceIn(1f / 240f, 0.10f)
        }
        lastFrameNs = nowNs
        val frameScale = (dtSeconds * 60f).coerceIn(0.25f, 6f)

        updateSimulation(frameScale, dtSeconds)
        renderGpu(frameScale)

        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            Log.w(TAG, "eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
            releaseEglSurface()
        }
    }

    private fun updateSimulation(frameScale: Float, dtSeconds: Float) {
        val subSteps = ceil(frameScale / 1.5f).toInt().coerceIn(1, 4)
        val stepScale = frameScale / subSteps
        val activeAttractors = buildActiveAttractors()

        repeat(subSteps) {
            val interactionStep = simulationStepCount % 2L == 0L

            for (p in backgroundParticles) {
                p.update(
                    attractors = activeAttractors,
                    interactionStep = interactionStep,
                    maxDistance = maxDistance,
                    maxDistanceSq = maxDistanceSq,
                    flowTime = flowTime,
                    width = width,
                    height = height,
                    stepScale = stepScale
                )
            }

            for (ball in particles) {
                ball.applyGravity = true
                applyPetrovaFlow(ball)
                for (a in activeAttractors) interactWithPoint(ball, a)
                ball.update(width, height, stepScale)
            }

            flowTime += FLOW_SPEED_PER_60HZ_FRAME * stepScale
            simulationStepCount++
        }

        simTimeSeconds += dtSeconds
        pulse?.let {
            // V1 used 0.84 per 60 Hz frame. Exponential scaling keeps the same real-time decay.
            it.life *= 0.84f.pow(frameScale)
            if (it.life < 0.05f) pulse = null
        }
    }

    private fun buildActiveAttractors(): List<Attractor> {
        val current = attractors.get()
        val p = pulse ?: return current
        val extra = Attractor(p.x, p.y, 1f, p.life)
        return if (current.isEmpty()) listOf(extra) else current + extra
    }

    private fun applyLauncherOffsetImpulse() {
        val next = pendingOffset ?: return
        pendingOffset = null
        val previous = launcherOffset
        launcherOffset = next
        if (previous == null) return

        val delta = (next - previous).coerceIn(-0.35f, 0.35f)
        if (abs(delta) < 0.0001f) return

        for (ball in particles) {
            ball.vx -= delta * (4.5f + ball.depth * 7f)
            ball.energy = max(ball.energy, min(1f, abs(delta) * 5f))
        }
        for (p in backgroundParticles) {
            p.vx -= delta * (0.12f + p.depth * 0.25f)
        }
    }

    private fun applyPetrovaFlow(ball: Ball) {
        if ((simulationStepCount + ball.flowSlot) % FLOW_RECALC_STEPS == 0L) {
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

    private fun rebuildParticleCounts() {
        if (width <= 1 || height <= 1) return

        val foregroundDivisor = (64 + 24 - config.foregroundDensity).toFloat()
        val baseForeground = (((width / foregroundDivisor) *
            (height / foregroundDivisor)).toInt()).coerceIn(FOREGROUND_MIN, FOREGROUND_MAX)

        val backgroundDivisor = (1400 + 350 - config.backgroundDensity).toLong()
        val baseBackground = ((width.toLong() * height.toLong()) / backgroundDivisor)
            .toInt().coerceIn(BACKGROUND_MIN, BACKGROUND_MAX)

        val foregroundCount = (baseForeground * config.powerMode.foregroundMultiplier)
            .toInt().coerceIn(90, FOREGROUND_MAX)
        val backgroundCount = (baseBackground * config.powerMode.backgroundMultiplier)
            .toInt().coerceIn(300, BACKGROUND_MAX)

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

    private fun renderGpu(frameScale: Float) {
        val source = trailIndex
        val target = 1 - source

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, trailFramebuffers[target])
        GLES20.glViewport(0, 0, trailWidth, trailHeight)
        GLES20.glDisable(GLES20.GL_BLEND)

        // V1 faded by trailAlpha every 60 Hz frame. Frame-rate-independent retention
        // keeps the visual trail lifetime nearly identical at 15, 20, 30 or 60 FPS.
        val retention = (1f - config.trailAlpha).coerceIn(0.01f, 0.99f).pow(frameScale)
        drawTexture(trailTextures[source], retention)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendEquation(GLES20.GL_FUNC_ADD)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        drawParticles(trailScale)
        GLES20.glDisable(GLES20.GL_BLEND)

        trailIndex = target

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawTexture(trailTextures[trailIndex], 1f)
    }

    private fun drawParticles(pointScale: Float) {
        particleBuffer.clear()
        var count = 0

        fun addVertex(x: Float, y: Float, size: Float, alpha: Float, kind: Float, energy: Float = 0f) {
            if (count >= MAX_RENDER_VERTICES) return
            particleBuffer.put(x)
            particleBuffer.put(y)
            particleBuffer.put(size)
            particleBuffer.put(alpha)
            particleBuffer.put(kind)
            particleBuffer.put(energy)
            count++
        }

        for (p in backgroundParticles) {
            val alpha = p.alpha(simTimeSeconds)
            addVertex(p.x, p.y, max(3.0f, p.size.toFloat() * 2.9f), alpha, KIND_BACKGROUND)
            if (p.spark) {
                addVertex(p.x, p.y, max(4f, p.size * 3.6f), min(0.75f, alpha * 1.4f), KIND_SPARK)
            }
        }

        for (ball in particles) {
            val pulseValue = ball.pulse(simTimeSeconds)
            if (ball.bokeh) {
                var size = ball.r * lerp(4f, 11f, ball.depth)
                size *= pulseValue * (1f + ball.energy * 0.5f)
                val drawSize = max(30f, size * 3.1f)
                val alpha = (0.10f + ball.depth * 0.12f + ball.energy * 0.12f).coerceIn(0.07f, 0.40f)
                addVertex(ball.x, ball.y, drawSize, alpha, KIND_BOKEH, ball.energy)
            } else {
                var size = ball.r * lerp(0.7f, 1.8f, ball.depth)
                size *= 1f + ball.energy * 0.8f
                val drawSize = max(8f, size * (7.5f + ball.energy * 3.5f) * pulseValue)
                val alpha = (0.25f + ball.depth * 0.45f + ball.energy * 0.30f).coerceIn(0.15f, 1f)
                addVertex(ball.x, ball.y, drawSize, alpha, KIND_ASTROPHAGE, ball.energy)

                if (ball.spark) {
                    val sparkSize = max(5.0f, drawSize * 0.34f)
                    val sparkAlpha = (0.25f + ball.energy * 0.55f).coerceIn(0f, 0.9f)
                    addVertex(ball.x, ball.y, sparkSize, sparkAlpha, KIND_SPARK, ball.energy)
                }
            }
        }

        if (count <= 0) return
        particleBuffer.flip()

        GLES20.glUseProgram(particleProgram)
        val positionLoc = GLES20.glGetAttribLocation(particleProgram, "aPosition")
        val sizeLoc = GLES20.glGetAttribLocation(particleProgram, "aSize")
        val alphaLoc = GLES20.glGetAttribLocation(particleProgram, "aAlpha")
        val kindLoc = GLES20.glGetAttribLocation(particleProgram, "aKind")
        val energyLoc = GLES20.glGetAttribLocation(particleProgram, "aEnergy")
        val resolutionLoc = GLES20.glGetUniformLocation(particleProgram, "uResolution")
        val pointScaleLoc = GLES20.glGetUniformLocation(particleProgram, "uPointScale")

        val stride = FLOATS_PER_PARTICLE * 4
        particleBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionLoc)
        GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, stride, particleBuffer)
        particleBuffer.position(2)
        GLES20.glEnableVertexAttribArray(sizeLoc)
        GLES20.glVertexAttribPointer(sizeLoc, 1, GLES20.GL_FLOAT, false, stride, particleBuffer)
        particleBuffer.position(3)
        GLES20.glEnableVertexAttribArray(alphaLoc)
        GLES20.glVertexAttribPointer(alphaLoc, 1, GLES20.GL_FLOAT, false, stride, particleBuffer)
        particleBuffer.position(4)
        GLES20.glEnableVertexAttribArray(kindLoc)
        GLES20.glVertexAttribPointer(kindLoc, 1, GLES20.GL_FLOAT, false, stride, particleBuffer)
        particleBuffer.position(5)
        GLES20.glEnableVertexAttribArray(energyLoc)
        GLES20.glVertexAttribPointer(energyLoc, 1, GLES20.GL_FLOAT, false, stride, particleBuffer)

        GLES20.glUniform2f(resolutionLoc, width.toFloat(), height.toFloat())
        GLES20.glUniform1f(pointScaleLoc, pointScale)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count)

        GLES20.glDisableVertexAttribArray(positionLoc)
        GLES20.glDisableVertexAttribArray(sizeLoc)
        GLES20.glDisableVertexAttribArray(alphaLoc)
        GLES20.glDisableVertexAttribArray(kindLoc)
        GLES20.glDisableVertexAttribArray(energyLoc)
        particleBuffer.position(0)
    }

    private fun drawTexture(texture: Int, multiplier: Float) {
        GLES20.glUseProgram(textureProgram)
        val positionLoc = GLES20.glGetAttribLocation(textureProgram, "aPosition")
        val texCoordLoc = GLES20.glGetAttribLocation(textureProgram, "aTexCoord")
        val textureLoc = GLES20.glGetUniformLocation(textureProgram, "uTexture")
        val multiplierLoc = GLES20.glGetUniformLocation(textureProgram, "uMultiplier")

        quadBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionLoc)
        GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)
        quadBuffer.position(2)
        GLES20.glEnableVertexAttribArray(texCoordLoc)
        GLES20.glVertexAttribPointer(texCoordLoc, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(textureLoc, 0)
        GLES20.glUniform1f(multiplierLoc, multiplier)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionLoc)
        GLES20.glDisableVertexAttribArray(texCoordLoc)
        quadBuffer.position(0)
    }

    private fun ensureEgl(): Boolean {
        if (!holder.surface.isValid) return false

        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false

            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false

            val configAttributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(
                    eglDisplay,
                    configAttributes,
                    0,
                    configs,
                    0,
                    configs.size,
                    numConfigs,
                    0
                ) || numConfigs[0] <= 0
            ) {
                return false
            }
            eglConfig = configs[0] ?: return false
        }

        val chosenConfig = eglConfig ?: return false

        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            val contextAttributes = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            eglContext = EGL14.eglCreateContext(
                eglDisplay,
                chosenConfig,
                EGL14.EGL_NO_CONTEXT,
                contextAttributes,
                0
            )
            if (eglContext == null || eglContext == EGL14.EGL_NO_CONTEXT) return false
        }

        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            val surfaceAttributes = intArrayOf(EGL14.EGL_NONE)
            Log.d(TAG, "Creating EGL window surface...")
            eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay,
                chosenConfig,
                holder.surface,
                surfaceAttributes,
                0
            )
            if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
                return false
            }
            Log.d(TAG, "EGL window surface created")
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.e(TAG, "eglMakeCurrent failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
            releaseEglSurface()
            return false
        }
        EGL14.eglSwapInterval(eglDisplay, 1)
        return true
    }

    private fun ensureGlResources(): Boolean {
        if (glReady) return true

        particleProgram = createProgram(PARTICLE_VERTEX_SHADER, PARTICLE_FRAGMENT_SHADER)
        textureProgram = createProgram(TEXTURE_VERTEX_SHADER, TEXTURE_FRAGMENT_SHADER)
        if (particleProgram == 0 || textureProgram == 0) {
            Log.e(TAG, "Unable to create OpenGL shader programs")
            return false
        }

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        glReady = true
        buffersDirty = true
        return true
    }

    private fun recreateTrailBuffers(): Boolean {
        deleteTrailBuffers()

        trailScale = config.powerMode.trailScale(width, height)
        trailWidth = max(1, (width * trailScale).toInt())
        trailHeight = max(1, (height * trailScale).toInt())

        GLES20.glGenTextures(2, trailTextures, 0)
        GLES20.glGenFramebuffers(2, trailFramebuffers, 0)

        for (i in 0..1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, trailTextures[i])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_RGBA,
                trailWidth,
                trailHeight,
                0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                null
            )

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, trailFramebuffers[i])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,
                trailTextures[i],
                0
            )
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                Log.e(TAG, "Trail framebuffer $i is incomplete")
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                deleteTrailBuffers()
                return false
            }

            GLES20.glViewport(0, 0, trailWidth, trailHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        trailIndex = 0
        buffersDirty = false
        return true
    }

    private fun deleteTrailBuffers() {
        if (trailTextures[0] != 0 || trailTextures[1] != 0) {
            GLES20.glDeleteTextures(2, trailTextures, 0)
            trailTextures[0] = 0
            trailTextures[1] = 0
        }
        if (trailFramebuffers[0] != 0 || trailFramebuffers[1] != 0) {
            GLES20.glDeleteFramebuffers(2, trailFramebuffers, 0)
            trailFramebuffers[0] = 0
            trailFramebuffers[1] = 0
        }
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) return 0
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (fragmentShader == 0) {
            GLES20.glDeleteShader(vertexShader)
            return 0
        }

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)

        if (status[0] == 0) {
            Log.e(TAG, "Program link failed: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun releaseEglSurface() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE) {
            Log.d(TAG, "Releasing EGL surface")
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            eglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            if (glReady) {
                deleteTrailBuffers()
                if (particleProgram != 0) GLES20.glDeleteProgram(particleProgram)
                if (textureProgram != 0) GLES20.glDeleteProgram(textureProgram)
            }
        }

        releaseEglSurface()

        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglContext != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(eglDisplay, eglContext)
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(eglDisplay)

        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglConfig = null
        glReady = false
        particleProgram = 0
        textureProgram = 0
    }

    private fun randomRange(min: Float, max: Float): Float = min + random.nextFloat() * (max - min)
    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private data class Pulse(val x: Float, val y: Float, var life: Float)
}

private class Ball(x0: Float, y0: Float, radius: Float, random: Random) {
    var x = x0
    var y = y0
    var vx = randomRange(random, -0.15f, 0.15f)
    var vy = randomRange(random, -0.15f, 0.15f)
    private var ax = 0f
    private var ay = 0f
    val r = if (radius.isFinite()) radius else 1f
    private val massScale = max(0.01f, ((PI.toFloat() * 2f * r) * 0.5f) * 0.01f)

    var applyGravity = true
    val depth = random.nextFloat().toDouble().pow(1.8).toFloat()
    val bokeh = random.nextFloat() < 0.12f
    val spark = random.nextFloat() < 0.06f
    var energy = 0f
    private val pulseOffset = randomRange(random, 0f, PI.toFloat() * 2f)
    private val pulseSpeedPerSecond = randomRange(random, 0.60f, 2.10f)
    var flowAngle = randomRange(random, 0f, PI.toFloat() * 2f)
    val flowSlot = random.nextInt(5)

    fun applyForce(fx: Float, fy: Float) {
        if (!fx.isFinite() || !fy.isFinite()) return
        ax += fx
        ay += fy
        if (!ax.isFinite() || !ay.isFinite()) {
            ax = 0f
            ay = 0f
            return
        }
        val magnitude = hypot(fx, fy)
        if (magnitude.isFinite()) energy = max(energy, (magnitude / 2.5f).coerceIn(0f, 1f))
    }

    fun update(width: Int, height: Int, stepScale: Float) {
        vx += ax * massScale * stepScale
        vy += ay * massScale * stepScale

        val damping = 0.992f.pow(stepScale)
        vx *= damping
        vy *= damping

        if (!vx.isFinite() || !vy.isFinite()) {
            vx = 0f
            vy = 0f
        }

        val maxSpeed = 4f + energy * 3f
        val speedSq = vx * vx + vy * vy
        if (speedSq.isFinite() && speedSq > maxSpeed * maxSpeed && speedSq > 0f) {
            val scale = maxSpeed / sqrt(speedSq)
            vx *= scale
            vy *= scale
        }

        x += vx * stepScale
        y += vy * stepScale
        if (!x.isFinite() || !y.isFinite()) {
            x = width * 0.5f
            y = height * 0.5f
            vx = 0f
            vy = 0f
        }

        ax = 0f
        ay = 0f
        energy = (energy * 0.94f.pow(stepScale)).coerceIn(0f, 1f)
        edges(width, height)
    }

    fun pulse(simTimeSeconds: Float): Float =
        0.85f + sin(simTimeSeconds * pulseSpeedPerSecond + pulseOffset) * 0.15f

    private fun edges(width: Int, height: Int) {
        val margin = max(5f, r * 5f)
        if (x > width + margin) x = -margin else if (x < -margin) x = width + margin
        if (y > height + margin) y = -margin else if (y < -margin) y = height + margin
    }

    companion object {
        private fun randomRange(random: Random, min: Float, max: Float): Float =
            min + random.nextFloat() * (max - min)
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
    private val twinkleSpeedPerSecond = randomRange(random, 0.24f, 1.08f)

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
        height: Int,
        stepScale: Float
    ) {
        val wobbleAngle = flowTime * wobbleSpeed + phase
        x += (vx + cos(wobbleAngle) * wobble) * stepScale
        y += (vy + sin(wobbleAngle * 1.13f) * wobble) * stepScale

        if (!x.isFinite() || !y.isFinite()) {
            x = width * 0.5f
            y = height * 0.5f
            vx = 0f
            vy = 0f
        }

        if (interactionStep && attractors.isNotEmpty()) {
            for (a in attractors) {
                val dx = a.x - x
                val dy = a.y - y
                val distanceSq = dx * dx + dy * dy
                if (!distanceSq.isFinite() || distanceSq <= 0f || distanceSq > maxDistanceSq) continue
                val distance = sqrt(distanceSq)
                val influence = (1f - distance / maxDistance) * a.strength
                if (influence <= 0f) continue
                val nx = dx / distance
                val ny = dy / distance
                val attraction = influence * 0.004f * stepScale
                val swirl = influence * 0.0018f * a.direction * stepScale
                vx += nx * attraction - ny * swirl
                vy += ny * attraction + nx * swirl
            }

            if (!vx.isFinite() || !vy.isFinite()) {
                vx = 0f
                vy = 0f
            }

            val speedSq = vx * vx + vy * vy
            val maxSpeed = 0.14f + depth * 0.22f
            if (speedSq.isFinite() && speedSq > maxSpeed * maxSpeed && speedSq > 0f) {
                val scale = maxSpeed / sqrt(speedSq)
                vx *= scale
                vy *= scale
            }
        }

        val margin = 4f
        if (x > width + margin) x = -margin else if (x < -margin) x = width + margin
        if (y > height + margin) y = -margin else if (y < -margin) y = height + margin
    }

    fun alpha(simTimeSeconds: Float): Float {
        val twinkle = 0.78f + sin(simTimeSeconds * twinkleSpeedPerSecond + twinkleOffset) * 0.22f
        return (baseAlpha * twinkle).coerceIn(0.04f, 0.60f)
    }

    companion object {
        private fun randomRange(random: Random, min: Float, max: Float): Float =
            min + random.nextFloat() * (max - min)
        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    }
}

private object SimpleNoise {
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

    private fun smooth(t: Float): Float = t * t * (3f - 2f * t)
    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun hash(x: Int, y: Int, z: Int): Float {
        var n = x * 374761393 + y * 668265263 + z * 2147483647
        n = (n xor (n ushr 13)) * 1274126177
        n = n xor (n ushr 16)
        return (n and 0x7fffffff) / 2147483647f
    }
}

private const val PARTICLE_VERTEX_SHADER = """
attribute vec2 aPosition;
attribute float aSize;
attribute float aAlpha;
attribute float aKind;
attribute float aEnergy;
uniform vec2 uResolution;
uniform float uPointScale;
varying float vAlpha;
varying float vKind;
varying float vEnergy;

void main() {
    vec2 normalized = aPosition / uResolution;
    vec2 ndc = vec2(normalized.x * 2.0 - 1.0, 1.0 - normalized.y * 2.0);
    gl_Position = vec4(ndc, 0.0, 1.0);
    gl_PointSize = max(1.0, aSize * uPointScale);
    vAlpha = aAlpha;
    vKind = aKind;
    vEnergy = aEnergy;
}
"""

private const val PARTICLE_FRAGMENT_SHADER = """
precision mediump float;
varying float vAlpha;
varying float vKind;
varying float vEnergy;

void main() {
    vec2 p = gl_PointCoord - vec2(0.5);
    float d = length(p) * 2.0;
    if (d > 1.0) discard;

    vec3 color;
    float alpha;

    if (vKind < 0.5) {
        float core = 1.0 - smoothstep(0.10, 1.0, d);
        color = vec3(1.0, 0.055, 0.075);
        alpha = vAlpha * core;
    } else if (vKind < 1.5) {
        float core = 1.0 - smoothstep(0.00, 0.16, d);
        float inner = 1.0 - smoothstep(0.04, 0.42, d);
        float outer = 1.0 - smoothstep(0.18, 1.00, d);
        color = vec3(1.0, 0.035 + inner * 0.24, 0.045 + inner * 0.20);
        color += vec3(core * (0.75 + vEnergy * 0.25));
        alpha = vAlpha * (outer * 0.30 + inner * 0.52 + core * 0.90);
    } else if (vKind < 2.5) {
        float soft = 1.0 - smoothstep(0.05, 1.0, d);
        float centre = 1.0 - smoothstep(0.00, 0.35, d);
        color = vec3(1.0, 0.08 + centre * 0.16, 0.10 + centre * 0.14);
        alpha = vAlpha * soft * 0.72;
    } else {
        float core = 1.0 - smoothstep(0.00, 0.28, d);
        float glow = 1.0 - smoothstep(0.10, 1.0, d);
        color = vec3(1.0, 0.68 + core * 0.32, 0.68 + core * 0.32);
        alpha = vAlpha * (glow * 0.45 + core * 0.90);
    }

    gl_FragColor = vec4(color, clamp(alpha, 0.0, 1.0));
}
"""

private const val TEXTURE_VERTEX_SHADER = """
attribute vec2 aPosition;
attribute vec2 aTexCoord;
varying vec2 vTexCoord;

void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
    vTexCoord = aTexCoord;
}
"""

private const val TEXTURE_FRAGMENT_SHADER = """
precision mediump float;
uniform sampler2D uTexture;
uniform float uMultiplier;
varying vec2 vTexCoord;

void main() {
    vec4 c = texture2D(uTexture, vTexCoord) * uMultiplier;
    gl_FragColor = vec4(c.rgb, 1.0);
}
"""
