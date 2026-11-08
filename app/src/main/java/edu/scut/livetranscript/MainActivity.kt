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
import org.opencv.android.OpenCVLoader
import org.vosk.SpeakerModel
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
    private val mouthExecutor = Executors.newSingleThreadExecutor()
    private val processing = AtomicBoolean(false)
    private val mouthProcessing = AtomicBoolean(false)
    private val lastFrameAt = AtomicLong(0L)
    private val lastMouthFrameAt = AtomicLong(0L)
    private val tracker = FaceTracker()
    private val speakerAttributor = SpeakerAttributor()
    private val store by lazy { LocalStore(this) }
    private val transcriptSession by lazy {
        TranscriptSession(
            resolveSpeaker = ::resolveSpeaker,
            onPreview = { generation, line ->
                runOnUiThread {
                    if (running && generation == sessionGeneration) transcriptPreview.text = "${line.speaker}：${line.text}"
                }
            },
            onFinal = { generation, line ->
                store.addUtterance(line.speaker, line.text)
                runOnUiThread {
                    if (generation == sessionGeneration) {
                        refreshTranscriptList()
                        if (running) {
                            transcriptPreview.text = "${line.speaker}：${line.text}"
                            speechStatus.text = "已保存到本机 · $speechEngineStatus"
                        }
                    }
                }
            }
        )
    }
    private val pipeline by lazy { FacePipeline(this) }
    private val mec by lazy { MecOffload(this, pipeline) }
    @Volatile private var mecMode = "本地"
    @Volatile private var mecUrl = "http://10.0.2.2:8000"
    @Volatile private var mecSize = 640
    @Volatile private var mecLimit = 0
    @Volatile private var mecReset = false
    @Volatile private var mouthAnalyzer: MouthActivityAnalyzer? = null
    @Volatile private var mouthAnalysisUnavailable = false
    @Volatile private var running = false
    @Volatile private var sessionGeneration = 0L
    private val speechEventLock = Any()
    @Volatile private var currentFaces: List<TrackedFace> = emptyList()
    @Volatile private var selectedTrackId: Int? = null
    @Volatile private var frameWidth = 640
    @Volatile private var frameHeight = 480
    @Volatile private var speechEngineStatus = "识别服务尚未启动"
    private var speechModel: Model? = null
    private var speakerModel: SpeakerModel? = null
    private var selectedSpeechMode = SpeechMode.REMOTE
    private var activeSpeechMode = SpeechMode.REMOTE
    private lateinit var voiceprintEnabled: CheckBox
    @Volatile private var voiceprintActive = false
    @Volatile private var enrollVoiceprintName: String? = null
    private var importKind = "english"
    private val importModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !running && !modelLoading) {
            modelLoading = true
            val kind = getPreferences(MODE_PRIVATE).getString("modelImportKind", importKind) ?: importKind
            Thread {
                val result = runCatching {
                    contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input)
                        ModelArchive.install(input, java.io.File(filesDir, "imported-models"), kind)
                    }
                }
                runOnUiThread {
                    modelLoading = false
                    speechStatus.text = result.fold({ "模型导入成功，下次开始采集时生效" }, { "模型导入失败：${it.message}" })
                }
            }.start()
        }
    }
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
    private val exportMetrics = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null && mec.logFile.exists()) contentResolver.openOutputStream(uri)?.use { output -> mec.logFile.inputStream().use { it.copyTo(output) } }
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
        selectedSpeechMode = SpeechMode.entries.firstOrNull { it.name == getPreferences(MODE_PRIVATE).getString("speechMode", null) } ?: SpeechMode.REMOTE
        buildScreen()
        refreshParticipants()
        refreshTranscriptList()
        if (BuildConfig.DEBUG && intent.getBooleanExtra("mecBenchmark", false)) runSampleBenchmark()
    }

    private fun buildScreen() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18))
            setBackgroundColor(0xFFF4F6F5.toInt())
        }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = true; isScrollbarFadingEnabled = false; addView(page) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        setContentView(scroll)
        ViewCompat.requestApplyInsets(scroll)

        page.addView(label("LIVE SESSION", 12f, 0xFF176B62.toInt(), true))
        page.addView(label("人脸与语音记录", 25f, 0xFF172321.toInt(), true).top(4))
        page.addView(label("实时音频通过已配置服务识别 · 不保存音频 · 转写文本仅保存在本机", 13f, 0xFF586561.toInt()).top(3))

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

        page.addView(label("计算切分实验", 16f, 0xFF172321.toInt(), true).top(12))
        fun options(items: List<String>, selected: (String) -> Unit): Spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { selected(items[position]); mecReset = true }
            }
        }
        page.addView(options(listOf("本地", "对齐后卸载", "整帧卸载", "动态")) { mecMode = it })
        page.addView(EditText(this).apply {
            setSingleLine(true); textSize = 14f; hint = "电脑服务地址"
            setText(mecUrl)
            doAfterTextChanged { mecUrl = it.toString().trim().trimEnd('/'); mecReset = true }
        })
        page.addView(label("输入图像最大边长", 12f, 0xFF586561.toInt()))
        page.addView(options(listOf("640", "480", "320")) { mecSize = it.toInt() })
        page.addView(label("上行限速（实验模拟）", 12f, 0xFF586561.toInt()))
        page.addView(options(listOf("不限速", "512 kbps", "2000 kbps", "10000 kbps")) { mecLimit = it.substringBefore(' ').toIntOrNull() ?: 0 })
        mecStatus = label("等待采样", 12f, 0xFF586561.toInt())
        page.addView(mecStatus)
        page.addView(Button(this).apply { text = "导出实验 CSV"; isAllCaps = false; setOnClickListener { exportMetrics.launch("mec-experiments.csv") } })
        page.addView(Button(this).apply { text = "运行样例切分实验"; isAllCaps = false; setOnClickListener { runSampleBenchmark() } })

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
        speechContent.addView(label("语音位置（下次开始采集生效）", 12f, 0xFF586561.toInt()))
        speechContent.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, SpeechMode.entries.map { it.title })
            setSelection(selectedSpeechMode.ordinal)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { selectedSpeechMode = SpeechMode.entries[position]; getPreferences(MODE_PRIVATE).edit().putString("speechMode", selectedSpeechMode.name).apply() }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        })
        listOf("english" to "导入英文 Vosk 模型 ZIP", "speaker" to "导入 Vosk 声纹模型 ZIP").forEach { (kind, title) ->
            speechContent.addView(Button(this).apply {
                text = title
                setOnClickListener {
                    if (running || modelLoading) speechStatus.text = "请停止采集并等待模型加载结束后导入"
                    else { importKind = kind; getPreferences(MODE_PRIVATE).edit().putString("modelImportKind", kind).apply(); importModel.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) }
                }
            })
        }
        voiceprintEnabled = CheckBox(this).apply { text = "实验声纹辅助（仅本地语音，需导入声纹模型）" }
        speechContent.addView(voiceprintEnabled)
        speechContent.addView(Button(this).apply {
            text = "为选中身份录入下一句声纹"
            setOnClickListener {
                val name = currentFaces.firstOrNull { it.id == selectedTrackId }?.label
                if (!running || !audioCaptureRunning || !voiceprintActive || name == null || store.participants().none { it.name == name }) {
                    speechStatus.text = "请开启本地声纹辅助，开始采集并点选已登记的人脸"
                } else androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("录入 $name 的声纹？")
                    .setMessage("确认本人同意保存声纹后，请仅由本人清晰说一句至少数秒的话。下一条有效最终结果将覆盖该身份声纹，仅存本机。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("本人同意并录入") { _, _ -> enrollVoiceprintName = name; speechStatus.text = "等待 $name 的下一句有效语音" }.show()
            }
        })
        speechContent.addView(Button(this).apply {
            text = "清除全部声纹"
            setOnClickListener { androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setMessage("清除本机全部声纹？人脸身份和字幕保留。")
                .setNegativeButton("取消", null).setPositiveButton("清除") { _, _ -> synchronized(speechEventLock) { enrollVoiceprintName = null; store.clearVoiceprints() } }.show() }
        })
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
        if (modelLoading) { status.text = "请等待模型加载或导入结束"; return }
        activeSpeechMode = selectedSpeechMode
        voiceprintActive = voiceprintEnabled.isChecked && activeSpeechMode != SpeechMode.REMOTE
        enrollVoiceprintName = null
        pendingStart = false
        lastFrameAt.set(0L)
        lastMouthFrameAt.set(0L)
        mouthAnalysisUnavailable = false
        speakerAttributor.clear()
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
                val selector = if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
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
        status.text = "已在本机登记 $name。该参与者需已同意进行识别。"
    }

    private fun startSpeechRecognition() {
        val generation = sessionGeneration
        val mode = activeSpeechMode
        modelLoading = true
        speechEngineStatus = "正在加载${mode.title}模型…"
        speechStatus.text = speechEngineStatus
        fun loaded(model: Model) {
            modelLoading = false
            if (!running || generation != sessionGeneration || isDestroyed) { model.close(); return }
            speechModel?.close()
            speechModel = model
            try {
                speakerModel?.close()
                speakerModel = null
                if (voiceprintActive) {
                    val dir = java.io.File(filesDir, "imported-models/speaker")
                    check(dir.isDirectory) { "请先导入 Vosk 声纹模型 ZIP" }
                    speakerModel = SpeakerModel(dir.absolutePath)
                }
                val local = VoskSpeechEngine(model, speakerModel)
                speechEngineStatus = if (mode == SpeechMode.REMOTE) "电脑端中英（本地中文后备）" else mode.title
                startFunAsrRecognition(local, generation)
            } catch (error: Exception) { speechStatus.text = "模型启动失败：${error.message}" }
        }
        fun failed(error: Exception) {
            modelLoading = false
            if (!running || generation != sessionGeneration || isDestroyed) return
            speechEngineStatus = "模型加载失败：${error.message}"
            speechStatus.text = speechEngineStatus
            if (mode == SpeechMode.REMOTE) startFunAsrRecognition(null, generation)
        }
        if (mode == SpeechMode.LOCAL_ENGLISH) {
            Thread {
                try {
                    val dir = java.io.File(filesDir, "imported-models/english")
                    check(dir.isDirectory) { "请先导入 vosk-model-small-en-us-0.15.zip" }
                    val model = Model(dir.absolutePath)
                    runOnUiThread { loaded(model) }
                } catch (error: Exception) { runOnUiThread { failed(error) } }
            }.start()
        } else StorageService.unpack(this, "models/vosk-model-small-cn-0.22", "vosk", { loaded(it) }, { failed(it) })
    }

    private fun startFunAsrRecognition(localFallback: SpeechEngine?, generation: Long) {
        if (!running || generation != sessionGeneration || audioCaptureRunning) return
        try {
            val engine = if (activeSpeechMode != SpeechMode.REMOTE) requireNotNull(localFallback) else FunAsrSpeechEngine(
                endpoint = BuildConfig.FUNASR_WS_URL,
                fallback = localFallback,
                onStatus = { message ->
                    if (running && generation == sessionGeneration) {
                        speechEngineStatus = message
                        runOnUiThread { if (running && generation == sessionGeneration) speechStatus.text = message }
                    }
                }
            )
            speechEngine = engine
            engine.start { event -> onSpeechEvent(event, generation) }
            val minBuffer = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            check(minBuffer > 0) { "Android 无法创建 16 kHz 麦克风流（$minBuffer）" }
            check(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "需要麦克风权限" }
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
                if (now - lastMeterUpdate >= AUDIO_METER_INTERVAL_MS) {
                    lastMeterUpdate = now
                    val level = levelDb.roundToInt()
                    runOnUiThread {
                        speechStatus.text = if (level < MIN_AUDIBLE_DBFS) {
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
            transcriptSession.accept(generation, event)
        }
    }

    private fun resolveSpeaker(event: SpeechEvent): String? {
        val visibleFaces = currentFaces
        val visualId = speakerAttributor.attribute(event, visibleFaces.map { it.id }.toSet())
        val visualName = visualId?.let { id -> visibleFaces.firstOrNull { it.id == id }?.label }
        val vector = event.speakerVector?.toFloatArray()
        if (voiceprintActive && event.isFinal && enrollVoiceprintName != null &&
            (event.speakerFrames < 300 || vector == null || !VoiceprintMatcher.valid(vector))) {
            speechEngineStatus = "声纹样本不足，请仅由录入者继续清晰说数秒并停顿"
        }
        if (voiceprintActive && event.isFinal && event.speakerFrames >= 300 && vector != null && VoiceprintMatcher.valid(vector)) {
            val enrollment = enrollVoiceprintName
            if (enrollment != null) {
                enrollVoiceprintName = null
                if (store.participants().any { it.name == enrollment }) {
                    store.saveVoiceprint(enrollment, vector)
                    speechEngineStatus = "已在本机录入 $enrollment 的声纹 · ${activeSpeechMode.title}"
                    return enrollment
                }
            }
            val matched = VoiceprintMatcher.match(vector, store.voiceprints())
            if (matched != null) return if (visualName == null || visualName == matched) matched else null
        }
        return visualName
    }

    private fun refreshParticipants() {
        peopleStatus.text = "身份库 ${store.participants().size} 人 · 本机保存"
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
            row.addView(label(participant.name, 14f, 0xFF172321.toInt()), LinearLayout.LayoutParams(0, -2, 1f))
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
            item.contentDescription = "编辑 ${row.speaker} 的字幕：${row.text}"
            item.setOnClickListener {
                val input = EditText(this).apply { setText(row.text); minLines = 2 }
                val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("修改已保存字幕").setView(input)
                    .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
                dialog.setOnShowListener {
                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (input.text.isNullOrBlank()) input.error = "字幕不能为空"
                        else if (store.updateUtterance(row.id, input.text.toString())) { refreshTranscriptList(); dialog.dismiss() }
                        else input.error = "记录已被删除，请取消并刷新"
                    }
                }
                dialog.show()
            }
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
        enrollVoiceprintName = null
        synchronized(speechEventLock) {
            val stoppedGeneration = sessionGeneration
            running = false
            sessionGeneration++
            transcriptSession.stop(stoppedGeneration)
            tracker.clear()
            currentFaces = emptyList()
        }
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
        mouthExecutor.execute {
            mouthAnalyzer?.close()
            mouthAnalyzer = null
            speakerAttributor.clear()
        }
        overlay.submit(emptyList(), frameWidth, frameHeight)
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
        speakerModel?.close()
        speakerModel = null
        cameraExecutor.shutdown()
        analysisExecutor.shutdown()
        mouthExecutor.shutdown()
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
