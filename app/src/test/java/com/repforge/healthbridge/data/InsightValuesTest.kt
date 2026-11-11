package com.repforge.healthbridge.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class InsightValuesTest {
    @Test fun `scores accept real zero and full scale but reject out of range and nonfinite values`() {
        assertEquals(mapOf("energy" to 0.0, "sleepScore" to 100.0),
            InsightValues.valid(mapOf("energy" to 0.0, "sleepScore" to 100.0)))
        assertTrue(InsightValues.valid(mapOf("energy" to 101.0, "sleepScore" to -1.0, "bmi" to Double.NaN)).isEmpty())
    }
    @Test fun `body composition keeps skeletal muscle distinct from fat free mass`() {
        val values = InsightValues.valid(mapOf("skeletalMuscleMass" to 30.0, "lean" to 60.0, "fatMass" to 20.0, "bmi" to 24.5))
        assertEquals(30.0, values["skeletalMuscleMass"]!!, 0.0)
        assertEquals(60.0, values["lean"]!!, 0.0)
        assertTrue(InsightValues.valid(mapOf("skeletalMuscleMass" to 0.0, "bmi" to -1.0)).isEmpty())
    }
    @Test fun `heart rate and sleep are explicit optional exports`() {
        assertTrue(Metric.HEART in bridgeMetrics)
        assertTrue(Metric.SLEEP in bridgeMetrics)
        assertFalse(Metric.HEART.defaultEnabled)
        assertFalse(Metric.SLEEP.defaultEnabled)
        assertTrue(Metric.ENERGY in bridgeMetrics)
        assertTrue(Metric.SLEEP_SCORE in bridgeMetrics)
    }
    @Test fun `Samsung scores never become fake Health Connect records`() {
        val point = SourcePoint("score", Instant.EPOCH, null, Instant.EPOCH, null,
            mapOf("energy" to 85.0, "sleepScore" to 90.0))
        assertTrue(RecordMapper.map(Metric.ENERGY, point).isEmpty())
        assertTrue(RecordMapper.map(Metric.SLEEP_SCORE, point).isEmpty())
        assertTrue(Metric.ENERGY.localOnly && Metric.SLEEP_SCORE.localOnly)
    }
}
