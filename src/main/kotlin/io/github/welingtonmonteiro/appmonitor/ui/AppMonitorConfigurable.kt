package io.github.welingtonmonteiro.appmonitor.ui

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import io.github.welingtonmonteiro.appmonitor.AppMonitorSettings
import javax.swing.JComponent

/** The App Monitor settings page (Settings → Tools → App Monitor). */
class AppMonitorConfigurable : Configurable {

    private val intervalField = JBTextField(6)
    private val notificationsCheck = JBCheckBox("Show alert notifications")

    override fun getDisplayName(): String = "App Monitor"

    override fun createComponent(): JComponent {
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(
                "Refresh interval (seconds, ${AppMonitorSettings.MIN_INTERVAL_SECONDS}-${AppMonitorSettings.MAX_INTERVAL_SECONDS}):",
                intervalField
            )
            .addComponent(notificationsCheck)
            .panel
        reset()
        return panel
    }

    override fun isModified(): Boolean {
        val settings = AppMonitorSettings.getInstance()
        return currentSeconds() != settings.refreshIntervalSeconds ||
            notificationsCheck.isSelected != settings.notificationsEnabled
    }

    override fun apply() {
        val settings = AppMonitorSettings.getInstance()
        settings.refreshIntervalSeconds = currentSeconds()
        settings.notificationsEnabled = notificationsCheck.isSelected
        intervalField.text = settings.refreshIntervalSeconds.toString() // reflect any clamp
    }

    override fun reset() {
        val settings = AppMonitorSettings.getInstance()
        intervalField.text = settings.refreshIntervalSeconds.toString()
        notificationsCheck.isSelected = settings.notificationsEnabled
    }

    private fun currentSeconds(): Int = AppMonitorSettings.clampSeconds(
        intervalField.text.trim().toIntOrNull() ?: AppMonitorSettings.DEFAULT_INTERVAL_SECONDS
    )
}
