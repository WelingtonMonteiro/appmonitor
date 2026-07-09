package io.github.welingtonmonteiro.appmonitor

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Holds the latest at-a-glance summary of the monitored apps (how many are up/down and their total
 * memory), written by the tool-window panel each refresh and read by the status-bar widget.
 */
@Service(Service.Level.PROJECT)
class AppMonitorStatusService {

    data class Summary(val up: Int = 0, val down: Int = 0, val totalRssKb: Long = 0)

    @Volatile
    var summary: Summary = Summary()

    companion object {
        fun getInstance(project: Project): AppMonitorStatusService = project.service()
    }
}
