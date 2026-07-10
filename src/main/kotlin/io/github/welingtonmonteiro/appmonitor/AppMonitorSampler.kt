package io.github.welingtonmonteiro.appmonitor

import io.github.welingtonmonteiro.appmonitor.model.MonitoredApp
import io.github.welingtonmonteiro.appmonitor.model.TargetKind

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
class AppMonitorSampler(private val store: HistoryStore? = null) {

    /** appId -> (pid -> cumulative CPU seconds) captured on the previous cycle. */
    private val prevCpuByApp = HashMap<String, Map<Long, Double>>()
    private var lastSampleMs = 0L
    private val hostTotalKb = ProcessStatsSampler.hostTotalMemoryKb()

    /** appId -> recorded memory session, guarded by [historyLock] (read from EDT, written here). */
    private val historyByApp = HashMap<String, ArrayDeque<MemoryHistory.Sample>>()
    private val lastRootPidByApp = HashMap<String, Long>()
    /** appId -> the last finished session, kept for comparison (guarded by [historyLock]). */
    private val previousByApp = HashMap<String, List<MemoryHistory.Sample>>()
    /** appIds already restored from disk this run (guarded by [historyLock]). */
    private val restored = HashSet<String>()
    /** appId -> refresh counter, so the current session is flushed to disk only every so often. */
    private val persistTick = HashMap<String, Int>()
    /** appId -> latest per-process breakdown of the tree, guarded by [historyLock]. */
    private val breakdownByApp = HashMap<String, List<ProcRow>>()
    private val historyLock = Any()

    /** Resolve + measure every app; order of the result matches the input order. */
    fun sample(apps: List<MonitoredApp>): List<AppSample> {
        val now = System.currentTimeMillis()
        val elapsedMs = if (lastSampleMs == 0L) 0L else now - lastSampleMs

        // 1. resolve every target of each app (primary + extra ports) to root pids, then collect and
        //    union the whole process tree of each resolved root - so one row aggregates all of them
        val treePidsByApp = HashMap<String, Set<Long>>()
        val rootPidByApp = HashMap<String, Long>()
        val allPids = LinkedHashSet<Long>()
        for (app in apps) {
            val roots = TargetResolver.resolveAll(app.allTargets())
            if (roots.isNotEmpty()) {
                val tree = LinkedHashSet<Long>()
                for (root in roots) tree.addAll(ProcessStatsSampler.processTreePids(root))
                // the lowest root is the display PID and the session key (stable across a refresh)
                rootPidByApp[app.id] = roots.min()
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
            if (app.targetKind == TargetKind.DOCKER) {
                rows.add(buildDockerRow(app, now))
                continue
            }
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
                    tag = app.tag,
                    colorRgb = app.colorRgb,
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
            restoreFromDisk(appId, history)
            if (lastRootPidByApp[appId] != rootPid) {
                // the previous process/session ended: keep it as the comparison baseline, then reset
                if (history.isNotEmpty()) {
                    previousByApp[appId] = history.toList()
                    store?.saveCurrent(appId, lastRootPidByApp[appId] ?: rootPid, history.toList())
                    store?.promoteToPrevious(appId)
                }
                history.clear()
            }
            lastRootPidByApp[appId] = rootPid
            history.addLast(MemoryHistory.Sample(now, rssKb, if (percent < 0) 0.0 else percent))
            while (history.size > MAX_SESSION_SAMPLES) history.removeFirst()
            maybePersist(appId, rootPid, history)
            return history.toList().takeLast(TREND_SAMPLES).map { it.rssKb }
        }
    }

    /** On the first sighting of an app this run, restore its persisted session and previous one. */
    private fun restoreFromDisk(appId: String, history: ArrayDeque<MemoryHistory.Sample>) {
        if (store == null || !restored.add(appId)) return
        val current = store.loadCurrent(appId)
        if (current.isNotEmpty()) {
            history.addAll(current)
            // adopt the persisted root pid so a still-running process continues the same session
            lastRootPidByApp[appId] = store.loadCurrentPid(appId) ?: lastRootPidByApp[appId] ?: Long.MIN_VALUE
        }
        val prev = store.loadPrevious(appId)
        if (prev.isNotEmpty()) previousByApp[appId] = prev
    }

    /** Flush the current session to disk every [PERSIST_EVERY] refreshes (bounds the write rate). */
    private fun maybePersist(appId: String, rootPid: Long, history: ArrayDeque<MemoryHistory.Sample>) {
        if (store == null) return
        val n = (persistTick[appId] ?: 0) + 1
        persistTick[appId] = n
        if (n % PERSIST_EVERY == 0) store.saveCurrent(appId, rootPid, history.toList())
    }

    /** The last finished session of an app (for the chart's comparison); safe to read off the EDT. */
    fun previousHistory(appId: String): List<MemoryHistory.Sample> =
        synchronized(historyLock) { previousByApp[appId] ?: emptyList() }

    /** Persist every current session now (call when the tool window closes, to not lose the tail). */
    fun flush() {
        if (store == null) return
        synchronized(historyLock) {
            for ((appId, history) in historyByApp) {
                if (history.isNotEmpty()) store.saveCurrent(appId, lastRootPidByApp[appId] ?: -1L, history.toList())
            }
        }
    }

    /** Drop and delete all recorded/persisted data of an app (when it stops being monitored). */
    fun forget(appId: String) {
        synchronized(historyLock) {
            historyByApp.remove(appId)
            lastRootPidByApp.remove(appId)
            previousByApp.remove(appId)
            breakdownByApp.remove(appId)
            restored.remove(appId)
            persistTick.remove(appId)
        }
        store?.deleteFor(appId)
    }

    /** Build a row for a docker-target app from the docker CLI (no host PID / process tree). */
    private fun buildDockerRow(app: MonitoredApp, now: Long): AppSample {
        val docker = DockerCli.sample(app.containerName)
        if (docker == null || !docker.up) return AppSample.down(app)
        // a container restart gets a new StartedAt, which resets the recorded memory session
        val memTrend = recordHistory(app.id, docker.startedAtMs ?: 0L, now, docker.memUsedKb, docker.memPercent)
        val uptimeMs = if (docker.startedAtMs != null && docker.startedAtMs > 0) now - docker.startedAtMs else -1L
        return AppSample(
            appId = app.id,
            name = app.name,
            targetLabel = app.targetLabel(),
            up = true,
            rootPid = -1,
            ports = docker.ports,
            uptimeMs = uptimeMs,
            rssKb = docker.memUsedKb,
            memPercent = docker.memPercent,
            cpuPercent = docker.cpuPercent,
            memTrendKb = memTrend,
            health = HealthChecker.healthOf(app.healthUrl),
            tag = app.tag,
            colorRgb = app.colorRgb,
        )
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
        /** Persist the current session to disk every N refreshes (~30s at a 2s refresh). */
        const val PERSIST_EVERY = 15
    }
}
