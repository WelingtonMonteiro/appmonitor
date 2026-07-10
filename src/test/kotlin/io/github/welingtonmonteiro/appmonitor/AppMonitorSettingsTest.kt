package io.github.welingtonmonteiro.appmonitor

import org.junit.Assert.assertEquals
import org.junit.Test

/** The refresh-interval clamp keeps the value sane regardless of what was persisted. */
class AppMonitorSettingsTest {

    @Test
    fun clampKeepsIntervalInRange() {
        assertEquals(2, AppMonitorSettings.clampSeconds(2))
        assertEquals(AppMonitorSettings.MIN_INTERVAL_SECONDS, AppMonitorSettings.clampSeconds(0))
        assertEquals(AppMonitorSettings.MIN_INTERVAL_SECONDS, AppMonitorSettings.clampSeconds(-5))
        assertEquals(AppMonitorSettings.MAX_INTERVAL_SECONDS, AppMonitorSettings.clampSeconds(9999))
    }
}
