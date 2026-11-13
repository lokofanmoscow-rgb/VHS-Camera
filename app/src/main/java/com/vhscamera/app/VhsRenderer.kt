package com.vhscamera.app

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The tape. Owns the GL thread: takes camera frames from a SurfaceTexture, runs them
 * through the VHS passes of the chosen look and shows the result in the viewfinder and,
 * while recording, feeds the same frames to the video encoder.
 *
 * CLASSIC: three passes matched to the reference clips (pass_a/b/c).
 * REAL_VHS: a 90s CCD camcorder recording on VHS SP, played into a capture card
 * (real_cam, real_smear, real_y, real_c, real_out).
 */
class VhsRenderer(private val context: Context, private val listener: Listener) {

    interface Listener {
        /** GL thread. The camera should start streaming into [surfaceTexture]. */
        fun onCameraTextureReady(surfaceTexture: SurfaceTexture)
        fun onRendererError(error: Throwable)
        /** GL thread. In the REAL_VHS look the zoom is done by the camera itself (like optics). */
        fun onCameraZoom(ratio: Float)
    }

    /** Receives the recording timeline from the GL thread. */
    interface RecordingSink {
        /** First frame of the recording has just been rendered (its timestamp is 0). */
        fun onFirstVideoFrame(nanoTime: Long)
        /** The last frame has been rendered; the encoder input can be closed. */
        fun onVideoFinished()
    }

    private val thread = HandlerThread("vhs-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private val random = Random()

    // ---- GL thread state ----
    private var egl: EglCore? = null
    private var pbuffer: EGLSurface? = null
    private var previewSurface: EGLSurface? = null
    private var previewW = 0
    private var previewH = 0
    private var encoderSurface: EGLSurface? = null

    private var camTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val texMatrix = FloatArray(16)
    private val camXform = FloatArray(9)

    private var progCam: Program? = null
    private var progRCam: Program? = null
    private var progRSmear: Program? = null
    private var progRY: Program? = null
    private var progRC: Program? = null
    private var progROut: Program? = null
    private var progA: Program? = null
    private var progB: Program? = null
    private var progC: Program? = null
    private var progBlit: Program? = null
    private var noiseTex = 0
    private var osdTex = 0
    private val texVga = IntArray(2)      // camera picture, this frame and the one before
    private val fboVga = IntArray(2)
    private var vgaCur = 0
    private val texRCam = IntArray(1)     // REAL: one camcorder field (YIQ)
    private val fboRCam = IntArray(1)
    private val texRSmear = IntArray(1)
    private val fboRSmear = IntArray(1)
    private val texRY = IntArray(1)
    private val fboRY = IntArray(1)
    private val texRC = IntArray(1)
    private val fboRC = IntArray(1)
    private val texA = IntArray(1)
    private val fboA = IntArray(1)
    private val texB = IntArray(1)
    private val fboB = IntArray(1)
    private val texC = IntArray(1)
    private val fboC = IntArray(1)
    private var quad: FloatBuffer? = null
    private var frameIndex = 0L
    private var lastTimestamp = 0L

    private var osd: Osd? = null
    private var osdKey = ""

    // camera geometry
    private var camW = 1440
    private var camH = 1080
    private var sensorOrientation = 90
    private var frontFacing = false
    private var displayRotation = Surface.ROTATION_90

    // zoom
    private var zoom = 1f
    private var zoomDir = 0
    private var cameraMaxZoom = 1f
    private var lastCameraZoom = 1f
    private var gainStops = 0f
    @Volatile private var exposureSeconds = 1f / 60f
    // REAL: the camera's frame interval, smoothed, and whether it gives 60 fields a second
    private var frameInterval = 1f / 30f
    @Volatile private var fields60 = false

    // options
    private var stampEnabled = true
    @Volatile
    private var look = Look.CLASSIC

    // recording
    private var recState = REC_IDLE
    private var recSink: RecordingSink? = null
    private var recT0 = -1L
    private var recLastPts = -1L
    private var recHeavyStart = false
    private var recEndFrame = 0
    private var recFrames = 0

    @Volatile
    var currentZoom = 1f
        private set

    /** True while the zoom motor is running (the REAL_VHS soundtrack has it). */
    @Volatile
    var zooming = false
        private set

    val maxZoom: Float get() = if (look == Look.REAL_VHS) MAX_ZOOM_REAL else MAX_ZOOM

    fun start() {
        handler.post {
            try {
                initGl()
                surfaceTexture?.let { listener.onCameraTextureReady(it) }
            } catch (t: Throwable) {
                Log.e(TAG, "GL init failed", t)
                listener.onRendererError(t)
            }
        }
    }

    /** Attaches (or with null detaches) the viewfinder surface. Blocks until the GL thread has done it. */
    fun setPreviewSurface(surface: Surface?, width: Int, height: Int) {
        runSync {
            val e = egl ?: return@runSync
            previewSurface?.let {
                e.makeCurrent(pbuffer!!)
                e.releaseSurface(it)
            }
            previewSurface = null
            if (surface != null && surface.isValid) {
                try {
                    previewSurface = e.createWindowSurface(surface)
                    previewW = width
                    previewH = height
                } catch (t: Throwable) {
                    Log.e(TAG, "preview surface failed", t)
                }
            }
        }
    }

    fun setCameraGeometry(width: Int, height: Int, sensorOrientation: Int, front: Boolean) {
        handler.post {
            camW = width
            camH = height
            this.sensorOrientation = sensorOrientation
            frontFacing = front
            surfaceTexture?.setDefaultBufferSize(width, height)
        }
    }

    fun setDisplayRotation(rotation: Int) {
        handler.post { displayRotation = rotation }
    }

    /** -1 = zoom out (W), +1 = zoom in (T), 0 = hold. */
    fun setZoomDirection(dir: Int) {
        handler.post { zoomDir = dir }
    }

    fun resetZoom() {
        handler.post {
            zoom = 1f
            currentZoom = 1f
            pushCameraZoom()
        }
    }

    /** How far the camera itself can zoom (1 = not at all). */
    fun setCameraMaxZoom(max: Float) {
        handler.post {
            cameraMaxZoom = max.coerceAtLeast(1f)
            lastCameraZoom = -1f
            pushCameraZoom()
        }
    }

    /** Camera gain in stops above daylight, from the camera's own exposure (REAL_VHS noise). */
    fun setCameraGain(stops: Float, exposure: Float) {
        handler.post {
            gainStops += (stops - gainStops) * 0.15f
            exposureSeconds = exposure
        }
    }

    /** REAL: fields a second the recording gets - 60 when the camera delivers 60 fps, else 30. */
    val fieldRate: Int get() = if (fields60) 60 else 30

    fun setStampEnabled(enabled: Boolean) {
        handler.post { stampEnabled = enabled }
    }

    /** Picks the look of the picture. */
    fun setLook(look: Look) {
        handler.post {
            if (this.look != look) {
                this.look = look
                zoom = 1f
                currentZoom = 1f
                osdKey = ""
                lastCameraZoom = -1f
                pushCameraZoom()
            }
        }
    }

    fun startRecording(encoderInput: Surface, sink: RecordingSink) {
        handler.post {
            val e = egl
            if (e == null || recState != REC_IDLE) {
                sink.onVideoFinished()
                return@post
            }
            try {
                encoderSurface = e.createWindowSurface(encoderInput)
            } catch (t: Throwable) {
                Log.e(TAG, "encoder surface failed", t)
                sink.onVideoFinished()
                return@post
            }
            recSink = sink
            recT0 = -1L
            recLastPts = -1L
            recEndFrame = 0
            recFrames = 0
            recHeavyStart = random.nextFloat() < 0.6f
            recState = REC_RUNNING
        }
    }

    /** CLASSIC plays the tape-stop glitch for a few frames; a real camcorder just stops. */
    fun stopRecording() {
        handler.post {
            if (recState == REC_RUNNING) {
                if (recT0 < 0 || look == Look.REAL_VHS) finishRecording() else recState = REC_ENDING
            }
        }
    }

    fun release() {
        runSync {
            if (recState != REC_IDLE) finishRecording()
            releaseGl()
        }
        thread.quitSafely()
    }

    private fun runSync(block: () -> Unit) {
        if (Thread.currentThread() === thread) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        val posted = handler.post {
            try {
                block()
            } catch (t: Throwable) {
                Log.e(TAG, "GL task failed", t)
            } finally {
                latch.countDown()
            }
        }
        if (posted) latch.await(3, TimeUnit.SECONDS)
    }

    // ---------------------------------------------------------------- GL thread

    private fun asset(name: String): String =
        context.assets.open(name).bufferedReader().use { it.readText() }

    private fun initGl() {
        val e = EglCore()
        egl = e
        val pb = e.createPbufferSurface(4, 4)
        pbuffer = pb
        e.makeCurrent(pb)

        val vs = asset("shaders/quad.vert")
        progCam = Program(vs, "#extension GL_OES_EGL_image_external : require\n#define CAM_SAMPLER samplerExternalOES\n" + asset("shaders/pass_cam.frag"))
        progRCam = Program(vs, asset("shaders/real_cam.frag"))
        progRSmear = Program(vs, asset("shaders/real_smear.frag"))
        progRY = Program(vs, asset("shaders/real_y.frag"))
        progRC = Program(vs, asset("shaders/real_c.frag"))
        progROut = Program(vs, asset("shaders/real_out.frag"))
        progA = Program(vs, asset("shaders/pass_a.frag"))
        progB = Program(vs, asset("shaders/pass_b.frag"))
        progC = Program(vs, asset("shaders/pass_c.frag"))
        progBlit = Program(vs, asset("shaders/blit.frag"))

        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }

        // camera texture
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        camTex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // noise
        val noise = ByteArray(256 * 256 * 4)
        Random(12345L).nextBytes(noise)
        noiseTex = newTexture(256, 256, ByteBuffer.wrap(noise), GLES20.GL_NEAREST, GLES20.GL_REPEAT)

        // on-screen display
        osd = Osd(PixelFont(asset("osd_font.txt")))
        osdTex = newTexture(Osd.WIDTH, Osd.HEIGHT, null, GLES20.GL_LINEAR, GLES20.GL_CLAMP_TO_EDGE)
        osdKey = ""

        newTarget(VGA_W, VGA_H, texVga, fboVga, 0)
        newTarget(VGA_W, VGA_H, texVga, fboVga, 1)
        newTarget(REAL_W, REAL_FIELD, texRCam, fboRCam, 0)
        newTarget(REAL_W, 1, texRSmear, fboRSmear, 0)
        newTarget(REAL_W, REAL_FIELD, texRY, fboRY, 0)
        newTarget(REAL_CW, REAL_FIELD, texRC, fboRC, 0)
        newTarget(TAPE_W, TAPE_H, texA, fboA, 0)
        newTarget(TAPE_W, TAPE_H, texB, fboB, 0)
        newTarget(OUT_W, OUT_H, texC, fboC, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        val st = SurfaceTexture(camTex)
        st.setDefaultBufferSize(camW, camH)
        st.setOnFrameAvailableListener({ onFrame() }, handler)
        surfaceTexture = st
    }

    private fun newTexture(w: Int, h: Int, data: ByteBuffer?, filter: Int, wrap: Int): Int {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, data)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrap)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrap)
        return t[0]
    }

    private fun newTarget(w: Int, h: Int, tex: IntArray, fbo: IntArray, index: Int) {
        tex[index] = newTexture(w, h, null, GLES20.GL_LINEAR, GLES20.GL_CLAMP_TO_EDGE)
        val f = IntArray(1)
        GLES20.glGenFramebuffers(1, f, 0)
        fbo[index] = f[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[index], 0)
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) { "framebuffer incomplete: $status" }
    }

    private fun releaseGl() {
        val e = egl ?: return
        try {
            pbuffer?.let { e.makeCurrent(it) }
            surfaceTexture?.setOnFrameAvailableListener(null)
            surfaceTexture?.release()
            surfaceTexture = null
            previewSurface?.let { e.releaseSurface(it) }
            previewSurface = null
            encoderSurface?.let { e.releaseSurface(it) }
            encoderSurface = null
            pbuffer?.let { e.releaseSurface(it) }
            pbuffer = null
        } catch (t: Throwable) {
            Log.w(TAG, "releaseGl", t)
        }
        e.release()
        egl = null
    }

    private fun finishRecording() {
        val e = egl
        encoderSurface?.let { s ->
            if (e != null) {
                pbuffer?.let { e.makeCurrent(it) }
                e.releaseSurface(s)
            }
        }
        encoderSurface = null
        recState = REC_IDLE
        Log.i(TAG, "recording stopped after $recFrames frames, ${recLastPts / 1_000_000} ms")
        val sink = recSink
        recSink = null
        sink?.onVideoFinished()
    }

    private fun onFrame() {
        val e = egl ?: return
        val st = surfaceTexture ?: return
        try {
            e.makeCurrent(previewSurface ?: pbuffer!!)
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            val ts = st.timestamp
            val dt = if (lastTimestamp != 0L) ((ts - lastTimestamp) / 1e9f).coerceIn(0f, 0.25f) else 1f / 30f
            lastTimestamp = ts
            // a camera running at 60 fps gives REAL its 60 fields a second
            frameInterval += (dt - frameInterval) * 0.1f
            fields60 = if (fields60) frameInterval < 0.025f else frameInterval < 0.019f
            render(e, ts, dt)
        } catch (t: Throwable) {
            Log.e(TAG, "frame failed", t)
            if (recState != REC_IDLE) finishRecording()
        }
    }

    private fun render(e: EglCore, ts: Long, dt: Float) {
        // Motor zoom, starts and stops dead. CLASSIC: constant speed in magnification, as on
        // the reference clips. REAL: a camcorder's power zoom, constant speed in ratio.
        val real = look == Look.REAL_VHS
        val before = zoom
        if (zoomDir != 0) {
            zoom = if (real) {
                (zoom * Math.exp((zoomDir * ZOOM_RATE_REAL * dt).toDouble()).toFloat()).coerceIn(1f, MAX_ZOOM_REAL)
            } else {
                (zoom + zoomDir * ZOOM_SPEED * dt).coerceIn(1f, MAX_ZOOM)
            }
            currentZoom = zoom
        }
        zooming = zoomDir != 0 && zoom != before
        if (real) pushCameraZoom()

        // Recording timeline.
        var colourLock = 1f
        var wobble = 1f
        var staticAmount = 0f
        var bandTop = 0f
        var bandHeight = 0f
        var bandShift = 0f
        var bandSmear = 0f
        var showPlay = false
        var pts = 0L
        if (recState != REC_IDLE) {
            if (recT0 < 0) {
                recT0 = ts
                recSink?.onFirstVideoFrame(System.nanoTime())
            }
            pts = ts - recT0
            if (pts <= recLastPts) pts = recLastPts + 1_000_000L
            recLastPts = pts
            recFrames++
            val t = pts / 1e9f
            if (real) {
                // a camcorder shot starts clean; the servo and the colour take a moment to lock
                colourLock = smoothstep(0.12f, 0.45f, t)
                wobble = 1f + 4f * (1f - smoothstep(0f, 0.3f, t))
            }
            showPlay = !real && t < PLAY_SECONDS
            if (!real && recHeavyStart && t > 0.02f && t < 0.17f) staticAmount = 1f
            if (!real && t < START_BAND_SECONDS) {
                val k = t / START_BAND_SECONDS
                bandTop = 0.72f + 0.26f * k
                bandHeight = 0.10f * (1f - 0.6f * k)
                bandShift = 0.012f
            }
            if (recState == REC_ENDING) {
                val k = recEndFrame / END_FRAMES.toFloat()
                bandTop = 0.13f + 0.02f * k
                bandHeight = 0.075f
                bandShift = 0.04f + 0.10f * k
                bandSmear = 0.55f
            }
        }

        updateOsd(showPlay)
        updateCamXform()
        if (frameIndex == 0L || frameIndex == 30L) {
            Log.i(TAG, "frame $frameIndex: camera ${camW}x$camH sensor=$sensorOrientation front=$frontFacing " +
                "displayRotation=$displayRotation look=$look texMatrix=${texMatrix.joinToString(",") { "%.2f".format(it) }}")
        }

        val q = quad!!
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, q)

        // Pass 0: phone camera -> the 960x720 camera picture
        vgaCur = 1 - vgaCur
        val cam = progCam!!
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboVga[vgaCur])
        GLES20.glViewport(0, 0, VGA_W, VGA_H)
        cam.use()
        GLES20.glUniform1f(cam.u("uFlipY"), 1f)
        GLES20.glUniformMatrix4fv(cam.u("uTexMatrix"), 1, false, texMatrix, 0)
        GLES20.glUniformMatrix3fv(cam.u("uCamXform"), 1, false, camXform, 0)
        GLES20.glUniform2f(cam.u("uInvSize"), 1f / VGA_W, 1f / VGA_H)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex)
        GLES20.glUniform1i(cam.u("uCam"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        if (real) {
            renderReal(colourLock, wobble)
        } else {
            renderClassic(staticAmount, bandTop, bandHeight, bandShift, bandSmear)
        }

        frameIndex++

        // Show and record the finished frame.
        val blit = progBlit!!
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        blit.use()
        GLES20.glUniform1f(blit.u("uFlipY"), -1f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texC[0])
        GLES20.glUniform1i(blit.u("uTex"), 0)

        previewSurface?.let { ps ->
            GLES20.glViewport(0, 0, previewW, previewH)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            e.swapBuffers(ps)
        }

        val es = encoderSurface
        if (es != null && recState != REC_IDLE) {
            e.makeCurrent(es)
            GLES20.glViewport(0, 0, OUT_W, OUT_H)
            GLES20.glEnableVertexAttribArray(0)
            GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, q)
            blit.use()
            GLES20.glUniform1f(blit.u("uFlipY"), -1f)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texC[0])
            GLES20.glUniform1i(blit.u("uTex"), 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            e.setPresentationTime(es, pts)
            e.swapBuffers(es)

            if (recState == REC_ENDING) {
                recEndFrame++
                if (recEndFrame > END_FRAMES) finishRecording()
            }
        }
    }

    private fun renderClassic(staticAmount: Float, bandTop: Float, bandHeight: Float, bandShift: Float, bandSmear: Float) {
        // Pass A: camera picture -> tape frame (digital zoom, colour, noise)
        val a = progA!!
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboA[0])
        GLES20.glViewport(0, 0, TAPE_W, TAPE_H)
        a.use()
        GLES20.glUniform1f(a.u("uFlipY"), 1f)
        GLES20.glUniform1f(a.u("uZoom"), zoom)
        GLES20.glUniform2f(a.u("uInvSize"), 1f / TAPE_W, 1f / TAPE_H)
        GLES20.glUniform4f(a.u("uRand"), random.nextFloat(), random.nextFloat(), random.nextFloat(), random.nextFloat())
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texVga[vgaCur])
        GLES20.glUniform1i(a.u("uVga"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, noiseTex)
        GLES20.glUniform1i(a.u("uNoise"), 2)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Pass B: horizontal tape filter; lays in the stamp and the side borders
        val b = progB!!
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboB[0])
        b.use()
        GLES20.glUniform1f(b.u("uFlipY"), 1f)
        GLES20.glUniform2f(b.u("uInvSize"), 1f / TAPE_W, 1f / TAPE_H)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texA[0])
        GLES20.glUniform1i(b.u("uTex"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, osdTex)
        GLES20.glUniform1i(b.u("uOsd"), 1)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Pass C: playback artefacts at output size
        val c = progC!!
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboC[0])
        GLES20.glViewport(0, 0, OUT_W, OUT_H)
        c.use()
        GLES20.glUniform1f(c.u("uFlipY"), 1f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texB[0])
        GLES20.glUniform1i(c.u("uCur"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, noiseTex)
        GLES20.glUniform1i(c.u("uNoise"), 2)
        GLES20.glUniform4f(c.u("uRand"), random.nextFloat(), random.nextFloat(), random.nextFloat(), random.nextFloat())
        GLES20.glUniform4f(c.u("uRand2"), random.nextFloat(), random.nextFloat(), random.nextFloat(), random.nextFloat())
        GLES20.glUniform1f(c.u("uStatic"), staticAmount)
        GLES20.glUniform4f(c.u("uBand"), bandTop, bandHeight, bandShift, bandSmear)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun renderReal(colourLock: Float, wobble: Float) {
        val prevCam = 1 - vgaCur
        val digital = (zoom / lastCameraZoom.coerceAtLeast(1f)).coerceAtLeast(1f)
        // At 60 fps every camera frame is a field of its own, the two fields taking turns;
        // at 30 fps each frame is shown as one field. A phone exposes far shorter than a
        // camcorder's 1/60 s in daylight: a share of the frame before stands in for the blur.
        val field = if (fields60) (frameIndex % 2).toFloat() else 0f
        val shutter = if (fields60) SHUTTER_BLEND * (1f - (exposureSeconds * 60f).coerceIn(0f, 1f)) else 0f

        // 1: the camcorder - one field, tone, colour, date stamp
        val rc = progRCam!!
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboRCam[0])
        GLES20.glViewport(0, 0, REAL_W, REAL_FIELD)
        rc.use()
        GLES20.glUniform1f(rc.u("uFlipY"), 1f)
        GLES20.glUniform1f(rc.u("uZoom"), digital)
        GLES20.glUniform1f(rc.u("uGain"), gainStops)
        GLES20.glUniform1f(rc.u("uField"), field)
        GLES20.glUniform1f(rc.u("uShutter"), shutter)
        bindTex(0, texVga[vgaCur], rc.u("uCur"))
        bindTex(1, texVga[prevCam], rc.u("uPrevCam"))
        bindTex(3, osdTex, rc.u("uOsd"))
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 2: CCD smear per column
        val sm = progRSmear!!
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboRSmear[0])
        GLES20.glViewport(0, 0, REAL_W, 1)
        sm.use()
        GLES20.glUniform1f(sm.u("uFlipY"), 1f)
        bindTex(0, texRCam[0], sm.u("uTex"))
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 3: luminance on tape
        val ry = progRY!!
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboRY[0])
        GLES20.glViewport(0, 0, REAL_W, REAL_FIELD)
        ry.use()
        GLES20.glUniform1f(ry.u("uFlipY"), 1f)
        bindTex(0, texRCam[0], ry.u("uTex"))
        bindTex(1, texRSmear[0], ry.u("uSmear"))
        bindTex(2, noiseTex, ry.u("uNoise"))
        GLES20.glUniform4f(ry.u("uRand"), random.nextFloat(), random.nextFloat(), random.nextFloat(), random.nextFloat())
        GLES20.glUniform1f(ry.u("uGain"), gainStops)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 4: colour on tape
        val rq = progRC!!
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboRC[0])
        GLES20.glViewport(0, 0, REAL_CW, REAL_FIELD)
        rq.use()
        GLES20.glUniform1f(rq.u("uFlipY"), 1f)
        bindTex(0, texRCam[0], rq.u("uTex"))
        bindTex(2, noiseTex, rq.u("uNoise"))
        GLES20.glUniform4f(rq.u("uRand"), random.nextFloat(), random.nextFloat(), random.nextFloat(), random.nextFloat())
        GLES20.glUniform1f(rq.u("uGain"), gainStops)
        GLES20.glUniform1f(rq.u("uColour"), colourLock)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 5: playback into the capture card
        val ro = progROut!!
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboC[0])
        GLES20.glViewport(0, 0, OUT_W, OUT_H)
        ro.use()
        GLES20.glUniform1f(ro.u("uFlipY"), 1f)
        bindTex(0, texRY[0], ro.u("uY"))
        bindTex(1, texRC[0], ro.u("uC"))
        bindTex(2, noiseTex, ro.u("uNoise"))
        GLES20.glUniform4f(ro.u("uRand"), random.nextFloat(), random.nextFloat(), random.nextFloat(), random.nextFloat())
        GLES20.glUniform4f(ro.u("uRand2"), random.nextFloat(), random.nextFloat(), random.nextFloat(), random.nextFloat())
        GLES20.glUniform1f(ro.u("uWobble"), wobble)
        GLES20.glUniform1f(ro.u("uField"), field)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun bindTex(unit: Int, tex: Int, location: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(location, unit)
    }

    /** REAL: hands the zoom to the camera (as far as it goes); the rest is done digitally. */
    private fun pushCameraZoom() {
        val want = if (look == Look.REAL_VHS) zoom.coerceAtMost(cameraMaxZoom) else 1f
        if (Math.abs(want - lastCameraZoom) > 0.002f) {
            lastCameraZoom = want
            listener.onCameraZoom(want)
        }
    }

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun updateOsd(showPlay: Boolean) {
        val now = System.currentTimeMillis()
        val edged = look == Look.REAL_VHS
        val key = "$showPlay|$stampEnabled|$edged|${now / 60000L}"
        if (key == osdKey) return
        osdKey = key
        val o = osd ?: return
        o.draw(showPlay, stampEnabled, now, edged)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, osdTex)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, o.bitmap, 0)
    }

    /**
     * Builds the matrix that takes picture coordinates (x right, y down, 0..1) to the
     * coordinates SurfaceTexture expects: un-mirror the front camera, centre-crop to 4:3
     * and undo the display rotation.
     */
    private fun updateCamXform() {
        val sensorSideways = sensorOrientation == 90 || sensorOrientation == 270
        val natW = if (sensorSideways) camH else camW
        val natH = if (sensorSideways) camW else camH
        val displaySideways = displayRotation == Surface.ROTATION_90 || displayRotation == Surface.ROTATION_270
        val outW = if (displaySideways) natH else natW
        val outH = if (displaySideways) natW else natH
        val aspect = outW.toFloat() / outH.toFloat()
        var kx = 1f
        var ky = 1f
        if (aspect > 4f / 3f) kx = (4f / 3f) / aspect else ky = aspect / (4f / 3f)

        fun map(x: Float, y: Float, out: FloatArray) {
            var u = x
            val v = 1f - y
            if (frontFacing) u = 1f - u
            val u1 = 0.5f + (u - 0.5f) * kx
            val v1 = 0.5f + (v - 0.5f) * ky
            when (displayRotation) {
                Surface.ROTATION_90 -> { out[0] = v1; out[1] = 1f - u1 }
                Surface.ROTATION_180 -> { out[0] = 1f - u1; out[1] = 1f - v1 }
                Surface.ROTATION_270 -> { out[0] = 1f - v1; out[1] = u1 }
                else -> { out[0] = u1; out[1] = v1 }
            }
        }
        val o = FloatArray(2)
        val px = FloatArray(2)
        val py = FloatArray(2)
        map(0f, 0f, o)
        map(1f, 0f, px)
        map(0f, 1f, py)
        camXform[0] = px[0] - o[0]; camXform[1] = px[1] - o[1]; camXform[2] = 0f
        camXform[3] = py[0] - o[0]; camXform[4] = py[1] - o[1]; camXform[5] = 0f
        camXform[6] = o[0]; camXform[7] = o[1]; camXform[8] = 1f
    }

    private class Program(vertex: String, fragment: String) {
        private val id: Int
        private val locations = HashMap<String, Int>()

        init {
            val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
            id = GLES20.glCreateProgram()
            GLES20.glAttachShader(id, vs)
            GLES20.glAttachShader(id, fs)
            GLES20.glBindAttribLocation(id, 0, "aPos")
            GLES20.glLinkProgram(id)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0)
            check(ok[0] == GLES20.GL_TRUE) { "link failed: " + GLES20.glGetProgramInfoLog(id) }
        }

        private fun compile(type: Int, source: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, source)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] == GLES20.GL_TRUE) { "shader compile failed: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }

        fun use() = GLES20.glUseProgram(id)

        fun u(name: String): Int = locations.getOrPut(name) { GLES20.glGetUniformLocation(id, name) }
    }

    companion object {
        private const val TAG = "VhsRenderer"
        const val OUT_W = 960
        const val OUT_H = 720
        private const val TAPE_W = 480
        private const val TAPE_H = 720
        private const val VGA_W = 960             // the camera picture: the 4:3 middle of 720p
        private const val VGA_H = 720
        private const val ZOOM_SPEED = 0.78f      // magnification per second, measured on the reference tapes
        private const val MAX_ZOOM = 6f
        private const val MAX_ZOOM_REAL = 10f      // a camcorder's 10x power zoom
        private const val ZOOM_RATE_REAL = 0.66f  // ln(10) in 3.5 s: wide to tele
        private const val REAL_W = 640            // REAL: samples per line of the video frame
        private const val REAL_FIELD = 240        // REAL: lines of one field
        private const val SHUTTER_BLEND = 0.25f   // REAL: most of the frame before mixed in for a 1/60 s shutter
        private const val REAL_CW = 160           // REAL: colour samples per line
        private const val PLAY_SECONDS = 3.0f
        private const val START_BAND_SECONDS = 0.55f
        private const val END_FRAMES = 8
        private const val REC_IDLE = 0
        private const val REC_RUNNING = 1
        private const val REC_ENDING = 2
    }
}
