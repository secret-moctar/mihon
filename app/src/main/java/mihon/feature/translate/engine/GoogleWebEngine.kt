package mihon.feature.translate.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import mihon.feature.translate.model.BlockKind
import mihon.feature.translate.model.BlockTranslation
import mihon.feature.translate.model.MemoryUpdate
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Google Translate through the free endpoint used by Chrome's translate extension. Needs no key and
 * translates sentence by sentence well, but has no memory of the story: it is the no-setup
 * fallback when no AI engine is configured or available.
 */
class GoogleWebEngine(private val client: OkHttpClient) : TranslationEngine {

    override val name = "Google Translate (free)"
    override val contextAware = false
    override val supportsImages = false
    override val maxInputChars = Int.MAX_VALUE

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        val blocks = request.pages.flatMap { it.blocks }
        val translations = mutableListOf<BlockTranslation>()
        val toSend = mutableListOf<Pair<String, String>>()
        blocks.forEach { block ->
            val text = block.text.trim()
            when (classify(text)) {
                BlockKind.NOISE -> translations += BlockTranslation(block.id, text, BlockKind.NOISE)
                BlockKind.CREDIT -> translations += BlockTranslation(block.id, text, BlockKind.CREDIT)
                else -> toSend += block.id to normalizeForTranslation(text)
            }
        }

        // One request per chunk keeps URLs and bodies small; bubbles are short.
        val chunks = mutableListOf<MutableList<Pair<String, String>>>()
        toSend.forEach { item ->
            val current = chunks.lastOrNull()
            if (current == null || current.size >= MAX_BLOCKS_PER_REQUEST || current.sumOf { it.second.length } + item.second.length > MAX_CHARS_PER_REQUEST) {
                chunks += mutableListOf(item)
            } else {
                current += item
            }
        }
        chunks.forEach { chunk ->
            val results = request(chunk.map { it.second }, request.sourceLanguage, request.targetLanguage)
            chunk.forEachIndexed { i, (id, _) ->
                results.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { translations += BlockTranslation(id, it.trim()) }
            }
        }
        return TranslationResult(translations, MemoryUpdate(), chapterSummary = "", engineName = name)
    }

    private suspend fun request(texts: List<String>, source: String, target: String): List<String> = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().apply { texts.forEach { add("q", it) } }.build()
        val url = "https://clients5.google.com/translate_a/t?client=dict-chrome-ex" +
            "&sl=${googleCode(source)}&tl=${googleCode(target)}"
        val call = client.newCall(Request.Builder().url(url).post(body).header("User-Agent", USER_AGENT).build())
        val response = try {
            call.execute()
        } catch (e: IOException) {
            throw TranslationException("Google Translate: network error ${e.message}", retryable = true, cause = e)
        }
        response.use {
            val text = it.body.string()
            if (it.code == 429 || text.startsWith("<")) {
                throw TranslationException("Google Translate is limiting requests", retryable = true, retryAfterMs = 60_000)
            }
            if (!it.isSuccessful) {
                throw TranslationException("Google Translate failed (${it.code})", retryable = it.code >= 500)
            }
            parse(text)
        }
    }

    companion object {
        private const val MAX_BLOCKS_PER_REQUEST = 40
        private const val MAX_CHARS_PER_REQUEST = 4000
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0 Mobile Safari/537.36"

        private val json = Json { isLenient = true }

        /** Replies are `["a","b"]` with a fixed source language, `[["a","ko"],["b","ko"]]` with auto. */
        internal fun parse(text: String): List<String> {
            val root = try {
                json.parseToJsonElement(text)
            } catch (e: Exception) {
                throw TranslationException("Google Translate returned an unexpected reply", retryable = true, cause = e)
            }
            val items = when (root) {
                is JsonArray -> root
                is JsonPrimitive -> return listOfNotNull(root.contentOrNull)
                else -> return emptyList()
            }
            return items.map { item ->
                when (item) {
                    is JsonPrimitive -> item.contentOrNull.orEmpty()
                    is JsonArray -> (item.firstOrNull() as? JsonPrimitive)?.contentOrNull.orEmpty()
                    else -> ""
                }
            }
        }

        internal fun googleCode(code: String): String = when (code) {
            "auto", "" -> "auto"
            "zh" -> "zh-CN"
            "he" -> "iw"
            "pt" -> "pt"
            else -> code.substringBefore('-')
        }

        private val creditPattern = Regex(
            "(https?://|www\\.|\\.com\\b|\\.net\\b|\\.org\\b|discord|patreon|scanlat|scans?\\b|raw\\s*provider|translator|proofread|typeset)",
            RegexOption.IGNORE_CASE,
        )

        internal fun classify(text: String): BlockKind = when {
            text.count { it.isLetter() } == 0 -> BlockKind.NOISE
            creditPattern.containsMatchIn(text) -> BlockKind.CREDIT
            else -> BlockKind.DIALOGUE
        }

        /**
         * English comics are written in capitals, which machine translation treats as acronyms or
         * shouting. Sentence case gives much better results.
         */
        internal fun normalizeForTranslation(text: String): String {
            val letters = text.filter { it.isLetter() && it.code < 0x250 }
            if (letters.length < 4 || letters.count { it.isUpperCase() } < letters.length * 0.8) return text
            val lower = text.lowercase()
            val sb = StringBuilder(lower.length)
            var capitalize = true
            lower.forEach { c ->
                if (capitalize && c.isLetter()) {
                    sb.append(c.uppercaseChar())
                    capitalize = false
                } else {
                    sb.append(c)
                }
                if (c == '.' || c == '!' || c == '?') capitalize = true
            }
            // The pronoun "I" stays capital.
            return sb.toString().replace(Regex("\\bi\\b"), "I")
        }
    }
}
