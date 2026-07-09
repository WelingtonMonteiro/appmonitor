package io.github.welingtonmonteiro.appmonitor

import com.intellij.openapi.diagnostic.Logger
import io.github.welingtonmonteiro.appmonitor.model.MonitoredApp
import java.io.File
import java.util.Locale

/**
 * Launches an app's user-configured start/stop command (Fase 3: the mini process manager). The
 * command runs through the OS shell in the app's working directory, with the variables from its
 * optional `.env` file added to the environment. Fire-and-forget: the process is detached, since
 * App Monitor tracks it afterwards by target, not by a process handle.
 *
 * Blocking (spawns a process); call off the EDT.
 */
object AppCommandRunner {

    private val LOG = Logger.getInstance(AppCommandRunner::class.java)

    /** Run the app's start command. Returns false when it has none or the launch failed. */
    fun start(app: MonitoredApp, baseDir: String?): Boolean =
        run(app.startCmd, app.workingDir, app.envFile, baseDir)

    /** Run the app's stop command. Returns false when it has none (the caller may force-kill instead). */
    fun stop(app: MonitoredApp, baseDir: String?): Boolean =
        run(app.stopCmd, app.workingDir, app.envFile, baseDir)

    private fun run(command: String, workingDir: String, envFile: String, baseDir: String?): Boolean {
        if (command.isBlank()) return false
        val builder = ProcessBuilder(shellInvocation(command, isWindows()))
        resolve(workingDir, baseDir)?.let { if (it.isDirectory) builder.directory(it) }
        builder.environment().putAll(loadEnv(envFile, baseDir))
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        builder.redirectError(ProcessBuilder.Redirect.DISCARD)
        return try {
            builder.start()
            true
        } catch (e: Exception) {
            LOG.warn("App Monitor: failed to run command: $command", e)
            false
        }
    }

    /** The shell invocation for a raw command line: {@code sh -c} on Unix, {@code cmd /c} on Windows. */
    fun shellInvocation(command: String, windows: Boolean): List<String> =
        if (windows) listOf("cmd.exe", "/c", command) else listOf("/bin/sh", "-c", command)

    /** Resolve a path against the project base dir when it is relative; null when blank. */
    private fun resolve(path: String, baseDir: String?): File? {
        if (path.isBlank()) return null
        val file = File(path)
        return if (file.isAbsolute || baseDir == null) file else File(baseDir, path)
    }

    private fun loadEnv(envFile: String, baseDir: String?): Map<String, String> {
        val file = resolve(envFile, baseDir) ?: return emptyMap()
        return try {
            if (file.isFile) EnvFile.parse(file.readText()) else emptyMap()
        } catch (e: Exception) {
            LOG.warn("App Monitor: failed to read env file: $envFile", e)
            emptyMap()
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("win")
}
