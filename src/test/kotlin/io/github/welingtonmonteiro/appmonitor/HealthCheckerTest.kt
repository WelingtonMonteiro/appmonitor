package io.github.welingtonmonteiro.appmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Non-network cases of the HTTP health probe (blank url = not configured, bad url = unhealthy). */
class HealthCheckerTest {

    @Test
    fun blankUrlMeansNoHealthCheck() {
        assertNull(HealthChecker.check(""))
        assertNull(HealthChecker.check("   "))
        assertEquals(Health.NONE, HealthChecker.healthOf(""))
    }

    @Test
    fun malformedUrlIsUnhealthyNotAnError() {
        // spaces make URI construction throw before any connection is attempted (no network)
        assertFalse(HealthChecker.check("not a valid url")!!)
        assertEquals(Health.UNHEALTHY, HealthChecker.healthOf("not a valid url"))
    }
}
