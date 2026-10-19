package com.watchreader.wear.tts

/**
 * One timer for one playback session. All calls, including scheduler callbacks, must be on the
 * service's main thread. The clock and deadlines use elapsed realtime, including device sleep.
 * Pausing, stopping, starting another playback session, or destroying the service cancels it;
 * neither resuming nor recreating the service restores it.
 */
class SleepTimer(
    private val elapsedRealtime: () -> Long,
    private val schedule: (deadlineMillis: Long, callback: () -> Unit) -> Cancellation,
    private val onChanged: (State?) -> Unit,
    private val onExpired: () -> Unit,
) {
    fun interface Cancellation { fun cancel() }

    data class State(val minutes: Int, val deadlineMillis: Long) {
        /** Round up so the display never says 00:00 while time remains. */
        fun remainingSeconds(elapsedRealtimeMillis: Long): Long =
            ((deadlineMillis - elapsedRealtimeMillis).coerceAtLeast(0) + 999) / 1_000
    }

    var state: State? = null
        private set
    private var generation = 0L
    private var scheduled: Cancellation? = null

    /** Selecting a duration again replaces the deadline, measured from this selection. */
    fun start(minutes: Int) {
        require(minutes in MINUTES) { "Unsupported sleep timer duration: $minutes" }
        invalidate()
        state = State(minutes, elapsedRealtime() + minutes * 60_000L)
        onChanged(state)
        try {
            arm(generation)
        } catch (failure: RuntimeException) {
            cancel()
            throw failure
        }
    }

    fun cancel() {
        invalidate()
        if (state != null) {
            state = null
            onChanged(null)
        }
    }

    /** Also checked at speech callbacks, in case a power-saving mode delayed alarm delivery. */
    fun checkExpired(): Boolean {
        val active = state ?: return false
        if (elapsedRealtime() < active.deadlineMillis) return false
        cancel()
        onExpired()
        return true
    }

    private fun arm(serial: Long) {
        val active = state ?: return
        scheduled?.cancel()
        scheduled = null
        scheduled = schedule(active.deadlineMillis) {
            // Cancellation cannot retract a callback already posted to the service thread.
            if (serial == generation && state != null && !checkExpired()) arm(serial)
        }
    }

    private fun invalidate() {
        generation++
        scheduled?.cancel()
        scheduled = null
    }

    companion object {
        val MINUTES: List<Int> = listOf(15, 30, 45, 60)
    }
}
