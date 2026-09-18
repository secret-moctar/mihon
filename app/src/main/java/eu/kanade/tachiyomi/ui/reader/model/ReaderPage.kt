package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.InputStream

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    var stream: (() -> InputStream)? = null,
) : Page(index, url, imageUrl, null) {

    open lateinit var chapter: ReaderChapter

    /** The untouched source image, kept when [stream] serves a translated version. */
    @Volatile
    var originalStream: (() -> InputStream)? = null

    /** Rendered translation of this page, when one exists. */
    @Volatile
    var translatedFile: File? = null

    /**
     * Incremented when what [stream] returns changes while the page is already [Page.State.Ready]
     * (translation finished, original/translated toggled), so viewers redraw it.
     */
    val displayRevision = MutableStateFlow(0)
}
