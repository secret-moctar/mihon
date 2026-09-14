package mihon.feature.translate.render

import mihon.feature.translate.model.Box
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pixel level helpers working on ARGB int arrays, independent from Android so they can be tested
 * on the JVM.
 */
class PixelGrid(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(pixels.size == width * height)
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]
    operator fun set(x: Int, y: Int, color: Int) {
        pixels[y * width + x] = color
    }

    fun contains(x: Int, y: Int) = x in 0 until width && y in 0 until height
}

data class BackgroundStats(
    val color: Int,
    /** Share of ring pixels close to [color]. */
    val uniformity: Float,
    val tolerance: Int,
)

object PixelOps {

    fun r(c: Int) = (c shr 16) and 0xFF
    fun g(c: Int) = (c shr 8) and 0xFF
    fun b(c: Int) = c and 0xFF
    fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** Max channel difference: cheap and matches how visible a difference is on flat comic colors. */
    fun distance(a: Int, b: Int): Int = max(abs(r(a) - r(b)), max(abs(g(a) - g(b)), abs(b(a) - b(b))))

    fun luminance(c: Int): Float = (0.2126f * r(c) + 0.7152f * g(c) + 0.0722f * b(c)) / 255f

    /** Pixels on a band of [thickness] just outside [region], clipped to the grid. */
    fun ringPixels(grid: PixelGrid, region: Box, thickness: Int): IntArray {
        val outer = region.expand(thickness).clamp(grid.width, grid.height)
        val result = ArrayList<Int>()
        for (y in outer.top until outer.bottom) {
            for (x in outer.left until outer.right) {
                val inside = x >= region.left && x < region.right && y >= region.top && y < region.bottom
                if (!inside) result += grid[x, y]
            }
        }
        return result.toIntArray()
    }

    /**
     * Dominant color around a text region and how uniform the surroundings are. A speech bubble
     * gives uniformity close to 1, text drawn over artwork gives a low value.
     */
    fun backgroundStats(ring: IntArray): BackgroundStats {
        if (ring.isEmpty()) return BackgroundStats(0xFFFFFFFF.toInt(), 0f, 24)
        // Mode of 4-bit quantized colors, then the mean of the pixels in that bin.
        val counts = IntArray(4096)
        ring.forEach { counts[quant4(it)]++ }
        var bestBin = 0
        for (i in counts.indices) if (counts[i] > counts[bestBin]) bestBin = i
        var sr = 0L
        var sg = 0L
        var sb = 0L
        var n = 0
        ring.forEach {
            if (quant4(it) == bestBin) {
                sr += r(it)
                sg += g(it)
                sb += b(it)
                n++
            }
        }
        val color = rgb((sr / n).toInt(), (sg / n).toInt(), (sb / n).toInt())

        // Tolerance adapts to JPEG noise / light gradients measured on the closest pixels.
        val near = ring.map { distance(it, color) }.filter { it < 48 }.sorted()
        val p90 = if (near.isEmpty()) 0 else near[(near.size * 0.9f).toInt().coerceAtMost(near.size - 1)]
        val tolerance = (p90 + 14).coerceIn(18, 48)
        val close = ring.count { distance(it, color) <= tolerance }
        return BackgroundStats(color, close.toFloat() / ring.size, tolerance)
    }

    private fun quant4(c: Int) = (r(c) shr 4 shl 8) or (g(c) shr 4 shl 4) or (b(c) shr 4)
    private fun quant5(c: Int) = (r(c) shr 3 shl 10) or (g(c) shr 3 shl 5) or (b(c) shr 3)

    /**
     * Marks text strokes inside [region] on a textured background: pixels whose color is rare in the
     * surroundings. Returned mask covers the whole grid.
     */
    fun inkMaskTextured(grid: PixelGrid, region: Box, ring: IntArray, dilate: Int): BooleanArray {
        val hist = IntArray(32768)
        ring.forEach { hist[quant5(it)]++ }
        // Blur the histogram a little by also counting neighbours via a 2x coarser lookup.
        val coarse = IntArray(4096)
        ring.forEach { coarse[quant4(it)]++ }
        val rareLimit = max(2, ring.size / 400)

        val mask = BooleanArray(grid.width * grid.height)
        val area = region.clamp(grid.width, grid.height)
        for (y in area.top until area.bottom) {
            for (x in area.left until area.right) {
                val c = grid[x, y]
                if (hist[quant5(c)] <= rareLimit && coarse[quant4(c)] <= rareLimit * 3) {
                    mask[y * grid.width + x] = true
                }
            }
        }
        return dilate(mask, grid.width, grid.height, dilate, area.expand(dilate).clamp(grid.width, grid.height))
    }

    /** Marks pixels in [region] that differ from a flat background color. */
    fun inkMaskFlat(grid: PixelGrid, region: Box, background: Int, tolerance: Int, dilate: Int): BooleanArray {
        val mask = BooleanArray(grid.width * grid.height)
        val area = region.clamp(grid.width, grid.height)
        for (y in area.top until area.bottom) {
            for (x in area.left until area.right) {
                if (distance(grid[x, y], background) > tolerance) mask[y * grid.width + x] = true
            }
        }
        return dilate(mask, grid.width, grid.height, dilate, area.expand(dilate).clamp(grid.width, grid.height))
    }

    fun rectMask(width: Int, height: Int, region: Box): BooleanArray {
        val mask = BooleanArray(width * height)
        val area = region.clamp(width, height)
        for (y in area.top until area.bottom) {
            for (x in area.left until area.right) mask[y * width + x] = true
        }
        return mask
    }

    fun dilate(mask: BooleanArray, width: Int, height: Int, radius: Int, limit: Box): BooleanArray {
        if (radius <= 0) return mask
        // Separable square dilation: horizontal pass then vertical pass.
        val horizontal = BooleanArray(mask.size)
        for (y in limit.top until limit.bottom) {
            val row = y * width
            var lastOn = Int.MIN_VALUE / 2
            for (x in 0 until width) {
                if (mask[row + x]) lastOn = x
                if (x - lastOn <= radius) horizontal[row + x] = true
            }
            lastOn = Int.MAX_VALUE / 2
            for (x in width - 1 downTo 0) {
                if (mask[row + x]) lastOn = x
                if (lastOn - x <= radius) horizontal[row + x] = true
            }
        }
        val result = BooleanArray(mask.size)
        for (x in limit.left until limit.right) {
            var lastOn = Int.MIN_VALUE / 2
            for (y in 0 until height) {
                if (horizontal[y * width + x]) lastOn = y
                if (y - lastOn <= radius) result[y * width + x] = true
            }
            lastOn = Int.MAX_VALUE / 2
            for (y in height - 1 downTo 0) {
                if (horizontal[y * width + x]) lastOn = y
                if (lastOn - y <= radius) result[y * width + x] = true
            }
        }
        return result
    }

    /**
     * Fills masked pixels from their surroundings with push-pull interpolation: a weighted image
     * pyramid is built from known pixels and holes are filled from coarser levels. Produces smooth
     * results on flat fills and gradients, and softens (rather than smears) texture.
     */
    fun inpaint(grid: PixelGrid, mask: BooleanArray) {
        val w0 = grid.width
        val h0 = grid.height
        if (mask.none { it }) return

        val levels = ArrayList<Level>()
        var level = Level(w0, h0).apply {
            for (i in 0 until w0 * h0) {
                if (!mask[i]) {
                    val c = grid.pixels[i]
                    rs[i] = r(c).toFloat()
                    gs[i] = g(c).toFloat()
                    bs[i] = b(c).toFloat()
                    ws[i] = 1f
                }
            }
        }
        levels += level
        while ((level.width > 1 || level.height > 1) && level.ws.any { it < 1f }) {
            val next = Level((level.width + 1) / 2, (level.height + 1) / 2)
            for (y in 0 until next.height) {
                for (x in 0 until next.width) {
                    var wr = 0f
                    var wg = 0f
                    var wb = 0f
                    var ww = 0f
                    for (dy in 0..1) for (dx in 0..1) {
                        val sx = x * 2 + dx
                        val sy = y * 2 + dy
                        if (sx < level.width && sy < level.height) {
                            val i = sy * level.width + sx
                            val weight = level.ws[i]
                            wr += level.rs[i] * weight
                            wg += level.gs[i] * weight
                            wb += level.bs[i] * weight
                            ww += weight
                        }
                    }
                    val o = y * next.width + x
                    if (ww > 0f) {
                        next.rs[o] = wr / ww
                        next.gs[o] = wg / ww
                        next.bs[o] = wb / ww
                        next.ws[o] = min(1f, ww)
                    }
                }
            }
            levels += next
            level = next
        }

        for (li in levels.size - 2 downTo 0) {
            val fine = levels[li]
            val coarse = levels[li + 1]
            for (y in 0 until fine.height) {
                for (x in 0 until fine.width) {
                    val i = y * fine.width + x
                    val weight = fine.ws[i]
                    if (weight >= 1f) continue
                    // Bilinear sample of the coarse level at this fine pixel center.
                    val cx = ((x + 0.5f) / 2f - 0.5f).coerceIn(0f, (coarse.width - 1).toFloat())
                    val cy = ((y + 0.5f) / 2f - 0.5f).coerceIn(0f, (coarse.height - 1).toFloat())
                    val x0 = cx.toInt()
                    val y0 = cy.toInt()
                    val x1 = min(x0 + 1, coarse.width - 1)
                    val y1 = min(y0 + 1, coarse.height - 1)
                    val fx = cx - x0
                    val fy = cy - y0
                    fun sample(arr: FloatArray): Float {
                        val a = arr[y0 * coarse.width + x0] * (1 - fx) + arr[y0 * coarse.width + x1] * fx
                        val b = arr[y1 * coarse.width + x0] * (1 - fx) + arr[y1 * coarse.width + x1] * fx
                        return a * (1 - fy) + b * fy
                    }
                    fine.rs[i] = fine.rs[i] * weight + sample(coarse.rs) * (1 - weight)
                    fine.gs[i] = fine.gs[i] * weight + sample(coarse.gs) * (1 - weight)
                    fine.bs[i] = fine.bs[i] * weight + sample(coarse.bs) * (1 - weight)
                    fine.ws[i] = 1f
                }
            }
        }

        val base = levels[0]
        for (i in 0 until w0 * h0) {
            if (mask[i]) {
                grid.pixels[i] = rgb(
                    (base.rs[i] + 0.5f).toInt(),
                    (base.gs[i] + 0.5f).toInt(),
                    (base.bs[i] + 0.5f).toInt(),
                )
            }
        }
    }

    private class Level(val width: Int, val height: Int) {
        val rs = FloatArray(width * height)
        val gs = FloatArray(width * height)
        val bs = FloatArray(width * height)
        val ws = FloatArray(width * height)
    }

    /**
     * Grows [start] one pixel row/column at a time, round robin on the four sides, while the new
     * edge is (almost) entirely background and does not touch [obstacles]. Inside a bubble this
     * stops at the outline and gives the space available for the translation.
     */
    fun growFreeRect(
        grid: PixelGrid,
        start: Box,
        background: Int,
        tolerance: Int,
        maxRect: Box,
        obstacles: List<Box>,
        cleanRatio: Float = 0.985f,
    ): Box {
        var l = start.left.coerceAtLeast(0)
        var t = start.top.coerceAtLeast(0)
        var r = start.right.coerceAtMost(grid.width)
        var b = start.bottom.coerceAtMost(grid.height)
        val limit = maxRect.clamp(grid.width, grid.height)
        val active = booleanArrayOf(true, true, true, true)

        fun clean(x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
            val candidate = Box(x0, y0, x1, y1)
            if (obstacles.any { it.intersect(candidate) != null }) return false
            var ok = 0
            var total = 0
            for (y in y0 until y1) for (x in x0 until x1) {
                total++
                if (distance(grid[x, y], background) <= tolerance) ok++
            }
            return total == 0 || ok >= total * cleanRatio
        }

        while (active.any { it }) {
            if (active[0]) active[0] = l - 1 >= limit.left && clean(l - 1, t, l, b).also { if (it) l-- }
            if (active[1]) active[1] = r + 1 <= limit.right && clean(r, t, r + 1, b).also { if (it) r++ }
            if (active[2]) active[2] = t - 1 >= limit.top && clean(l, t - 1, r, t).also { if (it) t-- }
            if (active[3]) active[3] = b + 1 <= limit.bottom && clean(l, b, r, b + 1).also { if (it) b++ }
        }
        return Box(l, t, r, b)
    }

    /**
     * Average color of the ink pixels (text strokes). Used so replacement text keeps the original
     * color, e.g. red shouting or white narration.
     */
    fun inkColor(grid: PixelGrid, mask: BooleanArray, background: Int): Int {
        var sr = 0L
        var sg = 0L
        var sb = 0L
        var n = 0
        // Only strongly contrasting pixels: anti-aliased edges would wash the color out.
        val pixels = ArrayList<Int>()
        for (i in mask.indices) if (mask[i]) pixels += grid.pixels[i]
        if (pixels.isEmpty()) return defaultInkFor(background)
        val distances = pixels.map { distance(it, background) }.sorted()
        val threshold = distances[(distances.size * 0.6f).toInt().coerceAtMost(distances.size - 1)]
        pixels.forEach {
            if (distance(it, background) >= threshold) {
                sr += r(it)
                sg += g(it)
                sb += b(it)
                n++
            }
        }
        if (n == 0) return defaultInkFor(background)
        val ink = rgb((sr / n).toInt(), (sg / n).toInt(), (sb / n).toInt())
        return if (contrast(ink, background) < 2.5f) defaultInkFor(background) else snapToPureIfNear(ink)
    }

    /** Near-black / near-white strokes are rendered pure so text does not look muddy. */
    private fun snapToPureIfNear(c: Int): Int {
        val l = luminance(c)
        val saturation = max(r(c), max(g(c), b(c))) - min(r(c), min(g(c), b(c)))
        return when {
            saturation < 40 && l < 0.25f -> rgb(0, 0, 0)
            saturation < 40 && l > 0.85f -> rgb(255, 255, 255)
            else -> c
        }
    }

    fun defaultInkFor(background: Int): Int = if (luminance(background) > 0.5f) rgb(0, 0, 0) else rgb(255, 255, 255)

    /** WCAG contrast ratio. */
    fun contrast(a: Int, b: Int): Float {
        fun lin(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        fun rel(c: Int) = 0.2126 * lin(r(c)) + 0.7152 * lin(g(c)) + 0.0722 * lin(b(c))
        val la = rel(a)
        val lb = rel(b)
        return ((max(la, lb) + 0.05) / (min(la, lb) + 0.05)).toFloat()
    }

    /** Standard deviation of luminance, a cheap texture measure. */
    fun luminanceStd(values: IntArray): Float {
        if (values.isEmpty()) return 0f
        val lums = values.map { luminance(it) }
        val mean = lums.average().toFloat()
        return sqrt(lums.map { (it - mean) * (it - mean) }.average().toFloat())
    }
}
