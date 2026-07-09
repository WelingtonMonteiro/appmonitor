package io.github.welingtonmonteiro.appmonitor

import java.net.HttpURLConnection
import java.net.URI

/**
 * A best-effort HTTP health probe for an app. A 2xx/3xx response reads as healthy, anything else
 * (or a connection error / timeout) as unhealthy. Blocking with short timeouts, so it must run off
 * the EDT (the sampler calls it on its background thread).
 */
object HealthChecker {

    private const val TIMEOUT_MS = 1500

    /** null = no URL configured; true = healthy (2xx/3xx); false = unhealthy / unreachable. */
    fun check(healthUrl: String): Boolean? {
        if (healthUrl.isBlank()) return null
        return try {
            val connection = URI(healthUrl.trim()).toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.responseCode in 200..399
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Maps [check]'s tri-state result onto the [Health] shown in the Status column. */
    fun healthOf(healthUrl: String): Health = when (check(healthUrl)) {
        null -> Health.NONE
        true -> Health.HEALTHY
        false -> Health.UNHEALTHY
    }
}
