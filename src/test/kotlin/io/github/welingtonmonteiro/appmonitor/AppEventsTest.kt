package io.github.welingtonmonteiro.appmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure event detector: sample transitions and alert mapping (no running IDE needed). */
class AppEventsTest {

    private fun sample(up: Boolean, pid: Long = 100, health: Health = Health.NONE) = AppSample(
        appId = "id", name = "api", targetLabel = "port 3003", up = up,
        rootPid = if (up) pid else -1, ports = emptyList(), uptimeMs = 0,
        rssKb = if (up) 1000 else -1, memPercent = 0.0, cpuPercent = 0.0, health = health,
    )

    @Test
    fun downToUpIsStartedAndCarriesTheTimestamp() {
        val events = AppEvents.lifecycle(sample(false), sample(true, 200), now = 5000)
        assertEquals(listOf(AppEventKind.STARTED), events.map { it.kind })
        assertEquals(5000L, events[0].timeMs)
    }

    @Test
    fun firstSightingWhileUpIsStarted() {
        assertEquals(listOf(AppEventKind.STARTED), AppEvents.lifecycle(null, sample(true), 1).map { it.kind })
    }

    @Test
    fun upToDownIsStopped() {
        assertEquals(listOf(AppEventKind.STOPPED), AppEvents.lifecycle(sample(true), sample(false), 1).map { it.kind })
    }

    @Test
    fun noChangeYieldsNoEvents() {
        assertTrue(AppEvents.lifecycle(sample(true, 100), sample(true, 100), 1).isEmpty())
    }

    @Test
    fun changedRootPidWhileUpIsRestarted() {
        val events = AppEvents.lifecycle(sample(true, 100), sample(true, 200), 1)
        assertEquals(listOf(AppEventKind.RESTARTED), events.map { it.kind })
        assertTrue(events[0].detail.contains("100"))
        assertTrue(events[0].detail.contains("200"))
    }

    @Test
    fun healthTransitionsAreEvents() {
        assertEquals(listOf(AppEventKind.UNHEALTHY),
            AppEvents.lifecycle(sample(true, 100, Health.HEALTHY), sample(true, 100, Health.UNHEALTHY), 1).map { it.kind })
        assertEquals(listOf(AppEventKind.HEALTHY),
            AppEvents.lifecycle(sample(true, 100, Health.UNHEALTHY), sample(true, 100, Health.HEALTHY), 1).map { it.kind })
    }

    @Test
    fun fromAlertsMapsKindsAndSkipsDown() {
        val alerts = listOf(
            Alert(AlertKind.DOWN, "api", "down"),
            Alert(AlertKind.MEMORY, "api", "mem"),
            Alert(AlertKind.CPU, "api", "cpu"),
            Alert(AlertKind.LEAK, "api", "leak"),
        )
        assertEquals(
            listOf(AppEventKind.MEMORY_ALERT, AppEventKind.CPU_ALERT, AppEventKind.LEAK),
            AppEvents.fromAlerts(alerts, 1).map { it.kind },
        )
    }
}
