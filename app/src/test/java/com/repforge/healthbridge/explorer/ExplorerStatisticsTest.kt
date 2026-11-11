package com.repforge.healthbridge.explorer

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class ExplorerStatisticsTest {
    @Test fun `daily median is exact for odd and even sample counts`() {
        val time = Instant.EPOCH
        val odd = Statistics.point(time, listOf(100.0, 2.0, 1.0))
        assertEquals(2.0, odd.median, 0.0)
        assertEquals(103.0 / 3, odd.mean, .000001)
        assertEquals(2.5, Statistics.point(time, listOf(1.0, 4.0, 2.0, 3.0)).median, 0.0)
        assertEquals(1.0, odd.min, 0.0); assertEquals(100.0, odd.max, 0.0)
    }
    @Test fun `daily statistics use the phones timezone and leave missing days empty`() {
        val samples = listOf(Instant.parse("2026-10-26T23:00:00Z") to 10.0,
            Instant.parse("2026-10-27T01:00:00Z") to 20.0,
            Instant.parse("2026-10-29T01:00:00Z") to 30.0,
            Instant.parse("2026-10-29T02:00:00Z") to Double.NaN)
        val zone = ZoneId.of("Asia/Kolkata")
        val points = Statistics.daily(samples, zone)
        assertEquals(2, points.size)
        assertEquals(LocalDate.of(2026, 10, 27), points.first().time.atZone(zone).toLocalDate())
        assertEquals(2, points.first().count)
        assertEquals(15.0, points.first().median, 0.0)
    }
    @Test fun `calendar day respects daylight savings rather than assuming 24 hours`() {
        val zone = ZoneId.of("America/Los_Angeles")
        val anchor = LocalDate.of(2026, 3, 8)
        val start = ChartRange.DAY.start(anchor, zone)
        val end = anchor.plusDays(1).atStartOfDay(zone).toInstant()
        assertEquals(23L, Duration.between(start, end).toHours())
    }
    @Test fun `year includes leap day when the selected calendar year contains one`() {
        val anchor = LocalDate.of(2024, 3, 1)
        val start = ChartRange.YEAR.start(anchor, ZoneOffset.UTC)
        assertEquals(LocalDate.of(2023, 3, 2), start.atZone(ZoneOffset.UTC).toLocalDate())
        assertEquals(366L, Duration.between(start, anchor.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)).toDays())
    }
    @Test fun `single recorded sample does not invent variation`() {
        val point = Statistics.point(Instant.EPOCH, listOf(14.8))
        assertEquals(point.min, point.max, 0.0)
        assertEquals(point.mean, point.median, 0.0)
        assertEquals(1, point.count)
    }
}
