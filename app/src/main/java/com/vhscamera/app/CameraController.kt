package com.vhscamera.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlin.math.abs

/** Camera2: streams a 4:3 video feed into the renderer's SurfaceTexture. */
class CameraController(context: Context, private val listener: Listener) {

    interface Listener {
        /** Camera thread, before frames start: the geometry of the stream. */
        fun onCameraConfigured(width: Int, height: Int, sensorOrientation: Int, front: Boolean)
        fun onCameraError(message: String)
    }

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("vhs-cam").apply { start() }
    private val handler = Handler(thread.looper)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var builder: CaptureRequest.Builder? = null
    private var surface: Surface? = null
    private var torch = false
    private var generation = 0

    @Volatile var hasFlash = false
        private set

    fun hasFrontAndBack(): Boolean {
        return try {
            findCamera(true) != null && findCamera(false) != null
        } catch (t: Throwable) {
            false
        }
    }

    private fun findCamera(front: Boolean): String? {
        val wanted = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        for (id in manager.cameraIdList) {
            val facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            if (facing == wanted) return id
        }
        return null
    }

    private fun chooseSize(sizes: Array<Size>): Size {
        val fourThirds = sizes.filter { abs(it.width.toFloat() / it.height - 4f / 3f) < 0.02f && it.width in 640..1920 }
        fourThirds.maxByOrNull { it.width * it.height }?.let { return it }
        val any = sizes.filter { it.width <= 1920 }
        return any.maxByOrNull { it.width * it.height } ?: sizes[0]
    }

    /** Opens the camera facing the given way and starts streaming into [texture]. */
    @SuppressLint("MissingPermission")
    fun open(texture: SurfaceTexture, front: Boolean) {
        val gen = ++generation
        handler.post {
            closeNow()
            try {
                val id = findCamera(front) ?: findCamera(!front) ?: manager.cameraIdList.firstOrNull()
                if (id == null) {
                    listener.onCameraError("no camera")
                    return@post
                }
                val ch = manager.getCameraCharacteristics(id)
                val isFront = ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes = map?.getOutputSizes(SurfaceTexture::class.java)
                if (sizes == null || sizes.isEmpty()) {
                    listener.onCameraError("camera has no preview sizes")
                    return@post
                }
                val size = chooseSize(sizes)
                val orientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                hasFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val fpsRanges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                val afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)

                texture.setDefaultBufferSize(size.width, size.height)
                listener.onCameraConfigured(size.width, size.height, orientation, isFront)
                val target = Surface(texture)
                surface = target

                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (gen != generation) {
                            camera.close()
                            return
                        }
                        device = camera
                        try {
                            @Suppress("DEPRECATION")
                            camera.createCaptureSession(listOf(target), object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    if (gen != generation || device !== camera) return
                                    session = s
                                    try {
                                        val b = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                                        b.addTarget(target)
                                        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                        if (afModes != null && afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                                            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                        }
                                        pickFpsRange(fpsRanges)?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                                        b.set(CaptureRequest.FLASH_MODE,
                                            if (torch && hasFlash) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                                        builder = b
                                        s.setRepeatingRequest(b.build(), null, handler)
                                    } catch (t: Throwable) {
                                        Log.e(TAG, "start preview", t)
                                        listener.onCameraError(t.message ?: "camera start failed")
                                    }
                                }

                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    listener.onCameraError("camera session failed")
                                }
                            }, handler)
                        } catch (t: Throwable) {
                            Log.e(TAG, "createCaptureSession", t)
                            listener.onCameraError(t.message ?: "camera session failed")
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (device === camera) device = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (device === camera) device = null
                        listener.onCameraError("camera error $error")
                    }
                }, handler)
            } catch (t: Throwable) {
                Log.e(TAG, "open camera", t)
                listener.onCameraError(t.message ?: "camera unavailable")
            }
        }
    }

    /** Camcorders run at a fixed field rate: prefer a locked 30 fps. */
    private fun pickFpsRange(ranges: Array<Range<Int>>?): Range<Int>? {
        if (ranges == null || ranges.isEmpty()) return null
        val at30 = ranges.filter { it.upper == 30 }
        return at30.maxByOrNull { it.lower } ?: ranges.filter { it.upper >= 24 }.minByOrNull { it.upper } ?: ranges.maxByOrNull { it.upper }
    }

    fun setTorch(on: Boolean) {
        handler.post {
            torch = on
            val b = builder ?: return@post
            val s = session ?: return@post
            try {
                b.set(CaptureRequest.FLASH_MODE,
                    if (on && hasFlash) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                s.setRepeatingRequest(b.build(), null, handler)
            } catch (t: Throwable) {
                Log.w(TAG, "torch", t)
            }
        }
    }

    fun close() {
        generation++
        handler.post { closeNow() }
    }

    /** Closes the camera and waits for it, so the renderer can drop the SurfaceTexture safely. */
    fun closeBlocking() {
        generation++
        val latch = java.util.concurrent.CountDownLatch(1)
        val posted = handler.post {
            try {
                closeNow()
            } finally {
                latch.countDown()
            }
        }
        if (posted) latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun closeNow() {
        try { session?.close() } catch (_: Throwable) {}
        session = null
        builder = null
        try { device?.close() } catch (_: Throwable) {}
        device = null
        try { surface?.release() } catch (_: Throwable) {}
        surface = null
    }

    fun release() {
        close()
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "CameraController"
    }
}
