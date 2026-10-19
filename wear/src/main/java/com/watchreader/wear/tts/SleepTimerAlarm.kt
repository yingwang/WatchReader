package com.watchreader.wear.tts

import android.app.AlarmManager
import android.os.Handler
import android.os.SystemClock

/**
 * A service-owned wakeup alarm, not an Activity timer or a persisted alarm that restarts playback.
 * The listener form needs no exact-alarm special access. The foreground TTS service keeps its
 * lifecycle active. Android can still defer this alarm in deep Doze; speech callbacks also check
 * the elapsed deadline. The Handler is a second delivery path while the CPU is already awake.
 */
internal class SleepTimerAlarm(private val alarms: AlarmManager, private val handler: Handler) {
    fun schedule(deadlineMillis: Long, callback: () -> Unit): SleepTimer.Cancellation {
        var delivered = false
        val deliver = {
            if (!delivered) {
                delivered = true
                callback()
            }
        }
        val listener = AlarmManager.OnAlarmListener { deliver() }
        val runnable = Runnable { deliver() }
        alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadlineMillis, TAG, listener, handler)
        handler.postDelayed(runnable, (deadlineMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        return SleepTimer.Cancellation {
            delivered = true
            handler.removeCallbacks(runnable)
            alarms.cancel(listener)
        }
    }

    private companion object {
        const val TAG = "WatchReader:sleep-timer"
    }
}
