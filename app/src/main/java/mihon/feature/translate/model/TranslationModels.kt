package mihon.feature.translate.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Axis-aligned rectangle in image pixel coordinates.
 */
@Serializable
data class Box(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Long get() = width.toLong().coerceAtLeast(0) * height.toLong().coerceAtLeast(0)
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun union(other: Box) = Box(
        minOf(left, other.left),
        minOf(top, other.top),
        maxOf(right, other.right),
        maxOf(bottom, other.bottom),
    )

    fun intersect(other: Box): Box? {
        val l = maxOf(left, other.left)
        val t = maxOf(top, other.top)
        val r = minOf(right, other.right)
        val b = minOf(bottom, other.bottom)
        return if (l < r && t < b) Box(l, t, r, b) else null
    }

    fun intersectionArea(other: Box): Long = intersect(other)?.area ?: 0L

    fun expand(dx: Int, dy: Int = dx) = Box(left - dx, top - dy, right + dx, bottom + dy)

    fun offset(dx: Int, dy: Int) = Box(left + dx, top + dy, right + dx, bottom + dy)

    fun clamp(maxWidth: Int, maxHeight: Int) = Box(
        left.coerceIn(0, maxWidth),
        top.coerceIn(0, maxHeight),
        right.coerceIn(0, maxWidth),
        bottom.coerceIn(0, maxHeight),
    )

    /** Gap between two boxes on each axis, negative when they overlap on that axis. */
    fun gapX(other: Box): Int = maxOf(left, other.left) - minOf(right, other.right)
    fun gapY(other: Box): Int = maxOf(top, other.top) - minOf(bottom, other.bottom)
}

@Serializable
data class TextLine(
    val text: String,
    val box: Box,
)

/**
 * A group of text lines that belong together, usually one speech bubble or caption.
 */
@Serializable
data class TextBlock(
    /** Stable id within a page, e.g. "3.2" for the second block of page index 3. */
    val id: String,
    val lines: List<TextLine>,
    /** Detected script/language hint from OCR, may be empty. */
    val language: String = "",
    val vertical: Boolean = false,
) {
    val box: Box get() = lines.map { it.box }.reduce(Box::union)
    val text: String get() = lines.joinToString(if (language in CJK_NO_SPACE) "" else " ") { it.text.trim() }

    val medianLineHeight: Int
        get() {
            val sizes = lines.map { if (vertical) it.box.width else it.box.height }.sorted()
            return sizes[sizes.size / 2]
        }

    companion object {
        val CJK_NO_SPACE = setOf("ja", "zh")
    }
}

@Serializable
data class PageText(
    val pageIndex: Int,
    val width: Int,
    val height: Int,
    val blocks: List<TextBlock>,
    /** The site already erased the text from the image; translations are drawn without erasing. */
    val preCleaned: Boolean = false,
)

@Serializable
enum class BlockKind {
    @SerialName("dialogue")
    DIALOGUE,

    @SerialName("thought")
    THOUGHT,

    @SerialName("narration")
    NARRATION,

    @SerialName("sfx")
    SFX,

    @SerialName("sign")
    SIGN,

    /** Scanlator credits, watermarks, site names: left untouched. */
    @SerialName("credit")
    CREDIT,

    /** OCR noise the model decided is not real text. */
    @SerialName("noise")
    NOISE,

    ;

    companion object {
        fun parse(value: String?): BlockKind = when (value?.lowercase()?.trim()) {
            "thought", "monologue" -> THOUGHT
            "narration", "caption", "narrative" -> NARRATION
            "sfx", "sound", "onomatopoeia", "effect" -> SFX
            "sign", "text", "label", "title" -> SIGN
            "credit", "credits", "watermark", "ad" -> CREDIT
            "noise", "garbage", "ignore", "none" -> NOISE
            else -> DIALOGUE
        }
    }
}

@Serializable
data class BlockTranslation(
    val id: String,
    val text: String,
    val kind: BlockKind = BlockKind.DIALOGUE,
    val speaker: String? = null,
)

/**
 * Everything known about one page after translation. Saved so the page can be re-rendered
 * (different font, cache eviction) without calling the translation service again.
 */
@Serializable
data class PageTranslation(
    val pageIndex: Int,
    /** Hash of the original image bytes, used to detect that a source replaced the image. */
    val imageHash: String,
    val text: PageText,
    val translations: List<BlockTranslation>,
    /** Name of the engine that produced this, for display and debugging. */
    val engine: String = "",
)

@Serializable
data class ChapterTranslationFile(
    val version: Int = 1,
    val chapterId: Long,
    val chapterName: String,
    val chapterNumber: Double,
    val targetLanguage: String,
    val pages: Map<Int, PageTranslation> = emptyMap(),
    /** Running summary of the chapter produced by the model. */
    val summary: String = "",
)
