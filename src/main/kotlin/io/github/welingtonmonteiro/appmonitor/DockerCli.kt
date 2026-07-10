package io.github.welingtonmonteiro.appmonitor

import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Samples a docker container through the docker CLI: one `docker inspect` (running + start time),
 * then `docker stats` (CPU / memory) and `docker port` (published ports) when it is running. Blocking
 * (shells out); call off the EDT. Returns null only when no container name is given; a missing or
 * stopped container yields a "down" sample.
 */
object DockerCli {

    private val LOG = Logger.getInstance(DockerCli::class.java)
    private const val TIMEOUT_SECONDS = 5L
    private const val INSPECT_FORMAT = "{{.State.Running}};{{.State.StartedAt}}"
    private const val STATS_FORMAT = "{{.CPUPerc}};{{.MemUsage}};{{.MemPerc}}"

    fun sample(container: String): DockerContainerSample? {
        if (container.isBlank()) return null
        val inspect = run("docker", "inspect", "-f", INSPECT_FORMAT, container)
        if (inspect.isEmpty()) {
            // container not found (or docker not installed): report it as down
            return DockerContainerSample(false, -1.0, -1, -1.0, emptyList(), null)
        }
        val (running, startedAtMs) = DockerStats.parseInspect(inspect.first())
        if (!running) {
            return DockerContainerSample(false, -1.0, -1, -1.0, emptyList(), startedAtMs)
        }
        val statsLine = run("docker", "stats", "--no-stream", "--format", STATS_FORMAT, container).firstOrNull() ?: ""
        val (cpu, memKb, memPerc) = DockerStats.parseStats(statsLine)
        val ports = DockerStats.parsePorts(run("docker", "port", container))
        return DockerContainerSample(true, cpu, memKb, memPerc, ports, startedAtMs)
    }

    private fun run(vararg command: String): List<String> {
        val lines = ArrayList<String>()
        try {
            val process = ProcessBuilder(*command).redirectErrorStream(false).start()
            BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    lines.add(line)
                    line = reader.readLine()
                }
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
        } catch (e: Exception) {
            LOG.debug("App Monitor: docker command not available: ${command.firstOrNull()}", e)
        }
        return lines
    }
}
