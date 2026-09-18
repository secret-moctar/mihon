package mihon.feature.translate

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Translation diagnostics: written to logcat under one tag and kept in memory so the reader's
 * translation tab can show what happened without a computer.
 */
object TranslateLog {
    private const val TAG = "MihonTranslate"
    private const val MAX_LINES = 80

    private val format = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun i(message: String) = add(Log.INFO, message, null)

    fun w(message: String, error: Throwable? = null) = add(Log.WARN, message, error)

    fun e(message: String, error: Throwable? = null) = add(Log.ERROR, message, error)

    private fun add(priority: Int, message: String, error: Throwable?) {
        val text = if (error != null) "$message: ${error.javaClass.simpleName}: ${error.message}" else message
        Log.println(priority, TAG, if (error != null) text + "\n" + Log.getStackTraceString(error) else text)
        val line = "${format.format(Date())} ${if (priority >= Log.WARN) "⚠ " else ""}$text"
        synchronized(this) { _lines.value = (_lines.value + line).takeLast(MAX_LINES) }
    }
}
