package dev.pranav.applock.core.utils

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

@SuppressLint("StaticFieldLeak")
object LogUtils {
    private val logScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
    private const val TAG = "LogUtils"
    private const val FILE_NAME = "app_logs.txt"
    private const val SECURITY_LOGS = "audit_log.txt"
    private const val EXPORT_DIRECTORY = "shared_logs"

    private fun exportDirectory(): File =
        File(context.cacheDir, EXPORT_DIRECTORY).apply { mkdirs() }

    private fun newExportFile(prefix: String): File =
        File.createTempFile(prefix.removeSuffix(".txt") + "-", ".txt", exportDirectory())

    private lateinit var context: Context
    @Volatile private var loggingEnabled = false
    private val fileMutex = Mutex()

    fun initialize(application: Context) {
        context = application
    }

    fun setLoggingEnabled(enabled: Boolean) {
        loggingEnabled = enabled
    }

    fun d(tag: String, message: String) {
        if (!loggingEnabled) return

        val line = "${Instant.now()} D $tag: $message\n"
        Log.d(tag, message)
        writeAuditLogLine(line)
    }

    fun e(tag: String, message: String, e: Throwable? = null) {
        if (!loggingEnabled) return

        val line = "${Instant.now()} E $tag: $message\n${Log.getStackTraceString(e)}\n"
        Log.e(tag, message)
        writeAuditLogLine(line)
    }

    private fun writeAuditLogLine(line: String) {
        logScope.launch {
            fileMutex.withLock {
                try {
                    val file = File(context.filesDir, SECURITY_LOGS)
                    if (!file.exists()) {
                        file.createNewFile()
                    }
                    file.appendText(line)
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing audit log", e)
                }
            }
        }
    }

    suspend fun exportAuditLogs(): Uri? = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            try {
                val file = File(context.filesDir, SECURITY_LOGS)
                if (file.exists()) {
                    val snapshot = newExportFile(SECURITY_LOGS)
                    file.copyTo(snapshot, overwrite = true)
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", snapshot)
                } else null
            } catch (e: Exception) {
                Log.e(TAG, "Error exporting audit logs", e)
                null
            }
        }
    }

    suspend fun exportLogs(): Uri? = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            try {
                val file = newExportFile(FILE_NAME)
                val process = Runtime.getRuntime().exec("logcat -d")

                try {
                    process.inputStream.bufferedReader().use { reader ->
                        file.writer().use { writer -> reader.copyTo(writer) }
                    }
                    check(process.waitFor() == 0) { "Logcat export failed" }
                } finally {
                    process.destroy()
                }


                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )

            } catch (e: Exception) {
                Log.e(TAG, "Error exporting logs", e)
                null
            }
        }
    }

    /**
     * Clear all security and audit logs.
     * Called when the app is updated.
     */
    fun clearAllLogs() {
        logScope.launch {
            fileMutex.withLock {
                try {
                    val securityLogFile = File(context.filesDir, SECURITY_LOGS)
                    if (securityLogFile.exists()) {
                        securityLogFile.delete()
                        Log.d(TAG, "Cleared security logs")
                    }

                    exportDirectory().listFiles()?.forEach { it.delete() }
                    // Remove exports from versions before snapshot isolation.
                    File(context.cacheDir, FILE_NAME).delete()
                } catch (e: Exception) {
                    Log.e(TAG, "Error clearing logs", e)
                }
            }
        }
    }

    /**
     * Purge log entries older than 3 days from both audit and app log files.
     * This prevents logs from growing indefinitely.
     * Runs asynchronously to avoid blocking the main thread.
     */
    fun purgeOldLogs() {
        logScope.launch {
            fileMutex.withLock {
                purgeOldLogsFromFile(File(context.filesDir, SECURITY_LOGS), "audit")
                purgeOldLogsFromFile(File(context.cacheDir, FILE_NAME), "app")
                exportDirectory().listFiles()?.forEach { purgeOldLogsFromFile(it, "app") }
            }
        }
    }

    private fun purgeOldLogsFromFile(logFile: File, logType: String) {
        if (!logFile.exists()) return
        val cutoff = Instant.now().minus(3, ChronoUnit.DAYS)
        // Exported snapshots (including logcat) have no guaranteed timestamp format.
        if (logType != "audit") {
            if (logFile.lastModified() < cutoff.toEpochMilli()) logFile.delete()
            return
        }
        var temporary: File? = null
        try {
            temporary = File.createTempFile("audit-retention-", ".tmp", logFile.parentFile)
            var keepRecord = true
            var keptLines = 0
            logFile.bufferedReader().use { reader ->
                temporary.bufferedWriter().use { writer ->
                    reader.forEachLine { line ->
                        val timestamp = runCatching { Instant.parse(line.substringBefore(' ')) }.getOrNull()
                        if (timestamp != null) keepRecord = !timestamp.isBefore(cutoff)
                        // Stack traces and multiline messages belong to their preceding entry.
                        if (keepRecord) {
                            writer.write(line)
                            writer.newLine()
                            keptLines++
                        }
                    }
                }
            }
            if (keptLines == 0) {
                logFile.delete()
            } else {
                java.nio.file.Files.move(temporary.toPath(), logFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error purging old $logType logs", e)
        } finally {
            temporary?.delete()
        }
    }
}
