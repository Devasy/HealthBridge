package com.repforge.healthbridge.data

import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.data.*
import com.samsung.android.sdk.health.data.request.*
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import java.lang.reflect.Modifier
import java.time.*

/** Public SDK fields only. Daily aggregates retain their actual resolution. */
class ExtendedSamsungReader(private val store: HealthDataStore) {
    suspend fun page(metric: Metric, start: Instant, end: Instant, token: String?): SourcePage {
        if (metric == Metric.STEPS || metric == Metric.ACTIVITY) return daily(metric, start, end, token)
        if (metric == Metric.EXERCISE) {
            val permission = Permission.of(DataTypes.EXERCISE_LOCATION, AccessType.READ)
            val routeAllowed = permission in store.getGrantedPermissions(setOf(permission))
            val response = WorkoutPages(store).read(start, end, token)
            return SourcePage(response.points.flatMap { decode(metric, it, routeAllowed) }, response.next, response.identities)
        }
        val type: DataType.Readable<HealthDataPoint, ReadDataRequest.DualTimeBuilder<HealthDataPoint>> = when (metric) {
            Metric.GLUCOSE -> DataTypes.BLOOD_GLUCOSE
            Metric.TEMPERATURE -> DataTypes.BODY_TEMPERATURE
            Metric.FLOORS -> DataTypes.FLOORS_CLIMBED
            Metric.WATER -> DataTypes.WATER_INTAKE
            Metric.NUTRITION -> DataTypes.NUTRITION
            Metric.EXERCISE -> DataTypes.EXERCISE
            else -> error("Not an extended reader")
        }
        val builder = type.readDataRequestBuilder
        if (token != null) builder.setPageToken(token) else builder
            .setInstantTimeFilter(InstantTimeFilter.of(start, end))
            .setPageSize(if (metric == Metric.EXERCISE) 1 else SyncPolicy.PAGE_SIZE).setOrdering(Ordering.ASC)
        val response = store.readData(builder.build())
        val routeAllowed = if (metric == Metric.EXERCISE) {
            val permission = Permission.of(DataTypes.EXERCISE_LOCATION, AccessType.READ)
            permission in store.getGrantedPermissions(setOf(permission))
        } else false
        return SourcePage(response.dataList.flatMap { decode(metric, it, routeAllowed) }, response.pageToken?.takeIf { it.isNotBlank() },
            response.dataList.map { "${it.uid}:${it.updateTime}" })
    }

    private fun decode(metric: Metric, p: HealthDataPoint, routeAllowed: Boolean): List<SourcePoint> {
        val values = mutableMapOf<String, Double>()
        val text = mutableMapOf<String, String>()
        var samples = emptyList<Sample>()
        fun number(key: String, n: Number?) { n?.let { values[key] = it.toDouble() } }
        when (metric) {
            Metric.GLUCOSE -> {
                number("glucose", p.getValue(DataType.BloodGlucoseType.GLUCOSE_LEVEL))
                samples = p.getValue(DataType.BloodGlucoseType.SERIES_DATA).orEmpty().map { Sample(it.timestamp, it.glucose.toDouble()) }
                p.getValue(DataType.BloodGlucoseType.MEAL_STATUS)?.let { text["meal"] = it.name }
                p.getValue(DataType.BloodGlucoseType.SAMPLE_SOURCE_TYPE)?.let { text["specimen"] = it.name }
            }
            Metric.TEMPERATURE -> number("temperature", p.getValue(DataType.BodyTemperatureType.BODY_TEMPERATURE))
            Metric.FLOORS -> number("floors", p.getValue(DataType.FloorsClimbedType.FLOOR))
            Metric.WATER -> number("waterMl", p.getValue(DataType.WaterIntakeType.AMOUNT))
            Metric.NUTRITION -> {
                for (field in DataType.NutritionType::class.java.fields.filter { Modifier.isStatic(it.modifiers) && it.type == Field::class.java }) {
                    @Suppress("UNCHECKED_CAST")
                    val v = p.getValue(field.get(null) as Field<Any>) ?: continue
                    if (v is Number) number(field.name.lowercase(), v) else text[field.name.lowercase()] = (v as? Enum<*>)?.name ?: v.toString()
                }
            }
            Metric.EXERCISE -> return p.getValue(DataType.ExerciseType.SESSIONS).orEmpty().map { session ->
                val logs = session.log.orEmpty()
                fun stream(value: (com.samsung.android.sdk.health.data.data.entries.ExerciseLog) -> Float?) =
                    logs.mapNotNull { entry -> value(entry)?.let { Sample(entry.timestamp, it.toDouble()) } }
                SourcePoint("${p.uid}:session:${session.startTime.toEpochMilli()}", session.startTime, session.endTime,
                    p.updateTime ?: p.startTime, p.zoneOffset,
                    values = buildMap {
                        session.distance?.let { put("distanceMeters", it.toDouble()) }
                        put("caloriesKcal", session.calories.toDouble())
                        put("durationSeconds", session.duration.toMillis() / 1000.0)
                        session.vo2Max?.let { put("vo2Max", it.toDouble()) }
                    },
                    text = mapOf("exercise" to session.exerciseType.name,
                        "title" to (session.customTitle ?: p.getValue(DataType.ExerciseType.CUSTOM_TITLE).orEmpty()),
                        "routeReadAllowed" to routeAllowed.toString()),
                    series = mapOf("heart" to stream { it.heartRate }, "speed" to stream { it.speed },
                        "cadence" to stream { it.cadence }, "power" to stream { it.power }),
                    route = if (routeAllowed) session.route.orEmpty().map { RoutePoint(it.timestamp,
                        it.latitude.toDouble(), it.longitude.toDouble(), it.altitude?.toDouble(), it.accuracy?.toDouble()) }
                        else emptyList())
            }
            else -> error("Not a readable extended metric")
        }
        return listOf(SourcePoint(p.uid, p.startTime, p.endTime, p.updateTime ?: p.startTime, p.zoneOffset,
            values = values, samples = samples, text = text))
    }

    private suspend fun daily(metric: Metric, start: Instant, end: Instant, token: String?): SourcePage {
        val zone = ZoneId.systemDefault()
        // One day per transfer page keeps IPC and memory bounded, and IDs stable across an evolving day.
        val date = token?.let(LocalDate::parse) ?: start.atZone(zone).toLocalDate()
        val from = date.atStartOfDay(zone).toInstant()
        if (from >= end) return SourcePage(emptyList(), null)
        val stop = minOf(date.plusDays(1).atStartOfDay(zone).toInstant(), end)
        val filter = LocalTimeFilter.of(from.atZone(zone).toLocalDateTime(), stop.atZone(zone).toLocalDateTime())
        val values = mutableMapOf<String, Double>()
        suspend fun <T : Any> total(key: String, op: AggregateOperation<T, AggregateRequest.LocalTimeBuilder<T>>) {
            val result = store.aggregateData(op.requestBuilder.setLocalTimeFilter(filter).build())
            // Ungrouped request returns one all-source total; do not add per-device duplicates.
            check(result.pageToken.isNullOrBlank() && result.dataList.size <= 1) { "Unexpected aggregate pagination" }
            (result.dataList.firstOrNull()?.value as? Number)?.let { values[key] = it.toDouble() }
        }
        if (metric == Metric.STEPS) total("steps", DataType.StepsType.TOTAL) else {
            total("activeCalories", DataType.ActivitySummaryType.TOTAL_ACTIVE_CALORIES_BURNED)
            total("totalCalories", DataType.ActivitySummaryType.TOTAL_CALORIES_BURNED)
            total("distance", DataType.ActivitySummaryType.TOTAL_DISTANCE)
        }
        val point = SourcePoint("daily:$date", from, stop, end, from.atZone(zone).offset, values)
        val next = date.plusDays(1).takeIf { it.atStartOfDay(zone).toInstant() < end }?.toString()
        return SourcePage(listOf(point), next)
    }

    companion object {
        val metrics = setOf(Metric.GLUCOSE, Metric.TEMPERATURE, Metric.FLOORS, Metric.WATER,
            Metric.NUTRITION, Metric.EXERCISE, Metric.STEPS, Metric.ACTIVITY)
    }
}
