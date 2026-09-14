package eu.kanade.presentation.more.settings.screen

import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import eu.kanade.presentation.more.settings.Preference
import kotlinx.coroutines.launch
import eu.kanade.tachiyomi.util.system.toast
import mihon.app.di.appGraph
import mihon.feature.translate.EngineConfig
import mihon.feature.translate.EngineType
import mihon.feature.translate.engine.BlockImage
import mihon.feature.translate.engine.Languages
import mihon.feature.translate.engine.OpenAiCompatibleEngine
import mihon.feature.translate.engine.TranslationRequest
import mihon.feature.translate.model.Box
import mihon.feature.translate.model.PageText
import mihon.feature.translate.model.SeriesMemory
import mihon.feature.translate.model.TextBlock
import mihon.feature.translate.model.TextLine
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

object SettingsTranslationScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_translation

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val manager = remember { context.appGraph.translationManager }
        val prefs = manager.preferences
        val sourceManager = remember { context.appGraph.sourceManager }

        var engines by remember { mutableStateOf(prefs.engineConfigs()) }
        var editing by remember { mutableStateOf<EngineConfig?>(null) }
        fun saveEngines(list: List<EngineConfig>) {
            engines = list
            prefs.saveEngineConfigs(list)
        }

        editing?.let { config ->
            EngineDialog(
                initial = config,
                index = engines.indexOfFirst { it.id == config.id },
                count = engines.size,
                onDismissRequest = { editing = null },
                onSave = { updated ->
                    val list = engines.toMutableList()
                    val i = list.indexOfFirst { it.id == updated.id }
                    if (i >= 0) list[i] = updated else list += updated
                    saveEngines(list)
                    editing = null
                },
                onMove = { delta ->
                    val list = engines.toMutableList()
                    val i = list.indexOfFirst { it.id == config.id }
                    val j = i + delta
                    if (i >= 0 && j in list.indices) {
                        list.add(j, list.removeAt(i))
                        saveEngines(list)
                    }
                },
                onDelete = if (config.id in EngineConfig.defaults().map { it.id }) {
                    null
                } else {
                    { saveEngines(engines.filterNot { it.id == config.id }); editing = null }
                },
            )
        }

        val sources by produceState(initialValue = emptyMap<String, String>()) {
            value = sourceManager.getOnlineSources()
                .sortedWith(compareBy({ it.lang }, { it.name }))
                .associate { it.id.toString() to "${it.name} (${it.lang})" }
        }
        val cacheSize by produceState(initialValue = "…", manager) {
            value = Formatter.formatFileSize(context, manager.store.cacheSizeBytes())
        }
        val pagesPerRequest by prefs.pagesPerRequest.collectAsState()
        val lookahead by prefs.lookaheadPages.collectAsState()
        val summaries by prefs.previousChapterSummaries.collectAsState()
        val cacheMb by prefs.renderedCacheSizeMb.collectAsState()
        val scope = rememberCoroutineScope()

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.translate_pref_engines),
                preferenceItems = buildList {
                    add(Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.translate_pref_engines_summary)))
                    engines.forEachIndexed { index, config ->
                        add(
                            Preference.PreferenceItem.TextPreference(
                                title = "${index + 1}. ${config.label}",
                                subtitle = when {
                                    !config.enabled -> stringResource(MR.strings.translate_engine_off)
                                    config.isUsable -> stringResource(MR.strings.translate_engine_ready) +
                                        config.model.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                                    else -> stringResource(MR.strings.translate_engine_not_ready)
                                },
                                onClick = { editing = config },
                            ),
                        )
                    }
                    add(
                        Preference.PreferenceItem.TextPreference(
                            title = stringResource(MR.strings.translate_engine_add),
                            onClick = {
                                editing = EngineConfig(
                                    type = EngineType.OPENAI_COMPATIBLE,
                                    label = "Custom",
                                    enabled = true,
                                    vision = false,
                                )
                            },
                        ),
                    )
                },
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.translate_tab),
                preferenceItems = listOf(
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.targetLanguage,
                        entries = Languages.targets.associate { it.code to "${it.nativeName} (${it.englishName})" },
                        title = stringResource(MR.strings.translate_target_language),
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = prefs.sourceLanguage,
                        entries = Languages.sources.associate { it.code to it.englishName },
                        title = stringResource(MR.strings.translate_source_language),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.translateAllByDefault,
                        title = stringResource(MR.strings.translate_pref_all_series),
                    ),
                    Preference.PreferenceItem.MultiSelectListPreference(
                        preference = prefs.enabledSources,
                        entries = sources,
                        title = stringResource(MR.strings.translate_pref_sources),
                        subtitle = stringResource(MR.strings.translate_pref_sources_summary),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.translateDownloads,
                        title = stringResource(MR.strings.translate_pref_downloads),
                        subtitle = stringResource(MR.strings.translate_pref_downloads_summary),
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.translate_pref_context),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SliderPreference(
                        value = pagesPerRequest,
                        valueRange = 1..15,
                        title = stringResource(MR.strings.translate_pref_pages_per_request),
                        onValueChanged = { prefs.pagesPerRequest.set(it) },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = lookahead,
                        valueRange = 0..6,
                        title = stringResource(MR.strings.translate_pref_lookahead),
                        onValueChanged = { prefs.lookaheadPages.set(it) },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = summaries,
                        valueRange = 0..20,
                        title = stringResource(MR.strings.translate_pref_previous_summaries),
                        onValueChanged = { prefs.previousChapterSummaries.set(it) },
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.pretranslateNextChapter,
                        title = stringResource(MR.strings.translate_pretranslate_next),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.sendBubbleImages,
                        title = stringResource(MR.strings.translate_pref_send_images),
                        subtitle = stringResource(MR.strings.translate_pref_send_images_summary),
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = prefs.globalInstructions,
                        title = stringResource(MR.strings.translate_pref_instructions),
                        subtitle = stringResource(MR.strings.translate_pref_instructions_hint),
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.translate_pref_display),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.translateSfx,
                        title = stringResource(MR.strings.translate_sfx),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.uppercaseLatin,
                        title = stringResource(MR.strings.translate_pref_uppercase),
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.showOriginalWhileTranslating,
                        title = stringResource(MR.strings.translate_original_while_loading),
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.translate_pref_storage),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SliderPreference(
                        value = cacheMb,
                        valueRange = 50..1000 step 50,
                        steps = 18,
                        title = stringResource(MR.strings.translate_pref_cache_size),
                        valueString = "$cacheMb MB",
                        onValueChanged = { prefs.renderedCacheSizeMb.set(it) },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.translate_pref_clear_cache),
                        subtitle = stringResource(MR.strings.translate_pref_clear_cache_summary, cacheSize),
                        onClick = {
                            scope.launch {
                                manager.store.clearCaches()
                                context.toast(MR.strings.translate_cache_cleared)
                            }
                        },
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = prefs.offlineModelsWifiOnly,
                        title = stringResource(MR.strings.translate_pref_offline_wifi),
                    ),
                ),
            ),
        )
    }
}

@Composable
private fun EngineDialog(
    initial: EngineConfig,
    index: Int,
    count: Int,
    onDismissRequest: () -> Unit,
    onSave: (EngineConfig) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val context = LocalContext.current
    val manager = remember { context.appGraph.translationManager }
    val scope = rememberCoroutineScope()

    var label by remember { mutableStateOf(initial.label) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var keys by remember { mutableStateOf(initial.apiKeys) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var model by remember { mutableStateOf(initial.model) }
    var vision by remember { mutableStateOf(initial.vision) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableIntStateOf(0) }

    fun current() = initial.copy(
        label = label.trim().ifBlank { initial.label },
        enabled = enabled,
        apiKeys = keys.trim(),
        baseUrl = baseUrl.trim(),
        model = model.trim(),
        vision = vision,
    )

    val keyUrl = when {
        initial.type == EngineType.GEMINI -> "https://aistudio.google.com/apikey"
        initial.type == EngineType.ANTHROPIC -> "https://console.anthropic.com/settings/keys"
        baseUrl.contains("openrouter.ai") -> "https://openrouter.ai/settings/keys"
        baseUrl.contains("groq.com") -> "https://console.groq.com/keys"
        baseUrl.contains("mistral.ai") -> "https://console.mistral.ai/api-keys"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(initial.label) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = enabled, onCheckedChange = { enabled = it })
                        Text(stringResource(MR.strings.translate_engine_enabled))
                    }
                }
                if (initial.type == EngineType.OPENAI_COMPATIBLE && initial.id !in EngineConfig.defaults().map { it.id }) {
                    item {
                        OutlinedTextField(value = label, onValueChange = { label = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            OpenAiCompatibleEngine.presets.forEach { preset ->
                                TextButton(onClick = {
                                    baseUrl = preset.baseUrl
                                    if (label.isBlank() || label == "Custom") label = preset.label
                                }) { Text(preset.label) }
                            }
                        }
                    }
                }
                if (initial.type == EngineType.OPENAI_COMPATIBLE) {
                    item {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = { Text(stringResource(MR.strings.translate_engine_base_url)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (initial.type != EngineType.OFFLINE && initial.type != EngineType.GOOGLE_WEB) {
                    item {
                        OutlinedTextField(
                            value = keys,
                            onValueChange = { keys = it },
                            label = { Text(stringResource(MR.strings.translate_engine_keys)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                        )
                    }
                    if (keyUrl != null) {
                        item {
                            TextButton(onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, keyUrl.toUri()))
                            }) { Text(stringResource(MR.strings.translate_engine_get_key)) }
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = model,
                            onValueChange = { model = it },
                            label = { Text(stringResource(MR.strings.translate_engine_model)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (initial.type == EngineType.OPENAI_COMPATIBLE) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = vision, onCheckedChange = { vision = it })
                                Text(stringResource(MR.strings.translate_engine_vision))
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                busy++
                                status = null
                                scope.launch {
                                    try {
                                        models = manager.createEngine(current().copy(model = model.ifBlank { "x" })).listModels()
                                        if (models.isEmpty()) status = "No models returned"
                                    } catch (e: Exception) {
                                        status = e.message
                                    } finally {
                                        busy--
                                    }
                                }
                            }) { Text(stringResource(MR.strings.translate_engine_load_models)) }
                            TextButton(onClick = {
                                busy++
                                status = null
                                scope.launch {
                                    status = try {
                                        val result = manager.createEngine(current()).translate(sampleRequest())
                                        "✓ " + result.translations.joinToString(" / ") { it.text }
                                    } catch (e: Exception) {
                                        "✗ " + (e.message ?: e.toString())
                                    } finally {
                                        busy--
                                    }
                                }
                            }) { Text(stringResource(MR.strings.translate_engine_test)) }
                            if (busy > 0) CircularProgressIndicator(modifier = Modifier.heightIn(max = 20.dp))
                        }
                    }
                }
                status?.let { message ->
                    item {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (message.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (models.isNotEmpty()) {
                    items(models.take(200)) { id ->
                        TextButton(onClick = { model = id }, modifier = Modifier.fillMaxWidth()) {
                            Text(id, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                item {
                    Column {
                        Row {
                            TextButton(enabled = index > 0, onClick = { onMove(-1) }) {
                                Text(stringResource(MR.strings.translate_engine_move_up))
                            }
                            TextButton(enabled = index in 0 until count - 1, onClick = { onMove(1) }) {
                                Text(stringResource(MR.strings.translate_engine_move_down))
                            }
                        }
                        if (onDelete != null) {
                            TextButton(onClick = onDelete) { Text(stringResource(MR.strings.action_delete)) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(current()) }) { Text(stringResource(MR.strings.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}

/** A tiny two-bubble page used by the Test button. */
private fun sampleRequest() = TranslationRequest(
    sourceLanguage = "ko",
    targetLanguage = "en",
    seriesTitle = "Test",
    memory = SeriesMemory(mangaId = -1, title = "Test", targetLanguage = "en"),
    chapterName = "Test",
    chapterNumber = 1.0,
    previousChapterSummaries = emptyList(),
    chapterSummarySoFar = "",
    recentLines = emptyList(),
    pages = listOf(
        PageText(
            pageIndex = 0,
            width = 800,
            height = 1200,
            blocks = listOf(
                TextBlock("0.1", listOf(TextLine("안녕하세요!", Box(100, 100, 300, 140))), "ko"),
                TextBlock("0.2", listOf(TextLine("오늘 날씨 좋네.", Box(400, 500, 700, 540))), "ko"),
            ),
        ),
    ),
    lookahead = emptyList(),
    images = emptyList<BlockImage>(),
    translateSfx = false,
)
