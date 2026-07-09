package io.github.welingtonmonteiro.appmonitor.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.Alarm
import io.github.welingtonmonteiro.appmonitor.AppMonitorSampler
import io.github.welingtonmonteiro.appmonitor.AppSample
import io.github.welingtonmonteiro.appmonitor.ProcessStatsSampler
import io.github.welingtonmonteiro.appmonitor.model.TargetKind
import io.github.welingtonmonteiro.appmonitor.state.MonitoredAppsState
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * The App Monitor tool-window UI: a docker-stats-like table over the watched apps, refreshed every
 * two seconds on a background thread. The list of apps is persisted (project-level), so it survives
 * restarts; each refresh re-resolves every target, so an app that came back is shown again.
 */
class AppMonitorPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val state = MonitoredAppsState.getInstance(project)
    private val sampler = AppMonitorSampler()
    private val model = AppTableModel()
    private val table = JBTable(model)
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    @Volatile
    private var disposed = false

    init {
        table.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        table.setShowGrid(false)
        table.rowHeight = table.rowHeight.coerceAtLeast(22)
        table.emptyText.text = "No apps monitored yet."
        table.emptyText.appendSecondaryText(
            "Add one by port  (＋)", com.intellij.ui.SimpleTextAttributes.LINK_ATTRIBUTES
        ) { addApp() }
        installStatusRenderer()
        installSparkline()
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val row = table.rowAtPoint(e.point)
                if (row < 0) return
                if (table.columnAtPoint(e.point) == AppTableModel.Column.MEM_TREND.ordinal) {
                    model.sampleAt(row)?.let { openChart(it) }
                } else if (e.clickCount == 2) {
                    editSelected()
                }
            }
        })

        toolbar = buildToolbar()
        setContent(ScrollPaneFactory.createScrollPane(table))

        scheduleNext(0)
    }

    // --- toolbar ---------------------------------------------------------------------------------

    private fun buildToolbar(): javax.swing.JComponent {
        val group = DefaultActionGroup().apply {
            add(object : DumbAwareAction("Add App", "Add an app to monitor by port", AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) = addApp()
            })
            add(object : DumbAwareAction("Remove App", "Stop monitoring the selected app(s)", AllIcons.General.Remove) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = table.selectedRowCount > 0
                }
                override fun actionPerformed(e: AnActionEvent) = removeSelected()
            })
            add(object : DumbAwareAction("Edit App", "Edit the selected app", AllIcons.Actions.Edit) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = table.selectedRowCount == 1
                }
                override fun actionPerformed(e: AnActionEvent) = editSelected()
            })
            add(object : DumbAwareAction("Memory Chart", "Open the full memory chart and leak analysis of the selected app", AllIcons.General.InspectionsEye) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = selectedSamples().singleOrNull()?.up == true
                }
                override fun actionPerformed(e: AnActionEvent) {
                    selectedSamples().singleOrNull()?.let { openChart(it) }
                }
            })
            addSeparator()
            add(object : DumbAwareAction("Refresh", "Refresh now", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = refreshNow()
            })
            add(object : DumbAwareAction("Kill Process on Port", "Kill whatever process listens on a TCP port", AllIcons.Actions.Cancel) {
                override fun actionPerformed(e: AnActionEvent) = killByPort()
            })
            add(object : DumbAwareAction("Force Kill", "Force-kill the process tree of the selected running app(s)", AllIcons.Actions.Suspend) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = selectedSamples().any { it.up }
                }
                override fun actionPerformed(e: AnActionEvent) = forceKillSelected()
            })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("AppMonitor", group, true)
        toolbar.targetComponent = table
        return toolbar.component
    }

    // --- actions ---------------------------------------------------------------------------------

    private fun addApp() {
        val dialog = AddAppDialog(project)
        if (dialog.showAndGet()) {
            val app = dialog.result()
            if (app.targetKind == TargetKind.PORT && state.hasPort(app.port)) {
                val proceed = Messages.showYesNoDialog(
                    project, "Another app already watches port ${app.port}. Add anyway?",
                    "Duplicate Port", Messages.getWarningIcon()
                )
                if (proceed != Messages.YES) return
            }
            state.add(app)
            refreshNow()
        }
    }

    private fun editSelected() {
        val sample = selectedSamples().singleOrNull() ?: return
        val app = state.findById(sample.appId) ?: return
        val dialog = AddAppDialog(project, app)
        if (dialog.showAndGet()) {
            state.update(dialog.result())
            refreshNow()
        }
    }

    private fun removeSelected() {
        val ids = selectedSamples().map { it.appId }
        if (ids.isEmpty()) return
        val proceed = Messages.showYesNoDialog(
            project, "Stop monitoring ${ids.size} app(s)?", "Remove App", Messages.getQuestionIcon()
        )
        if (proceed != Messages.YES) return
        ids.forEach { state.remove(it) }
        refreshNow()
    }

    private fun forceKillSelected() {
        val pids = selectedSamples().filter { it.up }.map { it.rootPid }
        if (pids.isEmpty()) return
        val proceed = Messages.showYesNoDialog(
            project, "Force-kill the process tree of ${pids.size} app(s)?", "Force Kill", Messages.getWarningIcon()
        )
        if (proceed != Messages.YES) return
        ApplicationManager.getApplication().executeOnPooledThread {
            pids.forEach { killTree(it) }
            refreshNow()
        }
    }

    private fun killByPort() {
        val input = Messages.showInputDialog(
            project, "TCP port:", "Kill Process on Port", Messages.getQuestionIcon()
        )?.trim() ?: return
        val port = input.toIntOrNull()
        if (port == null || port !in 1..65535) {
            Messages.showErrorDialog(project, "'$input' is not a valid port number.", "Kill Process on Port")
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val pids = ProcessStatsSampler.pidsListeningOnPort(port)
            ApplicationManager.getApplication().invokeLater({
                if (disposed) return@invokeLater
                if (pids.isEmpty()) {
                    Messages.showInfoMessage(project, "No process is listening on port $port.", "Kill Process on Port")
                    return@invokeLater
                }
                val proceed = Messages.showYesNoDialog(
                    project, "Kill ${pids.size} process(es) listening on port $port (and their trees)?",
                    "Kill Process on Port", "Kill", "Cancel", Messages.getWarningIcon()
                )
                if (proceed != Messages.YES) return@invokeLater
                ApplicationManager.getApplication().executeOnPooledThread {
                    pids.forEach { killTree(it) }
                    refreshNow()
                }
            }, ModalityState.any())
        }
    }

    private fun killTree(pid: Long) {
        ProcessHandle.of(pid).ifPresent { handle ->
            handle.descendants().forEach { it.destroyForcibly() }
            handle.destroyForcibly()
        }
    }

    // --- refresh loop ----------------------------------------------------------------------------

    private fun refreshNow() {
        alarm.cancelAllRequests()
        scheduleNext(0)
    }

    private fun scheduleNext(delayMs: Int) {
        if (disposed) return
        alarm.addRequest({ tick() }, delayMs)
    }

    private fun tick() {
        if (disposed) return
        val apps = state.apps()
        val rows = try {
            sampler.sample(apps)
        } catch (t: Throwable) {
            emptyList()
        }
        ApplicationManager.getApplication().invokeLater({
            if (!disposed) applyRows(rows)
        }, ModalityState.any())
        scheduleNext(REFRESH_MS)
    }

    private fun applyRows(rows: List<AppSample>) {
        val selectedIds = selectedSamples().map { it.appId }.toSet()
        model.setRows(rows)
        if (selectedIds.isEmpty()) return
        val selection = table.selectionModel
        selection.clearSelection()
        for (id in selectedIds) {
            val row = model.rowOfApp(id)
            if (row >= 0) selection.addSelectionInterval(row, row)
        }
    }

    private fun selectedSamples(): List<AppSample> =
        table.selectedRows.toList().mapNotNull { row -> model.sampleAt(row) }

    // --- rendering -------------------------------------------------------------------------------

    private fun installSparkline() {
        val column = table.columnModel.getColumn(AppTableModel.Column.MEM_TREND.ordinal)
        column.cellRenderer = SparklineRenderer()
        column.preferredWidth = 90
        column.minWidth = 60
    }

    private fun openChart(sample: AppSample) {
        if (!sample.up) return
        val name = sample.name.ifBlank { sample.targetLabel }
        MemoryChartDialog(project, name, sampler.history(sample.appId)).show()
    }

    private fun installStatusRenderer() {
        val statusColumn = AppTableModel.Column.STATUS.ordinal
        table.columnModel.getColumn(statusColumn).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
            ): Component {
                val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                if (!isSelected) {
                    val healthy = value == "up" || value == "healthy"
                    foreground = if (healthy) JBColor.namedColor("Label.successForeground", JBColor.GREEN)
                    else JBColor.namedColor("Label.errorForeground", JBColor.RED)
                }
                return c
            }
        }
    }

    override fun dispose() {
        disposed = true
        alarm.cancelAllRequests()
    }

    companion object {
        private const val REFRESH_MS = 2000
    }
}
