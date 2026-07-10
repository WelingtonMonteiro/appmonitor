package io.github.welingtonmonteiro.appmonitor.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.Alarm
import io.github.welingtonmonteiro.appmonitor.ActionKind
import io.github.welingtonmonteiro.appmonitor.ActionRules
import io.github.welingtonmonteiro.appmonitor.Alert
import io.github.welingtonmonteiro.appmonitor.AlertArm
import io.github.welingtonmonteiro.appmonitor.AlertKind
import io.github.welingtonmonteiro.appmonitor.AppRowFilter
import io.github.welingtonmonteiro.appmonitor.AlertNotifier
import io.github.welingtonmonteiro.appmonitor.AlertPolicy
import io.github.welingtonmonteiro.appmonitor.AppCommandRunner
import io.github.welingtonmonteiro.appmonitor.AppEvent
import io.github.welingtonmonteiro.appmonitor.AppEventKind
import io.github.welingtonmonteiro.appmonitor.AppEvents
import io.github.welingtonmonteiro.appmonitor.RuleFire
import io.github.welingtonmonteiro.appmonitor.RuleState
import io.github.welingtonmonteiro.appmonitor.AppMonitorSampler
import io.github.welingtonmonteiro.appmonitor.AppMonitorSettings
import io.github.welingtonmonteiro.appmonitor.AppMonitorStatusService
import io.github.welingtonmonteiro.appmonitor.AppSample
import io.github.welingtonmonteiro.appmonitor.DiscoveryScanner
import io.github.welingtonmonteiro.appmonitor.EnvFile
import io.github.welingtonmonteiro.appmonitor.EnvViewer
import io.github.welingtonmonteiro.appmonitor.HistoryStore
import io.github.welingtonmonteiro.appmonitor.MemoryHistory
import io.github.welingtonmonteiro.appmonitor.ProcessStatsSampler
import io.github.welingtonmonteiro.appmonitor.model.MonitoredApp
import io.github.welingtonmonteiro.appmonitor.model.TargetKind
import io.github.welingtonmonteiro.appmonitor.state.MonitoredAppsState
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.nio.file.Paths
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.Dimension
import javax.swing.JCheckBoxMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableColumn

/**
 * The App Monitor tool-window UI: a docker-stats-like table over the watched apps, refreshed every
 * two seconds on a background thread. The list of apps is persisted (project-level), so it survives
 * restarts; each refresh re-resolves every target, so an app that came back is shown again.
 */
class AppMonitorPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val state = MonitoredAppsState.getInstance(project)
    // persist per-app memory sessions under the IDE system dir, scoped by project (survives restarts)
    private val sampler = AppMonitorSampler(
        HistoryStore(Paths.get(PathManager.getSystemPath(), "appMonitor", "history", project.locationHash))
    )
    private val model = AppTableModel()
    private val table = JBTable(model)
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    /** appId -> armed alert flags, so each condition notifies once until it recovers. */
    private val alertArms = HashMap<String, AlertArm>()

    /** appId -> action-rule state (edge-triggering + sustained-memory timing). */
    private val ruleStates = HashMap<String, RuleState>()

    /** The full (unfiltered) rows from the last refresh, and the current toolbar filter query. */
    private var lastRows: List<AppSample> = emptyList()
    private var filterQuery: String = ""

    /** appId -> the previous refresh's sample, to detect up/down/restart/health transitions for events. */
    private var prevSampleById: Map<String, AppSample> = emptyMap()

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
        applyColumnVisibility()
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val row = table.rowAtPoint(e.point)
                if (row < 0) return
                val viewCol = table.columnAtPoint(e.point)
                val modelCol = if (viewCol >= 0) table.convertColumnIndexToModel(viewCol) else -1
                if (modelCol == AppTableModel.Column.MEM_TREND.ordinal) {
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
            add(object : DumbAwareAction("Discover Apps", "Scan listening ports and add unmonitored ones", AllIcons.Actions.Find) {
                override fun actionPerformed(e: AnActionEvent) = discoverApps()
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
            add(object : DumbAwareAction("Env Viewer", "Show the .env variables of the selected app", AllIcons.Actions.Properties) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = selectedApp()?.envFile?.isNotBlank() == true
                }
                override fun actionPerformed(e: AnActionEvent) = showEnv()
            })
            addSeparator()
            add(object : DumbAwareAction("Refresh", "Refresh now", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = refreshNow()
            })
            add(object : DumbAwareAction("Kill Process on Port", "Kill whatever process listens on a TCP port", AllIcons.Actions.Cancel) {
                override fun actionPerformed(e: AnActionEvent) = killByPort()
            })
            add(object : DumbAwareAction("Force Kill", "Force-kill the process tree of the selected running app(s)", AllIcons.Debugger.KillProcess) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = selectedSamples().any { it.up }
                }
                override fun actionPerformed(e: AnActionEvent) = forceKillSelected()
            })
            addSeparator()
            add(object : DumbAwareAction("Start", "Run the selected app's start command", AllIcons.Actions.Execute) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = selectedApp()?.startCmd?.isNotBlank() == true
                }
                override fun actionPerformed(e: AnActionEvent) = startSelected()
            })
            add(object : DumbAwareAction("Stop", "Run the selected app's stop command (or kill its tree)", AllIcons.Actions.Suspend) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    val app = selectedApp()
                    e.presentation.isEnabled = app != null &&
                        (app.stopCmd.isNotBlank() || selectedSamples().singleOrNull()?.up == true)
                }
                override fun actionPerformed(e: AnActionEvent) = stopSelected()
            })
            add(object : DumbAwareAction("Restart", "Stop then start the selected app (needs a start command)", AllIcons.Actions.Restart) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = selectedApp()?.startCmd?.isNotBlank() == true
                }
                override fun actionPerformed(e: AnActionEvent) = restartSelected()
            })
            addSeparator()
            add(object : DumbAwareAction("Show/Hide Columns", "Choose which columns are visible (persisted)", AllIcons.General.Settings) {
                override fun actionPerformed(e: AnActionEvent) = showColumnChooser(e)
            })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("AppMonitor", group, true)
        toolbar.targetComponent = table

        val search = SearchTextField().apply {
            textEditor.emptyText.text = "Filter by name, tag or port"
            preferredSize = Dimension(220, preferredSize.height)
            addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = onFilterChanged(text)
            })
        }
        val bar = JPanel(BorderLayout())
        bar.add(toolbar.component, BorderLayout.WEST)
        bar.add(search, BorderLayout.EAST)
        return bar
    }

    private fun onFilterChanged(text: String) {
        filterQuery = text
        val selectedIds = selectedSamples().map { it.appId }.toSet()
        model.setRows(AppRowFilter.filter(lastRows, filterQuery))
        restoreSelection(selectedIds)
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

    private fun discoverApps() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val monitored = state.apps()
                .filter { it.targetKind == TargetKind.PORT }
                .map { it.port }
                .toSet()
            val suggestions = DiscoveryScanner.discover(monitored)
            ApplicationManager.getApplication().invokeLater({
                if (disposed) return@invokeLater
                val dialog = DiscoverAppsDialog(project, suggestions)
                if (dialog.showAndGet()) {
                    dialog.selectedApps().forEach { state.add(it) }
                    refreshNow()
                }
            }, ModalityState.any())
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
        ids.forEach { state.remove(it); sampler.forget(it) }
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

    private fun selectedApp(): MonitoredApp? = selectedSamples().singleOrNull()?.let { state.findById(it.appId) }

    private fun startSelected() {
        val app = selectedApp() ?: return
        if (app.startCmd.isBlank()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            AppCommandRunner.start(app, project.basePath)
            refreshNow()
        }
    }

    private fun stopSelected() {
        val sample = selectedSamples().singleOrNull() ?: return
        val app = state.findById(sample.appId) ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            // stop command if configured; otherwise fall back to killing the running tree
            if (!AppCommandRunner.stop(app, project.basePath) && sample.up) killTree(sample.rootPid)
            refreshNow()
        }
    }

    private fun restartSelected() {
        val sample = selectedSamples().singleOrNull() ?: return
        val app = state.findById(sample.appId) ?: return
        if (app.startCmd.isBlank()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            if (!AppCommandRunner.stop(app, project.basePath) && sample.up) killTree(sample.rootPid)
            Thread.sleep(RESTART_STOP_WAIT_MS)
            AppCommandRunner.start(app, project.basePath)
            refreshNow()
        }
    }

    /** Open the read-only viewer of the selected app's `.env` (read off the EDT). */
    private fun showEnv() {
        val app = selectedApp() ?: return
        val path = EnvViewer.resolvePath(app.envFile, project.basePath)
        if (path == null) {
            Messages.showInfoMessage(
                project, "This app has no .env file configured. Set one in Edit App.", "Env Viewer"
            )
            return
        }
        val name = app.name.ifBlank { app.targetLabel() }
        ApplicationManager.getApplication().executeOnPooledThread {
            val file = java.io.File(path)
            val env = if (file.isFile) runCatching { EnvFile.parse(file.readText()) }.getOrNull() else null
            ApplicationManager.getApplication().invokeLater({
                if (disposed) return@invokeLater
                if (env == null) {
                    Messages.showErrorDialog(project, "Env file not found or unreadable:\n$path", "Env Viewer")
                    return@invokeLater
                }
                EnvViewerDialog(project, name, path, env).show()
            }, ModalityState.any())
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
        scheduleNext(AppMonitorSettings.getInstance().refreshIntervalMs())
    }

    private fun applyRows(rows: List<AppSample>) {
        lastRows = rows
        val selectedIds = selectedSamples().map { it.appId }.toSet()
        model.setRows(AppRowFilter.filter(rows, filterQuery))
        restoreSelection(selectedIds)
        evaluateAlertsAndEvents(rows) // alerts, events and the status widget consider every app, not just the filtered ones
        updateStatusWidget(rows)
    }

    private fun restoreSelection(selectedIds: Set<String>) {
        if (selectedIds.isEmpty()) return
        val selection = table.selectionModel
        selection.clearSelection()
        for (id in selectedIds) {
            val row = model.rowOfApp(id)
            if (row >= 0) selection.addSelectionInterval(row, row)
        }
    }

    /** Push the up/down/total-memory summary to the status-bar widget. */
    private fun updateStatusWidget(rows: List<AppSample>) {
        val up = rows.count { it.up }
        val totalRss = rows.filter { it.up && it.rssKb >= 0 }.sumOf { it.rssKb }
        AppMonitorStatusService.getInstance(project).summary =
            AppMonitorStatusService.Summary(up, rows.size - up, totalRss)
        WindowManager.getInstance().getStatusBar(project)?.updateWidget(AppMonitorStatusBarWidget.ID)
    }

    /**
     * Records each app's events (up/down/restart/health transitions + fired alerts) for the dashboard
     * and raises a balloon for each just-true down/memory/CPU/leak condition. Events are logged even
     * when notifications are off; only the balloons are gated by the setting.
     */
    private fun evaluateAlertsAndEvents(rows: List<AppSample>) {
        val notify = AppMonitorSettings.getInstance().notificationsEnabled
        val appsById = state.apps().associateBy { it.id }
        val now = System.currentTimeMillis()
        for (sample in rows) {
            val events = ArrayList(AppEvents.lifecycle(prevSampleById[sample.appId], sample, now))
            val app = appsById[sample.appId]
            if (app != null) {
                val leaking = MemoryHistory.analyze(sampler.history(sample.appId)).isSteadyLeak()
                val (alerts, arm) = AlertPolicy.evaluate(app, sample, leaking, alertArms[sample.appId] ?: AlertArm())
                alertArms[sample.appId] = arm
                if (notify) alerts.forEach { AlertNotifier.notify(project, it) }
                events.addAll(AppEvents.fromAlerts(alerts, now))

                // automated action rules (restart-on-down / sustained-memory action)
                val (fires, ruleState) = ActionRules.evaluate(app, sample, now, ruleStates[sample.appId] ?: RuleState())
                ruleStates[sample.appId] = ruleState
                for (fire in fires) {
                    events.add(AppEvent(now, AppEventKind.ACTION, fire.reason))
                    executeRule(app, sample, fire)
                }
            }
            sampler.recordEvents(sample.appId, events)
        }
        alertArms.keys.retainAll(rows.mapTo(HashSet()) { it.appId })
        ruleStates.keys.retainAll(rows.mapTo(HashSet()) { it.appId })
        prevSampleById = rows.associateBy { it.appId }
    }

    /** Run one fired action rule: notify, kill the tree, or restart via the app's commands (off-EDT). */
    private fun executeRule(app: MonitoredApp, sample: AppSample, fire: RuleFire) {
        when (fire.kind) {
            ActionKind.NOTIFY ->
                AlertNotifier.notify(project, Alert(AlertKind.MEMORY, sample.name.ifBlank { app.targetLabel() }, fire.reason))
            ActionKind.KILL -> if (sample.up) ApplicationManager.getApplication().executeOnPooledThread {
                killTree(sample.rootPid)
                refreshNow()
            }
            ActionKind.RESTART -> ApplicationManager.getApplication().executeOnPooledThread {
                if (!AppCommandRunner.stop(app, project.basePath) && sample.up) killTree(sample.rootPid)
                Thread.sleep(RESTART_STOP_WAIT_MS)
                AppCommandRunner.start(app, project.basePath)
                refreshNow()
            }
        }
    }

    private fun selectedSamples(): List<AppSample> =
        table.selectedRows.toList().mapNotNull { row -> model.sampleAt(row) }

    // --- rendering -------------------------------------------------------------------------------

    private fun openChart(sample: AppSample) {
        if (!sample.up) return
        val name = sample.name.ifBlank { sample.targetLabel }
        MemoryChartDialog(
            project, name, sampler.history(sample.appId), sampler.breakdown(sample.appId),
            sampler.previousHistory(sample.appId), sampler.events(sample.appId)
        ).show()
    }

    /** Rebuild the table's columns from the model, skipping the ones the user hid (Name always stays). */
    private fun applyColumnVisibility() {
        val hidden = state.hiddenColumns()
        val columns = table.columnModel
        while (columns.columnCount > 0) columns.removeColumn(columns.getColumn(0))
        for (col in AppTableModel.Column.entries) {
            if (col != AppTableModel.Column.NAME && col.name in hidden) continue
            val tableColumn = TableColumn(col.ordinal).apply { headerValue = col.title }
            when (col) {
                AppTableModel.Column.STATUS -> tableColumn.cellRenderer = newStatusRenderer()
                AppTableModel.Column.TAG -> tableColumn.cellRenderer = newTagRenderer()
                AppTableModel.Column.MEM_TREND -> {
                    tableColumn.cellRenderer = SparklineRenderer()
                    tableColumn.preferredWidth = 90
                    tableColumn.minWidth = 60
                }
                else -> {}
            }
            columns.addColumn(tableColumn)
        }
    }

    private fun showColumnChooser(e: AnActionEvent) {
        val menu = JPopupMenu()
        val hidden = state.hiddenColumns()
        for (col in AppTableModel.Column.entries) {
            val item = JCheckBoxMenuItem(col.title, col.name !in hidden)
            if (col == AppTableModel.Column.NAME) item.isEnabled = false // the anchor column stays visible
            item.addActionListener {
                state.setColumnHidden(col.name, !item.isSelected)
                applyColumnVisibility()
            }
            menu.add(item)
        }
        val source = e.inputEvent?.component
        if (source != null) menu.show(source, 0, source.height) else menu.show(table, 0, 0)
    }

    private fun newTagRenderer(): DefaultTableCellRenderer = object : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val rgb = model.sampleAt(table.convertRowIndexToModel(row))?.colorRgb ?: 0
            if (!isSelected && rgb != 0) foreground = JBColor(Color(rgb), Color(rgb))
            return c
        }
    }

    private fun newStatusRenderer(): DefaultTableCellRenderer = object : DefaultTableCellRenderer() {
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

    override fun dispose() {
        disposed = true
        alarm.cancelAllRequests()
        sampler.flush() // don't lose the tail of each session on close/restart
    }

    companion object {
        /** Pause between stop and start on a Restart, so the port is freed before the app relaunches. */
        private const val RESTART_STOP_WAIT_MS = 800L
    }
}
