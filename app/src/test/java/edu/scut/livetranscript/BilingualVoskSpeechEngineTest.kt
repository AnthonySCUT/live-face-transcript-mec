package edu.scut.livetranscript

import org.junit.Assert.assertEquals
import org.junit.Test

class BilingualVoskSpeechEngineTest {
    @Test
    fun choosesTheMorePlausibleLanguageCandidateOncePerUtterance() {
        val chinese = EventSpeechEngine(listOf(SpeechEvent("你好", 0, 900, true)))
        val english = EventSpeechEngine(listOf(SpeechEvent("hello there", 0, 900, true)))
        val engine = BilingualVoskSpeechEngine(chinese, english)
        val events = mutableListOf<SpeechEvent>()
        engine.start(events::add)

        engine.acceptPcm(ShortArray(1_600), 1_600, 1_000L)

        assertEquals(listOf("hello there"), events.map { it.text })
        assertEquals(listOf(true), events.map { it.isFinal })
    }

    @Test
    fun emitsAFinalWhenOnlyOneLanguageModelRecognizesTheUtterance() {
        val chinese = EventSpeechEngine(listOf(SpeechEvent("明天见", 0, 900, true)))
        val english = EventSpeechEngine(emptyList())
        val engine = BilingualVoskSpeechEngine(chinese, english)
        val events = mutableListOf<SpeechEvent>()
        engine.start(events::add)

        engine.acceptPcm(ShortArray(1_600), 1_600, 1_000L)
        engine.stop()

        assertEquals(listOf("明天见"), events.map { it.text })
    }

    @Test
    fun skipsMalformedLocalModelOutputWhenOtherLanguageHasNoCandidate() {
        val chinese = EventSpeechEngine(listOf(SpeechEvent("�� ���", 0, 900, true)))
        val english = EventSpeechEngine(emptyList())
        val engine = BilingualVoskSpeechEngine(chinese, english)
        val events = mutableListOf<SpeechEvent>()
        engine.start(events::add)

        engine.acceptPcm(ShortArray(1_600), 1_600, 1_000L)
        engine.stop()

        assertEquals(emptyList<SpeechEvent>(), events)
    }

    @Test
    fun comparesMatchingPartialFromOtherModelBeforeChoosingFinalLanguage() {
        val chinese = EventSpeechEngine(listOf(SpeechEvent("疑高二度我六疑度", 100, 900, true)))
        val english = EventSpeechEngine(listOf(SpeechEvent("if i told you eight years", 100, 800, false)))
        val engine = BilingualVoskSpeechEngine(chinese, english)
        val events = mutableListOf<SpeechEvent>()
        engine.start(events::add)

        engine.acceptPcm(ShortArray(1_600), 1_600, 1_000L)
        engine.stop()

        assertEquals(listOf("if i told you eight years"), events.map { it.text })
        assertEquals(listOf(true), events.map { it.isFinal })
    }

    @Test
    fun filtersSlowShortHanCandidateFromEnglishBackgroundAudio() {
        val chinese = EventSpeechEngine(listOf(SpeechEvent("我 怀疑 态度", 0, 6_100, true, confidence = 0.8)))
        val english = EventSpeechEngine(emptyList())
        val engine = BilingualVoskSpeechEngine(chinese, english)
        val events = mutableListOf<SpeechEvent>()
        engine.start(events::add)

        engine.acceptPcm(ShortArray(1_600), 1_600, 6_200L)
        engine.stop()

        assertEquals(emptyList<SpeechEvent>(), events)
    }

    @Test
    fun filtersEnglishLaughTokensFromBackgroundAudio() {
        val chinese = EventSpeechEngine(emptyList())
        val english = EventSpeechEngine(listOf(SpeechEvent("ha ha", 0, 1_000, true, confidence = 1.0)))
        val engine = BilingualVoskSpeechEngine(chinese, english)
        val events = mutableListOf<SpeechEvent>()
        engine.start(events::add)

        engine.acceptPcm(ShortArray(1_600), 1_600, 1_100L)
        engine.stop()

        assertEquals(emptyList<SpeechEvent>(), events)
    }

    private class EventSpeechEngine(private val events: List<SpeechEvent>) : SpeechEngine {
        private var callback: ((SpeechEvent) -> Unit)? = null
        private var emitted = false

        override fun start(onEvent: (SpeechEvent) -> Unit) { callback = onEvent }
        override fun acceptPcm(samples: ShortArray, count: Int, capturedAtMs: Long) {
            if (!emitted) events.forEach { callback?.invoke(it) }
            emitted = true
        }
        override fun stop() = Unit
    }
}
