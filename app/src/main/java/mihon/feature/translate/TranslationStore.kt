package mihon.feature.translate

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.feature.translate.model.ChapterTranslationFile
import mihon.feature.translate.model.PageText
import mihon.feature.translate.model.SeriesMemory
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Files used by the translation feature.
 *
 * - Series memory and chapter translations live in app files (small, valuable, survive cache clears).
 * - OCR results and rendered pages live in the cache directory and can be rebuilt.
 */
class TranslationStore(context: Context) {

    private val root = File(context.filesDir, "translate")
    private val ocrDir = File(context.cacheDir, "translate_ocr")
    private val pagesDir = File(context.cacheDir, "translate_pages")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lock(key: String) = locks.getOrPut(key) { Mutex() }

    private fun seriesDir(mangaId: Long, language: String) = File(root, "series/$mangaId/$language")

    // region Series memory

    suspend fun loadMemory(mangaId: Long, title: String, language: String): SeriesMemory {
        val file = File(seriesDir(mangaId, language), "memory.json")
        return lock(file.path).withLock {
            readJson<SeriesMemory>(file) ?: SeriesMemory(mangaId = mangaId, title = title, targetLanguage = language)
        }
    }

    /** Applies [transform] to the stored memory atomically and returns the result. */
    suspend fun updateMemory(
        mangaId: Long,
        title: String,
        language: String,
        transform: (SeriesMemory) -> SeriesMemory,
    ): SeriesMemory {
        val file = File(seriesDir(mangaId, language), "memory.json")
        return lock(file.path).withLock {
            val current = readJson<SeriesMemory>(file)
                ?: SeriesMemory(mangaId = mangaId, title = title, targetLanguage = language)
            transform(current).also { writeJson(file, it) }
        }
    }

    suspend fun clearMemory(mangaId: Long, language: String) {
        val file = File(seriesDir(mangaId, language), "memory.json")
        lock(file.path).withLock { file.delete() }
    }

    // endregion

    // region Chapters

    private fun chapterFile(mangaId: Long, language: String, chapterId: Long) =
        File(seriesDir(mangaId, language), "chapters/$chapterId.json")

    suspend fun loadChapter(mangaId: Long, language: String, chapterId: Long): ChapterTranslationFile? {
        val file = chapterFile(mangaId, language, chapterId)
        return lock(file.path).withLock { readJson<ChapterTranslationFile>(file) }
    }

    suspend fun updateChapter(
        mangaId: Long,
        language: String,
        chapterId: Long,
        create: () -> ChapterTranslationFile,
        transform: (ChapterTranslationFile) -> ChapterTranslationFile,
    ): ChapterTranslationFile {
        val file = chapterFile(mangaId, language, chapterId)
        return lock(file.path).withLock {
            val current = readJson<ChapterTranslationFile>(file) ?: create()
            transform(current).also { writeJson(file, it) }
        }
    }

    suspend fun deleteChapters(mangaId: Long, chapterIds: Collection<Long>) {
        File(root, "series/$mangaId").listFiles()?.forEach { languageDir ->
            chapterIds.forEach { id ->
                val file = File(languageDir, "chapters/$id.json")
                lock(file.path).withLock { file.delete() }
            }
        }
    }

    fun deleteSeries(mangaId: Long) {
        File(root, "series/$mangaId").deleteRecursively()
    }

    // endregion

    // region Site dialogs

    private fun siteDir(chapterId: Long) = File(root, "site/$chapterId")

    /** Keeps the text data a machine-translation site sent with a page, for downloaded chapters. */
    fun saveSiteDialogs(chapterId: Long, pageIndex: Int, fragment: String) {
        val file = File(siteDir(chapterId), "$pageIndex.json")
        if (file.exists() && file.length() == fragment.length.toLong()) return
        file.parentFile?.mkdirs()
        file.writeText(fragment)
    }

    fun loadSiteDialogs(chapterId: Long, pageIndex: Int): String? =
        File(siteDir(chapterId), "$pageIndex.json").takeIf { it.exists() }?.readText()

    fun hasSiteDialogs(chapterId: Long): Boolean = siteDir(chapterId).list()?.isNotEmpty() == true

    // endregion

    // region OCR cache

    fun loadOcr(imageHash: String, language: String): PageText? =
        readJson<PageText>(File(ocrDir, "${imageHash}_$language.json"))

    fun saveOcr(imageHash: String, language: String, text: PageText) {
        writeJson(File(ocrDir, "${imageHash}_$language.json"), text)
    }

    // endregion

    // region Rendered pages

    fun renderedPage(key: String): File? {
        val file = File(pagesDir, "$key.img")
        return file.takeIf { it.exists() && it.length() > 0 }?.also { it.setLastModified(System.currentTimeMillis()) }
    }

    fun newRenderedPageFile(key: String): File {
        pagesDir.mkdirs()
        return File(pagesDir, "$key.img")
    }

    /** Deletes least recently used rendered pages until the directory fits [maxBytes]. */
    fun trimRenderedPages(maxBytes: Long) {
        val files = pagesDir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= maxBytes) return
            total -= file.length()
            file.delete()
        }
    }

    fun clearCaches() {
        pagesDir.deleteRecursively()
        ocrDir.deleteRecursively()
    }

    fun cacheSizeBytes(): Long =
        (pagesDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }) +
            (ocrDir.walkTopDown().filter { it.isFile }.sumOf { it.length() })

    fun dataSizeBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // endregion

    private inline fun <reified T> readJson(file: File): T? {
        if (!file.exists()) return null
        return try {
            json.decodeFromString<T>(file.readText())
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Unreadable translation file ${file.name}" }
            null
        }
    }

    private inline fun <reified T> writeJson(file: File, value: T) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(value))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}
