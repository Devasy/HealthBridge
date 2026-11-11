package com.repforge.healthbridge.data

import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.*

/** Samsung log values keep their original timestamps. Summaries never become fabricated samples. */
object WorkoutRecordMapper {
    fun paceMinutesPerKm(metersPerSecond: Double): Double? =
        metersPerSecond.takeIf { it.isFinite() && it > 0 }?.let { 1000.0 / (60.0 * it) }?.takeIf { it.isFinite() }

    fun map(p: SourcePoint, allowRoute: Boolean = true, writeSession: Boolean = true): List<Record> {
        val end = p.end?.takeIf { it > p.start } ?: return emptyList()
        fun meta(key: String) = Metadata.unknownRecordingMethod(
            clientRecordId = SyncPolicy.recordId(Metric.EXERCISE, p.uid, key),
            // Supersede the v0.4 session shell even if Samsung's source update time is unchanged.
            clientRecordVersion = SyncPolicy.version(p.updated) + 1)
        return buildList {
            val route = if (!allowRoute) emptyList() else p.route.filter {
                it.time >= p.start && it.time < end && it.latitude.isFinite() && it.latitude in -90.0..90.0 &&
                    it.longitude.isFinite() && it.longitude in -180.0..180.0
            }.distinctBy { it.time }.sortedBy { it.time }.map {
                ExerciseRoute.Location(time = it.time, latitude = it.latitude, longitude = it.longitude,
                    altitude = it.altitude?.takeIf { n -> n.isFinite() }?.let(Length::meters),
                    horizontalAccuracy = it.accuracy?.takeIf { n -> n.isFinite() && n >= 0 }?.let(Length::meters))
            }
            if (writeSession) add(ExerciseSessionRecord(startTime = p.start, startZoneOffset = p.offset,
                endTime = end, endZoneOffset = p.offset, metadata = meta("session"),
                exerciseType = ExtendedRecordMapper.exerciseType(p.text["exercise"]),
                title = p.text["title"]?.takeIf { it.isNotBlank() },
                exerciseRoute = route.takeIf { it.isNotEmpty() }?.let(::ExerciseRoute)))

            val kind = p.text["exercise"].orEmpty()
            val stepCadence = kind in setOf("RUNNING", "TREADMILL", "WALKING", "HIKING", "BACKPACKING")
            // Generic cadence could mean rowing strokes, repetitions, etc. Export only known semantics.
            val cycleCadence = kind in setOf("BIKING", "STATIONARY_BIKING", "MOUNTAIN_BIKING")
            for ((field, range) in mapOf("heart" to 1.0..300.0, "speed" to 0.0..140.0,
                "cadence" to 0.0..10000.0, "power" to 0.0..100000.0)) {
                if (field == "cadence" && !stepCadence && !cycleCadence) continue
                val samples = p.series[field].orEmpty().filter {
                    it.time >= p.start && it.time < end && it.value.isFinite() && it.value in range
                }.distinctBy { it.time }.sortedBy { it.time }
                // Fixed UTC five-minute buckets avoid shifting later records when an earlier sample changes.
                for ((bucket, values) in samples.groupBy { Math.floorDiv(it.time.epochSecond, 300L) }) {
                    values.chunked(500).forEachIndexed { part, chunk ->
                        // Health Connect serializes times to milliseconds. A one-nanosecond
                        // envelope collapses to zero duration for a single-sample bucket.
                        val stop = minOf(end, chunk.last().time.plusMillis(1))
                        val metadata = meta("$field:$bucket:$part")
                        if (field == "cadence") {
                            if (stepCadence) add(StepsCadenceRecord(chunk.first().time, p.offset, stop, p.offset,
                                chunk.map { StepsCadenceRecord.Sample(it.time, it.value) }, metadata))
                            else add(CyclingPedalingCadenceRecord(chunk.first().time, p.offset, stop, p.offset,
                                chunk.map { CyclingPedalingCadenceRecord.Sample(it.time, it.value) }, metadata))
                            return@forEachIndexed
                        }
                        val record: Record = when (field) {
                            "heart" -> HeartRateRecord(chunk.first().time, p.offset, stop, p.offset,
                                chunk.map { HeartRateRecord.Sample(it.time, it.value.toLong()) }, metadata)
                            "speed" -> SpeedRecord(chunk.first().time, p.offset, stop, p.offset,
                                chunk.map { SpeedRecord.Sample(it.time, Velocity.metersPerSecond(it.value)) }, metadata)
                            else -> PowerRecord(chunk.first().time, p.offset, stop, p.offset,
                                chunk.map { PowerRecord.Sample(it.time, Power.watts(it.value)) }, metadata)
                        }
                        add(record)
                    }
                }
            }
        }
    }
}
