package com.repforge.healthbridge.data

import android.app.Activity
import android.content.Context
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.data.HealthDataPoint
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.*
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SamsungReader(context: Context) {
    private val store by lazy { HealthDataService.getStore(context.applicationContext) }

    private fun type(metric: Metric): DataType = when (metric) {
        Metric.ENERGY -> DataTypes.ENERGY_SCORE
        Metric.SLEEP_SCORE -> DataTypes.SLEEP
        Metric.BODY -> DataTypes.BODY_COMPOSITION
        Metric.SKIN -> DataTypes.SKIN_TEMPERATURE
        Metric.SLEEP -> DataTypes.SLEEP
        Metric.HEART -> DataTypes.HEART_RATE
        Metric.OXYGEN -> DataTypes.BLOOD_OXYGEN
        Metric.PRESSURE -> DataTypes.BLOOD_PRESSURE
        Metric.GLUCOSE -> DataTypes.BLOOD_GLUCOSE
        Metric.TEMPERATURE -> DataTypes.BODY_TEMPERATURE
        Metric.FLOORS -> DataTypes.FLOORS_CLIMBED
        Metric.WATER -> DataTypes.WATER_INTAKE
        Metric.NUTRITION -> DataTypes.NUTRITION
        Metric.EXERCISE -> DataTypes.EXERCISE
        Metric.STEPS -> DataTypes.STEPS
        Metric.ACTIVITY -> DataTypes.ACTIVITY_SUMMARY
    }
    fun permissions(metrics: Set<Metric>) = metrics.map { Permission.of(type(it), AccessType.READ) }.toSet()
    suspend fun routeGranted(): Boolean = withContext(Dispatchers.IO) {
        val permission = Permission.of(DataTypes.EXERCISE_LOCATION, AccessType.READ)
        permission in store.getGrantedPermissions(setOf(permission))
    }
    suspend fun granted(metrics: Set<Metric>): Set<Metric> = withContext(Dispatchers.IO) {
        val granted = store.getGrantedPermissions(permissions(metrics))
        metrics.filter { Permission.of(type(it), AccessType.READ) in granted }.toSet()
    }
    suspend fun request(metrics: Set<Metric>, activity: Activity) {
        store.requestPermissions(permissions(metrics), activity)
    }

    suspend fun page(metric: Metric, start: Instant, end: Instant, token: String?): SourcePage =
        withContext(Dispatchers.IO) {
            if (metric in ExtendedSamsungReader.metrics)
                return@withContext ExtendedSamsungReader(store).page(metric, start, end, token)
            if (metric == Metric.ENERGY) {
                val zone = ZoneId.systemDefault()
                val builder = DataTypes.ENERGY_SCORE.readDataRequestBuilder
                    .setLocalDateFilter(LocalDateFilter.of(start.atZone(zone).toLocalDate(),
                        end.atZone(zone).toLocalDate(), true, true))
                    .setPageSize(SyncPolicy.PAGE_SIZE).setOrdering(Ordering.ASC)
                if (token != null) builder.setPageToken(token)
                val response = store.readData(builder.build())
                return@withContext SourcePage(response.dataList.flatMap { decode(metric, it) },
                    response.pageToken?.takeIf { it.isNotBlank() })
            }
            val builder = when (metric) {
                Metric.ENERGY -> error("Energy score uses local dates")
                Metric.SLEEP_SCORE -> DataTypes.SLEEP.readDataRequestBuilder
                Metric.BODY -> DataTypes.BODY_COMPOSITION.readDataRequestBuilder
                Metric.SKIN -> DataTypes.SKIN_TEMPERATURE.readDataRequestBuilder
                Metric.SLEEP -> DataTypes.SLEEP.readDataRequestBuilder
                Metric.HEART -> DataTypes.HEART_RATE.readDataRequestBuilder
                Metric.OXYGEN -> DataTypes.BLOOD_OXYGEN.readDataRequestBuilder
                Metric.PRESSURE -> DataTypes.BLOOD_PRESSURE.readDataRequestBuilder
                else -> error("Handled by extended reader")
            }.setInstantTimeFilter(InstantTimeFilter.of(start, end))
                .setPageSize(SyncPolicy.PAGE_SIZE).setOrdering(Ordering.ASC)
            if (token != null) builder.setPageToken(token)
            val response = store.readData(builder.build())
            SourcePage(response.dataList.flatMap { decode(metric, it) }, response.pageToken?.takeIf { it.isNotBlank() })
        }

    private fun decode(metric: Metric, p: HealthDataPoint): List<SourcePoint> {
        if (metric == Metric.SLEEP) {
            val sessions = p.getValue(DataType.SleepType.SESSIONS).orEmpty()
            if (sessions.isNotEmpty()) return sessions.map { session ->
                // Preserve separate overnight/nap sessions; gaps between them are not sleep.
                SourcePoint("${p.uid}:session:${session.startTime.toEpochMilli()}", session.startTime,
                    session.endTime, p.updateTime ?: p.startTime, p.zoneOffset,
                    stages = session.stages.orEmpty().map { Stage(it.startTime, it.endTime, it.stage.name) })
            }
        }
        val values = mutableMapOf<String, Double>()
        fun number(key: String, value: Number?) { if (value != null) values[key] = value.toDouble() }
        var samples = emptyList<Sample>()
        var stages = emptyList<Stage>()
        when (metric) {
            Metric.ENERGY -> number("energy", p.getValue(DataType.EnergyScoreType.ENERGY_SCORE))
            Metric.SLEEP_SCORE -> number("sleepScore", p.getValue(DataType.SleepType.SLEEP_SCORE))
            Metric.BODY -> {
                number("weight", p.getValue(DataType.BodyCompositionType.WEIGHT))
                number("fat", p.getValue(DataType.BodyCompositionType.BODY_FAT))
                number("lean", p.getValue(DataType.BodyCompositionType.FAT_FREE_MASS))
                number("water", p.getValue(DataType.BodyCompositionType.TOTAL_BODY_WATER))
                number("bmr", p.getValue(DataType.BodyCompositionType.BASAL_METABOLIC_RATE))
                number("skeletalMuscleMass", p.getValue(DataType.BodyCompositionType.SKELETAL_MUSCLE_MASS))
                number("skeletalMusclePercent", p.getValue(DataType.BodyCompositionType.SKELETAL_MUSCLE))
                number("fatMass", p.getValue(DataType.BodyCompositionType.BODY_FAT_MASS))
                number("fatFreePercent", p.getValue(DataType.BodyCompositionType.FAT_FREE))
                number("bmi", p.getValue(DataType.BodyCompositionType.BODY_MASS_INDEX))
            }
            Metric.SKIN -> {
                number("temperature", p.getValue(DataType.SkinTemperatureType.SKIN_TEMPERATURE))
                samples = p.getValue(DataType.SkinTemperatureType.SERIES_DATA).orEmpty().map {
                    Sample(it.startTime, it.skinTemperature.toDouble())
                }
            }
            Metric.SLEEP -> stages = p.getValue(DataType.SleepType.SESSIONS).orEmpty().flatMap {
                it.stages.orEmpty().map { stage -> Stage(stage.startTime, stage.endTime, stage.stage.name) }
            }
            Metric.HEART -> {
                number("heart", p.getValue(DataType.HeartRateType.HEART_RATE))
                samples = p.getValue(DataType.HeartRateType.SERIES_DATA).orEmpty().map {
                    Sample(it.startTime, it.heartRate.toDouble())
                }
            }
            Metric.OXYGEN -> {
                number("oxygen", p.getValue(DataType.BloodOxygenType.OXYGEN_SATURATION))
                samples = p.getValue(DataType.BloodOxygenType.SERIES_DATA).orEmpty().map {
                    Sample(it.startTime, it.oxygenSaturation.toDouble())
                }
            }
            Metric.PRESSURE -> {
                number("systolic", p.getValue(DataType.BloodPressureType.SYSTOLIC))
                number("diastolic", p.getValue(DataType.BloodPressureType.DIASTOLIC))
            }
            else -> error("Handled by extended reader")
        }
        return listOf(SourcePoint(p.uid, p.startTime, p.endTime, p.updateTime ?: p.startTime, p.zoneOffset, values, samples, stages))
    }
}

data class SourcePage(val points: List<SourcePoint>, val next: String?,
    val identities: List<String> = points.map { "${it.uid}:${it.updated}" })
