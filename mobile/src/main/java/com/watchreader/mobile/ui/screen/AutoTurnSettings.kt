package com.watchreader.mobile.ui.screen

import android.content.Context
import android.content.SharedPreferences

/**
 * Auto page turn, kept with the reader's text settings in "reader_appearance": whether pages turn
 * by themselves, and how long each one stays.
 *
 * The seconds move along a ladder as on the watch, a second at a time where a second matters and
 * in larger steps above. A phone page holds several watch pages, so the ladder reaches much
 * further; it starts from the same eight seconds.
 */
class AutoTurnPrefs(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(NAME, Context.MODE_PRIVATE))

    /** Off unless the reader switches it on. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Seconds a page stays before it turns, always one of the ladder's stops. */
    var seconds: Int
        get() = nearest(prefs.getInt(KEY_SECONDS, DEFAULT_SECONDS))
        set(value) = prefs.edit().putInt(KEY_SECONDS, nearest(value)).apply()

    companion object {
        const val NAME = "reader_appearance"
        const val DEFAULT_SECONDS = 8
        /** The stops the speed slider moves through, as seconds a page. */
        val LADDER = intArrayOf(2, 3, 4, 5, 6, 8, 10, 12, 15, 20, 25, 30, 40, 50, 60, 75, 90, 120)
        private const val KEY_ENABLED = "auto_turn_enabled"
        private const val KEY_SECONDS = "auto_turn_seconds"

        /** The stop nearest [seconds]; the lower of two equally near. */
        fun nearest(seconds: Int): Int = LADDER.minByOrNull { kotlin.math.abs(it - seconds) } ?: DEFAULT_SECONDS

        /**
         * The reader sees a pace from slow to fast, not seconds (as in WeChat Reading): position 0
         * is the slowest stop, the last the fastest. [speedOf] and [secondsAt] go between the two.
         */
        val SPEEDS: Int get() = LADDER.size

        fun speedOf(seconds: Int): Int = LADDER.size - 1 - LADDER.indexOf(nearest(seconds))

        fun secondsAt(speed: Int): Int = LADDER[LADDER.size - 1 - speed.coerceIn(0, LADDER.size - 1)]

        /** One stop up or down from [seconds], staying at the ladder's ends. */
        fun step(seconds: Int, by: Int): Int {
            val index = LADDER.indexOf(nearest(seconds))
            return LADDER[(index + by).coerceIn(0, LADDER.size - 1)]
        }
    }
}

/**
 * Decides when an open page turns by itself. The screen tells it what happens and asks how long is
 * left; it keeps no thread of its own, so the rules can be followed step by step.
 *
 * A page gets its full time from the moment it comes up, whether it was turned by hand, by the
 * timer or shown again after the reader looked away. While anything holds the timer (the controls
 * or a sheet open, text selected, the app in the background) nothing turns; when the last hold
 * lets go the page starts its time again, since the reader has just been looking at something
 * else. The last page keeps its time like any other, and then the turning stops.
 */
class AutoTurnTimer(private val now: () -> Long) {
    enum class Tick {
        /** Not yet, or not at all while stopped or held. */
        WAIT,
        /** The page's time is up: turn it. */
        TURN,
        /** The last page's time is up: the turning stops, and the timer is off. */
        END,
    }

    var enabled = false
        private set
    private var millisPerPage = AutoTurnPrefs.DEFAULT_SECONDS * 1000L
    private var lastPage = false
    private val holds = HashSet<Any>()
    private var deadline: Long? = null

    /** Whether a page is counting down now. */
    val running: Boolean get() = enabled && holds.isEmpty()

    fun configure(enabled: Boolean, seconds: Int) {
        val millis = seconds * 1000L
        if (enabled == this.enabled && millis == millisPerPage) return
        this.enabled = enabled
        millisPerPage = millis
        restart()
    }

    /** Holds the timer for [reason] while [on]; each reason is let go of separately. */
    fun hold(reason: Any, on: Boolean) {
        val changed = if (on) holds.add(reason) else holds.remove(reason)
        if (changed) restart()
    }

    /** A page came up; [last] when it is the book's last. */
    fun pageShown(last: Boolean) {
        lastPage = last
        restart()
    }

    /** Milliseconds before the page turns; null while stopped or held. */
    fun remaining(): Long? = deadline?.let { (it - now()).coerceAtLeast(0) }

    fun tick(): Tick {
        val due = deadline ?: return Tick.WAIT
        if (now() < due) return Tick.WAIT
        deadline = null
        if (lastPage) {
            enabled = false
            return Tick.END
        }
        return Tick.TURN
    }

    private fun restart() {
        deadline = if (running) now() + millisPerPage else null
    }
}
