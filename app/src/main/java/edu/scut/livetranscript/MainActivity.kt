package edu.scut.livetranscript

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.setPadding
import com.google.android.material.card.MaterialCardView
import org.opencv.android.OpenCVLoader
import org.vosk.Model
import org.vosk.android.StorageService
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val processing = AtomicBoolean(false)
    private val lastFrameAt = AtomicLong(0L)
    private val tracker = FaceTracker()
    private val store by lazy { LocalStore(this) }
    private val pipeline by lazy { FacePipeline(this) }
    @Volatile private var running = false
    @Volatile private var currentFaces: List<TrackedFace> = emptyList()
    @Volatile private var selectedTrackId: Int? = null
    @Volatile private var frameWidth = 640
    @Volatile private var frameHeight = 480
    @Volatile private var lastSpeechSpeaker = "未确认说话人"
    private var speechModel: Model? = null
    private var speechEngine: SpeechEngine? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    @Volatile private var audioCaptureRunning = false
    private var modelLoading = false
    private var lastCommittedSpeech = ""
    private var lastCommittedAt = 0L
    private var pendingStart = false
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisUseCase: ImageAnalysis? = null
    private lateinit var preview: PreviewView
    private lateinit var overlay: FaceOverlayView
    private lateinit var consent: CheckBox
    private lateinit var startButton: Button
    private lateinit var status: TextView
    private lateinit var peopleStatus: TextView
    private lateinit var transcriptPreview: TextView
    private lateinit var speechStatus: TextView
    private lateinit var transcriptList: LinearLayout
    private lateinit var nameInput: EditText
    private lateinit var liveBadge: TextView

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.CAMERA] == true && result[Manifest.permission.RECORD_AUDIO] == true
        if (pendingStart && granted) beginSession() else if (pendingStart) {
            pendingStart = false
            status.text = "需要相机和麦克风权限才能开始。"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!OpenCVLoader.initLocal()) {
            setContentView(TextView(this).apply { text = "OpenCV 初始化失败"; setPadding(32) })
            return
        }
        buildScreen()
        refreshParticipants()
        refreshTranscriptList()
    }

    private fun buildScreen() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18))
            setBackgroundColor(0xFFF4F6F5.toInt())
        }
        val scroll = ScrollView(this).apply { addView(page) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        setContentView(scroll)
        ViewCompat.requestApplyInsets(scroll)

        page.addView(label("LIVE SESSION", 12f, 0xFF176B62.toInt(), true))
        page.addView(label("人脸与语音记录", 25f, 0xFF172321.toInt(), true).top(4))
        page.addView(label("识别框架优先离线运行 · 只保存转写文本，不保存音频", 13f, 0xFF586561.toInt()).top(3))

        liveBadge = label("● 已停止", 13f, 0xFF586561.toInt(), true).top(10)
        page.addView(liveBadge)
        preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            setBackgroundColor(0xFF202927.toInt())
            contentDescription = "实时摄像头预览"
        }
        overlay = FaceOverlayView(this).apply {
            onFaceSelected = { id ->
                selectedTrackId = id
                this.selectedId = id
                this.invalidate()
                status.text = "当前说话人：${currentFaces.firstOrNull { it.id == id }?.label ?: "未确认说话人"}"
            }
        }
        val cameraFrame = FrameLayout(this).apply {
            addView(preview, FrameLayout.LayoutParams(-1, -1))
            addView(this@MainActivity.overlay, FrameLayout.LayoutParams(-1, -1))
        }
        page.addView(card().apply { addView(cameraFrame, FrameLayout.LayoutParams(-1, -1)) }.top(12), LinearLayout.LayoutParams(-1, dp(270)))

        consent = CheckBox(this).apply {
            text = "已告知参与者并取得人脸与语音识别同意"
            textSize = 13f
            setTextColor(0xFF33413D.toInt())
        }
        page.addView(consent.top(4))
        startButton = Button(this).apply {
            text = "开始采集"
            isAllCaps = false
            setOnClickListener { onStartClicked() }
        }
        page.addView(startButton.top(2))
        status = label("点击开始后会同时启动摄像头与麦克风。", 12f, 0xFF586561.toInt()).top(3)
        page.addView(status)

        page.addView(label("参与者登记", 16f, 0xFF172321.toInt(), true).top(12))
        val enrollmentRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        nameInput = EditText(this).apply {
            hint = "姓名（本人同意后登记）"
            setSingleLine(true)
            textSize = 14f
        }
        enrollmentRow.addView(nameInput, LinearLayout.LayoutParams(0, dp(52), 1f))
        enrollmentRow.addView(Button(this).apply {
            text = "登记选中人脸"
            isAllCaps = false
            setOnClickListener { enrollSelectedFace() }
        })
        page.addView(enrollmentRow)
        peopleStatus = label("身份库 0 人 · 本机保存", 12f, 0xFF586561.toInt()).top(2)
        page.addView(peopleStatus)

        val speechCard = card()
        val speechContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16)) }
        speechContent.addView(label("实时转写", 16f, 0xFF172321.toInt(), true))
        transcriptPreview = label("等待语音…", 15f, 0xFF176B62.toInt()).top(8)
        speechContent.addView(transcriptPreview)
        speechStatus = label("识别服务尚未启动", 12f, 0xFF586561.toInt()).top(5)
        speechContent.addView(speechStatus)
        speechCard.addView(speechContent)
        page.addView(speechCard.top(12))

        val historyCard = card()
        val historyContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16)) }
        val historyHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        historyHeader.addView(label("已保存转写", 16f, 0xFF172321.toInt(), true), LinearLayout.LayoutParams(0, -2, 1f))
        historyHeader.addView(Button(this).apply {
            text = "清除"
            isAllCaps = false
            setOnClickListener { confirmClearHistory() }
        })
        historyContent.addView(historyHeader)
        transcriptList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        historyContent.addView(transcriptList.top(6))
        historyCard.addView(historyContent)
        page.addView(historyCard.top(10))
        page.addView(label("点选人脸可指定说话人；多人同时出现且未点选时，转写归入“未确认说话人”。", 12f, 0xFF586561.toInt()).top(10))
    }

    private fun onStartClicked() {
        if (running) {
            stopSession()
            return
        }
        if (!consent.isChecked) {
            status.text = "请先确认已告知并获得参与者同意。"
            return
        }
        val missing = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            pendingStart = true
            requestPermissions.launch(missing.toTypedArray())
        } else beginSession()
    }

    private fun beginSession() {
        pendingStart = false
        running = true
        startButton.text = "停止采集"
        consent.isEnabled = false
        liveBadge.text = "● CAMERA ON · MIC ON"
        liveBadge.setTextColor(0xFFB44D37.toInt())
        status.text = "正在启动摄像头与语音识别…"
        startCamera()
        startSpeechRecognition()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val previewUseCase = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(android.util.Size(640, 480))
                    .build()
                analysis.setAnalyzer(cameraExecutor, ::analyzeFrame)
                provider.unbindAll()
                val selector = if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                provider.bindToLifecycle(this, selector, previewUseCase, analysis)
                analysisUseCase = analysis
                status.text = "摄像头与麦克风运行中 · 未知访客编号只在本次会话有效"
            } catch (error: Exception) {
                status.text = "摄像头启动失败：${error.localizedMessage ?: "不可用"}"
                stopSession()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (!running || now - lastFrameAt.get() < FRAME_INTERVAL_MS || !processing.compareAndSet(false, true)) {
            image.close()
            return
        }
        lastFrameAt.set(now)
        val bitmap = try { rotate(imageToBitmap(image), image.imageInfo.rotationDegrees) }
        catch (_: Exception) { image.close(); processing.set(false); return }
        image.close()
        analysisExecutor.execute {
            try {
                val frame = downscale(bitmap, 640)
                val samples = pipeline.detect(frame)
                val people = store.participants()
                val tracked = tracker.update(samples, people, SystemClock.elapsedRealtime())
                frameWidth = frame.width
                frameHeight = frame.height
                currentFaces = tracked
                runOnUiThread {
                    overlay.submit(tracked, frame.width, frame.height)
                    if (tracked.isEmpty()) status.text = "实时扫描中 · 当前画面未检测到人脸"
                    else status.text = "实时扫描中 · 当前 ${tracked.size} 人 · 点击人脸可指定说话人"
                }
                if (frame !== bitmap) frame.recycle()
            } catch (error: Exception) {
                runOnUiThread { status.text = "画面分析失败：${error.localizedMessage ?: "请调整光线或距离"}" }
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
                processing.set(false)
            }
        }
    }

    private fun enrollSelectedFace() {
        val name = nameInput.text.toString().trim()
        if (name.isBlank()) { status.text = "请先填写姓名。"; return }
        val id = selectedTrackId
        val candidate = currentFaces.firstOrNull { it.id == id } ?: currentFaces.maxByOrNull { it.bounds.width() * it.bounds.height() }
        if (candidate == null) { status.text = "请将一张脸放入画面，并点选后再登记。"; return }
        store.saveParticipant(name, candidate.embedding)
        nameInput.setText("")
        refreshParticipants()
        status.text = "已在本机登记 $name。该参与者需已同意进行识别。"
    }

    private fun startSpeechRecognition() {
        if (audioCaptureRunning) {
            speechStatus.text = "本机离线中文识别运行中"
            return
        }
        if (modelLoading) {
            speechStatus.text = "正在解压离线中文模型…"
            return
        }
        modelLoading = true
        speechStatus.text = "正在加载离线中文模型…"
        StorageService.unpack(
            this,
            "models/vosk-model-small-cn-0.22",
            "vosk",
            { model ->
                modelLoading = false
                speechModel = model
                if (running) startVoskRecognition()
            },
            { error ->
                modelLoading = false
                runOnUiThread { speechStatus.text = "离线模型加载失败：${error.message ?: "未知错误"}" }
            }
        )
    }

    private fun startVoskRecognition() {
        if (!running || speechModel == null || audioCaptureRunning) return
        try {
            val model = speechModel ?: return
            val engine = VoskSpeechEngine(model)
            speechEngine = engine
            engine.start(::onSpeechEvent)
            val minBuffer = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            check(minBuffer > 0) { "Android 无法创建 16 kHz 麦克风流（$minBuffer）" }
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer * 2, AUDIO_BUFFER_BYTES)
            )
            check(recorder.state == AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                "麦克风 AudioRecord 初始化失败"
            }
            audioRecord = recorder
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Android 未进入麦克风录音状态" }
            audioCaptureRunning = true
            speechStatus.text = "麦克风已连接 · 正在检查输入电平…"
            audioThread = Thread({ captureAudio(recorder, engine) }, "live-transcript-audio").apply { start() }
        } catch (error: Exception) {
            audioCaptureRunning = false
            audioRecord?.release()
            audioRecord = null
            speechEngine?.stop()
            speechEngine = null
            runOnUiThread { speechStatus.text = "本机语音启动失败：${error.localizedMessage ?: "未知错误"}" }
        }
    }

    private fun captureAudio(recorder: AudioRecord, engine: SpeechEngine) {
        val samples = ShortArray(AUDIO_CHUNK_SAMPLES)
        var lastMeterUpdate = 0L
        try {
            while (audioCaptureRunning && running) {
                val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (count < 0) error("麦克风读取失败（$count）")
                if (count == 0) continue

                var energy = 0.0
                for (i in 0 until count) {
                    val sample = samples[i].toDouble()
                    energy += sample * sample
                }
                val rms = kotlin.math.sqrt(energy / count)
                val levelDb = if (rms > 0.0) 20.0 * kotlin.math.log10(rms / 32768.0) else -96.0
                val now = SystemClock.elapsedRealtime()
                if (now - lastMeterUpdate >= AUDIO_METER_INTERVAL_MS) {
                    lastMeterUpdate = now
                    val level = levelDb.roundToInt()
                    runOnUiThread {
                        speechStatus.text = if (level < MIN_AUDIBLE_DBFS) {
                            "麦克风输入 ${level} dBFS · 未检测到明显声音"
                        } else {
                            "麦克风正在收音 ${level} dBFS · 本机离线识别"
                        }
                    }
                }

                engine.acceptPcm(samples, count, now)
            }
        } catch (error: Exception) {
            if (running) runOnUiThread {
                speechStatus.text = "麦克风采集失败：${error.localizedMessage ?: "请检查系统麦克风输入"}"
            }
        }
    }

    private fun onSpeechEvent(event: SpeechEvent) {
        if (!running) return
        if (event.isFinal) {
            commitVoskText(event.text)
            return
        }
        val speaker = tracker.speakerLabel(selectedTrackId, currentFaces)
        runOnUiThread { transcriptPreview.text = "$speaker：${event.text}" }
    }

    private fun commitVoskText(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (text == lastCommittedSpeech && now - lastCommittedAt < 1800L) return
        lastCommittedSpeech = text
        lastCommittedAt = now
        saveUtterance(text)
    }

    private fun saveUtterance(text: String) {
        val speaker = tracker.speakerLabel(selectedTrackId, currentFaces)
        lastSpeechSpeaker = speaker
        store.addUtterance(speaker, text)
        runOnUiThread {
            transcriptPreview.text = "$speaker：$text"
            speechStatus.text = "已保存到本机 · 识别下一句中"
            refreshTranscriptList()
        }
    }

    private fun refreshParticipants() {
        peopleStatus.text = "身份库 ${store.participants().size} 人 · 本机保存"
    }

    private fun refreshTranscriptList() {
        if (!::transcriptList.isInitialized) return
        transcriptList.removeAllViews()
        val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val rows = store.recentUtterances()
        if (rows.isEmpty()) {
            transcriptList.addView(label("尚无已保存转写", 13f, 0xFF586561.toInt()))
            return
        }
        rows.forEach { row ->
            val item = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(7), 0, dp(7)) }
            item.addView(label("${formatter.format(Date(row.timestamp))} · ${row.speaker}", 11f, 0xFF176B62.toInt(), true))
            item.addView(label(row.text, 14f, 0xFF172321.toInt()).top(2))
            transcriptList.addView(item)
        }
    }

    private fun confirmClearHistory() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("清除全部转写？")
            .setMessage("只会清除本机保存的转写文本，参与者身份库不会删除。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清除") { _, _ ->
                store.clearUtterances()
                refreshTranscriptList()
                transcriptPreview.text = "转写已清除"
            }
            .show()
    }

    private fun stopSession() {
        running = false
        audioCaptureRunning = false
        val recorder = audioRecord
        audioRecord = null
        try {
            if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
        } catch (_: IllegalStateException) {
        }
        recorder?.release()
        try {
            audioThread?.join(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        audioThread = null
        speechEngine?.stop()
        speechEngine = null
        analysisUseCase?.clearAnalyzer()
        analysisUseCase = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        currentFaces = emptyList()
        overlay.submit(emptyList(), frameWidth, frameHeight)
        overlay.selectedId = null
        selectedTrackId = null
        startButton.text = "开始采集"
        consent.isEnabled = true
        liveBadge.text = "● 已停止"
        liveBadge.setTextColor(0xFF586561.toInt())
        transcriptPreview.text = "采集已停止"
        speechStatus.text = "识别已停止 · 历史文本保留在本机"
        status.text = "摄像头与麦克风已关闭。转写文本保存在本机。"
    }

    private fun imageToBitmap(image: ImageProxy): Bitmap {
        require(image.format == ImageFormat.YUV_420_888) { "Unsupported camera image format" }
        val width = image.width
        val height = image.height
        val planes = image.planes
        val y = planes[0]
        val u = planes[1]
        val v = planes[2]
        val nv21 = ByteArray(width * height * 3 / 2)
        var out = 0
        val yBase = y.buffer.position()
        for (row in 0 until height) for (col in 0 until width) nv21[out++] = y.buffer.get(yBase + row * y.rowStride + col)
        val uBase = u.buffer.position()
        val vBase = v.buffer.position()
        for (row in 0 until height / 2) for (col in 0 until width / 2) {
            nv21[out++] = v.buffer.get(vBase + row * v.rowStride + col * v.pixelStride)
            nv21[out++] = u.buffer.get(uBase + row * u.rowStride + col * u.pixelStride)
        }
        val jpeg = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), 88, jpeg)
        val data = jpeg.toByteArray()
        return BitmapFactory.decodeByteArray(data, 0, data.size) ?: error("Could not decode camera frame")
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun downscale(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val edge = maxOf(bitmap.width, bitmap.height)
        if (edge <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / edge
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt(), true)
    }

    private fun card() = MaterialCardView(this).apply {
        radius = dp(8).toFloat(); cardElevation = 0f; strokeWidth = dp(1)
        strokeColor = 0xFFDCE3E0.toInt(); setCardBackgroundColor(0xFFFFFFFF.toInt())
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun <T : View> T.top(value: Int): T = apply {
        val params = layoutParams as? LinearLayout.LayoutParams
        if (params != null) { params.topMargin = dp(value); layoutParams = params }
    }

    override fun onPause() {
        if (running) stopSession()
        super.onPause()
    }

    override fun onDestroy() {
        if (::overlay.isInitialized) stopSession()
        speechModel?.close()
        speechModel = null
        cameraExecutor.shutdown()
        analysisExecutor.shutdown()
        store.close()
        super.onDestroy()
    }

    companion object {
        private const val FRAME_INTERVAL_MS = 900L
        private const val AUDIO_SAMPLE_RATE = 16_000
        private const val AUDIO_BUFFER_BYTES = 6_400
        private const val AUDIO_CHUNK_SAMPLES = 1_024
        private const val AUDIO_METER_INTERVAL_MS = 500L
        private const val MIN_AUDIBLE_DBFS = -55
        private const val MIN_SPEECH_DBFS = -52.0
    }
}
