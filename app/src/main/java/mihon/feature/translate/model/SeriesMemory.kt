package mihon.feature.translate.model

import kotlinx.serialization.Serializable

/**
 * Long-lived translation context for one series and one target language.
 *
 * It is what keeps names, terms and the way characters talk consistent across chapters. It is
 * built up automatically from model responses and can be edited by the user.
 */
@Serializable
data class SeriesMemory(
    val version: Int = 1,
    val mangaId: Long,
    val title: String,
    val targetLanguage: String,
    val glossary: List<GlossaryEntry> = emptyList(),
    val characters: List<CharacterProfile> = emptyList(),
    /** Chapter number -> short summary. */
    val chapterSummaries: Map<String, String> = emptyMap(),
    /** Free-form instructions written by the user, e.g. "keep honorifics". */
    val userNotes: String = "",
) {
    fun merge(update: MemoryUpdate, maxGlossary: Int = 400, maxCharacters: Int = 120): SeriesMemory {
        val glossaryBySource = LinkedHashMap<String, GlossaryEntry>()
        glossary.forEach { glossaryBySource[it.source.normalizedKey()] = it }
        update.glossary.forEach { entry ->
            if (entry.source.isBlank() || entry.target.isBlank()) return@forEach
            val key = entry.source.normalizedKey()
            val existing = glossaryBySource[key]
            // User-locked entries always win; otherwise keep the first translation for consistency
            // unless the model explicitly marks it as a correction.
            glossaryBySource[key] = when {
                existing == null -> entry
                existing.locked -> existing
                entry.correction -> entry.copy(correction = false)
                else -> existing.copy(note = existing.note.ifBlank { entry.note })
            }
        }

        val charactersByName = LinkedHashMap<String, CharacterProfile>()
        characters.forEach { charactersByName[it.name.normalizedKey()] = it }
        update.characters.forEach { profile ->
            if (profile.name.isBlank()) return@forEach
            val key = profile.name.normalizedKey()
            val existing = charactersByName[key]
            charactersByName[key] = if (existing == null) {
                profile
            } else if (existing.locked) {
                existing
            } else {
                existing.copy(
                    originalName = existing.originalName.ifBlank { profile.originalName },
                    gender = existing.gender.ifBlank { profile.gender },
                    speechStyle = profile.speechStyle.ifBlank { existing.speechStyle },
                    relationships = profile.relationships.ifBlank { existing.relationships },
                )
            }
        }

        return copy(
            glossary = glossaryBySource.values.toList().takeLast(maxGlossary),
            characters = charactersByName.values.toList().takeLast(maxCharacters),
        )
    }

    fun withChapterSummary(chapterNumber: Double, summary: String, keep: Int = 200): SeriesMemory {
        if (summary.isBlank()) return this
        val updated = (chapterSummaries + (chapterNumber.toSummaryKey() to summary.trim()))
            .entries
            .sortedBy { it.key.toDoubleOrNull() ?: 0.0 }
            .takeLast(keep)
            .associate { it.key to it.value }
        return copy(chapterSummaries = updated)
    }

    /** Summaries of chapters strictly before [chapterNumber], most recent last. */
    fun summariesBefore(chapterNumber: Double, count: Int): List<Pair<String, String>> =
        chapterSummaries.entries
            .mapNotNull { e -> e.key.toDoubleOrNull()?.let { it to e.value } }
            .filter { it.first < chapterNumber }
            .sortedBy { it.first }
            .takeLast(count)
            .map { it.first.toSummaryKey() to it.second }

    companion object {
        fun Double.toSummaryKey(): String =
            if (this == Math.floor(this)) toLong().toString() else toString()

        private fun String.normalizedKey() = trim().lowercase()
    }
}

@Serializable
data class GlossaryEntry(
    val source: String,
    val target: String,
    /** character, place, skill, item, organization, term, honorific... */
    val category: String = "",
    val note: String = "",
    /** Set when the user edited this entry; the model can no longer change it. */
    val locked: Boolean = false,
    /** Only meaningful inside a [MemoryUpdate]: model is fixing an earlier wrong entry. */
    val correction: Boolean = false,
)

@Serializable
data class CharacterProfile(
    /** Name in the target language. */
    val name: String,
    val originalName: String = "",
    val gender: String = "",
    /** How they talk: formal/casual, rude, archaic, verbal tics, how they address others. */
    val speechStyle: String = "",
    val relationships: String = "",
    val locked: Boolean = false,
)

@Serializable
data class MemoryUpdate(
    val glossary: List<GlossaryEntry> = emptyList(),
    val characters: List<CharacterProfile> = emptyList(),
)
