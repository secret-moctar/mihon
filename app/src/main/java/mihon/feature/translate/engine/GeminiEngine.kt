package mihon.feature.translate.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import kotlin.io.encoding.Base64

/**
 * Google Gemini through the Generative Language API. Has a free tier (API key from
 * aistudio.google.com), reads images, and follows JSON output instructions well.
 */
class GeminiEngine(
    client: OkHttpClient,
    apiKeys: List<String>,
    private val model: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : LlmEngine(client, apiKeys) {

    override val name = "Gemini ($model)"
    override val supportsImages = true
    override val maxInputChars = 60_000

    // The free tier allows only a few requests per day per model.
    override val preferredPagesPerRequest = 15

    override suspend fun complete(system: String, user: String, images: List<BlockImage>): String {
        val body = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", system) } }
            }
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject { put("text", user) }
                        images.forEach { image ->
                            addJsonObject { put("text", "Block ${image.blockId}:") }
                            addJsonObject {
                                putJsonObject("inlineData") {
                                    put("mimeType", "image/jpeg")
                                    put("data", Base64.encode(image.jpeg))
                                }
                            }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", 0.35)
                put("maxOutputTokens", MAX_OUTPUT_TOKENS)
                put("responseMimeType", "application/json")
            }
            // Comics are fiction with fights and insults; default filters block ordinary chapters.
            putJsonArray("safetySettings") {
                SAFETY_CATEGORIES.forEach { category ->
                    addJsonObject {
                        put("category", category)
                        put("threshold", "BLOCK_NONE")
                    }
                }
            }
        }

        val url = "${baseUrl.trimEnd('/')}/models/${model.removePrefix("models/")}:generateContent"
        val response = postJson(url, body, mapOf("x-goog-api-key" to currentKey()))
        return extractText(response)
    }

    private fun extractText(response: JsonObject): String {
        val blockReason = response["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.contentOrNull
        if (blockReason != null) {
            throw TranslationException("Gemini refused this page ($blockReason)", retryable = false)
        }
        val candidate = (response["candidates"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?: throw TranslationException("Gemini returned no answer", retryable = true)
        val text = (candidate["content"]?.jsonObject?.get("parts") as? JsonArray).orEmpty()
            .map { it.jsonObject }
            // Skip thought summaries of thinking models.
            .filter { it["thought"]?.jsonPrimitive?.booleanOrNull != true }
            .mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
            .joinToString("")
        if (text.isBlank()) {
            val reason = candidate["finishReason"]?.jsonPrimitive?.contentOrNull
            throw TranslationException("Gemini returned an empty answer ($reason)", retryable = reason != "SAFETY")
        }
        return text
    }

    override suspend fun listModels(): List<String> {
        val models = mutableListOf<String>()
        var pageToken: String? = null
        do {
            val url = buildString {
                append(baseUrl.trimEnd('/')).append("/models?pageSize=1000")
                if (pageToken != null) append("&pageToken=").append(pageToken)
            }
            val response = getJson(url, mapOf("x-goog-api-key" to currentKey()))
            (response["models"] as? JsonArray).orEmpty().forEach { element ->
                val obj = element.jsonObject
                val methods = obj["supportedGenerationMethods"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                if ("generateContent" in methods) {
                    obj["name"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/")?.let(models::add)
                }
            }
            pageToken = response["nextPageToken"]?.jsonPrimitive?.contentOrNull
        } while (!pageToken.isNullOrEmpty())
        return listOf(DEFAULT_MODEL, "gemini-flash-lite-latest") +
            models.filter { "embedding" !in it && "tts" !in it && "image" !in it }.sorted()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"

        /** Alias that Google keeps pointed at its current Flash model, which is on the free tier. */
        const val DEFAULT_MODEL = "gemini-flash-latest"

        /** Lighter model with its own free quota, used after the main one runs out. */
        const val LITE_MODEL = "gemini-flash-lite-latest"

        private val SAFETY_CATEGORIES = listOf(
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_DANGEROUS_CONTENT",
        )
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
