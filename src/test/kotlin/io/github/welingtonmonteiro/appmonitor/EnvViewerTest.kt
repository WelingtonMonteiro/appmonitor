package io.github.welingtonmonteiro.appmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure env-viewer logic: secret detection, masking, row building and path resolution. */
class EnvViewerTest {

    @Test
    fun flagsSecretLookingKeys() {
        assertTrue(EnvViewer.isSecret("DB_PASSWORD"))
        assertTrue(EnvViewer.isSecret("password"))
        assertTrue(EnvViewer.isSecret("DB_PASSWD"))
        assertTrue(EnvViewer.isSecret("JWT_SECRET"))
        assertTrue(EnvViewer.isSecret("GITHUB_TOKEN"))
        assertTrue(EnvViewer.isSecret("API_KEY"))
        assertTrue(EnvViewer.isSecret("AWS_ACCESS_KEY"))
        assertTrue(EnvViewer.isSecret("STRIPE_APIKEY"))
        assertTrue(EnvViewer.isSecret("SSH_PASSPHRASE"))
    }

    @Test
    fun leavesOrdinaryKeysVisible() {
        assertFalse(EnvViewer.isSecret("DATABASE_URL"))
        assertFalse(EnvViewer.isSecret("PORT"))
        assertFalse(EnvViewer.isSecret("NODE_ENV"))
        assertFalse("KEY only masks as a suffix, not KEYCLOAK", EnvViewer.isSecret("KEYCLOAK_URL"))
    }

    @Test
    fun maskHidesValueWithoutLeakingLength() {
        assertEquals("", EnvViewer.mask(""))
        val short = EnvViewer.mask("x")
        val long = EnvViewer.mask("a-very-long-secret-value")
        assertEquals("same width regardless of length", short, long)
        assertFalse(long.contains("secret"))
    }

    @Test
    fun rowsMaskSecretsByDefaultAndKeepFileOrder() {
        val env = linkedMapOf("PORT" to "3003", "DB_PASSWORD" to "hunter2")
        val hidden = EnvViewer.rows(env, reveal = false)
        assertEquals(listOf("PORT", "DB_PASSWORD"), hidden.map { it.key })
        assertEquals("3003", hidden[0].displayValue)
        assertFalse(hidden[0].secret)
        assertTrue(hidden[1].secret)
        assertFalse(hidden[1].displayValue.contains("hunter2"))
    }

    @Test
    fun rowsRevealSecretsWhenAsked() {
        val env = linkedMapOf("DB_PASSWORD" to "hunter2")
        val shown = EnvViewer.rows(env, reveal = true)
        assertEquals("hunter2", shown[0].displayValue)
        assertTrue("still flagged, just revealed", shown[0].secret)
    }

    @Test
    fun resolvePathHandlesAbsoluteRelativeAndBlank() {
        assertNull(EnvViewer.resolvePath("", "/proj"))
        assertEquals("/etc/app.env", EnvViewer.resolvePath("/etc/app.env", "/proj"))
        assertEquals("/proj/.env", EnvViewer.resolvePath(".env", "/proj"))
        assertEquals(".env", EnvViewer.resolvePath(".env", null))
    }
}
