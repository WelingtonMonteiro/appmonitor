package io.github.welingtonmonteiro.appmonitor.ui

import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.ScrollPaneFactory
import io.github.welingtonmonteiro.appmonitor.MemoryHistory
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.io.IOException
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The full-session memory chart of one app: RSS over time with labeled axes and the peak marked,
 * a leak-analysis verdict (slope, R², monotonic fraction) and buttons to export the history as CSV
 * or the analysis as a text report. All numbers come from [MemoryHistory] (IDE-free, unit-tested).
 */
class MemoryChartDialog(
    private val project: Project?,
    private val appName: String,
    private val samples: List<MemoryHistory.Sample>,
) : DialogWrapper(project) {

    init {
        title = "Memory - ${appName.ifBlank { "application" }}"
        setOKButtonText("Close")
        init()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8))
        root.preferredSize = Dimension(680, 460)

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        buttons.add(JButton("Export CSV…").apply { addActionListener { exportCsv() } })
        buttons.add(JButton("Export report…").apply { addActionListener { exportReport() } })
        root.add(buttons, BorderLayout.NORTH)

        root.add(ChartPanel(samples), BorderLayout.CENTER)

        val analysis = JBTextArea(MemoryHistory.summaryText(appName, MemoryHistory.analyze(samples))).apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
        }
        val scroll = ScrollPaneFactory.createScrollPane(analysis).apply {
            preferredSize = Dimension(680, 130)
        }
        root.add(scroll, BorderLayout.SOUTH)
        return root
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

    /** Paints RSS (MiB) over elapsed time, with axes, gridlines, tick labels and the peak marked. */
    private class ChartPanel(private val samples: List<MemoryHistory.Sample>) : JComponent() {

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

                if (samples.size < 2) {
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

                val t0 = samples.first().timeMs
                val tSpan = (samples.last().timeMs - t0).coerceAtLeast(1)
                val maxRss = samples.maxOf { it.rssKb }.coerceAtLeast(1)
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

                // the RSS curve
                g2.color = JBColor(0x3573B8, 0x548AF7)
                var prevX = left
                var prevY = yFor(samples[0].rssKb, yMax, top, plotH)
                var peakX = prevX
                var peakY = prevY
                for (i in 1 until samples.size) {
                    val x = left + (plotW.toLong() * (samples[i].timeMs - t0) / tSpan).toInt()
                    val y = yFor(samples[i].rssKb, yMax, top, plotH)
                    g2.drawLine(prevX, prevY, x, y)
                    if (samples[i].rssKb == maxRss) {
                        peakX = x
                        peakY = y
                    }
                    prevX = x
                    prevY = y
                }

                // peak marker
                g2.color = JBColor(0xC0392B, 0xE06C5A)
                g2.fillOval(peakX - 3, peakY - 3, 6, 6)
                g2.drawString("peak ${MemoryHistory.mem(maxRss)}", (peakX + 6).coerceAtMost(width - 90), (peakY - 6).coerceAtLeast(top + 10))
            } finally {
                g2.dispose()
            }
        }

        private fun yFor(rssKb: Long, yMax: Long, top: Int, plotH: Int): Int =
            top + plotH - (plotH.toLong() * rssKb / yMax).toInt()
    }
}
