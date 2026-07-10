package io.github.welingtonmonteiro.appmonitor

import java.io.File

/**
 * Pure logic for the env viewer (Fase 2): decide which variables look like secrets, mask their
 * values, and build the rows to display in file order. IDE-free and unit-tested; reading the `.env`
 * file itself happens in the dialog. Reuses [EnvFile] for the actual parsing.
 */
object EnvViewer {

    /** One row of the env table: the key, the value as shown (masked when secret and hidden), and the flag. */
    data class EnvRow(val key: String, val displayValue: String, val secret: Boolean)

    /** Substrings (letters only, after uppercasing and dropping `_`) that mark a value as a secret. */
    private val SECRET_MARKERS =
        listOf("PASSWORD", "PASSWD", "PASSPHRASE", "SECRET", "TOKEN", "APIKEY", "CREDENTIAL", "PRIVATEKEY")

    /** Heuristic: does this variable name look like it holds a secret (so its value is masked by default)? */
    fun isSecret(key: String): Boolean {
        val k = key.uppercase().replace("_", "")
        if (SECRET_MARKERS.any { k.contains(it) }) return true
        return k.endsWith("KEY") // API_KEY / ACCESS_KEY, but not KEYCLOAK_URL
    }

    /** A fixed-width mask that never leaks the value's length. Blank stays blank. */
    fun mask(value: String): String = if (value.isEmpty()) "" else "•".repeat(8)

    /** Build the rows to display, in file order, masking secret values unless [reveal] is set. */
    fun rows(env: Map<String, String>, reveal: Boolean): List<EnvRow> =
        env.entries.map { (key, value) ->
            val secret = isSecret(key)
            EnvRow(key, if (secret && !reveal) mask(value) else value, secret)
        }

    /** Resolve the app's env-file path against the project base dir when relative; null when blank. */
    fun resolvePath(envFile: String, baseDir: String?): String? {
        if (envFile.isBlank()) return null
        val file = File(envFile)
        return if (file.isAbsolute || baseDir == null) file.path else File(baseDir, envFile).path
    }
}
