package io.github.welingtonmonteiro.appmonitor.ui

import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.table.JBTable
import io.github.welingtonmonteiro.appmonitor.MemoryHistory
import io.github.welingtonmonteiro.appmonitor.ProcRow
import io.github.welingtonmonteiro.appmonitor.ProcessStatsSampler
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.io.IOException
import java.util.Locale
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel

/**
 * The full-session memory chart of one app: RSS over time with labeled axes and the peak marked,
 * a leak-analysis verdict (slope, R², monotonic fraction) and buttons to export the history as CSV
 * or the analysis as a text report. All numbers come from [MemoryHistory] (IDE-free, unit-tested).
 */
class MemoryChartDialog(
    private val project: Project?,
    private val appName: String,
    private val samples: List<MemoryHistory.Sample>,
    private val breakdown: List<ProcRow> = emptyList(),
    /** The last finished session, drawn faintly for comparison; empty when there is none. */
    private val previousSamples: List<MemoryHistory.Sample> = emptyList(),
) : DialogWrapper(project) {

    init {
        title = "Memory - ${appName.ifBlank { "application" }}"
        setOKButtonText("Close")
        init()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8))
        root.preferredSize = Dimension(700, 500)

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        buttons.add(JButton("Export CSV…").apply { addActionListener { exportCsv() } })
        buttons.add(JButton("Export report…").apply { addActionListener { exportReport() } })
        root.add(buttons, BorderLayout.NORTH)

        val tabs = JBTabbedPane()
        tabs.addTab("Chart", chartTab())
        tabs.addTab("Processes (${breakdown.size})", ScrollPaneFactory.createScrollPane(JBTable(ProcTableModel(breakdown))))
        root.add(tabs, BorderLayout.CENTER)
        return root
    }

    private fun chartTab(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        panel.add(ChartPanel(samples, previousSamples), BorderLayout.CENTER)
        val text = MemoryHistory.summaryText(appName, MemoryHistory.analyze(samples)) + comparisonText()
        val analysis = JBTextArea(text).apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
        }
        panel.add(ScrollPaneFactory.createScrollPane(analysis).apply { preferredSize = Dimension(700, 150) }, BorderLayout.SOUTH)
        return panel
    }

    /** A short comparison of this session's peak against the previous session (empty when none). */
    private fun comparisonText(): String {
        if (previousSamples.isEmpty()) return ""
        val cur = MemoryHistory.analyze(samples)
        val prev = MemoryHistory.analyze(previousSamples)
        val delta = cur.maxRssKb - prev.maxRssKb
        val sign = if (delta >= 0) "+" else "-"
        return buildString {
            append("\nPrevious session (dashed): peak ").append(MemoryHistory.mem(prev.maxRssKb))
                .append(", ").append(MemoryHistory.verdictText(prev)).append('\n')
            append("This session peak ").append(MemoryHistory.mem(cur.maxRssKb))
                .append(" (").append(sign).append(MemoryHistory.mem(Math.abs(delta))).append(" vs previous)\n")
        }
    }

    /** The per-process breakdown of the app's tree (PID, command, memory and share of the tree). */
    private class ProcTableModel(private val rows: List<ProcRow>) : AbstractTableModel() {
        private val cols = arrayOf("PID", "Command", "Memory", "% of tree")
        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = cols.size
        override fun getColumnName(column: Int): String = cols[column]
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = rows[rowIndex]
            return when (columnIndex) {
                0 -> row.pid
                1 -> row.command.ifBlank { "—" }
                2 -> ProcessStatsSampler.formatMemory(row.rssKb)
                else -> String.format(Locale.US, "%.1f%%", row.pctOfTree)
            }
        }
    }

    private fun exportCsv() = export("csv", "memory-$appName.csv", MemoryHistory.toCsv(samples))

    private fun exportReport() =
        export("txt", "memory-$appName.txt", MemoryHistory.summaryText(appName, MemoryHistory.analyze(samples)))

    private fun export(ext: String, defaultName: String, content: String) {
        val descriptor = FileSaverDescriptor("Export Memory History", "Choose where to save", ext)
        val wrapper = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(null as VirtualFile?, defaultName) ?: return
        try {
            wrapper.file.writeText(content)
        } catch (e: IOException) {
            Messages.showErrorDialog(project, "Could not save the file: ${e.message}", "Export Failed")
        }
    }

    /**
     * Paints RSS (MiB) over elapsed time with axes, gridlines, tick labels and the peak marked. When
     * a [previous] session is given it is drawn faint and dashed under the current one, on a shared
     * scale (the larger time span and peak of the two), so the two sessions are comparable.
     */
    private class ChartPanel(
        private val samples: List<MemoryHistory.Sample>,
        private val previous: List<MemoryHistory.Sample> = emptyList(),
    ) : JComponent() {

        init {
            preferredSize = Dimension(680, 260)
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = JBColor.background()
                g2.fillRect(0, 0, width, height)

                if (samples.size < 2 && previous.size < 2) {
                    g2.color = JBColor.GRAY
                    g2.drawString("Not enough data yet - keep the app running.", 16, height / 2)
                    return
                }

                val left = 62
                val right = 16
                val top = 14
                val bottom = 28
                val plotW = width - left - right
                val plotH = height - top - bottom
                if (plotW <= 0 || plotH <= 0) return

                val tSpan = maxOf(span(samples), span(previous), 1L)
                val maxRss = maxOf(peak(samples), peak(previous), 1L)
                val yMax = (maxRss * 1.1).toLong().coerceAtLeast(1)

                val grid = JBColor(0xE0E0E0, 0x3C3F41)
                val axis = JBColor.foreground()

                // Y gridlines + MiB labels
                val yDiv = 4
                for (k in 0..yDiv) {
                    val yVal = yMax * k / yDiv
                    val y = top + plotH - plotH * k / yDiv
                    g2.color = grid
                    g2.drawLine(left, y, left + plotW, y)
                    g2.color = axis
                    g2.drawString(MemoryHistory.mem(yVal), 6, y + 4)
                }

                // X ticks + elapsed-time labels
                val xDiv = 4
                for (k in 0..xDiv) {
                    val tVal = tSpan * k / xDiv
                    val x = left + plotW * k / xDiv
                    g2.color = grid
                    g2.drawLine(x, top, x, top + plotH)
                    g2.color = axis
                    g2.drawString(MemoryHistory.durationText(tVal), x - 12, top + plotH + 18)
                }

                // axes
                g2.color = axis
                g2.drawLine(left, top, left, top + plotH)
                g2.drawLine(left, top + plotH, left + plotW, top + plotH)

                // previous session: faint dashed line under the current one
                if (previous.size >= 2) {
                    val stroke = g2.stroke
                    g2.color = JBColor(0x9AA7B0, 0x6B7178)
                    g2.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, floatArrayOf(4f, 4f), 0f)
                    drawSeries(g2, previous, tSpan, yMax, left, top, plotW, plotH)
                    g2.stroke = stroke
                }

                // current session: solid line + peak marker
                if (samples.size >= 2) {
                    g2.color = JBColor(0x3573B8, 0x548AF7)
                    val (peakX, peakY) = drawSeries(g2, samples, tSpan, yMax, left, top, plotW, plotH)
                    g2.color = JBColor(0xC0392B, 0xE06C5A)
                    g2.fillOval(peakX - 3, peakY - 3, 6, 6)
                    g2.drawString("peak ${MemoryHistory.mem(peak(samples))}",
                        (peakX + 6).coerceAtMost(width - 90), (peakY - 6).coerceAtLeast(top + 10))
                }

                // legend when comparing
                if (previous.size >= 2) {
                    g2.color = JBColor.foreground()
                    g2.drawString("— this   ---- previous", left + plotW - 150, top + 12)
                }
            } finally {
                g2.dispose()
            }
        }

        /** Draw a series' polyline on the shared scale; returns the pixel of its peak sample. */
        private fun drawSeries(
            g2: Graphics2D, series: List<MemoryHistory.Sample>, tSpan: Long, yMax: Long,
            left: Int, top: Int, plotW: Int, plotH: Int,
        ): Pair<Int, Int> {
            val t0 = series.first().timeMs
            val maxRss = peak(series)
            var prevX = left
            var prevY = yFor(series[0].rssKb, yMax, top, plotH)
            var peakX = prevX
            var peakY = prevY
            for (i in 1 until series.size) {
                val x = left + (plotW.toLong() * (series[i].timeMs - t0) / tSpan).toInt()
                val y = yFor(series[i].rssKb, yMax, top, plotH)
                g2.drawLine(prevX, prevY, x, y)
                if (series[i].rssKb == maxRss) {
                    peakX = x
                    peakY = y
                }
                prevX = x
                prevY = y
            }
            return peakX to peakY
        }

        private fun span(s: List<MemoryHistory.Sample>): Long =
            if (s.size >= 2) s.last().timeMs - s.first().timeMs else 0L

        private fun peak(s: List<MemoryHistory.Sample>): Long = s.maxOfOrNull { it.rssKb } ?: 0L

        private fun yFor(rssKb: Long, yMax: Long, top: Int, plotH: Int): Int =
            top + plotH - (plotH.toLong() * rssKb / yMax).toInt()
    }
}
