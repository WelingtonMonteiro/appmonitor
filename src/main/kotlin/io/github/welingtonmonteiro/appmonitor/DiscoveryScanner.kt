package io.github.welingtonmonteiro.appmonitor

/** A listening port found on the machine that is not yet monitored - a candidate app to add. */
data class Suggestion(val port: Int, val pid: Long, val command: String) {
    /** A short default name from the command's executable basename, or "port-<port>". */
    fun defaultName(): String {
        val exec = command.trim()
            .substringBefore(' ')
            .substringAfterLast('/')
            .substringAfterLast('\\')
        return exec.ifBlank { "port-$port" }
    }
}

/**
 * Auto-discovery: scan every TCP port in LISTEN on the machine and suggest the ones not already
 * monitored, so the user can add them with a click. The OS scan lives in [ProcessStatsSampler]; the
 * suggestion-building is pure and unit-tested here.
 */
object DiscoveryScanner {

    /** Scan the machine (blocking - call off the EDT) and suggest apps for unmonitored ports. */
    fun discover(monitoredPorts: Set<Int>): List<Suggestion> {
        val byPid: Map<Long, Set<Int>> = ProcessStatsSampler.allListeningPorts()
        val commands = byPid.keys.associateWith { ProcessStatsSampler.processCommand(it) }
        return buildSuggestions(byPid, commands, monitoredPorts)
    }

    /** Pure: one suggestion per not-yet-monitored port, de-duplicated by port and sorted by port. */
    fun buildSuggestions(
        byPid: Map<Long, Set<Int>>,
        commands: Map<Long, String>,
        monitoredPorts: Set<Int>,
    ): List<Suggestion> {
        val suggestions = ArrayList<Suggestion>()
        for ((pid, ports) in byPid) {
            for (port in ports) {
                if (port !in monitoredPorts) suggestions += Suggestion(port, pid, commands[pid] ?: "")
            }
        }
        return suggestions.distinctBy { it.port }.sortedBy { it.port }
    }
}
