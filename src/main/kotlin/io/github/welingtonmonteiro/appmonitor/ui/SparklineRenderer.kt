package io.github.welingtonmonteiro.appmonitor.ui

import com.intellij.ui.JBColor
import io.github.welingtonmonteiro.appmonitor.AppSample
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.table.TableCellRenderer

/**
 * Paints a tiny memory-trend chart (the last minute of RSS) in the "Mem trend" column, coloured by
 * direction: reddish when memory is climbing, green when it is flat or falling. Click it to open the
 * full-session chart (wired in the panel).
 */
class SparklineRenderer : JComponent(), TableCellRenderer {

    private var values: List<Long> = emptyList()

    init {
        isOpaque = true
    }

    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
    ): Component {
        values = (value as? AppSample)?.memTrendKb ?: emptyList()
        background = if (isSelected) table.selectionBackground else table.background
        return this
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        g.color = background
        g.fillRect(0, 0, width, height)
        if (values.size < 2) return

        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val padX = 3
            val padY = 3
            val w = width - 2 * padX
            val h = height - 2 * padY
            if (w <= 0 || h <= 0) return

            val min = values.min()
            val max = values.max()
            val span = (max - min).coerceAtLeast(1)
            val n = values.size

            g2.color = trendColor(values)
            var prevX = padX
            var prevY = yFor(values[0], min, span, padY, h)
            for (i in 1 until n) {
                val x = padX + (w * i / (n - 1))
                val y = yFor(values[i], min, span, padY, h)
                g2.drawLine(prevX, prevY, x, y)
                prevX = x
                prevY = y
            }
        } finally {
            g2.dispose()
        }
    }

    private fun yFor(value: Long, min: Long, span: Long, padY: Int, h: Int): Int {
        // higher RSS -> higher on screen (smaller y)
        val ratio = (value - min).toDouble() / span
        return padY + (h - (h * ratio)).toInt()
    }

    private fun trendColor(v: List<Long>): Color {
        val first = v.first().coerceAtLeast(1)
        val last = v.last()
        val growing = last > first * 1.05 // >5% up over the window
        return if (growing) JBColor(Color(0xC0, 0x3A, 0x2B), Color(0xE0, 0x6C, 0x5A))
        else JBColor(Color(0x32, 0x92, 0x05), Color(0x59, 0xA8, 0x69))
    }
}
