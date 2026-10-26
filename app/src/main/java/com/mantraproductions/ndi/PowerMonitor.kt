package com.mantraproductions.ndi

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlin.math.abs

/**
 * POWER MONITOR (v155, Marko, 26.10.2026: "battery minutes, meaning how long I can keep running like this until my
 * battery dies ... If the phone is charging, it's the opposite: it shows how much time it needs to charge. It should
 * show whether it's charging or discharging, plus or minus, or stable in balance ... robust and true to life").
 *
 * What Android gives (BatteryManager): the charge left in the cell (CHARGE_COUNTER, µAh), the current now
 * (CURRENT_NOW), the voltage, the level, the status. The traps, also handled by Capacity Info (Apache-2.0, the open
 * source monitor read for this): phones report the current in µA or in mA, and with either sign. Here:
 *  - the unit is read from the size of the number (a phone draws more than 15 mA; under 15 000 it is mA);
 *  - the sign is read from the cell itself: the charge counter rising is charging, falling is discharging; until it
 *    has moved, Android's own status decides;
 *  - the honest number is the charge counter's slope over the last minutes (every spike of the encoder, the torch,
 *    the radio is in it); the instant current, smoothed, stands in until there are [WINDOW_MIN_MS] of samples.
 * Time to empty = charge left ÷ drain. Time to full = what is missing ÷ net charge, the part above 80 % counted
 * twice as long (a lithium cell takes its last fifth at a falling current), or Android's own estimate when it has one.
 */
class PowerMonitor(private val context: Context) {

    enum class Flow { CHARGING, DISCHARGING, BALANCED, FULL }

    data class Reading(
        val level: Int,                 // %
        val flow: Flow,
        val netMa: Int,                 // + into the cell, − out of it
        val volts: Double,
        val watts: Double,              // |net| × V
        val seconds: Long?,             // to empty (discharging) or to full (charging); null: not known yet
        val tempC: Double,
        val plugged: String,            // "", "AC", "USB", "WIRELESS", "DOCK"
        val measured: Boolean,          // true: from the cell's own counter over minutes; false: the current now
        val chargeMah: Int?             // charge left in the cell
    )

    private val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val counter = ArrayDeque<Pair<Long, Long>>()       // (ms, µAh)
    private var ema: Double? = null                             // smoothed current, mA, sign as reported
    private var signFlip: Int? = null                           // 1 or -1 once the counter has shown the direction
    private var lastSystemFull = 0L
    private var lastPlugged: String? = null

    /** One look at the battery; call about once a second. */
    fun read(now: Long = System.currentTimeMillis()): Reading {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.let { (it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100f / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)).toInt() } ?: -1
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN) ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val plugged = when (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"; BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"; 8 -> "DOCK"; else -> ""
        }
        val volts = (i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0).let { if (it > 100) it / 1000.0 else it.toDouble() }
        val tempC = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0

        val rawNow = runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrDefault(Int.MIN_VALUE)
        val nowMa = if (rawNow == Int.MIN_VALUE || rawNow == 0) null else Calc.toMilliamps(rawNow.toLong())
        val uah = runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) }.getOrDefault(Int.MIN_VALUE)
            .takeIf { it > 0 }?.toLong()
            // a counter that would make a full cell under 1 000 or over 15 000 mAh is not believed (seen 26.10.2026:
            // the emulator says 10 mAh at 64 %); then the level and the cell's design capacity stand in
            ?.takeIf { Calc.plausible(it, level) }

        // a cable in or out: the minutes before it measured another flow
        if (plugged != lastPlugged) { counter.clear(); ema = null; lastPlugged = plugged }
        if (uah != null) {
            counter.addLast(now to uah)
            while (counter.size > 2 && now - counter.first().first > WINDOW_MAX_MS) counter.removeFirst()
        }
        nowMa?.let { ema = Calc.smooth(ema, it.toDouble()) }

        val slope = Calc.slopeMa(counter.toList(), WINDOW_MIN_MS, MIN_STEP_UAH)
        // the reported current's sign, set right by the counter once it has moved
        if (slope != null && ema != null && abs(slope) > BALANCE_MA && abs(ema!!) > BALANCE_MA)
            signFlip = if ((slope > 0) == (ema!! > 0)) 1 else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING
        val fromEma = ema?.let { e ->
            val f = signFlip ?: if (charging == (e > 0) || abs(e) < BALANCE_MA) 1 else -1   // no counter yet: the status says
            e * f
        }
        val net = slope ?: fromEma ?: 0.0
        val measured = slope != null

        val flow = when {
            status == BatteryManager.BATTERY_STATUS_FULL || (level >= 100 && plugged.isNotEmpty() && net > -BALANCE_MA) -> Flow.FULL
            abs(net) < BALANCE_MA -> Flow.BALANCED
            net > 0 -> Flow.CHARGING
            else -> Flow.DISCHARGING
        }
        val chargeMah = uah?.let { (it / 1000).toInt() }
        val seconds = when (flow) {
            Flow.DISCHARGING -> chargeMah?.let { Calc.secondsToEmpty(it.toDouble(), -net) }
                ?: Calc.secondsToEmptyByLevel(level, -net, DESIGN_MAH_GUESS)
            Flow.CHARGING -> {
                val system = runCatching { bm.computeChargeTimeRemaining() }.getOrDefault(-1L)
                if (system > 0) { lastSystemFull = system; system / 1000 }
                else Calc.secondsToFull(level, chargeMah?.toDouble(), net)
            }
            else -> null
        }
        return Reading(level, flow, net.toInt(), volts, abs(net) / 1000.0 * volts, seconds, tempC, plugged, measured, chargeMah)
    }

    /** The numbers, without Android, so they are tested on the Mac. */
    object Calc {
        /** µA or mA, by size: a running phone draws more than 15 mA, so a number under 15 000 is in mA. */
        fun toMilliamps(raw: Long): Int = if (abs(raw) >= 15_000) (raw / 1000).toInt() else raw.toInt()

        /** Does a charge counter of [uah] at [level] % mean a cell of 1 000 to 15 000 mAh? */
        fun plausible(uah: Long, level: Int): Boolean {
            if (level !in 1..100) return false
            val fullMah = uah / 1000.0 * 100.0 / level
            return fullMah in 1000.0..15000.0
        }

        fun smooth(prev: Double?, x: Double, a: Double = 0.15): Double = if (prev == null) x else prev + a * (x - prev)

        /** The counter's slope in mA (+ rising) over the samples, or null until they span [minMs] and moved [minStep]. */
        fun slopeMa(samples: List<Pair<Long, Long>>, minMs: Long, minStep: Long): Double? {
            if (samples.size < 2) return null
            val (t0, c0) = samples.first(); val (t1, c1) = samples.last()
            val dt = t1 - t0
            if (dt < minMs) return null
            // a least-squares line, so one jittery read does not swing the answer
            val n = samples.size; val mt = samples.sumOf { (it.first - t0).toDouble() } / n; val mc = samples.sumOf { it.second.toDouble() } / n
            var num = 0.0; var den = 0.0
            for ((t, c) in samples) { val x = (t - t0) - mt; num += x * (c - mc); den += x * x }
            if (den == 0.0) return null
            val uahPerMs = num / den
            if (abs(c1 - c0) < minStep && abs(uahPerMs * dt) < minStep) return 0.0            // still: balanced
            return uahPerMs * 3_600_000.0 / 1000.0                                            // µAh/ms → mA
        }

        fun secondsToEmpty(chargeMah: Double, drainMa: Double): Long? =
            if (drainMa <= 0) null else (chargeMah / drainMa * 3600).toLong()

        fun secondsToEmptyByLevel(level: Int, drainMa: Double, designMah: Int): Long? =
            if (drainMa <= 0 || level < 0) null else (designMah * level / 100.0 / drainMa * 3600).toLong()

        /** To full: what is missing below 80 % at the present current, what is above 80 % at half of it. */
        fun secondsToFull(level: Int, chargeMah: Double?, netMa: Double): Long? {
            if (netMa <= 0 || level < 0) return null
            val full = chargeMah?.takeIf { level in 1..99 }?.let { it * 100.0 / level } ?: (DESIGN_MAH_GUESS.toDouble())
            val now = full * level / 100.0
            val to80 = (full * 0.8 - now).coerceAtLeast(0.0)
            val above = full - maxOf(now, full * 0.8)
            return ((to80 / netMa + above / (netMa / 2)) * 3600).toLong()
        }

        /** 3:42 h, 42 min. */
        fun format(seconds: Long?): String = when {
            seconds == null -> "--:--"
            seconds >= 100 * 3600 -> "99:59"
            seconds >= 3600 -> String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 3600, (seconds / 60) % 60)
            else -> String.format(java.util.Locale.ROOT, "0:%02d", (seconds / 60).coerceAtLeast(0))
        }
    }

    companion object {
        const val BALANCE_MA = 50.0
        const val WINDOW_MIN_MS = 120_000L
        const val WINDOW_MAX_MS = 600_000L
        const val MIN_STEP_UAH = 2_000L
        /** When a phone gives no charge counter: the Pixel 7's cell. */
        const val DESIGN_MAH_GUESS = 4355
    }
}
