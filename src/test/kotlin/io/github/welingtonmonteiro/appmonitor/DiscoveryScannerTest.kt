package io.github.welingtonmonteiro.appmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure suggestion-building of auto-discovery (no OS scan). */
class DiscoveryScannerTest {

    @Test
    fun buildsOneSuggestionPerUnmonitoredPortSortedByPort() {
        val byPid = mapOf(100L to setOf(3000, 9229), 5555L to setOf(8080))
        val commands = mapOf(100L to "/usr/bin/node /app/server.js", 5555L to "java -jar app.jar")

        val suggestions = DiscoveryScanner.buildSuggestions(byPid, commands, monitoredPorts = setOf(3000))

        assertEquals(listOf(8080, 9229), suggestions.map { it.port })
        assertEquals(5555L, suggestions[0].pid)
        assertEquals(100L, suggestions[1].pid)
    }

    @Test
    fun alreadyMonitoredPortsAreExcluded() {
        val byPid = mapOf(100L to setOf(3000))
        assertTrue(DiscoveryScanner.buildSuggestions(byPid, emptyMap(), setOf(3000)).isEmpty())
    }

    @Test
    fun defaultNameIsTheExecutableBasenameOrPortFallback() {
        assertEquals("node", Suggestion(9229, 100, "/usr/bin/node /app/server.js").defaultName())
        assertEquals("java", Suggestion(8080, 1, "java -jar app.jar").defaultName())
        assertEquals("app.exe", Suggestion(8080, 1, "C:\\bin\\app.exe --port 8080").defaultName())
        assertEquals("port-5000", Suggestion(5000, 1, "").defaultName())
    }

    @Test
    fun duplicatePortAcrossPidsIsListedOnce() {
        val byPid = mapOf(1L to setOf(8080), 2L to setOf(8080))
        assertEquals(1, DiscoveryScanner.buildSuggestions(byPid, emptyMap(), emptySet()).size)
    }
}
