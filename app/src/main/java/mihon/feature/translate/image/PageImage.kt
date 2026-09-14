package mihon.feature.translate.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import androidx.core.graphics.createBitmap
import ca.mpreg.imagedecoder.ImageDecoder
import mihon.feature.translate.model.Box
import java.io.Closeable
import java.security.MessageDigest

/**
 * Random access to parts of one page image without holding the full bitmap, so very tall webtoon
 * strips can be processed on low memory phones.
 */
class PageImage private constructor(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    private val regionDecoder: BitmapRegionDecoder?,
) : Closeable {

    /** Full decode used when the format has no region decoder (AVIF, JXL, animated...). */
    private var fallbackBitmap: Bitmap? = null

    val hash: String by lazy { sha1(bytes) }

    val pixelCount: Long get() = width.toLong() * height

    /**
     * Decodes [region] (clamped to the image) scaled down by [sampleSize]. The returned bitmap is
     * owned by the caller.
     */
    @Synchronized
    fun decodeRegion(region: Box, sampleSize: Int = 1): Bitmap {
        val clamped = region.clamp(width, height)
        require(clamped.width > 0 && clamped.height > 0) { "Empty region $region" }
        val decoder = regionDecoder
        if (decoder != null) {
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            decoder.decodeRegion(Rect(clamped.left, clamped.top, clamped.right, clamped.bottom), options)
                ?.let { return it.ensureMutable() }
        }
        val full = fallbackBitmap ?: decodeFull().also { fallbackBitmap = it }
        val cropped = Bitmap.createBitmap(full, clamped.left, clamped.top, clamped.width, clamped.height)
        return if (sampleSize > 1) {
            Bitmap.createScaledBitmap(cropped, clamped.width / sampleSize, clamped.height / sampleSize, true)
                .also { if (it !== cropped) cropped.recycle() }
        } else {
            cropped.ensureMutable()
        }
    }

    private fun decodeFull(): Bitmap {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it }
        ImageDecoder.new(bytes.inputStream()).use { decoder ->
            val result = decoder.decode()
            return createBitmap(result.width, result.height).also { bitmap ->
                result.image.rewind()
                bitmap.copyPixelsFromBuffer(result.image)
            }
        }
    }

    private fun Bitmap.ensureMutable(): Bitmap = if (isMutable && config == Bitmap.Config.ARGB_8888) {
        this
    } else {
        copy(Bitmap.Config.ARGB_8888, true).also { recycle() }
    }

    @Synchronized
    override fun close() {
        regionDecoder?.recycle()
        fallbackBitmap?.recycle()
        fallbackBitmap = null
    }

    companion object {
        fun open(bytes: ByteArray): PageImage {
            val regionDecoder = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
                } else {
                    @Suppress("DEPRECATION")
                    BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
                }
            } catch (_: Exception) {
                null
            }
            if (regionDecoder != null) {
                return PageImage(bytes, regionDecoder.width, regionDecoder.height, regionDecoder)
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                return PageImage(bytes, bounds.outWidth, bounds.outHeight, null)
            }
            val size = ImageDecoder.new(bytes.inputStream()).use { decoder ->
                val result = decoder.decode()
                result.width to result.height
            }
            return PageImage(bytes, size.first, size.second, null)
        }

        fun sha1(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
