package mihon.feature.translate

import mihon.feature.translate.engine.ResponseParser
import mihon.feature.translate.engine.TranslationException
import mihon.feature.translate.model.BlockKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResponseParserTest {

    private val ids = listOf("0.1", "0.2", "1.1")

    @Test
    fun `parses a well formed reply`() {
        val raw = """
            {"translations":[
              {"id":"0.1","type":"dialogue","speaker":"Jin","text":"Where are you going?"},
              {"id":"0.2","type":"sfx","speaker":"","text":"Bang"},
              {"id":"1.1","type":"credit","text":"scan by xyz"}
            ],
            "glossary":[{"source":"진우","target":"Jinwoo","category":"character","note":"MC","correction":false}],
            "characters":[{"name":"Jinwoo","original_name":"진우","gender":"male","speech_style":"blunt","relationships":""}],
            "chapter_summary":"Jinwoo leaves."}
        """.trimIndent()

        val parsed = ResponseParser.parse(raw, ids)

        assertEquals(3, parsed.translations.size)
        assertEquals("Where are you going?", parsed.translations[0].text)
        assertEquals("Jin", parsed.translations[0].speaker)
        assertEquals(BlockKind.SFX, parsed.translations[1].kind)
        assertEquals(BlockKind.CREDIT, parsed.translations[2].kind)
        assertEquals("Jinwoo", parsed.memoryUpdate.glossary.single().target)
        assertEquals("blunt", parsed.memoryUpdate.characters.single().speechStyle)
        assertEquals("Jinwoo leaves.", parsed.chapterSummary)
    }

    @Test
    fun `accepts markdown fences, renamed keys and numeric ids`() {
        val raw = """
            Here you go:
            ```json
            {"blocks":[{"block_id":0.1,"translation":"Hi"},{"id":"p0b2","translated":"Bye"}],"summary":"x"}
            ```
        """.trimIndent()

        val parsed = ResponseParser.parse(raw, ids)

        assertEquals(listOf("0.1", "0.2"), parsed.translations.map { it.id })
        assertEquals(listOf("Hi", "Bye"), parsed.translations.map { it.text })
        assertEquals("x", parsed.chapterSummary)
    }

    @Test
    fun `keeps complete entries of a truncated reply`() {
        val raw = """{"translations":[{"id":"0.1","text":"One"},{"id":"0.2","text":"Two"},{"id":"1.1","te"""

        val parsed = ResponseParser.parse(raw, ids)

        assertEquals(listOf("One", "Two"), parsed.translations.map { it.text })
    }

    @Test
    fun `bare array reply is treated as translations`() {
        val parsed = ResponseParser.parse("""[{"id":"1.1","text":"Yes"}]""", ids)
        assertEquals("Yes", parsed.translations.single().text)
    }

    @Test
    fun `arabic text survives parsing`() {
        val parsed = ResponseParser.parse("""{"translations":[{"id":"0.1","text":"إلى أين أنت ذاهب؟"}]}""", ids)
        assertEquals("إلى أين أنت ذاهب؟", parsed.translations.single().text)
    }

    @Test
    fun `garbage throws a retryable error`() {
        val e = assertThrows(TranslationException::class.java) { ResponseParser.parse("sorry, I can't", ids) }
        assertTrue(e.retryable)
    }
}

class AutomaticEngineTest {

    @org.junit.jupiter.api.Test
    fun `google web replies are parsed in both shapes`() {
        val auto = mihon.feature.translate.engine.GoogleWebEngine.parse("""[["أين أنت ذاهب","en"],["انتهت المدرسة.","zh-CN"]]""")
        org.junit.jupiter.api.Assertions.assertEquals(listOf("أين أنت ذاهب", "انتهت المدرسة."), auto)
        val fixed = mihon.feature.translate.engine.GoogleWebEngine.parse("""["Minjun, où vas-tu ?","L'école est finie."]""")
        org.junit.jupiter.api.Assertions.assertEquals(2, fixed.size)
    }

    @org.junit.jupiter.api.Test
    fun `capital comic text is sentence cased and credits are kept`() {
        val e = mihon.feature.translate.engine.GoogleWebEngine
        org.junit.jupiter.api.Assertions.assertEquals("Where are you going? I am hungry.", e.normalizeForTranslation("WHERE ARE YOU GOING? I AM HUNGRY."))
        org.junit.jupiter.api.Assertions.assertEquals(mihon.feature.translate.model.BlockKind.CREDIT, e.classify("READ AT ASURASCANS.COM"))
        org.junit.jupiter.api.Assertions.assertEquals(mihon.feature.translate.model.BlockKind.NOISE, e.classify("!!"))
    }

    private fun request(vararg texts: String) = mihon.feature.translate.engine.TranslationRequest(
        sourceLanguage = "en", targetLanguage = "ar", seriesTitle = "T",
        memory = mihon.feature.translate.model.SeriesMemory(mangaId = 1, title = "T", targetLanguage = "ar"),
        chapterName = "1", chapterNumber = 1.0, previousChapterSummaries = emptyList(), chapterSummarySoFar = "",
        recentLines = emptyList(),
        pages = listOf(
            mihon.feature.translate.model.PageText(0, 100, 100, texts.mapIndexed { i, t ->
                mihon.feature.translate.model.TextBlock("0.${i + 1}", listOf(mihon.feature.translate.model.TextLine(t, mihon.feature.translate.model.Box(0, i * 20, 50, i * 20 + 10))), "en")
            }),
        ),
        lookahead = emptyList(), translateSfx = false,
    )

    private fun result(vararg texts: String) = mihon.feature.translate.engine.TranslationResult(
        texts.mapIndexed { i, t -> mihon.feature.translate.model.BlockTranslation("0.${i + 1}", t) },
        mihon.feature.translate.model.MemoryUpdate(), "", "test",
    )

    @org.junit.jupiter.api.Test
    fun `validator accepts good arabic and rejects copies, wrong language and gaps`() {
        val v = mihon.feature.translate.engine.ResultValidator
        val req = request("Where are you going?", "School is over.", "I'm hungry!")
        org.junit.jupiter.api.Assertions.assertTrue(v.check(req, result("إلى أين أنت ذاهب؟", "انتهت المدرسة.", "أنا جائع!")).ok)
        org.junit.jupiter.api.Assertions.assertFalse(v.check(req, result("Where are you going?", "School is over.", "I'm hungry!")).ok)
        org.junit.jupiter.api.Assertions.assertFalse(v.check(req, result("Où vas-tu ?", "L'école est finie.", "J'ai faim !")).ok)
        org.junit.jupiter.api.Assertions.assertFalse(v.check(req, result("إلى أين أنت ذاهب؟")).ok)
    }
}

class MachineTranslatedPagesTest {
    @org.junit.jupiter.api.Test
    fun `raw url is extracted only from composed pages`() {
        val composed = "https://cdn.example.com/ch1/01.webp#[{\"x\":1,\"y\":2,\"_width\":3,\"_height\":4}]"
        org.junit.jupiter.api.Assertions.assertEquals("https://cdn.example.com/ch1/01.webp", MachineTranslatedPages.rawUrl(composed))
        org.junit.jupiter.api.Assertions.assertNull(MachineTranslatedPages.rawUrl("https://cdn.example.com/ch1/01.jpg"))
        org.junit.jupiter.api.Assertions.assertNull(MachineTranslatedPages.rawUrl("https://example.com/page#anchor"))
    }
}

class SiteDialogsTest {
    private val sample = """[{"x":120.5,"y":300.0,"_width":260.0,"_height":90.0,"textByLanguage":{"text":"你要去哪里？","en":"Where are you going?","fr":"Où vas-tu ?"}},""" +
        """{"x":80,"y":900,"_width":300,"_height":120,"textByLanguage":{"text":"我饿了","en":"I'm hungry"}}]"""

    @org.junit.jupiter.api.Test
    fun `parses boxes and prefers the original chinese text`() {
        val dialogs = SiteDialogs.parse(sample)
        org.junit.jupiter.api.Assertions.assertEquals(2, dialogs.size)
        org.junit.jupiter.api.Assertions.assertEquals(mihon.feature.translate.model.Box(121, 300, 381, 390), dialogs[0].box)
        val choice = SiteDialogs.choose(dialogs)!!
        org.junit.jupiter.api.Assertions.assertEquals("text", choice.key)
        org.junit.jupiter.api.Assertions.assertEquals("zh", choice.language)
        val page = SiteDialogs.toPageText(dialogs, choice, 4, 800, 2000)
        org.junit.jupiter.api.Assertions.assertTrue(page.preCleaned)
        org.junit.jupiter.api.Assertions.assertEquals(listOf("4.1", "4.2"), page.blocks.map { it.id })
        org.junit.jupiter.api.Assertions.assertEquals("你要去哪里？", page.blocks[0].text)
    }

    @org.junit.jupiter.api.Test
    fun `korean wins and english is the fallback`() {
        val ko = SiteDialogs.parse("""[{"x":1,"y":1,"_width":10,"_height":10,"textByLanguage":{"en":"Hi","ko":"안녕하세요"}}]""")
        org.junit.jupiter.api.Assertions.assertEquals(SiteDialogs.Choice("ko", "ko"), SiteDialogs.choose(ko))
        val en = SiteDialogs.parse("""[{"x":1,"y":1,"_width":10,"_height":10,"textByLanguage":{"text":"Hello there","en":"Hello there"}}]""")
        org.junit.jupiter.api.Assertions.assertEquals(SiteDialogs.Choice("en", "en"), SiteDialogs.choose(en))
    }

    @org.junit.jupiter.api.Test
    fun `percent encoded and broken fragments are handled`() {
        val encoded = java.net.URLEncoder.encode("""[{"x":1,"y":1,"_width":10,"_height":10,"textByLanguage":{"text":"好"}}]""", "UTF-8")
        org.junit.jupiter.api.Assertions.assertEquals(1, SiteDialogs.parse(encoded).size)
        org.junit.jupiter.api.Assertions.assertTrue(SiteDialogs.parse("[not json").isEmpty())
    }
}

class ModelPickTest {
    @org.junit.jupiter.api.Test
    fun `picks a free general chat model on openrouter and a strong one elsewhere`() {
        val pick = mihon.feature.translate.engine.OpenAiCompatibleEngine.pickModel(
            listOf("openai/gpt-5", "deepseek/deepseek-r1:free", "meta-llama/llama-3.3-70b-instruct:free", "google/gemma-3-27b-it:free"),
        )
        org.junit.jupiter.api.Assertions.assertEquals("meta-llama/llama-3.3-70b-instruct:free", pick)
        val groq = mihon.feature.translate.engine.OpenAiCompatibleEngine.pickModel(
            listOf("whisper-large-v3", "llama-guard-4", "llama-3.1-8b-instant", "llama-3.3-70b-versatile"),
        )
        org.junit.jupiter.api.Assertions.assertEquals("llama-3.3-70b-versatile", groq)
    }
}
