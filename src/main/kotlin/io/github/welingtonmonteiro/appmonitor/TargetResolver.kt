package io.github.welingtonmonteiro.appmonitor

import io.github.welingtonmonteiro.appmonitor.model.Target
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Resolves a [Target] to a live root PID, the heart of the "no ProcessHandler" design: every
 * refresh re-resolves the target, so an app that went down and came back is picked up again.
 * Returns {@code null} when nothing matches (the app is reported **down**).
 */
object TargetResolver {

    fun resolve(target: Target): Long? = when (target) {
        is Target.Port -> resolvePort(target.port)
        is Target.Pid -> resolvePid(target.pid)
        is Target.ProcessName -> resolveProcessName(target.regex)
        // docker containers are not a host PID - the sampler handles them via the docker CLI
        is Target.Docker -> null
    }

    /** Resolve every target of an app to its root PID, keeping only the ones found (de-duplicated). */
    fun resolveAll(targets: List<Target>): List<Long> =
        targets.mapNotNull { resolve(it) }.distinct()

    private fun resolvePort(port: Int): Long? {
        if (port <= 0) return null
        // A port is held by a single LISTEN socket; if several pids report it (forked workers
        // sharing the socket) the lowest pid is the parent - the natural tree root.
        return ProcessStatsSampler.pidsListeningOnPort(port).minOrNull()
    }

    private fun resolvePid(pid: Long): Long? {
        if (pid <= 0) return null
        val handle = ProcessHandle.of(pid).orElse(null) ?: return null
        return if (handle.isAlive) pid else null
    }

    private fun resolveProcessName(regex: String): Long? {
        val pattern = compile(regex) ?: return null
        return ProcessHandle.allProcesses()
            .filter { it.isAlive }
            .filter { matches(pattern, commandOf(it)) }
            .map { it.pid() }
            .findFirst()
            .orElse(null)
    }

    private fun commandOf(handle: ProcessHandle): String =
        handle.info().commandLine().orElse(handle.info().command().orElse(""))

    private fun compile(regex: String): Pattern? {
        if (regex.isBlank()) return null
        return try {
            Pattern.compile(regex)
        } catch (e: PatternSyntaxException) {
            null
        }
    }

    /** True when the command line matches the (already compiled) pattern. Pure - unit-tested. */
    private fun matches(pattern: Pattern, command: String): Boolean =
        command.isNotEmpty() && pattern.matcher(command).find()

    /**
     * Whether a process-name regex matches a command line. Extracted as a pure, null-safe helper so
     * the matching rule (invalid regex never matches; empty command never matches) is unit-testable
     * without touching live processes.
     */
    @JvmStatic
    fun commandMatches(regex: String, command: String): Boolean {
        val pattern = compile(regex) ?: return false
        return matches(pattern, command)
    }
}
