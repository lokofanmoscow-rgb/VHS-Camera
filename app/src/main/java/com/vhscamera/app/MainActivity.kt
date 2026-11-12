package com.vhscamera.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** The camcorder: viewfinder in the middle, zoom rocker on the left, record button on the right. */
class MainActivity : Activity(), SurfaceHolder.Callback, VhsRenderer.Listener,
    CameraController.Listener, TapeRecorder.Listener {

    private enum class State { IDLE, RECORDING, SAVING }

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var preview: SurfaceView
    private lateinit var hud: FrameLayout
    private lateinit var leftPanel: LinearLayout
    private lateinit var rightPanel: LinearLayout
    private lateinit var message: TextView
    private lateinit var recLabel: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var recButton: RecButton
    private lateinit var lightButton: TextView
    private lateinit var flipButton: TextView
    private lateinit var dateButton: TextView
    private lateinit var galleryButton: TextView

    private var renderer: VhsRenderer? = null
    private var camera: CameraController? = null
    private var cameraTexture: SurfaceTexture? = null

    private var surfaceReady = false
    private var surfaceW = 0
    private var surfaceH = 0
    private var resumed = false
    private var askedPermissions = false

    private var front = false
    private var torch = false
    private var stamp = true
    private var state = State.IDLE
    private var recStartMs = 0L
    private var lastUri: Uri? = null

    private var autotestSeconds = 0
    private var autotestStarted = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            renderer?.setDisplayRotation(currentRotation())
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateRecLabel()
            zoomLabel.text = formatZoom(renderer?.currentZoom ?: 1f)
            ui.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        autotestSeconds = intent?.getIntExtra("autotest", 0) ?: 0
        front = intent?.getBooleanExtra("front", false) ?: false
        buildUi()
        hideSystemUi()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        resumed = true
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, ui)
        ui.post(ticker)
        if (hasPermission(Manifest.permission.CAMERA)) {
            showMessage(null)
            startPipeline()
        } else if (!askedPermissions) {
            askedPermissions = true
            requestNeededPermissions()
        } else {
            showMessage("Нужен доступ к камере.\nНажмите, чтобы разрешить.")
        }
    }

    override fun onPause() {
        resumed = false
        ui.removeCallbacks(ticker)
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
        stopPipeline()
        super.onPause()
    }

    private fun hasPermission(name: String) = checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED

    private fun requestNeededPermissions() {
        val wanted = ArrayList<String>()
        wanted += Manifest.permission.CAMERA
        wanted += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT < 29) wanted += Manifest.permission.WRITE_EXTERNAL_STORAGE
        val missing = wanted.filter { !hasPermission(it) }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasPermission(Manifest.permission.CAMERA)) {
            showMessage(null)
            if (resumed) startPipeline()
        } else {
            showMessage("Нужен доступ к камере.\nНажмите, чтобы разрешить.")
        }
    }

    private fun startPipeline() {
        if (renderer != null) return
        val r = VhsRenderer(applicationContext, this)
        renderer = r
        r.setDisplayRotation(currentRotation())
        r.setStampEnabled(stamp)
        camera = CameraController(applicationContext, this)
        r.start()
        if (surfaceReady) r.setPreviewSurface(preview.holder.surface, surfaceW, surfaceH)
        flipButton.visibility = if (camera?.hasFrontAndBack() == true) View.VISIBLE else View.GONE
    }

    private fun stopPipeline() {
        val r = renderer ?: return
        if (state == State.RECORDING) {
            state = State.SAVING
            updateControls()
        }
        camera?.closeBlocking()
        camera?.release()
        camera = null
        r.release()            // finishes a running recording first
        renderer = null
        cameraTexture = null
        torch = false
        updateControls()
    }

    private fun currentRotation(): Int {
        return if (Build.VERSION.SDK_INT >= 30) {
            display?.rotation ?: Surface.ROTATION_90
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
    }

    // ------------------------------------------------------- renderer / camera

    override fun onCameraTextureReady(surfaceTexture: SurfaceTexture) {
        ui.post {
            if (renderer == null) return@post
            cameraTexture = surfaceTexture
            if (resumed) camera?.open(surfaceTexture, front)
        }
    }

    override fun onRendererError(error: Throwable) {
        ui.post { showMessage("Ошибка графики: ${error.message}") }
    }

    override fun onCameraConfigured(width: Int, height: Int, sensorOrientation: Int, front: Boolean) {
        renderer?.setCameraGeometry(width, height, sensorOrientation, front)
        ui.post {
            lightButton.visibility = if (camera?.hasFlash == true) View.VISIBLE else View.INVISIBLE
            if (autotestSeconds > 0 && !autotestStarted) {
                autotestStarted = true
                runAutotest()
            }
        }
    }

    override fun onCameraError(message: String) {
        Log.e(TAG, "camera: $message")
        ui.post { Toast.makeText(this, "Камера: $message", Toast.LENGTH_LONG).show() }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceReady = true
        surfaceW = width
        surfaceH = height
        renderer?.setPreviewSurface(holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        renderer?.setPreviewSurface(null, 0, 0)
    }

    // ---------------------------------------------------------------- recording

    private fun toggleRecording() {
        when (state) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopRecording()
            State.SAVING -> {}
        }
    }

    private fun startRecording() {
        val r = renderer ?: return
        if (state != State.IDLE || cameraTexture == null) return
        if (Build.VERSION.SDK_INT < 29 && !hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            requestNeededPermissions()
            return
        }
        try {
            val rec = TapeRecorder(this, hasPermission(Manifest.permission.RECORD_AUDIO), this)
            val surface = rec.start()
            r.startRecording(surface, rec)
            state = State.RECORDING
            recStartMs = SystemClock.elapsedRealtime()
            updateControls()
        } catch (t: Throwable) {
            Log.e(TAG, "start recording", t)
            Toast.makeText(this, "Не удалось начать запись: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecording() {
        if (state != State.RECORDING) return
        state = State.SAVING
        updateControls()
        renderer?.stopRecording()
    }

    override fun onRecordingSaved(uri: Uri?, displayName: String?, error: String?) {
        state = State.IDLE
        if (uri != null) {
            lastUri = uri
            Toast.makeText(this, "Сохранено в галерею: $displayName", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Запись не сохранена: ${error ?: "ошибка"}", Toast.LENGTH_LONG).show()
        }
        updateControls()
        if (autotestSeconds > 0) Log.i(TAG, "AUTOTEST_DONE uri=$uri name=$displayName error=$error")
    }

    private fun runAutotest() {
        Log.i(TAG, "AUTOTEST_BEGIN seconds=$autotestSeconds")
        ui.postDelayed({
            startRecording()
            ui.postDelayed({ renderer?.setZoomDirection(1) }, 1500)
            ui.postDelayed({ renderer?.setZoomDirection(0) }, 2700)
            ui.postDelayed({ stopRecording() }, autotestSeconds * 1000L)
        }, 3000)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_CAMERA) {
            if (event.repeatCount == 0) toggleRecording()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_CAMERA) return true
        return super.onKeyUp(keyCode, event)
    }

    // ----------------------------------------------------------------------- UI

    private fun dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun buildUi() {
        preview = SurfaceView(this)
        preview.holder.addCallback(this)
        // Dragging a finger over the picture upsets the tracking there, like nudging the tape.
        preview.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                    renderer?.setTouchGlitch((e.y / v.height.coerceAtLeast(1)).coerceIn(0f, 1f))
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> renderer?.setTouchGlitch(-1f)
            }
            true
        }

        hud = FrameLayout(this)
        recLabel = TextView(this).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(REC_RED)
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            visibility = View.INVISIBLE
        }
        hud.addView(recLabel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END
        ).apply { setMargins(0, dp(12f), dp(28f), 0) })

        // Left: the zoom rocker.
        leftPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val tele = pad("T", 22f)
        val wide = pad("W", 22f)
        zoomLabel = label("1.0x", 12f)
        holdToZoom(tele, 1)
        holdToZoom(wide, -1)
        leftPanel.addView(label("ZOOM", 10f), wrapParams(0, 0))
        leftPanel.addView(tele, sizeParams(64f, 72f, 8f))
        leftPanel.addView(zoomLabel, wrapParams(8, 8))
        leftPanel.addView(wide, sizeParams(64f, 72f, 0f))

        // Right: options, the record button, the gallery.
        rightPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        lightButton = pad("СВЕТ", 11f).apply {
            setOnClickListener {
                torch = !torch
                camera?.setTorch(torch)
                updateControls()
            }
        }
        flipButton = pad("КАМЕРА", 11f).apply {
            setOnClickListener {
                if (state != State.IDLE) return@setOnClickListener
                val st = cameraTexture ?: return@setOnClickListener
                front = !front
                torch = false
                renderer?.resetZoom()
                camera?.open(st, front)
                updateControls()
            }
        }
        dateButton = pad("ДАТА", 11f).apply {
            setOnClickListener {
                stamp = !stamp
                renderer?.setStampEnabled(stamp)
                updateControls()
            }
        }
        recButton = RecButton(this).apply { setOnClickListener { toggleRecording() } }
        galleryButton = pad("ГАЛЕРЕЯ", 11f).apply { setOnClickListener { openGallery() } }
        rightPanel.addView(lightButton, sizeParams(76f, 34f, 6f))
        rightPanel.addView(flipButton, sizeParams(76f, 34f, 6f))
        rightPanel.addView(dateButton, sizeParams(76f, 34f, 14f))
        rightPanel.addView(recButton, sizeParams(76f, 76f, 14f))
        rightPanel.addView(galleryButton, sizeParams(76f, 34f, 0f))

        message = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            setOnClickListener { onMessageClicked() }
        }

        val root = CamLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(preview)
        root.addView(hud)
        root.addView(leftPanel)
        root.addView(rightPanel)
        root.addView(message)
        setContentView(root)
        updateControls()
    }

    private fun label(text: String, sp: Float) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(DIM)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    private fun pad(text: String, sp: Float) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        maxLines = 1
        isClickable = true
        background = padBackground(false)
    }

    private fun padBackground(active: Boolean): StateListDrawable {
        fun shape(color: Int, stroke: Int) = GradientDrawable().apply {
            cornerRadius = dp(8f).toFloat()
            setColor(color)
            setStroke(dp(1f), stroke)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(0xFF5A5D66.toInt(), 0xFF8A8D96.toInt()))
            addState(intArrayOf(), if (active) shape(0xFF6B5320.toInt(), AMBER) else shape(0xFF24262C.toInt(), 0xFF3A3D45.toInt()))
        }
    }

    private fun sizeParams(wDp: Float, hDp: Float, bottomDp: Float) =
        LinearLayout.LayoutParams(dp(wDp), dp(hDp)).apply { bottomMargin = dp(bottomDp) }

    private fun wrapParams(topDp: Int, bottomDp: Int) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(topDp.toFloat())
            bottomMargin = dp(bottomDp.toFloat())
        }

    private fun holdToZoom(view: View, dir: Int) {
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    renderer?.setZoomDirection(dir)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    renderer?.setZoomDirection(0)
                }
            }
            true
        }
    }

    private fun formatZoom(z: Float): String {
        val tenths = Math.round(z * 10f)
        return "${tenths / 10}.${tenths % 10}x"
    }

    private fun updateControls() {
        recButton.mode = state.ordinal
        recButton.invalidate()
        lightButton.background = padBackground(torch)
        dateButton.background = padBackground(stamp)
        flipButton.alpha = if (state == State.IDLE) 1f else 0.35f
        galleryButton.alpha = if (state == State.IDLE) 1f else 0.35f
        updateRecLabel()
    }

    private fun updateRecLabel() {
        when (state) {
            State.RECORDING -> {
                val ms = SystemClock.elapsedRealtime() - recStartMs
                val s = ms / 1000
                val sec = s % 60
                val dot = if (ms / 500 % 2 == 0L) "●" else " "
                recLabel.text = "$dot REC ${s / 60}:${if (sec < 10) "0$sec" else "$sec"}"
                recLabel.visibility = View.VISIBLE
            }
            State.SAVING -> {
                recLabel.text = "СОХРАНЕНИЕ…"
                recLabel.visibility = View.VISIBLE
            }
            State.IDLE -> recLabel.visibility = View.INVISIBLE
        }
    }

    private fun showMessage(text: String?) {
        if (text == null) {
            message.visibility = View.GONE
        } else {
            message.text = text
            message.visibility = View.VISIBLE
        }
    }

    private fun onMessageClicked() {
        if (hasPermission(Manifest.permission.CAMERA)) {
            showMessage(null)
            startPipeline()
        } else if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            requestNeededPermissions()
        } else {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            } catch (t: Throwable) {
                requestNeededPermissions()
            }
        }
    }

    private fun openGallery() {
        if (state != State.IDLE) return
        try {
            val uri = lastUri
            val intent = if (uri != null && uri.scheme == "content") {
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                Intent(Intent.ACTION_VIEW, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            }
            startActivity(intent)
        } catch (t: Throwable) {
            Toast.makeText(this, "Видео лежат в галерее, альбом «${TapeRecorder.ALBUM}»", Toast.LENGTH_LONG).show()
        }
    }

    /** Viewfinder 4:3 in the middle, a control panel on each side. */
    private inner class CamLayout(context: Context) : ViewGroup(context) {
        private var pw = 0
        private var ph = 0

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(w, h)
            val minPanel = dp(92f)
            pw = minOf(w - 2 * minPanel, h * 4 / 3).coerceAtLeast(4)
            ph = pw * 3 / 4
            val panel = (w - pw) / 2
            fun exactly(v: Int) = MeasureSpec.makeMeasureSpec(v, MeasureSpec.EXACTLY)
            preview.measure(exactly(pw), exactly(ph))
            hud.measure(exactly(pw), exactly(ph))
            leftPanel.measure(exactly(panel), exactly(h))
            rightPanel.measure(exactly(panel), exactly(h))
            message.measure(exactly(w), exactly(h))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l
            val h = b - t
            val px = (w - pw) / 2
            val py = (h - ph) / 2
            preview.layout(px, py, px + pw, py + ph)
            hud.layout(px, py, px + pw, py + ph)
            leftPanel.layout(0, 0, px, h)
            rightPanel.layout(px + pw, 0, w, h)
            message.layout(0, 0, w, h)
        }
    }

    /** Round record button: red dot when idle, red square while recording, grey while saving. */
    private class RecButton(context: Context) : View(context) {
        var mode = 0
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = TEXT }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val rect = RectF()

        init {
            isClickable = true
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) / 2f
            ring.strokeWidth = r * 0.09f
            canvas.drawCircle(cx, cy, r * 0.92f, ring)
            fill.color = if (mode == 2) 0xFF6A6A6A.toInt() else if (isPressed) 0xFFB02A22.toInt() else REC_RED
            if (mode == 1) {
                val s = r * 0.40f
                rect.set(cx - s, cy - s, cx + s, cy + s)
                canvas.drawRoundRect(rect, s * 0.25f, s * 0.25f, fill)
            } else {
                canvas.drawCircle(cx, cy, r * 0.72f, fill)
            }
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            invalidate()
        }
    }

    companion object {
        private const val TAG = "VHSCAM"
        private val TEXT = 0xFFE8E6DE.toInt()
        private val DIM = 0xFF8C8A84.toInt()
        private val AMBER = 0xFFE0A030.toInt()
        private val REC_RED = 0xFFE5352B.toInt()
    }
}
