package io.github.welingtonmonteiro.appmonitor

import io.github.welingtonmonteiro.appmonitor.model.MonitoredApp

/** What an action rule does when it fires. */
enum class ActionKind { RESTART, KILL, NOTIFY }

/** One action to execute now because a rule matched, with a human-readable reason. */
data class RuleFire(val kind: ActionKind, val reason: String)

/** Per-app rule state carried between refreshes (edge-triggering + sustained-duration tracking). */
data class RuleState(
    val downArmed: Boolean = false,
    /** Epoch ms since memory has been continuously over the threshold, or 0 when it is not. */
    val memHighSinceMs: Long = 0L,
    val memArmed: Boolean = false,
)

/**
 * Evaluates an app's automated action rules each refresh (Fase 4): **auto-restart when it goes down**,
 * and a **sustained-high-memory** action (notify / kill / restart). Pure and IDE-free so the
 * edge-triggering and the "held for N minutes" logic are unit-testable; the panel executes the fires.
 * Each rule fires once when its condition becomes true and re-arms only when the condition clears, so
 * a persistently bad state can't trigger the action every two seconds.
 */
object ActionRules {

    fun evaluate(app: MonitoredApp, sample: AppSample, now: Long, prev: RuleState): Pair<List<RuleFire>, RuleState> {
        val name = sample.name.ifBlank { app.targetLabel() }
        val fires = ArrayList<RuleFire>()

        // Rule 1: restart when the app goes down (needs a start command). Fires once per down episode.
        var downArmed = prev.downArmed
        if (app.restartOnDown && app.startCmd.isNotBlank()) {
            if (sample.up) {
                downArmed = false
            } else if (!prev.downArmed) {
                fires.add(RuleFire(ActionKind.RESTART, "$name is down - auto-restart"))
                downArmed = true
            }
        } else {
            downArmed = false
        }

        // Rule 2: sustained high memory -> notify / kill / restart. Fires once until memory drops back.
        val action = parseAction(app.memAction)
        var since = prev.memHighSinceMs
        var memArmed = prev.memArmed
        if (action != null && app.memActionMb > 0 && sample.up && sample.rssKb >= 0) {
            val high = sample.rssKb >= app.memActionMb * 1024L
            if (!high) {
                since = 0L
                memArmed = false
            } else {
                if (since == 0L) since = now
                val heldMs = now - since
                val needMs = app.memActionMinutes.coerceAtLeast(0) * 60_000L
                if (!prev.memArmed && heldMs >= needMs) {
                    val held = if (app.memActionMinutes > 0) " for ${app.memActionMinutes} min" else ""
                    fires.add(RuleFire(action,
                        "$name memory ${ProcessStatsSampler.formatMemory(sample.rssKb)} over ${app.memActionMb} MB$held"))
                    memArmed = true
                }
            }
        } else {
            since = 0L
            memArmed = false
        }

        return fires to RuleState(downArmed, since, memArmed)
    }

    /** Parse the persisted memory-action string; null = off/unknown. */
    fun parseAction(text: String): ActionKind? = when (text.trim().uppercase()) {
        "NOTIFY" -> ActionKind.NOTIFY
        "KILL" -> ActionKind.KILL
        "RESTART" -> ActionKind.RESTART
        else -> null
    }
}
