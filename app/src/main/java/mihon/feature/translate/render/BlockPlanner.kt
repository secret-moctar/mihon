package mihon.feature.translate.render

import mihon.feature.translate.model.Box
import mihon.feature.translate.model.TextBlock
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Everything needed to replace one text block, in page coordinates.
 */
class BlockPlan(
    val blockId: String,
    /** Region whose pixels are replaced by [patchPixels] (original text erased). */
    val patchBox: Box,
    val patchPixels: IntArray,
    /** Preferred area for the translated text. */
    val textBox: Box,
    /** Largest area the text may use if it does not fit in [textBox]. */
    val overflowBox: Box,
    val inkColor: Int,
    /** Outline drawn around glyphs for text over artwork, null in clean bubbles. */
    val outlineColor: Int?,
    val maxFontPx: Float,
    val minFontPx: Float,
    val onArtwork: Boolean,
)

/**
 * Analyzes the pixels around a text block to erase it cleanly and find room for the translation.
 */
object BlockPlanner {

    /** Share of surrounding pixels that must match the background to count as a clean bubble. */
    private const val FLAT_UNIFORMITY = 0.88f

    fun lineHeight(block: TextBlock): Int = block.medianLineHeight.coerceAtLeast(6)

    private fun padding(block: TextBlock): Int = max(2, (lineHeight(block) * 0.2f).roundToInt())

    /** Area to decode around a block: enough to measure the background and grow the text box. */
    fun cropFor(block: TextBlock, imageWidth: Int, imageHeight: Int): Box {
        val lh = lineHeight(block)
        val region = block.box.expand(padding(block))
        val mx = max((region.width * 0.9f).roundToInt(), lh * 4)
        val my = max((region.height * 0.6f).roundToInt(), lh * 3)
        return region.expand(mx, my).clamp(imageWidth, imageHeight)
    }

    /**
     * @param grid pixels of [crop]; modified in place (text erased).
     * @param others boxes of the other blocks on the page, in page coordinates.
     */
    fun plan(
        grid: PixelGrid,
        crop: Box,
        block: TextBlock,
        others: List<Box>,
        imageWidth: Int,
    ): BlockPlan {
        val lh = lineHeight(block)
        val pad = padding(block)
        val region = block.box.expand(pad).clamp(imageWidth, Int.MAX_VALUE).offset(-crop.left, -crop.top)
            .clamp(grid.width, grid.height)
        val obstacles = others
            .map { it.expand(pad).offset(-crop.left, -crop.top) }
            .filter { it.intersect(Box(0, 0, grid.width, grid.height)) != null }
            // Never treat overlapping OCR boxes as obstacles to ourselves.
            .filter { it.intersect(region) == null }

        val ring = PixelOps.ringPixels(grid, region, max(3, (lh * 0.3f).roundToInt()))
        val stats = PixelOps.backgroundStats(ring)
        val flat = stats.uniformity >= FLAT_UNIFORMITY

        val ink: Int
        val outline: Int?
        val mask: BooleanArray
        if (flat) {
            val strokes = PixelOps.inkMaskFlat(grid, region, stats.color, stats.tolerance, dilate = 0)
            ink = PixelOps.inkColor(grid, strokes, stats.color)
            outline = null
            // Whole padded line boxes plus any stray stroke inside the region, slightly dilated to
            // remove JPEG halos around letters.
            val lines = BooleanArray(grid.width * grid.height)
            block.lines.forEach { line ->
                val box = line.box.expand(pad).offset(-crop.left, -crop.top).clamp(grid.width, grid.height)
                for (y in box.top until box.bottom) for (x in box.left until box.right) lines[y * grid.width + x] = true
            }
            val strayDilated = PixelOps.dilate(
                strokes, grid.width, grid.height, max(1, lh / 12),
                region.expand(lh / 12 + 1).clamp(grid.width, grid.height),
            )
            mask = BooleanArray(lines.size) { lines[it] || strayDilated[it] }
        } else {
            val strokes = PixelOps.inkMaskTextured(grid, region, ring, dilate = 0)
            val colors = fillAndOutline(grid, strokes)
            ink = colors.first
            outline = colors.second
            mask = PixelOps.dilate(
                strokes, grid.width, grid.height, max(2, (lh * 0.14f).roundToInt()),
                region.expand(lh / 4 + 2).clamp(grid.width, grid.height),
            )
        }

        PixelOps.inpaint(grid, mask)

        val maskBounds = bounds(mask, grid.width, grid.height) ?: region
        val patchLocal = maskBounds
        val patchPixels = IntArray(patchLocal.width * patchLocal.height)
        for (y in 0 until patchLocal.height) {
            System.arraycopy(grid.pixels, (patchLocal.top + y) * grid.width + patchLocal.left, patchPixels, y * patchLocal.width, patchLocal.width)
        }

        val textLocal: Box
        val overflowLocal: Box
        if (flat) {
            val maxGrow = region.expand(max((region.width * 0.8f).roundToInt(), lh * 4), max(lh * 2, (region.height * 0.5f).roundToInt()))
                .clamp(grid.width, grid.height)
            val grown = PixelOps.growFreeRect(grid, region, stats.color, stats.tolerance, maxGrow, obstacles)
            // Keep a margin from the bubble outline.
            val inset = max(2, lh / 5)
            textLocal = grown.expand(-inset).takeIf { it.width >= region.width / 2 && it.height >= lh } ?: region
            overflowLocal = grown
        } else {
            val extra = max(lh, (region.width * 0.15f).roundToInt())
            textLocal = region.expand(extra, 0).clamp(grid.width, grid.height)
            overflowLocal = region.expand(extra * 2, lh * 2).clamp(grid.width, grid.height)
        }

        val maxFont = if (block.vertical) {
            // Vertical CJK column width is about one glyph wide.
            lh * 0.75f
        } else {
            lh * 0.92f
        }
        val minFont = min(maxFont, max(9f, imageWidth / 85f))

        return BlockPlan(
            blockId = block.id,
            patchBox = patchLocal.offset(crop.left, crop.top),
            patchPixels = patchPixels,
            textBox = textLocal.offset(crop.left, crop.top),
            overflowBox = overflowLocal.offset(crop.left, crop.top),
            inkColor = ink,
            outlineColor = outline,
            maxFontPx = maxFont,
            minFontPx = minFont,
            onArtwork = !flat,
        )
    }

    /**
     * For pages whose text the site already erased: nothing is erased, the text box is the site's
     * dialog box, grown inside the bubble when it sits on a clean background.
     */
    fun planPreCleaned(grid: PixelGrid, crop: Box, block: TextBlock, imageWidth: Int): BlockPlan {
        val region = block.box.offset(-crop.left, -crop.top).clamp(grid.width, grid.height)
        val step = max(1, min(region.width, region.height) / 60)
        val inside = ArrayList<Int>()
        for (y in region.top until region.bottom step step) {
            for (x in region.left until region.right step step) inside += grid[x, y]
        }
        val stats = PixelOps.backgroundStats(inside.toIntArray())
        val flat = stats.uniformity >= 0.8f

        val textLocal: Box
        val overflowLocal: Box
        if (flat) {
            val maxGrow = region.expand((region.width * 0.25f).roundToInt(), (region.height * 0.25f).roundToInt())
                .clamp(grid.width, grid.height)
            val grown = PixelOps.growFreeRect(grid, region, stats.color, stats.tolerance, maxGrow, emptyList(), cleanRatio = 0.97f)
            val inset = max(2, min(grown.width, grown.height) / 16)
            textLocal = grown.expand(-inset).takeIf { it.width >= 8 && it.height >= 8 } ?: region
            overflowLocal = grown
        } else {
            textLocal = region
            overflowLocal = region.expand((region.width * 0.15f).roundToInt(), (region.height * 0.3f).roundToInt())
                .clamp(grid.width, grid.height)
        }

        val maxFont = min(textLocal.height * 0.5f, imageWidth / 13f).coerceAtLeast(10f)
        val minFont = min(maxFont, max(10f, imageWidth / 75f))
        return BlockPlan(
            blockId = block.id,
            patchBox = Box(0, 0, 0, 0),
            patchPixels = IntArray(0),
            textBox = textLocal.offset(crop.left, crop.top),
            overflowBox = overflowLocal.offset(crop.left, crop.top),
            inkColor = if (flat) PixelOps.defaultInkFor(stats.color) else PixelOps.rgb(255, 255, 255),
            outlineColor = if (flat) null else PixelOps.rgb(0, 0, 0),
            maxFontPx = maxFont,
            minFontPx = minFont,
            onArtwork = !flat,
        )
    }

    /**
     * Text over artwork is usually light fill with a dark outline or the reverse. Splits the stroke
     * pixels by luminance: the larger group is the fill.
     */
    private fun fillAndOutline(grid: PixelGrid, strokes: BooleanArray): Pair<Int, Int> {
        var light = 0
        var dark = 0
        var lr = 0L
        var lg = 0L
        var lb = 0L
        var dr = 0L
        var dg = 0L
        var db = 0L
        for (i in strokes.indices) {
            if (!strokes[i]) continue
            val c = grid.pixels[i]
            if (PixelOps.luminance(c) >= 0.5f) {
                light++
                lr += PixelOps.r(c)
                lg += PixelOps.g(c)
                lb += PixelOps.b(c)
            } else {
                dark++
                dr += PixelOps.r(c)
                dg += PixelOps.g(c)
                db += PixelOps.b(c)
            }
        }
        val white = PixelOps.rgb(255, 255, 255)
        val black = PixelOps.rgb(0, 0, 0)
        if (light == 0 && dark == 0) return white to black
        return if (light >= dark) {
            val fill = PixelOps.rgb((lr / light).toInt(), (lg / light).toInt(), (lb / light).toInt())
            (if (PixelOps.luminance(fill) > 0.85f) white else fill) to black
        } else {
            val fill = PixelOps.rgb((dr / dark).toInt(), (dg / dark).toInt(), (db / dark).toInt())
            (if (PixelOps.luminance(fill) < 0.2f) black else fill) to white
        }
    }

    private fun bounds(mask: BooleanArray, width: Int, height: Int): Box? {
        var l = width
        var t = height
        var r = -1
        var b = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (mask[row + x]) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    if (y > b) b = y
                }
            }
        }
        return if (r < 0) null else Box(l, t, r + 1, b + 1)
    }
}
