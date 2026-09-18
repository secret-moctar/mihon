package mihon.feature.translate.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import mihon.feature.translate.model.BlockKind
import mihon.feature.translate.model.BlockTranslation
import mihon.feature.translate.model.CharacterProfile
import mihon.feature.translate.model.GlossaryEntry
import mihon.feature.translate.model.MemoryUpdate

/**
 * Tolerant parser for model output. Models wrap JSON in markdown, rename keys, return ids as
 * numbers, or occasionally stop mid-object; this recovers whatever is usable.
 */
object ResponseParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    class Parsed(
        val translations: List<BlockTranslation>,
        val memoryUpdate: MemoryUpdate,
        val chapterSummary: String,
    )

    fun parse(raw: String, expectedIds: Collection<String>): Parsed {
        val root = parseRoot(raw) ?: throw TranslationException("Model reply was not valid JSON", retryable = true)

        val translationsElement = root.firstOf("translations", "blocks", "results", "items")
        val translations = (translationsElement as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { obj ->
                val id = obj.string("id", "block_id", "blockId") ?: return@mapNotNull null
                val text = obj.string("text", "translation", "translated", "target") ?: return@mapNotNull null
                BlockTranslation(
                    id = normalizeId(id, expectedIds),
                    text = text.trim(),
                    kind = BlockKind.parse(obj.string("type", "kind", "category")),
                    speaker = obj.string("speaker")?.takeIf { it.isNotBlank() },
                )
            }
            .distinctBy { it.id }

        val glossary = (root.firstOf("glossary", "terms") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { obj ->
                val source = obj.string("source", "original") ?: return@mapNotNull null
                val target = obj.string("target", "translation") ?: return@mapNotNull null
                GlossaryEntry(
                    source = source.trim(),
                    target = target.trim(),
                    category = obj.string("category", "type").orEmpty(),
                    note = obj.string("note", "notes").orEmpty(),
                    correction = obj.bool("correction") ?: false,
                )
            }

        val characters = (root.firstOf("characters") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { obj ->
                val name = obj.string("name") ?: return@mapNotNull null
                CharacterProfile(
                    name = name.trim(),
                    originalName = obj.string("original_name", "originalName", "original").orEmpty(),
                    gender = obj.string("gender").orEmpty(),
                    speechStyle = obj.string("speech_style", "speechStyle", "speech").orEmpty(),
                    relationships = obj.string("relationships", "relations").orEmpty(),
                )
            }

        return Parsed(
            translations = translations,
            memoryUpdate = MemoryUpdate(glossary, characters),
            chapterSummary = root.string("chapter_summary", "chapterSummary", "summary").orEmpty(),
        )
    }

    private fun parseRoot(raw: String): JsonObject? {
        val cleaned = raw.trim()
            .removePrefix("﻿")
            .let { stripFences(it) }
        tryParse(cleaned)?.let { return it }

        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start >= 0 && end > start) {
            tryParse(cleaned.substring(start, end + 1))?.let { return it }
        }
        if (start >= 0) {
            // Truncated reply: close what we can so earlier complete translations survive.
            tryParse(repairTruncated(cleaned.substring(start)))?.let { return it }
        }
        return null
    }

    private fun tryParse(text: String): JsonObject? = try {
        when (val element = json.parseToJsonElement(text)) {
            is JsonObject -> element
            // Some models return the translations array directly.
            is JsonArray -> JsonObject(mapOf("translations" to element))
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun stripFences(text: String): String {
        val fence = Regex("^```[a-zA-Z]*\\s*\\n?(.*?)\\n?```\\s*$", RegexOption.DOT_MATCHES_ALL)
        return fence.find(text)?.groupValues?.get(1)?.trim() ?: text
    }

    /**
     * Cuts a truncated JSON document back to its last complete array element and closes the open
     * brackets.
     */
    internal fun repairTruncated(text: String): String {
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        var lastSafeCut = -1
        var stackAtCut: List<Char> = emptyList()
        text.forEachIndexed { index, c ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                return@forEachIndexed
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> stack.addLast(c)
                '}', ']' -> {
                    if (stack.isNotEmpty()) stack.removeLast()
                    // An element just closed inside an array: safe place to cut.
                    if (stack.lastOrNull() == '[') {
                        lastSafeCut = index + 1
                        stackAtCut = stack.toList()
                    }
                }
            }
        }
        if (lastSafeCut < 0) return text
        val closing = stackAtCut.reversed().joinToString("") { if (it == '{') "}" else "]" }
        return text.substring(0, lastSafeCut) + closing
    }

    /** Models sometimes answer "p3b1", 3.1 as a number, or drop leading zeros. */
    internal fun normalizeId(id: String, expected: Collection<String>): String {
        val trimmed = id.trim().removePrefix("[").removeSuffix("]")
        if (trimmed in expected) return trimmed
        val digits = Regex("(\\d+)\\D+(\\d+)").find(trimmed)
        if (digits != null) {
            val candidate = "${digits.groupValues[1].toInt()}.${digits.groupValues[2].toInt()}"
            if (candidate in expected || expected.isEmpty()) return candidate
        }
        return trimmed
    }

    private fun JsonObject.firstOf(vararg keys: String): JsonElement? =
        keys.firstNotNullOfOrNull { this[it]?.takeIf { e -> e !is JsonNull } }

    private fun JsonObject.string(vararg keys: String): String? =
        (firstOf(*keys) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.bool(vararg keys: String): Boolean? =
        (firstOf(*keys) as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }
}
