package mihon.feature.translate.engine

object Languages {

    data class Language(val code: String, val englishName: String, val nativeName: String, val rtl: Boolean = false)

    /** Languages offered as translation targets. Any code works with LLM engines. */
    val targets = listOf(
        Language("en", "English", "English"),
        Language("ar", "Arabic", "العربية", rtl = true),
        Language("fr", "French", "Français"),
        Language("es", "Spanish", "Español"),
        Language("pt", "Portuguese (Brazil)", "Português"),
        Language("de", "German", "Deutsch"),
        Language("it", "Italian", "Italiano"),
        Language("ru", "Russian", "Русский"),
        Language("tr", "Turkish", "Türkçe"),
        Language("id", "Indonesian", "Bahasa Indonesia"),
        Language("vi", "Vietnamese", "Tiếng Việt"),
        Language("th", "Thai", "ไทย"),
        Language("hi", "Hindi", "हिन्दी"),
        Language("fa", "Persian", "فارسی", rtl = true),
        Language("ur", "Urdu", "اردو", rtl = true),
        Language("he", "Hebrew", "עברית", rtl = true),
        Language("pl", "Polish", "Polski"),
        Language("uk", "Ukrainian", "Українська"),
        Language("ja", "Japanese", "日本語"),
        Language("ko", "Korean", "한국어"),
        Language("zh", "Chinese (Simplified)", "简体中文"),
    )

    /** Languages the on-device OCR can read. */
    val sources = listOf(
        Language("auto", "Automatic", "Automatic"),
        Language("ko", "Korean", "한국어"),
        Language("zh", "Chinese", "中文"),
        Language("ja", "Japanese", "日本語"),
        Language("en", "English", "English"),
        Language("fr", "French", "Français"),
        Language("es", "Spanish", "Español"),
        Language("id", "Indonesian", "Bahasa Indonesia"),
        Language("vi", "Vietnamese", "Tiếng Việt"),
        Language("pt", "Portuguese", "Português"),
    )

    fun byCode(code: String): Language? =
        targets.firstOrNull { it.code == code } ?: sources.firstOrNull { it.code == code }

    fun englishName(code: String): String = byCode(code)?.englishName ?: code

    fun isRtl(code: String): Boolean = byCode(code)?.rtl == true

    /**
     * Maps a Mihon source language (e.g. "ko", "zh-Hans", "all", "ja") to an OCR language, or null
     * when it cannot be determined.
     */
    fun fromSourceLang(sourceLang: String?): String? {
        val base = sourceLang?.lowercase()?.substringBefore('-') ?: return null
        return when (base) {
            "ko", "zh", "ja", "en", "fr", "es", "id", "vi", "pt" -> base
            else -> null
        }
    }
}
