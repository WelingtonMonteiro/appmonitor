package io.github.welingtonmonteiro.appmonitor

import io.github.welingtonmonteiro.appmonitor.model.MonitoredApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure action-rule engine: restart-on-down and sustained-high-memory (no running IDE needed). */
class ActionRulesTest {

    private fun app(block: MonitoredApp.() -> Unit) = MonitoredApp.ofPort("api", 3003).apply(block)

    private fun sample(up: Boolean, rssKb: Long = 0) = AppSample(
        appId = "id", name = "api", targetLabel = "port 3003", up = up,
        rootPid = if (up) 100 else -1, ports = emptyList(), uptimeMs = 0,
        rssKb = if (up) rssKb else -1, memPercent = 0.0, cpuPercent = 0.0,
    )

    // --- restart on down -------------------------------------------------------------------------

    @Test
    fun restartOnDownFiresOnceWhileDownThenReArmsWhenUp() {
        val a = app { restartOnDown = true; startCmd = "./run.sh" }

        val (f1, s1) = ActionRules.evaluate(a, sample(false), now = 1000, prev = RuleState())
        assertEquals(listOf(ActionKind.RESTART), f1.map { it.kind })
        assertTrue(s1.downArmed)

        // still down next refresh -> no second restart
        val (f2, s2) = ActionRules.evaluate(a, sample(false), now = 3000, prev = s1)
        assertTrue(f2.isEmpty())
        assertTrue(s2.downArmed)

        // came back up -> re-arm
        val (f3, s3) = ActionRules.evaluate(a, sample(true), now = 5000, prev = s2)
        assertTrue(f3.isEmpty())
        assertTrue(!s3.downArmed)

        // goes down again -> fires again
        val (f4, _) = ActionRules.evaluate(a, sample(false), now = 7000, prev = s3)
        assertEquals(listOf(ActionKind.RESTART), f4.map { it.kind })
    }

    @Test
    fun restartOnDownNeedsTheFlagAndAStartCommand() {
        val noFlag = app { restartOnDown = false; startCmd = "./run.sh" }
        assertTrue(ActionRules.evaluate(noFlag, sample(false), 1000, RuleState()).first.isEmpty())

        val noCmd = app { restartOnDown = true; startCmd = "" }
        assertTrue(ActionRules.evaluate(noCmd, sample(false), 1000, RuleState()).first.isEmpty())
    }

    // --- sustained high memory -------------------------------------------------------------------

    // realistic epoch-ms base (real timestamps are never 0, which is the "not high" sentinel)
    private val t0 = 1_700_000_000_000L

    @Test
    fun memoryActionFiresOnlyAfterTheSustainedDuration() {
        val a = app { memActionMb = 100; memActionMinutes = 1; memAction = "KILL" } // 100 MB, held 1 min
        val over = 200 * 1024L // 200 MB > threshold

        // first over-threshold sample starts the clock, does not fire yet
        val (f0, s0) = ActionRules.evaluate(a, sample(true, over), now = t0, prev = RuleState())
        assertTrue(f0.isEmpty())
        assertEquals(t0, s0.memHighSinceMs) // "since" is the first over-threshold time

        // 30s later: still under the 1-minute hold -> no fire
        val (f30, s30) = ActionRules.evaluate(a, sample(true, over), now = t0 + 30_000, prev = s0)
        assertTrue(f30.isEmpty())

        // 60s later: held long enough -> KILL fires once
        val (f60, s60) = ActionRules.evaluate(a, sample(true, over), now = t0 + 60_000, prev = s30)
        assertEquals(listOf(ActionKind.KILL), f60.map { it.kind })
        assertTrue(s60.memArmed)

        // stays high -> does not fire again
        assertTrue(ActionRules.evaluate(a, sample(true, over), t0 + 90_000, s60).first.isEmpty())
    }

    @Test
    fun memoryActionReArmsAfterDroppingBelowThreshold() {
        val a = app { memActionMb = 100; memActionMinutes = 0; memAction = "RESTART" }
        val over = 200 * 1024L

        val (f1, s1) = ActionRules.evaluate(a, sample(true, over), t0, RuleState())
        assertEquals(listOf(ActionKind.RESTART), f1.map { it.kind }) // 0 minutes -> immediate
        // drop below threshold -> re-arm
        val (f2, s2) = ActionRules.evaluate(a, sample(true, 10 * 1024L), t0 + 2000, s1)
        assertTrue(f2.isEmpty())
        assertTrue(!s2.memArmed)
        // back over -> fires again
        assertEquals(listOf(ActionKind.RESTART),
            ActionRules.evaluate(a, sample(true, over), t0 + 4000, s2).first.map { it.kind })
    }

    @Test
    fun memoryActionOffOrDownDoesNothing() {
        val off = app { memActionMb = 100; memAction = "" } // no action
        assertTrue(ActionRules.evaluate(off, sample(true, 999 * 1024L), 0, RuleState()).first.isEmpty())

        val down = app { memActionMb = 100; memAction = "KILL" }
        assertTrue(ActionRules.evaluate(down, sample(false), 0, RuleState()).first.isEmpty())
    }

    @Test
    fun parseActionMapsKnownVerbsOnly() {
        assertEquals(ActionKind.NOTIFY, ActionRules.parseAction("notify"))
        assertEquals(ActionKind.KILL, ActionRules.parseAction("KILL"))
        assertEquals(ActionKind.RESTART, ActionRules.parseAction(" Restart "))
        assertNull(ActionRules.parseAction(""))
        assertNull(ActionRules.parseAction("boom"))
    }
}
