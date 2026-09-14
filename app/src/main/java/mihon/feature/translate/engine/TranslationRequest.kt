package mihon.feature.translate.engine

import mihon.feature.translate.model.BlockTranslation
import mihon.feature.translate.model.MemoryUpdate
import mihon.feature.translate.model.PageText
import mihon.feature.translate.model.SeriesMemory

data class TranslationRequest(
    /** BCP-47-ish code, or "auto". */
    val sourceLanguage: String,
    val targetLanguage: String,
    val seriesTitle: String,
    val memory: SeriesMemory,
    val chapterName: String,
    val chapterNumber: Double,
    /** (chapter number, summary) for chapters read before this one. */
    val previousChapterSummaries: List<Pair<String, String>>,
    /** Summary of this chapter up to the pages in [pages]. */
    val chapterSummarySoFar: String,
    /** Last translated lines right before [pages], for continuity of tone and running jokes. */
    val recentLines: List<RecentLine>,
    val pages: List<PageText>,
    /** Pages after [pages] (may include the next chapter) given only as context. */
    val lookahead: List<PageText>,
    /** Optional crops of text blocks so vision models can fix OCR mistakes. */
    val images: List<BlockImage> = emptyList(),
    val translateSfx: Boolean,
    /** Extra instructions entered by the user in settings, applied to every series. */
    val globalInstructions: String = "",
) {
    val blockCount: Int get() = pages.sumOf { it.blocks.size }
}

data class RecentLine(val pageIndex: Int, val original: String, val translation: String, val speaker: String?)

class BlockImage(val blockId: String, val jpeg: ByteArray)

data class TranslationResult(
    val translations: List<BlockTranslation>,
    val memoryUpdate: MemoryUpdate,
    val chapterSummary: String,
    val engineName: String,
)

interface TranslationEngine {
    /** Human readable, e.g. "Gemini (gemini-flash-latest)". */
    val name: String

    /** Whether the engine uses [TranslationRequest.memory] and friends. Offline engines do not. */
    val contextAware: Boolean

    val supportsImages: Boolean

    /** Rough character budget of one request, used to size chunks. */
    val maxInputChars: Int

    /**
     * Pages worth sending per request. Engines with small daily request quotas ask for bigger
     * batches so the quota covers more chapters; 0 keeps the user's setting.
     */
    val preferredPagesPerRequest: Int get() = 0

    suspend fun translate(request: TranslationRequest): TranslationResult

    /** Lists model ids usable with this engine, for the settings picker. */
    suspend fun listModels(): List<String> = emptyList()
}

class TranslationException(
    message: String,
    /** Rate limited or temporarily unavailable; worth retrying later or on another engine. */
    val retryable: Boolean,
    /** Suggested wait before retrying, in milliseconds, if the service said so. */
    val retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
