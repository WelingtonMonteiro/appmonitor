package io.github.welingtonmonteiro.appmonitor

import com.intellij.util.xmlb.XmlSerializer
import io.github.welingtonmonteiro.appmonitor.model.MonitoredApp
import io.github.welingtonmonteiro.appmonitor.model.Target
import io.github.welingtonmonteiro.appmonitor.model.TargetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic tests for the target model and its process-name matching (no running IDE needed). */
class AppMonitorModelTest {

    // --- MonitoredApp.toTarget ---------------------------------------------------------------

    @Test
    fun mapsEachKindToItsTypedTarget() {
        assertEquals(Target.Port(3003), MonitoredApp.ofPort("api", 3003).toTarget())

        val byName = MonitoredApp().apply { targetKind = TargetKind.PROCESS_NAME; processNameRegex = "node.*server" }
        assertEquals(Target.ProcessName("node.*server"), byName.toTarget())

        val byPid = MonitoredApp().apply { targetKind = TargetKind.PID; pid = 4242 }
        assertEquals(Target.Pid(4242), byPid.toTarget())
    }

    @Test
    fun ofPortSetsNamePortAndKind() {
        val app = MonitoredApp.ofPort("web", 8080)
        assertEquals("web", app.name)
        assertEquals(8080, app.port)
        assertEquals(TargetKind.PORT, app.targetKind)
        assertTrue("a fresh app gets a non-blank id", app.id.isNotBlank())
    }

    // --- multiple targets per app (primary + extra ports) ------------------------------------

    @Test
    fun parsePortsKeepsValidDistinctPortsOnly() {
        assertEquals(listOf(9090, 4000), MonitoredApp.parsePorts("9090, 4000"))
        assertEquals("tolerates spaces and semicolons", listOf(80, 443), MonitoredApp.parsePorts(" 80 ; 443 "))
        assertEquals("drops non-numbers, out-of-range and duplicates",
                     listOf(22), MonitoredApp.parsePorts("abc, 70000, 0, 22, 22"))
        assertTrue(MonitoredApp.parsePorts("").isEmpty())
    }

    @Test
    fun allTargetsFoldsExtraPortsInAndDropsThePrimaryPort() {
        val portApp = MonitoredApp.ofPort("api", 3000).apply { extraPorts = "3000, 9090, 4000" }
        assertEquals(
            listOf(Target.Port(3000), Target.Port(9090), Target.Port(4000)),
            portApp.allTargets()
        )

        val nameApp = MonitoredApp().apply {
            targetKind = TargetKind.PROCESS_NAME; processNameRegex = "node.*api"; extraPorts = "9090"
        }
        assertEquals(listOf(Target.ProcessName("node.*api"), Target.Port(9090)), nameApp.allTargets())
    }

    @Test
    fun dockerAppNeverAggregatesExtraPorts() {
        val docker = MonitoredApp().apply {
            targetKind = TargetKind.DOCKER; containerName = "web"; extraPorts = "9090"
        }
        assertEquals(listOf(Target.Docker("web")), docker.allTargets())
        assertEquals("docker web", docker.targetLabel())
    }

    @Test
    fun targetLabelMentionsExtraPorts() {
        assertEquals("port 3000", MonitoredApp.ofPort("api", 3000).targetLabel())
        assertEquals(
            "port 3000 + port 9090",
            MonitoredApp.ofPort("api", 3000).apply { extraPorts = "9090" }.targetLabel()
        )
    }

    @Test
    fun resolveAllKeepsOnlyResolvedTargets() {
        assertTrue(TargetResolver.resolveAll(emptyList()).isEmpty())
        // docker never resolves to a host pid; a non-positive pid never resolves either
        assertTrue(TargetResolver.resolveAll(listOf(Target.Docker("x"), Target.Pid(-1))).isEmpty())
    }

    // --- TargetResolver.commandMatches (process-name target) ---------------------------------

    @Test
    fun processNameRegexMatchesCommandLine() {
        assertTrue(TargetResolver.commandMatches("node.*server\\.js",
                                                 "/usr/bin/node /app/server.js --port 3000"))
        assertTrue("substring match, not full match", TargetResolver.commandMatches("eparts-api",
                                                 "node /home/wm/eparts-api/index.js"))
        assertFalse(TargetResolver.commandMatches("python", "/usr/bin/node /app/server.js"))
    }

    @Test
    fun invalidRegexOrEmptyCommandNeverMatches() {
        assertFalse("an unparseable regex must never match", TargetResolver.commandMatches("[", "anything"))
        assertFalse("a blank regex never matches", TargetResolver.commandMatches("", "node server.js"))
        assertFalse("an empty command never matches", TargetResolver.commandMatches("node", ""))
    }

    // --- persistence round-trip (XmlSerializer, as PersistentStateComponent uses) -------------

    @Test
    fun monitoredAppSurvivesXmlRoundTrip() {
        val app = MonitoredApp.ofPort("eparts-api", 3003).apply {
            extraPorts = "9090, 4000"
            healthUrl = "http://localhost:3003/health"
            memAlertMb = 512
            tag = "backend"
            colorRgb = 0x329205
            restartOnDown = true
            memActionMb = 1024
            memActionMinutes = 5
            memAction = "RESTART"
        }

        val restored = XmlSerializer.deserialize(XmlSerializer.serialize(app), MonitoredApp::class.java)

        assertEquals(app.id, restored.id)
        assertEquals("eparts-api", restored.name)
        assertEquals(TargetKind.PORT, restored.targetKind)
        assertEquals(3003, restored.port)
        assertEquals("9090, 4000", restored.extraPorts)
        assertEquals("http://localhost:3003/health", restored.healthUrl)
        assertEquals(512, restored.memAlertMb)
        assertEquals("backend", restored.tag)
        assertEquals(0x329205, restored.colorRgb)
        assertTrue(restored.restartOnDown)
        assertEquals(1024, restored.memActionMb)
        assertEquals(5, restored.memActionMinutes)
        assertEquals("RESTART", restored.memAction)
    }
}
