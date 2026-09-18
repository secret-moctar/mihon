package mihon.feature.translate

/**
 * Some sites (Manhuarm, SnowMTL, SolarMTL...) serve the raw page and let the extension paint their
 * own machine translation on top. Their page URLs carry the dialog boxes as a `#[...]` fragment.
 *
 * When this app translates such a series, it downloads the untouched raw page instead, so text is
 * read in its original language and translated once, rather than re-translating the site's
 * machine translation.
 */
object MachineTranslatedPages {

    private val pattern = Regex("\\.(webp|png|jpe?g|avif|gif)#\\[", RegexOption.IGNORE_CASE)

    fun isComposed(imageUrl: String?): Boolean = imageUrl != null && pattern.containsMatchIn(imageUrl)

    /** The dialog data carried by a composed page URL. */
    fun fragment(imageUrl: String?): String? = if (isComposed(imageUrl)) imageUrl!!.substringAfter('#') else null

    /** URL of the raw page, or null when [imageUrl] is an ordinary page. */
    fun rawUrl(imageUrl: String?): String? = if (isComposed(imageUrl)) imageUrl!!.substringBefore('#') else null
}
