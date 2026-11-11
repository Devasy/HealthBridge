package com.repforge.healthbridge.data

import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.*

object ExtendedRecordMapper {
    fun map(metric: Metric, p: SourcePoint): List<Record> {
        if (metric == Metric.EXERCISE) return WorkoutRecordMapper.map(p)
        fun meta(key: String) = Metadata.unknownRecordingMethod(clientRecordId = SyncPolicy.recordId(metric, p.uid, key),
            clientRecordVersion = SyncPolicy.version(p.updated))
        fun number(key: String, range: ClosedFloatingPointRange<Double> = 0.0..Double.MAX_VALUE) =
            p.values[key]?.takeIf { it.isFinite() && it in range }
        fun grams(key: String) = number(key)?.let(Mass::grams)
        fun mg(key: String) = number(key)?.let(Mass::milligrams)
        val stop = p.end?.takeIf { it > p.start } ?: p.start.plusMillis(1)
        return buildList {
            when (metric) {
                Metric.GLUCOSE -> {
                    val samples = p.samples.ifEmpty { number("glucose", 1.0..40.0)?.let { listOf(Sample(p.start, it)) }.orEmpty() }
                    samples.filter { it.value.isFinite() && it.value in 1.0..40.0 && it.time >= p.start &&
                        (p.end == null || it.time <= p.end) }.distinctBy { it.time }.forEach { s ->
                        add(BloodGlucoseRecord(time = s.time, zoneOffset = p.offset, metadata = meta("glucose:${s.time.toEpochMilli()}"),
                            level = BloodGlucose.millimolesPerLiter(s.value),
                            relationToMeal = when {
                                p.text["meal"] == "FASTING" -> BloodGlucoseRecord.RELATION_TO_MEAL_FASTING
                                p.text["meal"]?.startsWith("BEFORE_") == true && p.text["meal"] != "BEFORE_SLEEP" -> BloodGlucoseRecord.RELATION_TO_MEAL_BEFORE_MEAL
                                p.text["meal"]?.startsWith("AFTER_") == true && p.text["meal"] != "AFTER_BED_TIME" -> BloodGlucoseRecord.RELATION_TO_MEAL_AFTER_MEAL
                                p.text["meal"] == "GENERAL" -> BloodGlucoseRecord.RELATION_TO_MEAL_GENERAL
                                else -> BloodGlucoseRecord.RELATION_TO_MEAL_UNKNOWN
                            }, specimenSource = if (p.text["specimen"] == "CAPILLARY") BloodGlucoseRecord.SPECIMEN_SOURCE_CAPILLARY_BLOOD
                                else BloodGlucoseRecord.SPECIMEN_SOURCE_UNKNOWN))
                    }
                }
                Metric.TEMPERATURE -> number("temperature", 0.0..100.0)?.let {
                    add(BodyTemperatureRecord(time = p.start, zoneOffset = p.offset, temperature = Temperature.celsius(it), metadata = meta("temperature")))
                }
                Metric.FLOORS -> number("floors")?.let {
                    add(FloorsClimbedRecord(p.start, p.offset, stop, p.offset, it, meta("floors")))
                }
                Metric.WATER -> number("waterMl", 0.0..100000.0)?.let {
                    add(HydrationRecord(p.start, p.offset, stop, p.offset, Volume.milliliters(it), meta("water")))
                }
                Metric.STEPS -> number("steps", 0.0..1_000_000.0)?.takeIf { it % 1.0 == 0.0 }?.let {
                    add(StepsRecord(p.start, p.offset, stop, p.offset, it.toLong(), meta("steps")))
                }
                Metric.ACTIVITY -> {
                    number("activeCalories")?.let { add(ActiveCaloriesBurnedRecord(p.start, p.offset, stop, p.offset, Energy.kilocalories(it), meta("activeCalories"))) }
                    number("totalCalories")?.let { add(TotalCaloriesBurnedRecord(p.start, p.offset, stop, p.offset, Energy.kilocalories(it), meta("totalCalories"))) }
                    number("distance")?.let { add(DistanceRecord(p.start, p.offset, stop, p.offset, Length.meters(it), meta("distance"))) }
                }
                Metric.EXERCISE -> if (p.end != null && p.end > p.start) add(ExerciseSessionRecord(
                    startTime = p.start, startZoneOffset = p.offset, endTime = p.end, endZoneOffset = p.offset,
                    metadata = meta("session"), exerciseType = exerciseType(p.text["exercise"]), title = p.text["title"]?.takeIf { it.isNotBlank() }))
                Metric.NUTRITION -> if (p.values.values.any { it.isFinite() && it >= 0 }) add(NutritionRecord(
                    startTime = p.start, startZoneOffset = p.offset, endTime = stop, endZoneOffset = p.offset, metadata = meta("nutrition"),
                    name = p.text["title"], mealType = when(p.text["meal_type"]) {
                        "BREAKFAST" -> MealType.MEAL_TYPE_BREAKFAST
                        "LUNCH" -> MealType.MEAL_TYPE_LUNCH
                        "DINNER" -> MealType.MEAL_TYPE_DINNER
                        "MORNING_SNACK", "AFTERNOON_SNACK", "EVENING_SNACK" -> MealType.MEAL_TYPE_SNACK
                        else -> MealType.MEAL_TYPE_UNKNOWN
                    }, energy = number("calories")?.let(Energy::kilocalories),
                    totalFat = grams("total_fat"), saturatedFat = grams("saturated_fat"),
                    polyunsaturatedFat = grams("polysaturated_fat"), monounsaturatedFat = grams("monosaturated_fat"),
                    transFat = grams("trans_fat"), totalCarbohydrate = grams("carbohydrate"),
                    dietaryFiber = grams("dietary_fiber"), sugar = grams("sugar"), protein = grams("protein"),
                    cholesterol = mg("cholesterol"), sodium = mg("sodium"), potassium = mg("potassium"),
                    vitaminA = number("vitamin_a")?.let(Mass::micrograms), vitaminC = mg("vitamin_c"),
                    calcium = mg("calcium"), iron = mg("iron")))
                else -> error("Not an extended metric")
            }
        }
    }
    internal fun exerciseType(name: String?): Int {
        val equivalent = when (name) {
            "STATIONARY_BIKING" -> "BIKING_STATIONARY"
            "POOL_SWIMMING" -> "SWIMMING_POOL"
            "OPEN_WATER_SWIMMING" -> "SWIMMING_OPEN_WATER"
            "TREADMILL" -> "RUNNING_TREADMILL"
            else -> name
        }
        // Match explicit public names; Samsung integer codes are a different enum from Health Connect.
        return try { ExerciseSessionRecord::class.java.getField("EXERCISE_TYPE_$equivalent").getInt(null) }
        catch (_: ReflectiveOperationException) { ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT }
    }
}
