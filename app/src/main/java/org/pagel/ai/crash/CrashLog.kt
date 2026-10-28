package org.pagel.ai.crash

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** One crash as the phone saw it. Sent as-is to `POST /api/v1/app/crash`, so the server stores what happened. */
@Serializable
data class CrashReport(
    val at: Long,
    val versionName: String,
    val versionCode: Int,
    val thread: String,
    val type: String,
    val message: String,
    val stack: String,
    val device: String,
    val sdk: Int,
)

/**
 * The crash folder (`files/crashes/`): plain files, pure JVM, so the whole flow is testable without Android. A crash
 * is written while the process is dying, so this does nothing clever: one small file, no network, no locks.
 */
object CrashLog {
    const val KEEP = 20
    private const val STACK_MAX = 16_000
    private val json = Json { ignoreUnknownKeys = true }

    fun report(thread: Thread, e: Throwable, versionName: String, versionCode: Int, device: String, sdk: Int, now: Long): CrashReport {
        // A crash that passed through the Compass keeps its classes and lines, never a message: a message could hold a
        // goal's words, and the report goes to pagel.uz (compass checker #10).
        val sealed = touchesCompass(e)
        val trace = if (sealed) quietTrace(e) else StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
        return CrashReport(
            at = now, versionName = versionName, versionCode = versionCode, thread = thread.name,
            type = e.javaClass.name, message = if (sealed) HIDDEN else (e.message ?: "").take(500), stack = trace.take(STACK_MAX), device = device, sdk = sdk,
        )
    }

    const val HIDDEN = "(hidden: a Compass crash)"

    private fun chain(e: Throwable) = generateSequence(e) { t -> t.cause?.takeIf { it !== t } }.take(20)

    /** Any frame of the throwable or its causes in a Compass package. */
    fun touchesCompass(e: Throwable): Boolean = chain(e).any { t -> t.stackTrace.any { ".compass." in it.className } }

    /** The stack as types and frames only ("Caused by: <type>", then its frames), no messages. */
    private fun quietTrace(e: Throwable): String = buildString {
        chain(e).forEachIndexed { i, t ->
            append(if (i == 0) "" else "Caused by: ").append(t.javaClass.name).append('\n')
            for (f in t.stackTrace) append("\tat ").append(f).append('\n')
        }
    }

    fun write(dir: File, r: CrashReport): File {
        dir.mkdirs()
        val f = File(dir, "crash-${r.at}.json")
        f.writeText(json.encodeToString(CrashReport.serializer(), r))
        // A crash loop must not fill the phone: only the newest KEEP files stay.
        pending(dir).drop(KEEP).forEach { it.delete() }
        return f
    }

    /** Newest first. */
    fun pending(dir: File): List<File> =
        (dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") && f.name.endsWith(".json") } ?: emptyArray())
            .sortedByDescending { it.name.removePrefix("crash-").removeSuffix(".json").toLongOrNull() ?: 0L }

    fun read(f: File): CrashReport? = runCatching { json.decodeFromString(CrashReport.serializer(), f.readText()) }.getOrNull()
}
