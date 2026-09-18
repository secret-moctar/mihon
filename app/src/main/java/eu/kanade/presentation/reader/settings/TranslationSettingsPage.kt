package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.loader.ReaderTranslationHook
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import mihon.feature.translate.TranslateLog
import mihon.feature.translate.engine.Languages
import mihon.feature.translate.model.GlossaryEntry
import mihon.feature.translate.model.SeriesMemory
import mihon.feature.translate.render.PageRenderer
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState as collectPrefAsState

@Composable
internal fun ColumnScope.TranslationSettingsPage(viewModel: ReaderViewModel) {
    val manager = viewModel.translationManager
    val prefs = manager.preferences
    val state by viewModel.state.collectAsState()
    val manga = state.manga ?: return

    var enabled by remember { mutableStateOf(viewModel.isTranslationEnabled()) }
    val showOriginal by prefs.showOriginal.collectPrefAsState()
    val target by prefs.targetLanguage.collectPrefAsState()
    val source by prefs.sourceLanguage.collectPrefAsState()
    val fontStyle by prefs.fontStyle.collectPrefAsState()
    val fontScale by prefs.fontScale.collectPrefAsState()

    if (!manager.hasUsableEngine()) {
        Text(
            text = stringResource(MR.strings.translate_no_engine),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = 8.dp),
        )
    }

    CheckboxItem(
        label = stringResource(MR.strings.translate_this_series),
        checked = enabled,
        onClick = {
            enabled = !enabled
            viewModel.setTranslationEnabled(enabled)
        },
    )
    CheckboxItem(
        label = stringResource(MR.strings.translate_show_original),
        checked = showOriginal,
        onClick = { viewModel.toggleShowOriginal() },
    )

    // Live progress of the current chapter.
    val hook = state.currentChapter?.pageLoader?.readyHook as? ReaderTranslationHook
    val progress by remember(hook) { hook?.currentJob?.progress ?: flowOf(null) }.collectAsState(null)
    progress?.let { p ->
        Text(
            text = buildString {
                append(stringResource(MR.strings.translate_progress, p.translatedPages, p.totalPages))
                p.error?.let { append("\n").append(it) }
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (p.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = 4.dp),
        )
    }

    SettingsChipRow(MR.strings.translate_target_language) {
        Languages.targets.forEach { language ->
            FilterChip(
                selected = target == language.code,
                onClick = {
                    prefs.targetLanguage.set(language.code)
                    viewModel.onTranslationSettingsChanged()
                },
                label = { Text(language.nativeName) },
            )
        }
    }

    SettingsChipRow(MR.strings.translate_source_language) {
        Languages.sources.forEach { language ->
            FilterChip(
                selected = source == language.code,
                onClick = { prefs.sourceLanguage.set(language.code) },
                label = { Text(language.englishName) },
            )
        }
    }

    SettingsChipRow(MR.strings.translate_font) {
        listOf(
            PageRenderer.FontStyle.COMIC to MR.strings.translate_font_comic,
            PageRenderer.FontStyle.SANS to MR.strings.translate_font_sans,
            PageRenderer.FontStyle.SERIF to MR.strings.translate_font_serif,
        ).forEach { (style, label) ->
            FilterChip(
                selected = fontStyle == style,
                onClick = {
                    prefs.fontStyle.set(style)
                    viewModel.onTranslationSettingsChanged()
                },
                label = { Text(stringResource(label)) },
            )
        }
    }

    SliderItem(
        value = fontScale,
        valueRange = 60..140 step 10,
        steps = 7,
        label = stringResource(MR.strings.translate_font_size),
        valueString = "$fontScale%",
        onChange = {
            prefs.fontScale.set(it)
            viewModel.onTranslationSettingsChanged()
        },
    )

    CheckboxItem(label = stringResource(MR.strings.translate_sfx), pref = prefs.translateSfx)
    CheckboxItem(label = stringResource(MR.strings.translate_original_while_loading), pref = prefs.showOriginalWhileTranslating)
    CheckboxItem(label = stringResource(MR.strings.translate_pretranslate_next), pref = prefs.pretranslateNextChapter)

    var showMemory by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.medium, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = { showMemory = true }) {
            Text(stringResource(MR.strings.translate_series_memory))
        }
    }

    // Recent diagnostics, so problems can be understood without a computer.
    val log by TranslateLog.lines.collectAsState()
    if (log.isNotEmpty()) {
        HeadingItem(stringResource(MR.strings.translate_log))
        Text(
            text = log.takeLast(20).joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = 4.dp),
        )
    }

    if (showMemory) {
        SeriesMemoryDialog(
            viewModel = viewModel,
            mangaId = manga.id,
            title = manga.title,
            language = target,
            onDismissRequest = { showMemory = false },
        )
    }
}

/**
 * Shows and edits what the translator remembers about a series: names, terms, characters and
 * user notes. Entries edited here are locked so the model will not change them.
 */
@Composable
private fun SeriesMemoryDialog(
    viewModel: ReaderViewModel,
    mangaId: Long,
    title: String,
    language: String,
    onDismissRequest: () -> Unit,
) {
    val store = viewModel.translationManager.store
    val scope = rememberCoroutineScope()
    var memory by remember { mutableStateOf<SeriesMemory?>(null) }
    var notes by remember { mutableStateOf("") }
    var newSource by remember { mutableStateOf("") }
    var newTarget by remember { mutableStateOf("") }

    LaunchedEffect(mangaId, language) {
        memory = store.loadMemory(mangaId, title, language).also { notes = it.userNotes }
    }

    fun save(transform: (SeriesMemory) -> SeriesMemory) {
        scope.launch { memory = store.updateMemory(mangaId, title, language, transform) }
    }

    AlertDialog(
        onDismissRequest = {
            save { it.copy(userNotes = notes) }
            onDismissRequest()
        },
        title = { Text(stringResource(MR.strings.translate_series_memory)) },
        text = {
            val current = memory
            if (current == null) {
                Text(stringResource(MR.strings.loading))
                return@AlertDialog
            }
            LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                item {
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text(stringResource(MR.strings.translate_series_notes)) },
                        placeholder = { Text(stringResource(MR.strings.translate_series_notes_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                }
                item { HeadingItem(stringResource(MR.strings.translate_glossary)) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(
                            value = newSource,
                            onValueChange = { newSource = it },
                            label = { Text(stringResource(MR.strings.translate_glossary_original)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = newTarget,
                            onValueChange = { newTarget = it },
                            label = { Text(stringResource(MR.strings.translate_glossary_translation)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(
                            enabled = newSource.isNotBlank() && newTarget.isNotBlank(),
                            onClick = {
                                val entry = GlossaryEntry(newSource.trim(), newTarget.trim(), locked = true)
                                save { m -> m.copy(glossary = m.glossary.filterNot { it.source.equals(entry.source, true) } + entry) }
                                newSource = ""
                                newTarget = ""
                            },
                        ) { Text(stringResource(MR.strings.action_add)) }
                    }
                }
                items(current.glossary.reversed(), key = { "g:" + it.source }) { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${entry.source} → ${entry.target}" + if (entry.locked) " 🔒" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                        )
                        TextButton(onClick = { save { m -> m.copy(glossary = m.glossary - entry) } }) {
                            Text(stringResource(MR.strings.action_delete))
                        }
                    }
                }
                if (current.characters.isNotEmpty()) {
                    item { HeadingItem(stringResource(MR.strings.translate_characters)) }
                    items(current.characters, key = { "c:" + it.name }) { character ->
                        Text(
                            text = buildString {
                                append(character.name)
                                if (character.originalName.isNotBlank()) append(" (").append(character.originalName).append(")")
                                listOf(character.gender, character.speechStyle).filter { it.isNotBlank() }
                                    .takeIf { it.isNotEmpty() }?.let { append(": ").append(it.joinToString("; ")) }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
                if (current.chapterSummaries.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(MR.strings.translate_summaries_count, current.chapterSummaries.size),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                save { it.copy(userNotes = notes) }
                onDismissRequest()
            }) { Text(stringResource(MR.strings.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = {
                scope.launch {
                    store.clearMemory(mangaId, language)
                    memory = store.loadMemory(mangaId, title, language)
                    notes = ""
                }
            }) { Text(stringResource(MR.strings.translate_clear_memory)) }
        },
    )
}
