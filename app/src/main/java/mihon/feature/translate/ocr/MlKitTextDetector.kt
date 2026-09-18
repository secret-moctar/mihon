package mihon.feature.translate.ocr

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import mihon.feature.translate.engine.await
import mihon.feature.translate.image.PageImage
import mihon.feature.translate.model.Box
import mihon.feature.translate.model.PageText
import kotlin.math.max
import kotlin.math.min

/**
 * On-device OCR with Google ML Kit (bundled models, works offline and without Play Services).
 * Gives accurate text positions, which is what the typesetter needs; the translation model then
 * fixes the occasional misread character using context.
 */
class MlKitTextDetector {

    private val recognizers = HashMap<String, TextRecognizer>()

    @Synchronized
    private fun recognizer(script: String): TextRecognizer = recognizers.getOrPut(script) {
        when (script) {
            "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.Builder().build())
        }
    }

    /**
     * Picks the recognizer for "auto" by trying the CJK ones on a slice of the page and keeping the
     * one that reads the most native characters.
     */
    suspend fun detectScript(image: PageImage): String? {
        // Tall webtoon strips often start with empty art or credits: sample several slices.
        val sliceHeight = min(image.height, max(image.width * 2, 1200))
        val tops = if (image.height <= sliceHeight) {
            listOf(0)
        } else {
            listOf(0, (image.height - sliceHeight) / 2, image.height - sliceHeight)
        }
        val scores = HashMap<String, Int>()
        var latinLetters = 0
        for (top in tops) {
            val bitmap = image.decodeRegion(Box(0, top, image.width, top + sliceHeight), sampleFor(image.width))
            try {
                val input = InputImage.fromBitmap(bitmap, 0)
                for (script in listOf("ko", "zh", "ja")) {
                    val text = await(recognizer(script).process(input)).text
                    scores[script] = (scores[script] ?: 0) + when (script) {
                        "ko" -> text.count { it in '가'..'힣' } * 2
                        // Kanji is shared with Japanese; kana decides between them.
                        "zh" -> text.count { it in '一'..'鿿' }
                        else -> text.count { it in '぀'..'ヿ' } * 3
                    }
                    if (script == "ko") latinLetters += text.count { it in 'A'..'Z' || it in 'a'..'z' }
                }
            } finally {
                bitmap.recycle()
            }
        }
        val best = scores.maxByOrNull { it.value }
        return when {
            best != null && best.value >= 4 -> best.key
            latinLetters >= 12 -> "latin"
            else -> null
        }
    }

    suspend fun detect(image: PageImage, pageIndex: Int, language: String): PageText {
        val script = when (language) {
            "ko", "zh", "ja" -> language
            else -> "latin"
        }
        val recognizer = recognizer(script)
        val sample = sampleFor(image.width)

        // Tiles about 1.6x as tall as wide keep text large relative to the tile, which ML Kit needs.
        val tileHeight = min(image.height, max((image.width * 1.6f).toInt(), 1000))
        val overlap = if (tileHeight < image.height) (tileHeight * 0.25f).toInt() else 0
        val lines = mutableListOf<Pair<BlockGrouper.RawLine, Int>>()

        var top = 0
        while (top < image.height) {
            val bottom = min(image.height, top + tileHeight)
            val tile = Box(0, top, image.width, bottom)
            val bitmap = image.decodeRegion(tile, sample)
            try {
                val result = await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                collectLines(result, tile, sample, isFirst = top == 0, isLast = bottom == image.height, into = lines)
            } finally {
                bitmap.recycle()
            }
            if (bottom == image.height) break
            top = bottom - overlap
        }

        val deduped = if (overlap > 0) BlockGrouper.dedupeTileLines(lines) else lines.map { it.first }
        val blocks = BlockGrouper.group(deduped, pageIndex, if (script == "latin") language else script)
        return PageText(pageIndex, image.width, image.height, blocks)
    }

    private fun collectLines(
        result: Text,
        tile: Box,
        sample: Int,
        isFirst: Boolean,
        isLast: Boolean,
        into: MutableList<Pair<BlockGrouper.RawLine, Int>>,
    ) {
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val rect = line.boundingBox ?: continue
                val box = Box(
                    rect.left * sample,
                    rect.top * sample + tile.top,
                    rect.right * sample,
                    rect.bottom * sample + tile.top,
                )
                // Distance to the nearest border shared with another tile; outer page edges don't count.
                val distTop = if (isFirst) Int.MAX_VALUE else box.top - tile.top
                val distBottom = if (isLast) Int.MAX_VALUE else tile.bottom - box.bottom
                into += BlockGrouper.RawLine(line.text, box, line.confidence) to min(distTop, distBottom)
            }
        }
    }

    /** Very wide pages are scanned at half resolution; comic text stays legible. */
    private fun sampleFor(width: Int) = if (width > 2400) 2 else 1

    @Synchronized
    fun close() {
        recognizers.values.forEach { it.close() }
        recognizers.clear()
    }
}
