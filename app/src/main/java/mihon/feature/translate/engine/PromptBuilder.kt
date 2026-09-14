package mihon.feature.translate.engine

import mihon.feature.translate.model.PageText
import mihon.feature.translate.model.TextBlock

/**
 * Builds the prompts shared by all LLM engines. Kept free of Android types so it can be unit tested.
 */
object PromptBuilder {

    fun systemPrompt(request: TranslationRequest): String {
        val target = Languages.englishName(request.targetLanguage)
        val source = if (request.sourceLanguage == "auto") {
            "the original language (Korean, Chinese, Japanese or other)"
        } else {
            Languages.englishName(request.sourceLanguage)
        }
        return buildString {
            appendLine(
                "You are an expert comic translator and typesetter's assistant. You translate manhwa, manhua " +
                    "and manga from $source into natural, fluent $target, the way a skilled professional " +
                    "scanlation team would.",
            )
            appendLine()
            appendLine("Input: text extracted by OCR from comic pages, grouped into blocks (usually one speech bubble each).")
            appendLine("Each block has an id, its position on the page and the OCR text. OCR can contain mistakes:")
            appendLine("wrong or missing characters, broken lines, stray symbols. Use the context to recover the intended text.")
            appendLine()
            appendLine("Rules:")
            appendLine("1. Translate the meaning, tone and personality, not word by word. Dialogue must sound like real people talking in $target.")
            appendLine("2. Stay consistent with the series memory: use the glossary translations for names and terms exactly, and keep each character's speech style (formal/casual, rude, archaic, childish, verbal tics, how they address others).")
            appendLine("3. Use the previous chapter summaries, the recent lines and the upcoming pages to resolve who is speaking, pronouns, gender, implied subjects and jokes. Never translate upcoming pages; they are context only.")
            appendLine("4. Bubbles are small: keep translations concise, similar in length to the original meaning. No translator notes, no brackets with explanations.")
            appendLine("5. Blocks that are split parts of one sentence across bubbles should read naturally in sequence.")
            appendLine("6. Classify every block with type: dialogue, thought, narration, sfx (sound effects / onomatopoeia), sign (written text in the scene), credit (scanlator credits, watermarks, website names, ads) or noise (OCR garbage that is not real text).")
            if (request.translateSfx) {
                appendLine("7. Translate sound effects with short natural $target equivalents.")
            } else {
                appendLine("7. For sfx still give a short $target equivalent in text, it may not be displayed.")
            }
            appendLine("8. For credit and noise blocks, return the text unchanged.")
            appendLine("9. Keep honorifics and titles only when the glossary or user notes say so; otherwise use natural $target equivalents.")
            appendLine("10. Report new recurring names and terms in glossary (source as written in the original, target as you translated it), and new or better information about characters (gender, speech style, relationships) in characters. Only include genuinely new or corrected information. Set correction=true only to fix a glossary entry you are sure is wrong.")
            appendLine("11. chapter_summary: 2-5 sentences summarizing the whole chapter so far (previous summary + these pages), in English, naming characters as in the glossary.")
            if (Languages.isRtl(request.targetLanguage)) {
                appendLine("12. $target is written right-to-left. Write normal $target text; do not add direction marks.")
            }
            if (request.globalInstructions.isNotBlank()) {
                appendLine()
                appendLine("User instructions (follow them):")
                appendLine(request.globalInstructions.trim())
            }
            if (request.memory.userNotes.isNotBlank()) {
                appendLine()
                appendLine("User notes for this series (follow them):")
                appendLine(request.memory.userNotes.trim())
            }
            appendLine()
            appendLine("Reply with only a JSON object, no markdown, using exactly this shape:")
            appendLine(RESPONSE_SHAPE)
            appendLine("Every input block id to translate must appear exactly once in translations.")
        }
    }

    fun userPrompt(request: TranslationRequest): String = buildString {
        appendLine("SERIES: ${request.seriesTitle}")
        appendLine("CHAPTER: ${request.chapterName}")
        appendLine("TARGET LANGUAGE: ${Languages.englishName(request.targetLanguage)} (${request.targetLanguage})")
        appendLine()

        val memory = request.memory
        if (memory.glossary.isNotEmpty()) {
            appendLine("GLOSSARY (source => target [category] note):")
            memory.glossary.forEach { g ->
                append("- ").append(g.source).append(" => ").append(g.target)
                if (g.category.isNotBlank()) append(" [").append(g.category).append("]")
                if (g.note.isNotBlank()) append(" ").append(g.note)
                appendLine()
            }
            appendLine()
        }
        if (memory.characters.isNotEmpty()) {
            appendLine("CHARACTERS:")
            memory.characters.forEach { c ->
                append("- ").append(c.name)
                if (c.originalName.isNotBlank()) append(" (").append(c.originalName).append(")")
                val details = listOf(c.gender, c.speechStyle, c.relationships).filter { it.isNotBlank() }
                if (details.isNotEmpty()) append(": ").append(details.joinToString("; "))
                appendLine()
            }
            appendLine()
        }
        if (request.previousChapterSummaries.isNotEmpty()) {
            appendLine("PREVIOUS CHAPTERS:")
            request.previousChapterSummaries.forEach { (number, summary) ->
                appendLine("- Chapter $number: $summary")
            }
            appendLine()
        }
        if (request.chapterSummarySoFar.isNotBlank()) {
            appendLine("THIS CHAPTER SO FAR: ${request.chapterSummarySoFar}")
            appendLine()
        }
        if (request.recentLines.isNotEmpty()) {
            appendLine("RECENT LINES (already translated, for continuity):")
            request.recentLines.forEach { line ->
                append("- ")
                if (!line.speaker.isNullOrBlank()) append(line.speaker).append(": ")
                append(line.original.oneLine()).append(" => ").appendLine(line.translation.oneLine())
            }
            appendLine()
        }

        appendLine("PAGES TO TRANSLATE:")
        request.pages.forEach { appendPage(it) }

        if (request.lookahead.any { it.blocks.isNotEmpty() }) {
            appendLine()
            appendLine("UPCOMING PAGES (context only, do NOT translate):")
            request.lookahead.forEach { appendPage(it, includeIds = false) }
        }

        if (request.images.isNotEmpty()) {
            appendLine()
            appendLine("Images of some blocks are attached in order, labeled by block id. Prefer what you see in them over the OCR text.")
        }
    }

    private fun StringBuilder.appendPage(page: PageText, includeIds: Boolean = true) {
        appendLine("## Page ${page.pageIndex + 1}")
        if (page.blocks.isEmpty()) {
            appendLine("(no text)")
            return
        }
        page.blocks.forEach { block ->
            if (includeIds) append("[").append(block.id).append("] ")
            append("(").append(positionHint(block, page)).append(") ")
            appendLine(block.text.oneLine())
        }
    }

    /** Coarse position so the model can tell reading order and which bubbles are near each other. */
    fun positionHint(block: TextBlock, page: PageText): String {
        val box = block.box
        val x = if (page.width > 0) box.centerX * 100 / page.width else 0
        val y = if (page.height > 0) box.centerY * 100 / page.height else 0
        return "x=$x% y=$y%"
    }

    private fun String.oneLine() = replace('\n', ' ').replace(Regex("\\s+"), " ").trim()

    const val RESPONSE_SHAPE = """{
  "translations": [{"id": "0.1", "type": "dialogue", "speaker": "name or empty", "text": "translated text"}],
  "glossary": [{"source": "original term", "target": "translated term", "category": "character|place|skill|item|organization|term", "note": "", "correction": false}],
  "characters": [{"name": "name in target language", "original_name": "", "gender": "", "speech_style": "", "relationships": ""}],
  "chapter_summary": "..."
}"""
}
