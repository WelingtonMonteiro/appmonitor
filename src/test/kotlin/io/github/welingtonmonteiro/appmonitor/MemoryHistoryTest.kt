package io.github.welingtonmonteiro.appmonitor

import io.github.welingtonmonteiro.appmonitor.MemoryHistory.Sample
import io.github.welingtonmonteiro.appmonitor.MemoryHistory.Trend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic tests for the memory history: leak analysis, verdict text and CSV. */
class MemoryHistoryTest {

    /** A series of [count] samples [stepMs] apart, rss = base + i*deltaKb. */
    private fun series(base: Long, deltaKb: Long, count: Int, stepMs: Long): List<Sample> =
        (0 until count).map { i -> Sample(i * stepMs, base + i * deltaKb, 0.0) }

    @Test
    fun analyzeFewerThanThreeSamplesIsInsufficient() {
        assertEquals(Trend.INSUFFICIENT_DATA, MemoryHistory.analyze(emptyList()).trend)
        assertEquals(Trend.INSUFFICIENT_DATA, MemoryHistory.analyze(series(100_000, 0, 2, 1000)).trend)
    }

    @Test
    fun analyzeFlatSeriesIsStable() {
        val a = MemoryHistory.analyze(series(200_000, 0, 10, 1000))
        assertEquals(Trend.STABLE, a.trend)
        assertEquals(0, a.netChangeKb)
        assertEquals(0.0, a.slopeKbPerMin, 0.001)
    }

    @Test
    fun analyzeSteadyGrowthReadsAsPossibleLeak() {
        val a = MemoryHistory.analyze(series(100 * 1024, 20 * 1024, 20, 2000))
        assertEquals(Trend.GROWING, a.trend)
        assertTrue(a.netChangeKb > 0)
        assertTrue(a.slopeKbPerMin > 0)
        assertEquals(100 * 1024L, a.firstRssKb)
        assertEquals(a.maxRssKb, a.lastRssKb) // monotonic growth: last is the peak
    }

    @Test
    fun analyzeSteadyDeclineIsShrinking() {
        val a = MemoryHistory.analyze(series(500 * 1024, -20 * 1024, 20, 2000))
        assertEquals(Trend.SHRINKING, a.trend)
        assertTrue(a.netChangeKb < 0)
        assertTrue(a.slopeKbPerMin < 0)
    }

    @Test
    fun analyzeTinyWiggleStaysStable() {
        // grows only ~1 MiB total on a 300 MiB base -> under the 10%/5MiB threshold
        assertEquals(Trend.STABLE, MemoryHistory.analyze(series(300 * 1024, 64, 16, 1000)).trend)
    }

    @Test
    fun steadyLinearGrowthIsHighConfidenceLeak() {
        val a = MemoryHistory.analyze(series(100 * 1024, 20 * 1024, 20, 2000))
        assertTrue("perfectly linear -> R2 near 1", a.rSquared > 0.99)
        assertEquals("monotonic growth -> memory never freed", 1.0, a.monotonicFraction, 0.0001)
        assertTrue(a.isSteadyLeak())
        assertTrue(a.projectedPerHourKb > 0)
        assertEquals("Growing - likely memory leak (steady)", MemoryHistory.verdictText(a))
    }

    @Test
    fun summaryTextCarriesAppNameAndVerdict() {
        val text = MemoryHistory.summaryText("eparts-api",
            MemoryHistory.analyze(series(100 * 1024, 20 * 1024, 20, 2000)))
        assertTrue(text.startsWith("Memory analysis - eparts-api"))
        assertTrue(text.contains("Verdict: Growing"))
        assertTrue(text.contains("R2="))
    }

    @Test
    fun summaryTextForInsufficientDataStaysShort() {
        val text = MemoryHistory.summaryText("app", MemoryHistory.analyze(emptyList()))
        assertTrue(text.contains("Insufficient data"))
        assertTrue(text.contains("Not enough samples"))
    }

    @Test
    fun memAndDurationFormattersAreLocaleStable() {
        assertEquals("512.0MiB", MemoryHistory.mem(512 * 1024))
        assertEquals("1.50GiB", MemoryHistory.mem((1.5 * 1024 * 1024).toLong()))
        assertEquals("42s", MemoryHistory.durationText(42_000))
        assertEquals("5m 12s", MemoryHistory.durationText((5 * 60 + 12) * 1000L))
    }

    @Test
    fun csvHasHeaderAndOneRowPerSample() {
        val csv = MemoryHistory.toCsv(listOf(Sample(1000L, 2048L, 12.5), Sample(3000L, 4096L, 25.0)))
        val lines = csv.split("\n")
        assertEquals(3, lines.size - 1) // header + 2 rows + trailing empty from final \n
        assertEquals("timestamp_ms,iso_time,rss_kb,percent", lines[0])
        assertTrue(lines[1].startsWith("1000,"))
        assertTrue(lines[1].contains(",2048,"))
        assertTrue("US decimal separator regardless of locale", lines[1].endsWith(",12.50"))
        assertTrue(lines[2].endsWith(",25.00"))
    }

    @Test
    fun csvOfEmptyHistoryIsJustTheHeader() {
        assertEquals("timestamp_ms,iso_time,rss_kb,percent\n", MemoryHistory.toCsv(emptyList()))
    }
}
