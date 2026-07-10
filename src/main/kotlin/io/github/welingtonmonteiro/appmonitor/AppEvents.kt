package io.github.welingtonmonteiro.appmonitor

/** The kind of a recorded per-app event, shown in the memory dashboard's Events tab (Fase 4). */
enum class AppEventKind { STARTED, STOPPED, RESTARTED, HEALTHY, UNHEALTHY, MEMORY_ALERT, CPU_ALERT, LEAK, ACTION }

/** One timestamped event in an app's life. */
data class AppEvent(val timeMs: Long, val kind: AppEventKind, val detail: String)

/**
 * Derives per-app events from successive samples and from the alerts already computed for a refresh.
 * Pure and IDE-free so the transition rules are unit-testable; the panel records what this returns.
 */
object AppEvents {

    /** Lifecycle events from comparing the previous sample (if any) to the current one. */
    fun lifecycle(prev: AppSample?, curr: AppSample, now: Long): List<AppEvent> {
        val out = ArrayList<AppEvent>()
        val wasUp = prev?.up == true
        when {
            !wasUp && curr.up -> out.add(AppEvent(now, AppEventKind.STARTED, "up${pidSuffix(curr)}"))
            wasUp && !curr.up -> out.add(AppEvent(now, AppEventKind.STOPPED, "down"))
        }
        // a changed root PID while it stays up means the process was replaced (restart)
        if (wasUp && curr.up && prev!!.rootPid > 0 && curr.rootPid > 0 && prev.rootPid != curr.rootPid) {
            out.add(AppEvent(now, AppEventKind.RESTARTED, "pid ${prev.rootPid} -> ${curr.rootPid}"))
        }
        // health transitions (only meaningful while up and when a health check is configured)
        if (wasUp && curr.up) {
            if (prev!!.health != Health.UNHEALTHY && curr.health == Health.UNHEALTHY) {
                out.add(AppEvent(now, AppEventKind.UNHEALTHY, "health check failing"))
            } else if (prev.health == Health.UNHEALTHY && curr.health == Health.HEALTHY) {
                out.add(AppEvent(now, AppEventKind.HEALTHY, "health check passing"))
            }
        }
        return out
    }

    /** Maps the alerts raised this refresh to events. DOWN is skipped: [lifecycle] already logs it. */
    fun fromAlerts(alerts: List<Alert>, now: Long): List<AppEvent> =
        alerts.mapNotNull { alert ->
            val kind = when (alert.kind) {
                AlertKind.MEMORY -> AppEventKind.MEMORY_ALERT
                AlertKind.CPU -> AppEventKind.CPU_ALERT
                AlertKind.LEAK -> AppEventKind.LEAK
                AlertKind.DOWN -> return@mapNotNull null
            }
            AppEvent(now, kind, alert.message)
        }

    private fun pidSuffix(s: AppSample): String = if (s.rootPid > 0) " (pid ${s.rootPid})" else ""
}
