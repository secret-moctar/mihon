package mihon.feature.translate.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import mihon.feature.translate.engine.Languages
import mihon.feature.translate.image.PageImage
import mihon.feature.translate.model.BlockKind
import mihon.feature.translate.model.BlockTranslation
import mihon.feature.translate.model.Box
import mihon.feature.translate.model.PageText
import java.io.File
import java.io.OutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Produces the translated page image: erases original text and typesets translations.
 */
class PageRenderer(private val context: Context) {

    enum class FontStyle { COMIC, SANS, SERIF }

    data class Options(
        val targetLanguage: String,
        val translateSfx: Boolean,
        val fontStyle: FontStyle,
        /** Multiplier applied to the automatic font size, from settings. */
        val fontScale: Float,
        val uppercaseLatin: Boolean,
    )

    private val comicTypeface: Typeface by lazy {
        runCatching { Typeface.createFromAsset(context.assets, "translate/fonts/ComicNeue-Bold.ttf") }
            .getOrDefault(Typeface.DEFAULT_BOLD)
    }

    private fun typeface(options: Options): Typeface = when {
        // The comic font has Latin glyphs only; other scripts look best with the system fonts.
        options.fontStyle == FontStyle.COMIC && isLatinScript(options.targetLanguage) -> comicTypeface
        options.fontStyle == FontStyle.SERIF -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
        else -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    fun shouldReplace(kind: BlockKind, options: Options): Boolean = when (kind) {
        BlockKind.CREDIT, BlockKind.NOISE -> false
        BlockKind.SFX -> options.translateSfx
        else -> true
    }

    /**
     * Renders to [output] and returns the file extension used ("jpg" or "png").
     */
    fun render(
        image: PageImage,
        page: PageText,
        translations: List<BlockTranslation>,
        options: Options,
        output: File,
    ): File {
        val byId = translations.associateBy { it.id }
        val targets = page.blocks.filter { block ->
            val t = byId[block.id] ?: return@filter false
            // Erased bubbles must always be filled, even with text identical to the site's.
            t.text.isNotBlank() && (page.preCleaned || (shouldReplace(t.kind, options) && t.text.trim() != block.text.trim()))
        }
        val allBoxes = page.blocks.associate { it.id to it.box }

        val plans = targets.map { block ->
            val crop = BlockPlanner.cropFor(block, image.width, image.height)
            val bitmap = image.decodeRegion(crop)
            try {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                // Region decoders may return a slightly smaller bitmap at image edges.
                val actualCrop = Box(crop.left, crop.top, crop.left + bitmap.width, crop.top + bitmap.height)
                val grid = PixelGrid(bitmap.width, bitmap.height, pixels)
                val others = allBoxes.filterKeys { it != block.id }.values.toList()
                val plan = if (page.preCleaned) {
                    BlockPlanner.planPreCleaned(grid, actualCrop, block, image.width)
                } else {
                    BlockPlanner.plan(grid, actualCrop, block, others, image.width)
                }
                val text = byId.getValue(block.id).text.let { if (options.uppercaseLatin && isLatinScript(options.targetLanguage)) it.uppercase() else it }
                plan to typeset(text, plan, options)
            } finally {
                bitmap.recycle()
            }
        }

        val tmp = File(output.parentFile, output.name + ".tmp")
        tmp.outputStream().buffered().use { stream ->
            if (image.pixelCount <= MAX_SINGLE_BITMAP_PIXELS) {
                composeSingle(image, plans, stream)
            } else {
                composeBanded(image, plans, stream)
            }
        }
        if (!tmp.renameTo(output)) {
            tmp.copyTo(output, overwrite = true)
            tmp.delete()
        }
        return output
    }

    private class Typeset(val layout: StaticLayout, val paint: TextPaint, val left: Float, val top: Float, val strokeWidth: Float)

    private fun typeset(text: String, plan: BlockPlan, options: Options): Typeset {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = typeface(options)
            color = plan.inkColor
        }
        val rtl = Languages.isRtl(options.targetLanguage)
        val maxFont = plan.maxFontPx * options.fontScale
        val minFont = min(maxFont, plan.minFontPx)

        fun build(box: Box, size: Float): StaticLayout {
            paint.textSize = size
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, box.width))
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setTextDirection(if (rtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setIncludePad(false)
                .setLineSpacing(0f, if (rtl) 1.05f else 0.95f)
                .setBreakStrategy(LineBreaker.BREAK_STRATEGY_BALANCED)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL_FAST)
                    } else {
                        setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                    }
                }
                .build()
        }

        fun fits(box: Box, size: Float): Boolean {
            val layout = build(box, size)
            if (layout.height > box.height) return false
            // A word wider than the box would be split mid-word.
            val longestWord = text.split(Regex("\\s+")).maxOfOrNull { paint.measureText(it) } ?: 0f
            return longestWord <= box.width
        }

        fun largestFitting(box: Box): Float? {
            if (!fits(box, minFont)) return null
            var lo = minFont
            var hi = maxFont
            repeat(9) {
                val mid = (lo + hi) / 2
                if (fits(box, mid)) lo = mid else hi = mid
            }
            return lo
        }

        var box = plan.textBox
        var size = largestFitting(box)
        if (size == null) {
            box = plan.overflowBox
            size = largestFitting(box) ?: minFont
        }
        val layout = build(box, size)
        val strokeWidth = if (plan.outlineColor != null) max(1.5f, size * 0.16f) else 0f
        val left = box.left + (box.width - layout.width) / 2f
        val top = box.centerY - layout.height / 2f
        return Typeset(layout, paint, left, top, strokeWidth)
    }

    private fun composeSingle(image: PageImage, plans: List<Pair<BlockPlan, Typeset>>, stream: OutputStream) {
        val bitmap = image.decodeRegion(Box(0, 0, image.width, image.height))
        try {
            val canvas = Canvas(bitmap)
            plans.forEach { (plan, _) -> applyPatch(bitmap, plan, 0) }
            plans.forEach { (plan, typeset) -> drawText(canvas, plan, typeset, 0f) }
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        } finally {
            bitmap.recycle()
        }
    }

    /** For pages too tall to hold in memory: decode, edit and encode horizontal bands. */
    private fun composeBanded(image: PageImage, plans: List<Pair<BlockPlan, Typeset>>, stream: OutputStream) {
        PngStreamWriter(stream, image.width, image.height).use { writer ->
            var top = 0
            val row = IntArray(image.width * BAND_HEIGHT)
            while (top < image.height) {
                val bottom = min(image.height, top + BAND_HEIGHT)
                val band = image.decodeRegion(Box(0, top, image.width, bottom))
                try {
                    val canvas = Canvas(band)
                    plans.forEach { (plan, _) -> applyPatch(band, plan, top) }
                    plans.forEach { (plan, typeset) ->
                        val extent = typesetBounds(typeset)
                        if (extent.bottom >= top && extent.top <= bottom) drawText(canvas, plan, typeset, top.toFloat())
                    }
                    band.getPixels(row, 0, image.width, 0, 0, image.width, bottom - top)
                    writer.writeRows(row, 0, bottom - top)
                } finally {
                    band.recycle()
                }
                top = bottom
            }
        }
    }

    private fun typesetBounds(t: Typeset): Box {
        val pad = ceil(t.strokeWidth).toInt() + 2
        return Box(
            t.left.toInt() - pad,
            t.top.toInt() - pad,
            (t.left + t.layout.width).roundToInt() + pad,
            (t.top + t.layout.height).roundToInt() + pad,
        )
    }

    private fun applyPatch(target: Bitmap, plan: BlockPlan, bandTop: Int) {
        val box = plan.patchBox
        val targetBox = Box(0, bandTop, target.width, bandTop + target.height)
        val visible = box.intersect(targetBox) ?: return
        val srcOffset = (visible.top - box.top) * box.width + (visible.left - box.left)
        target.setPixels(plan.patchPixels, srcOffset, box.width, visible.left, visible.top - bandTop, visible.width, visible.height)
    }

    private fun drawText(canvas: Canvas, plan: BlockPlan, typeset: Typeset, bandTop: Float) {
        canvas.save()
        canvas.translate(typeset.left, typeset.top - bandTop)
        val paint = typeset.paint
        if (plan.outlineColor != null) {
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = typeset.strokeWidth
            paint.color = plan.outlineColor
            typeset.layout.draw(canvas)
        }
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
        paint.color = plan.inkColor
        typeset.layout.draw(canvas)
        canvas.restore()
    }

    companion object {
        /** ~64 MB as ARGB; above that pages are encoded in bands. */
        private const val MAX_SINGLE_BITMAP_PIXELS = 16_000_000L
        private const val BAND_HEIGHT = 2048
        private const val JPEG_QUALITY = 92

        private val nonLatin = setOf("ar", "fa", "ur", "he", "ru", "uk", "ja", "ko", "zh", "th", "hi")

        fun isLatinScript(language: String) = language.substringBefore('-') !in nonLatin
    }
}
