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
 * through the three VHS passes and shows the result in the viewfinder and, while
 * recording, feeds the same frames to the video encoder.
 */
class VhsRenderer(private val context: Context, private val listener: Listener) {

    interface Listener {
        /** GL thread. The camera should start streaming into [surfaceTexture]. */
        fun onCameraTextureReady(surfaceTexture: SurfaceTexture)
        fun onRendererError(error: Throwable)
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

    private var progA: Program? = null
    private var progB: Program? = null
    private var progC: Program? = null
    private var progBlit: Program? = null
    private var noiseTex = 0
    private var osdTex = 0
    private val texA = IntArray(1)
    private val fboA = IntArray(1)
    private val texB = IntArray(2)
    private val fboB = IntArray(2)
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
    private var maxZoom = MAX_ZOOM

    // options
    private var stampEnabled = true

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
        }
    }

    fun setStampEnabled(enabled: Boolean) {
        handler.post { stampEnabled = enabled }
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

    /** Plays the tape-stop glitch for a few frames and then finishes the recording. */
    fun stopRecording() {
        handler.post {
            if (recState == REC_RUNNING) {
                if (recT0 < 0) finishRecording() else recState = REC_ENDING
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
        progA = Program(vs, "#extension GL_OES_EGL_image_external : require\n#define CAM_SAMPLER samplerExternalOES\n" + asset("shaders/pass_a.frag"))
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
        osd = Osd(asset("osd_font.txt"))
        osdTex = newTexture(Osd.WIDTH, Osd.HEIGHT, null, GLES20.GL_LINEAR, GLES20.GL_CLAMP_TO_EDGE)
        osdKey = ""

        newTarget(TAPE_W, TAPE_H, texA, fboA, 0)
        newTarget(TAPE_W, TAPE_H, texB, fboB, 0)
        newTarget(TAPE_W, TAPE_H, texB, fboB, 1)
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
            render(e, ts, dt)
        } catch (t: Throwable) {
            Log.e(TAG, "frame failed", t)
            if (recState != REC_IDLE) finishRecording()
        }
    }

    private fun render(e: EglCore, ts: Long, dt: Float) {
        // Motor zoom: constant speed in magnification, starts and stops dead.
        if (zoomDir != 0) {
            zoom = (zoom + zoomDir * ZOOM_SPEED * dt).coerceIn(1f, maxZoom)
            currentZoom = zoom
        }

        // Recording timeline.
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
            showPlay = t < PLAY_SECONDS
            if (recHeavyStart && t > 0.02f && t < 0.17f) staticAmount = 1f
            if (t < START_BAND_SECONDS) {
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
                "displayRotation=$displayRotation texMatrix=${texMatrix.joinToString(",") { "%.2f".format(it) }}")
        }

        val q = quad!!
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, q)

        // Pass A: camera -> tape frame
        val a = progA!!
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboA[0])
        GLES20.glViewport(0, 0, TAPE_W, TAPE_H)
        a.use()
        GLES20.glUniform1f(a.u("uFlipY"), 1f)
        GLES20.glUniformMatrix4fv(a.u("uTexMatrix"), 1, false, texMatrix, 0)
        GLES20.glUniformMatrix3fv(a.u("uCamXform"), 1, false, camXform, 0)
        GLES20.glUniform2f(a.u("uInvSize"), 1f / TAPE_W, 1f / TAPE_H)
        GLES20.glUniform4f(a.u("uRand"), random.nextFloat(), random.nextFloat(), random.nextFloat(), random.nextFloat())
        GLES20.glUniform1f(a.u("uSoft"), ((zoom - 1f) * 0.3f).coerceIn(0f, 1.6f))
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTex)
        GLES20.glUniform1i(a.u("uCam"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, osdTex)
        GLES20.glUniform1i(a.u("uOsd"), 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, noiseTex)
        GLES20.glUniform1i(a.u("uNoise"), 2)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Pass B: horizontal tape filter
        val cur = (frameIndex % 2).toInt()
        val prev = if (frameIndex == 0L) cur else 1 - cur
        val b = progB!!
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboB[cur])
        b.use()
        GLES20.glUniform1f(b.u("uFlipY"), 1f)
        GLES20.glUniform2f(b.u("uInvSize"), 1f / TAPE_W, 1f / TAPE_H)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texA[0])
        GLES20.glUniform1i(b.u("uTex"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Pass C: playback artefacts at output size
        val c = progC!!
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboC[0])
        GLES20.glViewport(0, 0, OUT_W, OUT_H)
        c.use()
        GLES20.glUniform1f(c.u("uFlipY"), 1f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texB[cur])
        GLES20.glUniform1i(c.u("uCur"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texB[prev])
        GLES20.glUniform1i(c.u("uPrev"), 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, noiseTex)
        GLES20.glUniform1i(c.u("uNoise"), 2)
        GLES20.glUniform4f(c.u("uRand"), random.nextFloat(), random.nextFloat(), random.nextFloat(), random.nextFloat())
        GLES20.glUniform4f(c.u("uRand2"), random.nextFloat(), random.nextFloat(), random.nextFloat(), random.nextFloat())
        GLES20.glUniform1f(c.u("uStatic"), staticAmount)
        GLES20.glUniform4f(c.u("uBand"), bandTop, bandHeight, bandShift, bandSmear)
        GLES20.glUniform1f(c.u("uWeave"), WEAVE)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

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

    private fun updateOsd(showPlay: Boolean) {
        val now = System.currentTimeMillis()
        val key = "$showPlay|$stampEnabled|${now / 60000L}"
        if (key == osdKey) return
        osdKey = key
        val o = osd ?: return
        o.draw(showPlay, stampEnabled, now)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, osdTex)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, o.bitmap, 0)
    }

    /**
     * Builds the matrix that takes picture coordinates (x right, y down, 0..1) to the
     * coordinates SurfaceTexture expects: un-mirror the front camera, centre-crop to 4:3,
     * apply the digital zoom and undo the display rotation.
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
        kx /= zoom
        ky /= zoom

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
        private const val ZOOM_SPEED = 0.78f      // magnification per second, measured on the reference tapes
        private const val MAX_ZOOM = 6f
        private const val WEAVE = 0.25f
        private const val PLAY_SECONDS = 3.0f
        private const val START_BAND_SECONDS = 0.55f
        private const val END_FRAMES = 8
        private const val REC_IDLE = 0
        private const val REC_RUNNING = 1
        private const val REC_ENDING = 2
    }
}
