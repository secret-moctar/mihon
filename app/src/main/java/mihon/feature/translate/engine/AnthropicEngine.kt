package mihon.feature.translate.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
 * Anthropic Claude through the Messages API (paid, no free tier). Called over plain HTTP so it
 * shares the OkHttp stack with the other engines instead of pulling a second HTTP client into
 * the APK.
 */
class AnthropicEngine(
    client: OkHttpClient,
    apiKeys: List<String>,
    private val model: String,
) : LlmEngine(client, apiKeys) {

    override val name = "Claude ($model)"
    override val supportsImages = true
    override val maxInputChars = 80_000

    override suspend fun complete(system: String, user: String, images: List<BlockImage>): String {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", MAX_OUTPUT_TOKENS)
            put("system", system)
            // Routine per-page work: low effort keeps latency and cost down.
            putJsonObject("output_config") { put("effort", "low") }
            // Re-run on a suitable model if a safety classifier declines a page.
            put("fallbacks", "default")
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        images.forEach { image ->
                            addJsonObject {
                                put("type", "text")
                                put("text", "Block ${image.blockId}:")
                            }
                            addJsonObject {
                                put("type", "image")
                                putJsonObject("source") {
                                    put("type", "base64")
                                    put("media_type", "image/jpeg")
                                    put("data", Base64.encode(image.jpeg))
                                }
                            }
                        }
                        addJsonObject {
                            put("type", "text")
                            put("text", user)
                        }
                    }
                }
            }
        }

        val response = postJson(
            "$BASE_URL/v1/messages",
            body,
            mapOf(
                "x-api-key" to currentKey(),
                "anthropic-version" to "2023-06-01",
                "anthropic-beta" to "server-side-fallback-2026-07-01",
            ),
        )

        val stopReason = response["stop_reason"]?.jsonPrimitive?.contentOrNull
        if (stopReason == "refusal") {
            throw TranslationException("Claude declined to translate this page", retryable = false)
        }
        val text = (response["content"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
            .mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
            .joinToString("")
        if (text.isBlank()) {
            throw TranslationException("Claude returned an empty answer ($stopReason)", retryable = true)
        }
        return text
    }

    override suspend fun listModels(): List<String> {
        val response = getJson(
            "$BASE_URL/v1/models?limit=100",
            mapOf("x-api-key" to currentKey(), "anthropic-version" to "2023-06-01"),
        )
        val ids = (response["data"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }
        return (listOf(DEFAULT_MODEL) + ids).distinct()
    }

    companion object {
        private const val BASE_URL = "https://api.anthropic.com"
        const val DEFAULT_MODEL = "claude-opus-5"
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
