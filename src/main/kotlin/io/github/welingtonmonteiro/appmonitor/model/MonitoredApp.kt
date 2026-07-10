package io.github.welingtonmonteiro.appmonitor.model

import java.util.UUID

/**
 * How the App Monitor finds the live process of an app on each refresh. This is the design shift
 * away from Multiple Run's `ProcessHandler`: instead of tracking a process the IDE launched, we
 * track a **target** and re-resolve it to a live PID every cycle (so an app that went down and came
 * back is picked up again).
 */
enum class TargetKind { PORT, PROCESS_NAME, PID, DOCKER }

/** The resolved, strongly-typed target of a [MonitoredApp] (derived from its persisted fields). */
sealed interface Target {
    /** Resolve the PID in LISTEN state on this TCP port. */
    data class Port(val port: Int) : Target

    /** Match a process whose command line matches this regex. */
    data class ProcessName(val regex: String) : Target

    /** A fixed PID. */
    data class Pid(val pid: Long) : Target

    /** A docker container by name or id (sampled via the docker CLI, not a host PID). */
    data class Docker(val container: String) : Target
}

/**
 * One app the user asked to watch. This is the persisted definition (a flat, mutable bean so the
 * IntelliJ `XmlSerializer` can round-trip it); the live measurements are computed each refresh and
 * are **not** part of this object.
 *
 * Only [name] and the target fields are required for the MVP; everything else is optional and
 * unlocks later features (health check, start/stop, alerts, grouping) without changing the model.
 */
class MonitoredApp {
    /** Stable identity across refreshes and restarts (selection, history and de-dup key). */
    var id: String = UUID.randomUUID().toString()
    var name: String = ""

    var targetKind: TargetKind = TargetKind.PORT
    /** Used when [targetKind] is [TargetKind.PORT]. */
    var port: Int = 0
    /** Used when [targetKind] is [TargetKind.PROCESS_NAME]. */
    var processNameRegex: String = ""
    /** Used when [targetKind] is [TargetKind.PID]. */
    var pid: Long = 0
    /** Used when [targetKind] is [TargetKind.DOCKER]: the container name or id. */
    var containerName: String = ""
    /**
     * Extra TCP ports folded into this app's measurement, comma-separated (e.g. `"9090, 4000"`).
     * Lets one row aggregate an app that listens on several ports, or a process-name target plus a
     * port. Ignored for [TargetKind.DOCKER]. Stored as a string so it round-trips trivially.
     */
    var extraPorts: String = ""

    // --- optional, for later phases ---
    var healthUrl: String = ""
    var startCmd: String = ""
    var stopCmd: String = ""
    var workingDir: String = ""
    var envFile: String = ""
    /** Memory alert threshold in MB; 0 = off. Also used as the "limit" for the Mem % column. */
    var memAlertMb: Int = 0
    /** CPU alert threshold in percent; 0 = off. */
    var cpuAlertPercent: Int = 0
    var tag: String = ""
    /** Packed 0xRRGGBB, or 0 for "no color". */
    var colorRgb: Int = 0

    // --- action rules (Fase 4) ---
    /** When true and a [startCmd] is set, auto-run it when the app goes down. */
    var restartOnDown: Boolean = false
    /** Memory threshold in MB for the sustained-memory rule; 0 = the rule is off. */
    var memActionMb: Int = 0
    /** How long memory must stay above [memActionMb] before the rule fires, in minutes (0 = at once). */
    var memActionMinutes: Int = 0
    /** What the sustained-memory rule does: "NOTIFY" / "KILL" / "RESTART" (empty = off). */
    var memAction: String = ""

    /** The strongly-typed **primary** target derived from the persisted fields. */
    fun toTarget(): Target = when (targetKind) {
        TargetKind.PORT -> Target.Port(port)
        TargetKind.PROCESS_NAME -> Target.ProcessName(processNameRegex)
        TargetKind.PID -> Target.Pid(pid)
        TargetKind.DOCKER -> Target.Docker(containerName)
    }

    /** The extra ports (parsed, valid, de-duplicated, minus the primary port when it is a port target). */
    fun extraPortList(): List<Int> {
        val extras = parsePorts(extraPorts)
        return if (targetKind == TargetKind.PORT) extras.filterNot { it == port } else extras
    }

    /**
     * Every target that defines this app: the primary one plus a [Target.Port] for each extra port.
     * A docker app never aggregates host ports, so it keeps just its primary target.
     */
    fun allTargets(): List<Target> {
        if (targetKind == TargetKind.DOCKER) return listOf(toTarget())
        return listOf(toTarget()) + extraPortList().map { Target.Port(it) }
    }

    /** Short human description of the target(s) (for the tooltip / secondary text). */
    fun targetLabel(): String {
        val base = when (targetKind) {
            TargetKind.PORT -> "port $port"
            TargetKind.PROCESS_NAME -> "name ~ /$processNameRegex/"
            TargetKind.PID -> "pid $pid"
            TargetKind.DOCKER -> "docker $containerName"
        }
        val extras = if (targetKind == TargetKind.DOCKER) emptyList() else extraPortList()
        return if (extras.isEmpty()) base else "$base + " + extras.joinToString(", ") { "port $it" }
    }

    companion object {
        /** Factory for the common case: watch an app by name + port. */
        fun ofPort(name: String, port: Int): MonitoredApp = MonitoredApp().apply {
            this.name = name
            this.targetKind = TargetKind.PORT
            this.port = port
        }

        /** Parse a comma/space-separated port list into valid, de-duplicated port numbers (pure). */
        fun parsePorts(text: String): List<Int> =
            text.split(',', ' ', '\t', ';')
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 1..65535 }
                .distinct()
    }
}
