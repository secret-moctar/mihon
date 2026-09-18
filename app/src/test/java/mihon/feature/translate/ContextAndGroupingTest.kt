package mihon.feature.translate

import mihon.feature.translate.engine.PromptBuilder
import mihon.feature.translate.engine.RecentLine
import mihon.feature.translate.engine.TranslationRequest
import mihon.feature.translate.model.Box
import mihon.feature.translate.model.CharacterProfile
import mihon.feature.translate.model.GlossaryEntry
import mihon.feature.translate.model.MemoryUpdate
import mihon.feature.translate.model.PageText
import mihon.feature.translate.model.SeriesMemory
import mihon.feature.translate.ocr.BlockGrouper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContextAndGroupingTest {

    private fun line(text: String, l: Int, t: Int, r: Int, b: Int) = BlockGrouper.RawLine(text, Box(l, t, r, b))

    @Test
    fun `lines of one bubble are grouped and separate bubbles stay apart`() {
        val lines = listOf(
            // Bubble A: three centered lines
            line("너 지금", 110, 100, 250, 130),
            line("어디 가는", 100, 134, 260, 164),
            line("거야?", 140, 168, 220, 198),
            // Bubble B far below
            line("집에 가.", 400, 600, 540, 630),
            // Page number noise
            line("12", 380, 1150, 400, 1170),
        )

        val blocks = BlockGrouper.group(lines, pageIndex = 3, language = "ko")

        assertEquals(2, blocks.size)
        assertEquals("3.1", blocks[0].id)
        assertEquals(3, blocks[0].lines.size)
        assertEquals("집에 가.", blocks[1].text)
    }

    @Test
    fun `vertical japanese columns read right to left`() {
        val lines = listOf(
            line("いくぞ", 300, 100, 330, 220),
            line("おまえ", 340, 100, 370, 220),
        )
        val block = BlockGrouper.group(lines, 0, "ja").single()
        assertTrue(block.vertical)
        assertEquals("おまえいくぞ", block.text)
    }

    @Test
    fun `overlapping tile duplicates are removed keeping the one away from the seam`() {
        val cut = line("잘린", 100, 990, 200, 1000) to 2
        val full = line("잘린 줄", 100, 990, 220, 1020) to 300
        val kept = BlockGrouper.dedupeTileLines(listOf(cut, full))
        assertEquals(listOf("잘린 줄"), kept.map { it.text })
    }

    @Test
    fun `memory keeps first translation of a name and respects user locks`() {
        val memory = SeriesMemory(
            mangaId = 1,
            title = "T",
            targetLanguage = "ar",
            glossary = listOf(
                GlossaryEntry("진우", "جين وو"),
                GlossaryEntry("헌터", "صياد", locked = true),
            ),
        )
        val updated = memory.merge(
            MemoryUpdate(
                glossary = listOf(
                    GlossaryEntry("진우", "جينو"),
                    GlossaryEntry("헌터", "هنتر", correction = true),
                    GlossaryEntry("게이트", "البوابة"),
                ),
                characters = listOf(CharacterProfile("جين وو", speechStyle = "calm")),
            ),
        )
        assertEquals("جين وو", updated.glossary.first { it.source == "진우" }.target)
        assertEquals("صياد", updated.glossary.first { it.source == "헌터" }.target)
        assertEquals("البوابة", updated.glossary.first { it.source == "게이트" }.target)

        val corrected = updated.merge(MemoryUpdate(glossary = listOf(GlossaryEntry("진우", "جينوو", correction = true))))
        assertEquals("جينوو", corrected.glossary.first { it.source == "진우" }.target)
    }

    @Test
    fun `previous summaries are ordered and exclude current chapter`() {
        var memory = SeriesMemory(mangaId = 1, title = "T", targetLanguage = "en")
        listOf(3.0 to "c", 1.0 to "a", 2.5 to "b", 4.0 to "future").forEach { (n, s) ->
            memory = memory.withChapterSummary(n, s)
        }
        assertEquals(listOf("1" to "a", "2.5" to "b", "3" to "c"), memory.summariesBefore(4.0, 5))
        assertEquals(listOf("2.5" to "b", "3" to "c"), memory.summariesBefore(4.0, 2))
    }

    @Test
    fun `prompt contains memory, context and only translatable ids`() {
        val page = BlockGrouper.group(listOf(line("가자", 10, 10, 60, 30)), 5, "ko")
        val next = BlockGrouper.group(listOf(line("그래", 10, 10, 60, 30)), 6, "ko")
        val request = TranslationRequest(
            sourceLanguage = "ko",
            targetLanguage = "ar",
            seriesTitle = "Series",
            memory = SeriesMemory(
                mangaId = 1,
                title = "Series",
                targetLanguage = "ar",
                glossary = listOf(GlossaryEntry("진우", "جين وو", "character")),
                characters = listOf(CharacterProfile("جين وو", "진우", "male", "casual")),
                userNotes = "Use Modern Standard Arabic",
            ),
            chapterName = "Chapter 10",
            chapterNumber = 10.0,
            previousChapterSummaries = listOf("9" to "They entered the gate."),
            chapterSummarySoFar = "Fight starts.",
            recentLines = listOf(RecentLine(4, "뭐야", "ما هذا", "جين وو")),
            pages = listOf(PageText(5, 100, 100, page)),
            lookahead = listOf(PageText(6, 100, 100, next)),
            translateSfx = false,
        )

        val system = PromptBuilder.systemPrompt(request)
        val user = PromptBuilder.userPrompt(request)

        assertTrue("Arabic" in system)
        assertTrue("right-to-left" in system)
        assertTrue("Use Modern Standard Arabic" in system)
        assertTrue("진우 => جين وو [character]" in user)
        assertTrue("Chapter 9: They entered the gate." in user)
        assertTrue("THIS CHAPTER SO FAR: Fight starts." in user)
        assertTrue("[5.1]" in user)
        // Lookahead text is present but without an id so it is not translated.
        assertTrue("그래" in user)
        assertFalse("[6.1]" in user)
    }
}
