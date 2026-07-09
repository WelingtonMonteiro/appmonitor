package io.github.welingtonmonteiro.appmonitor.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/** Registers the "App Monitor" tool window (bottom stripe) and wires up its live panel. */
class AppMonitorToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = AppMonitorPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        // dispose the panel (stops the refresh alarm) when the tool window content goes away
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}
