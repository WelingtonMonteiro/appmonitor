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
            healthUrl = "http://localhost:3003/health"
            memAlertMb = 512
            tag = "backend"
            colorRgb = 0x329205
        }

        val restored = XmlSerializer.deserialize(XmlSerializer.serialize(app), MonitoredApp::class.java)

        assertEquals(app.id, restored.id)
        assertEquals("eparts-api", restored.name)
        assertEquals(TargetKind.PORT, restored.targetKind)
        assertEquals(3003, restored.port)
        assertEquals("http://localhost:3003/health", restored.healthUrl)
        assertEquals(512, restored.memAlertMb)
        assertEquals("backend", restored.tag)
        assertEquals(0x329205, restored.colorRgb)
    }
}
