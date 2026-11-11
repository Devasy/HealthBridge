package com.repforge.healthbridge.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*

class HealthGateway(private val context: Context) {
    val available get() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    val client by lazy { HealthConnectClient.getOrCreate(context) }
    val skinAvailable get() = available && client.features.getFeatureStatus(
        HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE,
    ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    fun permissions(metric: Metric): Set<String> = when (metric) {
        Metric.ENERGY, Metric.SLEEP_SCORE -> emptySet()
        Metric.BODY -> setOf(WeightRecord::class, BodyFatRecord::class, LeanBodyMassRecord::class,
            BodyWaterMassRecord::class, BasalMetabolicRateRecord::class)
        Metric.SKIN -> if (skinAvailable) setOf(SkinTemperatureRecord::class) else emptySet()
        Metric.SLEEP -> setOf(SleepSessionRecord::class)
        Metric.HEART -> setOf(HeartRateRecord::class)
        Metric.OXYGEN -> setOf(OxygenSaturationRecord::class)
        Metric.PRESSURE -> setOf(BloodPressureRecord::class)
        Metric.GLUCOSE -> setOf(BloodGlucoseRecord::class)
        Metric.TEMPERATURE -> setOf(BodyTemperatureRecord::class)
        Metric.FLOORS -> setOf(FloorsClimbedRecord::class)
        Metric.WATER -> setOf(HydrationRecord::class)
        Metric.NUTRITION -> setOf(NutritionRecord::class)
        Metric.EXERCISE -> setOf(ExerciseSessionRecord::class, HeartRateRecord::class, SpeedRecord::class,
            StepsCadenceRecord::class, CyclingPedalingCadenceRecord::class, PowerRecord::class)
        Metric.STEPS -> setOf(StepsRecord::class)
        Metric.ACTIVITY -> setOf(ActiveCaloriesBurnedRecord::class, TotalCaloriesBurnedRecord::class, DistanceRecord::class)
    }.map { HealthPermission.getWritePermission(it) }.toSet().let {
        if (metric == Metric.EXERCISE) it + HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE else it
    }
    fun permissions(metrics: Set<Metric>) = metrics.flatMap { permissions(it) }.toSet()
}
