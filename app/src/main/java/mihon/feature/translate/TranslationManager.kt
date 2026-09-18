package mihon.feature.translate

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import mihon.feature.translate.engine.AnthropicEngine
import mihon.feature.translate.engine.GeminiEngine
import mihon.feature.translate.engine.GoogleWebEngine
import mihon.feature.translate.engine.MlKitOfflineEngine
import mihon.feature.translate.engine.OpenAiCompatibleEngine
import mihon.feature.translate.engine.ResultValidator
import mihon.feature.translate.engine.TranslationEngine
import mihon.feature.translate.engine.TranslationException
import mihon.feature.translate.engine.TranslationRequest
import mihon.feature.translate.engine.TranslationResult
import mihon.feature.translate.image.PageImage
import mihon.feature.translate.model.PageTranslation
import mihon.feature.translate.ocr.MlKitTextDetector
import mihon.feature.translate.render.PageRenderer
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

@Inject
@SingleIn(AppScope::class)
class TranslationManager(
    private val context: Context,
    val preferences: TranslationPreferences,
    private val networkHelper: NetworkHelper,
    private val getManga: GetManga,
    private val getChapter: GetChapter,
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
) {
    internal val dispatcher = Dispatchers.Default
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    val store = TranslationStore(context)
    internal val detector by lazy { MlKitTextDetector() }
    internal val renderer by lazy { PageRenderer(context) }

    /** OCR and image analysis are memory heavy; one page at a time. */
    internal val ocrPermits = Semaphore(1)
    internal val renderPermits = Semaphore(2)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Short user facing notices (rate limits, engine switches, failures). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    // region Engines

    private val httpClient by lazy {
        networkHelper.client.newBuilder()
            .readTimeout(3.minutes.toJavaDuration())
            .callTimeout(5.minutes.toJavaDuration())
            .build()
    }

    private val engineLock = Any()
    private var engineCacheKey: String? = null
    private var engineCache: List<TranslationEngine> = emptyList()

    /** Engine name -> time (ms) until which it is skipped after running out of quota. */
    private val cooldowns = ConcurrentHashMap<String, Long>()

    fun engines(): List<TranslationEngine> = synchronized(engineLock) {
        val configs = preferences.engineConfigs()
        val key = configs.toString() + preferences.offlineModelsWifiOnly.get()
        if (key != engineCacheKey) {
            engineCache.filterIsInstance<MlKitOfflineEngine>().forEach { it.close() }
            engineCache = configs.filter { it.isUsable }.flatMap { config ->
                val engine = createEngine(config)
                val model = config.model.ifBlank { GeminiEngine.DEFAULT_MODEL }
                if (config.type == EngineType.GEMINI && !model.contains("lite")) {
                    // Each Gemini model has its own free quota: fall back to Lite on the same key.
                    listOf(engine, createEngine(config.copy(model = GeminiEngine.LITE_MODEL)))
                } else {
                    listOf(engine)
                }
            }
            engineCacheKey = key
        }
        engineCache
    }

    fun createEngine(config: EngineConfig): TranslationEngine = when (config.type) {
        EngineType.GEMINI -> GeminiEngine(
            httpClient,
            config.keyList,
            config.model.ifBlank { GeminiEngine.DEFAULT_MODEL },
            config.baseUrl.ifBlank { GeminiEngine.DEFAULT_BASE_URL },
        )
        EngineType.OPENAI_COMPATIBLE -> OpenAiCompatibleEngine(httpClient, config.keyList, config.baseUrl, config.model, config.vision)
        EngineType.ANTHROPIC -> AnthropicEngine(httpClient, config.keyList, config.model.ifBlank { AnthropicEngine.DEFAULT_MODEL })
        EngineType.GOOGLE_WEB -> GoogleWebEngine(httpClient)
        EngineType.OFFLINE -> MlKitOfflineEngine(preferences.offlineModelsWifiOnly.get())
    }

    fun hasUsableEngine(): Boolean = preferences.engineConfigs().any { it.isUsable }

    /** Pages per request for the engine that will most likely answer next. */
    internal fun pagesPerRequest(): Int {
        val setting = preferences.pagesPerRequest.get().coerceIn(1, 20)
        val preferred = engines().firstOrNull { !isCoolingDown(it) }?.preferredPagesPerRequest ?: 0
        return max(setting, preferred).coerceAtMost(20)
    }

    internal fun primaryEngineSupportsImages(): Boolean =
        engines().firstOrNull { !isCoolingDown(it) }?.supportsImages == true

    private fun isCoolingDown(engine: TranslationEngine) = (cooldowns[engine.name] ?: 0L) > System.currentTimeMillis()

    /** Serializes model calls so free rate limits are not hit by parallel requests. */
    private val requestMutex = Mutex()

    internal suspend fun translate(request: TranslationRequest): TranslationResult = requestMutex.withLock {
        val engines = engines()
        if (engines.isEmpty()) throw TranslationException("No translation engine configured", retryable = false)

        var lastError: Exception? = null
        for ((position, engine) in engines.withIndex()) {
            if (isCoolingDown(engine) && position < engines.lastIndex) continue
            var attempt = 0
            while (attempt < MAX_ATTEMPTS) {
                attempt++
                try {
                    val result = engine.translate(request)
                    val fixed = completeMissing(engine, request, result)
                    val verdict = ResultValidator.check(request, fixed)
                    if (!verdict.ok) {
                        // A bad answer from this engine: try the next one and rest this one briefly.
                        TranslateLog.w("${engine.name} rejected: ${verdict.reason}")
                        cooldowns[engine.name] = System.currentTimeMillis() + QUALITY_COOLDOWN_MS
                        lastError = TranslationException("${engine.name}: ${verdict.reason}", retryable = false)
                        break
                    }
                    TranslateLog.i("${engine.name} answer accepted (${fixed.translations.size} blocks)")
                    if (position > 0 && engines.first().let { isCoolingDown(it) }) {
                        notify("Using ${engine.name} while ${engines.first().name} is unavailable")
                    }
                    return@withLock fixed
                } catch (e: TranslationException) {
                    lastError = e
                    TranslateLog.w("${engine.name} attempt $attempt: ${e.message}")
                    if (!e.retryable) {
                        // Bad key or unknown model: don't retry it on every page for a while.
                        cooldowns[engine.name] = System.currentTimeMillis() + BROKEN_ENGINE_COOLDOWN_MS
                        break
                    }
                    val wait = e.retryAfterMs ?: (2_000L shl (attempt - 1))
                    if (wait > MAX_INLINE_WAIT_MS) {
                        cooldowns[engine.name] = System.currentTimeMillis() + wait
                        break
                    }
                    delay(wait)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    lastError = e
                    TranslateLog.e("${engine.name} failed", e)
                    break
                }
            }
            if (attempt >= MAX_ATTEMPTS) {
                cooldowns[engine.name] = System.currentTimeMillis() + 60.seconds.inWholeMilliseconds
            }
        }
        throw lastError ?: TranslationException("Translation failed", retryable = false)
    }

    /**
     * Models sometimes skip a bubble. Asks once more for just the missing blocks so no speech bubble
     * is left untranslated.
     */
    private suspend fun completeMissing(
        engine: TranslationEngine,
        request: TranslationRequest,
        result: TranslationResult,
    ): TranslationResult {
        val done = result.translations.map { it.id }.toSet()
        val missingPages = request.pages
            .map { page -> page.copy(blocks = page.blocks.filter { it.id !in done }) }
            .filter { it.blocks.isNotEmpty() }
        if (missingPages.isEmpty() || !engine.contextAware) return result
        return try {
            val retry = engine.translate(
                request.copy(pages = missingPages, lookahead = emptyList(), images = emptyList()),
            )
            result.copy(
                translations = result.translations + retry.translations.filter { it.id !in done },
                memoryUpdate = result.memoryUpdate.copy(
                    glossary = result.memoryUpdate.glossary + retry.memoryUpdate.glossary,
                    characters = result.memoryUpdate.characters + retry.memoryUpdate.characters,
                ),
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            result
        }
    }

    private val lastMessages = ConcurrentHashMap<String, Long>()

    private fun notify(message: String) {
        val now = System.currentTimeMillis()
        val last = lastMessages[message] ?: 0L
        if (now - last > 60_000) {
            lastMessages[message] = now
            _messages.tryEmit(message)
        }
    }

    internal fun reportError(message: String) = notify("Translation: $message")

    // endregion

    // region Rendering

    fun renderOptions(targetLanguage: String) = PageRenderer.Options(
        targetLanguage = targetLanguage,
        translateSfx = preferences.translateSfx.get(),
        fontStyle = preferences.fontStyle.get(),
        fontScale = preferences.fontScale.get() / 100f,
        uppercaseLatin = preferences.uppercaseLatin.get(),
    )

    fun renderKey(translation: PageTranslation, options: PageRenderer.Options): String {
        val content = buildString {
            append(RENDER_VERSION).append('|')
            append(translation.imageHash).append('|').append(options).append('|')
            translation.translations.forEach { append(it.id).append('=').append(it.text).append(':').append(it.kind).append(';') }
        }
        return PageImage.sha1(content.toByteArray()).take(32)
    }

    @Volatile
    private var rendersSinceTrim = 0

    internal fun onPageRendered() {
        if (++rendersSinceTrim >= 10) {
            rendersSinceTrim = 0
            scope.launch(Dispatchers.IO) {
                store.trimRenderedPages(preferences.renderedCacheSizeMb.get() * 1024L * 1024L)
            }
        }
    }

    // endregion

    // region Jobs

    private val jobs = HashMap<String, ChapterJob>()

    private fun jobKey(chapterId: Long, language: String) = "$chapterId:$language"

    /**
     * Returns the running job for this chapter or starts one. [previousChapterId] makes the new job
     * wait for that chapter's translation so context (summaries, glossary) flows in reading order.
     */
    fun job(
        info: ChapterInfo,
        feed: PageFeed,
        renderEagerly: Boolean,
        previousChapterId: Long? = null,
    ): ChapterJob {
        val language = preferences.targetLanguage.get()
        val key = jobKey(info.chapterId, language)
        return synchronized(jobs) {
            val existing = jobs[key]?.takeUnless { it.finished.isCompleted && it.progress.value.error != null }
            existing ?: ChapterJob(info, language, feed, this, renderEagerly).also { job ->
                jobs[key] = job
                job.start(after = previousChapterId?.let { jobs[jobKey(it, language)] })
            }
        }
    }

    /**
     * Drops a finished background job from the registry without cancelling it, so its page images
     * can be freed while a reader that picked it up keeps working.
     */
    internal fun forgetJob(job: ChapterJob) {
        synchronized(jobs) { jobs.remove(jobKey(job.info.chapterId, job.targetLanguage), job) }
    }

    fun releaseJob(job: ChapterJob) {
        synchronized(jobs) { jobs.remove(jobKey(job.info.chapterId, job.targetLanguage), job) }
        job.cancel()
    }

    // endregion

    // region Downloads

    private val downloadQueue = Channel<Pair<Long, Long>>(Channel.UNLIMITED)

    init {
        scope.launch {
            for ((mangaId, chapterId) in downloadQueue) {
                try {
                    DownloadedChapterTranslator(this@TranslationManager, getManga, getChapter, sourceManager, downloadManager, downloadProvider)
                        .translate(mangaId, chapterId)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    logcat(LogPriority.ERROR, e) { "Download translation failed for chapter $chapterId" }
                }
            }
        }
    }

    /** Called by the downloader when a chapter finished downloading. */
    fun onChapterDownloaded(mangaId: Long, sourceId: Long, chapterId: Long) {
        if (!preferences.translateDownloads.get()) return
        if (!preferences.isEnabledFor(mangaId, sourceId)) return
        if (!hasUsableEngine()) return
        downloadQueue.trySend(mangaId to chapterId)
    }

    // endregion

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val QUALITY_COOLDOWN_MS = 2 * 60_000L
        private const val BROKEN_ENGINE_COOLDOWN_MS = 30 * 60_000L
        private const val MAX_INLINE_WAIT_MS = 20_000L

        /** Bump when rendering changes so cached pages are redrawn. */
        private const val RENDER_VERSION = 2
    }
}
