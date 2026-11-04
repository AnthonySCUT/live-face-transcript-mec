package edu.scut.livetranscript

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.BatteryManager
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

enum class Partition { LOCAL, ALIGNED, REMOTE }
data class NetworkProbe(val upBytesPerMs: Double, val downBytesPerMs: Double, val rttMs: Double)
data class Profile(val localMs: Double, val remoteMs: Double, val sent: Int, val received: Int, val cpuLoad: Double)

class PartitionScheduler {
    private val profiles = mutableMapOf<Partition, Profile>()
    private var selected = Partition.LOCAL
    private var frames = 0
    fun choose(probe: NetworkProbe, cpuLoad: Double): Partition {
        frames++
        Partition.entries.firstOrNull { it !in profiles }?.let { return it }
        if (frames % 30 == 0) return Partition.entries[(frames / 30) % Partition.entries.size]
        fun cost(mode: Partition): Double {
            val p = profiles.getValue(mode)
            val loadFactor = ((0.5 + cpuLoad) / (0.5 + p.cpuLoad)).coerceIn(0.5, 2.0)
            return p.localMs * loadFactor + p.remoteMs +
                (if (mode == Partition.LOCAL) 0.0 else probe.rttMs + p.sent / max(1.0, probe.upBytesPerMs) + p.received / max(1.0, probe.downBytesPerMs))
        }
        val best = Partition.entries.minBy { cost(it) }
        if (cost(best) < cost(selected) * 0.85) selected = best
        return selected
    }
    fun observe(mode: Partition, profile: Profile) {
        val previous = profiles[mode]
        profiles[mode] = if (previous == null) profile else Profile(
            previous.localMs * 0.7 + profile.localMs * 0.3,
            previous.remoteMs * 0.7 + profile.remoteMs * 0.3,
            (previous.sent * 0.7 + profile.sent * 0.3).toInt(),
            (previous.received * 0.7 + profile.received * 0.3).toInt(),
            previous.cpuLoad * 0.7 + profile.cpuLoad * 0.3
        )
    }
    fun clear() { profiles.clear(); selected = Partition.LOCAL; frames = 0 }
}

data class MecResult(val samples: List<FaceSample>, val mode: Partition, val totalMs: Double, val detectMs: Double,
    val alignMs: Double, val featureMs: Double, val remoteMs: Double, val networkMs: Double, val sent: Int, val received: Int, val cpuLoad: Double)

class MecOffload(private val context: Context, private val pipeline: FacePipeline) {
    private val scheduler = PartitionScheduler()
    private var lastProbeAt = 0L
    private var cpuAt = Process.getElapsedCpuTime()
    private var wallAt = SystemClock.elapsedRealtime()
    private var cpuLoad = 0.0
    var probe = NetworkProbe(0.0, 0.0, 0.0); private set
    val logFile = File(context.filesDir, "mec-experiments.csv")

    fun reset() { scheduler.clear(); lastProbeAt = 0L; probe = NetworkProbe(0.0, 0.0, 0.0) }
    fun run(bitmap: Bitmap, requested: String, endpoint: String, limitKbps: Int): MecResult {
        val wall = SystemClock.elapsedRealtime()
        val cpu = Process.getElapsedCpuTime()
        cpuLoad = ((cpu - cpuAt).toDouble() / max(1L, wall - wallAt)).coerceIn(0.0, 4.0)
        cpuAt = cpu; wallAt = wall
        if (requested == "动态" && wall - lastProbeAt > 15_000L) {
            probe = measureNetwork(endpoint, limitKbps)
            lastProbeAt = SystemClock.elapsedRealtime()
        }
        val mode = when (requested) {
            "本地" -> Partition.LOCAL
            "对齐后卸载" -> Partition.ALIGNED
            "整帧卸载" -> Partition.REMOTE
            else -> scheduler.choose(probe, cpuLoad)
        }
        val start = SystemClock.elapsedRealtimeNanos()
        var detect = 0.0; var align = 0.0; var feature = 0.0; var remote = 0.0; var network = 0.0
        var sent = 0; var received = 0
        val samples = if (mode == Partition.LOCAL) {
            val local = pipeline.process(bitmap, false)
            detect = local.detectMs; align = local.alignMs; feature = local.featureMs
            local.samples
        } else {
            val body: ByteArray
            val path: String
            if (mode == Partition.ALIGNED) {
                val local = pipeline.process(bitmap, true)
                detect = local.detectMs; align = local.alignMs; feature = local.featureMs
                val faces = JSONArray()
                local.aligned.forEach { face -> faces.put(JSONObject().put("bounds", JSONArray(listOf(face.bounds.left, face.bounds.top, face.bounds.right, face.bounds.bottom))).put("image", face.image)) }
                body = JSONObject().put("faces", faces).toString().toByteArray()
                path = "/aligned"
            } else {
                val output = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
                body = output.toByteArray(); path = "/frame"
            }
            val transferStart = SystemClock.elapsedRealtimeNanos()
            val response = post(endpoint + path, body, limitKbps)
            network = elapsed(transferStart)
            sent = body.size; received = response.size
            val json = JSONObject(String(response))
            remote = json.getDouble("detect_ms") + json.getDouble("align_ms") + json.getDouble("feature_ms")
            val faces = json.getJSONArray("faces")
            (0 until faces.length()).map { i ->
                val face = faces.getJSONObject(i); val b = face.getJSONArray("bounds"); val e = face.getJSONArray("embedding")
                FaceSample(RectF(b.getDouble(0).toFloat(), b.getDouble(1).toFloat(), b.getDouble(2).toFloat(), b.getDouble(3).toFloat()), FloatArray(e.length()) { e.getDouble(it).toFloat() })
            }
        }
        val total = elapsed(start)
        val result = MecResult(samples, mode, total, detect, align, feature, remote, network, sent, received, cpuLoad)
        scheduler.observe(mode, Profile(max(0.0, total - network - remote), remote, sent, received, cpuLoad))
        return result
    }

    private fun measureNetwork(endpoint: String, limitKbps: Int): NetworkProbe {
        val pingStart = SystemClock.elapsedRealtimeNanos()
        val ping = URL(endpoint + "/health").openConnection() as HttpURLConnection
        ping.connectTimeout = 2000; ping.readTimeout = 3000
        ping.inputStream.use { it.readBytes() }; ping.disconnect()
        val rtt = elapsed(pingStart)
        val start = SystemClock.elapsedRealtimeNanos()
        val conn = URL(endpoint + "/probe").openConnection() as HttpURLConnection
        conn.connectTimeout = 2000; conn.readTimeout = 3000
        val bytes = conn.inputStream.use { it.readBytes() }; conn.disconnect()
        val downMs = max(1.0, elapsed(start))
        val upStart = SystemClock.elapsedRealtimeNanos()
        post(endpoint + "/probe", ByteArray(65536), limitKbps)
        val upMs = max(1.0, elapsed(upStart))
        return NetworkProbe(65536.0 / max(1.0, upMs - rtt), bytes.size / max(1.0, downMs - rtt), rtt)
    }

    private fun post(url: String, body: ByteArray, limitKbps: Int): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000; conn.readTimeout = 10000; conn.requestMethod = "POST"; conn.doOutput = true
        conn.setFixedLengthStreamingMode(body.size)
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        val start = SystemClock.elapsedRealtime()
        conn.outputStream.use { stream ->
            var offset = 0
            while (offset < body.size) {
                val count = minOf(4096, body.size - offset); stream.write(body, offset, count); offset += count
                if (limitKbps > 0) {
                    val target = offset * 8L / limitKbps
                    val wait = target - (SystemClock.elapsedRealtime() - start)
                    if (wait > 0) Thread.sleep(wait)
                }
            }
        }
        return conn.inputStream.use { it.readBytes() }.also { conn.disconnect() }
    }
    private fun elapsed(start: Long) = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
    fun log(r: MecResult, requested: String, width: Int, height: Int, limit: Int, frameMs: Double, trackerMs: Double) {
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val voltage = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        if (!logFile.exists()) logFile.appendText("timestamp_ms,requested,partition,width,height,bandwidth_limit_kbps,frame_to_track_ms,pipeline_ms,tracker_ms,detect_ms,align_ms,feature_or_encode_ms,remote_ms,request_ms,upload_bytes,download_bytes,probe_up_bytes_ms,probe_down_bytes_ms,probe_rtt_ms,process_cpu_cores,battery_current_ua,battery_charge_uah,battery_voltage_mv,battery_energy_nwh\n")
        logFile.appendText("${System.currentTimeMillis()},$requested,${r.mode},$width,$height,$limit,$frameMs,${r.totalMs},$trackerMs,${r.detectMs},${r.alignMs},${r.featureMs},${r.remoteMs},${r.networkMs},${r.sent},${r.received},${probe.upBytesPerMs},${probe.downBytesPerMs},${probe.rttMs},${r.cpuLoad},${battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)},${battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)},$voltage,${battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)}\n")
    }
}
