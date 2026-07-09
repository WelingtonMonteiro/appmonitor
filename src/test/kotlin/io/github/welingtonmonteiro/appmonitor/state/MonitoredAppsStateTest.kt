package io.github.welingtonmonteiro.appmonitor.state

import io.github.welingtonmonteiro.appmonitor.model.MonitoredApp
import io.github.welingtonmonteiro.appmonitor.model.TargetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The add/remove/update/lookup logic of the per-project app list (no running IDE needed). */
class MonitoredAppsStateTest {

    private fun newState() = MonitoredAppsState()

    @Test
    fun addStoresTheAppAndSnapshotIsIndependent() {
        val state = newState()
        state.add(MonitoredApp.ofPort("api", 3003))

        val snapshot = state.apps()
        assertEquals(1, snapshot.size)
        // apps() returns a copy: mutating it must not affect the stored list
        (snapshot as MutableList).clear()
        assertEquals(1, state.apps().size)
    }

    @Test
    fun removeDeletesById() {
        val state = newState()
        val api = MonitoredApp.ofPort("api", 3003)
        state.add(api)
        state.add(MonitoredApp.ofPort("web", 8080))

        state.remove(api.id)

        assertEquals(1, state.apps().size)
        assertEquals("web", state.apps()[0].name)
    }

    @Test
    fun updateReplacesSameIdInPlaceOtherwiseAppends() {
        val state = newState()
        val api = MonitoredApp.ofPort("api", 3003)
        state.add(api)

        val edited = MonitoredApp.ofPort("api-renamed", 3005).apply { id = api.id }
        state.update(edited)
        assertEquals(1, state.apps().size)
        assertEquals("api-renamed", state.apps()[0].name)
        assertEquals(3005, state.apps()[0].port)

        // an unknown id is appended, not lost
        state.update(MonitoredApp.ofPort("brand-new", 9000))
        assertEquals(2, state.apps().size)
    }

    @Test
    fun findByIdAndHasPort() {
        val state = newState()
        val api = MonitoredApp.ofPort("api", 3003)
        state.add(api)

        assertEquals("api", state.findById(api.id)?.name)
        assertNull(state.findById("nope"))
        assertTrue(state.hasPort(3003))
        assertFalse(state.hasPort(9999))
    }

    @Test
    fun hasPortOnlyConsidersPortTargets() {
        val state = newState()
        state.add(MonitoredApp().apply { targetKind = TargetKind.PID; pid = 3003 })

        assertFalse("a PID target of 3003 is not a port 3003", state.hasPort(3003))
    }
}
