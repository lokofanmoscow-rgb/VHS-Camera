package com.vhscamera.app

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records one clip: H.264 960x720 from the renderer's frames plus AAC mono 48 kHz from the
 * microphone (run through [VhsAudio]), muxed to MP4 straight into the gallery.
 */
class TapeRecorder(context: Context, private val withMicrophone: Boolean, private val listener: Listener) :
    VhsRenderer.RecordingSink {

    interface Listener {
        /** Main thread. [uri] is the gallery entry, or null with an [error] message. */
        fun onRecordingSaved(uri: Uri?, displayName: String?, error: String?)
    }

    private val appContext = context.applicationContext
    private val thread = HandlerThread("vhs-enc").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var muxerStarted = false
    private var videoTrack = -1
    private var audioTrack = -1
    private var lastVideoPts = -1L
    private var lastAudioPts = -1L
    private val pending = ArrayList<Pending>()
    private var wroteVideo = false
    private var audioAbandoned = false

    private var audioThread: Thread? = null
    @Volatile private var audioRunning = false
    @Volatile private var videoStartNano = 0L
    private var videoDone = false
    private var audioDone = false
    private var finished = false

    private var outUri: Uri? = null
    private var outPfd: ParcelFileDescriptor? = null
    private var outFile: File? = null
    private var displayName = ""

    private class Pending(val video: Boolean, val data: ByteBuffer, val info: MediaCodec.BufferInfo)

    /** Prepares the encoders and the output file. Returns the surface the renderer must draw frames into. */
    fun start(): Surface {
        try {
            return prepare()
        } catch (t: Throwable) {
            discard()
            throw t
        }
    }

    /** Releases everything and removes the unfinished gallery entry after a failed [start]. */
    private fun discard() {
        audioRunning = false
        try { videoCodec?.release() } catch (_: Throwable) {}
        try { audioCodec?.release() } catch (_: Throwable) {}
        try { inputSurface?.release() } catch (_: Throwable) {}
        try { muxer?.release() } catch (_: Throwable) {}
        try { outPfd?.close() } catch (_: Throwable) {}
        try { outUri?.let { appContext.contentResolver.delete(it, null, null) } } catch (_: Throwable) {}
        try { outFile?.delete() } catch (_: Throwable) {}
        thread.quitSafely()
    }

    private fun prepare(): Surface {
        openOutput()

        val videoCallback = object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    val buf = codec.getOutputBuffer(index)
                    if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        writeSample(true, buf, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        Log.i(TAG, "video encoder drained")
                        videoDone = true
                        maybeFinish()
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "video output", t)
                }
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                addTrack(true, format)
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "video encoder error", e)
                videoDone = true
                maybeFinish()
            }
        }
        val vc = createVideoEncoder(videoCallback)
        val surface = vc.createInputSurface()
        vc.start()
        videoCodec = vc
        inputSurface = surface

        try {
            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, VhsAudio.SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val ac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            ac.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            ac.start()
            audioCodec = ac
            audioRunning = true
            audioThread = Thread({ audioLoop(ac) }, "vhs-audio").apply { start() }
        } catch (t: Throwable) {
            Log.e(TAG, "audio encoder unavailable, recording without sound", t)
            audioCodec = null
            audioDone = true
        }
        return surface
    }

    /** H.264 High profile like the reference clips; falls back to the encoder's default profile. */
    private fun createVideoEncoder(callback: MediaCodec.Callback): MediaCodec {
        var failure: Throwable? = null
        for (high in booleanArrayOf(true, false)) {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VhsRenderer.OUT_W, VhsRenderer.OUT_H).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (high) {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                }
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                codec.setCallback(callback, handler)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return codec
            } catch (t: Throwable) {
                Log.w(TAG, "video encoder refused ${if (high) "High profile" else "default profile"}", t)
                failure = t
                try { codec.release() } catch (_: Throwable) {}
            }
        }
        throw failure ?: IllegalStateException("no video encoder")
    }

    override fun onFirstVideoFrame(nanoTime: Long) {
        videoStartNano = nanoTime
    }

    override fun onVideoFinished() {
        Log.i(TAG, "video finished, closing the tape")
        audioRunning = false
        handler.post {
            try {
                videoCodec?.signalEndOfInputStream()
            } catch (t: Throwable) {
                Log.e(TAG, "signalEndOfInputStream", t)
                videoDone = true
            }
            maybeFinish()
        }
        // Never leave a recording hanging if an encoder fails to report end of stream.
        handler.postDelayed({
            videoDone = true
            audioDone = true
            maybeFinish()
        }, FINISH_TIMEOUT_MS)
    }

    // ------------------------------------------------------------------ output

    private fun openOutput() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmssSS", Locale.US).format(Date()).substring(0, 17)
        displayName = "VHS_$stamp.mp4"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/" + ALBUM)
                put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = appContext.contentResolver
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = checkNotNull(resolver.insert(collection, values)) { "MediaStore insert failed" }
            outUri = uri
            val pfd = checkNotNull(resolver.openFileDescriptor(uri, "rw")) { "cannot open gallery file" }
            outPfd = pfd
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), ALBUM)
            dir.mkdirs()
            val file = File(dir, displayName)
            outFile = file
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }
    }

    private fun addTrack(video: Boolean, format: MediaFormat) {
        synchronized(lock) {
            val m = muxer ?: return
            if (muxerStarted) return
            if (video) videoTrack = m.addTrack(format) else audioTrack = m.addTrack(format)
            if (videoTrack >= 0 && (audioTrack >= 0 || audioCodec == null)) startMuxerLocked()
        }
    }

    private fun startMuxerLocked() {
        val m = muxer ?: return
        m.start()
        muxerStarted = true
        for (p in pending) writeLocked(p.video, p.data, p.info)
        pending.clear()
    }

    private fun writeSample(video: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (finished) return
            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            if (!muxerStarted && video && videoTrack >= 0 && audioTrack < 0 &&
                info.presentationTimeUs > AUDIO_WAIT_US) {
                // The sound track never showed up (microphone stuck?): keep the picture at least.
                Log.w(TAG, "no audio track after ${AUDIO_WAIT_US / 1000} ms, recording picture only")
                audioAbandoned = true
                startMuxerLocked()
            }
            if (!muxerStarted) {
                val copy = ByteBuffer.allocate(info.size)
                copy.put(buf)
                copy.flip()
                val ci = MediaCodec.BufferInfo()
                ci.set(0, info.size, info.presentationTimeUs, info.flags)
                pending.add(Pending(video, copy, ci))
            } else {
                writeLocked(video, buf, info)
            }
        }
    }

    private fun writeLocked(video: Boolean, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        val m = muxer ?: return
        val track = if (video) videoTrack else audioTrack
        if (track < 0 || (!video && audioAbandoned)) return
        // The muxer wants strictly increasing timestamps per track.
        if (video) {
            if (info.presentationTimeUs <= lastVideoPts) info.presentationTimeUs = lastVideoPts + 1
            lastVideoPts = info.presentationTimeUs
            wroteVideo = true
        } else {
            if (info.presentationTimeUs <= lastAudioPts) info.presentationTimeUs = lastAudioPts + 1
            lastAudioPts = info.presentationTimeUs
        }
        try {
            m.writeSampleData(track, buf, info)
        } catch (t: Throwable) {
            Log.e(TAG, "writeSampleData", t)
        }
    }

    /** Encoder thread. Closes the file once both tracks have ended and publishes it to the gallery. */
    private fun maybeFinish() {
        if (finished || !videoDone || !audioDone) return
        var ok: Boolean
        synchronized(lock) {
            finished = true
            ok = muxerStarted && wroteVideo
            try {
                if (muxerStarted) muxer?.stop()
            } catch (t: Throwable) {
                Log.e(TAG, "muxer stop", t)
                ok = false
            }
            try { muxer?.release() } catch (_: Throwable) {}
            muxer = null
        }
        try { videoCodec?.stop() } catch (_: Throwable) {}
        try { videoCodec?.release() } catch (_: Throwable) {}
        try { inputSurface?.release() } catch (_: Throwable) {}
        try { audioCodec?.stop() } catch (_: Throwable) {}
        try { audioCodec?.release() } catch (_: Throwable) {}
        videoCodec = null
        audioCodec = null
        inputSurface = null

        var uri: Uri? = null
        var error: String? = null
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                try { outPfd?.close() } catch (_: Throwable) {}
                val u = outUri
                if (u != null) {
                    if (ok) {
                        val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                        appContext.contentResolver.update(u, values, null, null)
                        uri = u
                    } else {
                        appContext.contentResolver.delete(u, null, null)
                        error = "empty recording"
                    }
                }
            } else {
                val f = outFile
                if (f != null) {
                    if (ok) {
                        // The scanner hands back the gallery (content) uri once the file is indexed.
                        MediaScannerConnection.scanFile(appContext, arrayOf(f.absolutePath), arrayOf("video/mp4")) { _, scanned ->
                            Log.i(TAG, "recording finished: uri=$scanned name=$displayName")
                            main.post { listener.onRecordingSaved(scanned ?: Uri.fromFile(f), displayName, null) }
                        }
                        handler.removeCallbacksAndMessages(null)
                        thread.quitSafely()
                        return
                    } else {
                        f.delete()
                        error = "empty recording"
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "publish failed", t)
            error = t.message ?: "save failed"
        }
        Log.i(TAG, "recording finished: uri=$uri name=$displayName error=$error")
        val resultUri = uri
        val resultError = error
        main.post { listener.onRecordingSaved(resultUri, displayName, resultError) }
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    // ------------------------------------------------------------------- audio

    @SuppressLint("MissingPermission")
    private fun audioLoop(codec: MediaCodec) {
        var record: AudioRecord? = null
        if (withMicrophone) {
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    VhsAudio.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val r = AudioRecord(
                    MediaRecorder.AudioSource.CAMCORDER, VhsAudio.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, 16384))
                if (r.state == AudioRecord.STATE_INITIALIZED) {
                    r.startRecording()
                    record = r
                } else {
                    r.release()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "microphone unavailable", t)
            }
        }

        val dsp = VhsAudio()
        val block = ShortArray(BLOCK)
        val info = MediaCodec.BufferInfo()
        var samples = 0L
        var basePtsUs = -1L
        var silentStart = 0L
        var silentSamples = 0L
        try {
            while (audioRunning) {
                var n: Int
                val r = record
                if (r != null) {
                    n = r.read(block, 0, BLOCK)
                    if (n < 0) {
                        Log.w(TAG, "AudioRecord.read = $n, continuing with tape noise only")
                        try { r.stop() } catch (_: Throwable) {}
                        r.release()
                        record = null
                        continue
                    }
                } else {
                    // No microphone: pace silence in real time so the tape noise still gets recorded.
                    val now = System.nanoTime()
                    if (silentStart == 0L) silentStart = now
                    val due = (now - silentStart) * VhsAudio.SAMPLE_RATE / 1_000_000_000L
                    if (due - silentSamples < BLOCK) {
                        Thread.sleep(5)
                        continue
                    }
                    java.util.Arrays.fill(block, 0.toShort())
                    n = BLOCK
                    silentSamples += BLOCK
                }
                if (n == 0) continue
                val start = videoStartNano
                if (start == 0L) continue          // the picture has not started yet
                if (basePtsUs < 0) {
                    val blockNanos = n * 1_000_000_000L / VhsAudio.SAMPLE_RATE
                    basePtsUs = maxOf(0L, (System.nanoTime() - blockNanos - start) / 1000L)
                }
                dsp.process(block, n)
                val pts = basePtsUs + samples * 1_000_000L / VhsAudio.SAMPLE_RATE
                val index = codec.dequeueInputBuffer(20_000)
                if (index >= 0) {
                    val buf = codec.getInputBuffer(index)
                    if (buf != null) {
                        buf.clear()
                        buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(block, 0, n)
                        codec.queueInputBuffer(index, 0, n * 2, pts, 0)
                    }
                }
                samples += n
                drainAudio(codec, info, false)
            }
            // end of stream
            var sent = false
            for (attempt in 0 until 50) {
                val index = codec.dequeueInputBuffer(20_000)
                if (index >= 0) {
                    val pts = maxOf(0L, basePtsUs) + samples * 1_000_000L / VhsAudio.SAMPLE_RATE
                    codec.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    sent = true
                    break
                }
                drainAudio(codec, info, false)
            }
            if (sent) drainAudio(codec, info, true)
        } catch (t: Throwable) {
            Log.e(TAG, "audio loop", t)
        } finally {
            try { record?.stop() } catch (_: Throwable) {}
            try { record?.release() } catch (_: Throwable) {}
            Log.i(TAG, "audio encoder drained, $samples samples, microphone=${withMicrophone}")
            handler.post {
                audioDone = true
                maybeFinish()
            }
        }
    }

    private fun drainAudio(codec: MediaCodec, info: MediaCodec.BufferInfo, toEnd: Boolean) {
        var idle = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (toEnd) 20_000L else 0L)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                addTrack(false, codec.outputFormat)
            } else if (index >= 0) {
                val buf = codec.getOutputBuffer(index)
                if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    writeSample(false, buf, info)
                }
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
            } else {
                if (!toEnd) return
                if (++idle > 100) return
            }
        }
    }

    companion object {
        private const val TAG = "TapeRecorder"
        const val ALBUM = "VHS Camera"
        private const val VIDEO_BITRATE = 6_000_000
        private const val AUDIO_BITRATE = 128_000
        private const val BLOCK = 1024
        private const val AUDIO_WAIT_US = 1_500_000L
        private const val FINISH_TIMEOUT_MS = 15_000L
    }
}
