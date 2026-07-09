package io.github.welingtonmonteiro.appmonitor

/**
 * A tiny `.env` parser (KEY=VALUE per line) used when starting an app by command. Skips blank lines
 * and `#` comments, tolerates a leading `export`, and strips one pair of surrounding quotes from the
 * value. Pure and IDE-free so it is unit-testable; reading the file itself happens in the runner.
 */
object EnvFile {

    fun parse(text: String): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            var line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("export ")) line = line.substring("export ".length).trim()
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            if (key.isEmpty()) continue
            env[key] = unquote(line.substring(eq + 1).trim())
        }
        return env
    }

    private fun unquote(value: String): String {
        if (value.length >= 2 &&
            ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))
        ) {
            return value.substring(1, value.length - 1)
        }
        return value
    }
}
