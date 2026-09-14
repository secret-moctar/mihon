package mihon.feature.translate.engine

import mihon.feature.translate.model.BlockKind

/**
 * Cheap checks that decide whether an engine's answer is good enough to show, so a broken or
 * lazy reply falls through to the next engine instead of reaching the page.
 */
object ResultValidator {

    data class Verdict(val ok: Boolean, val reason: String)

    fun check(request: TranslationRequest, result: TranslationResult): Verdict {
        val blocks = request.pages.flatMap { it.blocks }.associateBy { it.id }
        if (blocks.isEmpty()) return Verdict(true, "no text")

        val byId = result.translations.associateBy { it.id }
        // Blocks that need a real translation: anything with letters the engine did not flag.
        val relevant = blocks.values.filter { block -> block.text.count { it.isLetter() } >= 2 }
        val answered = relevant.mapNotNull { byId[it.id] }
        val coverage = if (relevant.isEmpty()) 1f else answered.size.toFloat() / relevant.size
        val minCoverage = if (relevant.size <= 2) 1f else 0.6f
        if (coverage < minCoverage) {
            return Verdict(false, "only ${answered.size} of ${relevant.size} bubbles translated")
        }

        val spoken = answered.filter { it.kind != BlockKind.NOISE && it.kind != BlockKind.CREDIT && it.kind != BlockKind.SFX }
        if (spoken.isEmpty()) return Verdict(true, "nothing to check")

        val target = request.targetLanguage.substringBefore('-')
        val checkable = spoken.filter { it.text.count { c -> c.isLetter() } >= 3 }
        if (checkable.isNotEmpty()) {
            val inScript = checkable.count { scriptShare(it.text, target) >= 0.5f }
            if (inScript < checkable.size * 0.6f) {
                return Verdict(false, "reply is not in ${Languages.englishName(target)} ($inScript/${checkable.size})")
            }
        }

        val copied = spoken.count { t ->
            val original = blocks[t.id]?.text ?: return@count false
            normalize(t.text) == normalize(original) && scriptShare(original, target) < 0.5f
        }
        if (spoken.size >= 2 && copied > spoken.size / 2) {
            return Verdict(false, "$copied bubbles were returned untranslated")
        }

        val tooLong = spoken.count { t ->
            val original = blocks[t.id]?.text ?: return@count false
            t.text.length > original.length * 8 + 60
        }
        if (tooLong > spoken.size / 3) {
            return Verdict(false, "translations are far longer than the bubbles")
        }
        return Verdict(true, "ok")
    }

    /** Share of letters in [text] written in the script used by [language]. */
    fun scriptShare(text: String, language: String): Float {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return 1f
        val matching = letters.count { inScript(it, language) }
        return matching.toFloat() / letters.length
    }

    private fun inScript(c: Char, language: String): Boolean {
        val block = Character.UnicodeBlock.of(c)
        return when (language) {
            "ar", "fa", "ur" -> block == Character.UnicodeBlock.ARABIC ||
                block == Character.UnicodeBlock.ARABIC_SUPPLEMENT ||
                block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A ||
                block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B
            "he" -> block == Character.UnicodeBlock.HEBREW
            "ru", "uk" -> block == Character.UnicodeBlock.CYRILLIC
            "ko" -> block == Character.UnicodeBlock.HANGUL_SYLLABLES || block == Character.UnicodeBlock.HANGUL_JAMO ||
                block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
            "ja" -> block == Character.UnicodeBlock.HIRAGANA || block == Character.UnicodeBlock.KATAKANA ||
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
            "zh" -> block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
            "th" -> block == Character.UnicodeBlock.THAI
            "hi" -> block == Character.UnicodeBlock.DEVANAGARI
            else -> c.code < 0x250 || block == Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL
        }
    }

    private fun normalize(text: String) = text.lowercase().filter { it.isLetterOrDigit() }
}
