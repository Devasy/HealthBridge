package com.repforge.healthbridge.data

import java.time.Instant
import java.time.ZoneOffset

enum class Metric(val title: String, val description: String, val defaultEnabled: Boolean = false,
    val localOnly: Boolean = false) {
    ENERGY("Galaxy Energy Score", "Samsung's daily score · shown in this app", true, true),
    SLEEP_SCORE("Samsung Sleep Score", "Samsung's recorded score · no duplicate sleep sessions", true, true),
    BODY("Body composition", "Body fat, weight, fat-free mass, water and BMR", true),
    SKIN("Skin temperature", "Overnight temperature and changes", true),
    SLEEP("Sleep", "Sessions and sleep stages"),
    HEART("Heart rate", "Timestamped heart rate samples"),
    OXYGEN("Blood oxygen", "Oxygen saturation samples", true),
    PRESSURE("Blood pressure", "Systolic and diastolic measurements", true),
    GLUCOSE("Blood glucose", "Spot readings and continuous glucose samples", true),
    TEMPERATURE("Body temperature", "Measured body temperature, separate from skin temperature", true),
    FLOORS("Floors climbed", "Recorded floor counts", true),
    WATER("Water intake", "Recorded drinks and their volumes", true),
    NUTRITION("Nutrition", "Meals, calories, macros and available micronutrients", true),
    EXERCISE("Workouts, routes & streams", "Sessions, GPS routes, speed, cadence, workout HR and power", true),
    STEPS("Steps", "Samsung daily totals; not individual step timestamps", true),
    ACTIVITY("Daily activity", "Daily active calories, total calories and distance", true);
}

/** HR and sleep remain opt-in because Samsung may already export them directly. */
val bridgeMetrics = Metric.entries

data class Insight(val label: String, val value: String, val date: String)

object InsightValues {
    fun valid(values: Map<String, Double>) = values.filter { (key, value) ->
        value.isFinite() && when (key) {
            "energy", "sleepScore", "fat", "skeletalMusclePercent", "fatFreePercent" -> value in 0.0..100.0
            "bmi" -> value > 0 && value <= 150
            else -> value > 0
        }
    }
    val fields = linkedMapOf(
        "energy" to ("Galaxy Energy Score" to "/100"),
        "sleepScore" to ("Samsung Sleep Score" to "/100"),
        "skeletalMuscleMass" to ("Skeletal muscle mass" to "kg"),
        "skeletalMusclePercent" to ("Skeletal muscle" to "%"),
        "fatMass" to ("Body fat mass" to "kg"),
        "fat" to ("Body fat" to "%"),
        "lean" to ("Fat-free mass" to "kg"),
        "water" to ("Body water" to "L"),
        "bmi" to ("Body mass index" to ""),
        "bmr" to ("Basal metabolic rate" to "kcal/day"),
        "weight" to ("Weight" to "kg"),
    )
}

data class Sample(val time: Instant, val value: Double)
data class Stage(val start: Instant, val end: Instant, val kind: String)
data class RoutePoint(val time: Instant, val latitude: Double, val longitude: Double,
    val altitude: Double? = null, val accuracy: Double? = null)
data class SourcePoint(
    val uid: String,
    val start: Instant,
    val end: Instant?,
    val updated: Instant,
    val offset: ZoneOffset?,
    val values: Map<String, Double> = emptyMap(),
    val samples: List<Sample> = emptyList(),
    val stages: List<Stage> = emptyList(),
    val text: Map<String, String> = emptyMap(),
    val series: Map<String, List<Sample>> = emptyMap(),
    val route: List<RoutePoint> = emptyList(),
)

object SyncPolicy {
    const val PAGE_SIZE = 100
    const val WRITE_BATCH_SIZE = 100
    fun start(now: Instant, backfillDays: Int, checkpoint: Instant?): Instant {
        val earliest = now.minusSeconds(backfillDays.toLong() * 86_400)
        return checkpoint?.minusSeconds(2 * 86_400)?.coerceAtLeast(earliest) ?: earliest
    }
    fun recordId(metric: Metric, uid: String, field: String) = "shealth:${metric.name}:$uid:$field"
    // Source update time, rather than a fixed version, lets corrected readings replace old ones.
    fun version(updated: Instant) = updated.toEpochMilli().coerceAtLeast(1)
}
