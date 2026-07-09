package io.github.welingtonmonteiro.appmonitor

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure parts of the mini process manager: the .env parser and the shell invocation. */
class CommandRunnerTest {

    @Test
    fun envParsesKeyValueSkippingCommentsAndBlanks() {
        val env = EnvFile.parse(
            """
            # a comment
            PORT=3003

            NODE_ENV=production
            """.trimIndent()
        )
        assertEquals(mapOf("PORT" to "3003", "NODE_ENV" to "production"), env)
    }

    @Test
    fun envToleratesExportAndStripsOnePairOfQuotes() {
        val env = EnvFile.parse(
            """
            export TOKEN="ab cd"
            NAME='eparts api'
            RAW=no-quotes
            """.trimIndent()
        )
        assertEquals("ab cd", env["TOKEN"])
        assertEquals("eparts api", env["NAME"])
        assertEquals("no-quotes", env["RAW"])
    }

    @Test
    fun envIgnoresLinesWithoutAKey() {
        assertEquals(emptyMap<String, String>(), EnvFile.parse("=value\n   \n#only comment"))
    }

    @Test
    fun shellInvocationDiffersByOs() {
        assertEquals(listOf("/bin/sh", "-c", "npm start"), AppCommandRunner.shellInvocation("npm start", windows = false))
        assertEquals(listOf("cmd.exe", "/c", "npm start"), AppCommandRunner.shellInvocation("npm start", windows = true))
    }
}
