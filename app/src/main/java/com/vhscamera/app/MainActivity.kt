package com.vhscamera.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
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
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * The camcorder. Viewfinder in the middle; on the body to the left MENU, DATE and the
 * zoom rocker, to the right LIGHT, CAMERA, the red REC key with its lamp, and PLAY.
 */
class MainActivity : Activity(), SurfaceHolder.Callback, VhsRenderer.Listener,
    CameraController.Listener, TapeRecorder.Listener {

    private enum class State { IDLE, RECORDING, SAVING }

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var painter: PixelPainter
    private lateinit var preview: SurfaceView
    private lateinit var viewfinder: Viewfinder
    private lateinit var menu: MenuPanel
    private lateinit var leftPanel: LinearLayout
    private lateinit var rightPanel: LinearLayout
    private lateinit var message: TextView
    private lateinit var menuKey: KeyButton
    private lateinit var dateKey: KeyButton
    private lateinit var lightKey: KeyButton
    private lateinit var cameraKey: KeyButton
    private lateinit var playKey: KeyButton
    private lateinit var recKey: RecKey
    private lateinit var recLamp: Lamp

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
    private var look = Look.CLASSIC
    private var hasFlash = false
    private var hasTwoCameras = false
    private var state = State.IDLE
    private var recStartMs = 0L
    private var lastRecordedMs = 0L
    private var lastUri: Uri? = null

    private var lastBarsCheck = 0L

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
            val now = SystemClock.elapsedRealtime()
            viewfinder.mode = state.ordinal
            viewfinder.recordedMillis = if (state == State.RECORDING) now - recStartMs else lastRecordedMs
            viewfinder.zoom = renderer?.currentZoom ?: 1f
            viewfinder.maxZoom = renderer?.maxZoom ?: 6f
            viewfinder.invalidate()
            recLamp.lit = state == State.RECORDING && now / 500 % 2 == 0L
            // Some systems bring the status bar back (for instance when the camera indicator appears).
            if (now - lastBarsCheck > 1500) {
                lastBarsCheck = now
                if (Build.VERSION.SDK_INT >= 30 &&
                    window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.statusBars()) == true) {
                    hideSystemUi()
                }
            }
            ui.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        autotestSeconds = intent?.getIntExtra("autotest", 0) ?: 0
        front = intent?.getBooleanExtra("front", false) ?: false
        look = Look.fromName(getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_LOOK, null))
        // The tape hiss takes a moment to synthesise; do it before the first recording needs it.
        Thread({ VhsAudio.warmUp() }, "vhs-hiss").start()
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
        r.setLook(look)
        val cam = CameraController(applicationContext, this)
        camera = cam
        hasTwoCameras = cam.hasFrontAndBack()
        r.start()
        if (surfaceReady) r.setPreviewSurface(preview.holder.surface, surfaceW, surfaceH)
        updateControls()
    }

    private fun stopPipeline() {
        val r = renderer ?: return
        if (state == State.RECORDING) {
            lastRecordedMs = SystemClock.elapsedRealtime() - recStartMs
            state = State.SAVING
        }
        camera?.closeBlocking()
        camera?.release()
        camera = null
        r.release()            // finishes a running recording first
        renderer = null
        cameraTexture = null
        torch = false
        showMenu(false)
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

    override fun onCameraConfigured(width: Int, height: Int, sensorOrientation: Int, front: Boolean, maxZoom: Float) {
        renderer?.setCameraGeometry(width, height, sensorOrientation, front)
        renderer?.setCameraMaxZoom(maxZoom)
        ui.post {
            hasFlash = camera?.hasFlash == true
            updateControls()
            if (autotestSeconds > 0 && !autotestStarted) {
                autotestStarted = true
                runAutotest()
            }
        }
    }

    override fun onCameraGain(stops: Float) {
        renderer?.setCameraGain(stops)
    }

    override fun onCameraZoom(ratio: Float) {
        camera?.setZoom(ratio)
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
        showMenu(false)
        try {
            val zoomMotor = { r.zooming }
            val rec = TapeRecorder(this, hasPermission(Manifest.permission.RECORD_AUDIO), look, zoomMotor, this)
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
        lastRecordedMs = SystemClock.elapsedRealtime() - recStartMs
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
            ui.postDelayed({ zoomTo(1) }, 1500)
            ui.postDelayed({ zoomTo(0) }, 2700)
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (menu.visibility == View.VISIBLE) {
            showMenu(false)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ---------------------------------------------------------------- controls

    private fun zoomTo(dir: Int) {
        renderer?.setZoomDirection(dir)
        // Keep the zoom bar in the viewfinder while zooming and for a moment afterwards.
        viewfinder.zoomBarUntil = if (dir != 0) Long.MAX_VALUE else SystemClock.elapsedRealtime() + 1500
    }

    private fun toggleLight() {
        if (!hasFlash) return
        torch = !torch
        camera?.setTorch(torch)
        updateControls()
    }

    private fun toggleDate() {
        stamp = !stamp
        renderer?.setStampEnabled(stamp)
        updateControls()
    }

    /** Switches between the looks; only while not recording, so a clip has one look throughout. */
    private fun switchLook() {
        if (state != State.IDLE) return
        look = look.next()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_LOOK, look.name).apply()
        renderer?.setLook(look)
        Log.i(TAG, "look: $look")
        updateControls()
    }

    private fun flipCamera() {
        if (state != State.IDLE || !hasTwoCameras) return
        val st = cameraTexture ?: return
        front = !front
        torch = false
        renderer?.resetZoom()
        camera?.open(st, front)
        updateControls()
    }

    private fun showMenu(show: Boolean) {
        menu.visibility = if (show) View.VISIBLE else View.GONE
        menu.invalidate()
    }

    private fun openLastClip() {
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

    private fun updateControls() {
        recKey.mode = state.ordinal
        dateKey.lamp = stamp
        lightKey.lamp = torch
        lightKey.isEnabled = hasFlash
        cameraKey.isEnabled = hasTwoCameras && state == State.IDLE
        playKey.isEnabled = state == State.IDLE
        lightKey.invalidate()
        cameraKey.invalidate()
        playKey.invalidate()
        viewfinder.mode = state.ordinal
        viewfinder.invalidate()
        menu.invalidate()
    }

    // ----------------------------------------------------------------------- UI

    private fun dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun buildUi() {
        painter = PixelPainter(PixelFont(assets.open("osd_font.txt").bufferedReader().use { it.readText() }))

        preview = SurfaceView(this)
        preview.holder.addCallback(this)
        viewfinder = Viewfinder(this, painter)

        menu = MenuPanel(this, painter)
        menu.items = listOf(
            MenuPanel.Item("MODE", { look.menuLabel }, { state == State.IDLE }) { switchLook() },
            MenuPanel.Item("DATE/TIME", { if (stamp) "ON" else "OFF" }, { true }) { toggleDate() },
            MenuPanel.Item("LIGHT", { if (torch) "ON" else "OFF" }, { hasFlash }) { toggleLight() },
            MenuPanel.Item("CAMERA", { if (front) "FRONT" else "BACK" }, { hasTwoCameras && state == State.IDLE }) { flipCamera() },
            MenuPanel.Item("EXIT", { "" }, { true }) { showMenu(false) },
        )
        menu.visibility = View.GONE

        menuKey = KeyButton(this, painter, "MENU").apply { setOnClickListener { showMenu(menu.visibility != View.VISIBLE) } }
        dateKey = KeyButton(this, painter, "DATE").apply { setOnClickListener { toggleDate() } }
        lightKey = KeyButton(this, painter, "LIGHT").apply { setOnClickListener { toggleLight() } }
        cameraKey = KeyButton(this, painter, "CAMERA").apply { setOnClickListener { flipCamera() } }
        playKey = KeyButton(this, painter, "PLAY>").apply { setOnClickListener { openLastClip() } }
        recKey = RecKey(this).apply { setOnClickListener { toggleRecording() } }
        recLamp = Lamp(this)
        val rocker = ZoomRocker(this, painter).apply { onZoom = { zoomTo(it) } }

        // Left side of the body: MENU, DATE and the zoom rocker.
        leftPanel = bodyPanel()
        leftPanel.addView(menuKey, sized(72f, 34f, 0f, 7f))
        leftPanel.addView(dateKey, sized(72f, 34f, 0f, 18f))
        leftPanel.addView(BodyLabel(this, painter, "ZOOM"), sized(72f, 9f, 0f, 6f))
        leftPanel.addView(rocker, sized(56f, 132f, 0f, 0f))

        // Right side: LIGHT, CAMERA, the record key with its lamp, PLAY.
        rightPanel = bodyPanel()
        rightPanel.addView(lightKey, sized(72f, 34f, 0f, 7f))
        rightPanel.addView(cameraKey, sized(72f, 34f, 0f, 16f))
        val recRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        recRow.addView(recLamp, LinearLayout.LayoutParams(dp(9f), dp(9f)).apply { rightMargin = dp(5f) })
        recRow.addView(BodyLabel(this, painter, "REC"), LinearLayout.LayoutParams(dp(26f), dp(9f)))
        rightPanel.addView(recRow, sized(72f, 9f, 0f, 6f))
        rightPanel.addView(recKey, sized(76f, 76f, 0f, 16f))
        rightPanel.addView(playKey, sized(72f, 36f, 0f, 0f))

        message = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFE8E6DE.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            setOnClickListener { onMessageClicked() }
        }

        val root = CamLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(preview)
        root.addView(viewfinder)
        root.addView(menu)
        root.addView(leftPanel)
        root.addView(rightPanel)
        root.addView(message)
        setContentView(root)
        updateControls()
    }

    /** A side of the camcorder body: dark moulded plastic. */
    private fun bodyPanel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF2A2B2F.toInt(), 0xFF17181B.toInt(), 0xFF0E0F11.toInt()))
    }

    private fun sized(wDp: Float, hDp: Float, topDp: Float, bottomDp: Float) =
        LinearLayout.LayoutParams(dp(wDp), dp(hDp)).apply {
            topMargin = dp(topDp)
            bottomMargin = dp(bottomDp)
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

    /** Viewfinder 4:3 in the middle, a side of the body on each side, the menu over the picture. */
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
            viewfinder.measure(exactly(pw), exactly(ph))
            menu.measure(exactly(pw * 62 / 100), exactly(ph * 64 / 100))
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
            viewfinder.layout(px, py, px + pw, py + ph)
            val mw = menu.measuredWidth
            val mh = menu.measuredHeight
            menu.layout(px + (pw - mw) / 2, py + (ph - mh) / 2, px + (pw + mw) / 2, py + (ph + mh) / 2)
            leftPanel.layout(0, 0, px, h)
            rightPanel.layout(px + pw, 0, w, h)
            message.layout(0, 0, w, h)
        }
    }

    companion object {
        private const val TAG = "VHSCAM"
        private const val PREFS = "vhs"
        private const val PREF_LOOK = "look"
    }
}
