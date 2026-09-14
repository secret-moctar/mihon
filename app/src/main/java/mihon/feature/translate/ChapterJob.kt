package mihon.feature.translate

import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.feature.translate.engine.BlockImage
import mihon.feature.translate.engine.Languages
import mihon.feature.translate.engine.RecentLine
import mihon.feature.translate.engine.TranslationRequest
import mihon.feature.translate.image.PageImage
import mihon.feature.translate.model.BlockTranslation
import mihon.feature.translate.model.Box
import mihon.feature.translate.model.ChapterTranslationFile
import mihon.feature.translate.model.PageText
import mihon.feature.translate.model.PageTranslation
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

/**
 * Where a [ChapterJob] gets page images from: the reader's page loader or a downloaded chapter.
 */
interface PageFeed {
    val pageCount: Int

    /** Asks for these pages to be loaded; results arrive through [ChapterJob.submitOriginal]. */
    fun requestPages(indices: List<Int>)
}

data class ChapterInfo(
    val mangaId: Long,
    val mangaTitle: String,
    val sourceId: Long,
    /** Language declared by the extension, e.g. "ko". */
    val sourceLang: String?,
    val chapterId: Long,
    val chapterName: String,
    val chapterNumber: Double,
    /** The site sends the page text itself (bubbles are erased in its images); no OCR is done. */
    val siteProvidesText: Boolean = false,
)

/**
 * Translation of one chapter into one language.
 */
class ChapterJob internal constructor(
    val info: ChapterInfo,
    val targetLanguage: String,
    private val feed: PageFeed,
    private val manager: TranslationManager,
    /** Whether rendered images are produced as soon as translations arrive. */
    private val renderEagerly: Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + manager.dispatcher)
    private val pageCount = feed.pageCount
    private val slots = Array(pageCount) { Slot(it) }
    private val prefs get() = manager.preferences

    /** Page the reader currently shows; translation prioritizes the batch around it. */
    private val focus = MutableStateFlow(0)

    /** Bumped when a page that timed out earlier becomes available. */
    private val pagesReturned = MutableStateFlow(0)

    private val _progress = MutableStateFlow(Progress(0, pageCount, null))
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    val finished = CompletableDeferred<Unit>()

    @Volatile
    private var detectedLanguage: String? = null

    private var stored: ChapterTranslationFile? = null
    private val storedLoaded = CompletableDeferred<Unit>()

    data class Progress(val translatedPages: Int, val totalPages: Int, val error: String?)

    private inner class Slot(val index: Int) {
        val original = CompletableDeferred<ByteArray>()

        @Volatile
        var hash: String? = null
        val text = CompletableDeferred<PageText>()
        val translation = CompletableDeferred<PageTranslation?>()
        val rendered = CompletableDeferred<File?>()

        @Volatile
        var translationRequested = false

        @Volatile
        var siteDialogs: List<SiteDialog>? = null
    }

    fun start(after: ChapterJob?) {
        scope.launch {
            stored = manager.store.loadChapter(info.mangaId, targetLanguage, info.chapterId)
            storedLoaded.complete(Unit)
        }
        slots.forEach { slot -> scope.launch { prepare(slot) } }
        scope.launch {
            // Keep context flowing forward: the next chapter waits for this one's summary, unless
            // the user is already looking at it (see [limitWaitForPrevious]).
            if (after != null) {
                select {
                    after.finished.onAwait {}
                    skipPreviousWait.onAwait {}
                }
            }
            translateLoop()
        }
        if (renderEagerly) {
            slots.forEach { slot -> scope.launch { renderWhenReady(slot) } }
        }
    }

    private val skipPreviousWait = CompletableDeferred<Unit>()

    /** Stops waiting for the previous chapter after [timeoutMs], used once a page is on screen. */
    fun limitWaitForPrevious(timeoutMs: Long) {
        if (skipPreviousWait.isCompleted) return
        scope.launch {
            delay(timeoutMs)
            skipPreviousWait.complete(Unit)
        }
    }

    fun cancel() {
        scope.cancel()
        slots.forEach { it.rendered.complete(null) }
        if (!finished.isCompleted) finished.complete(Unit)
    }

    fun setFocus(pageIndex: Int) {
        focus.value = pageIndex.coerceIn(0, max(0, pageCount - 1))
    }

    fun submitOriginal(index: Int, bytes: ByteArray, siteDialogs: List<SiteDialog>? = null) {
        val slot = slots.getOrNull(index) ?: return
        if (siteDialogs != null) slot.siteDialogs = siteDialogs
        slot.original.complete(bytes)
    }

    @Volatile
    private var siteDataLogged = false

    /** Suspends until the translated image for [index] exists; null means show the original. */
    suspend fun awaitRendered(index: Int): File? {
        val slot = slots.getOrNull(index) ?: return null
        if (!renderEagerly) scope.launch { renderWhenReady(slot) }
        return slot.rendered.await()
    }

    /** Suspends until the page has been translated (not rendered). */
    suspend fun awaitTranslated(index: Int) {
        slots.getOrNull(index)?.translation?.await()
    }

    private suspend fun prepare(slot: Slot) {
        val bytes = slot.original.await()
        val hash = PageImage.sha1(bytes)
        slot.hash = hash
        storedLoaded.await()

        val storedPage = stored?.pages?.get(slot.index)?.takeIf { it.imageHash == hash }
        if (storedPage != null) {
            // Translation first: the scheduler treats "text ready, translation missing" as work to do.
            slot.translation.complete(storedPage)
            slot.text.complete(storedPage.text)
            return
        }

        if (info.siteProvidesText) {
            prepareFromSite(slot, bytes, hash)
            return
        }

        val language = languageFor(bytes)
        if (language == null || language == targetLanguage.substringBefore('-')) {
            TranslateLog.i(
                if (language == null) {
                    "Page ${slot.index + 1}: no text found"
                } else {
                    "Page ${slot.index + 1}: already in $targetLanguage, skipped"
                },
            )
            // Already in the target language.
            slot.translation.complete(null)
            slot.text.complete(PageText(slot.index, 0, 0, emptyList()))
            return
        }

        val text = manager.store.loadOcr(hash, language) ?: try {
            manager.ocrPermits.withPermit {
                PageImage.open(bytes).use { image -> manager.detector.detect(image, slot.index, language) }
            }.also { manager.store.saveOcr(hash, language, it) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            TranslateLog.e("Page ${slot.index + 1}: text detection failed", e)
            PageText(slot.index, 0, 0, emptyList())
        }
        TranslateLog.i("Page ${slot.index + 1}: found ${text.blocks.size} text blocks ($language)")
        slot.text.complete(text)
    }

    /** Pages of machine-translation sites: the text comes from the site's data, not the image. */
    private suspend fun prepareFromSite(slot: Slot, bytes: ByteArray, hash: String) {
        val dialogs = slot.siteDialogs.orEmpty()
        val choice = SiteDialogs.choose(dialogs)
        if (choice == null) {
            TranslateLog.i("Page ${slot.index + 1}: site sent no text")
            slot.translation.complete(null)
            slot.text.complete(PageText(slot.index, 0, 0, emptyList()))
            return
        }
        if (!siteDataLogged) {
            siteDataLogged = true
            TranslateLog.i(
                "Site text data: ${SiteDialogs.describe(dialogs)} -> using '${choice.key}' " +
                    "(${Languages.englishName(choice.language)})",
            )
        }
        detectedLanguage = choice.language
        val (width, height) = imageSize(bytes)
        val text = SiteDialogs.toPageText(dialogs, choice, slot.index, width, height)
        if (choice.language == targetLanguage.substringBefore('-')) {
            // Already in the wanted language: draw the site's text back into its erased bubbles.
            val identity = text.blocks.map { BlockTranslation(it.id, it.text) }
            slot.translation.complete(PageTranslation(slot.index, hash, text, identity, engine = "site"))
        }
        TranslateLog.i("Page ${slot.index + 1}: ${text.blocks.size} text blocks from site (${choice.language})")
        slot.text.complete(text)
    }

    private fun imageSize(bytes: ByteArray): Pair<Int, Int> {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) return bounds.outWidth to bounds.outHeight
        return PageImage.open(bytes).use { it.width to it.height }
    }

    private suspend fun languageFor(bytes: ByteArray): String? {
        val configured = prefs.sourceLanguage.get()
        if (configured != "auto") return configured
        Languages.fromSourceLang(info.sourceLang)?.let { return it }
        detectedLanguage?.let { return it }
        return manager.ocrPermits.withPermit {
            detectedLanguage ?: when (val script = PageImage.open(bytes).use { manager.detector.detectScript(it) }) {
                // A Korean/Chinese/Japanese page sets the language for the whole chapter.
                "ko", "zh", "ja" -> script.also {
                    detectedLanguage = it
                    TranslateLog.i("Original language detected: ${Languages.englishName(it)}")
                }
                // Latin text or no text: decide per page, a later page may still show the real language.
                "latin" -> "en"
                else -> null
            }
        }
    }

    // region Translation scheduling

    private suspend fun translateLoop() {
        storedLoaded.await()
        while (true) {
            // Read before scheduling so a page returning in between is not missed.
            val seen = pagesReturned.value
            val start = nextBatchStart()
            if (start == null) {
                if (slots.all { it.translation.isCompleted }) break
                // Some pages could not be loaded yet; wait until one of them shows up.
                pagesReturned.first { it != seen }
                continue
            }
            val requested = collectBatch(start)
            feed.requestPages(requested.map { it.index })
            requested.forEach { it.translationRequested = true }

            // Ask for a few more pages as lookahead context while waiting.
            val lookaheadCount = prefs.lookaheadPages.get().coerceIn(0, 6)
            val lookaheadSlots = ((requested.last().index + 1) until min(pageCount, requested.last().index + 1 + lookaheadCount))
                .map { slots[it] }
            feed.requestPages(lookaheadSlots.map { it.index })

            // A page that fails to download must not block the chapter: translate what arrived and
            // put the rest back in the queue once their image shows up (e.g. after a retry).
            withTimeoutOrNull(PAGE_WAIT_MS) { requested.forEach { it.text.await() } }
            val batch = requested.filter { it.text.isCompleted }
            requested.filterNot { it.text.isCompleted }.forEach { slot ->
                scope.launch {
                    slot.text.await()
                    slot.translationRequested = false
                    pagesReturned.value++
                }
            }
            if (batch.isEmpty()) continue
            val texts = batch.map { it.text.getCompleted() }
            val pending = batch.filterNot { it.translation.isCompleted }
            if (pending.isEmpty()) continue

            val pagesNeedingModel = pending.filter { slot -> texts[batch.indexOf(slot)].blocks.isNotEmpty() }
            pending.filter { it !in pagesNeedingModel }.forEach { slot ->
                slot.translation.complete(
                    PageTranslation(slot.index, slot.hash.orEmpty(), slot.text.getCompleted(), emptyList()),
                )
            }
            if (pagesNeedingModel.isNotEmpty()) {
                translateBatch(pagesNeedingModel, lookaheadSlots.filter { it.text.isCompleted })
            }
            updateProgress()
        }
        finishChapter()
    }

    /** First untranslated page, starting from the batch that contains the focused page. */
    private fun nextBatchStart(): Int? {
        val focused = focus.value
        val pagesPerRequest = manager.pagesPerRequest()
        // Pages just before the focus are translated together with it.
        val focusStart = max(0, focused - pagesPerRequest / 3)
        val fromFocus = (focusStart until pageCount).firstOrNull { !slots[it].translationRequested }
        if (fromFocus != null && fromFocus <= focused + pagesPerRequest * 3) return fromFocus
        // Each batch completes every page it took (translated or failed), so nothing is left pending.
        return slots.firstOrNull { !it.translationRequested }?.index
    }

    private fun collectBatch(start: Int): List<Slot> {
        val pagesPerRequest = manager.pagesPerRequest()
        // The first batch of a chapter is smaller so the first page appears quickly.
        val size = if (start == focus.value && slots.none { it.translation.isCompleted }) {
            min(pagesPerRequest, if (pagesPerRequest > 8) 6 else 3)
        } else {
            pagesPerRequest
        }
        val result = mutableListOf<Slot>()
        var i = start
        while (i < pageCount && result.size < size && !slots[i].translationRequested) {
            result += slots[i]
            i++
        }
        return result
    }

    private suspend fun translateBatch(batch: List<Slot>, lookahead: List<Slot>) {
        val memory = manager.store.loadMemory(info.mangaId, info.mangaTitle, targetLanguage)
        val chapterFile = manager.store.loadChapter(info.mangaId, targetLanguage, info.chapterId)
        val firstIndex = batch.first().index

        val recent = chapterFile?.pages?.values
            ?.filter { it.pageIndex < firstIndex }
            ?.sortedBy { it.pageIndex }
            ?.flatMap { page ->
                val byId = page.text.blocks.associateBy { it.id }
                page.translations.mapNotNull { t ->
                    byId[t.id]?.let { RecentLine(page.pageIndex, it.text, t.text, t.speaker) }
                }
            }
            ?.takeLast(14)
            .orEmpty()

        val pages = batch.map { it.text.getCompleted() }
        val engineSupportsImages = manager.primaryEngineSupportsImages()
        val images = if (prefs.sendBubbleImages.get() && engineSupportsImages) cropImages(batch) else emptyList()

        val request = TranslationRequest(
            sourceLanguage = detectedLanguage ?: Languages.fromSourceLang(info.sourceLang) ?: prefs.sourceLanguage.get(),
            targetLanguage = targetLanguage,
            seriesTitle = info.mangaTitle,
            memory = memory,
            chapterName = info.chapterName,
            chapterNumber = info.chapterNumber,
            previousChapterSummaries = memory.summariesBefore(info.chapterNumber, prefs.previousChapterSummaries.get()),
            chapterSummarySoFar = chapterFile?.summary.orEmpty(),
            recentLines = recent,
            pages = pages,
            lookahead = lookahead.map { it.text.getCompleted() },
            images = images,
            translateSfx = prefs.translateSfx.get(),
            globalInstructions = prefs.globalInstructions.get(),
        )

        TranslateLog.i("Translating pages ${batch.map { it.index + 1 }} (${request.blockCount} blocks)")
        val result = try {
            manager.translate(request)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            TranslateLog.e("Translation failed for pages ${batch.map { it.index + 1 }}", e)
            _progress.value = _progress.value.copy(error = e.message)
            manager.reportError(e.message ?: "Translation failed")
            // Show originals for this batch; reopening the chapter retries.
            batch.forEach { it.translation.complete(null) }
            return
        }

        val byPage = result.translations.groupBy { it.id.substringBefore('.').toIntOrNull() }
        val pageTranslations = batch.map { slot ->
            val text = slot.text.getCompleted()
            val ids = text.blocks.map { it.id }.toSet()
            PageTranslation(
                pageIndex = slot.index,
                imageHash = slot.hash.orEmpty(),
                text = text,
                translations = byPage[slot.index].orEmpty().filter { it.id in ids },
                engine = result.engineName,
            )
        }

        manager.store.updateChapter(
            info.mangaId,
            targetLanguage,
            info.chapterId,
            create = {
                ChapterTranslationFile(
                    chapterId = info.chapterId,
                    chapterName = info.chapterName,
                    chapterNumber = info.chapterNumber,
                    targetLanguage = targetLanguage,
                )
            },
            transform = { file ->
                file.copy(
                    pages = file.pages + pageTranslations.associateBy { it.pageIndex },
                    summary = result.chapterSummary.ifBlank { file.summary },
                )
            },
        )
        if (result.memoryUpdate.glossary.isNotEmpty() || result.memoryUpdate.characters.isNotEmpty()) {
            manager.store.updateMemory(info.mangaId, info.mangaTitle, targetLanguage) { it.merge(result.memoryUpdate) }
        }

        TranslateLog.i("Translated ${result.translations.size}/${request.blockCount} blocks with ${result.engineName}")
        batch.zip(pageTranslations).forEach { (slot, translation) -> slot.translation.complete(translation) }
    }

    private suspend fun cropImages(batch: List<Slot>): List<BlockImage> {
        val result = mutableListOf<BlockImage>()
        batch.forEach { slot ->
            val bytes = slot.original.getCompleted()
            val text = slot.text.getCompleted()
            if (text.blocks.isEmpty()) return@forEach
            manager.ocrPermits.withPermit {
                PageImage.open(bytes).use { image ->
                    text.blocks.take(MAX_IMAGES_PER_PAGE).forEach { block ->
                        val box: Box = block.box.expand(block.medianLineHeight / 2 + 4).clamp(image.width, image.height)
                        val longest = max(box.width, box.height)
                        val sample = max(1, Integer.highestOneBit(max(1, longest / 384)))
                        val bitmap = image.decodeRegion(box, sample)
                        try {
                            val out = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                            result += BlockImage(block.id, out.toByteArray())
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        }
        return result
    }

    private suspend fun finishChapter() {
        val file = manager.store.loadChapter(info.mangaId, targetLanguage, info.chapterId)
        val summary = file?.summary.orEmpty()
        if (summary.isNotBlank()) {
            manager.store.updateMemory(info.mangaId, info.mangaTitle, targetLanguage) {
                it.withChapterSummary(info.chapterNumber, summary)
            }
        }
        updateProgress()
        finished.complete(Unit)
    }

    private fun updateProgress() {
        val done = slots.count { it.translation.isCompleted }
        _progress.value = _progress.value.copy(translatedPages = done)
    }

    // endregion

    // region Rendering

    private val renderLaunched = ConcurrentHashMap.newKeySet<Int>()

    private suspend fun renderWhenReady(slot: Slot) {
        if (!renderLaunched.add(slot.index)) return
        val translation = slot.translation.await()
        if (translation == null || translation.translations.isEmpty()) {
            slot.rendered.complete(null)
            return
        }
        val options = manager.renderOptions(targetLanguage)
        val key = manager.renderKey(translation, options)
        manager.store.renderedPage(key)?.let {
            slot.rendered.complete(it)
            return
        }
        val bytes = slot.original.await()
        val file = try {
            manager.renderPermits.withPermit {
                PageImage.open(bytes).use { image ->
                    manager.renderer.render(image, translation.text, translation.translations, options, manager.store.newRenderedPageFile(key))
                }
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            TranslateLog.e("Page ${slot.index + 1}: drawing translation failed", e)
            null
        }
        if (file != null) TranslateLog.i("Page ${slot.index + 1}: translated image ready")
        slot.rendered.complete(file)
        manager.onPageRendered()
    }

    // endregion

    private companion object {
        const val MAX_IMAGES_PER_PAGE = 12
        const val PAGE_WAIT_MS = 60_000L
    }
}
