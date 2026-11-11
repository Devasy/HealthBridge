package com.repforge.healthbridge.data

import androidx.health.connect.client.records.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class WorkoutRecordMapperTest {
    private val start = Instant.parse("2026-11-01T06:00:00Z")
    private fun point(kind: String = "RUNNING") = SourcePoint("run", start, start.plusSeconds(600), start.plusSeconds(1000),
        ZoneOffset.UTC, text = mapOf("exercise" to kind, "title" to "Morning run"))

    @Test fun `route retains actual coordinates altitude and timestamps and drops invalid points`() {
        val point = point().copy(route = listOf(RoutePoint(start.plusSeconds(2), 12.0, 77.0, 850.0, 3.0),
            RoutePoint(start, 12.1, 77.1), RoutePoint(start, 99.0, 77.0),
            RoutePoint(start.minusSeconds(1), 12.0, 77.0), RoutePoint(start.plusSeconds(600), 12.0, 77.0)))
        val session = WorkoutRecordMapper.map(point).single() as ExerciseSessionRecord
        val route = (session.exerciseRouteResult as ExerciseRouteResult.Data).exerciseRoute.route
        assertEquals(2, route.size)
        assertEquals(start, route[0].time)
        assertEquals(77.0, route[1].longitude, 0.0)
        assertEquals(850.0, route[1].altitude!!.inMeters, 0.0)
        assertNull(route[0].altitude)
        assertEquals(3.0, route[1].horizontalAccuracy!!.inMeters, 0.0)
    }
    @Test fun `denied route write omits GPS without losing session or streams`() {
        val point = point().copy(route = listOf(RoutePoint(start, 12.0, 77.0)),
            series = mapOf("speed" to listOf(Sample(start, 3.0))))
        val records = WorkoutRecordMapper.map(point, allowRoute = false)
        assertTrue(records.filterIsInstance<ExerciseSessionRecord>().single().exerciseRouteResult is ExerciseRouteResult.NoData)
        assertEquals(1, records.filterIsInstance<SpeedRecord>().size)
    }
    @Test fun `source route access loss can preserve existing session while syncing streams`() {
        val point = point().copy(series = mapOf("heart" to listOf(Sample(start, 150.0))))
        val records = WorkoutRecordMapper.map(point, writeSession = false)
        assertFalse(records.any { it is ExerciseSessionRecord })
        assertEquals(150L, records.filterIsInstance<HeartRateRecord>().single().samples.single().beatsPerMinute)
    }
    @Test fun `running exports measured speed cadence HR and power with original times`() {
        val time = start.plusSeconds(12)
        val point = point().copy(series = mapOf("speed" to listOf(Sample(time, 4.0)),
            "cadence" to listOf(Sample(time, 176.0)), "heart" to listOf(Sample(time, 151.0)),
            "power" to listOf(Sample(time, 250.0))))
        val records = WorkoutRecordMapper.map(point)
        assertEquals(5, records.size)
        assertEquals(4.0, records.filterIsInstance<SpeedRecord>().single().samples.single().speed.inMetersPerSecond, 0.0)
        assertEquals(time, records.filterIsInstance<SpeedRecord>().single().samples.single().time)
        assertTrue(records.filterIsInstance<SpeedRecord>().single().endTime.toEpochMilli() >
            records.filterIsInstance<SpeedRecord>().single().startTime.toEpochMilli())
        assertEquals(176.0, records.filterIsInstance<StepsCadenceRecord>().single().samples.single().rate, 0.0)
        assertEquals(250.0, records.filterIsInstance<PowerRecord>().single().samples.single().power.inWatts, 0.0)
    }
    @Test fun `cycling cadence is never written as step cadence and rowing cadence stays unmapped`() {
        val series = mapOf("cadence" to listOf(Sample(start, 80.0)))
        val records = WorkoutRecordMapper.map(point("BIKING").copy(series = series))
        assertTrue(records.any { it is CyclingPedalingCadenceRecord })
        assertFalse(records.any { it is StepsCadenceRecord })
        assertEquals(1, WorkoutRecordMapper.map(point("ROWING").copy(series = series)).size)
    }
    @Test fun `pace derives from positive speed only and stationary speed remains a real zero`() {
        assertEquals(5.0, WorkoutRecordMapper.paceMinutesPerKm(10.0 / 3.0)!!, 0.00001)
        for (value in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) assertNull(WorkoutRecordMapper.paceMinutesPerKm(value))
        val records = WorkoutRecordMapper.map(point().copy(series = mapOf("speed" to listOf(Sample(start, 0.0)))))
        assertEquals(0.0, records.filterIsInstance<SpeedRecord>().single().samples.single().speed.inMetersPerSecond, 0.0)
    }
    @Test fun `series batches are bounded sorted deduplicated and reject invalid samples`() {
        val samples = (0..1200).map { Sample(start.plusMillis(it.toLong()), 3.0) }.reversed() +
            listOf(Sample(start, 3.0), Sample(start.minusSeconds(1), 3.0), Sample(start.plusSeconds(5), Double.NaN))
        val records = WorkoutRecordMapper.map(point().copy(series = mapOf("speed" to samples))).filterIsInstance<SpeedRecord>()
        assertEquals(1201, records.sumOf { it.samples.size })
        assertTrue(records.all { it.samples.size <= 500 && it.samples.zipWithNext().all { (a,b) -> a.time < b.time } })
        assertEquals(records.size, records.map { it.metadata.clientRecordId }.toSet().size)
    }
    @Test fun `retry identities are stable correction versions advance and upgrade supersedes old shell`() {
        val point = point().copy(series = mapOf("speed" to listOf(Sample(start, 3.0))))
        val first = WorkoutRecordMapper.map(point)
        val retry = WorkoutRecordMapper.map(point)
        val corrected = WorkoutRecordMapper.map(point.copy(updated = point.updated.plusSeconds(1)))
        assertEquals(first.map { it.metadata.clientRecordId }, retry.map { it.metadata.clientRecordId })
        assertEquals(first.map { it.metadata.clientRecordId }, corrected.map { it.metadata.clientRecordId })
        assertTrue(first.zip(corrected).all { (a,b) -> b.metadata.clientRecordVersion > a.metadata.clientRecordVersion })
        assertTrue(first.first().metadata.clientRecordVersion > SyncPolicy.version(point.updated))
        assertEquals(SyncPolicy.recordId(Metric.EXERCISE, "run", "session"), first.first().metadata.clientRecordId)
    }
    @Test fun `summaries and absent streams do not generate invented samples`() {
        val records = WorkoutRecordMapper.map(point().copy(values = mapOf("meanSpeed" to 3.0, "meanHeartRate" to 150.0)))
        assertEquals(1, records.size)
        assertTrue(records.single() is ExerciseSessionRecord)
        assertTrue(WorkoutRecordMapper.map(point().copy(end = start)).isEmpty())
    }
}
