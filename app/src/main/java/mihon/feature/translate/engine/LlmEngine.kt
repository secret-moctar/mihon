package mihon.feature.translate.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resumeWithException

/**
 * Shared plumbing for HTTP based LLM engines: key rotation, error mapping and response parsing.
 */
abstract class LlmEngine(
    protected val client: OkHttpClient,
    /** One or more API keys; rotated when a key is rate limited. */
    apiKeys: List<String>,
) : TranslationEngine {

    protected val keys = apiKeys.map { it.trim() }.filter { it.isNotEmpty() }
    private val keyIndex = AtomicInteger(0)

    override val contextAware = true

    protected val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    protected fun currentKey(): String = if (keys.isEmpty()) "" else keys[Math.floorMod(keyIndex.get(), keys.size)]

    private fun rotateKey(): Boolean {
        if (keys.size <= 1) return false
        keyIndex.incrementAndGet()
        return true
    }

    /** Sends the prompt and returns the raw text the model produced. */
    protected abstract suspend fun complete(system: String, user: String, images: List<BlockImage>): String

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        val system = PromptBuilder.systemPrompt(request)
        val user = PromptBuilder.userPrompt(request)
        val images = if (supportsImages) request.images else emptyList()

        var attemptsLeft = keys.size.coerceAtLeast(1)
        while (true) {
            try {
                val raw = complete(system, user, images)
                val expectedIds = request.pages.flatMap { page -> page.blocks.map { it.id } }
                val parsed = ResponseParser.parse(raw, expectedIds)
                return TranslationResult(parsed.translations, parsed.memoryUpdate, parsed.chapterSummary, name)
            } catch (e: TranslationException) {
                attemptsLeft--
                // A rate limited key can be swapped for another one immediately.
                if (e.retryable && attemptsLeft > 0 && rotateKey()) continue
                throw e
            }
        }
    }

    protected suspend fun postJson(
        url: String,
        body: JsonObject,
        headers: Map<String, String> = emptyMap(),
    ): JsonObject {
        val request = Request.Builder()
            .url(url)
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        return execute(request)
    }

    protected suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonObject {
        val request = Request.Builder()
            .url(url)
            .get()
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        return execute(request)
    }

    private suspend fun execute(request: Request): JsonObject = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            throw TranslationException("Network error: ${e.message}", retryable = true, cause = e)
        }
        response.use {
            val text = it.body.string()
            if (!it.isSuccessful) {
                throw mapHttpError(it.code, text, it.header("retry-after"))
            }
            try {
                json.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                throw TranslationException("Unexpected reply from ${request.url.host}", retryable = true, cause = e)
            }
        }
    }

    protected open fun mapHttpError(code: Int, body: String, retryAfter: String?): TranslationException {
        val message = extractErrorMessage(body)
        val retryAfterMs = retryAfter?.toDoubleOrNull()?.let { (it * 1000).toLong() }
            ?: Regex("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"").find(body)
                ?.groupValues?.get(1)?.toDoubleOrNull()?.let { (it * 1000).toLong() }
        // Daily quotas reset once a day; retrying within seconds only wastes time.
        val daily = Regex("per.?day|daily", RegexOption.IGNORE_CASE).containsMatchIn(body)
        if (code == 429 && daily) {
            return TranslationException(
                "$name: free daily limit reached, using other engines for now. $message",
                retryable = true,
                retryAfterMs = DAILY_LIMIT_COOLDOWN_MS,
            )
        }
        return when (code) {
            401, 403 -> TranslationException("$name: API key rejected ($code). $message", retryable = false)
            404 -> TranslationException("$name: model or endpoint not found. $message", retryable = false)
            408, 409, 425, 429 -> TranslationException(
                "$name: rate limited, waiting. $message",
                retryable = true,
                retryAfterMs = retryAfterMs,
            )
            in 500..599 -> TranslationException("$name: service error $code. $message", retryable = true, retryAfterMs)
            else -> TranslationException("$name: request failed ($code). $message", retryable = false)
        }
    }

    private fun extractErrorMessage(body: String): String = try {
        val obj = json.parseToJsonElement(body).jsonObject
        val error = obj["error"]
        when (error) {
            is JsonObject -> error["message"]?.toString()?.trim('"') ?: body.take(300)
            null -> obj["message"]?.toString()?.trim('"') ?: body.take(300)
            else -> error.toString().trim('"')
        }
    } catch (_: Exception) {
        body.take(300)
    }

    companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        const val MAX_OUTPUT_TOKENS = 16000
        const val DAILY_LIMIT_COOLDOWN_MS = 60 * 60_000L
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
        },
    )
    continuation.invokeOnCancellation { runCatching { cancel() } }
}
