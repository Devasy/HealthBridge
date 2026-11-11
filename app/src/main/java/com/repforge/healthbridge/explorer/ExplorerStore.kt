package com.repforge.healthbridge.explorer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ExplorerStore(context: Context) : SQLiteOpenHelper(context, "explorer.db", null, 1) {
    companion object {
        private val accessLock = Mutex()
        /** Serialize helper lifetimes: onOpen pruning writes must not race another helper's read/transaction. */
        suspend fun <T> access(context: Context, block: suspend (ExplorerStore) -> T): T = accessLock.withLock {
            ExplorerStore(context.applicationContext).use { block(it) }
        }
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE observations(source TEXT NOT NULL, field TEXT NOT NULL, id TEXT NOT NULL, time INTEGER NOT NULL, value REAL, text_value TEXT, unit TEXT NOT NULL, resolution TEXT NOT NULL, origin TEXT NOT NULL, PRIMARY KEY(source,field,id))")
        db.execSQL("CREATE INDEX source_field_time ON observations(source,field,time)")
        db.execSQL("CREATE TABLE sources(source TEXT PRIMARY KEY, title TEXT NOT NULL, detail TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) db.delete("observations", "time < ?", arrayOf(cutoff().toString()))
    }
    private fun cutoff() = Instant.now().minusSeconds(366L * 86400).toEpochMilli()
    fun status(key: String, title: String, detail: String) {
        writableDatabase.insertWithOnConflict("sources", null, ContentValues().apply {
            put("source", key); put("title", title); put("detail", detail)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun statuses(): Map<String, String> = readableDatabase.query("sources", arrayOf("source", "detail"),
        null, null, null, null, null).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) } }
    fun clearRange(source: String, start: Instant, end: Instant) {
        writableDatabase.delete("observations", "source=? AND time>=? AND time<?",
            arrayOf(source, start.toEpochMilli().toString(), end.toEpochMilli().toString()))
    }
    fun clearFieldRange(source: String, field: String, start: Instant, end: Instant) {
        writableDatabase.delete("observations", "source=? AND field=? AND time>=? AND time<?",
            arrayOf(source, field, start.toEpochMilli().toString(), end.toEpochMilli().toString()))
    }
    fun put(source: String, readings: List<Observation>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in readings) {
                if (r.value != null && !r.value.isFinite()) continue
                if (r.time.toEpochMilli() < cutoff() || r.time > Instant.now().plusSeconds(86400)) continue
                check(db.insertWithOnConflict("observations", null, ContentValues().apply {
                    put("source", source); put("field", r.field); put("id", r.id); put("time", r.time.toEpochMilli())
                    if (r.value == null) putNull("value") else put("value", r.value)
                    put("text_value", r.text); put("unit", r.unit); put("resolution", r.resolution); put("origin", r.origin)
                }, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun fields(source: String): List<FieldInfo> = readableDatabase.rawQuery(
        "SELECT field,unit,COUNT(*),COUNT(value),MAX(resolution) FROM observations WHERE source=? AND time>=? GROUP BY field,unit ORDER BY field",
        arrayOf(source, cutoff().toString())).use { c -> buildList {
            while (c.moveToNext()) add(FieldInfo(c.getString(0), c.getString(1), c.getInt(2), c.getInt(3), c.getString(4)))
        } }

    /** Daily stats hold just one day's values at a time. Missing days are never filled with zero. */
    fun chart(source: String, field: String, start: Instant, end: Instant, raw: Boolean, zone: ZoneId): ChartData {
        val points = mutableListOf<ChartPoint>()
        val rows = ArrayDeque<RawRow>()
        var day: java.time.LocalDate? = null
        val values = mutableListOf<Double>()
        var count = 0
        var min: Double? = null
        var max: Double? = null
        var sum = 0.0
        var unit = ""
        var resolution = ""
        readableDatabase.rawQuery("SELECT time,value,text_value,unit,resolution,origin FROM observations WHERE source=? AND field=? AND time>=? AND time<? ORDER BY time",
            arrayOf(source, field, maxOf(start.toEpochMilli(), cutoff()).toString(), end.toEpochMilli().toString())).use { c ->
            while (c.moveToNext()) {
                val time = Instant.ofEpochMilli(c.getLong(0))
                unit = c.getString(3); resolution = c.getString(4)
                val number = if (c.isNull(1)) null else c.getDouble(1)
                rows.addLast(RawRow(time, number?.toString()
                    ?: c.getString(2).orEmpty(), c.getString(5)))
                if (rows.size > 100) rows.removeFirst()
                if (number == null || !number.isFinite()) continue
                count++; sum += number; min = min?.coerceAtMost(number) ?: number; max = max?.coerceAtLeast(number) ?: number
                if (raw) points += ChartPoint(time, number, number, number, number, 1)
                else {
                    val nextDay = time.atZone(zone).toLocalDate()
                    if (day != null && day != nextDay) {
                        points += Statistics.point(day!!.atStartOfDay(zone).toInstant(), values)
                        values.clear()
                    }
                    day = nextDay; values += number
                }
            }
        }
        if (!raw && day != null && values.isNotEmpty()) points += Statistics.point(day!!.atStartOfDay(zone).toInstant(), values)
        // Overall median is shown only for one-day raw mode; daily medians remain exact in other views.
        val median = if (raw && points.isNotEmpty()) Statistics.point(start, points.map { it.median }).median else null
        return ChartData(points, rows.toList().reversed(), count, min, max, if (count == 0) null else sum / count,
            median, unit, resolution)
    }
}
