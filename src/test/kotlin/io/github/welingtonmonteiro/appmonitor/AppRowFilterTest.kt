package io.github.welingtonmonteiro.appmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure toolbar filter over name / tag / target / ports. */
class AppRowFilterTest {

    private fun sample(name: String = "eparts-api", tag: String = "backend", ports: List<Int> = listOf(3003)) =
        AppSample(
            appId = "id", name = name, targetLabel = "port 3003", up = true, rootPid = 100,
            ports = ports, uptimeMs = 0, rssKb = 0, memPercent = 0.0, cpuPercent = 0.0, tag = tag,
        )

    @Test
    fun blankQueryMatchesEverything() {
        assertTrue(AppRowFilter.matches(sample(), ""))
        assertTrue(AppRowFilter.matches(sample(), "   "))
    }

    @Test
    fun matchesNameTagAndPortCaseInsensitively() {
        assertTrue(AppRowFilter.matches(sample(), "EPARTS"))
        assertTrue(AppRowFilter.matches(sample(), "backend"))
        assertTrue(AppRowFilter.matches(sample(), "3003"))
        assertTrue("target label is searched too", AppRowFilter.matches(sample(), "port"))
        assertFalse(AppRowFilter.matches(sample(), "frontend"))
    }

    @Test
    fun filterKeepsOnlyMatchingRows() {
        val rows = listOf(sample(name = "api", tag = "backend"), sample(name = "web", tag = "frontend"))
        assertEquals(listOf("web"), AppRowFilter.filter(rows, "front").map { it.name })
    }
}
