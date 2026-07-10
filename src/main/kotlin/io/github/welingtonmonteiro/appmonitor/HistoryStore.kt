package io.github.welingtonmonteiro.appmonitor

import java.nio.file.Files
import java.nio.file.Path

/**
 * File-backed persistence of per-app memory sessions (Fase 4), so the chart and leak analysis
 * survive IDE restarts and the last finished session is kept for comparison. IDE-free - it just
 * takes a base directory - so it is unit-testable with a temp dir; the project wires it to a path
 * under the IDE system directory.
 *
 * Per app it keeps CSV files in the same format as the chart's export, plus a tiny file with the
 * root PID of the current session:
 * - `<appId>.current.csv` / `<appId>.current.pid` — the live (or last) session and its root pid
 * - `<appId>.previous.csv` — the last finished session, kept for comparison
 *
 * Every operation swallows I/O errors: persistence is best-effort and must never break monitoring.
 */
class HistoryStore(private val baseDir: Path) {

    /** Overwrite the current session's samples and remember its root pid. No-op for an empty session. */
    fun saveCurrent(appId: String, rootPid: Long, samples: List<MemoryHistory.Sample>) {
        if (samples.isEmpty()) return
        write(file(appId, CURRENT_CSV), MemoryHistory.toCsv(samples))
        write(file(appId, CURRENT_PID), rootPid.toString())
    }

    fun loadCurrent(appId: String): List<MemoryHistory.Sample> =
        read(file(appId, CURRENT_CSV))?.let { MemoryHistory.fromCsv(it) } ?: emptyList()

    fun loadCurrentPid(appId: String): Long? = read(file(appId, CURRENT_PID))?.trim()?.toLongOrNull()

    fun loadPrevious(appId: String): List<MemoryHistory.Sample> =
        read(file(appId, PREVIOUS_CSV))?.let { MemoryHistory.fromCsv(it) } ?: emptyList()

    /** Make the current session the comparison baseline (its content becomes `previous`). */
    fun promoteToPrevious(appId: String) {
        val current = read(file(appId, CURRENT_CSV)) ?: return
        write(file(appId, PREVIOUS_CSV), current)
    }

    /** Remove every persisted file of an app (called when it is no longer monitored). */
    fun deleteFor(appId: String) {
        for (suffix in listOf(CURRENT_CSV, CURRENT_PID, PREVIOUS_CSV)) {
            runCatching { Files.deleteIfExists(file(appId, suffix)) }
        }
    }

    private fun file(appId: String, suffix: String): Path = baseDir.resolve("$appId.$suffix")

    private fun write(path: Path, content: String) {
        runCatching {
            Files.createDirectories(baseDir)
            Files.writeString(path, content)
        }
    }

    private fun read(path: Path): String? =
        runCatching { if (Files.isRegularFile(path)) Files.readString(path) else null }.getOrNull()

    private companion object {
        const val CURRENT_CSV = "current.csv"
        const val CURRENT_PID = "current.pid"
        const val PREVIOUS_CSV = "previous.csv"
    }
}
