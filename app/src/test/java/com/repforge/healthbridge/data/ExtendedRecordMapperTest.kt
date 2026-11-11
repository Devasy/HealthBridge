package com.repforge.healthbridge.data

import androidx.health.connect.client.records.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ExtendedRecordMapperTest {
    private val start = Instant.parse("2026-10-31T00:00:00Z")
    private fun p(values: Map<String, Double> = emptyMap()) = SourcePoint("uid", start, start.plusSeconds(60),
        start.plusSeconds(120), ZoneOffset.UTC, values)

    @Test fun `hydration preserves milliliters and floor counts preserve fractions`() {
        val water = RecordMapper.map(Metric.WATER, p(mapOf("waterMl" to 250.0))).single() as HydrationRecord
        assertEquals(0.25, water.volume.inLiters, 0.00001)
        val floors = RecordMapper.map(Metric.FLOORS, p(mapOf("floors" to 2.5))).single() as FloorsClimbedRecord
        assertEquals(2.5, floors.floors, 0.0)
    }
    @Test fun `glucose is mmol per liter and continuous samples replace packet average`() {
        val point = p(mapOf("glucose" to 9.0)).copy(samples = listOf(Sample(start, 5.5), Sample(start.plusSeconds(10), 6.0)),
            text = mapOf("meal" to "AFTER_LUNCH", "specimen" to "CAPILLARY"))
        val records = RecordMapper.map(Metric.GLUCOSE, point).filterIsInstance<BloodGlucoseRecord>()
        assertEquals(2, records.size)
        assertEquals(5.5, records[0].level.inMillimolesPerLiter, 0.0)
        assertEquals(BloodGlucoseRecord.RELATION_TO_MEAL_AFTER_MEAL, records[0].relationToMeal)
        assertEquals(BloodGlucoseRecord.SPECIMEN_SOURCE_CAPILLARY_BLOOD, records[0].specimenSource)
        assertNotEquals(records[0].metadata.clientRecordId, records[1].metadata.clientRecordId)
    }
    @Test fun `body temperature is not skin or basal temperature`() {
        val record = RecordMapper.map(Metric.TEMPERATURE, p(mapOf("temperature" to 36.6))).single()
        assertTrue(record is BodyTemperatureRecord)
        assertEquals(36.6, (record as BodyTemperatureRecord).temperature.inCelsius, 0.00001)
    }
    @Test fun `nutrition converts grams milligrams and micrograms independently`() {
        val record = RecordMapper.map(Metric.NUTRITION, p(mapOf("calories" to 350.0, "protein" to 20.0,
            "sodium" to 500.0, "vitamin_a" to 900.0, "vitamin_c" to 30.0))).single() as NutritionRecord
        assertEquals(350.0, record.energy!!.inKilocalories, 0.0)
        assertEquals(20.0, record.protein!!.inGrams, 0.0)
        assertEquals(0.5, record.sodium!!.inGrams, 0.00001)
        assertEquals(0.0009, record.vitaminA!!.inGrams, 0.0000001)
        assertEquals(0.03, record.vitaminC!!.inGrams, 0.00001)
        assertNull(record.totalFat)
    }
    @Test fun `daily activity has no fabricated individual step events`() {
        val point = p(mapOf("steps" to 1200.0)).copy(uid = "daily:2026-10-31", end = start.plusSeconds(86400))
        val record = RecordMapper.map(Metric.STEPS, point).single() as StepsRecord
        assertEquals(1200L, record.count)
        assertEquals(point.end, record.endTime)
        assertTrue(RecordMapper.map(Metric.STEPS, p(mapOf("steps" to 1.5))).isEmpty())
        val records = RecordMapper.map(Metric.ACTIVITY, p(mapOf("activeCalories" to 250.0,
            "totalCalories" to 1800.0, "distance" to 1234.0)))
        assertEquals(250.0, records.filterIsInstance<ActiveCaloriesBurnedRecord>().single().energy.inKilocalories, 0.0)
        assertEquals(1800.0, records.filterIsInstance<TotalCaloriesBurnedRecord>().single().energy.inKilocalories, 0.0)
        assertEquals(1234.0, records.filterIsInstance<DistanceRecord>().single().distance.inMeters, 0.0)
    }
    @Test fun `corrections reuse IDs and increment versions for every new metric`() {
        val points = mapOf(Metric.STEPS to mapOf("steps" to 10.0), Metric.WATER to mapOf("waterMl" to 100.0),
            Metric.GLUCOSE to mapOf("glucose" to 5.0), Metric.FLOORS to mapOf("floors" to 2.0),
            Metric.TEMPERATURE to mapOf("temperature" to 37.0), Metric.NUTRITION to mapOf("calories" to 20.0),
            Metric.ACTIVITY to mapOf("distance" to 20.0), Metric.EXERCISE to emptyMap())
        for ((metric, values) in points) {
            val point = p(values)
            val first = RecordMapper.map(metric, point).first()
            val edit = RecordMapper.map(metric, point.copy(updated = point.updated.plusSeconds(10))).first()
            assertEquals(metric.name, first.metadata.clientRecordId, edit.metadata.clientRecordId)
            assertTrue(metric.name, edit.metadata.clientRecordVersion > first.metadata.clientRecordVersion)
        }
    }
    @Test fun `exercise types map by name and unsupported types use other workout`() {
        val running = RecordMapper.map(Metric.EXERCISE, p().copy(text = mapOf("exercise" to "RUNNING"))).single() as ExerciseSessionRecord
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, running.exerciseType)
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY, ExtendedRecordMapper.exerciseType("STATIONARY_BIKING"))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, ExtendedRecordMapper.exerciseType("SAMSUNG_ONLY_ACTIVITY"))
        assertTrue(RecordMapper.map(Metric.EXERCISE, p().copy(end = start)).isEmpty())
    }
    @Test fun `missing negative and nonfinite readings never become synthetic zeros`() {
        for (metric in ExtendedSamsungReader.metrics - Metric.EXERCISE) assertTrue(metric.name, RecordMapper.map(metric, p()).isEmpty())
        assertTrue(RecordMapper.map(Metric.WATER, p(mapOf("waterMl" to -5.0))).isEmpty())
        assertTrue(RecordMapper.map(Metric.GLUCOSE, p(mapOf("glucose" to Double.NaN))).isEmpty())
        assertTrue(RecordMapper.map(Metric.ACTIVITY, p(mapOf("distance" to Double.POSITIVE_INFINITY))).isEmpty())
    }
}
