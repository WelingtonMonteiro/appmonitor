package io.github.welingtonmonteiro.appmonitor

/**
 * The toolbar filter for the monitor table: a case-insensitive substring match over an app's name,
 * tag, target label and ports. Pure and IDE-free so it is unit-testable; a blank query matches all.
 */
object AppRowFilter {

    fun matches(sample: AppSample, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return sample.name.lowercase().contains(q) ||
            sample.tag.lowercase().contains(q) ||
            sample.targetLabel.lowercase().contains(q) ||
            sample.ports.any { it.toString().contains(q) }
    }

    fun filter(rows: List<AppSample>, query: String): List<AppSample> = rows.filter { matches(it, query) }
}
