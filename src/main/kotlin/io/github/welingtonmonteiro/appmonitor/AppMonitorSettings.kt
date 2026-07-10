package io.github.welingtonmonteiro.appmonitor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Application-wide App Monitor settings (edited on the Settings page): how often the monitor
 * refreshes and whether alert notifications are shown. Persisted across projects and restarts.
 */
@Service(Service.Level.APP)
@State(name = "AppMonitorSettings", storages = [Storage("appMonitorSettings.xml")])
class AppMonitorSettings : PersistentStateComponent<AppMonitorSettings.State> {

    class State {
        var refreshIntervalSeconds: Int = DEFAULT_INTERVAL_SECONDS
        var notificationsEnabled: Boolean = true
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        state = loaded
        state.refreshIntervalSeconds = clampSeconds(state.refreshIntervalSeconds)
    }

    var refreshIntervalSeconds: Int
        get() = clampSeconds(state.refreshIntervalSeconds)
        set(value) { state.refreshIntervalSeconds = clampSeconds(value) }

    var notificationsEnabled: Boolean
        get() = state.notificationsEnabled
        set(value) { state.notificationsEnabled = value }

    /** The refresh interval in milliseconds, ready for the refresh loop. */
    fun refreshIntervalMs(): Int = refreshIntervalSeconds * 1000

    companion object {
        const val DEFAULT_INTERVAL_SECONDS = 2
        const val MIN_INTERVAL_SECONDS = 1
        const val MAX_INTERVAL_SECONDS = 60

        /** Keep the interval in a sane range so the monitor never hammers the OS or stalls. */
        fun clampSeconds(value: Int): Int = value.coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS)

        fun getInstance(): AppMonitorSettings = ApplicationManager.getApplication().service()
    }
}
