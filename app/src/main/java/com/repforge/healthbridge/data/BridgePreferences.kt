package com.repforge.healthbridge.data

import android.content.Context
import android.content.SharedPreferences
import java.time.Instant

class BridgePreferences(context: Context) {
    val raw: SharedPreferences = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
    init {
        if (!raw.getBoolean("extras_scope_v1", false)) {
            val previous = raw.getStringSet("metrics", null)?.toSet()
                ?: Metric.entries.filter { it.defaultEnabled }.map { it.name }.toSet()
            raw.edit().putStringSet("metrics", (previous - setOf("HEART", "SLEEP")) + setOf("ENERGY", "SLEEP_SCORE"))
                .putBoolean("extras_scope_v1", true).apply()
        }
        if (!raw.getBoolean("full_scope_v1", false)) {
            val previous = raw.getStringSet("metrics", emptySet()).orEmpty()
            raw.edit().putStringSet("metrics", previous + ExtendedSamsungReader.metrics.map { it.name } + setOf("OXYGEN", "PRESSURE"))
                .putBoolean("full_scope_v1", true).apply()
        }
        if (!raw.getBoolean("workout_streams_v1", false)) {
            raw.edit().remove("cursor_EXERCISE").remove("catalog_cursor_EXERCISE")
                .putBoolean("workout_streams_v1", true).apply()
        }
    }
    var selected: Set<Metric>
        get() = raw.getStringSet("metrics", null)?.mapNotNull { name ->
            Metric.entries.find { it.name == name }
        }?.toSet() ?: Metric.entries.filter { it.defaultEnabled }.toSet()
        set(value) { raw.edit().putStringSet("metrics", value.map { it.name }.toSet()).apply() }
    var days: Int
        get() = raw.getInt("days", 7)
        set(value) {
            require(value in setOf(7, 30, 90))
            raw.edit().putInt("days", value).apply()
            // A wider requested backfill must actually re-read older data.
            Metric.entries.forEach { raw.edit().remove("cursor_${it.name}").apply() }
            val edit = raw.edit()
            raw.all.keys.filter { it.startsWith("catalog_cursor_") }.forEach(edit::remove)
            edit.apply()
        }
    var background: Boolean
        get() = raw.getBoolean("background", false)
        set(value) { raw.edit().putBoolean("background", value).apply() }
    fun checkpoint(metric: Metric) = raw.getLong("cursor_${metric.name}", 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
    fun checkpoint(metric: Metric, time: Instant) {
        check(raw.edit().putLong("cursor_${metric.name}", time.toEpochMilli()).commit())
    }
    fun report(text: String, count: Int, time: Instant) {
        raw.edit().putString("report", text).putInt("count", count)
            .putLong("last_run", time.toEpochMilli()).apply()
    }
}
