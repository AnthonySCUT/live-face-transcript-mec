package edu.scut.livetranscript

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File

class ReplayExperimentLog(private val context: Context) {
    val file = File(context.filesDir, "reproducible-experiments.csv")
    val edgeFile = File(context.filesDir, "reproducible-edge-diagnostics.csv")

    @Synchronized
    fun edge(inputFile: String, scenario: String, frameAtMs: Long, detectedFaces: Int,
             trackLabels: List<String>, mouthMatchedTracks: Int, mouthSamples: List<MouthSample>, overlapIds: Set<Int>) {
        if (!edgeFile.exists()) edgeFile.writeText(
            "timestamp_ms,scenario,input_file,input_offset_ms,detected_faces,track_labels,mouth_matched_tracks,mouth_samples,overlap_track_ids\n"
        )
        val sampleSummary = mouthSamples.groupBy { it.trackId }.toSortedMap().entries.joinToString("|") { (trackId, values) ->
            "$trackId:${values.size}:${"%.4f".format(java.util.Locale.US, values.maxOf { it.activity })}"
        }
        val values = listOf(
            System.currentTimeMillis(), scenario, inputFile, frameAtMs, detectedFaces,
            trackLabels.joinToString("|"), mouthMatchedTracks, sampleSummary, overlapIds.sorted().joinToString("|")
        )
        edgeFile.appendText(values.joinToString(",") { csv(it.toString()) } + "\n")
    }

    @Synchronized
    fun face(inputFile: String, scenario: String, frameAtMs: Long, width: Int, height: Int, limitKbps: Int,
             requested: String, result: MecResult, trackLabels: List<String>, overlapIds: Set<Int> = emptySet()) {
        ensureHeader()
        append(
            listOf(
                System.currentTimeMillis(), "face", scenario, inputFile, frameAtMs,
                "", width, height, requested, result.mode.name, limitKbps, result.totalMs,
                result.detectMs, result.alignMs, result.featureMs, result.remoteMs, result.networkMs,
                result.sent, result.received, result.probe.upBytesPerMs, result.probe.downBytesPerMs,
                result.probe.rttMs, result.cpuLoad, result.samples.size, trackLabels.joinToString("|"), overlapIds.joinToString("|"),
                "", "", "", "ok"
            )
        )
    }

    @Synchronized
    fun speech(scenario: String, inputFile: String, mode: String, wavBytes: Int, durationMs: Long, wallMs: Double,
               firstResultMs: Double?, uploadedBytes: Long, downloadedBytes: Long, text: String, status: String) {
        ensureHeader()
        append(
            listOf(
                System.currentTimeMillis(), "speech", scenario, inputFile, 0,
                wavBytes, "", "", mode, mode, "", wallMs, "", "", "", "", "", uploadedBytes,
                downloadedBytes, "", "", "", "", "", "", "", firstResultMs ?: "", durationMs, text, status
            )
        )
    }

    @Synchronized
    fun faceFailure(inputFile: String, scenario: String, frameAtMs: Long, width: Int, height: Int,
                    limitKbps: Int, requested: String, error: String) {
        ensureHeader()
        append(
            listOf(
                System.currentTimeMillis(), "face", scenario, inputFile, frameAtMs,
                "", width, height, requested, "", limitKbps, "", "", "", "", "", "",
                "", "", "", "", "", "", 0, "", "", "", error, "error"
            )
        )
    }

    private fun ensureHeader() {
        if (!file.exists()) {
            file.writeText(
                "timestamp_ms,task,scenario,input_file,input_offset_ms,input_bytes,width,height,requested_mode,selected_mode," +
                    "bandwidth_limit_kbps,total_ms,detect_ms,align_ms,feature_ms,remote_compute_ms,transfer_ms," +
                    "upload_bytes,download_bytes,probe_up_bytes_ms,probe_down_bytes_ms,probe_rtt_ms,cpu_cores," +
                    "faces_detected,track_labels,overlap_track_ids,first_result_ms,media_duration_ms,result_text,status," +
                    "battery_current_ua,battery_charge_uah,battery_voltage_mv,battery_energy_nwh\n"
            )
        }
    }

    private fun append(values: List<Any?>) {
        file.appendText((values + batteryValues()).joinToString(",") { csv(it?.toString().orEmpty()) } + "\n")
    }

    private fun batteryValues(): List<Any> {
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val voltage = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        return listOf(
            battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            voltage,
            battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        )
    }

    private fun csv(value: String) = "\"${value.replace("\"", "\"\"")}\""

}
