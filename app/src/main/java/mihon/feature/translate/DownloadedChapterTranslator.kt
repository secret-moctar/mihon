package mihon.feature.translate

import eu.kanade.domain.chapter.model.toDbChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager

/**
 * Translates a chapter right after it is downloaded so it can be read translated offline.
 * Only the text translation is stored; pages are drawn when the chapter is opened.
 */
internal class DownloadedChapterTranslator(
    private val manager: TranslationManager,
    private val getManga: GetManga,
    private val getChapter: GetChapter,
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
) {

    suspend fun translate(mangaId: Long, chapterId: Long) {
        val manga = getManga.await(mangaId) ?: return
        val chapter = getChapter.await(chapterId) ?: return
        val source = sourceManager.getOrStub(manga.source)

        val readerChapter = ReaderChapter(chapter.toDbChapter())
        val loader = DownloadPageLoader(readerChapter, manga, source, downloadManager, downloadProvider)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val pages = loader.getPages()
            if (pages.isEmpty()) return
            pages.forEach { it.chapter = readerChapter }
            readerChapter.state = ReaderChapter.State.Loaded(pages)

            lateinit var job: ChapterJob
            val feed = object : PageFeed {
                override val pageCount = pages.size
                private val requested = HashSet<Int>()

                override fun requestPages(indices: List<Int>) {
                    val fresh = synchronized(requested) { indices.filter { requested.add(it) } }
                    fresh.forEach { index ->
                        scope.launch {
                            val bytes = readPage(loader, pages[index]) ?: return@launch
                            val dialogs = manager.store.loadSiteDialogs(chapter.id, index)?.let(SiteDialogs::parse)
                            job.submitOriginal(index, bytes, dialogs)
                        }
                    }
                }
            }
            job = manager.job(
                ChapterInfo(
                    mangaId = manga.id,
                    mangaTitle = manga.title,
                    sourceId = source.id,
                    sourceLang = source.lang.takeUnless { manager.store.hasSiteDialogs(chapter.id) },
                    chapterId = chapter.id,
                    chapterName = chapter.name,
                    chapterNumber = chapter.chapterNumber,
                    siteProvidesText = manager.store.hasSiteDialogs(chapter.id),
                ),
                feed,
                renderEagerly = false,
            )
            job.finished.await()
            manager.forgetJob(job)
            logcat { "Translated downloaded chapter ${chapter.name}" }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            logcat(LogPriority.ERROR, e) { "Could not translate downloaded chapter $chapterId" }
        } finally {
            scope.cancel()
            loader.recycle()
        }
    }

    private suspend fun readPage(loader: DownloadPageLoader, page: ReaderPage): ByteArray? {
        if (page.stream == null || page.status != Page.State.Ready) {
            // Archive pages are opened lazily by the loader.
            loader.loadPage(page)
        }
        return try {
            page.stream?.invoke()?.use { it.readBytes() }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Could not read page ${page.index}" }
            null
        }
    }
}
