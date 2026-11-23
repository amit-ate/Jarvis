package com.jarvis.edge.setup

/**
 * When the extras watch may start a background run. A network event starts one only when the gate turns from
 * not-allowed to allowed (a change that keeps it allowed is ignored), and never within [EXTRAS_RETRY_COOLDOWN_MS] of a
 * background run that ended with the extras still pending. The first run of a service start is not decided here.
 */
object ExtrasRetryPolicy {
    const val EXTRAS_RETRY_COOLDOWN_MS = 6L * 60 * 60 * 1000

    fun shouldRun(prev: NetState, now: NetState, nowMs: Long, lastFailedRunAtMs: Long?): Boolean =
        prev != NetState.ALLOWED && now == NetState.ALLOWED && !inCooldown(nowMs, lastFailedRunAtMs)

    fun inCooldown(nowMs: Long, lastFailedRunAtMs: Long?): Boolean =
        lastFailedRunAtMs != null && nowMs - lastFailedRunAtMs < EXTRAS_RETRY_COOLDOWN_MS
}
