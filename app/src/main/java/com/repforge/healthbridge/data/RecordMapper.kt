package com.repforge.healthbridge.data

import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.*
import java.time.Instant

/** Only equivalent measurements are exported. No inferred HRV, resting HR or muscle-as-lean mass. */
object RecordMapper {
    fun map(metric: Metric, p: SourcePoint): List<Record> {
        if (metric in ExtendedSamsungReader.metrics) return ExtendedRecordMapper.map(metric, p)
        fun meta(field: String) = Metadata.unknownRecordingMethod(
            clientRecordId = SyncPolicy.recordId(metric, p.uid, field),
            clientRecordVersion = SyncPolicy.version(p.updated),
        )
        fun value(key: String, range: ClosedFloatingPointRange<Double>): Double? =
            p.values[key]?.takeIf { it.isFinite() && it in range }
        val end = p.end?.takeIf { it > p.start }
        return buildList {
            when (metric) {
                Metric.ENERGY, Metric.SLEEP_SCORE -> Unit // Samsung-only scores have no Health Connect record.
                Metric.GLUCOSE, Metric.TEMPERATURE, Metric.FLOORS, Metric.WATER, Metric.NUTRITION,
                Metric.EXERCISE, Metric.STEPS, Metric.ACTIVITY -> error("Handled by extended mapper")
                Metric.BODY -> {
                    value("weight", 0.001..1000.0)?.let {
                        add(WeightRecord(p.start, p.offset, Mass.kilograms(it), meta("weight")))
                    }
                    value("fat", 0.0..100.0)?.let {
                        add(BodyFatRecord(p.start, p.offset, Percentage(it), meta("fat")))
                    }
                    value("lean", 0.001..1000.0)?.let {
                        add(LeanBodyMassRecord(p.start, p.offset, Mass.kilograms(it), meta("lean")))
                    }
                    value("water", 0.001..1000.0)?.let {
                        // SDK reports litres of body water; 1 L water ≈ 1 kg.
                        add(BodyWaterMassRecord(p.start, p.offset, Mass.kilograms(it), meta("water")))
                    }
                    value("bmr", 0.001..10000.0)?.let {
                        add(BasalMetabolicRateRecord(p.start, p.offset, Power.kilocaloriesPerDay(it), meta("bmr")))
                    }
                }
                Metric.SKIN -> if (end != null) {
                    val samples = p.samples.valid(p.start, end, 0.0..100.0)
                    // The absolute mean is our explicit baseline; deltas reconstruct source temperatures.
                    val baseline = value("temperature", 0.0..100.0) ?: samples.map { it.value }.average()
                    if (baseline.isFinite()) add(SkinTemperatureRecord(
                        startTime = p.start, startZoneOffset = p.offset,
                        endTime = end, endZoneOffset = p.offset,
                        baseline = Temperature.celsius(baseline),
                        deltas = samples.map {
                            SkinTemperatureRecord.Delta(it.time, TemperatureDelta.celsius(it.value - baseline))
                        },
                        measurementLocation = SkinTemperatureRecord.MEASUREMENT_LOCATION_WRIST,
                        metadata = meta("temperature"),
                    ))
                }
                Metric.HEART -> {
                    val stop = end ?: p.start.plusMillis(1)
                    val samples = (p.samples.ifEmpty {
                        value("heart", 1.0..300.0)?.let { listOf(Sample(p.start, it)) } ?: emptyList()
                    }).valid(p.start, stop, 1.0..300.0)
                    if (samples.isNotEmpty()) add(HeartRateRecord(
                        p.start, p.offset, stop, p.offset,
                        samples.map { HeartRateRecord.Sample(it.time, it.value.toLong()) }, meta("heart"),
                    ))
                }
                Metric.OXYGEN -> {
                    val samples = p.samples.ifEmpty {
                        value("oxygen", 0.0..100.0)?.let { listOf(Sample(p.start, it)) } ?: emptyList()
                    }.filter { it.value.isFinite() && it.value in 0.0..100.0 }
                        .distinctBy { it.time }.sortedBy { it.time }
                    samples.forEach { s -> add(OxygenSaturationRecord(
                        s.time, p.offset, Percentage(s.value), meta("oxygen:${s.time.toEpochMilli()}"),
                    )) }
                }
                Metric.PRESSURE -> {
                    val systolic = value("systolic", 20.0..200.0)
                    val diastolic = value("diastolic", 10.0..180.0)
                    if (systolic != null && diastolic != null) add(BloodPressureRecord(
                        time = p.start, zoneOffset = p.offset,
                        systolic = Pressure.millimetersOfMercury(systolic),
                        diastolic = Pressure.millimetersOfMercury(diastolic), metadata = meta("pressure"),
                    ))
                }
                Metric.SLEEP -> if (end != null) {
                    var previousEnd = p.start
                    val stages = p.stages.sortedBy { it.start }.mapNotNull { s ->
                        val start = maxOf(s.start, p.start, previousEnd)
                        val stop = minOf(s.end, end)
                        if (stop <= start) null else {
                            previousEnd = stop
                            SleepSessionRecord.Stage(start, stop, when (s.kind) {
                                "AWAKE" -> SleepSessionRecord.STAGE_TYPE_AWAKE
                                "LIGHT" -> SleepSessionRecord.STAGE_TYPE_LIGHT
                                "DEEP" -> SleepSessionRecord.STAGE_TYPE_DEEP
                                "REM" -> SleepSessionRecord.STAGE_TYPE_REM
                                else -> SleepSessionRecord.STAGE_TYPE_UNKNOWN
                            })
                        }
                    }
                    add(SleepSessionRecord(
                        startTime = p.start, startZoneOffset = p.offset,
                        endTime = end, endZoneOffset = p.offset, stages = stages,
                        metadata = meta("sleep"),
                    ))
                }
            }
        }
    }

    private fun List<Sample>.valid(start: Instant, end: Instant, range: ClosedFloatingPointRange<Double>) =
        filter { it.time >= start && it.time < end && it.value.isFinite() && it.value in range }
            .distinctBy { it.time }.sortedBy { it.time }
}
