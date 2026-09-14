package mihon.feature.translate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import mihon.feature.translate.model.Box
import mihon.feature.translate.model.PageText
import mihon.feature.translate.model.TextBlock
import mihon.feature.translate.model.TextLine
import java.net.URLDecoder
import kotlin.math.roundToInt

/**
 * Text boxes that machine-translation sites (Manhuarm and similar) send with each page. Their page
 * images have the bubbles already erased, so this data, not OCR, is the only source of the text.
 */
data class SiteDialog(
    val box: Box,
    /** Every text the site provides for the box, by key ("text", "en", "fr"...). */
    val texts: Map<String, String>,
)

object SiteDialogs {

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    /** Which of the site's texts is used, and its language. */
    data class Choice(val key: String, val language: String)

    /** Parses the `#[...]` fragment of a page URL (or the stored copy of it). */
    fun parse(fragment: String): List<SiteDialog> {
        val raw = fragment.trim().removePrefix("#").let { if (it.startsWith("%5B", ignoreCase = true)) URLDecoder.decode(it, "UTF-8") else it }
        val root = try {
            json.parseToJsonElement(raw) as? JsonArray
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return root.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val x = obj.number("x") ?: return@mapNotNull null
            val y = obj.number("y") ?: return@mapNotNull null
            val w = obj.number("_width") ?: obj.number("width") ?: return@mapNotNull null
            val h = obj.number("_height") ?: obj.number("height") ?: return@mapNotNull null
            val texts = (obj["textByLanguage"] as? JsonObject)
                ?.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { key to it } }
                ?.toMap()
                .orEmpty()
            if (texts.isEmpty() || w <= 0f || h <= 0f) return@mapNotNull null
            SiteDialog(Box(x.roundToInt(), y.roundToInt(), (x + w).roundToInt(), (y + h).roundToInt()), texts)
        }
    }

    /**
     * Prefers the text in the comic's original script (Korean/Chinese/Japanese) so it is translated
     * once from the source, and falls back to the site's English only when nothing else is there.
     */
    fun choose(dialogs: List<SiteDialog>): Choice? {
        val keys = dialogs.flatMap { it.texts.keys }.distinct()
        if (keys.isEmpty()) return null
        val scripts = keys.associateWith { key -> script(dialogs.mapNotNull { it.texts[key] }.joinToString(" ")) }
        scripts.entries.firstOrNull { it.value == "ko" }?.let { return Choice(it.key, "ko") }
        scripts.entries.firstOrNull { it.value == "ja" }?.let { return Choice(it.key, "ja") }
        scripts.entries.firstOrNull { it.value == "zh" }?.let { return Choice(it.key, "zh") }
        val key = keys.firstOrNull { it == "en" } ?: keys.firstOrNull { it == "text" } ?: keys.first()
        val language = if (key.length in 2..5 && key != "text") key.substringBefore('-') else "en"
        return Choice(key, language)
    }

    fun toPageText(dialogs: List<SiteDialog>, choice: Choice, pageIndex: Int, width: Int, height: Int): PageText {
        val blocks = dialogs.mapNotNull { dialog ->
            val text = dialog.texts[choice.key] ?: return@mapNotNull null
            dialog to text
        }
            .sortedWith(compareBy({ it.first.box.top }, { it.first.box.left }))
            .mapIndexed { i, (dialog, text) ->
                TextBlock(
                    id = "$pageIndex.${i + 1}",
                    lines = listOf(TextLine(text, dialog.box.clamp(width, height))),
                    language = choice.language,
                )
            }
        return PageText(pageIndex, width, height, blocks, preCleaned = true)
    }

    /** Short description of the data for the diagnostics log. */
    fun describe(dialogs: List<SiteDialog>): String = dialogs.flatMap { it.texts.entries }
        .groupBy { it.key }
        .entries.joinToString { (key, values) -> "$key='${values.first().value.take(24)}'" }

    internal fun script(text: String): String {
        var hangul = 0
        var kana = 0
        var han = 0
        var latin = 0
        text.forEach { c ->
            when (c) {
                in '가'..'힣', in 'ᄀ'..'ᇿ', in '㄰'..'㆏' -> hangul++
                in '぀'..'ヿ' -> kana++
                in '一'..'鿿', in '㐀'..'䶿' -> han++
                else -> if (c.isLetter()) latin++
            }
        }
        val total = hangul + kana + han + latin
        if (total == 0) return "none"
        return when {
            hangul * 3 >= total -> "ko"
            (kana + han) * 3 >= total && kana > 0 -> "ja"
            han * 3 >= total -> "zh"
            else -> "latin"
        }
    }

    private fun JsonObject.number(key: String): Float? = (this[key] as? JsonPrimitive)?.floatOrNull
}
