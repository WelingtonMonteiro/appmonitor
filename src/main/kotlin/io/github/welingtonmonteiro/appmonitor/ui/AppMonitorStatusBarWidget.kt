package io.github.welingtonmonteiro.appmonitor.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Consumer
import io.github.welingtonmonteiro.appmonitor.AppMonitorStatusService
import io.github.welingtonmonteiro.appmonitor.ProcessStatsSampler
import java.awt.Component
import java.awt.event.MouseEvent

/**
 * A compact status-bar indicator: "▲N ▼M" (apps up / down) with the total memory in the tooltip.
 * Clicking it opens the App Monitor tool window. Fed by [AppMonitorStatusService], which the panel
 * updates on every refresh.
 */
class AppMonitorStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    private var statusBar: StatusBar? = null

    override fun ID(): String = ID

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
    }

    override fun dispose() {
        statusBar = null
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String {
        val s = AppMonitorStatusService.getInstance(project).summary
        return "App Monitor: ▲${s.up} ▼${s.down}"
    }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getTooltipText(): String {
        val s = AppMonitorStatusService.getInstance(project).summary
        return "${s.up} up, ${s.down} down, ${ProcessStatsSampler.formatMemory(s.totalRssKb)} total memory"
    }

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        ToolWindowManager.getInstance(project).getToolWindow("App Monitor")?.activate(null)
    }

    companion object {
        const val ID = "AppMonitor.StatusBarWidget"
    }
}
