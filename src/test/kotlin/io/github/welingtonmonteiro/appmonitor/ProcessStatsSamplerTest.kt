package io.github.welingtonmonteiro.appmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the process sampler: parsing of ps / lsof / netstat output, aggregation of a process tree
 * and the docker-stats-like formatting shown in the App Monitor tool window.
 */
class ProcessStatsSamplerTest {

    // --- parsePsOutput --------------------------------------------------------------------

    @Test
    fun parsesPidRssAndCpuTimeColumns() {
        val stats = ProcessStatsSampler.parsePsOutput(listOf("  1234 151200  00:01:30", "5678 30720 00:00:00"))

        assertEquals(2, stats.size)
        assertEquals(151200L, stats.getValue(1234L).rssKb)
        assertEquals(90.0, stats.getValue(1234L).cpuTimeSeconds, 0.0001)
        assertEquals(30720L, stats.getValue(5678L).rssKb)
    }

    @Test
    fun toleratesCommaDecimalSeparatorInCpuTime() {
        val stats = ProcessStatsSampler.parsePsOutput(listOf("1234 1024 0:01,50"))

        assertEquals(1.5, stats.getValue(1234L).cpuTimeSeconds, 0.0001)
    }

    @Test
    fun parsesWindowsPowershellLines() {
        val stats = ProcessStatsSampler.parsePsOutput(listOf("1234 151200 12.34", "5678 2048 3,5"))

        assertEquals(12.34, stats.getValue(1234L).cpuTimeSeconds, 0.0001)
        assertEquals("comma decimal (pt-BR locale) must work too", 3.5, stats.getValue(5678L).cpuTimeSeconds, 0.0001)
    }

    // --- parseCpuTime -----------------------------------------------------------------------

    @Test
    fun parsesLinuxAndMacCpuTimeFormats() {
        assertEquals(12.0, ProcessStatsSampler.parseCpuTime("00:00:12"), 0.0001)
        assertEquals(7384.0, ProcessStatsSampler.parseCpuTime("02:03:04"), 0.0001)
        assertEquals(93784.0, ProcessStatsSampler.parseCpuTime("1-02:03:04"), 0.0001)   // Linux, with days
        assertEquals(12.34, ProcessStatsSampler.parseCpuTime("0:12.34"), 0.0001)        // macOS
        assertEquals(-1.0, ProcessStatsSampler.parseCpuTime("abc"), 0.0001)
    }

    // --- parseProcStatCpuTicks (/proc precision for the instantaneous CPU %) -----------------

    @Test
    fun parsesUtimePlusStimeFromProcStat() {
        val line = "42 (node) S 1 42 42 0 -1 4194304 500 0 0 0 150 250 3 2 20 0 11 0 12345 100000 200"

        assertEquals(400L, ProcessStatsSampler.parseProcStatCpuTicks(line))
    }

    @Test
    fun procStatCommandNameMayContainSpacesAndParentheses() {
        val line = "42 (my (weird) app) S 1 42 42 0 -1 4194304 500 0 0 0 70 30 3 2 20 0 11 0 12345 100000 200"

        assertEquals(100L, ProcessStatsSampler.parseProcStatCpuTicks(line))
    }

    @Test
    fun malformedProcStatGivesMinusOne() {
        assertEquals(-1L, ProcessStatsSampler.parseProcStatCpuTicks("garbage"))
        assertEquals(-1L, ProcessStatsSampler.parseProcStatCpuTicks("42 (node) S 1 42"))
        assertEquals(-1L, ProcessStatsSampler.parseProcStatCpuTicks("42 (node) S a b c d e f g h i j k l m n"))
    }

    @Test
    fun skipsMalformedLines() {
        val stats = ProcessStatsSampler.parsePsOutput(listOf("", "  PID   RSS  %CPU", "abc def", "1234 2048 0.3", "999"))

        assertEquals(1, stats.size)
        assertEquals(2048L, stats.getValue(1234L).rssKb)
    }

    @Test
    fun emptyOutputGivesEmptyStats() {
        assertTrue(ProcessStatsSampler.parsePsOutput(emptyList()).isEmpty())
    }

    // --- aggregate (whole process tree, e.g. npm wrapper + node child) ---------------------

    @Test
    fun aggregateSumsTheWholeProcessTree() {
        val byPid = ProcessStatsSampler.parsePsOutput(listOf("100 1000 00:00:01", "101 2000 00:00:02", "999 50000 00:00:09"))
        val tree = linkedSetOf(100L, 101L)

        val stats = ProcessStatsSampler.aggregate(byPid, tree)

        assertEquals("the npm wrapper and its node child must be summed", 3000L, stats!!.rssKb)
        assertEquals(3.0, stats.cpuTimeSeconds, 0.0001)
    }

    @Test
    fun aggregateReturnsNullWhenNoPidWasSampled() {
        val byPid = ProcessStatsSampler.parsePsOutput(listOf("999 1 00:00:00"))

        assertNull("a dead process tree must show as n/a, not as 0",
            ProcessStatsSampler.aggregate(byPid, linkedSetOf(100L)))
    }

    // --- cpuDeltaSeconds (docker-style instantaneous CPU %) ----------------------------------

    @Test
    fun cpuDeltaSumsOnlyPidsPresentInBothSamples() {
        val current = ProcessStatsSampler.parsePsOutput(listOf("100 1000 00:00:05", "101 2000 00:00:07", "102 500 00:00:09"))
        val previous = mapOf(100L to 4.0, 101L to 6.5)
        val tree = linkedSetOf(100L, 101L, 102L)

        assertEquals(1.5, ProcessStatsSampler.cpuDeltaSeconds(current, previous, tree), 0.0001)
    }

    @Test
    fun cpuDeltaIsUnknownOnTheFirstSample() {
        val current = ProcessStatsSampler.parsePsOutput(listOf("100 1000 00:00:05"))

        assertEquals("without a baseline there is no rate yet", -1.0,
            ProcessStatsSampler.cpuDeltaSeconds(current, emptyMap(), linkedSetOf(100L)), 0.0001)
    }

    // --- formatUptime -------------------------------------------------------------------------

    @Test
    fun formatsUptimeLikeDockerPs() {
        assertEquals("42s", ProcessStatsSampler.formatUptime(42_000))
        assertEquals("5m 12s", ProcessStatsSampler.formatUptime((5 * 60 + 12) * 1000L))
        assertEquals("2h 08m", ProcessStatsSampler.formatUptime((2 * 3600 + 8 * 60) * 1000L))
        assertEquals("3d 4h", ProcessStatsSampler.formatUptime((3 * 86400 + 4 * 3600) * 1000L))
        assertEquals("n/a", ProcessStatsSampler.formatUptime(-1))
    }

    // --- formatMemory (docker stats style) --------------------------------------------------

    @Test
    fun formatsKilobytesAsMiBAndGiB() {
        assertEquals("151.2MiB", ProcessStatsSampler.formatMemory(154829))
        assertEquals("1.00GiB", ProcessStatsSampler.formatMemory(1024 * 1024))
        assertEquals("0.0MiB", ProcessStatsSampler.formatMemory(0))
        assertEquals("n/a", ProcessStatsSampler.formatMemory(-1))
    }

    // --- memoryPercent ----------------------------------------------------------------------

    @Test
    fun percentAgainstConfiguredLimitWhenSet() {
        assertEquals(50.0, ProcessStatsSampler.memoryPercent(512 * 1024L, 1024, 32L * 1024 * 1024), 0.0001)
    }

    @Test
    fun percentAgainstHostMemoryWhenNoLimit() {
        assertEquals(6.25, ProcessStatsSampler.memoryPercent(1024 * 1024L, null, 16L * 1024 * 1024), 0.0001)
    }

    @Test
    fun percentIsUnknownWithoutAnyBase() {
        assertEquals(-1.0, ProcessStatsSampler.memoryPercent(1024L, null, -1L), 0.0001)
        assertEquals(-1.0, ProcessStatsSampler.memoryPercent(-1L, 1024, -1L), 0.0001)
    }

    // --- processTreePids --------------------------------------------------------------------

    @Test
    fun invalidPidGivesEmptyTree() {
        assertTrue(ProcessStatsSampler.processTreePids(-1).isEmpty())
        assertTrue(ProcessStatsSampler.processTreePids(0).isEmpty())
    }

    @Test
    fun ownProcessTreeContainsAtLeastTheRootPid() {
        val myPid = ProcessHandle.current().pid()

        assertTrue("the pid itself must always be part of its tree",
            ProcessStatsSampler.processTreePids(myPid).contains(myPid))
    }

    // --- processStartMillis (uptime) ---------------------------------------------------------

    @Test
    fun invalidPidHasNoStartTime() {
        assertEquals(-1L, ProcessStatsSampler.processStartMillis(-1))
        assertEquals(-1L, ProcessStatsSampler.processStartMillis(0))
    }

    // --- parseLsofOutput (Ports column / kill-by-port on Linux/macOS) ------------------------

    @Test
    fun parsesLsofListenLinesIntoPidToPorts() {
        val ports = ProcessStatsSampler.parseLsofOutput(listOf(
            "COMMAND   PID USER   FD   TYPE DEVICE SIZE/OFF NODE NAME",
            "node    41234 wm   23u  IPv6 123456      0t0  TCP *:3015 (LISTEN)",
            "node    41234 wm   24u  IPv4 123457      0t0  TCP 127.0.0.1:9229 (LISTEN)",
            "java     5555 wm   88u  IPv6    999      0t0  TCP [::1]:8080 (LISTEN)"))

        assertEquals(2, ports.size)
        assertEquals(setOf(3015, 9229), ports.getValue(41234L))
        assertEquals(setOf(8080), ports.getValue(5555L))
    }

    @Test
    fun lsofParserSkipsMalformedLines() {
        val ports = ProcessStatsSampler.parseLsofOutput(listOf("", "garbage without columns", "node abc def"))

        assertTrue(ports.isEmpty())
    }

    @Test
    fun parsesTersePidOutput() {
        assertEquals(listOf(41234L, 5555L),
            ProcessStatsSampler.parseTersePids(listOf("41234", " 5555 ", "", "noise")))
    }

    // --- parseNetstatListening (Ports column / kill-by-port on Windows) ----------------------

    @Test
    fun parsesWindowsNetstatListeningRows() {
        val ports = ProcessStatsSampler.parseNetstatListening(listOf(
            "Active Connections",
            "  Proto  Local Address          Foreign Address        State           PID",
            "  TCP    0.0.0.0:3000           0.0.0.0:0              LISTENING       1234",
            "  TCP    [::]:3000              [::]:0                 LISTENING       1234",
            "  TCP    127.0.0.1:9229         0.0.0.0:0              LISTENING       1234",
            "  TCP    192.168.0.5:5555       10.0.0.1:443          ESTABLISHED     9999",
            "  TCP    0.0.0.0:8080           0.0.0.0:0              LISTENING       5555"))

        assertEquals(2, ports.size)
        assertEquals("dedupes IPv4/IPv6 rows of the same port", setOf(3000, 9229), ports.getValue(1234L))
        assertEquals(setOf(8080), ports.getValue(5555L))
    }

    @Test
    fun netstatParserIgnoresNonListeningAndHeaderLines() {
        val ports = ProcessStatsSampler.parseNetstatListening(listOf(
            "",
            "  Proto  Local Address          Foreign Address        State           PID",
            "  UDP    0.0.0.0:53             *:*                                    77",
            "  TCP    0.0.0.0:445            0.0.0.0:0              TIME_WAIT       88"))

        assertTrue(ports.isEmpty())
    }
}
