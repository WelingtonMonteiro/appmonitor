package io.github.welingtonmonteiro.appmonitor

import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale
import java.util.TreeSet
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Samples memory (RSS) and CPU usage of a process and its whole descendant tree, the same numbers
 * `docker stats` shows for containers. A process is measured together with all its descendants (an
 * npm script spawns the actual node process, a shell script spawns the real server, …), so the
 * reported usage is the whole process tree.
 *
 * CPU is sampled as cumulative CPU time; the monitor computes the instantaneous percentage from the
 * delta between two consecutive samples, exactly like docker stats does.
 *
 * Sampling shells out to `ps`/`lsof` (Linux/macOS) and to PowerShell/`netstat` (Windows). On
 * platforms without any of them the sampler returns no data and the monitor shows "n/a".
 *
 * All parsing functions are pure and unit-tested. (Ported from Java; the project is now 100% Kotlin.)
 */
object ProcessStatsSampler {

    private val LOG = Logger.getInstance(ProcessStatsSampler::class.java)
    private const val PS_TIMEOUT_SECONDS = 5L

    /** Usage of one process at one sampling moment. */
    data class Stats(val rssKb: Long, /** Cumulative CPU time since the process started, in seconds. */ val cpuTimeSeconds: Double)

    /** The pid itself plus every live descendant (children, grandchildren, …). */
    fun processTreePids(rootPid: Long): Set<Long> {
        val pids = LinkedHashSet<Long>()
        if (rootPid <= 0) return pids
        pids.add(rootPid)
        ProcessHandle.of(rootPid).ifPresent { handle ->
            handle.descendants().forEach { pids.add(it.pid()) }
        }
        return pids
    }

    /** One `ps` (or PowerShell on Windows) call for all pids; missing pids are simply absent. */
    fun samplePids(pids: Collection<Long>): Map<Long, Stats> {
        if (pids.isEmpty()) return emptyMap()
        val pidList = pids.joinToString(",")
        if (isWindows()) {
            // Get-Process prints "pid rssKb cpuSeconds" lines in the same shape parsePsOutput expects
            return parsePsOutput(runCommand(
                "powershell", "-NoProfile", "-Command",
                "Get-Process -Id $pidList -ErrorAction SilentlyContinue | ForEach-Object { " +
                    "'{0} {1} {2}' -f \$_.Id, [math]::Round(\$_.WorkingSet64/1024), " +
                    "\$_.TotalProcessorTime.TotalSeconds }"))
        }
        val stats = parsePsOutput(runCommand("ps", "-o", "pid=,rss=,time=", "-p", pidList))
        // the ps TIME column has 1-second resolution on Linux - useless for a CPU % computed over a
        // 2 s window (the delta is almost always 0). /proc has 10 ms ticks: prefer it.
        val result = LinkedHashMap<Long, Stats>(stats.size)
        for ((pid, st) in stats) {
            val procSeconds = procCpuSeconds(pid)
            result[pid] = if (procSeconds >= 0) Stats(st.rssKb, procSeconds) else st
        }
        return result
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("win")

    /** OS start time of a process in epoch millis, or -1 when unknown; feeds the Uptime column. */
    fun processStartMillis(pid: Long): Long {
        if (pid <= 0) return -1
        return ProcessHandle.of(pid)
            .flatMap { it.info().startInstant() }
            .map { it.toEpochMilli() }
            .orElse(-1L)
    }

    /** The command line of a process, or empty when unknown; used to resolve process-name targets. */
    fun processCommand(pid: Long): String {
        if (pid <= 0) return ""
        return ProcessHandle.of(pid)
            .map { it.info().commandLine().orElse(it.info().command().orElse("")) }
            .orElse("")
    }

    /** Linux scheduler tick rate, needed to convert /proc cpu ticks into seconds. */
    private val CLOCK_TICKS_PER_SECOND: Double = detectClockTicksPerSecond()

    private fun detectClockTicksPerSecond(): Double {
        for (line in runCommand("getconf", "CLK_TCK")) {
            line.trim().toLongOrNull()?.let { return it.toDouble() }
        }
        return 100.0 // the value on virtually every Linux
    }

    /** Cumulative CPU seconds of one pid from /proc (Linux); -1 where /proc does not exist. */
    private fun procCpuSeconds(pid: Long): Double {
        val statFile = File("/proc/$pid/stat")
        if (!statFile.exists()) return -1.0
        return try {
            val line = String(Files.readAllBytes(statFile.toPath()), StandardCharsets.UTF_8)
            val ticks = parseProcStatCpuTicks(line)
            if (ticks < 0) -1.0 else ticks / CLOCK_TICKS_PER_SECOND
        } catch (e: IOException) {
            -1.0
        }
    }

    /**
     * Extracts utime+stime (clock ticks) from a /proc/[pid]/stat line. The command name (field 2) is
     * parenthesized and may contain spaces, so fields are counted after the last ')': utime and stime
     * are the 12th and 13th fields from there. Returns -1 for a malformed line.
     */
    fun parseProcStatCpuTicks(line: String): Long {
        val close = line.lastIndexOf(')')
        if (close < 0) return -1
        val fields = line.substring(close + 1).trim().split(Regex("\\s+"))
        if (fields.size < 13) return -1
        return try {
            fields[11].toLong() + fields[12].toLong()
        } catch (e: NumberFormatException) {
            -1
        }
    }

    /**
     * Parses `ps -o pid=,rss=,time=` output lines ("`1234 151200 00:01:30`"). Malformed lines are
     * skipped.
     */
    fun parsePsOutput(lines: List<String>): Map<Long, Stats> {
        val stats = LinkedHashMap<Long, Stats>()
        for (line in lines) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2) continue
            try {
                val pid = parts[0].toLong()
                val rssKb = parts[1].toLong()
                val cpuTime = if (parts.size > 2) parseCpuTime(parts[2]) else 0.0
                stats[pid] = Stats(rssKb, max(0.0, cpuTime))
            } catch (ignored: NumberFormatException) {
                // header junk or truncated line - skip it
            }
        }
        return stats
    }

    /**
     * Parses the ps TIME column into seconds. Handles the Linux form `[dd-]hh:mm:ss`, the macOS form
     * `mm:ss.xx` and a comma decimal separator (locale-aware ps). Returns -1 when it is not a time.
     */
    fun parseCpuTime(value: String): Double {
        return try {
            var days = 0.0
            var rest = value
            val dash = value.indexOf('-')
            if (dash >= 0) {
                days = value.substring(0, dash).toLong().toDouble()
                rest = value.substring(dash + 1)
            }
            var seconds = 0.0
            for (part in rest.split(":")) {
                seconds = seconds * 60 + part.replace(',', '.').toDouble()
            }
            days * 86_400 + seconds
        } catch (e: NumberFormatException) {
            -1.0
        }
    }

    /** Sums the stats of the pids belonging to one process tree; null when none was sampled. */
    fun aggregate(byPid: Map<Long, Stats>, treePids: Set<Long>): Stats? {
        var rssKb = 0L
        var cpuTime = 0.0
        var found = false
        for (pid in treePids) {
            val stats = byPid[pid]
            if (stats != null) {
                rssKb += stats.rssKb
                cpuTime += stats.cpuTimeSeconds
                found = true
            }
        }
        return if (found) Stats(rssKb, cpuTime) else null
    }

    /**
     * CPU seconds burned by a process tree between two samples - the docker-stats way of getting an
     * instantaneous CPU percentage (delta / elapsed * 100). Only pids present in both samples count
     * (a pid missing from the previous sample has no meaningful delta). Returns -1 when no pid of the
     * tree has a previous sample yet.
     */
    fun cpuDeltaSeconds(current: Map<Long, Stats>, previous: Map<Long, Double>, treePids: Set<Long>): Double {
        var delta = 0.0
        var matched = false
        for (pid in treePids) {
            val now = current[pid]
            val before = previous[pid]
            if (now != null && before != null) {
                delta += max(0.0, now.cpuTimeSeconds - before)
                matched = true
            }
        }
        return if (matched) delta else -1.0
    }

    /** Formats kilobytes the way docker stats does: `151.2MiB`, `1.50GiB`. */
    fun formatMemory(kb: Long): String {
        if (kb < 0) return "n/a"
        val mib = kb / 1024.0
        return if (mib < 1024) String.format(Locale.US, "%.1fMiB", mib)
        else String.format(Locale.US, "%.2fGiB", mib / 1024.0)
    }

    /** Formats an uptime like docker ps: `42s`, `5m 12s`, `2h 08m`, `3d 4h`. */
    fun formatUptime(ms: Long): String {
        if (ms < 0) return "n/a"
        val seconds = ms / 1000
        if (seconds < 60) return "${seconds}s"
        val minutes = seconds / 60
        if (minutes < 60) return String.format(Locale.US, "%dm %02ds", minutes, seconds % 60)
        val hours = minutes / 60
        if (hours < 24) return String.format(Locale.US, "%dh %02dm", hours, minutes % 60)
        return String.format(Locale.US, "%dd %dh", hours / 24, hours % 24)
    }

    /**
     * Memory percentage like docker stats: usage against the configured limit when one is set,
     * against the total host memory otherwise. Returns -1 when it cannot be computed.
     */
    fun memoryPercent(rssKb: Long, limitMb: Int?, hostTotalKb: Long): Double {
        val baseKb = if (limitMb != null && limitMb > 0) limitMb * 1024L else hostTotalKb
        if (baseKb <= 0 || rssKb < 0) return -1.0
        return rssKb * 100.0 / baseKb
    }

    /**
     * TCP ports in LISTEN state per pid, like the PORTS column of `docker ps`. Uses `lsof` on
     * Linux/macOS and `netstat -ano` on Windows.
     */
    fun sampleListeningPorts(pids: Collection<Long>): Map<Long, Set<Int>> {
        if (pids.isEmpty()) return emptyMap()
        if (isWindows()) {
            // netstat lists every connection; keep only LISTENING rows owned by one of our pids
            val wanted = LinkedHashSet(pids)
            val mine = LinkedHashMap<Long, Set<Int>>()
            for ((pid, ports) in parseNetstatListening(runCommand("netstat", "-ano", "-p", "TCP"))) {
                if (pid in wanted) mine[pid] = ports
            }
            return mine
        }
        val pidList = pids.joinToString(",")
        return parseLsofOutput(runCommand("lsof", "-nP", "-a", "-p", pidList, "-iTCP", "-sTCP:LISTEN"))
    }

    /**
     * Pids listening on the given TCP port; used to resolve a Port target and by "Kill Process on
     * Port". Uses `lsof -t` on Linux/macOS and `netstat -ano` on Windows.
     */
    fun pidsListeningOnPort(port: Int): List<Long> {
        if (isWindows()) {
            val pids = ArrayList<Long>()
            for ((pid, ports) in parseNetstatListening(runCommand("netstat", "-ano", "-p", "TCP"))) {
                if (port in ports) pids.add(pid)
            }
            return pids
        }
        return parseTersePids(runCommand("lsof", "-t", "-iTCP:$port", "-sTCP:LISTEN"))
    }

    /**
     * Every TCP port in LISTEN state on the machine mapped to its owning pid - the input to
     * auto-discovery. Uses `lsof` on Linux/macOS and `netstat -ano` on Windows.
     */
    fun allListeningPorts(): Map<Long, Set<Int>> =
        if (isWindows()) parseNetstatListening(runCommand("netstat", "-ano", "-p", "TCP"))
        else parseLsofOutput(runCommand("lsof", "-nP", "-iTCP", "-sTCP:LISTEN"))

    /**
     * Parses regular `lsof -iTCP -sTCP:LISTEN` lines
     * ("`node 41234 user 23u IPv6 … TCP *:3015 (LISTEN)`") into pid -> listening ports. The pid is the
     * first numeric token (command names may contain spaces); the port is the digits after the last
     * ':' of the address token, so IPv4, IPv6 and wildcard forms all work.
     */
    fun parseLsofOutput(lines: List<String>): Map<Long, Set<Int>> {
        val ports = LinkedHashMap<Long, MutableSet<Int>>()
        for (line in lines) {
            val parts = line.trim().split(Regex("\\s+"))
            var pid: Long? = null
            var port: Int? = null
            for (part in parts) {
                if (pid == null) {
                    val parsed = part.toLongOrNull()
                    if (parsed != null) {
                        pid = parsed
                        continue
                    }
                }
                val colon = part.lastIndexOf(':')
                if (colon in 0 until part.length - 1) {
                    val candidate = part.substring(colon + 1)
                    if (candidate.isNotEmpty() && candidate.all { it.isDigit() }) port = candidate.toInt()
                }
            }
            if (pid != null && port != null) ports.getOrPut(pid) { TreeSet() }.add(port)
        }
        return ports
    }

    /**
     * Parses `netstat -ano` output (Windows) into pid -> listening TCP ports. Keeps only TCP rows in
     * the LISTENING state; the local port is the digits after the last ':' of the local address (IPv4
     * `0.0.0.0:3000` and IPv6 `[::]:3000` both work) and the pid is the last column. Header and
     * non-matching lines are skipped.
     */
    fun parseNetstatListening(lines: List<String>): Map<Long, Set<Int>> {
        val ports = LinkedHashMap<Long, MutableSet<Int>>()
        for (line in lines) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 5) continue
            if (!parts[0].equals("TCP", ignoreCase = true) || !parts[3].equals("LISTENING", ignoreCase = true)) continue
            val port = portFromAddress(parts[1])
            val pid = parts[parts.size - 1].trim().toLongOrNull()
            if (port != null && pid != null) ports.getOrPut(pid) { TreeSet() }.add(port)
        }
        return ports
    }

    /** Port digits after the last ':' of an address token, or null when there are none. */
    private fun portFromAddress(address: String): Int? {
        val colon = address.lastIndexOf(':')
        if (colon < 0 || colon >= address.length - 1) return null
        val candidate = address.substring(colon + 1)
        return if (candidate.all { it.isDigit() }) candidate.toInt() else null
    }

    /** Parses `lsof -t` output: one pid per line, anything else is skipped. */
    fun parseTersePids(lines: List<String>): List<Long> {
        val pids = ArrayList<Long>()
        for (line in lines) line.trim().toLongOrNull()?.let { pids.add(it) }
        return pids
    }

    private fun runCommand(vararg command: String): List<String> {
        val lines = ArrayList<String>()
        try {
            val process = ProcessBuilder(*command).redirectErrorStream(false).start()
            BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    lines.add(line)
                    line = reader.readLine()
                }
            }
            if (!process.waitFor(PS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
        } catch (e: IOException) {
            LOG.debug("App Monitor: command not available: " + command[0], e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return lines
    }

    /** Total physical memory of the machine in KB, or -1 when unknown. */
    fun hostTotalMemoryKb(): Long =
        try {
            val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
            os.totalMemorySize / 1024
        } catch (t: Throwable) {
            -1
        }
}
