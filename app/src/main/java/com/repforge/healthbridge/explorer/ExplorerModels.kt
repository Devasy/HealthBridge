package com.repforge.healthbridge.explorer

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class ChartRange(val label: String, val days: Long) {
    DAY("1 day", 1), WEEK("7 days", 7), MONTH("30 days", 30), YEAR("1 year", 365);
    fun start(anchor: LocalDate, zone: ZoneId): Instant =
        (if (this == YEAR) anchor.minusYears(1).plusDays(1) else anchor.minusDays(days - 1)).atStartOfDay(zone).toInstant()
}
data class Observation(val id: String, val field: String, val time: Instant,
    val value: Double? = null, val text: String? = null, val unit: String = "",
    val resolution: String = "Recorded value", val origin: String = "Samsung SDK")
data class ChartPoint(val time: Instant, val min: Double, val max: Double, val median: Double,
    val mean: Double, val count: Int)
data class FieldInfo(val key: String, val unit: String, val count: Int, val numericCount: Int, val resolution: String)
data class SourceStatus(val key: String, val title: String, val detail: String)
data class RawRow(val time: Instant, val value: String, val origin: String)
data class ChartData(val points: List<ChartPoint> = emptyList(), val rows: List<RawRow> = emptyList(),
    val count: Int = 0, val min: Double? = null, val max: Double? = null, val mean: Double? = null,
    val median: Double? = null, val unit: String = "", val resolution: String = "")

object Statistics {
    fun point(time: Instant, values: List<Double>): ChartPoint {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val middle = sorted.size / 2
        val median = if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
        return ChartPoint(time, sorted.first(), sorted.last(), median, sorted.average(), sorted.size)
    }
    fun daily(samples: List<Pair<Instant, Double>>, zone: ZoneId): List<ChartPoint> = samples
        .filter { it.second.isFinite() }.groupBy { it.first.atZone(zone).toLocalDate() }.toSortedMap()
        .map { (day, values) -> point(day.atStartOfDay(zone).toInstant(), values.map { it.second }) }
}

fun fieldLabel(key: String) = key.split('.', '_').filter { it.isNotBlank() }
    .joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }

fun metricUnit(source: String, field: String): String {
    val key = field.lowercase()
    return when {
        source == "EXERCISE" && key.endsWith(".pace_min_per_km") -> "min/km"
        source == "EXERCISE" && (key.endsWith(".speed") || key.endsWith(".mean_speed") || key.endsWith(".max_speed")) -> "m/s"
        source == "EXERCISE" && key.endsWith(".heart_rate") -> "bpm"
        source == "EXERCISE" && key.endsWith(".power") -> "W"
        source == "EXERCISE" && key.endsWith(".cadence") -> "movements/min"
        source == "EXERCISE" && (key.endsWith(".latitude") || key.endsWith(".longitude")) -> "°"
        source == "EXERCISE" && (key.endsWith(".altitude") || key.endsWith(".distance")) -> "m"
        source.contains("RESPIRATORY") -> "breaths/min"
        source == "ENERGY_SCORE" || key.endsWith("sleep_score") -> "/100"
        source == "SKIN_TEMPERATURE" || source == "BODY_TEMPERATURE" -> "°C"
        source == "HEART_RATE" -> "bpm"
        source == "BLOOD_OXYGEN" -> "%"
        source == "BLOOD_GLUCOSE" && (key == "glucose_level" || key.endsWith(".glucose")) -> "mmol/L"
        source == "WATER_INTAKE" || source == "WATER_INTAKE_GOAL" -> "mL"
        source == "FLOORS_CLIMBED" -> "floors"
        source == "STEPS" || source == "STEPS_GOAL" -> "steps"
        source == "USER_PROFILE" && key == "height" -> "cm"
        source == "USER_PROFILE" && key == "weight" -> "kg"
        source == "ACTIVITY_SUMMARY" && key == "total_distance" -> "m"
        source == "NUTRITION" && key == "vitamin_a" -> "µg"
        source == "NUTRITION" && key in setOf("cholesterol", "sodium", "potassium", "vitamin_c", "calcium", "iron") -> "mg"
        source == "NUTRITION" && key in setOf("total_fat", "saturated_fat", "polysaturated_fat", "monosaturated_fat", "trans_fat", "carbohydrate", "dietary_fiber", "sugar", "protein") -> "g"
        source == "BLOOD_PRESSURE" && (key.endsWith("systolic") || key.endsWith("diastolic") || key.endsWith("mean")) -> "mmHg"
        source == "BODY_COMPOSITION" && (key.endsWith("mass") || key == "weight") -> "kg"
        source == "BODY_COMPOSITION" && key == "total_body_water" -> "L"
        source == "BODY_COMPOSITION" && key in setOf("body_fat", "fat_free", "skeletal_muscle") -> "%"
        key.contains("basal_metabolic_rate") -> "kcal/day"
        key.contains("calories") -> "kcal"
        else -> "" // Unknown units stay unspecified instead of guessing.
    }
}
