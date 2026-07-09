package io.github.welingtonmonteiro.appmonitor

import io.github.welingtonmonteiro.appmonitor.model.MonitoredApp

/** Result of an app's health check: none configured, or the outcome of the HTTP probe. */
enum class Health { NONE, HEALTHY, UNHEALTHY }

/**
 * One row of the monitor at one refresh: the live measurement of a [MonitoredApp]. Numeric and
 * IDE-free (formatting happens in the table model); {@code -1} / empty means "not available".
 */
data class AppSample(
    val appId: String,
    val name: String,
    val targetLabel: String,
    /** false = the target could not be resolved this refresh (the app is down). */
    val up: Boolean,
    /** Resolved root PID, or -1 when down. */
    val rootPid: Long,
    val ports: List<Int>,
    /** Wall-clock uptime of the root process in ms, or -1 when unknown. */
    val uptimeMs: Long,
    /** Resident memory of the whole process tree in KB, or -1 when unavailable. */
    val rssKb: Long,
    /** Memory percentage (vs. alert limit if set, else host total), or -1. */
    val memPercent: Double,
    /** Instantaneous CPU percentage of the tree, or -1 before the first delta is known. */
    val cpuPercent: Double,
    /** Recent RSS values (KB), oldest→newest, for the Mem-trend sparkline; empty when down. */
    val memTrendKb: List<Long> = emptyList(),
    /** HTTP health outcome when a health URL is configured; [Health.NONE] otherwise. */
    val health: Health = Health.NONE,
    /** The app's tag/group (for filtering and grouping); empty when none. */
    val tag: String = "",
    /** The app's colour packed 0xRRGGBB, or 0 for none. */
    val colorRgb: Int = 0,
) {
    companion object {
        /** A down row: the app exists in the list but its target was not found this refresh. */
        fun down(app: MonitoredApp): AppSample = AppSample(
            appId = app.id,
            name = app.name,
            targetLabel = app.targetLabel(),
            up = false,
            rootPid = -1,
            ports = emptyList(),
            uptimeMs = -1,
            rssKb = -1,
            memPercent = -1.0,
            cpuPercent = -1.0,
            tag = app.tag,
            colorRgb = app.colorRgb,
        )
    }
}
