package com.wendigo.suzgec

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Stats {

    data class Recent(val host: String, val time: Long)

    private const val PREFS = "suzgec_stats"
    private const val KEY_TOTAL = "total"
    private const val KEY_BLOCKED = "blocked"
    private const val KEY_DAY = "day"
    private const val KEY_DAY_TOTAL = "day_total"
    private const val KEY_DAY_BLOCKED = "day_blocked"
    private const val MAX_RECENT = 40

    private val lock = Any()
    private val recent = ArrayDeque<Recent>()
    private var prefs: android.content.SharedPreferences? = null
    private var dirty = 0

    @Volatile var total: Long = 0
        private set
    @Volatile var blocked: Long = 0
        private set
    @Volatile var dayTotal: Long = 0
        private set
    @Volatile var dayBlocked: Long = 0
        private set
    @Volatile var lastQueryAt: Long = 0
        private set

    private var dayKey: String = todayKey()

    fun init(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = p
            total = p.getLong(KEY_TOTAL, 0)
            blocked = p.getLong(KEY_BLOCKED, 0)
            val savedDay = p.getString(KEY_DAY, "") ?: ""
            if (savedDay == dayKey) {
                dayTotal = p.getLong(KEY_DAY_TOTAL, 0)
                dayBlocked = p.getLong(KEY_DAY_BLOCKED, 0)
            } else {
                dayTotal = 0
                dayBlocked = 0
            }
        }
    }

    fun onQuery(host: String?, isBlocked: Boolean) {
        synchronized(lock) {
            rollDayIfNeeded()
            lastQueryAt = System.currentTimeMillis()
            total++
            dayTotal++
            if (isBlocked) {
                blocked++
                dayBlocked++
                if (!host.isNullOrEmpty()) {
                    recent.addFirst(Recent(host, System.currentTimeMillis()))
                    while (recent.size > MAX_RECENT) recent.removeLast()
                }
            }
            dirty++
            if (dirty >= 20) flushLocked()
        }
    }

    fun recentBlocked(): List<Recent> = synchronized(lock) { recent.toList() }

    fun todayQueryCount(): Long = synchronized(lock) { rollDayIfNeeded(); dayTotal }

    fun todayBlockedCount(): Long = synchronized(lock) { rollDayIfNeeded(); dayBlocked }

    fun flush() = synchronized(lock) { flushLocked() }

    fun resetToday() {
        synchronized(lock) {
            dayTotal = 0
            dayBlocked = 0
            recent.clear()
            flushLocked()
        }
    }

    private fun rollDayIfNeeded() {
        val key = todayKey()
        if (key != dayKey) {
            dayKey = key
            dayTotal = 0
            dayBlocked = 0
        }
    }

    private fun flushLocked() {
        dirty = 0
        prefs?.edit()
            ?.putLong(KEY_TOTAL, total)
            ?.putLong(KEY_BLOCKED, blocked)
            ?.putString(KEY_DAY, dayKey)
            ?.putLong(KEY_DAY_TOTAL, dayTotal)
            ?.putLong(KEY_DAY_BLOCKED, dayBlocked)
            ?.apply()
    }

    private fun todayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
