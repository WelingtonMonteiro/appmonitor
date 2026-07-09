package io.github.welingtonmonteiro.appmonitor

import io.github.welingtonmonteiro.appmonitor.model.MonitoredApp
import java.util.Locale

/** The kind of condition that raised an alert. */
enum class AlertKind { DOWN, MEMORY, CPU, LEAK }

/** One alert to surface as a balloon notification. */
data class Alert(val kind: AlertKind, val appName: String, val message: String)

/**
 * Per-app "armed" flags. A condition fires **once** when it becomes true and stays armed until it
 * recovers (docker-like edge alerts), so a persistently high value doesn't spam a notification every
 * two seconds.
 */
data class AlertArm(
    val down: Boolean = false,
    val mem: Boolean = false,
    val cpu: Boolean = false,
    val leak: Boolean = false,
)

/**
 * Decides which alerts an app raises this refresh. Pure and IDE-free (no notifications here) so the
 * edge-triggering / re-arming rules are unit-testable; the panel turns the returned [Alert]s into
 * IDE balloon notifications.
 */
object AlertPolicy {

    /**
     * Evaluate one app against its thresholds given the previously armed state. Returns the alerts
     * to raise now (conditions that just became true) and the new armed state to carry forward.
     * [leaking] is the caller's leak verdict (from [MemoryHistory.analyze]) for this app.
     */
    fun evaluate(app: MonitoredApp, sample: AppSample, leaking: Boolean, prev: AlertArm): Pair<List<Alert>, AlertArm> {
        val name = sample.name.ifBlank { app.targetLabel() }
        val alerts = ArrayList<Alert>()

        val down = !sample.up
        if (down && !prev.down) alerts += Alert(AlertKind.DOWN, name, "$name is down")

        val memHigh = sample.up && app.memAlertMb > 0 && sample.rssKb >= app.memAlertMb * 1024L
        if (memHigh && !prev.mem) {
            alerts += Alert(AlertKind.MEMORY, name,
                "$name memory ${ProcessStatsSampler.formatMemory(sample.rssKb)} reached the ${app.memAlertMb} MB alert")
        }

        val cpuHigh = sample.up && app.cpuAlertPercent > 0 &&
            sample.cpuPercent >= 0 && sample.cpuPercent >= app.cpuAlertPercent
        if (cpuHigh && !prev.cpu) {
            alerts += Alert(AlertKind.CPU, name,
                String.format(Locale.US, "%s CPU %.0f%% reached the %d%% alert", name, sample.cpuPercent, app.cpuAlertPercent))
        }

        val leak = sample.up && leaking
        if (leak && !prev.leak) alerts += Alert(AlertKind.LEAK, name, "$name shows a steady memory-leak trend")

        return alerts to AlertArm(down, memHigh, cpuHigh, leak)
    }
}
