package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.feature.translate.ChapterInfo
import mihon.feature.translate.ChapterJob
import mihon.feature.translate.MachineTranslatedPages
import mihon.feature.translate.PageFeed
import mihon.feature.translate.SiteDialog
import mihon.feature.translate.SiteDialogs
import mihon.feature.translate.TranslateLog
import mihon.feature.translate.TranslationManager
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Connects a chapter's [PageLoader] to the translation pipeline: pages become ready once their
 * translation is rendered, and upcoming pages are fetched ahead so batches have context.
 */
class ReaderTranslationHook(
    private val manager: TranslationManager,
    private val chapter: ReaderChapter,
    private val manga: Manga,
    private val source: Source,
    private val previousChapterId: () -> Long?,
    /** Receives pages whose displayed image changed after they were ready (for the WebGPU viewer). */
    private val pageUpdates: MutableSharedFlow<ReaderPage>,
) : PageReadyHook {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadJobs = ConcurrentHashMap<Int, Job>()
    private val submitted = ConcurrentHashMap.newKeySet<Int>()

    @Volatile
    private var job: ChapterJob? = null

    private val prefs get() = manager.preferences

    val isEnabled: Boolean
        get() = prefs.isEnabledFor(manga.id, source.id) && manager.hasUsableEngine()

    val currentJob: ChapterJob? get() = job

    /** Pages of a machine-translation site whose raw image (not the site's painted one) is loaded. */
    private val rawLoaded = ConcurrentHashMap.newKeySet<Int>()

    override fun rawImageUrl(imageUrl: String): String? =
        if (isEnabled) MachineTranslatedPages.rawUrl(imageUrl) else null

    override fun onPageReady(page: ReaderPage, stream: () -> InputStream) {
        page.originalStream = stream
        page.stream = { displayStream(page) }
        if (MachineTranslatedPages.isComposed(page.imageUrl)) {
            if (isEnabled) rawLoaded.add(page.index) else rawLoaded.remove(page.index)
        }

        if (!isEnabled) {
            if (page.index == 0) {
                TranslateLog.i(
                    "Page ready, translation off for this series (enabled=${prefs.isEnabledFor(manga.id, source.id)}, " +
                        "engine available=${manager.hasUsableEngine()})",
                )
            }
            page.status = Page.State.Ready
            return
        }
        val job = ensureJob()
        if (job == null) {
            TranslateLog.w("Page ${page.index + 1} ready but chapter page list is missing")
            page.status = Page.State.Ready
            return
        }
        if (page.translatedFile?.exists() == true) {
            // Image re-fetched after cache eviction; the translation is still valid.
            page.status = Page.State.Ready
            return
        }

        if (prefs.showOriginalWhileTranslating.get()) {
            page.status = Page.State.Ready
        } else if (page.status != Page.State.Ready) {
            page.status = Page.State.LoadPage
        }

        scope.launch {
            val bytes = try {
                stream().use { it.readBytes() }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Could not read page ${page.index} for translation" }
                page.status = Page.State.Ready
                return@launch
            }
            if (submitted.add(page.index)) job.submitOriginal(page.index, bytes, siteDialogs(page))
            loadJobs.remove(page.index)?.cancel()

            val file = job.awaitRendered(page.index)
            page.translatedFile = file
            if (page.status == Page.State.Ready) {
                if (file != null) refresh(page)
            } else {
                page.status = Page.State.Ready
            }
        }
    }

    /**
     * Text data sent by machine-translation sites. It comes in the page URL when reading online and
     * is kept on disk so downloaded chapters (whose pages have no URL) still have it.
     */
    private fun siteDialogs(page: ReaderPage): List<SiteDialog>? {
        val chapterId = chapter.chapter.id ?: return null
        val fragment = MachineTranslatedPages.fragment(page.imageUrl)
            ?.also { manager.store.saveSiteDialogs(chapterId, page.index, it) }
            ?: manager.store.loadSiteDialogs(chapterId, page.index)
            ?: return null
        return SiteDialogs.parse(fragment)
    }

    /** What viewers display: the translation unless disabled or the user asked for the original. */
    private fun displayStream(page: ReaderPage): InputStream {
        val file = page.translatedFile
        if (file != null && !prefs.showOriginal.get() && isEnabled) {
            try {
                return file.inputStream()
            } catch (_: Exception) {
                // Cache cleared while reading: fall back to the original.
                page.translatedFile = null
            }
        }
        return page.originalStream!!.invoke()
    }

    private fun ensureJob(): ChapterJob? {
        job?.let { return it }
        val pages = chapter.pages ?: return null
        synchronized(this) {
            job?.let { return it }
            val dbChapter = chapter.chapter
            val feed = object : PageFeed {
                override val pageCount = pages.size
                override fun requestPages(indices: List<Int>) = indices.forEach { requestLoad(it) }
            }
            val siteChapter = pages.any { MachineTranslatedPages.isComposed(it.imageUrl) } ||
                manager.store.hasSiteDialogs(dbChapter.id!!)
            TranslateLog.i(
                "Start: ${manga.title} / ${dbChapter.name}, ${pages.size} pages, site=${source.name} (${source.lang}), " +
                    "to=${prefs.targetLanguage.get()}, engines=${manager.engines().joinToString { it.name }}",
            )
            return manager.job(
                ChapterInfo(
                    mangaId = manga.id,
                    mangaTitle = manga.title,
                    sourceId = source.id,
                    // A machine-translation site's language is its translation, not the raw page's.
                    sourceLang = source.lang.takeUnless { siteChapter },
                    chapterId = dbChapter.id!!,
                    chapterName = dbChapter.name,
                    chapterNumber = dbChapter.chapter_number.toDouble(),
                    siteProvidesText = siteChapter,
                ),
                feed,
                renderEagerly = true,
                previousChapterId = previousChapterId(),
            ).also {
                it.setFocus(chapter.requestedPage)
                job = it
            }
        }
    }

    /** Makes the loader fetch a page that the viewer has not asked for yet. */
    private fun requestLoad(index: Int) {
        if (index in submitted || loadJobs.containsKey(index)) return
        val page = chapter.pages?.getOrNull(index) ?: return
        val loader = chapter.pageLoader ?: return
        loadJobs[index] = scope.launch { loader.loadPage(page) }
    }

    /** Starts translating this chapter without it being displayed (next chapter pre-translation). */
    fun startInBackground() {
        if (!isEnabled) return
        val job = ensureJob() ?: return
        // The translation loop requests pages batch by batch from here.
        job.setFocus(0)
    }

    /** Called when the user shows a page of this chapter. */
    fun setFocus(pageIndex: Int) {
        val job = if (isEnabled) ensureJob() else this.job
        job ?: return
        job.setFocus(pageIndex)
        // The chapter is being read: don't hold it for long behind the previous chapter.
        job.limitWaitForPrevious(PREVIOUS_CHAPTER_WAIT_MS)
    }

    /**
     * Called when translation settings changed: pages waiting for a translation that is no longer
     * wanted are released, and already shown pages are redrawn.
     */
    fun onDisplaySettingsChanged() {
        val pages = chapter.pages ?: return
        val enabled = isEnabled
        pages.forEach { page ->
            val original = page.originalStream ?: return@forEach
            // Machine-translation sites: switch between the raw page and the site's painted one.
            if (MachineTranslatedPages.isComposed(page.imageUrl) && page.status == Page.State.Ready &&
                enabled != (page.index in rawLoaded)
            ) {
                page.translatedFile = null
                submitted.remove(page.index)
                page.status = Page.State.Queue
                chapter.pageLoader?.retryPage(page)
                return@forEach
            }
            when {
                !enabled && page.status == Page.State.LoadPage -> page.status = Page.State.Ready
                enabled && page.status == Page.State.Ready && page.translatedFile == null && page.index !in submitted -> {
                    onPageReady(page, original)
                }
                page.status == Page.State.Ready -> refresh(page)
            }
        }
    }

    private fun refresh(page: ReaderPage) {
        page.displayRevision.value++
        pageUpdates.tryEmit(page)
    }

    override fun recycle() {
        scope.cancel()
        loadJobs.clear()
        job?.let { manager.releaseJob(it) }
        job = null
    }

    private companion object {
        const val PREVIOUS_CHAPTER_WAIT_MS = 15_000L
    }
}
