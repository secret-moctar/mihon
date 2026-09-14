package mihon.feature.translate.engine

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import mihon.feature.translate.model.BlockKind
import mihon.feature.translate.model.BlockTranslation
import mihon.feature.translate.model.MemoryUpdate
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google ML Kit on-device translation. Free, offline after a ~30 MB model download per language,
 * but translates each bubble in isolation: no context, names and tone are not kept consistent.
 * Used as a fallback when no online engine is available.
 */
class MlKitOfflineEngine(
    private val wifiOnlyDownload: Boolean,
) : TranslationEngine {

    override val name = "On-device (ML Kit)"
    override val contextAware = false
    override val supportsImages = false
    override val maxInputChars = Int.MAX_VALUE

    private val translators = HashMap<Pair<String, String>, Translator>()

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        val target = TranslateLanguage.fromLanguageTag(request.targetLanguage)
            ?: throw TranslationException("Offline translation does not support ${request.targetLanguage}", retryable = false)

        val source = resolveSource(request)
            ?: throw TranslationException("Could not detect the page language for offline translation", retryable = false)

        val translations = if (source == target) {
            request.pages.flatMap { page -> page.blocks.map { BlockTranslation(it.id, it.text) } }
        } else {
            val translator = translator(source, target)
            // The download waits silently for Wi-Fi; never hold up the whole queue behind it.
            withTimeoutOrNull(MODEL_DOWNLOAD_TIMEOUT_MS) { await(translator.downloadModelIfNeeded(conditions())) }
                ?: throw TranslationException(
                    "Offline language model not downloaded yet (needs Wi-Fi)",
                    retryable = false,
                )
            request.pages.flatMap { page ->
                page.blocks.map { block ->
                    val text = block.text
                    val kind = if (text.isNoise()) BlockKind.NOISE else BlockKind.DIALOGUE
                    val translated = if (kind == BlockKind.NOISE) text else await(translator.translate(text))
                    BlockTranslation(block.id, translated, kind)
                }
            }
        }
        return TranslationResult(translations, MemoryUpdate(), chapterSummary = "", engineName = name)
    }

    private suspend fun resolveSource(request: TranslationRequest): String? {
        if (request.sourceLanguage != "auto") {
            return TranslateLanguage.fromLanguageTag(request.sourceLanguage)
        }
        val sample = request.pages.flatMap { it.blocks }.joinToString(" ") { it.text }.take(2000)
        if (sample.isBlank()) return null
        val client = LanguageIdentification.getClient()
        return try {
            val tag = await(client.identifyLanguage(sample))
            if (tag == "und") null else TranslateLanguage.fromLanguageTag(tag)
        } finally {
            client.close()
        }
    }

    private fun conditions(): DownloadConditions = DownloadConditions.Builder()
        .apply { if (wifiOnlyDownload) requireWifi() }
        .build()

    @Synchronized
    private fun translator(source: String, target: String): Translator =
        translators.getOrPut(source to target) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(target)
                    .build(),
            )
        }

    @Synchronized
    fun close() {
        translators.values.forEach { it.close() }
        translators.clear()
    }

    private fun String.isNoise(): Boolean = count { it.isLetter() } < 1

    private companion object {
        const val MODEL_DOWNLOAD_TIMEOUT_MS = 90_000L
    }
}

suspend fun <T> await(task: Task<T>): T = suspendCancellableCoroutine { continuation ->
    task.addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    task.addOnFailureListener { e ->
        if (continuation.isActive) {
            continuation.resumeWithException(
                TranslationException(e.message ?: "On-device operation failed", retryable = false, cause = e),
            )
        }
    }
    task.addOnCanceledListener { continuation.cancel() }
}
