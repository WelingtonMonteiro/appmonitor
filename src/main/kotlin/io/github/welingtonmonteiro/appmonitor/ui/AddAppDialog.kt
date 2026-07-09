package io.github.welingtonmonteiro.appmonitor.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ColorPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import io.github.welingtonmonteiro.appmonitor.model.MonitoredApp
import io.github.welingtonmonteiro.appmonitor.model.TargetKind
import java.awt.Color
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import javax.swing.JComponent

/**
 * "Add App" / "Edit App" dialog. Name + a target (port, process-name regex or PID) is the minimum;
 * the rest (health URL, memory alert, tag) is optional and already maps onto [MonitoredApp] fields
 * that later phases will use.
 */
class AddAppDialog(project: Project?, private val existing: MonitoredApp? = null) : DialogWrapper(project) {

    private val nameField = JBTextField(24)
    private val kindCombo = ComboBox(arrayOf("Port", "Process name", "PID"))
    private val valueLabel = JBLabel("Port:")
    private val valueField = JBTextField(24)
    private val healthField = JBTextField(24)
    private val memAlertField = JBTextField(8)
    private val tagField = JBTextField(16)
    private val colorPanel = ColorPanel()
    private val startCmdField = JBTextField(24)
    private val stopCmdField = JBTextField(24)
    private val workingDirField = JBTextField(24)
    private val envFileField = JBTextField(24)

    init {
        title = if (existing == null) "Add App" else "Edit App"
        kindCombo.addActionListener { valueLabel.text = valueLabelFor(kindCombo.selectedIndex) }
        prefill()
        init()
    }

    private fun prefill() {
        val app = existing ?: return
        nameField.text = app.name
        kindCombo.selectedIndex = when (app.targetKind) {
            TargetKind.PORT -> 0
            TargetKind.PROCESS_NAME -> 1
            TargetKind.PID -> 2
        }
        valueLabel.text = valueLabelFor(kindCombo.selectedIndex)
        valueField.text = when (app.targetKind) {
            TargetKind.PORT -> if (app.port > 0) app.port.toString() else ""
            TargetKind.PROCESS_NAME -> app.processNameRegex
            TargetKind.PID -> if (app.pid > 0) app.pid.toString() else ""
        }
        healthField.text = app.healthUrl
        memAlertField.text = if (app.memAlertMb > 0) app.memAlertMb.toString() else ""
        tagField.text = app.tag
        if (app.colorRgb != 0) colorPanel.selectedColor = Color(app.colorRgb)
        startCmdField.text = app.startCmd
        stopCmdField.text = app.stopCmd
        workingDirField.text = app.workingDir
        envFileField.text = app.envFile
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Name:", nameField)
        .addLabeledComponent("Target:", kindCombo)
        .addLabeledComponent(valueLabel, valueField)
        .addSeparator()
        .addLabeledComponent("Health URL (optional):", healthField)
        .addLabeledComponent("Memory alert MB (optional):", memAlertField)
        .addLabeledComponent("Tag (optional):", tagField)
        .addLabeledComponent("Tag color (optional):", colorPanel)
        .addSeparator()
        .addLabeledComponent("Start command (optional):", startCmdField)
        .addLabeledComponent("Stop command (optional):", stopCmdField)
        .addLabeledComponent("Working dir (optional):", workingDirField)
        .addLabeledComponent("Env file (optional):", envFileField)
        .panel

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): ValidationInfo? {
        if (nameField.text.isBlank()) {
            return ValidationInfo("Give the app a name.", nameField)
        }
        val value = valueField.text.trim()
        when (kindCombo.selectedIndex) {
            0 -> {
                val port = value.toIntOrNull()
                if (port == null || port !in 1..65535) {
                    return ValidationInfo("Enter a port between 1 and 65535.", valueField)
                }
            }
            2 -> {
                val pid = value.toLongOrNull()
                if (pid == null || pid <= 0) {
                    return ValidationInfo("Enter a positive PID.", valueField)
                }
            }
            else -> {
                if (value.isBlank()) {
                    return ValidationInfo("Enter a process-name pattern (regex).", valueField)
                }
                try {
                    Pattern.compile(value)
                } catch (e: PatternSyntaxException) {
                    return ValidationInfo("Invalid regex: ${e.description}", valueField)
                }
            }
        }
        val memText = memAlertField.text.trim()
        if (memText.isNotEmpty() && (memText.toIntOrNull() == null || memText.toInt() < 0)) {
            return ValidationInfo("Memory alert must be a non-negative number of MB.", memAlertField)
        }
        return null
    }

    /** The app defined by the dialog (reusing the existing id when editing). */
    fun result(): MonitoredApp {
        val app = MonitoredApp()
        if (existing != null) app.id = existing.id
        app.name = nameField.text.trim()
        val value = valueField.text.trim()
        when (kindCombo.selectedIndex) {
            0 -> {
                app.targetKind = TargetKind.PORT
                app.port = value.toInt()
            }
            2 -> {
                app.targetKind = TargetKind.PID
                app.pid = value.toLong()
            }
            else -> {
                app.targetKind = TargetKind.PROCESS_NAME
                app.processNameRegex = value
            }
        }
        app.healthUrl = healthField.text.trim()
        app.memAlertMb = memAlertField.text.trim().toIntOrNull() ?: 0
        app.tag = tagField.text.trim()
        app.colorRgb = colorPanel.selectedColor?.let { it.rgb and 0xFFFFFF } ?: 0
        app.startCmd = startCmdField.text.trim()
        app.stopCmd = stopCmdField.text.trim()
        app.workingDir = workingDirField.text.trim()
        app.envFile = envFileField.text.trim()
        return app
    }

    private fun valueLabelFor(index: Int): String = when (index) {
        0 -> "Port:"
        2 -> "PID:"
        else -> "Name regex:"
    }
}
