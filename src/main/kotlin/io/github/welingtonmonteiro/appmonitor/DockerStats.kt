package io.github.welingtonmonteiro.appmonitor

import java.time.Instant

/** One docker container's live measurement, the docker equivalent of a process-tree [AppSample]. */
data class DockerContainerSample(
    val up: Boolean,
    val cpuPercent: Double,
    val memUsedKb: Long,
    val memPercent: Double,
    val ports: List<Int>,
    val startedAtMs: Long?,
)

/**
 * Pure parsers for the docker CLI output used by the Docker target: `docker stats`, the two-field
 * `docker inspect` line and `docker port`. Kept IDE-free and side-effect-free so they are
 * unit-testable; the actual CLI calls live in [DockerCli].
 */
object DockerStats {

    /** {@code "12.34%"} → {@code 12.34}; -1 when it is not a percentage. */
    fun parsePercent(text: String): Double =
        text.trim().removeSuffix("%").trim().toDoubleOrNull() ?: -1.0

    /** The used side of docker's {@code "123.4MiB / 2GiB"} memory usage, in KB; -1 when unparseable. */
    fun parseMemUsedKb(memUsage: String): Long = parseSizeToKb(memUsage.substringBefore('/'))

    /** Parse a docker size ({@code 123.4MiB}, {@code 1.5GiB}, {@code 512KiB}, {@code 128B}) into KB. */
    fun parseSizeToKb(size: String): Long {
        val match = SIZE_REGEX.matchEntire(size.trim()) ?: return -1
        val value = match.groupValues[1].toDoubleOrNull() ?: return -1
        val kb = when (match.groupValues[2].lowercase()) {
            "b", "" -> value / 1024.0
            "kib", "kb" -> value
            "mib", "mb" -> value * 1024
            "gib", "gb" -> value * 1024 * 1024
            "tib", "tb" -> value * 1024 * 1024 * 1024
            else -> return -1
        }
        return kb.toLong()
    }

    private val SIZE_REGEX = Regex("""([0-9]*\.?[0-9]+)\s*([A-Za-z]*)""")

    /** Parse the {@code CPUPerc;MemUsage;MemPerc} stats line into (cpu%, memUsedKb, mem%). */
    fun parseStats(line: String): Triple<Double, Long, Double> {
        val parts = line.split(';')
        return Triple(
            parts.getOrNull(0)?.let { parsePercent(it) } ?: -1.0,
            parts.getOrNull(1)?.let { parseMemUsedKb(it) } ?: -1,
            parts.getOrNull(2)?.let { parsePercent(it) } ?: -1.0,
        )
    }

    /** Parse the {@code Running;StartedAt} inspect line into (running, startedAtMs?). */
    fun parseInspect(line: String): Pair<Boolean, Long?> {
        val parts = line.split(';')
        val running = parts.getOrNull(0)?.trim().equals("true", ignoreCase = true)
        val startedAtMs = parts.getOrNull(1)?.trim()?.let { parseInstantMs(it) }
        return running to startedAtMs
    }

    /** Container ports from {@code docker port} lines ({@code "3000/tcp -> 0.0.0.0:3000"}). */
    fun parsePorts(lines: List<String>): List<Int> = lines
        .mapNotNull { it.trim().substringBefore("->").trim().substringBefore('/').trim().toIntOrNull() }
        .distinct()
        .sorted()

    private fun parseInstantMs(text: String): Long? {
        // docker StartedAt is RFC3339; "0001-01-01T00:00:00Z" means the container never started
        if (text.isBlank() || text.startsWith("0001")) return null
        return try {
            Instant.parse(text).toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }
}
