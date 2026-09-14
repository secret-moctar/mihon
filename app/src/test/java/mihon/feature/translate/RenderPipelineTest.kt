package mihon.feature.translate

import mihon.feature.translate.model.Box
import mihon.feature.translate.model.TextBlock
import mihon.feature.translate.model.TextLine
import mihon.feature.translate.render.BlockPlanner
import mihon.feature.translate.render.PixelGrid
import mihon.feature.translate.render.PixelOps
import mihon.feature.translate.render.PngStreamWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * Exercises the erase/typeset planning on synthetic comic pages. Set TRANSLATE_TEST_OUT to a
 * directory to also write before/after PNGs for visual inspection.
 */
class RenderPipelineTest {

    private val outDir = System.getenv("TRANSLATE_TEST_OUT")?.let { File(it).apply { mkdirs() } }

    private fun gradientPage(w: Int, h: Int): PixelGrid {
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            PixelOps.rgb(120 + x * 60 / w, 150 + y * 50 / h, 190)
        }
        return PixelGrid(w, h, pixels)
    }

    private fun fillEllipse(g: PixelGrid, cx: Int, cy: Int, rx: Int, ry: Int, color: Int, outline: Int?) {
        for (y in cy - ry - 3..cy + ry + 3) for (x in cx - rx - 3..cx + rx + 3) {
            if (!g.contains(x, y)) continue
            val d = ((x - cx) * (x - cx)).toDouble() / (rx * rx) + ((y - cy) * (y - cy)).toDouble() / (ry * ry)
            if (outline != null && d <= 1.08 && d > 0.93) g[x, y] = outline else if (d <= 0.93) g[x, y] = color
        }
    }

    /** Fake glyphs: thin strokes inside a line box. */
    private fun drawStrokes(g: PixelGrid, box: Box, color: Int, seed: Long) {
        val random = Random(seed)
        var x = box.left
        while (x < box.right - 4) {
            val w = 2 + random.nextInt(2)
            for (yy in box.top until box.bottom) for (xx in x until minOf(box.right, x + w)) g[xx, yy] = color
            // horizontal bar in the middle of some glyphs
            if (random.nextBoolean()) {
                val y = (box.top + box.bottom) / 2
                for (xx in x until minOf(box.right, x + 9)) g[xx, y] = color
            }
            x += 6 + random.nextInt(4)
        }
    }

    private fun save(g: PixelGrid, name: String) {
        val dir = outDir ?: return
        File(dir, name).outputStream().use { out ->
            PngStreamWriter(out, g.width, g.height).use { it.writeRows(g.pixels, 0, g.height) }
        }
    }

    @Test
    fun `text in a speech bubble is erased flat and text area stays inside the bubble`() {
        val page = gradientPage(700, 900)
        val white = PixelOps.rgb(255, 255, 255)
        val black = PixelOps.rgb(0, 0, 0)
        fillEllipse(page, 350, 300, 220, 110, white, black)
        val lines = listOf(Box(280, 250, 420, 276), Box(260, 285, 440, 311), Box(300, 320, 400, 346))
        lines.forEachIndexed { i, b -> drawStrokes(page, b, PixelOps.rgb(20, 20, 20), i.toLong()) }
        save(page, "bubble_before.png")

        val block = TextBlock("0.1", lines.map { TextLine("x", it) }, "ko")
        val crop = BlockPlanner.cropFor(block, page.width, page.height)
        val grid = crop(page, crop)
        val plan = BlockPlanner.plan(grid, crop, block, emptyList(), page.width)

        assertTrue(!plan.onArtwork, "bubble should be detected as a flat background")
        assertNull(plan.outlineColor)
        assertEquals(PixelOps.rgb(0, 0, 0), plan.inkColor)

        // After erasing, every pixel of the original text lines is (near) white.
        applyPatch(page, plan.patchBox, plan.patchPixels)
        lines.forEach { b ->
            for (y in b.top until b.bottom) for (x in b.left until b.right) {
                assertTrue(PixelOps.distance(page[x, y], white) <= 12, "left over ink at $x,$y")
            }
        }
        // The outline is untouched.
        assertEquals(black, page[350, 300 - 110 + 1].let { if (PixelOps.distance(it, black) < 30) black else it })

        // The text box grew beyond the original lines but stays within the ellipse.
        val union = lines.reduce(Box::union)
        assertTrue(plan.textBox.width >= union.width, "text box ${plan.textBox} narrower than $union")
        corners(plan.textBox).forEach { (x, y) ->
            val d = ((x - 350.0) * (x - 350.0)) / (220 * 220) + ((y - 300.0) * (y - 300.0)) / (110 * 110)
            assertTrue(d < 1.0, "text box corner $x,$y outside bubble (d=$d)")
        }
        markBox(page, plan.textBox, PixelOps.rgb(255, 0, 0))
        save(page, "bubble_after.png")
    }

    @Test
    fun `outlined text over artwork is inpainted and gets an outline color`() {
        val page = gradientPage(600, 600)
        val random = Random(7)
        // Busy artwork: random soft blobs.
        repeat(400) {
            val cx = random.nextInt(600)
            val cy = random.nextInt(600)
            val c = PixelOps.rgb(80 + random.nextInt(100), 60 + random.nextInt(120), 90 + random.nextInt(100))
            fillEllipse(page, cx, cy, 6 + random.nextInt(14), 6 + random.nextInt(14), c, null)
        }
        val white = PixelOps.rgb(255, 255, 255)
        val black = PixelOps.rgb(0, 0, 0)
        val lineBox = Box(180, 280, 420, 320)
        // White glyphs with a black outline.
        drawStrokes(page, lineBox.expand(2), black, 3)
        drawStrokes(page, lineBox, white, 3)
        save(page, "artwork_before.png")

        val block = TextBlock("0.1", listOf(TextLine("x", lineBox)), "ko")
        val crop = BlockPlanner.cropFor(block, page.width, page.height)
        val plan = BlockPlanner.plan(crop(page, crop), crop, block, emptyList(), page.width)

        assertTrue(plan.onArtwork, "busy background must not be treated as a bubble")
        assertEquals(white, plan.inkColor)
        assertEquals(black, plan.outlineColor)

        applyPatch(page, plan.patchBox, plan.patchPixels)
        val remainingWhite = (lineBox.top until lineBox.bottom).sumOf { y ->
            (lineBox.left until lineBox.right).count { x -> PixelOps.distance(page[x, y], white) < 20 }
        }
        assertTrue(remainingWhite < lineBox.area * 0.02, "white strokes left: $remainingWhite")
        save(page, "artwork_after.png")
    }

    @Test
    fun `inpaint reproduces a smooth gradient`() {
        val g = gradientPage(200, 200)
        val expected = g.pixels.copyOf()
        val hole = Box(60, 60, 140, 140)
        val mask = PixelOps.rectMask(200, 200, hole)
        for (y in hole.top until hole.bottom) for (x in hole.left until hole.right) g[x, y] = PixelOps.rgb(255, 0, 255)
        PixelOps.inpaint(g, mask)
        var maxError = 0
        for (i in expected.indices) maxError = maxOf(maxError, PixelOps.distance(expected[i], g.pixels[i]))
        assertTrue(maxError <= 16, "max error $maxError")
    }

    @Test
    fun `streaming png decodes to the same pixels`() {
        val w = 37
        val h = 53
        val random = Random(1)
        val pixels = IntArray(w * h) { PixelOps.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        val out = ByteArrayOutputStream()
        PngStreamWriter(out, w, h).use { writer ->
            writer.writeRows(pixels, 0, 20)
            writer.writeRows(pixels, 20 * w, h - 20)
        }
        val image = ImageIO.read(out.toByteArray().inputStream())
        assertEquals(w, image.width)
        assertEquals(h, image.height)
        for (y in 0 until h) for (x in 0 until w) {
            assertEquals(pixels[y * w + x] and 0xFFFFFF, image.getRGB(x, y) and 0xFFFFFF, "pixel $x,$y")
        }
    }

    @Test
    fun `contrast helper matches wcag`() {
        assertTrue(abs(PixelOps.contrast(PixelOps.rgb(0, 0, 0), PixelOps.rgb(255, 255, 255)) - 21f) < 0.01f)
    }

    private fun crop(page: PixelGrid, box: Box): PixelGrid {
        val pixels = IntArray(box.width * box.height)
        for (y in 0 until box.height) System.arraycopy(page.pixels, (box.top + y) * page.width + box.left, pixels, y * box.width, box.width)
        return PixelGrid(box.width, box.height, pixels)
    }

    private fun applyPatch(page: PixelGrid, box: Box, patch: IntArray) {
        for (y in 0 until box.height) System.arraycopy(patch, y * box.width, page.pixels, (box.top + y) * page.width + box.left, box.width)
    }

    private fun corners(b: Box) = listOf(b.left to b.top, b.right - 1 to b.top, b.left to b.bottom - 1, b.right - 1 to b.bottom - 1)

    private fun markBox(g: PixelGrid, b: Box, color: Int) {
        for (x in b.left until b.right) {
            g[x, b.top] = color
            g[x, b.bottom - 1] = color
        }
        for (y in b.top until b.bottom) {
            g[b.left, y] = color
            g[b.right - 1, y] = color
        }
    }
}
