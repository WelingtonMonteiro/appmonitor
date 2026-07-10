package io.github.welingtonmonteiro.appmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Pure parsers for docker stats / inspect / port output. */
class DockerStatsTest {

    @Test
    fun parsesPercent() {
        assertEquals(12.34, DockerStats.parsePercent("12.34%"), 0.0001)
        assertEquals(0.0, DockerStats.parsePercent("0.00%"), 0.0001)
        assertEquals(-1.0, DockerStats.parsePercent("n/a"), 0.0001)
    }

    @Test
    fun parsesMemSizesIntoKb() {
        assertEquals(512, DockerStats.parseSizeToKb("512KiB"))
        assertEquals(123 * 1024, DockerStats.parseSizeToKb("123MiB"))
        assertEquals(2L * 1024 * 1024, DockerStats.parseSizeToKb("2GiB"))
        assertEquals(0, DockerStats.parseSizeToKb("128B")) // 128 bytes rounds down to 0 KB
        assertEquals(-1, DockerStats.parseSizeToKb("garbage"))
    }

    @Test
    fun parsesMemUsedSideOfTheStatsPair() {
        // "used / limit" -> only the used side counts
        assertEquals(151 * 1024, DockerStats.parseMemUsedKb("151MiB / 2GiB"))
    }

    @Test
    fun parsesTheStatsLine() {
        val (cpu, memKb, memPerc) = DockerStats.parseStats("37.50%;151MiB / 2GiB;7.38%")
        assertEquals(37.5, cpu, 0.0001)
        assertEquals(151 * 1024, memKb)
        assertEquals(7.38, memPerc, 0.0001)
    }

    @Test
    fun parsesInspectRunningAndStartTime() {
        val (running, startedAtMs) = DockerStats.parseInspect("true;2026-07-09T12:00:00Z")
        assertTrue(running)
        assertEquals(Instant.parse("2026-07-09T12:00:00Z").toEpochMilli(), startedAtMs)

        val (stopped, noStart) = DockerStats.parseInspect("false;0001-01-01T00:00:00Z")
        assertFalse(stopped)
        assertNull("the zero time means never started", noStart)
    }

    @Test
    fun parsesPublishedPortsFromDockerPort() {
        val ports = DockerStats.parsePorts(listOf(
            "3000/tcp -> 0.0.0.0:3000",
            "3000/tcp -> :::3000",           // IPv6 line of the same port
            "5432/tcp -> 0.0.0.0:5432",
        ))
        assertEquals(listOf(3000, 5432), ports)
    }
}
