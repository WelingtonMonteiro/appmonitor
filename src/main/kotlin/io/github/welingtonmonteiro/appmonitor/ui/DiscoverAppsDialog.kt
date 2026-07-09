package io.github.welingtonmonteiro.appmonitor.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import io.github.welingtonmonteiro.appmonitor.Suggestion
import io.github.welingtonmonteiro.appmonitor.model.MonitoredApp
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.table.AbstractTableModel

/**
 * Lists the listening ports found on the machine that are not yet monitored, each with a tick box
 * and an editable name (defaulted from the command), so the user can add several at once.
 */
class DiscoverAppsDialog(project: Project?, private val suggestions: List<Suggestion>) : DialogWrapper(project) {

    private class Row(var add: Boolean, var name: String, val suggestion: Suggestion)

    private val rows = suggestions.map { Row(false, it.defaultName(), it) }

    private val model = object : AbstractTableModel() {
        private val cols = arrayOf("Add", "Name", "Port", "PID", "Command")
        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = cols.size
        override fun getColumnName(column: Int): String = cols[column]
        override fun getColumnClass(column: Int): Class<*> =
            if (column == 0) java.lang.Boolean::class.java else Any::class.java
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == 0 || columnIndex == 1
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = rows[rowIndex]
            return when (columnIndex) {
                0 -> row.add
                1 -> row.name
                2 -> row.suggestion.port
                3 -> row.suggestion.pid
                else -> row.suggestion.command.ifBlank { "—" }
            }
        }
        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            when (columnIndex) {
                0 -> rows[rowIndex].add = value as? Boolean ?: false
                1 -> rows[rowIndex].name = value?.toString().orEmpty()
            }
        }
    }

    init {
        title = "Discover Apps"
        init()
    }

    override fun createCenterPanel(): JComponent {
        if (suggestions.isEmpty()) {
            return JBLabel("No unmonitored listening ports were found.")
        }
        val table = JBTable(model)
        table.preferredScrollableViewportSize = Dimension(580, 280)
        table.columnModel.getColumn(0).apply { maxWidth = 44; preferredWidth = 44 }
        table.columnModel.getColumn(1).preferredWidth = 130
        table.columnModel.getColumn(4).preferredWidth = 260
        return ScrollPaneFactory.createScrollPane(table)
    }

    /** The apps the user ticked, as name + port targets ready to add. */
    fun selectedApps(): List<MonitoredApp> = rows
        .filter { it.add }
        .map { MonitoredApp.ofPort(it.name.ifBlank { it.suggestion.defaultName() }, it.suggestion.port) }
}
