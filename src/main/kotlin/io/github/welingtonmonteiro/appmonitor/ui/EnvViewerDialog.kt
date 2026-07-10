package io.github.welingtonmonteiro.appmonitor.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.welingtonmonteiro.appmonitor.EnvViewer
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Read-only viewer for an app's `.env` variables (Fase 2). Secret-looking values (password / token /
 * key / …) are masked by default; a **Show values** toggle reveals them. This is a viewer, not an
 * editor — the table is never editable.
 */
class EnvViewerDialog(
    project: Project,
    appName: String,
    private val envFilePath: String,
    private val env: Map<String, String>,
) : DialogWrapper(project) {

    private var currentRows = EnvViewer.rows(env, reveal = false)
    private val tableModel = EnvTableModel()
    private val table = JBTable(tableModel)

    init {
        title = "Environment — $appName"
        init()
        setOKButtonText("Close")
    }

    override fun createCenterPanel(): JComponent {
        table.setShowGrid(false)
        table.rowHeight = table.rowHeight.coerceAtLeast(22)
        table.emptyText.text = "No variables in this .env file."
        table.columnModel.getColumn(1).cellRenderer = monospaceRenderer()

        val toggle = JBCheckBox("Show values").apply {
            addActionListener {
                currentRows = EnvViewer.rows(env, reveal = isSelected)
                tableModel.fireTableDataChanged()
            }
        }
        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(6)
            add(JBLabel("${env.size} variable(s) — $envFilePath"), BorderLayout.WEST)
            add(toggle, BorderLayout.EAST)
        }

        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(560, 360)
            add(header, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER)
        }
    }

    override fun createActions() = arrayOf(okAction)

    private fun monospaceRenderer(): DefaultTableCellRenderer = object : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
            return c
        }
    }

    private inner class EnvTableModel : AbstractTableModel() {
        override fun getRowCount(): Int = currentRows.size
        override fun getColumnCount(): Int = 2
        override fun getColumnName(column: Int): String = if (column == 0) "Variable" else "Value"
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = currentRows[rowIndex]
            return if (columnIndex == 0) row.key else row.displayValue
        }
    }
}
