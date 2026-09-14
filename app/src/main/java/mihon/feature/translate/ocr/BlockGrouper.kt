package mihon.feature.translate.ocr

import mihon.feature.translate.model.Box
import mihon.feature.translate.model.TextBlock
import mihon.feature.translate.model.TextLine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Turns loose OCR lines into bubble-sized blocks and orders them for reading.
 *
 * OCR engines have their own notion of blocks, but they break at tile borders and often merge
 * neighbouring bubbles, so grouping is redone from lines with rules tuned for comics.
 */
object BlockGrouper {

    data class RawLine(
        val text: String,
        val box: Box,
        /** 0..1, or -1 when unknown. */
        val confidence: Float = -1f,
    )

    fun group(
        lines: List<RawLine>,
        pageIndex: Int,
        language: String,
        rightToLeft: Boolean = language == "ja",
    ): List<TextBlock> {
        val clean = lines
            .map { it.copy(text = it.text.trim()) }
            .filter { it.text.isNotEmpty() && it.box.width > 2 && it.box.height > 2 }
            .filterNot { it.confidence in 0f..0.35f && it.text.length <= 2 }
            .filterNot { isJunk(it.text) }
        if (clean.isEmpty()) return emptyList()

        val n = clean.size
        val parent = IntArray(n) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (belongTogether(clean[i], clean[j])) union(i, j)
            }
        }

        val groups = clean.indices.groupBy { find(it) }.values.map { indices -> indices.map { clean[it] } }

        return groups
            .map { group ->
                val vertical = isVerticalGroup(group)
                val ordered = if (vertical) {
                    // Vertical CJK columns read right to left, top to bottom.
                    group.sortedWith(compareByDescending<RawLine> { it.box.centerX }.thenBy { it.box.top })
                } else {
                    group.sortedWith(compareBy<RawLine> { it.box.top }.thenBy { it.box.left })
                }
                ordered to vertical
            }
            .sortedWith(readingOrder(rightToLeft))
            .mapIndexed { index, (group, vertical) ->
                TextBlock(
                    id = "$pageIndex.${index + 1}",
                    lines = group.map { TextLine(it.text, it.box) },
                    language = language,
                    vertical = vertical,
                )
            }
    }

    private fun belongTogether(a: RawLine, b: RawLine): Boolean {
        val aVertical = a.isVertical()
        val bVertical = b.isVertical()
        if (aVertical != bVertical) return false

        return if (!aVertical) {
            val hA = a.box.height
            val hB = b.box.height
            val ratio = max(hA, hB).toFloat() / min(hA, hB)
            if (ratio > 1.8f) return false
            val lineHeight = (hA + hB) / 2f
            val gapY = a.box.gapY(b.box)
            val overlapX = -a.box.gapX(b.box)
            // Same line split in two pieces by OCR.
            val sameRow = gapY < -lineHeight * 0.5f && a.box.gapX(b.box) < lineHeight * 1.2f
            // Stacked lines of one bubble: close vertically and horizontally overlapping or centered.
            val centerDelta = abs(a.box.centerX - b.box.centerX)
            val stacked = gapY < lineHeight * 0.9f &&
                (overlapX > min(a.box.width, b.box.width) * 0.25f || centerDelta < lineHeight * 1.5f)
            sameRow || stacked
        } else {
            val wA = a.box.width
            val wB = b.box.width
            val ratio = max(wA, wB).toFloat() / min(wA, wB)
            if (ratio > 1.8f) return false
            val colWidth = (wA + wB) / 2f
            val gapX = a.box.gapX(b.box)
            val overlapY = -a.box.gapY(b.box)
            val sameColumn = gapX < -colWidth * 0.5f && a.box.gapY(b.box) < colWidth * 1.2f
            val adjacent = gapX < colWidth * 0.9f && overlapY > min(a.box.height, b.box.height) * 0.25f
            sameColumn || adjacent
        }
    }

    private fun RawLine.isVertical(): Boolean = box.height > box.width * 1.6f && text.length > 1

    private fun isVerticalGroup(group: List<RawLine>): Boolean = group.count { it.isVertical() } * 2 > group.size

    /**
     * Top to bottom by rows; inside a row left to right (or right to left for manga). Blocks whose
     * vertical ranges overlap a lot are considered the same row.
     */
    private fun readingOrder(rightToLeft: Boolean): Comparator<Pair<List<RawLine>, Boolean>> =
        Comparator { a, b ->
            val boxA = a.first.map { it.box }.reduce(Box::union)
            val boxB = b.first.map { it.box }.reduce(Box::union)
            val overlap = -boxA.gapY(boxB)
            val sameRow = overlap > min(boxA.height, boxB.height) * 0.5f
            when {
                sameRow && rightToLeft -> boxB.centerX.compareTo(boxA.centerX)
                sameRow -> boxA.centerX.compareTo(boxB.centerX)
                else -> boxA.top.compareTo(boxB.top)
            }
        }

    private val junkPattern = Regex("^[\\p{P}\\p{S}\\s\\d]{1,3}$")

    /** Page numbers, lone punctuation and similar noise. */
    fun isJunk(text: String): Boolean = junkPattern.matches(text) && !text.any { it == '!' || it == '?' || it == '…' }

    /**
     * Removes duplicates produced by overlapping OCR tiles. When two lines cover the same area,
     * the one farther from its tile edge (so less likely to be cut) wins.
     */
    fun dedupeTileLines(lines: List<Pair<RawLine, Int>>): List<RawLine> {
        // second = distance from the line to the closest horizontal tile border
        val sorted = lines.sortedByDescending { it.second }
        val kept = mutableListOf<RawLine>()
        for ((line, _) in sorted) {
            val duplicate = kept.any { other ->
                val inter = line.box.intersectionArea(other.box)
                inter > 0 && inter >= min(line.box.area, other.box.area) * 0.5
            }
            if (!duplicate) kept += line
        }
        return kept
    }
}
