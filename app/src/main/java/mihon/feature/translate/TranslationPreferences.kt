package mihon.feature.translate

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mihon.feature.translate.engine.GeminiEngine
import mihon.feature.translate.render.PageRenderer
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import java.util.UUID

@Inject
@SingleIn(AppScope::class)
class TranslationPreferences(
    private val preferenceStore: PreferenceStore,
) {
    // region What gets translated

    /** Translate every series unless turned off for it. */
    val translateAllByDefault: Preference<Boolean> = preferenceStore.getBoolean("translate_all_default", false)

    /** Source (website/extension) ids whose series are translated by default. */
    val enabledSources: Preference<Set<String>> = preferenceStore.getStringSet("translate_enabled_sources", emptySet())

    /** Series ids explicitly turned on / off from the reader, overriding the defaults above. */
    val enabledManga: Preference<Set<String>> = preferenceStore.getStringSet("translate_enabled_manga", emptySet())
    val disabledManga: Preference<Set<String>> = preferenceStore.getStringSet("translate_disabled_manga", emptySet())

    val sourceLanguage: Preference<String> = preferenceStore.getString("translate_source_language", "auto")
    val targetLanguage: Preference<String> = preferenceStore.getString("translate_target_language", "en")

    // endregion

    // region Engines

    /**
     * Ordered engine list, JSON encoded. First usable engine is used, the next ones are fallbacks.
     * Private so API keys never end up in backup files.
     */
    val engines: Preference<String> = preferenceStore.getString(Preference.privateKey("translate_engines"), "")

    // endregion

    // region Context

    /** Pages sent in one request. More pages = more context and fewer requests, but slower first page. */
    val pagesPerRequest: Preference<Int> = preferenceStore.getInt("translate_pages_per_request", 6)

    /** Following pages given to the model as context only. */
    val lookaheadPages: Preference<Int> = preferenceStore.getInt("translate_lookahead_pages", 2)

    val previousChapterSummaries: Preference<Int> = preferenceStore.getInt("translate_previous_summaries", 5)

    val pretranslateNextChapter: Preference<Boolean> = preferenceStore.getBoolean("translate_pretranslate_next", true)

    val translateDownloads: Preference<Boolean> = preferenceStore.getBoolean("translate_on_download", true)

    /** Attach crops of each bubble so vision models can correct OCR mistakes. Uses more free quota. */
    val sendBubbleImages: Preference<Boolean> = preferenceStore.getBoolean("translate_send_images", false)

    val globalInstructions: Preference<String> = preferenceStore.getString("translate_global_instructions", "")

    // endregion

    // region Display

    val showOriginal: Preference<Boolean> = preferenceStore.getBoolean("translate_show_original", false)

    /** Show the original page while its translation is prepared instead of a loading indicator. */
    val showOriginalWhileTranslating: Preference<Boolean> =
        preferenceStore.getBoolean("translate_original_while_loading", false)

    val translateSfx: Preference<Boolean> = preferenceStore.getBoolean("translate_sfx", false)

    val fontStyle: Preference<PageRenderer.FontStyle> =
        preferenceStore.getEnum("translate_font_style", PageRenderer.FontStyle.COMIC)

    /** Percent. */
    val fontScale: Preference<Int> = preferenceStore.getInt("translate_font_scale", 100)

    val uppercaseLatin: Preference<Boolean> = preferenceStore.getBoolean("translate_uppercase", false)

    // endregion

    // region Storage

    val renderedCacheSizeMb: Preference<Int> = preferenceStore.getInt("translate_cache_size_mb", 300)

    val offlineModelsWifiOnly: Preference<Boolean> = preferenceStore.getBoolean("translate_offline_wifi_only", true)

    // endregion

    fun isEnabledFor(mangaId: Long, sourceId: Long): Boolean {
        val id = mangaId.toString()
        return when {
            id in disabledManga.get() -> false
            id in enabledManga.get() -> true
            sourceId.toString() in enabledSources.get() -> true
            else -> translateAllByDefault.get()
        }
    }

    fun setEnabledFor(mangaId: Long, enabled: Boolean) {
        val id = mangaId.toString()
        if (enabled) {
            disabledManga.set(disabledManga.get() - id)
            enabledManga.set(enabledManga.get() + id)
        } else {
            enabledManga.set(enabledManga.get() - id)
            disabledManga.set(disabledManga.get() + id)
        }
    }

    fun engineConfigs(): List<EngineConfig> {
        val raw = engines.get()
        if (raw.isBlank()) return EngineConfig.defaults()
        val saved = try {
            json.decodeFromString<List<EngineConfig>>(raw)
        } catch (_: Exception) {
            return EngineConfig.defaults()
        }
        // Engines added in app updates appear in saved lists too, before the offline fallback.
        val missing = EngineConfig.defaults().filter { d -> saved.none { it.id == d.id } }
        if (missing.isEmpty()) return saved
        val offlineIndex = saved.indexOfFirst { it.type == EngineType.OFFLINE }.takeIf { it >= 0 } ?: saved.size
        return saved.subList(0, offlineIndex) + missing.filter { it.type != EngineType.OFFLINE } +
            saved.subList(offlineIndex, saved.size) + missing.filter { it.type == EngineType.OFFLINE }
    }

    fun saveEngineConfigs(configs: List<EngineConfig>) {
        engines.set(json.encodeToString(configs))
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

@Serializable
data class EngineConfig(
    val id: String = UUID.randomUUID().toString(),
    val type: EngineType,
    val label: String,
    val enabled: Boolean,
    /** One key per line; several keys are rotated when a free quota runs out. */
    val apiKeys: String = "",
    val baseUrl: String = "",
    val model: String = "",
    val vision: Boolean = true,
) {
    val keyList: List<String> get() = apiKeys.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }

    val isUsable: Boolean
        get() = enabled && when (type) {
            EngineType.OFFLINE, EngineType.GOOGLE_WEB -> true
            // A blank model is picked automatically from the service's list.
            EngineType.OPENAI_COMPATIBLE -> baseUrl.isNotBlank() &&
                (keyList.isNotEmpty() || !baseUrl.startsWith("https://"))
            else -> keyList.isNotEmpty() && model.isNotBlank()
        }

    companion object {
        fun defaults() = listOf(
            EngineConfig(
                id = "gemini",
                type = EngineType.GEMINI,
                label = "Google Gemini (free tier)",
                enabled = true,
                model = GeminiEngine.DEFAULT_MODEL,
            ),
            EngineConfig(
                id = "openrouter",
                type = EngineType.OPENAI_COMPATIBLE,
                label = "OpenRouter (free models)",
                enabled = true,
                baseUrl = "https://openrouter.ai/api/v1",
                vision = false,
            ),
            EngineConfig(
                id = "groq",
                type = EngineType.OPENAI_COMPATIBLE,
                label = "Groq (free tier)",
                enabled = true,
                baseUrl = "https://api.groq.com/openai/v1",
                vision = false,
            ),
            EngineConfig(
                id = "google_web",
                type = EngineType.GOOGLE_WEB,
                label = "Google Translate (free, no key, no context)",
                enabled = true,
            ),
            EngineConfig(
                id = "offline",
                type = EngineType.OFFLINE,
                label = "On-device (offline, no context)",
                enabled = true,
            ),
        )
    }
}

@Serializable
enum class EngineType {
    GEMINI,
    OPENAI_COMPATIBLE,
    ANTHROPIC,
    GOOGLE_WEB,
    OFFLINE,
}
