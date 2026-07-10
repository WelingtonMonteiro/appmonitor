package io.github.welingtonmonteiro.appmonitor.ui

import io.github.welingtonmonteiro.appmonitor.AppSample
import io.github.welingtonmonteiro.appmonitor.Health
import io.github.welingtonmonteiro.appmonitor.ProcessStatsSampler
import java.util.Locale
import javax.swing.table.AbstractTableModel

/**
 * The docker-stats-like table behind the App Monitor tool window. Holds the latest [AppSample] per
 * row and formats each column as display text; the raw samples stay available (via [sampleAt]) so
 * actions can read the resolved PID / up state of the selected row.
 */
class AppTableModel : AbstractTableModel() {

    enum class Column(val title: String) {
        NAME("Name"),
        TAG("Tag"),
        PORTS("Port(s)"),
        PID("PID"),
        UPTIME("Uptime"),
        STATUS("Status"),
        MEM("Mem"),
        MEM_PCT("Mem %"),
        CPU_PCT("CPU %"),
        MEM_TREND("Mem trend"),
    }

    private val columns = Column.entries.toTypedArray()
    private var rows: List<AppSample> = emptyList()

    fun setRows(newRows: List<AppSample>) {
        rows = newRows
        fireTableDataChanged()
    }

    fun sampleAt(row: Int): AppSample? = rows.getOrNull(row)

    /** Row index of the app with this id, or -1 (used to restore selection across refreshes). */
    fun rowOfApp(appId: String): Int = rows.indexOfFirst { it.appId == appId }

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns[column].title

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val s = rows[rowIndex]
        return when (columns[columnIndex]) {
            Column.NAME -> s.name.ifBlank { s.targetLabel }
            Column.TAG -> s.tag
            Column.PORTS -> when {
                s.ports.isNotEmpty() -> s.ports.joinToString(", ")
                s.up -> "—"
                else -> ""
            }
            Column.PID -> if (s.up && s.rootPid > 0) s.rootPid.toString() else "—"
            Column.UPTIME -> if (s.up) ProcessStatsSampler.formatUptime(s.uptimeMs) else "—"
            Column.STATUS -> when {
                !s.up -> "down"
                s.health == Health.HEALTHY -> "healthy"
                s.health == Health.UNHEALTHY -> "unhealthy"
                else -> "up"
            }
            Column.MEM -> if (s.up) ProcessStatsSampler.formatMemory(s.rssKb) else "—"
            Column.MEM_PCT -> if (s.memPercent < 0) "—" else String.format(Locale.US, "%.1f%%", s.memPercent)
            Column.CPU_PCT -> if (s.cpuPercent < 0) "—" else String.format(Locale.US, "%.2f%%", s.cpuPercent)
            // the sparkline renderer reads the trend off the sample itself
            Column.MEM_TREND -> s
        }
    }
}
