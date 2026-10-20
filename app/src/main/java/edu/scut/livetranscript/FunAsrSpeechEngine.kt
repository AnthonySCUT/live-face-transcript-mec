package edu.scut.livetranscript

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.sqrt

interface FunAsrSocket {
    fun sendText(text: String): Boolean
    fun sendBinary(bytes: ByteArray): Boolean
    fun close()
}

interface FunAsrSocketListener {
    fun onOpen(socket: FunAsrSocket)
    fun onText(text: String)
    fun onFailure(error: Throwable)
    fun onClosed()
}

interface FunAsrSocketFactory {
    fun connect(url: String, listener: FunAsrSocketListener)
}

class OkHttpFunAsrSocketFactory : FunAsrSocketFactory {
    override fun connect(url: String, listener: FunAsrSocketListener) {
        val request = Request.Builder()
            .url(url)
            .header("Sec-WebSocket-Protocol", "binary")
            .build()
        CLIENT.newWebSocket(request, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: Response) {
                listener.onOpen(object : FunAsrSocket {
                    override fun sendText(text: String) = webSocket.send(text)
                    override fun sendBinary(bytes: ByteArray) = webSocket.send(ByteString.of(*bytes))
                    override fun close() {
                        webSocket.close(1000, "session stopped")
                    }
                })
            }

            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) = listener.onText(text)

            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: Response?) = listener.onFailure(t)

            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) = listener.onClosed()
        })
    }

    private companion object {
        val CLIENT = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }
}

class FunAsrSpeechEngine(
    private val endpoint: String,
    private val socketFactory: FunAsrSocketFactory = OkHttpFunAsrSocketFactory(),
    private val fallback: SpeechEngine?,
    private val onStatus: (String) -> Unit = {}
) : SpeechEngine {
    private enum class State { STOPPED, CONNECTING, REMOTE, DRAINING, FALLBACK }

    private data class PcmFrame(val samples: ShortArray, val capturedAtMs: Long)

    private val lock = Any()
    private val fallbackLock = Any()
    private val pendingFrames = ArrayDeque<PcmFrame>()
    private var state = State.STOPPED
    private var callback: ((SpeechEvent) -> Unit)? = null
    private var socket: FunAsrSocket? = null
    private var fallbackStarted = false
    private var pendingSampleCount = 0
    private var speechStartedAtMs: Long? = null
    private var latestCapturedAtMs = 0L
    private var previousPartial = ""

    override fun start(onEvent: (SpeechEvent) -> Unit) {
        synchronized(lock) {
            if (state != State.STOPPED) return
            state = State.CONNECTING
            callback = onEvent
            pendingFrames.clear()
            pendingSampleCount = 0
            speechStartedAtMs = null
            latestCapturedAtMs = 0L
            previousPartial = ""
            fallbackStarted = false
        }
        onStatus("正在连接中英实时识别服务…")
        try {
            socketFactory.connect(endpoint, object : FunAsrSocketListener {
                override fun onOpen(socket: FunAsrSocket) = onSocketOpen(socket)
                override fun onText(text: String) = onRemoteText(text)
                override fun onFailure(error: Throwable) = switchToFallback(error)
                override fun onClosed() = switchToFallback(IllegalStateException("远端识别连接已关闭"))
            })
        } catch (error: Exception) {
            switchToFallback(error)
        }
    }

    override fun acceptPcm(samples: ShortArray, count: Int, capturedAtMs: Long) {
        if (count <= 0) return
        val frame = PcmFrame(samples.copyOf(count), capturedAtMs)
        var remoteSocket: FunAsrSocket? = null
        var sendToFallback = false
        synchronized(lock) {
            if (state == State.STOPPED) return
            updateSpeechWindowLocked(frame.samples, count, capturedAtMs)
            when (state) {
                State.CONNECTING -> queueLocked(frame)
                State.REMOTE -> remoteSocket = socket
                State.FALLBACK -> sendToFallback = true
                State.DRAINING -> return
                State.STOPPED -> return
            }
        }
        if (sendToFallback) {
            synchronized(fallbackLock) {
                val shouldSend = synchronized(lock) { state == State.FALLBACK && fallbackStarted }
                if (shouldSend) fallback?.acceptPcm(frame.samples, frame.samples.size, frame.capturedAtMs)
            }
        } else if (remoteSocket != null && !remoteSocket!!.sendBinary(FunAsrProtocol.encodePcm(frame.samples, frame.samples.size))) {
            switchToFallback(IllegalStateException("远端识别服务未接收音频"), listOf(frame))
        }
    }

    override fun stop() {
        synchronized(fallbackLock) {
            val activeSocket: FunAsrSocket?
            val shouldStopFallback: Boolean
            synchronized(lock) {
                if (state == State.STOPPED || state == State.DRAINING) return
                activeSocket = socket
                shouldStopFallback = fallbackStarted
                pendingFrames.clear()
                pendingSampleCount = 0
                speechStartedAtMs = null
                previousPartial = ""
                if (activeSocket != null) {
                    state = State.DRAINING
                } else {
                    socket = null
                    state = State.STOPPED
                    callback = null
                }
            }
            if (activeSocket != null) {
                activeSocket.sendText(FunAsrProtocol.stopMessage())
                Thread {
                    try { Thread.sleep(REMOTE_DRAIN_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                    val closeSocket = synchronized(lock) {
                        if (state != State.DRAINING) false else {
                            state = State.STOPPED
                            callback = null
                            socket = null
                            true
                        }
                    }
                    if (closeSocket) activeSocket.close()
                }.apply { name = "funasr-stop-drain"; isDaemon = true; start() }
            } else if (shouldStopFallback) {
                fallback?.stop()
            }
        }
    }

    private fun onSocketOpen(openSocket: FunAsrSocket) {
        var setupFailed = false
        synchronized(lock) {
            if (state != State.CONNECTING) {
                openSocket.close()
                return
            }
            if (!openSocket.sendText(FunAsrProtocol.startMessage(UUID.randomUUID().toString()))) {
                setupFailed = true
            } else {
                while (pendingFrames.isNotEmpty()) {
                    val frame = pendingFrames.first()
                    if (!openSocket.sendBinary(FunAsrProtocol.encodePcm(frame.samples, frame.samples.size))) {
                        setupFailed = true
                        break
                    }
                    pendingFrames.removeFirst()
                    pendingSampleCount -= frame.samples.size
                }
                if (!setupFailed) {
                    socket = openSocket
                    state = State.REMOTE
                }
            }
        }
        if (setupFailed) {
            openSocket.close()
            switchToFallback(IllegalStateException("无法向远端识别服务发送实时音频"))
        } else {
            onStatus("FunASR 中英混合实时识别中")
        }
    }

    private fun onRemoteText(message: String) {
        val event: SpeechEvent
        val output: ((SpeechEvent) -> Unit)?
        var socketToClose: FunAsrSocket? = null
        synchronized(lock) {
            if (state != State.REMOTE && state != State.DRAINING) return
            val endAtMs = latestCapturedAtMs
            val startAtMs = speechStartedAtMs ?: endAtMs
            event = FunAsrProtocol.parseResponse(message, startAtMs, endAtMs) ?: return
            if (state == State.DRAINING && !event.isFinal) return
            if (!event.isFinal && event.text == previousPartial) return
            if (event.isFinal) {
                speechStartedAtMs = null
                previousPartial = ""
            } else {
                previousPartial = event.text
            }
            output = callback
            if (state == State.DRAINING && event.isFinal) {
                state = State.STOPPED
                callback = null
                socketToClose = socket
                socket = null
            }
        }
        output?.invoke(event)
        socketToClose?.close()
    }

    private fun switchToFallback(error: Throwable, unsentFrames: List<PcmFrame> = emptyList()) {
        synchronized(fallbackLock) {
            val activeSocket: FunAsrSocket?
            val queuedFrames: List<PcmFrame>
            synchronized(lock) {
                if (state == State.STOPPED || state == State.FALLBACK || state == State.DRAINING) return
                activeSocket = socket
                socket = null
                state = State.FALLBACK
                queuedFrames = buildList {
                    addAll(pendingFrames)
                    addAll(unsentFrames)
                }
                pendingFrames.clear()
                pendingSampleCount = 0
                fallbackStarted = fallback != null
            }
            activeSocket?.close()
            val localFallback = fallback
            if (localFallback == null) {
                onStatus("远端识别服务不可用，且本地中文后备模型未加载")
                return
            }
            try {
                localFallback.start { event ->
                    synchronized(fallbackLock) {
                        val output = synchronized(lock) {
                            if (state == State.FALLBACK) callback else null
                        }
                        output?.invoke(event)
                    }
                }
                queuedFrames.forEach { frame -> localFallback.acceptPcm(frame.samples, frame.samples.size, frame.capturedAtMs) }
                onStatus("远端服务暂不可用，已切换本地中文后备（${error.message ?: "连接失败"}）")
            } catch (fallbackError: Exception) {
                synchronized(lock) {
                    callback = null
                    fallbackStarted = false
                }
                onStatus("远端连接失败，本地识别也无法启动：${fallbackError.localizedMessage ?: "未知错误"}")
            }
        }
    }

    private fun queueLocked(frame: PcmFrame) {
        pendingFrames.addLast(frame)
        pendingSampleCount += frame.samples.size
        while (pendingSampleCount > MAX_PENDING_SAMPLES && pendingFrames.isNotEmpty()) {
            pendingSampleCount -= pendingFrames.removeFirst().samples.size
        }
    }

    private fun updateSpeechWindowLocked(samples: ShortArray, count: Int, capturedAtMs: Long) {
        var energy = 0.0
        for (index in 0 until count) {
            val sample = samples[index].toDouble()
            energy += sample * sample
        }
        val rms = sqrt(energy / count)
        val levelDb = if (rms > 0.0) 20.0 * log10(rms / 32768.0) else -96.0
        val frameStartAtMs = capturedAtMs - count * 1000L / SAMPLE_RATE
        if (levelDb >= MIN_SPEECH_DBFS && speechStartedAtMs == null) speechStartedAtMs = frameStartAtMs
        latestCapturedAtMs = capturedAtMs
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val MIN_SPEECH_DBFS = -52.0
        const val MAX_PENDING_SAMPLES = SAMPLE_RATE * 3
        const val REMOTE_DRAIN_MS = 750L
    }
}

object FunAsrProtocol {
    fun startMessage(sessionId: String): String = JSONObject()
        .put("mode", "2pass")
        .put("wav_name", sessionId)
        .put("is_speaking", true)
        .put("chunk_size", JSONArray(listOf(5, 10, 5)))
        .put("chunk_interval", 10)
        .put("encoder_chunk_look_back", 4)
        .put("decoder_chunk_look_back", 1)
        .toString()

    fun stopMessage(): String = JSONObject().put("is_speaking", false).toString()

    fun encodePcm(samples: ShortArray, count: Int): ByteArray {
        require(count in 0..samples.size)
        val bytes = ByteArray(count * 2)
        for (index in 0 until count) {
            val sample = samples[index].toInt()
            bytes[index * 2] = sample.toByte()
            bytes[index * 2 + 1] = (sample shr 8).toByte()
        }
        return bytes
    }

    fun parseResponse(message: String, startedAtMs: Long, endedAtMs: Long): SpeechEvent? {
        return try {
            val result = JSONObject(message)
            val text = result.optString("text").trim()
            if (text.isEmpty()) return null
            val mode = result.optString("mode")
            val isFinal = result.optBoolean("is_final", false) || mode.endsWith("offline")
            SpeechEvent(text, startedAtMs, endedAtMs, isFinal)
        } catch (_: Exception) {
            null
        }
    }
}
