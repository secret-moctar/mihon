package eu.kanade.tachiyomi.ui.reader.loader

import androidx.annotation.CallSuper
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import java.io.InputStream

/**
 * A loader used to load pages into the reader. Any open resources must be cleaned up when the
 * method [recycle] is called.
 */
abstract class PageLoader {

    /**
     * Whether this loader has been already recycled.
     */
    var isRecycled = false
        private set

    abstract var isLocal: Boolean

    /**
     * Receives pages once their image is available, e.g. to translate them before they are shown.
     * Set before [getPages] is called.
     */
    var readyHook: PageReadyHook? = null

    /**
     * Returns the list of pages of a chapter.
     */
    abstract suspend fun getPages(): List<ReaderPage>

    /**
     * Loads the page. May also preload other pages.
     * Progress of the page loading should be followed via [page.statusFlow].
     * [loadPage] is not currently guaranteed to complete, so it should be launched asynchronously.
     */
    open suspend fun loadPage(page: ReaderPage) {}

    /**
     * Retries the given [page] in case it failed to load. This method only makes sense when an
     * online source is used.
     */
    open fun retryPage(page: ReaderPage) {}

    /**
     * Recycles this loader. Implementations must override this method to clean up any active
     * resources.
     */
    @CallSuper
    open fun recycle() {
        isRecycled = true
        readyHook?.recycle()
    }

    /**
     * Marks [page] as ready with the image from [stream], going through [readyHook] when set.
     */
    protected fun publishReady(page: ReaderPage, stream: () -> InputStream) {
        val hook = readyHook
        if (hook == null) {
            page.stream = stream
            page.status = Page.State.Ready
        } else {
            hook.onPageReady(page, stream)
        }
    }

    /**
     * For loaders that know every page stream up front: without a hook pages are ready right away,
     * with one they wait for [loadPage] so only viewed pages are processed.
     */
    protected fun prepareLocalPage(page: ReaderPage, stream: () -> InputStream) {
        if (readyHook == null) {
            page.stream = stream
            page.status = Page.State.Ready
        } else {
            page.originalStream = stream
        }
    }

    protected fun publishLocalPage(page: ReaderPage) {
        val stream = page.originalStream ?: return
        if (page.status == Page.State.Queue) publishReady(page, stream)
    }
}

interface PageReadyHook {
    /** Must eventually set [ReaderPage.stream] and move [page] to [Page.State.Ready]. Must not block. */
    fun onPageReady(page: ReaderPage, stream: () -> InputStream)

    fun recycle()

    /** URL to download instead of [imageUrl] (e.g. the raw page of a machine-translation site). */
    fun rawImageUrl(imageUrl: String): String? = null
}
