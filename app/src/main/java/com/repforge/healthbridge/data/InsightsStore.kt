package com.repforge.healthbridge.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.json.JSONObject

/** Private on-device history for data that Health Connect cannot represent. No Room/runtime dependency. */
class InsightsStore(context: Context) : SQLiteOpenHelper(context, "samsung_insights.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE insights (metric TEXT NOT NULL, uid TEXT NOT NULL, time INTEGER NOT NULL, updated INTEGER NOT NULL, values_json TEXT NOT NULL, PRIMARY KEY(metric, uid))")
        db.execSQL("CREATE INDEX insights_time ON insights(time DESC)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) prune(db)
    }
    private fun cutoff() = Instant.now().minusSeconds(90L * 86400).toEpochMilli().toString()
    private fun prune(db: SQLiteDatabase) { db.delete("insights", "time < ?", arrayOf(cutoff())) }

    fun put(metric: Metric, points: List<SourcePoint>): Int {
        var count = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (p in points) {
                val values = InsightValues.valid(p.values)
                if (values.isEmpty()) continue
                // Do not replace a correction with an older source version.
                val newerExists = db.rawQuery("SELECT updated FROM insights WHERE metric=? AND uid=?",
                    arrayOf(metric.name, p.uid)).use { it.moveToFirst() && it.getLong(0) > p.updated.toEpochMilli() }
                if (newerExists) continue
                val row = ContentValues().apply {
                    put("metric", metric.name); put("uid", p.uid); put("time", p.start.toEpochMilli())
                    put("updated", p.updated.toEpochMilli()); put("values_json", JSONObject(values).toString())
                }
                check(db.insertWithOnConflict("insights", null, row, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
                count++
            }
            // Retain a bounded history, not an indefinitely growing health database.
            prune(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return count
    }

    fun latest(selected: Set<Metric>): List<Insight> {
        val found = linkedMapOf<String, Insight>()
        readableDatabase.query("insights", arrayOf("metric", "time", "values_json"), "time >= ?", arrayOf(cutoff()),
            null, null, "time DESC", "2000").use { cursor ->
            while (cursor.moveToNext()) {
                if (selected.none { it.name == cursor.getString(0) }) continue
                val date = Instant.ofEpochMilli(cursor.getLong(1)).atZone(ZoneId.systemDefault()).toLocalDate().toString()
                val json = JSONObject(cursor.getString(2))
                for ((key, field) in InsightValues.fields) {
                    if (key in found || !json.has(key)) continue
                    val value = json.getDouble(key)
                    val digits = if (key in setOf("energy", "sleepScore", "bmr")) "%.0f" else "%.1f"
                    found[key] = Insight(field.first, "${String.format(Locale.getDefault(), digits, value)} ${field.second}".trim(), date)
                }
            }
        }
        return InsightValues.fields.keys.mapNotNull { found[it] }
    }
}
