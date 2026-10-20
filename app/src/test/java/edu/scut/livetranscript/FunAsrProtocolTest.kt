package edu.scut.livetranscript

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FunAsrProtocolTest {
    @Test
    fun startupMessageRequestsTwoPassStreaming() {
        val message = JSONObject(FunAsrProtocol.startMessage("session-1"))

        assertEquals("2pass", message.getString("mode"))
        assertEquals("session-1", message.getString("wav_name"))
        assertTrue(message.getBoolean("is_speaking"))
        assertEquals("[5,10,5]", message.getJSONArray("chunk_size").toString())
        assertEquals(10, message.getInt("chunk_interval"))
    }

    @Test
    fun pcmChunksUseLittleEndianAndStayInSendOrder() {
        val first = FunAsrProtocol.encodePcm(shortArrayOf(0x1234, -2), 2)
        val second = FunAsrProtocol.encodePcm(shortArrayOf(0x5678), 1)

        assertArrayEquals(byteArrayOf(0x34, 0x12, -2, -1), first)
        assertArrayEquals(byteArrayOf(0x78, 0x56), second)

        val factory = FakeSocketFactory()
        val engine = FunAsrSpeechEngine("ws://example.test", factory, RecordingSpeechEngine())
        engine.start {}
        factory.open()
        engine.acceptPcm(shortArrayOf(0x1234, -2), 2, capturedAtMs = 100L)
        engine.acceptPcm(shortArrayOf(0x5678), 1, capturedAtMs = 200L)

        val setup = JSONObject(factory.socket.textMessages.single())
        assertEquals("2pass", setup.getString("mode"))
        assertTrue(setup.getBoolean("is_speaking"))
        assertEquals(2, factory.socket.audioChunks.size)
        assertArrayEquals(first, factory.socket.audioChunks[0])
        assertArrayEquals(second, factory.socket.audioChunks[1])
        engine.stop()
    }

    @Test
    fun onlineAndOfflineResponsesMapToPartialAndFinalEvents() {
        val partial = FunAsrProtocol.parseResponse(
            """{"mode":"2pass-online","text":"Hello 你好","is_final":false}""",
            startedAtMs = 1_000L,
            endedAtMs = 1_400L
        )
        val final = FunAsrProtocol.parseResponse(
            """{"mode":"2pass-offline","text":"Hello 你好","is_final":true}""",
            startedAtMs = 1_000L,
            endedAtMs = 1_600L
        )

        assertEquals(SpeechEvent("Hello 你好", 1_000L, 1_400L, false), partial)
        assertEquals(SpeechEvent("Hello 你好", 1_000L, 1_600L, true), final)
        assertFalse(FunAsrProtocol.parseResponse("not json", 1_000L, 1_100L) != null)
    }

    @Test
    fun connectionFailureSwitchesToLocalChineseFallbackAndFlushesQueuedPcm() {
        val factory = FakeSocketFactory()
        val fallback = RecordingSpeechEngine()
        val statuses = mutableListOf<String>()
        val engine = FunAsrSpeechEngine("ws://example.test", factory, fallback, statuses::add)

        engine.start {}
        engine.acceptPcm(shortArrayOf(10, 20), 2, capturedAtMs = 100L)
        factory.fail(IllegalStateException("connection refused"))
        engine.acceptPcm(shortArrayOf(30, 40), 2, capturedAtMs = 200L)

        assertEquals(1, fallback.startCount)
        assertEquals(listOf(2, 2), fallback.acceptedCounts)
        assertTrue(statuses.any { it.contains("本地中文后备") })
        engine.stop()
    }

    @Test
    fun finalResponseDuringStopDrainIsDelivered() {
        val factory = FakeSocketFactory()
        var finalText: String? = null
        val engine = FunAsrSpeechEngine("ws://example.test", factory, RecordingSpeechEngine())

        engine.start { if (it.isFinal) finalText = it.text }
        factory.open()
        engine.stop()
        factory.listener.onText("""{"mode":"2pass-offline","text":"最后一句","is_final":true}""")

        assertEquals("最后一句", finalText)
        assertTrue(factory.socket.closed)
    }

    @Test
    fun remoteFailureWithoutLoadedLocalModelReportsUnavailableFallback() {
        val factory = FakeSocketFactory()
        val statuses = mutableListOf<String>()
        val engine = FunAsrSpeechEngine("ws://example.test", factory, null, statuses::add)

        engine.start {}
        factory.fail(IllegalStateException("connection refused"))
        engine.acceptPcm(shortArrayOf(10, 20), 2, capturedAtMs = 100L)

        assertTrue(statuses.any { it.contains("本地中文后备模型未加载") })
        engine.stop()
    }

    private class FakeSocketFactory : FunAsrSocketFactory {
        lateinit var listener: FunAsrSocketListener
        val socket = FakeSocket()

        override fun connect(url: String, listener: FunAsrSocketListener) {
            assertEquals("ws://example.test", url)
            this.listener = listener
        }

        fun open() = listener.onOpen(socket)
        fun fail(error: Throwable) = listener.onFailure(error)
    }

    private class FakeSocket : FunAsrSocket {
        val textMessages = mutableListOf<String>()
        val audioChunks = mutableListOf<ByteArray>()
        var closed = false

        override fun sendText(text: String): Boolean {
            textMessages += text
            return true
        }

        override fun sendBinary(bytes: ByteArray): Boolean {
            audioChunks += bytes
            return true
        }

        override fun close() { closed = true }
    }

    private class RecordingSpeechEngine : SpeechEngine {
        var startCount = 0
        val acceptedCounts = mutableListOf<Int>()

        override fun start(onEvent: (SpeechEvent) -> Unit) {
            startCount++
        }

        override fun acceptPcm(samples: ShortArray, count: Int, capturedAtMs: Long) {
            acceptedCounts += count
        }

        override fun stop() = Unit
    }
}
