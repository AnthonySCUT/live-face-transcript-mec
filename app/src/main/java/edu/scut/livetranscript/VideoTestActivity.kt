package edu.scut.livetranscript

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.vosk.Model
import org.vosk.android.StorageService
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** File replay has its own lifetime and result directory; it never opens camera/microphone. */
class VideoTestActivity : AppCompatActivity() {
    @Volatile private var cancelled = false
    @Volatile private var active = false
    @Volatile private var acceptingEvents = false
    @Volatile private var visibleFaces: List<TrackedFace> = emptyList()
    private lateinit var state: TextView
    private lateinit var transcript: TextView
    private lateinit var image: ImageView
    private lateinit var export: Button
    private var shownBitmap: Bitmap? = null
    private var worker: Thread? = null
    private lateinit var resultDir: File
    private val outputLock = Any()
    private val exportResults = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null && !active) {
            runCatching {
                contentResolver.openOutputStream(uri).use { output ->
                    ZipOutputStream(requireNotNull(output)).use { zip ->
                        resultDir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.forEach { file ->
                            zip.putNextEntry(ZipEntry(file.name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                        }
                    }
                }
            }.fold({ state.text = "结果已导出" }, { state.text = "导出失败：${it.message}" })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        val scroll = ScrollView(this).apply { addView(page) }
        setContentView(scroll)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(scroll)
        page.addView(TextView(this).apply { text = "视频文件测试（不启用相机和麦克风）"; textSize = 20f })
        state = TextView(this).also { page.addView(it) }
        image = ImageView(this).apply { adjustViewBounds = true; minimumHeight = 300 }
        page.addView(image)
        transcript = TextView(this).also { page.addView(it) }
        page.addView(Button(this).apply {
            text = "停止测试"
            setOnClickListener { cancelled = true; worker?.interrupt(); state.text = "正在停止并保存已有结果…" }
        })
        export = Button(this).apply {
            text = "导出本次结果 ZIP"; isEnabled = false
            setOnClickListener { exportResults.launch("${resultDir.name}.zip") }
        }.also { page.addView(it) }
        page.addView(TextView(this).apply { text = "按视频时间以约 1 倍速输入；处理慢时会延长总时间，不丢弃采样帧。每 200ms 采样一帧。文件解码耗时也计入本次总耗时。声纹辅助在文件测试中关闭。" })
        resultDir = File(filesDir, "video-tests/${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().take(8)}")
        check(resultDir.mkdirs())
        if (!org.opencv.android.OpenCVLoader.initLocal()) { state.text = "OpenCV 初始化失败"; return }
        active = true
        worker = Thread(::runTest, "video-file-test").also { it.start() }
    }

    private fun runTest() {
        val uri = intent.data
        var model: Model? = null
        var engine: SpeechEngine? = null
        var mouth: MouthActivityAnalyzer? = null
        val retriever = MediaMetadataRetriever()
        val store = LocalStore(this)
        val attributor = SpeakerAttributor()
        val tracker = FaceTracker()
        val mode = SpeechMode.entries.firstOrNull { it.name == intent.getStringExtra("speechMode") } ?: SpeechMode.LOCAL_CHINESE
        val requested = intent.getStringExtra("mecMode") ?: "本地"
        val endpoint = intent.getStringExtra("mecUrl") ?: "http://10.0.2.2:8000"
        val size = intent.getIntExtra("mecSize", 640).coerceIn(160, 1920)
        val limit = intent.getIntExtra("mecLimit", 0)
        val summary = File(resultDir, "summary.txt")
        val frames = File(resultDir, "frames.csv")
        val captions = File(resultDir, "transcripts.csv")
        var outcome = "未完成"
        var count = 0
        var origin = 0L
        val started = SystemClock.elapsedRealtime()
        try {
            requireNotNull(uri) { "未选择视频" }
            val filename = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "unknown"
            summary.writeText("source=$filename\nspeech=${mode.name}\nface=$requested\nface_endpoint=$endpoint\ninput_max_edge=$size\nface_upload_limit_kbps=$limit\nframe_interval_ms=200\npacing=media time, minimum 1x wall time, no sampled-frame dropping\nvoiceprints=disabled\n")
            frames.writeText("media_ms,wall_elapsed_ms,partition,width,height,pipeline_ms,frame_processing_ms,upload_bytes,download_bytes,face_count\n")
            captions.writeText("started_media_ms,ended_media_ms,received_wall_ms,final,speaker,text\n")
            retriever.setDataSource(this, uri)
            check(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes") { "文件没有视频画面" }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: error("无法读取视频时长")
            require(duration > 0) { "视频时长必须大于零" }
            ui { state.text = "正在加载 ${mode.title}，视频时长 ${duration / 1000} 秒" }
            model = Model(if (mode == SpeechMode.LOCAL_ENGLISH) {
                File(filesDir, "imported-models/english").also { check(it.isDirectory) { "请返回主界面导入英文模型 ZIP" } }.absolutePath
            } else StorageService.sync(this, "models/vosk-model-small-cn-0.22", "vosk"))
            if (cancelled) throw InterruptedException()
            val local = VoskSpeechEngine(model)
            val selected = if (mode == SpeechMode.REMOTE) FunAsrSpeechEngine(BuildConfig.FUNASR_WS_URL, fallback = local,
                onStatus = { message ->
                    synchronized(outputLock) { if (acceptingEvents) summary.appendText("speech_status=$message\n") }
                    ui { state.text = message }
                }) else local
            engine = selected
            mouth = MouthActivityAnalyzer(this, attributor)
            val analyzer = mouth
            val mec = MecOffload(this, FacePipeline(this))
            origin = SystemClock.elapsedRealtime()
            val base = origin
            acceptingEvents = true
            selected.start { event ->
                if (!cancelled && acceptingEvents) {
                    val faces = visibleFaces
                    val id = attributor.attribute(event, faces.map { it.id }.toSet())
                    val speaker = faces.firstOrNull { it.id == id }?.label ?: "未确认说话人"
                    synchronized(outputLock) {
                        if (acceptingEvents) captions.appendText(csv(event.startedAtMs - base, event.endedAtMs - base,
                            SystemClock.elapsedRealtime() - base, event.isFinal, speaker, event.text))
                    }
                    ui { transcript.text = "${if (event.isFinal) "最终" else "临时"} · $speaker：${event.text}" }
                }
            }
            var nextFrameMs = 0L
            fun processFramesThrough(endMs: Long) {
                while (nextFrameMs <= endMs && nextFrameMs < duration && !cancelled) {
                    val frameTime = nextFrameMs
                    val remaining = base + frameTime - SystemClock.elapsedRealtime()
                    if (remaining > 0) Thread.sleep(remaining)
                    val tick = SystemClock.elapsedRealtimeNanos()
                    val bitmap = readFrame(retriever, frameTime, size)
                        ?: error("无法解码 ${frameTime}ms 视频帧")
                    try {
                        val metrics = try { mec.run(bitmap, requested, endpoint, limit) }
                        catch (error: java.io.IOException) {
                            if (requested != "动态") throw error
                            mec.run(bitmap, "本地", endpoint, limit)
                        }
                        val tracked = tracker.update(metrics.samples, store.participants(), base + frameTime)
                        visibleFaces = tracked
                        analyzer.process(bitmap, tracked, base + frameTime)
                        count++
                        val frameMs = (SystemClock.elapsedRealtimeNanos() - tick) / 1_000_000.0
                        frames.appendText(csv(frameTime, SystemClock.elapsedRealtime() - base, metrics.mode,
                            bitmap.width, bitmap.height, metrics.totalMs, frameMs, metrics.sent, metrics.received, tracked.size))
                        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                        runOnUiThread {
                            if (!cancelled && !isDestroyed && copy != null) {
                                image.setImageBitmap(copy); shownBitmap?.recycle(); shownBitmap = copy
                                state.text = "视频 ${frameTime / 1000}/${duration / 1000}s · ${metrics.mode} · ${tracked.joinToString { it.label }}"
                            } else copy?.recycle()
                        }
                    } finally { bitmap.recycle() }
                    nextFrameMs += 200
                }
            }
            VideoAudioDecoder.decode(this, uri, { cancelled }) { pcm, audioEndMs ->
                processFramesThrough(audioEndMs)
                val wait = base + audioEndMs - SystemClock.elapsedRealtime()
                if (wait > 0) Thread.sleep(wait)
                if (!cancelled) selected.acceptPcm(pcm, pcm.size, base + audioEndMs)
            }
            if (cancelled) throw InterruptedException()
            selected.finishInput()
            if (mode == SpeechMode.REMOTE) Thread.sleep(1000) // Existing engine drains remote finals for 750ms.
            processFramesThrough(duration - 1)
            if (cancelled) throw InterruptedException()
            outcome = if (mode == SpeechMode.REMOTE) "完成（远端最终结果以已收到记录为准）" else "完成"
        } catch (_: InterruptedException) {
            outcome = "已取消，已有结果保留"
        } catch (error: Exception) {
            outcome = "失败：${error.message}"
        } finally {
            synchronized(outputLock) { acceptingEvents = false }
            runCatching { engine?.stop() }
            runCatching { mouth?.close() }
            runCatching { model?.close() }
            runCatching { retriever.release() }
            runCatching { store.close() }
            synchronized(outputLock) {
                acceptingEvents = false
                summary.appendText("outcome=$outcome\nframes=$count\nwall_total_ms=${SystemClock.elapsedRealtime() - started}\nprocessing_origin_elapsed_ms=$origin\n")
            }
            active = false
            ui { state.text = "$outcome · 已处理 $count 帧"; export.isEnabled = true }
        }
    }

    private fun readFrame(retriever: MediaMetadataRetriever, mediaMs: Long, size: Int): Bitmap? {
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            return retriever.getScaledFrameAtTime(mediaMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, size, size)
        }
        val original = retriever.getFrameAtTime(mediaMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
        val scale = minOf(1f, size.toFloat() / maxOf(original.width, original.height))
        if (scale == 1f) return original
        return try {
            Bitmap.createScaledBitmap(original, maxOf(1, (original.width * scale).toInt()), maxOf(1, (original.height * scale).toInt()), true)
        } finally { original.recycle() }
    }

    private fun ui(block: () -> Unit) = runOnUiThread { if (!isDestroyed && !isFinishing) block() }

    override fun onPause() {
        super.onPause()
        if (active) { cancelled = true; worker?.interrupt() }
    }

    override fun onDestroy() {
        cancelled = true
        worker?.interrupt()
        image.setImageDrawable(null)
        shownBitmap?.recycle()
        super.onDestroy()
    }

    companion object {
        fun csv(vararg values: Any?): String = values.joinToString(",") {
            "\"${it.toString().replace("\"", "\"\"") }\""
        } + "\n"
    }
}
