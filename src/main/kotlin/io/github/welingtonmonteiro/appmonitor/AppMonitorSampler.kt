package io.github.welingtonmonteiro.appmonitor

import io.github.welingtonmonteiro.appmonitor.model.MonitoredApp

/** One process of an app's tree, for the per-process breakdown in the memory chart. */
data class ProcRow(val pid: Long, val command: String, val rssKb: Long, val pctOfTree: Double)

/**
 * Runs one refresh cycle over the watched apps: resolve each target to a live PID, then sample the
 * whole process tree of every resolved app in **batched** {@code ps}/{@code lsof} (or
 * PowerShell/{@code netstat}) calls - one per cycle, not one per app - like Multiple Run's monitor.
 *
 * <p>Keeps the previous CPU-time sample per app between cycles so CPU % is the instantaneous
 * delta (docker-stats style), and must therefore be called from a single background thread; it is
 * not thread-safe against concurrent {@link #sample} calls.</p>
 */
class AppMonitorSampler {

    /** appId -> (pid -> cumulative CPU seconds) captured on the previous cycle. */
    private val prevCpuByApp = HashMap<String, Map<Long, Double>>()
    private var lastSampleMs = 0L
    private val hostTotalKb = ProcessStatsSampler.hostTotalMemoryKb()

    /** appId -> recorded memory session, guarded by [historyLock] (read from EDT, written here). */
    private val historyByApp = HashMap<String, ArrayDeque<MemoryHistory.Sample>>()
    private val lastRootPidByApp = HashMap<String, Long>()
    /** appId -> latest per-process breakdown of the tree, guarded by [historyLock]. */
    private val breakdownByApp = HashMap<String, List<ProcRow>>()
    private val historyLock = Any()

    /** Resolve + measure every app; order of the result matches the input order. */
    fun sample(apps: List<MonitoredApp>): List<AppSample> {
        val now = System.currentTimeMillis()
        val elapsedMs = if (lastSampleMs == 0L) 0L else now - lastSampleMs

        // 1. resolve each target to a root pid, then collect the whole tree of every resolved app
        val treePidsByApp = HashMap<String, Set<Long>>()
        val rootPidByApp = HashMap<String, Long>()
        val allPids = LinkedHashSet<Long>()
        for (app in apps) {
            val rootPid = TargetResolver.resolve(app.toTarget())
            if (rootPid != null) {
                val tree = ProcessStatsSampler.processTreePids(rootPid)
                rootPidByApp[app.id] = rootPid
                treePidsByApp[app.id] = tree
                allPids.addAll(tree)
            }
        }

        // 2. one batched sample of memory/CPU and of listening ports for all pids at once
        val statsByPid = ProcessStatsSampler.samplePids(allPids)
        val portsByPid = ProcessStatsSampler.sampleListeningPorts(allPids)

        // 3. build a row per app, and remember this cycle's CPU times for the next delta
        val nextPrev = HashMap<String, Map<Long, Double>>()
        val rows = ArrayList<AppSample>(apps.size)
        for (app in apps) {
            val rootPid = rootPidByApp[app.id]
            val tree = treePidsByApp[app.id]
            val stats = if (tree != null) ProcessStatsSampler.aggregate(statsByPid, tree) else null
            if (rootPid == null || tree == null || stats == null) {
                rows.add(AppSample.down(app))
                continue
            }

            nextPrev[app.id] = tree.mapNotNull { pid ->
                statsByPid[pid]?.let { pid to it.cpuTimeSeconds }
            }.toMap()

            val prev = prevCpuByApp[app.id] ?: emptyMap()
            val deltaSec = ProcessStatsSampler.cpuDeltaSeconds(statsByPid, prev, tree)
            val cpuPercent = if (deltaSec >= 0 && elapsedMs > 0) deltaSec / (elapsedMs / 1000.0) * 100.0 else -1.0

            val ports = tree.flatMap { portsByPid[it] ?: emptySet() }.toSortedSet().toList()
            val startMs = ProcessStatsSampler.processStartMillis(rootPid)
            val uptimeMs = if (startMs >= 0) now - startMs else -1L
            val limitMb: Int? = if (app.memAlertMb > 0) app.memAlertMb else null
            val memPercent = ProcessStatsSampler.memoryPercent(stats.rssKb, limitMb, hostTotalKb)
            val memTrend = recordHistory(app.id, rootPid, now, stats.rssKb, memPercent)
            val health = HealthChecker.healthOf(app.healthUrl)
            recordBreakdown(app.id, tree, statsByPid, stats.rssKb)

            rows.add(
                AppSample(
                    appId = app.id,
                    name = app.name,
                    targetLabel = app.targetLabel(),
                    up = true,
                    rootPid = rootPid,
                    ports = ports,
                    uptimeMs = uptimeMs,
                    rssKb = stats.rssKb,
                    memPercent = memPercent,
                    cpuPercent = cpuPercent,
                    memTrendKb = memTrend,
                    health = health,
                )
            )
        }

        lastSampleMs = now
        prevCpuByApp.clear()
        prevCpuByApp.putAll(nextPrev)
        return rows
    }

    /**
     * Appends one memory sample to the app's session and returns the last-minute RSS values for the
     * sparkline. A change of root PID starts a fresh session (the previous process is gone). The
     * session is capped at [MAX_SESSION_SAMPLES] so a long-lived app can't grow the history forever.
     */
    private fun recordHistory(appId: String, rootPid: Long, now: Long, rssKb: Long, percent: Double): List<Long> {
        synchronized(historyLock) {
            val history = historyByApp.getOrPut(appId) { ArrayDeque() }
            if (lastRootPidByApp[appId] != rootPid) history.clear() // new process -> new session
            lastRootPidByApp[appId] = rootPid
            history.addLast(MemoryHistory.Sample(now, rssKb, if (percent < 0) 0.0 else percent))
            while (history.size > MAX_SESSION_SAMPLES) history.removeFirst()
            return history.toList().takeLast(TREND_SAMPLES).map { it.rssKb }
        }
    }

    /** A snapshot of the full recorded memory session of an app (safe to read off the EDT). */
    fun history(appId: String): List<MemoryHistory.Sample> = synchronized(historyLock) {
        historyByApp[appId]?.toList() ?: emptyList()
    }

    /** Builds the per-process breakdown of a tree (each pid's RSS and share of the whole). */
    private fun recordBreakdown(appId: String, tree: Set<Long>, statsByPid: Map<Long, ProcessStatsSampler.Stats>, treeTotalKb: Long) {
        val total = treeTotalKb.coerceAtLeast(1)
        val procRows = tree.mapNotNull { pid ->
            statsByPid[pid]?.let { st ->
                ProcRow(pid, ProcessStatsSampler.processCommand(pid), st.rssKb, st.rssKb * 100.0 / total)
            }
        }.sortedByDescending { it.rssKb }
        synchronized(historyLock) { breakdownByApp[appId] = procRows }
    }

    /** A snapshot of the latest per-process breakdown of an app's tree (safe to read off the EDT). */
    fun breakdown(appId: String): List<ProcRow> = synchronized(historyLock) {
        breakdownByApp[appId] ?: emptyList()
    }

    private companion object {
        /** ~2.7h at a 2s refresh - bounds the per-app history memory. */
        const val MAX_SESSION_SAMPLES = 5000
        /** Samples shown in the row sparkline (~last minute at a 2s refresh). */
        const val TREND_SAMPLES = 30
    }
}
