package edu.scut.livetranscript

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.io.IOException

data class HostConnectionAddresses(
    val faceBaseUrl: String,
    val funAsrWsUrl: String,
    val overlapUrl: String
)

data class HostConnectionStatus(
    val faceReady: Boolean,
    val funAsrReady: Boolean,
    val overlapReady: Boolean
) {
    val allReady get() = faceReady && funAsrReady && overlapReady
    fun summary() = listOf(
        "人脸 MEC ${if (faceReady) "正常" else "未连接"}",
        "FunASR ${if (funAsrReady) "正常" else "未连接"}",
        "双人分离 ${if (overlapReady) "正常" else "未连接"}"
    ).joinToString(" · ")
}

object HostConnectionConfig {
    fun isEmulator(fingerprint: String, model: String, product: String): Boolean =
        fingerprint.contains("generic", ignoreCase = true) ||
            model.contains("emulator", ignoreCase = true) ||
            model.contains("sdk_gphone", ignoreCase = true) ||
            product.contains("sdk_gphone", ignoreCase = true)

    fun defaults(isEmulator: Boolean, funAsrWsUrl: String): HostConnectionAddresses {
        val host = if (isEmulator) "10.0.2.2" else "127.0.0.1"
        return HostConnectionAddresses(
            faceBaseUrl = "http://$host:8000",
            funAsrWsUrl = funAsrWsUrl,
            overlapUrl = "http://$host:8001/separate-and-transcribe"
        )
    }
}

object HostConnectionProbe {
    fun check(faceBaseUrl: String, funAsrWsUrl: String, overlapUrl: String): HostConnectionStatus =
        HostConnectionStatus(
            faceReady = checkHttp("${faceBaseUrl.trimEnd('/')}/health"),
            funAsrReady = checkWebSocketPort(funAsrWsUrl),
            overlapReady = checkHttp("${overlapUrl.substringBeforeLast('/')}/health")
        )

    private fun checkHttp(address: String): Boolean {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 2000
        connection.readTimeout = 2000
        return try {
            connection.responseCode in 200..299
        } catch (_: IOException) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun checkWebSocketPort(address: String): Boolean {
        val uri = URI(address)
        val port = if (uri.port > 0) uri.port else if (uri.scheme == "wss") 443 else 80
        return try { Socket().use { socket ->
            socket.connect(InetSocketAddress(uri.host, port), 2000)
            true
        } } catch (_: IOException) { false }
    }
}
