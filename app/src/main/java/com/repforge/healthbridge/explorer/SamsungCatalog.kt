package com.repforge.healthbridge.explorer

import android.app.Activity
import android.content.Context
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.data.*
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.*
import java.lang.reflect.Modifier
import java.time.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import com.repforge.healthbridge.data.PageProgress
import com.repforge.healthbridge.data.WorkoutPages

data class SamsungSource(val key: String, val type: DataType) {
    val title get() = fieldLabel(key)
    val fieldNames get() = type.javaClass.fields.filter {
        Modifier.isStatic(it.modifiers) && (it.type == Field::class.java || it.type == AggregateOperation::class.java)
    }.map { it.name }
}

class SamsungCatalog(context: Context) {
    private val store by lazy { HealthDataService.getStore(context.applicationContext) }
    val sources: List<SamsungSource> = DataTypes::class.java.fields.filter {
        Modifier.isStatic(it.modifiers) && DataType::class.java.isAssignableFrom(it.type)
    }.map { SamsungSource(it.name, it.get(null) as DataType) }.sortedBy { it.title }
    // Location is a permission on exercise data, not an independently readable data type.
    private val excluded = setOf("EXERCISE_LOCATION")
    private fun permission(source: SamsungSource) = Permission.of(source.type, AccessType.READ)
    fun permissions() = sources.map(::permission).toSet()
    suspend fun request(activity: Activity) {
        store.requestPermissions(permissions(), activity)
    }
    suspend fun granted(): Set<Permission> = store.getGrantedPermissions(
        permissions())

    suspend fun scan(source: SamsungSource, granted: Set<Permission>, start: Instant, end: Instant,
        zone: ZoneId, output: ExplorerStore, progress: (String) -> Unit): Boolean {
        if (source.key in excluded) {
            output.status(source.key, source.title, "Route coordinates are read within Exercise → Sessions → Route, with exercise-location access.")
            return false
        }
        if (permission(source) !in granted) {
            output.status(source.key, source.title, "Samsung read permission not granted")
            return false
        }
        try {
            if (source.key == "USER_PROFILE") {
                val response = store.readData(DataTypes.USER_PROFILE.readDataRequestBuilder.build())
                val time = Instant.now()
                val batch = mutableListOf<Observation>()
                for (point in response.dataList) for (field in source.type.javaClass.fields.filter {
                    Modifier.isStatic(it.modifiers) && it.type == Field::class.java }) {
                    @Suppress("UNCHECKED_CAST")
                    val value = point.getValue(field.get(null) as Field<Any>) ?: continue
                    flatten(source.key, field.name.lowercase(), "current-profile", time, value, batch,
                        resolution = "Current profile snapshot · not a historical measurement")
                }
                // A profile has no measurement timestamp; keep one current snapshot rather than inventing a history.
                output.clearRange(source.key, Instant.EPOCH, time.plusSeconds(1))
                output.put(source.key, batch)
                output.status(source.key, source.title, "${batch.size} current profile fields · stays on this phone")
                return true
            }
            var records = 0
            var readings = 0
            if (source.type is DataType.Readable<*, *>) {
                var token: String? = null
                val pages = PageProgress()
                do {
                    coroutineContext.ensureActive()
                    progress("${source.title}: $records records")
                    val builder = source.type.readDataRequestBuilder
                    when (builder) {
                        is ReadDataRequest.DualTimeBuilder<*> -> {
                            // Series packets can start before midnight but contain samples in the requested day.
                            // Read a short overlap, then retain only values whose actual timestamps are in range.
                            if (token != null) builder.setPageToken(token) else builder
                                .setInstantTimeFilter(InstantTimeFilter.of(start.minusSeconds(2 * 86400), end))
                                .setPageSize(if (source.key == "EXERCISE") 1 else 100).setOrdering(Ordering.ASC)
                        }
                        is ReadDataRequest.LocalDateBuilder<*> -> {
                            if (token != null) builder.setPageToken(token) else builder
                                .setLocalDateFilter(LocalDateFilter.of(start.atZone(zone).toLocalDate(),
                                end.minusNanos(1).atZone(zone).toLocalDate(), true, true)).setPageSize(100).setOrdering(Ordering.ASC)
                        }
                        else -> { output.status(source.key, source.title, "No timestamped numeric reader in this SDK."); return false }
                    }
                    @Suppress("UNCHECKED_CAST")
                    val workoutPage = if (source.key == "EXERCISE")
                        WorkoutPages(store).read(start.minusSeconds(2 * 86400), end, token) else null
                    val response = if (workoutPage == null) store.readData(builder.build() as ReadDataRequest<DataPoint>) else null
                    val dataList: List<DataPoint> = workoutPage?.points ?: response!!.dataList
                    val next = pages.next(workoutPage?.next ?: if (workoutPage != null) null else response!!.pageToken,
                        workoutPage?.identities ?: dataList.filterIsInstance<HealthDataPoint>().map { "${it.uid}:${it.updateTime}" })
                    if (token == null) output.clearRange(source.key, start, end)
                    for (point in dataList) {
                        if (point !is HealthDataPoint) continue
                        records++
                        val batch = mutableListOf<Observation>()
                        for (field in source.type.javaClass.fields.filter { Modifier.isStatic(it.modifiers) && it.type == Field::class.java }) {
                            @Suppress("UNCHECKED_CAST")
                            val value = point.getValue(field.get(null) as Field<Any>) ?: continue
                            flatten(source.key, field.name.lowercase(), point.uid, point.startTime, value, batch)
                        }
                        val inRange = batch.filter { it.time >= start && it.time < end }
                        inRange.chunked(500).forEach { output.put(source.key, it) }
                        readings += inRange.size
                    }
                    token = next
                } while (token != null)
            } else {
                for (field in source.type.javaClass.fields.filter { Modifier.isStatic(it.modifiers) && it.type == AggregateOperation::class.java }) {
                    @Suppress("UNCHECKED_CAST")
                    val operation = field.get(null) as AggregateOperation<Any, *>
                    var token: String? = null
                    val pages = PageProgress()
                    do {
                        coroutineContext.ensureActive()
                        progress("${source.title}: ${field.name.lowercase()}")
                        val builder = operation.requestBuilder
                        val dates = LocalDateFilter.of(start.atZone(zone).toLocalDate(), end.minusNanos(1).atZone(zone).toLocalDate(), true, true)
                        when (builder) {
                            is AggregateRequest.LocalTimeBuilder<*> -> {
                                builder.setLocalTimeFilterWithGroup(LocalTimeFilter.of(start.atZone(zone).toLocalDateTime(),
                                    end.atZone(zone).toLocalDateTime()), LocalTimeGroup.of(LocalTimeGroupUnit.DAILY, 1)).setPageSize(100)
                                token?.let(builder::setPageToken)
                            }
                            is AggregateRequest.LocalDateBuilder<*> -> {
                                builder.setLocalDateFilterWithGroup(dates, LocalDateGroup.of(LocalDateGroupUnit.DAILY, 1)).setPageSize(100)
                                token?.let(builder::setPageToken)
                            }
                            is AggregateRequest.AllSourceLocalDateBuilder<*> -> {
                                builder.setLocalDateFilterWithGroup(dates, LocalDateGroup.of(LocalDateGroupUnit.DAILY, 1)).setPageSize(100)
                                token?.let(builder::setPageToken)
                            }
                            else -> break
                        }
                        @Suppress("UNCHECKED_CAST")
                        val response = store.aggregateData(builder.build() as AggregateRequest<Any>)
                        val next = pages.next(response.pageToken, response.dataList.map { "${it.startTime}:${it.value}" })
                        if (token == null) output.clearFieldRange(source.key, field.name.lowercase(), start, end)
                        val batch = mutableListOf<Observation>()
                        for (point in response.dataList) {
                            val value = point.value ?: continue
                            records++
                            flatten(source.key, field.name.lowercase(), "aggregate:${point.startTime}", point.startTime,
                                value, batch, resolution = "Samsung daily aggregate")
                        }
                        output.put(source.key, batch); readings += batch.size
                        token = next
                    } while (token != null)
                }
            }
            output.status(source.key, source.title, "$records source records · $readings field values · ${start.atZone(zone).toLocalDate()} to ${end.minusNanos(1).atZone(zone).toLocalDate()}")
            return true
        } catch (cancelled: CancellationException) {
            output.status(source.key, source.title, "Scan canceled. This range's cached results may be partial.")
            throw cancelled
        }
        catch (failure: Exception) {
            // Stack frames only: no exception messages, record IDs, coordinates or health values.
            android.util.Log.e("BridgeReadError", "${source.key}: ${failure.javaClass.name}\n" +
                failure.stackTrace.take(10).joinToString("\n"))
            output.status(source.key, source.title, "Read failed: ${failure.javaClass.simpleName}. Cached results may be partial; retry this source.")
            return false
        }
    }

    private fun flatten(source: String, path: String, id: String, time: Instant, value: Any,
        out: MutableList<Observation>, depth: Int = 0, resolution: String = "Recorded value") {
        if (depth > 4) return
        val key = "$id:$path:${time.toEpochMilli()}"
        when (value) {
            is Number -> if (value.toDouble().isFinite()) out += Observation(key, path, time, value.toDouble(),
                unit = metricUnit(source, path), resolution = resolution)
            is Duration -> out += Observation(key, path, time, value.toMillis() / 60000.0, unit = "min", resolution = resolution)
            is String -> if (value.length <= 500) out += Observation(key, path, time, text = value, resolution = resolution)
            is Enum<*> -> out += Observation(key, path, time, text = value.name, resolution = resolution)
            is Boolean -> out += Observation(key, path, time, text = value.toString(), resolution = resolution)
            is List<*> -> value.filterNotNull().forEachIndexed { index, item ->
                val sampleTime = item.javaClass.methods.firstOrNull { it.name in setOf("getStartTime", "getTimestamp") && it.parameterCount == 0 }
                    ?.invoke(item) as? Instant ?: time
                flatten(source, path, "$id:$index", sampleTime, item, out, depth + 1, "SDK series sample")
            }
            else -> if (value.javaClass.name.startsWith("com.samsung.android.sdk.health.data.data.entries.")) {
                for (getter in value.javaClass.methods.filter { it.name.startsWith("get") && it.parameterCount == 0 &&
                    it.name !in setOf("getClass", "getStartTime", "getEndTime", "getStartLocalDateTime", "getEndLocalDateTime") }) {
                    val nested = getter.invoke(value) ?: continue
                    val name = getter.name.removePrefix("get").replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()
                    flatten(source, "$path.$name", id, time, nested, out, depth + 1, resolution)
                    if (source == "EXERCISE" && name == "speed" && nested is Number) {
                        com.repforge.healthbridge.data.WorkoutRecordMapper.paceMinutesPerKm(nested.toDouble())?.let { pace ->
                            out += Observation("$id:$path.pace_min_per_km:${time.toEpochMilli()}", "$path.pace_min_per_km",
                                time, pace, unit = "min/km", resolution = "Derived from recorded speed · no pace while stopped")
                        }
                    }
                }
            }
        }
    }
}
