package mihon.feature.translate.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import kotlin.io.encoding.Base64

/**
 * Any service speaking the OpenAI chat completions protocol: OpenRouter (has free models), Groq,
 * Mistral, DeepSeek, OpenAI, or a model running on your own computer with Ollama / LM Studio.
 */
class OpenAiCompatibleEngine(
    client: OkHttpClient,
    apiKeys: List<String>,
    baseUrl: String,
    model: String,
    private val visionEnabled: Boolean,
) : LlmEngine(client, apiKeys) {

    private val baseUrl = baseUrl.trim().trimEnd('/')
    private val configuredModel = model.trim()

    /** Model picked from the service's list when none is configured. */
    @Volatile
    private var resolvedModel: String? = configuredModel.ifBlank { null }

    override val name = "${hostLabel(this.baseUrl)} (${configuredModel.ifBlank { "auto" }})"
    override val supportsImages get() = visionEnabled
    override val maxInputChars = 40_000

    // Free tiers limit requests per day more than tokens.
    override val preferredPagesPerRequest = 10

    /** Flipped off after a server rejects response_format, so later requests skip it. */
    @Volatile
    private var useJsonMode = true

    override suspend fun complete(system: String, user: String, images: List<BlockImage>): String {
        return try {
            send(system, user, images, useJsonMode)
        } catch (e: TranslationException) {
            if (useJsonMode && !e.retryable) {
                useJsonMode = false
                send(system, user, images, jsonMode = false)
            } else {
                throw e
            }
        }
    }

    private suspend fun model(): String {
        resolvedModel?.let { return it }
        val available = try {
            listModels()
        } catch (e: TranslationException) {
            throw TranslationException("$name: could not list models. ${e.message}", e.retryable, e.retryAfterMs, e)
        }
        val picked = pickModel(available)
            ?: throw TranslationException("$name: no usable model found, choose one in settings", retryable = false)
        resolvedModel = picked
        mihon.feature.translate.TranslateLog.i("$name: using model $picked")
        return picked
    }

    private suspend fun send(system: String, user: String, images: List<BlockImage>, jsonMode: Boolean): String {
        val model = model()
        val body = buildJsonObject {
            put("model", model)
            put("temperature", 0.35)
            put("max_tokens", MAX_OUTPUT_TOKENS)
            if (jsonMode) {
                putJsonObject("response_format") { put("type", "json_object") }
            }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", system)
                }
                addJsonObject {
                    put("role", "user")
                    if (images.isEmpty()) {
                        put("content", user)
                    } else {
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", "text")
                                put("text", user)
                            }
                            images.forEach { image ->
                                addJsonObject {
                                    put("type", "text")
                                    put("text", "Block ${image.blockId}:")
                                }
                                addJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") {
                                        put("url", "data:image/jpeg;base64," + Base64.encode(image.jpeg))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val response = postJson("$baseUrl/chat/completions", body, headers())
        val message = (response["choices"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?: throw TranslationException("$name returned no answer", retryable = true)
        val content = when (val c = message["content"]) {
            is JsonPrimitive -> c.contentOrNull
            is JsonArray -> c.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }.joinToString("")
            else -> null
        }
        if (content.isNullOrBlank()) {
            throw TranslationException("$name returned an empty answer", retryable = true)
        }
        return content
    }

    private fun headers(): Map<String, String> = buildMap {
        val key = currentKey()
        if (key.isNotEmpty()) put("Authorization", "Bearer $key")
        // OpenRouter shows this name on its dashboard; ignored elsewhere.
        put("X-Title", "Mihon Translate")
    }

    override suspend fun listModels(): List<String> {
        val response = getJson("$baseUrl/models", headers())
        val data = (response["data"] as? JsonArray) ?: (response["models"] as? JsonArray) ?: return emptyList()
        return data.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }
            // Free models first on OpenRouter.
            .sortedWith(compareBy<String> { !it.endsWith(":free") }.thenBy { it })
    }

    companion object {
        /** Model ids change often on these services, so the user picks one from the live list. */
        data class Preset(val label: String, val baseUrl: String, val needsKey: Boolean = true)

        val presets = listOf(
            Preset("OpenRouter", "https://openrouter.ai/api/v1"),
            Preset("Groq", "https://api.groq.com/openai/v1"),
            Preset("Mistral", "https://api.mistral.ai/v1"),
            Preset("DeepSeek", "https://api.deepseek.com/v1"),
            Preset("OpenAI", "https://api.openai.com/v1"),
            Preset("Ollama (your PC)", "http://192.168.1.10:11434/v1", needsKey = false),
            Preset("LM Studio (your PC)", "http://192.168.1.10:1234/v1", needsKey = false),
        )

        private val unsuitable = Regex("whisper|tts|audio|guard|embed|moderation|image|vision-preview|r1|thinking|reasoning|coder|:online", RegexOption.IGNORE_CASE)
        private val preferred = listOf("deepseek-chat", "llama-3.3-70b", "gpt-oss-120b", "qwen3", "llama-4", "gemma-3-27b", "mistral-small")

        /** Chooses a capable general chat model; on OpenRouter only free ones. */
        internal fun pickModel(ids: List<String>): String? {
            val openRouter = ids.any { it.endsWith(":free") }
            val candidates = ids.filter { !unsuitable.containsMatchIn(it) && (!openRouter || it.endsWith(":free")) }
            preferred.forEach { wanted -> candidates.firstOrNull { it.contains(wanted, ignoreCase = true) }?.let { return it } }
            return candidates.firstOrNull()
        }

        fun hostLabel(baseUrl: String): String {
            val host = baseUrl.substringAfter("://").substringBefore('/').substringBefore(':')
            return presets.firstOrNull { it.baseUrl.contains(host) }?.label?.substringBefore(" (") ?: host
        }
    }
}
