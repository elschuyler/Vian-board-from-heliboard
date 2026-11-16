package helium314.keyboard.latin.utils

import android.os.Build
import java.time.LocalDateTime
import java.util.Date

/**
 * Logger that bridges Android system logging and the lightweight LogCatcher ring buffer.
 */
object Log {
    @JvmStatic
    fun wtf(tag: String?, message: String) {
        log('F', tag, message, null)
        android.util.Log.wtf(tag, message)
    }

    @JvmStatic
    fun e(tag: String?, message: String, e: Throwable?) {
        log('E', tag, "$message\n${e?.stackTraceToString()}", e)
        android.util.Log.e(tag, message, e)
    }

    @JvmStatic
    fun e(tag: String?, message: String) {
        log('E', tag, message, null)
        android.util.Log.e(tag, message)
    }

    @JvmStatic
    fun w(tag: String?, message: String, e: Throwable?) {
        log('W', tag, "$message\n${e?.stackTraceToString()}", e)
        android.util.Log.w(tag, message, e)
    }

    @JvmStatic
    fun w(tag: String?, message: String) {
        log('W', tag, message, null)
        android.util.Log.w(tag, message)
    }

    @JvmStatic
    fun i(tag: String?, message: String, e: Throwable?) {
        log('I', tag, "$message\n${e?.stackTraceToString()}", e)
        android.util.Log.i(tag, message, e)
    }

    @JvmStatic
    fun i(tag: String?, message: String) {
        log('I', tag, message, null)
        android.util.Log.i(tag, message)
    }

    @JvmStatic
    fun d(tag: String?, message: String, e: Throwable?) {
        log('D', tag, "$message\n${e?.stackTraceToString()}", e)
        android.util.Log.d(tag, message, e)
    }

    @JvmStatic
    fun d(tag: String?, message: String) {
        log('D', tag, message, null)
        android.util.Log.d(tag, message)
    }

    @JvmStatic
    fun v(tag: String?, message: String) {
        log('V', tag, message, null)
        android.util.Log.v(tag, message)
    }

    private fun log(line: LogLine) {
        // Forward directly to LogCatcher engine
        LogCatcher.log(line.level, line.tag, line.message, line.throwable)
    }

    private fun log(level: Char, tag: String?, message: String, throwable: Throwable?) {
        LogCatcher.log(level, tag, message, throwable)
    }

    /** returns recent logs as LogLine */
    fun getLog(maxLines: Int = 1000): List<LogLine> {
        return LogCatcher.getLogs(maxLines).map {
            LogLine(it.level, it.tag, it.message, null, it.timestamp)
        }
    }
}

data class LogLine(
    val level: Char,
    val tag: String?,
    val message: String,
    val throwable: Throwable? = null,
    val timestampMs: Long = System.currentTimeMillis()
) {
    override fun toString(): String {
        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(timestampMs))
        return "$dateStr $level $tag: $message"
    }
}
