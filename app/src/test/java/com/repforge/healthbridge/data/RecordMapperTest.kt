package com.repforge.healthbridge.data

import androidx.health.connect.client.records.*
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class RecordMapperTest {
    private val start = Instant.parse("2026-10-26T20:00:00Z")
    private fun point(values: Map<String, Double> = emptyMap(), samples: List<Sample> = emptyList(),
        stages: List<Stage> = emptyList()) = SourcePoint("samsung-uid", start, start.plusSeconds(3600),
        start.plusSeconds(7200), ZoneOffset.ofHoursMinutes(5, 30), values, samples, stages)

    @Test fun `body composition preserves units and never substitutes skeletal muscle for lean mass`() {
        val records = RecordMapper.map(Metric.BODY, point(mapOf(
            "weight" to 80.0, "fat" to 20.0, "muscle" to 30.0, "water" to 45.0, "bmr" to 1800.0)))
        assertEquals(4, records.size)
        assertFalse(records.any { it is LeanBodyMassRecord })
        assertEquals(80.0, records.filterIsInstance<WeightRecord>().single().weight.inKilograms, 0.0)
        assertEquals(20.0, records.filterIsInstance<BodyFatRecord>().single().percentage.value, 0.0)
        assertEquals(45.0, records.filterIsInstance<BodyWaterMassRecord>().single().mass.inKilograms, 0.0)
        assertEquals(1800.0, records.filterIsInstance<BasalMetabolicRateRecord>().single().basalMetabolicRate.inKilocaloriesPerDay, 0.001)
    }
    @Test fun `fat free mass is the only lean mass source`() {
        val lean = RecordMapper.map(Metric.BODY, point(mapOf("lean" to 64.0))).single() as LeanBodyMassRecord
        assertEquals(64.0, lean.mass.inKilograms, 0.0)
    }
    @Test fun `absent and invalid values do not become zero health readings`() {
        assertTrue(RecordMapper.map(Metric.BODY, point()).isEmpty())
        assertTrue(RecordMapper.map(Metric.BODY, point(mapOf("weight" to 0.0, "fat" to Double.NaN,
            "lean" to -2.0, "bmr" to Double.POSITIVE_INFINITY))).isEmpty())
    }
    @Test fun `ids remain stable across retries but corrected source readings get a newer version`() {
        val original = point(mapOf("weight" to 80.0))
        val corrected = original.copy(values = mapOf("weight" to 81.0), updated = original.updated.plusSeconds(60))
        val a = RecordMapper.map(Metric.BODY, original).single()
        val b = RecordMapper.map(Metric.BODY, corrected).single()
        assertEquals(a.metadata.clientRecordId, b.metadata.clientRecordId)
        assertTrue(b.metadata.clientRecordVersion > a.metadata.clientRecordVersion)
        assertEquals(a.metadata.clientRecordVersion, RecordMapper.map(Metric.BODY, original).single().metadata.clientRecordVersion)
    }
    @Test fun `distinct Samsung uids at the same timestamp do not collide`() {
        val p = point(mapOf("weight" to 80.0))
        assertNotEquals(RecordMapper.map(Metric.BODY, p).single().metadata.clientRecordId,
            RecordMapper.map(Metric.BODY, p.copy(uid = "other")).single().metadata.clientRecordId)
    }
    @Test fun `skin baseline and deltas reconstruct the original absolute temperatures`() {
        val p = point(mapOf("temperature" to 34.0), listOf(Sample(start, 33.5), Sample(start.plusSeconds(60), 34.5)))
        val record = RecordMapper.map(Metric.SKIN, p).single() as SkinTemperatureRecord
        assertEquals(34.0, record.baseline!!.inCelsius, 0.0)
        assertEquals(-0.5, record.deltas.first().delta.inCelsius, 0.0)
        assertEquals(34.5, record.baseline!!.inCelsius + record.deltas.last().delta.inCelsius, 0.0)
    }
    @Test fun `skin without summary derives explicit baseline from actual samples`() {
        val record = RecordMapper.map(Metric.SKIN, point(samples = listOf(Sample(start, 33.0),
            Sample(start.plusSeconds(60), 35.0)))).single() as SkinTemperatureRecord
        assertEquals(34.0, record.baseline!!.inCelsius, 0.0)
        assertTrue(RecordMapper.map(Metric.SKIN, point()).isEmpty())
    }
    @Test fun `heart series sorts deduplicates and excludes samples outside the interval`() {
        val record = RecordMapper.map(Metric.HEART, point(samples = listOf(
            Sample(start.plusSeconds(20), 65.0), Sample(start, 60.0), Sample(start, 62.0),
            Sample(start.minusSeconds(1), 70.0), Sample(start.plusSeconds(3600), 70.0), Sample(start.plusSeconds(30), -1.0),
        ))).single() as HeartRateRecord
        assertEquals(listOf(60L, 65L), record.samples.map { it.beatsPerMinute })
        assertEquals(ZoneOffset.ofHoursMinutes(5, 30), record.startZoneOffset)
    }
    @Test fun `sleep stages are clipped to session and overlaps normalized`() {
        val record = RecordMapper.map(Metric.SLEEP, point(stages = listOf(
            Stage(start.minusSeconds(60), start.plusSeconds(600), "LIGHT"),
            Stage(start.plusSeconds(500), start.plusSeconds(900), "DEEP"),
            Stage(start.plusSeconds(3000), start.plusSeconds(4000), "REM"),
        ))).single() as SleepSessionRecord
        assertEquals(start, record.stages.first().startTime)
        assertEquals(start.plusSeconds(600), record.stages[1].startTime)
        assertEquals(record.endTime, record.stages.last().endTime)
        assertEquals(SleepSessionRecord.STAGE_TYPE_DEEP, record.stages[1].stage)
    }
    @Test fun `invalid intervals are not invented for sleep or skin`() {
        assertTrue(RecordMapper.map(Metric.SLEEP, point().copy(end = start)).isEmpty())
        assertTrue(RecordMapper.map(Metric.SKIN, point(mapOf("temperature" to 33.0)).copy(end = null)).isEmpty())
    }
    @Test fun `blood pressure requires a complete pair`() {
        assertTrue(RecordMapper.map(Metric.PRESSURE, point(mapOf("systolic" to 120.0))).isEmpty())
        val record = RecordMapper.map(Metric.PRESSURE, point(mapOf("systolic" to 120.0, "diastolic" to 80.0))).single() as BloodPressureRecord
        assertEquals(120.0, record.systolic.inMillimetersOfMercury, 0.0)
    }
    @Test fun `oxygen sample ids are timestamp based so reordering does not duplicate records`() {
        val samples = listOf(Sample(start, 98.0), Sample(start.plusSeconds(60), 97.0))
        assertEquals(RecordMapper.map(Metric.OXYGEN, point(samples = samples)).map { it.metadata.clientRecordId },
            RecordMapper.map(Metric.OXYGEN, point(samples = samples.reversed())).map { it.metadata.clientRecordId })
    }
}
