package io.github.welingtonmonteiro.appmonitor

import io.github.welingtonmonteiro.appmonitor.model.MonitoredApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Edge-triggering and re-arming rules of the alert policy (pure, no notifications). */
class AlertPolicyTest {

    private fun sample(up: Boolean, rssKb: Long = 0, cpu: Double = 0.0) = AppSample(
        appId = "id", name = "api", targetLabel = "port 3003", up = up,
        rootPid = if (up) 100 else -1, ports = emptyList(), uptimeMs = 0,
        rssKb = rssKb, memPercent = 0.0, cpuPercent = cpu,
    )

    @Test
    fun downFiresOnceThenReArmsWhenBackUp() {
        val app = MonitoredApp.ofPort("api", 3003)

        val (a1, arm1) = AlertPolicy.evaluate(app, sample(up = false), false, AlertArm())
        assertEquals(1, a1.size)
        assertEquals(AlertKind.DOWN, a1[0].kind)
        assertTrue(arm1.down)

        // still down -> no repeat
        val (a2, arm2) = AlertPolicy.evaluate(app, sample(up = false), false, arm1)
        assertTrue(a2.isEmpty())

        // back up -> disarms, no alert
        val (a3, arm3) = AlertPolicy.evaluate(app, sample(up = true), false, arm2)
        assertTrue(a3.isEmpty())
        assertTrue(!arm3.down)

        // down again -> fires again
        val (a4, _) = AlertPolicy.evaluate(app, sample(up = false), false, arm3)
        assertEquals(AlertKind.DOWN, a4.single().kind)
    }

    @Test
    fun memoryAlertFiresWhenReachingTheLimit() {
        val app = MonitoredApp.ofPort("api", 3003).apply { memAlertMb = 100 }

        val (below, armBelow) = AlertPolicy.evaluate(app, sample(up = true, rssKb = 99 * 1024), false, AlertArm())
        assertTrue(below.isEmpty())

        val (at, armAt) = AlertPolicy.evaluate(app, sample(up = true, rssKb = 100 * 1024), false, armBelow)
        assertEquals(AlertKind.MEMORY, at.single().kind)
        assertTrue(armAt.mem)

        // stays high -> no repeat
        assertTrue(AlertPolicy.evaluate(app, sample(up = true, rssKb = 120 * 1024), false, armAt).first.isEmpty())
    }

    @Test
    fun cpuAlertNeedsAKnownValueOverTheThreshold() {
        val app = MonitoredApp.ofPort("api", 3003).apply { cpuAlertPercent = 80 }

        // unknown cpu (-1) must never alert
        assertTrue(AlertPolicy.evaluate(app, sample(up = true, cpu = -1.0), false, AlertArm()).first.isEmpty())

        val (over, _) = AlertPolicy.evaluate(app, sample(up = true, cpu = 90.0), false, AlertArm())
        assertEquals(AlertKind.CPU, over.single().kind)
    }

    @Test
    fun leakAlertFiresOnceWhileUp() {
        val app = MonitoredApp.ofPort("api", 3003)

        val (fired, arm) = AlertPolicy.evaluate(app, sample(up = true), leaking = true, AlertArm())
        assertEquals(AlertKind.LEAK, fired.single().kind)
        assertTrue(AlertPolicy.evaluate(app, sample(up = true), leaking = true, arm).first.isEmpty())
    }

    @Test
    fun noThresholdsMeansNoMemOrCpuAlerts() {
        val app = MonitoredApp.ofPort("api", 3003) // no memAlertMb / cpuAlertPercent
        val (alerts, _) = AlertPolicy.evaluate(app, sample(up = true, rssKb = 9_000_000, cpu = 500.0), false, AlertArm())
        assertTrue(alerts.isEmpty())
    }
}
