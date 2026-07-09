package io.github.welingtonmonteiro.appmonitor.state

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.XCollection
import io.github.welingtonmonteiro.appmonitor.model.MonitoredApp

/**
 * The per-project list of apps to watch, persisted to {@code .idea/appMonitor.xml} so it survives
 * IDE restarts. A project-level service (as opposed to Multiple Run, which had no persisted list at
 * all - it only ever showed what the IDE had launched).
 */
@Service(Service.Level.PROJECT)
@State(name = "AppMonitor", storages = [Storage("appMonitor.xml")])
class MonitoredAppsState : PersistentStateComponent<MonitoredAppsState.State> {

    /** The serialized shape: the list of app definitions plus panel preferences. */
    class State {
        @XCollection(propertyElementName = "apps", style = XCollection.Style.v2)
        var apps: MutableList<MonitoredApp> = mutableListOf()

        /** Column names (see AppTableModel.Column) the user chose to hide - persisted per project. */
        @XCollection(propertyElementName = "hiddenColumns", style = XCollection.Style.v2)
        var hiddenColumns: MutableList<String> = mutableListOf()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        state = loaded
    }

    /** A snapshot copy of the current apps (safe to iterate off any thread). */
    fun apps(): List<MonitoredApp> = ArrayList(state.apps)

    fun add(app: MonitoredApp) {
        state.apps.add(app)
    }

    fun remove(id: String) {
        state.apps.removeIf { it.id == id }
    }

    /** Replace the stored app that has the same id (used by the edit dialog). */
    fun update(app: MonitoredApp) {
        val index = state.apps.indexOfFirst { it.id == app.id }
        if (index >= 0) state.apps[index] = app else state.apps.add(app)
    }

    fun findById(id: String): MonitoredApp? = state.apps.firstOrNull { it.id == id }

    /** True when an app already watches this exact TCP port (used to warn on duplicates). */
    fun hasPort(port: Int): Boolean = state.apps.any {
        it.targetKind == io.github.welingtonmonteiro.appmonitor.model.TargetKind.PORT && it.port == port
    }

    fun hiddenColumns(): Set<String> = state.hiddenColumns.toSet()

    fun setColumnHidden(columnName: String, hidden: Boolean) {
        if (hidden) {
            if (!state.hiddenColumns.contains(columnName)) state.hiddenColumns.add(columnName)
        } else {
            state.hiddenColumns.remove(columnName)
        }
    }

    companion object {
        fun getInstance(project: Project): MonitoredAppsState = project.service()
    }
}
