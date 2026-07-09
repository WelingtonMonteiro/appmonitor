package io.github.welingtonmonteiro.appmonitor

import io.github.welingtonmonteiro.appmonitor.model.MonitoredApp

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
                )
            )
        }

        lastSampleMs = now
        prevCpuByApp.clear()
        prevCpuByApp.putAll(nextPrev)
        return rows
    }
}
