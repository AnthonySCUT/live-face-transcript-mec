package edu.scut.livetranscript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptSessionTest {
    @Test
    fun partialResultsRevisePreviewWithoutPersistence() {
        val previews = mutableListOf<TranscriptLine>()
        val saved = mutableListOf<TranscriptLine>()
        val session = TranscriptSession({ "陌生人 A" }, { _, line -> previews.add(line) }, { _, line -> saved.add(line) })
        session.start(1L)

        assertTrue(session.accept(1L, event("Hello", final = false)))
        assertTrue(session.accept(1L, event("Hello 你好", final = false)))

        assertEquals(listOf("Hello", "Hello 你好"), previews.map { it.text })
        assertTrue(saved.isEmpty())
    }

    @Test
    fun finalResultIsSavedOnceAndReplacesPreview() {
        val previews = mutableListOf<TranscriptLine>()
        val saved = mutableListOf<TranscriptLine>()
        val session = TranscriptSession({ "陌生人 A" }, { _, line -> previews.add(line) }, { _, line -> saved.add(line) })
        val final = event("Hello 你好", final = true)
        session.start(2L)

        session.accept(2L, event("Hello", final = false))
        assertTrue(session.accept(2L, final))
        assertFalse(session.accept(2L, final))

        assertEquals(listOf(TranscriptLine("陌生人 A", "Hello 你好")), saved)
        assertEquals(TranscriptLine("陌生人 A", "Hello 你好"), previews.last())
    }

    @Test
    fun finalPrefixDoesNotDiscardTheLastCharactersAlreadyRecognizedInPreview() {
        val saved = mutableListOf<TranscriptLine>()
        val session = TranscriptSession({ "cpf" }, { _, _ -> }, { _, line -> saved.add(line) })
        session.start(5L)

        session.accept(5L, event("现在是八点了", final = false))
        session.accept(5L, event("现在是八点", final = true))

        assertEquals(TranscriptLine("cpf", "现在是八点了"), saved.single())
    }

    @Test
    fun repeatedShortFinalAfterPreservingPreviewTailIsStillSavedOnlyOnce() {
        val saved = mutableListOf<TranscriptLine>()
        val session = TranscriptSession({ "cpf" }, { _, _ -> }, { _, line -> saved.add(line) })
        session.start(6L)

        session.accept(6L, event("现在是八点了", final = false))
        session.accept(6L, event("现在是八点", final = true))
        assertFalse(session.accept(6L, event("现在是八点", final = true)))

        assertEquals(listOf(TranscriptLine("cpf", "现在是八点了")), saved)
    }

    @Test
    fun missingSpeakerEvidenceUsesUnconfirmedLabel() {
        val saved = mutableListOf<TranscriptLine>()
        val session = TranscriptSession({ null }, { _, _ -> }, { _, line -> saved.add(line) })
        session.start(3L)

        session.accept(3L, event("混合语句", final = true))

        assertEquals("未确认说话人", saved.single().speaker)
    }

    @Test
    fun stopIgnoresLateCallbacksFromThatGeneration() {
        val previews = mutableListOf<TranscriptLine>()
        val saved = mutableListOf<TranscriptLine>()
        val session = TranscriptSession({ "陌生人 A" }, { _, line -> previews.add(line) }, { _, line -> saved.add(line) })
        session.start(4L)
        session.accept(4L, event("正在识别", final = false))

        session.stop(4L)

        assertFalse(session.accept(4L, event("迟到结果", final = true)))
        assertEquals(1, previews.size)
        assertTrue(saved.isEmpty())
    }

    private fun event(text: String, final: Boolean) = SpeechEvent(
        text = text,
        startedAtMs = 1_000L,
        endedAtMs = 1_500L,
        isFinal = final
    )
}
