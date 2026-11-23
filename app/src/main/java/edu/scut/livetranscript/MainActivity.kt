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
import android.media.MediaMetadataRetriever
import android.util.Base64
import android.util.Log
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
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import androidx.core.widget.doAfterTextChanged
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
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.vosk.Model
import org.vosk.android.StorageService
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val mouthExecutor = Executors.newSingleThreadExecutor()
    private val overlapExecutor = Executors.newSingleThreadExecutor()
    private val processing = AtomicBoolean(false)
    private val mouthProcessing = AtomicBoolean(false)
    private val lastFrameAt = AtomicLong(0L)
    private val lastMouthFrameAt = AtomicLong(0L)
    private val tracker = FaceTracker()
    private val speakerAttributor = SpeakerAttributor()
    private val pcmTimeline = PcmTimelineBuffer()
    private val voiceEnrollment = VoiceprintEnrollment()
    private val store by lazy { LocalStore(this) }
    private val transcriptSession by lazy {
        TranscriptSession(
            resolveSpeaker = ::resolveSpeaker,
            onPreview = { generation, line ->
                runOnUiThread {
                    if (running && generation == sessionGeneration) {
                        transcriptPreview.text = if (line.text.isBlank()) "等待语音…" else "${line.speaker}：${line.text}"
                    }
                }
            },
            onFinal = { generation, line ->
                store.addUtterance(line.speaker, line.text)
                runOnUiThread {
                    refreshTranscriptList()
                    if (generation == sessionGeneration || (!running && generation + 1 == sessionGeneration)) {
                        if (running) {
                            transcriptPreview.text = "${line.speaker}：${line.text}"
                            speechStatus.text = "已保存到本机 · $speechEngineStatus"
                        } else {
                            transcriptPreview.text = "${line.speaker}：${line.text}"
                        }
                    }
                }
            }
        )
    }
    private val pipeline by lazy { FacePipeline(this) }
    private val mec by lazy { MecOffload(this, pipeline) }
    private val replayLog by lazy { ReplayExperimentLog(this) }
    @Volatile private var mecMode = "本地"
    @Volatile private var speechMode = "电脑端中英"
    @Volatile private var mecUrl = "http://10.0.2.2:8000"
    @Volatile private var funasrUrl = BuildConfig.FUNASR_WS_URL
    @Volatile private var overlapUrl = "http://10.0.2.2:8001/separate-and-transcribe"
    @Volatile private var mecSize = 640
    @Volatile private var mecLimit = 0
    @Volatile private var mecReset = false
    @Volatile private var mouthAnalyzer: MouthActivityAnalyzer? = null
    @Volatile private var mouthAnalysisUnavailable = false
    @Volatile private var running = false
    @Volatile private var replayBusy = false
    @Volatile private var sessionGeneration = 0L
    private val speechEventLock = Any()
    @Volatile private var currentFaces: List<TrackedFace> = emptyList()
    @Volatile private var selectedTrackId: Int? = null
    @Volatile private var frameWidth = 640
    @Volatile private var frameHeight = 480
    @Volatile private var speechEngineStatus = "识别服务尚未启动"
    private var speechModel: Model? = null
    private var speechEnglishModel: Model? = null
    private var speechEngine: SpeechEngine? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    @Volatile private var audioCaptureRunning = false
    private var modelLoading = false
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
    private lateinit var mecStatus: TextView
    private lateinit var replayStatus: TextView
    private val exportMetrics = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null && mec.logFile.exists()) contentResolver.openOutputStream(uri)?.use { output -> mec.logFile.inputStream().use { it.copyTo(output) } }
    }
    private val exportReplayMetrics = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null && replayLog.file.exists()) contentResolver.openOutputStream(uri)?.use { output -> replayLog.file.inputStream().use { it.copyTo(output) } }
    }
    private val exportEdgeDiagnostics = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null && replayLog.edgeFile.exists()) contentResolver.openOutputStream(uri)?.use { output -> replayLog.edgeFile.inputStream().use { it.copyTo(output) } }
    }

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
        if (BuildConfig.DEBUG && intent.getBooleanExtra("mecBenchmark", false)) runSampleBenchmark()
        if (BuildConfig.DEBUG && intent.getBooleanExtra("videoBenchmark", false)) runVideoReplayBenchmark()
        if (BuildConfig.DEBUG && intent.getBooleanExtra("edgeBenchmark", false)) runEdgeCaseReplay()
        if (BuildConfig.DEBUG && intent.getBooleanExtra("speechBenchmark", false)) runWavSpeechBenchmark()
        if (BuildConfig.DEBUG && intent.getBooleanExtra("overlapBenchmark", false)) runOverlapReplayBenchmark()
    }

    private fun buildScreen() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18))
            setBackgroundColor(0xFFF4F6F5.toInt())
        }
        val scroll = ScrollView(this).apply {
            addView(page)
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            scrollBarSize = dp(10)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        setContentView(scroll)
        ViewCompat.requestApplyInsets(scroll)

        page.addView(label("LIVE SESSION", 12f, 0xFF176B62.toInt(), true))
        page.addView(label("实时人脸、声纹与字幕", 25f, 0xFF172321.toInt(), true).top(4))
        page.addView(label("音频实时发送至识别服务 · 人脸/声纹特征与转写文本保存在本机", 13f, 0xFF586561.toInt()).top(3))

        liveBadge = label("● 已停止", 13f, 0xFF586561.toInt(), true).top(10)
        page.addView(liveBadge)
        preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
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
        page.addView(card().apply { addView(cameraFrame, FrameLayout.LayoutParams(-1, -1)) }.top(12), LinearLayout.LayoutParams(-1, dp(450)))

        consent = CheckBox(this).apply {
            text = "已告知参与者并取得人脸、声纹与语音识别同意"
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

        page.addView(label("计算切分实验", 16f, 0xFF172321.toInt(), true).top(12))
        fun options(items: List<String>, selected: (String) -> Unit): Spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { selected(items[position]); mecReset = true }
            }
        }
        page.addView(options(listOf("本地", "对齐后卸载", "整帧卸载", "动态")) { mecMode = it })
        page.addView(label("语音识别位置（停止采集后切换）", 12f, 0xFF586561.toInt()))
        page.addView(options(listOf("电脑端中英", "本地中英", "本地中文", "本地英文")) { speechMode = it })
        page.addView(label("人脸 MEC HTTP 服务地址", 12f, 0xFF586561.toInt()))
        page.addView(EditText(this).apply {
            setSingleLine(true); textSize = 14f; hint = "人脸服务，例如 http://10.0.2.2:8000"
            setText(mecUrl)
            doAfterTextChanged { mecUrl = it.toString().trim().trimEnd('/'); mecReset = true }
        })
        page.addView(label("电脑端 FunASR WebSocket 地址", 12f, 0xFF586561.toInt()))
        page.addView(EditText(this).apply {
            setSingleLine(true); textSize = 14f; hint = "模拟器用 localhost；手机填电脑局域网 IP"
            setText(funasrUrl)
            doAfterTextChanged { funasrUrl = it.toString().trim() }
        })
        page.addView(label("双人重叠分离服务地址", 12f, 0xFF586561.toInt()))
        page.addView(EditText(this).apply {
            setSingleLine(true); textSize = 14f; hint = "例如 http://10.0.2.2:8001/separate-and-transcribe"
            setText(overlapUrl)
            doAfterTextChanged { overlapUrl = it.toString().trim() }
        })
        page.addView(label("输入图像最大边长", 12f, 0xFF586561.toInt()))
        page.addView(options(listOf("640", "480", "320")) { mecSize = it.toInt() })
        page.addView(label("上行限速（实验模拟）", 12f, 0xFF586561.toInt()))
        page.addView(options(listOf("不限速", "512 kbps", "2000 kbps", "10000 kbps")) { mecLimit = it.substringBefore(' ').toIntOrNull() ?: 0 })
        mecStatus = label("等待采样", 12f, 0xFF586561.toInt())
        page.addView(mecStatus)
        page.addView(Button(this).apply { text = "导出实验 CSV"; isAllCaps = false; setOnClickListener { exportMetrics.launch("mec-experiments.csv") } })
        page.addView(Button(this).apply { text = "运行样例切分实验"; isAllCaps = false; setOnClickListener { runSampleBenchmark() } })

        page.addView(label("可复现实验素材", 16f, 0xFF172321.toInt(), true).top(12))
        page.addView(label("内置同源 MP4 / 16 kHz WAV；包含单人讲话、多镜头与观众画面。", 12f, 0xFF586561.toInt()))
        replayStatus = label("等待运行", 12f, 0xFF586561.toInt()).top(4)
        page.addView(replayStatus)
        page.addView(Button(this).apply {
            text = "回放 MP4：静态 + 动态人脸切分"
            isAllCaps = false
            setOnClickListener { runVideoReplayBenchmark() }
        })
        page.addView(Button(this).apply {
            text = "边界回放：影视对话 / 多人 / 遮挡 / 出画"
            isAllCaps = false
            setOnClickListener { runEdgeCaseReplay() }
        })
        page.addView(Button(this).apply {
            text = "回放 WAV：本地中英/英文 vs 电脑端中英"
            isAllCaps = false
            setOnClickListener { runWavSpeechBenchmark() }
        })
        page.addView(Button(this).apply {
            text = "回放双人重叠：分离后并行转写"
            isAllCaps = false
            setOnClickListener { runOverlapReplayBenchmark() }
        })
        page.addView(Button(this).apply {
            text = "导出复现实验 CSV"
            isAllCaps = false
            setOnClickListener { exportReplayMetrics.launch("reproducible-experiments.csv") }
        })
        page.addView(Button(this).apply {
            text = "导出边界场景诊断 CSV"
            isAllCaps = false
            setOnClickListener { exportEdgeDiagnostics.launch("reproducible-edge-diagnostics.csv") }
        })

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
        page.addView(Button(this).apply {
            text = "录制选中人的声纹（5 秒）"
            isAllCaps = false
            setOnClickListener { startVoiceprintEnrollment() }
        }.top(2))
        val participantStatusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        peopleStatus = label("身份库 0 人 · 本机保存", 12f, 0xFF586561.toInt())
        participantStatusRow.addView(peopleStatus, LinearLayout.LayoutParams(0, -2, 1f))
        participantStatusRow.addView(Button(this).apply {
            text = "管理身份"
            isAllCaps = false
            setOnClickListener { showParticipantManager() }
        })
        page.addView(participantStatusRow.top(2))

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
        page.addView(label("点击人脸用于登记身份；转写说话人根据嘴部活动判断，置信度不足时标为“未确认说话人”。", 12f, 0xFF586561.toInt()).top(10))
    }

    private fun runSampleBenchmark() {
        val endpoint = mecUrl
        mecStatus.text = "样例实验正在运行：不同切分点、图像尺寸与上行限速"
        analysisExecutor.execute {
            try {
                val original = assets.open("test/lena.jpg").use { BitmapFactory.decodeStream(it) }
                var count = 0
                try {
                    for (size in listOf(320, 640)) {
                        val frame = downscale(original, size)
                        try {
                            for (limit in listOf(0, 512)) {
                                for (mode in listOf("本地", "对齐后卸载", "整帧卸载")) {
                                    val started = SystemClock.elapsedRealtime()
                                    val result = mec.run(frame, mode, endpoint, limit)
                                    mec.log(result, "样例_$mode", frame.width, frame.height, limit, (SystemClock.elapsedRealtime() - started).toDouble(), 0.0)
                                    count++
                                }
                                mec.reset()
                                repeat(4) {
                                    val started = SystemClock.elapsedRealtime()
                                    val result = mec.run(frame, "动态", endpoint, limit)
                                    mec.log(result, "样例_动态", frame.width, frame.height, limit, (SystemClock.elapsedRealtime() - started).toDouble(), 0.0)
                                    count++
                                }
                            }
                        } finally { if (frame !== original) frame.recycle() }
                    }
                } finally { original.recycle() }
                runOnUiThread { mecStatus.text = "样例实验完成：$count 条记录，可导出 CSV" }
            } catch (error: Exception) {
                runOnUiThread { mecStatus.text = "样例实验失败：${error.localizedMessage}" }
            }
        }
    }

    private fun runVideoReplayBenchmark() {
        if (running || replayBusy) {
            replayStatus.text = "请先停止实时采集，并等待当前实验结束。"
            return
        }
        replayBusy = true
        replayStatus.text = "正在解码 MP4 并回放 320/640 输入、静态切分、动态网络阶段…"
        val endpoint = mecUrl
        analysisExecutor.execute {
            var decodedFrames = emptyList<Pair<Long, Bitmap>>()
            var rows = 0
            try {
                decodedFrames = decodeReplayFrames("obama-farewell-60s.mp4", listOf(2_000L, 40_000L, 59_000L))
                mec.reset()
                for (size in listOf(320, 640)) {
                    val frames = decodedFrames.map { (atMs, bitmap) -> Triple(atMs, downscale(bitmap, size), bitmap) }
                    try {
                        for (limit in listOf(0, 512)) {
                            for (mode in listOf("本地", "对齐后卸载", "整帧卸载")) {
                                for ((atMs, frame, _) in frames) {
                                    try {
                                        val result = mec.run(frame, mode, endpoint, limit)
                                        replayLog.face("obama-farewell-60s.mp4", "static_${mode}_size${size}", atMs,
                                            frame.width, frame.height, limit, mode, result, emptyList())
                                    } catch (error: Exception) {
                                        replayLog.faceFailure("obama-farewell-60s.mp4", "static_${mode}_size${size}", atMs,
                                            frame.width, frame.height, limit, mode, error.message ?: error.javaClass.simpleName)
                                    }
                                    rows++
                                    if (rows % 6 == 0) runOnUiThread { replayStatus.text = "MP4 静态矩阵进行中：$rows 条记录" }
                                }
                            }
                        }
                    } finally {
                        frames.forEach { (_, frame, original) -> if (frame !== original) frame.recycle() }
                    }
                }

                var priorLimit = -1
                val dynamicLimits = List(12) { 0 } + List(12) { 512 } + List(12) { 0 }
                dynamicLimits.forEachIndexed { index, limit ->
                    val (atMs, source) = decodedFrames[index % decodedFrames.size]
                    val frame = downscale(source, 640)
                    try {
                        try {
                            val result = mec.run(frame, "动态", endpoint, limit, forceProbe = limit != priorLimit)
                            replayLog.face("obama-farewell-60s.mp4", "dynamic_network_phase_${if (limit == 0) "unlimited" else "512kbps"}",
                                atMs, frame.width, frame.height, limit, "动态", result, emptyList())
                        } catch (error: Exception) {
                            replayLog.faceFailure("obama-farewell-60s.mp4", "dynamic_network_phase_${if (limit == 0) "unlimited" else "512kbps"}",
                                atMs, frame.width, frame.height, limit, "动态", error.message ?: error.javaClass.simpleName)
                        }
                        rows++
                        priorLimit = limit
                    } finally { if (frame !== source) frame.recycle() }
                }
                val completedRows = rows
                runOnUiThread { replayStatus.text = "MP4 回放完成：$completedRows 条；含静态切分与 0→512→0 kbps 动态网络阶段。" }
            } catch (error: Exception) {
                runOnUiThread { replayStatus.text = "MP4 实验失败：${error.localizedMessage ?: error.javaClass.simpleName}" }
            } finally {
                decodedFrames.forEach { it.second.recycle() }
                replayBusy = false
            }
        }
    }

    private fun decodeReplayFrames(assetName: String, timestampsMs: List<Long>): List<Pair<Long, Bitmap>> {
        val video = File(cacheDir, assetName)
        assets.open("test/reproducible/$assetName").use { input -> video.outputStream().use(input::copyTo) }
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(video.absolutePath)
        return try {
            timestampsMs.map { atMs ->
                atMs to (retriever.getFrameAtTime(atMs * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: error("无法从内置 MP4 解码 ${atMs}ms 帧"))
            }
        } finally {
            retriever.release()
            video.delete()
        }
    }

    private data class EdgeReplayRow(val atMs: Long, val width: Int, val height: Int, val result: MecResult,
        val tracks: List<TrackedFace>, val mouthMatchedTracks: Int)

    private fun runEdgeCaseReplay() {
        if (running || replayBusy) {
            replayStatus.text = "请先停止实时采集，并等待当前实验结束。"
            return
        }
        replayBusy = true
        replayStatus.text = "正在回放影视快剪、多人物、遮挡与人物出画素材…"
        val endpoint = mecUrl
        analysisExecutor.execute {
            val cases = listOf(
                "two-speaker-overlap-20s.mp4" to "two_person_overlap",
                "face-occlusion-20s.mp4" to "face_occlusion",
                "faces-leave-frame-20s.mp4" to "face_leaves_frame",
                "his-girl-friday-dialogue-20s.mp4" to "film_rapid_dialogue",
                "his-girl-friday-crowd-20s.mp4" to "film_multi_person",
                "his-girl-friday-occlusion-20s.mp4" to "film_multi_person_occlusion"
            )
            var rowCount = 0
            val participants = store.participants()
            try {
                mec.reset()
                for ((assetName, scenario) in cases) {
                    val timestamps = when (scenario) {
                        "two_person_overlap" -> (1_000L..4_000L step 200L).toList()
                        "film_rapid_dialogue" -> (500L..19_500L step 500L).toList()
                        "film_multi_person", "film_multi_person_occlusion" ->
                            (1_000L..19_000L step 2_000L).toList()
                        else -> listOf(2_000L, 4_000L, 6_000L, 8_000L, 10_000L, 12_000L, 15_000L, 19_000L)
                    }
                    val frames = decodeReplayFrames(assetName, timestamps)
                    val replayBase = SystemClock.elapsedRealtime()
                    tracker.clear()
                    speakerAttributor.clear()
                    val analyzer = MouthActivityAnalyzer(this, speakerAttributor)
                    try {
                        val results = frames.map { (atMs, bitmap) ->
                            val result = mec.run(bitmap, "本地", endpoint, 0)
                            val tracked = tracker.update(result.samples, participants, replayBase + atMs)
                            val mouthMatchedTracks = analyzer.process(bitmap, tracked, replayBase + atMs)
                            Log.i("EdgeReplay", "$scenario t=$atMs faces=${result.samples.size} ${analyzer.lastDiagnostic}")
                            EdgeReplayRow(atMs, bitmap.width, bitmap.height, result, tracked, mouthMatchedTracks)
                        }
                        val overlapEvent = SpeechEvent("overlap-probe", replayBase + 1_000L, replayBase + 4_000L, true)
                        val overlapIds = if (scenario == "two_person_overlap") speakerAttributor.overlappingTracks(overlapEvent) else emptySet()
                        val overlapSamples = if (scenario == "two_person_overlap") {
                            speakerAttributor.samplesBetween(overlapEvent.startedAtMs - 100L, overlapEvent.endedAtMs)
                        } else emptyList()
                        results.forEach { row ->
                            replayLog.face(assetName, scenario, row.atMs, row.width, row.height, 0, "本地", row.result,
                                row.tracks.map { "${it.id}:${it.label}" }, overlapIds)
                            val mouthSamples = if (scenario == "two_person_overlap") overlapSamples else
                                speakerAttributor.samplesBetween(replayBase + row.atMs - 1_000L, replayBase + row.atMs)
                            replayLog.edge(assetName, scenario, row.atMs, row.result.samples.size,
                                row.tracks.map { "${it.id}:${it.label}" }, row.mouthMatchedTracks, mouthSamples, overlapIds)
                            rowCount++
                        }
                        val maxFaces = results.maxOfOrNull { it.result.samples.size } ?: 0
                        val label = when (scenario) {
                            "two_person_overlap" -> "嘴部重叠人脸 ${overlapIds.size}；MediaPipe样本 ${overlapSamples.size}"
                            "film_rapid_dialogue" -> "快剪采样；最多检测 $maxFaces 张脸"
                            "film_multi_person" -> "多人拼贴；最多检测 $maxFaces 张脸"
                            "film_multi_person_occlusion" -> "多人遮挡；最多检测 $maxFaces 张脸"
                            else -> "$scenario；最多检测 $maxFaces 张脸"
                        }
                        runOnUiThread { replayStatus.text = "$assetName：${results.size} 帧；$label" }
                    } finally {
                        analyzer.close()
                        frames.forEach { it.second.recycle() }
                    }
                }
                val completed = rowCount
                runOnUiThread { replayStatus.text = "边界回放完成：$completed 条帧记录；已写入检测数、人脸轨迹与重叠候选。" }
            } catch (error: Exception) {
                runOnUiThread { replayStatus.text = "边界回放失败：${error.localizedMessage ?: error.javaClass.simpleName}" }
            } finally { replayBusy = false }
        }
    }

    private fun runOverlapReplayBenchmark() {
        if (running || replayBusy) {
            replayStatus.text = "请先停止实时采集，并等待当前实验结束。"
            return
        }
        replayBusy = true
        replayStatus.text = "正在同步回放双人嘴部活动与 20 秒重叠 PCM…"
        val endpoint = overlapUrl
        val faceEndpoint = mecUrl
        analysisExecutor.execute {
            try {
                val videoName = "two-speaker-overlap-20s.mp4"
                val replayBase = SystemClock.elapsedRealtime()
                val frames = decodeReplayFrames(videoName, (1_000L..4_000L step 200L).toList())
                val analyzer = MouthActivityAnalyzer(this, speakerAttributor)
                val participants = store.participants()
                val edgeRows = mutableListOf<EdgeReplayRow>()
                tracker.clear()
                speakerAttributor.clear()
                mec.reset()
                try {
                    for ((atMs, bitmap) in frames) {
                        val faceResult = mec.run(bitmap, "本地", faceEndpoint, 0)
                        val tracked = tracker.update(faceResult.samples, participants, replayBase + atMs)
                        val mouthMatchedTracks = analyzer.process(bitmap, tracked, replayBase + atMs)
                        edgeRows += EdgeReplayRow(atMs, bitmap.width, bitmap.height, faceResult, tracked, mouthMatchedTracks)
                    }
                } finally {
                    analyzer.close()
                    frames.forEach { it.second.recycle() }
                }
                val matchEvent = SpeechEvent("overlap-probe", replayBase + 1_000L, replayBase + 4_000L, true)
                val candidateTrackIds = speakerAttributor.overlappingTracks(
                    matchEvent,
                    edgeRows.flatMap { row -> row.tracks.map { it.id } }.toSet()
                )
                edgeRows.forEach { row ->
                    val mouthSamples = speakerAttributor.samplesBetween(replayBase + row.atMs - 1_000L, replayBase + row.atMs)
                    replayLog.edge(videoName, "two_person_overlap_audio_link", row.atMs, row.result.samples.size,
                        row.tracks.map { "${it.id}:${it.label}" }, row.mouthMatchedTracks, mouthSamples, candidateTrackIds)
                }

                val wav = assets.open("test/reproducible/two-speaker-overlap-20s-16k-mono.wav").use { PcmWavReader.read(it.readBytes()) }
                val requestBytes = ByteBuffer.allocate(wav.pcmBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .apply { wav.samples.forEach(::putShort) }.array()
                val request = URL(endpoint).openConnection() as HttpURLConnection
                request.connectTimeout = 10_000
                request.readTimeout = 120_000
                request.requestMethod = "POST"
                request.doOutput = true
                request.setFixedLengthStreamingMode(requestBytes.size)
                request.setRequestProperty("Content-Type", "application/octet-stream")
                val started = SystemClock.elapsedRealtime()
                request.outputStream.use { it.write(requestBytes) }
                val responseBytes = request.inputStream.use { it.readBytes() }
                request.disconnect()
                val response = JSONObject(String(responseBytes, Charsets.UTF_8))
                val sources = response.getJSONArray("sources")
                val matchStart = AUDIO_SAMPLE_RATE
                val matchEnd = AUDIO_SAMPLE_RATE * 4
                val matchingSources = mutableListOf<ShortArray>()
                val matchedPcm = (0 until sources.length()).map { index ->
                    val payload = Base64.decode(sources.getJSONObject(index).getString("audio_base64"), Base64.DEFAULT)
                    val pcmView = ByteBuffer.wrap(payload).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    ShortArray(pcmView.remaining()).also(pcmView::get).also { full ->
                        matchingSources += full.copyOfRange(matchStart, minOf(matchEnd, full.size))
                    }
                }
                val faceMapping = speakerAttributor.associateSeparatedSources(matchEvent, matchingSources, candidateTrackIds)
                val labels = mutableListOf<String>()
                val outputs = mutableListOf<String>()
                for (index in 0 until sources.length()) {
                    val source = sources.getJSONObject(index)
                    val samples = matchedPcm[index]
                    val identity = VoiceprintMatcher.match(VoiceprintExtractor.extract(samples), store.participants())
                    val faceTrackId = faceMapping[index]
                    val speaker = identity?.name ?: faceTrackId?.let(tracker::labelForTrackId)
                        ?: "陌生人 ${('A'.code + index).toChar()}"
                    val text = source.optString("text").trim()
                    val status = if (text.isBlank()) "no_text" else "ok"
                    if (text.isNotBlank()) {
                        store.addUtterance(speaker, text)
                        labels += speaker
                        val face = faceTrackId?.let { " · 人脸轨迹 $it" } ?: " · 人脸关联待确认"
                        outputs += "$speaker$face：$text"
                    }
                    replayLog.speech(
                        "two_speaker_overlap", "two-speaker-overlap-20s-16k-mono.wav",
                        "SepFormer+FunASR_${index + 1}_$speaker${faceTrackId?.let { "_faceTrack$it" } ?: "_faceUnconfirmed"}",
                        wav.pcmBytes, wav.durationMs, (SystemClock.elapsedRealtime() - started).toDouble(),
                        source.optDouble("asr_ms", 0.0),
                        if (index == 0) requestBytes.size.toLong() else 0L,
                        if (index == 0) responseBytes.size.toLong() else 0L,
                        text, status
                    )
                }
                runOnUiThread {
                    refreshTranscriptList()
                    transcriptPreview.text = outputs.joinToString("\n")
                    replayStatus.text = "重叠分离完成：${labels.joinToString("、")}；嘴部候选 ${candidateTrackIds.size} 人；人脸关联 ${faceMapping.size} 路；设备 ${response.optString("device")}。"
                }
            } catch (error: Exception) {
                runOnUiThread { replayStatus.text = "双人重叠分离失败：${error.localizedMessage ?: error.javaClass.simpleName}" }
            } finally { replayBusy = false }
        }
    }

    private data class SpeechReplayResult(
        val text: String,
        val wallMs: Double,
        val firstResultMs: Double?,
        val status: String,
        val transfer: SpeechTransferMetrics
    )

    private fun runWavSpeechBenchmark() {
        if (running || replayBusy) {
            replayStatus.text = "请先停止实时采集，并等待当前实验结束。"
            return
        }
        replayBusy = true
        replayStatus.text = "正在准备同一 20 秒英语 WAV 的本地/电脑端语音对照…"
        val endpoint = funasrUrl
        fun launch(chineseModel: Model, englishModel: Model) {
            analysisExecutor.execute {
                try {
                    val wav = assets.open("test/reproducible/obama-farewell-60s-16k-mono.wav").use { PcmWavReader.read(it.readBytes()) }
                    check(wav.sampleRate == AUDIO_SAMPLE_RATE && wav.channels == 1) { "实验 WAV 必须为 16 kHz 单声道" }
                    val comparisonSamples = wav.samples.copyOfRange(0, minOf(wav.samples.size, 20 * AUDIO_SAMPLE_RATE))
                    val comparisonBytes = comparisonSamples.size * 2
                    val comparisonDurationMs = comparisonSamples.size * 1000L / AUDIO_SAMPLE_RATE
                    val bilingualSamples = comparisonSamples
                    val bilingual = replaySpeech("本地中英默认", BilingualVoskSpeechEngine(chineseModel, englishModel), bilingualSamples, waitForRemote = false)
                    replayLog.speech("local_bilingual_default", "obama-farewell-60s-16k-mono.wav", "本地中英默认",
                        comparisonBytes, comparisonDurationMs, bilingual.wallMs, bilingual.firstResultMs,
                        bilingual.transfer.uploadedPayloadBytes, bilingual.transfer.downloadedPayloadBytes, bilingual.text, bilingual.status)
                    runOnUiThread { replayStatus.text = "本地中英默认模式完成：${bilingual.text.take(90)}；继续单模型对照…" }

                    val local = replaySpeech("本地Vosk英文", VoskSpeechEngine(englishModel), comparisonSamples, waitForRemote = false)
                    replayLog.speech("single_speaker_compare", "obama-farewell-60s-16k-mono.wav", "本地Vosk英文",
                        comparisonBytes, comparisonDurationMs, local.wallMs, local.firstResultMs,
                        local.transfer.uploadedPayloadBytes, local.transfer.downloadedPayloadBytes, local.text, local.status)
                    runOnUiThread { replayStatus.text = "本地英文完成：${local.text.take(90)}；继续电脑端 FunASR…" }

                    val remoteEngine = FunAsrSpeechEngine(
                        endpoint = endpoint,
                        fallback = null,
                        onStatus = { message -> runOnUiThread { if (replayBusy) replayStatus.text = "电脑端 FunASR：$message" } },
                        hotwords = store.participants().map { it.name }
                    )
                    val remote = replaySpeech("电脑端FunASR中英", remoteEngine, comparisonSamples, waitForRemote = true)
                    replayLog.speech("single_speaker_compare", "obama-farewell-60s-16k-mono.wav", "电脑端FunASR中英",
                        comparisonBytes, comparisonDurationMs, remote.wallMs, remote.firstResultMs,
                        remote.transfer.uploadedPayloadBytes, remote.transfer.downloadedPayloadBytes, remote.text, remote.status)
                    runOnUiThread {
                        replayStatus.text = "WAV 对照完成：本地 ${local.status}；电脑端 ${remote.status}；CSV 已记录语音字节与字幕。"
                    }
                } catch (error: Exception) {
                    runOnUiThread { replayStatus.text = "WAV 实验失败：${error.localizedMessage ?: error.javaClass.simpleName}" }
                } finally { replayBusy = false }
            }
        }

        fun loadEnglish(chineseModel: Model) {
            speechEnglishModel?.let { launch(chineseModel, it); return }
            StorageService.unpack(
                this,
                "models/vosk-model-small-en-us-0.15",
                "vosk-en",
                { model -> speechEnglishModel = model; launch(chineseModel, model) },
                { error ->
                    replayBusy = false
                    runOnUiThread { replayStatus.text = "本地英文模型加载失败：${error.message ?: "未知错误"}" }
                }
            )
        }

        speechModel?.let(::loadEnglish) ?: StorageService.unpack(
            this,
            "models/vosk-model-small-cn-0.22",
            "vosk",
            { model -> speechModel = model; loadEnglish(model) },
            { error ->
                replayBusy = false
                runOnUiThread { replayStatus.text = "本地中文模型加载失败：${error.message ?: "未知错误"}" }
            }
        )
    }

    private fun replaySpeech(mode: String, engine: SpeechEngine, samples: ShortArray, waitForRemote: Boolean): SpeechReplayResult {
        val finished = CountDownLatch(1)
        val transcript = SpeechTranscriptAccumulator()
        val status = AtomicReference(if (waitForRemote) "连接中" else "本地解码")
        val startedAt = SystemClock.elapsedRealtime()
        val firstResultAt = AtomicLong(0L)
        val transfer = engine as? FunAsrSpeechEngine
        engine.start { event ->
            if (event.text.isNotBlank()) {
                if (mode == "本地中英默认" && event.isFinal) {
                    Log.i("BilingualReplay", "${event.startedAtMs}-${event.endedAtMs} confidence=${event.confidence} text=${event.text}")
                }
                firstResultAt.compareAndSet(0L, SystemClock.elapsedRealtime() - startedAt)
                transcript.accept(event)
                if (event.isFinal) finished.countDown()
            }
        }
        if (waitForRemote && transfer != null) {
            val connectionDeadline = SystemClock.elapsedRealtime() + 15_000L
            while (!transfer.isRemoteReady() && transfer.connectionStatus() == "CONNECTING" && SystemClock.elapsedRealtime() < connectionDeadline) {
                Thread.sleep(100L)
            }
            if (!transfer.isRemoteReady()) {
                val connectionState = transfer.connectionStatus()
                engine.stop()
                return SpeechReplayResult("", (SystemClock.elapsedRealtime() - startedAt).toDouble(), null,
                    "connect_$connectionState", transfer.transferMetrics())
            }
        }

        val sourceStartAt = SystemClock.elapsedRealtime()
        var offset = 0
        val chunk = 1_600
        while (offset < samples.size) {
            val count = minOf(chunk, samples.size - offset)
            val captureAt = sourceStartAt + (offset + count) * 1000L / AUDIO_SAMPLE_RATE
            engine.acceptPcm(samples.copyOfRange(offset, offset + count), count, captureAt)
            offset += count
            val target = offset * 1000L / AUDIO_SAMPLE_RATE
            val elapsed = SystemClock.elapsedRealtime() - sourceStartAt
            if (target > elapsed) Thread.sleep(target - elapsed)
        }
        val silence = ShortArray(chunk)
        repeat(8) { index ->
            val captureAt = sourceStartAt + samples.size * 1000L / AUDIO_SAMPLE_RATE + index * 100L
            engine.acceptPcm(silence, silence.size, captureAt)
            Thread.sleep(100L)
        }
        engine.stop()
        if (waitForRemote && transfer != null) transfer.awaitStopDrain(6_500L)
        else finished.await(2, TimeUnit.SECONDS)
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        val resultText = transcript.text()
        status.set(if (resultText.isBlank()) "no_final_text" else "ok")
        return SpeechReplayResult(
            resultText, elapsedMs.toDouble(), firstResultAt.get().takeIf { it > 0L }?.toDouble(),
            status.get(), transfer?.transferMetrics() ?: SpeechTransferMetrics(0, 0)
        )
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
        lastFrameAt.set(0L)
        lastMouthFrameAt.set(0L)
        mouthAnalysisUnavailable = false
        speakerAttributor.clear()
        pcmTimeline.clear()
        voiceEnrollment.cancel()
        synchronized(speechEventLock) {
            sessionGeneration++
            tracker.clear()
            currentFaces = emptyList()
            running = true
            transcriptSession.start(sessionGeneration)
        }
        startButton.text = "停止采集"
        consent.isEnabled = false
        liveBadge.text = "● CAMERA ON · MIC ON"
        liveBadge.setTextColor(0xFFB44D37.toInt())
        status.text = "正在启动摄像头与语音识别…"
        startCamera()
        startSpeechRecognition()
    }

    private fun startCamera() {
        val generation = sessionGeneration
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                if (!running || generation != sessionGeneration) return@addListener
                cameraProvider = provider
                val previewUseCase = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(android.util.Size(640, 480))
                    .build()
                analysis.setAnalyzer(cameraExecutor, ::analyzeFrame)
                provider.unbindAll()
                val selector = when {
                    provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    else -> CameraSelector.Builder()
                        .addCameraFilter { cameras -> cameras.take(1) }
                        .build()
                }
                provider.bindToLifecycle(this, selector, previewUseCase, analysis)
                analysisUseCase = analysis
                status.text = "摄像头与麦克风运行中 · 未知访客编号只在本次会话有效"
            } catch (error: Exception) {
                if (running && generation == sessionGeneration) {
                    status.text = "摄像头启动失败：${error.localizedMessage ?: "不可用"}"
                    stopSession()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val generation = sessionGeneration
        if (!running) {
            image.close()
            return
        }
        val runIdentity = now - lastFrameAt.get() >= FRAME_INTERVAL_MS && processing.compareAndSet(false, true)
        val runMouth = !mouthAnalysisUnavailable && now - lastMouthFrameAt.get() >= MOUTH_FRAME_INTERVAL_MS &&
            mouthProcessing.compareAndSet(false, true)
        if (!runIdentity && !runMouth) {
            image.close()
            return
        }
        if (runIdentity) lastFrameAt.set(now)
        if (runMouth) lastMouthFrameAt.set(now)
        val bitmap = try {
            val decoded = imageToBitmap(image)
            try {
                rotate(decoded, image.imageInfo.rotationDegrees).also { rotated ->
                    if (rotated !== decoded && !decoded.isRecycled) decoded.recycle()
                }
            } catch (error: Exception) {
                if (!decoded.isRecycled) decoded.recycle()
                throw error
            }
        }
        catch (_: Exception) {
            image.close()
            if (runIdentity) processing.set(false)
            if (runMouth) mouthProcessing.set(false)
            return
        }
        image.close()

        val identityBitmap = if (runIdentity && runMouth) bitmap.copy(Bitmap.Config.ARGB_8888, false) else null
        if (runIdentity && runMouth && identityBitmap == null) {
            bitmap.recycle()
            processing.set(false)
            mouthProcessing.set(false)
            return
        }
        val faceFrame = if (runIdentity) identityBitmap ?: bitmap else null
        val mouthFrame = if (runMouth) bitmap else null

        if (runIdentity) {
            analysisExecutor.execute {
                var frame: Bitmap? = null
                try {
                    if (!running || generation != sessionGeneration) return@execute
                    val processedFrame = downscale(faceFrame!!, mecSize)
                    frame = processedFrame
                    if (mecReset) { mec.reset(); mecReset = false }
                    val requested = mecMode
                    val metrics = try { mec.run(processedFrame, requested, mecUrl, mecLimit) }
                    catch (error: java.io.IOException) {
                        if (requested != "动态") throw error
                        mec.run(processedFrame, "本地", mecUrl, mecLimit)
                    }
                    val canonicalScale = minOf(1f, 640f / maxOf(faceFrame.width, faceFrame.height)) * faceFrame.width / processedFrame.width
                    val samples = metrics.samples.map { sample ->
                        val b = sample.bounds
                        FaceSample(android.graphics.RectF(b.left * canonicalScale, b.top * canonicalScale, b.right * canonicalScale, b.bottom * canonicalScale), sample.embedding)
                    }
                    if (!running || generation != sessionGeneration) return@execute
                    val people = store.participants()
                    val trackerStart = SystemClock.elapsedRealtimeNanos()
                    val tracked = synchronized(speechEventLock) {
                        if (!running || generation != sessionGeneration) null else {
                            val result = tracker.update(samples, people, SystemClock.elapsedRealtime())
                            frameWidth = (processedFrame.width * canonicalScale).roundToInt()
                            frameHeight = (processedFrame.height * canonicalScale).roundToInt()
                            currentFaces = result
                            result
                        }
                    } ?: return@execute
                    val trackerMs = (SystemClock.elapsedRealtimeNanos() - trackerStart) / 1_000_000.0
                    mec.log(metrics, requested, processedFrame.width, processedFrame.height, mecLimit, (SystemClock.elapsedRealtime() - now).toDouble(), trackerMs)
                    val processedWidth = frameWidth
                    val processedHeight = frameHeight
                    runOnUiThread {
                        if (!running || generation != sessionGeneration) return@runOnUiThread
                        overlay.submit(tracked, processedWidth, processedHeight)
                        mecStatus.text = "${metrics.mode} · ${metrics.totalMs.roundToInt()} ms · 上行 ${metrics.sent} B · 下行 ${metrics.received} B · CPU ${"%.2f".format(metrics.cpuLoad)} 核"
                        if (tracked.isEmpty()) status.text = "实时扫描中 · 当前画面未检测到人脸"
                        else status.text = "实时扫描中 · 当前 ${tracked.size} 人 · 点击人脸可登记身份"
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        if (running && generation == sessionGeneration) status.text = "画面分析失败：${error.localizedMessage ?: "请调整光线或距离"}"
                    }
                } finally {
                    if (frame != null && frame !== faceFrame && !frame.isRecycled) frame.recycle()
                    if (faceFrame != null && !faceFrame.isRecycled) faceFrame.recycle()
                    processing.set(false)
                }
            }
        }

        if (runMouth) {
            val trackedFaces = currentFaces.toList()
            mouthExecutor.execute {
                var processedFrame: Bitmap? = null
                try {
                    if (running && generation == sessionGeneration) {
                        processedFrame = downscale(mouthFrame!!, 640)
                        val analyzer = mouthAnalyzer ?: MouthActivityAnalyzer(this, speakerAttributor).also { mouthAnalyzer = it }
                        analyzer.process(processedFrame!!, trackedFaces, now)
                    }
                } catch (error: Exception) {
                    mouthAnalyzer?.close()
                    mouthAnalyzer = null
                    speakerAttributor.clear()
                    if (running && generation == sessionGeneration) {
                        mouthAnalysisUnavailable = true
                        runOnUiThread {
                            if (running && generation == sessionGeneration) status.text = "嘴部说话人分析不可用：${error.localizedMessage ?: "请检查模型资源"}"
                        }
                    }
                } finally {
                    processedFrame?.let { if (it !== mouthFrame && !it.isRecycled) it.recycle() }
                    if (mouthFrame != null && !mouthFrame.isRecycled) mouthFrame.recycle()
                    mouthProcessing.set(false)
                }
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
        status.text = "已在本机登记 $name。开始采集后可录制其声纹。"
    }

    private fun startVoiceprintEnrollment() {
        if (!running || !audioCaptureRunning) {
            status.text = "请先开始采集，再录制声纹。"
            return
        }
        val face = currentFaces.firstOrNull { it.id == selectedTrackId }
            ?: currentFaces.singleOrNull()
        if (face == null) {
            status.text = "请点选一张清晰人脸后录制声纹。"
            return
        }
        val participant = store.participants().firstOrNull { it.name == face.label }
        if (participant == null) {
            status.text = "请先登记选中人脸的姓名，再录制声纹。"
            return
        }
        voiceEnrollment.start(participant.name)
        speechStatus.text = "正在登记 ${participant.name} 的声纹 · 请连续说话 5 秒"
    }

    private fun startSpeechRecognition() {
        if (audioCaptureRunning) {
            speechStatus.text = "实时语音识别运行中"
            return
        }
        if (modelLoading) {
            speechStatus.text = "正在加载语音模型…"
            return
        }
        val generation = sessionGeneration
        val selectedMode = speechMode
        if (selectedMode == "电脑端中英") {
            loadBilingualModels(generation) { fallback -> startFunAsrRecognition(fallback, generation) }
            return
        }
        if (selectedMode == "本地中英") {
            loadBilingualModels(generation) { engine ->
                if (running && generation == sessionGeneration) {
                    startAudioRecognition(engine, generation, "语音实际位置：手机 · 本地中英")
                }
            }
            return
        }
        modelLoading = true
        speechEngineStatus = "正在加载${selectedMode}模型…"
        speechStatus.text = speechEngineStatus
        StorageService.unpack(
            this,
            if (selectedMode == "本地英文") "models/vosk-model-small-en-us-0.15" else "models/vosk-model-small-cn-0.22",
            if (selectedMode == "本地英文") "vosk-en" else "vosk",
            { model ->
                modelLoading = false
                if (selectedMode == "本地英文") { speechEnglishModel?.close(); speechEnglishModel = model }
                else { speechModel?.close(); speechModel = model }
                if (running && generation == sessionGeneration) startAudioRecognition(VoskSpeechEngine(model), generation, "语音实际位置：手机 · $selectedMode")
            },
            { error ->
                modelLoading = false
                speechEngineStatus = "${selectedMode}模型加载失败：${error.message ?: "未知错误"}"
                runOnUiThread { if (running) speechStatus.text = speechEngineStatus }
            }
        )
    }

    private fun loadBilingualModels(generation: Long, onReady: (SpeechEngine) -> Unit) {
        val ready = {
            val chinese = speechModel
            val english = speechEnglishModel
            if (chinese != null && english != null) {
                modelLoading = false
                if (running && generation == sessionGeneration) {
                    onReady(BilingualVoskSpeechEngine(chinese, english))
                }
            }
        }
        val fail: (Throwable) -> Unit = { error ->
            modelLoading = false
            speechEngineStatus = "本地中英模型加载失败：${error.message ?: "未知错误"}"
            runOnUiThread { if (running && generation == sessionGeneration) speechStatus.text = speechEngineStatus }
        }
        modelLoading = true
        speechEngineStatus = "正在加载手机中英识别模型…"
        speechStatus.text = speechEngineStatus

        fun loadEnglish() {
            if (speechEnglishModel != null) {
                ready()
                return
            }
            StorageService.unpack(
                this,
                "models/vosk-model-small-en-us-0.15",
                "vosk-en",
                { model -> speechEnglishModel = model; ready() },
                fail
            )
        }

        if (speechModel != null) {
            loadEnglish()
        } else {
            StorageService.unpack(
                this,
                "models/vosk-model-small-cn-0.22",
                "vosk",
                { model -> speechModel = model; loadEnglish() },
                fail
            )
        }
    }

    private fun startFunAsrRecognition(localFallback: SpeechEngine?, generation: Long) {
        if (!running || generation != sessionGeneration || audioCaptureRunning) return
        try {
            val engine = FunAsrSpeechEngine(
                endpoint = funasrUrl,
                fallback = localFallback,
                onStatus = { message ->
                    if (running && generation == sessionGeneration) {
                        speechEngineStatus = message
                        runOnUiThread { if (running && generation == sessionGeneration) speechStatus.text = message }
                    }
                },
                hotwords = store.participants().map { it.name }
            )
            startAudioRecognition(engine, generation, "正在连接电脑端语音识别…")
        } catch (error: Exception) {
            speechEngineStatus = "语音启动失败：${error.localizedMessage ?: "未知错误"}"
            runOnUiThread { speechStatus.text = speechEngineStatus }
        }
    }

    private fun startAudioRecognition(engine: SpeechEngine, generation: Long, initialStatus: String) {
        if (!running || generation != sessionGeneration || audioCaptureRunning) return
        try {
            speechEngineStatus = initialStatus
            speechEngine = engine
            engine.start { event -> onSpeechEvent(event, generation) }
            val minBuffer = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            check(minBuffer > 0) { "Android 无法创建 16 kHz 麦克风流（$minBuffer）" }
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
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
            speechEngineStatus = "语音启动失败：${error.localizedMessage ?: "未知错误"}"
            runOnUiThread { speechStatus.text = speechEngineStatus }
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
                pcmTimeline.append(samples, count, now)
                voiceEnrollment.append(samples, count)?.let { captured ->
                    val embedding = captured.embedding
                    if (embedding == null) {
                        runOnUiThread { speechStatus.text = "声纹登记未采到足够清晰的语音 · 请重试" }
                    } else {
                        store.saveVoiceprint(captured.name, embedding)
                        runOnUiThread {
                            refreshParticipants()
                            speechStatus.text = "${captured.name} 的声纹已保存在本机 · $speechEngineStatus"
                        }
                    }
                }
                if (now - lastMeterUpdate >= AUDIO_METER_INTERVAL_MS) {
                    lastMeterUpdate = now
                    val level = levelDb.roundToInt()
                    val enrollmentName = voiceEnrollment.activeName()
                    runOnUiThread {
                        speechStatus.text = if (enrollmentName != null) {
                            "正在登记 $enrollmentName 的声纹 · 输入 ${level} dBFS"
                        } else if (level < MIN_AUDIBLE_DBFS) {
                            "麦克风输入 ${level} dBFS · $speechEngineStatus"
                        } else {
                            "麦克风正在收音 ${level} dBFS · $speechEngineStatus"
                        }
                    }
                }

                engine.acceptPcm(samples, count, now)
            }
        } catch (error: Exception) {
            if (running) {
                speechEngineStatus = "麦克风采集失败：${error.localizedMessage ?: "请检查系统麦克风输入"}"
                runOnUiThread {
                    speechStatus.text = speechEngineStatus
                }
            }
        }
    }

    private fun onSpeechEvent(event: SpeechEvent, generation: Long) {
        synchronized(speechEventLock) {
            if (!running || generation != sessionGeneration) return
            if (event.isFinal) {
                val overlappingIds = speakerAttributor.overlappingTracks(event)
                if (overlappingIds.size >= 2) {
                    transcriptPreview.text = "检测到 ${overlappingIds.size} 人嘴部同步 · 正在分离语音流…"
                    overlapExecutor.execute { separateLiveOverlap(event, generation, overlappingIds) }
                    return
                }
            }
            transcriptSession.accept(generation, event)
        }
    }

    private fun separateLiveOverlap(event: SpeechEvent, generation: Long, trackIds: Set<Int>) {
        val pcm = pcmTimeline.slice(event.startedAtMs, event.endedAtMs)
        if (pcm.size < AUDIO_SAMPLE_RATE) {
            transcriptSession.accept(generation, event, overlapSpeakerLabel(trackIds))
            return
        }
        try {
            val body = ByteBuffer.allocate(pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .apply { pcm.forEach(::putShort) }.array()
            val connection = URL(overlapUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 120_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.outputStream.use { it.write(body) }
            val response = JSONObject(String(connection.inputStream.use { it.readBytes() }, Charsets.UTF_8))
            connection.disconnect()
            val sources = response.getJSONArray("sources")
            val separated = (0 until sources.length()).map { index ->
                val payload = Base64.decode(sources.getJSONObject(index).getString("audio_base64"), Base64.DEFAULT)
                val pcmView = ByteBuffer.wrap(payload).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                ShortArray(pcmView.remaining()).also(pcmView::get)
            }
            val mapping = speakerAttributor.associateSeparatedSources(event, separated, trackIds)
            var delivered = 0
            for (index in 0 until sources.length()) {
                val source = sources.getJSONObject(index)
                val text = source.optString("text").trim()
                if (text.isBlank()) continue
                val sourceSamples = separated[index]
                val voiceIdentity = VoiceprintMatcher.match(VoiceprintExtractor.extract(sourceSamples), store.participants())
                val trackId = mapping[index]
                val speaker = voiceIdentity?.name ?: trackId?.let(tracker::labelForTrackId) ?: "陌生人 ${('A'.code + index).toChar()}"
                transcriptSession.accept(
                    generation,
                    SpeechEvent(text, event.startedAtMs, event.endedAtMs, true),
                    speaker
                )
                if (trackId != null) showActiveSpeakerIds(setOf(trackId))
                delivered++
            }
            if (delivered == 0) transcriptSession.accept(generation, event, overlapSpeakerLabel(trackIds))
            else runOnUiThread { if (generation == sessionGeneration) speechStatus.text = "双人语音已拆分并分别转写 · FunASR" }
        } catch (error: Exception) {
            transcriptSession.accept(generation, event, overlapSpeakerLabel(trackIds))
            runOnUiThread { if (generation == sessionGeneration) speechStatus.text = "重叠分离服务不可用 · ${error.localizedMessage ?: error.javaClass.simpleName}" }
        }
    }

    private fun overlapSpeakerLabel(trackIds: Set<Int>): String {
        val labels = trackIds.mapNotNull { id -> currentFaces.firstOrNull { it.id == id }?.label ?: tracker.labelForTrackId(id) }.distinct()
        return if (labels.isEmpty()) "多人同时说话" else "多人同时说话（${labels.joinToString("、")}）"
    }

    private fun resolveSpeaker(event: SpeechEvent): String? {
        val visibleFaces = currentFaces
        val visibleIds = visibleFaces.map { it.id }.toSet()
        val overlappingIds = speakerAttributor.overlappingTracks(event)
        if (overlappingIds.size > 1) {
            showActiveSpeakerIds(overlappingIds)
            val names = overlappingIds.mapNotNull { id -> visibleFaces.firstOrNull { it.id == id }?.label ?: tracker.labelForTrackId(id) }.distinct()
            return if (names.isEmpty()) "多人同时说话" else "多人同时说话（${names.joinToString("、")}）"
        }

        val trackId = speakerAttributor.attribute(event, visibleIds)
        val visualFace = visibleFaces.firstOrNull { it.id == trackId }
        val voiceSamples = if (event.hasReliableSourceTimestamp) {
            pcmTimeline.slice(event.startedAtMs, event.endedAtMs)
        } else ShortArray(0)
        val voiceMatch = VoiceprintMatcher.match(VoiceprintExtractor.extract(voiceSamples), store.participants())
        if (voiceMatch != null) {
            val matchingFace = visibleFaces.firstOrNull { it.label == voiceMatch.name }
            if (matchingFace != null) {
                showActiveSpeakerIds(setOf(matchingFace.id))
                return voiceMatch.name
            }
            if (visualFace != null) {
                showActiveSpeakerIds(setOf(visualFace.id))
                if (!visualFace.known) {
                    tracker.confirmVoiceIdentity(visualFace.id, voiceMatch.name)
                    val relabeled = visualFace.copy(label = voiceMatch.name, known = true)
                    currentFaces = visibleFaces.map { if (it.id == relabeled.id) relabeled else it }
                    runOnUiThread { overlay.submit(currentFaces, frameWidth, frameHeight) }
                    return voiceMatch.name
                }
                if (visualFace.label != voiceMatch.name) {
                    return "身份待复核（声纹${voiceMatch.name}／人脸${visualFace.label}）"
                }
            }
            showActiveSpeakerIds(emptySet())
            return if (visibleFaces.isEmpty()) "${voiceMatch.name}（人脸暂不可见）" else "${voiceMatch.name}（人脸未定位）"
        }
        if (visualFace != null) {
            showActiveSpeakerIds(setOf(visualFace.id))
            return visualFace.label
        }
        showActiveSpeakerIds(emptySet())
        return null
    }

    private fun showActiveSpeakerIds(ids: Set<Int>) {
        runOnUiThread { if (::overlay.isInitialized) overlay.setActiveSpeakerIds(ids) }
    }

    private fun refreshParticipants() {
        val participants = store.participants()
        val withVoice = participants.count { it.voiceEmbedding != null }
        peopleStatus.text = "身份库 ${participants.size} 人 · 声纹 $withVoice 人 · 本机保存"
    }

    private fun showParticipantManager() {
        if (running) {
            status.text = "请先停止采集，再修改身份库。"
            return
        }
        val participants = store.participants()
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("管理身份")
            .setView(ScrollView(this).apply { addView(rows) })
            .setPositiveButton("完成", null)
            .create()
        if (participants.isEmpty()) {
            rows.addView(label("身份库为空", 14f, 0xFF586561.toInt()).top(8))
        }
        participants.forEach { participant ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val voiceState = if (participant.voiceEmbedding == null) "未登记声纹" else "已登记声纹"
            row.addView(label("${participant.name} · $voiceState", 14f, 0xFF172321.toInt()), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply {
                text = "改名"
                isAllCaps = false
                setOnClickListener {
                    dialog.dismiss()
                    renameParticipant(participant.name)
                }
            })
            row.addView(Button(this).apply {
                text = "删除"
                isAllCaps = false
                setOnClickListener {
                    dialog.dismiss()
                    confirmDeleteParticipant(participant.name)
                }
            })
            rows.addView(row)
        }
        dialog.show()
    }

    private fun renameParticipant(oldName: String) {
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(oldName)
            setSelection(text.length)
            hint = "新姓名"
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("重命名身份")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newName = input.text.toString().trim()
                when {
                    newName.isBlank() -> status.text = "姓名不能为空。"
                    store.participants().any { it.name == newName && it.name != oldName } -> status.text = "这个姓名已存在。"
                    store.renameParticipant(oldName, newName) -> {
                        refreshParticipants()
                        status.text = "已将身份“$oldName”重命名为“$newName”。"
                        dialog.dismiss()
                    }
                    else -> status.text = "身份不存在，未能重命名。"
                }
            }
        }
        dialog.show()
    }

    private fun confirmDeleteParticipant(name: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("删除身份？")
            .setMessage("将删除“$name”的人脸特征；已保存的转写记录不会改变。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                if (store.deleteParticipant(name)) {
                    refreshParticipants()
                    status.text = "已删除身份“$name”。"
                } else {
                    status.text = "身份不存在，未能删除。"
                }
            }
            .show()
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
            item.setOnClickListener { editUtterance(row) }
            transcriptList.addView(item)
        }
    }

    private fun editUtterance(utterance: Utterance) {
        val input = EditText(this).apply {
            setText(utterance.text)
            setSelection(text.length)
            minLines = 2
            maxLines = 6
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("修正已保存转写")
            .setMessage("${utterance.speaker} · 修改后覆盖本机保存的文字")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存修改") { _, _ ->
                val corrected = input.text.toString().trim()
                if (corrected.isNotEmpty()) {
                    store.updateUtterance(utterance.id, corrected)
                    refreshTranscriptList()
                }
            }
            .show()
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
        val stoppedGeneration = synchronized(speechEventLock) {
            val stoppedGeneration = sessionGeneration
            running = false
            sessionGeneration++
            tracker.clear()
            currentFaces = emptyList()
            stoppedGeneration
        }
        audioCaptureRunning = false
        voiceEnrollment.cancel()
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
        val stoppedEngine = speechEngine
        speechEngine = null
        stoppedEngine?.stop()
        if (stoppedEngine is FunAsrSpeechEngine) {
            Thread {
                try { Thread.sleep(5_500L) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                transcriptSession.stop(stoppedGeneration)
                pcmTimeline.clear()
            }.apply { name = "transcript-final-drain"; isDaemon = true; start() }
        } else {
            transcriptSession.stop(stoppedGeneration)
            pcmTimeline.clear()
        }
        analysisUseCase?.clearAnalyzer()
        analysisUseCase = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        mouthExecutor.execute {
            mouthAnalyzer?.close()
            mouthAnalyzer = null
            speakerAttributor.clear()
        }
        overlay.submit(emptyList(), frameWidth, frameHeight)
        overlay.setActiveSpeakerIds(emptySet())
        overlay.selectedId = null
        selectedTrackId = null
        startButton.text = "开始采集"
        consent.isEnabled = true
        liveBadge.text = "● 已停止"
        liveBadge.setTextColor(0xFF586561.toInt())
        transcriptPreview.text = "采集已停止"
        speechEngineStatus = "识别已停止 · 历史文本保留在本机"
        speechStatus.text = speechEngineStatus
        status.text = "摄像头与麦克风已关闭。转写文本保存在本机。"
        refreshTranscriptList()
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
        speechEnglishModel?.close()
        speechEnglishModel = null
        cameraExecutor.shutdown()
        analysisExecutor.shutdown()
        mouthExecutor.shutdown()
        overlapExecutor.shutdown()
        store.close()
        super.onDestroy()
    }

    companion object {
        private const val FRAME_INTERVAL_MS = 450L
        private const val MOUTH_FRAME_INTERVAL_MS = 200L
        private const val AUDIO_SAMPLE_RATE = 16_000
        private const val AUDIO_BUFFER_BYTES = 6_400
        private const val AUDIO_CHUNK_SAMPLES = 1_024
        private const val AUDIO_METER_INTERVAL_MS = 500L
        private const val MIN_AUDIBLE_DBFS = -55
        private const val MIN_SPEECH_DBFS = -52.0
    }
}
