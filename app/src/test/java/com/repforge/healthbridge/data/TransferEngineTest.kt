package com.repforge.healthbridge.data

import androidx.health.connect.client.records.Record
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TransferEngineTest {
    private val now = Instant.parse("2026-10-27T00:00:00Z")
    private val point = SourcePoint("uid", now.minusSeconds(3600), now.minusSeconds(60), now,
        null, mapOf("weight" to 80.0))
    private class Port : TransferPort {
        val cursors = mutableMapOf<Metric, Instant>()
        val tokens = mutableListOf<String?>()
        val batches = mutableListOf<List<Record>>()
        var onRead: (Metric, String?) -> SourcePage = { _, _ -> SourcePage(emptyList(), null) }
        var onWrite: () -> Unit = {}
        override suspend fun read(metric: Metric, start: Instant, end: Instant, token: String?): SourcePage {
            tokens += token
            return onRead(metric, token)
        }
        override suspend fun write(records: List<Record>) { onWrite(); batches += records }
        override fun checkpoint(metric: Metric) = cursors[metric]
        override fun checkpoint(metric: Metric, time: Instant) { cursors[metric] = time }
    }
    @Test fun `reads all pages before advancing checkpoint`() = runTest {
        val p = Port()
        p.onRead = { _, token -> SourcePage(listOf(point.copy(uid = if (token == null) "first" else "second")), if (token == null) "page2" else null) }
        val result = TransferEngine(p).run(setOf(Metric.BODY), now, 7)
        assertEquals(listOf(null, "page2"), p.tokens)
        assertEquals(2, result.written)
        assertEquals(now, p.cursors[Metric.BODY])
    }
    @Test fun `failed second page preserves cursor and allows other categories to complete`() = runTest {
        val p = Port()
        val old = now.minusSeconds(86400)
        p.cursors[Metric.BODY] = old
        p.onRead = { metric, token ->
            if (metric == Metric.BODY && token != null) error("IPC interrupted")
            SourcePage(listOf(point), if (metric == Metric.BODY) "page2" else null)
        }
        val result = TransferEngine(p).run(linkedSetOf(Metric.BODY, Metric.SLEEP), now, 7)
        assertEquals(setOf(Metric.BODY), result.failed)
        assertEquals(old, p.cursors[Metric.BODY])
        assertEquals(now, p.cursors[Metric.SLEEP])
    }
    @Test fun `failed insert never advances cursor`() = runTest {
        val p = Port()
        p.onRead = { _, _ -> SourcePage(listOf(point), null) }
        p.onWrite = { error("Permission revoked") }
        val result = TransferEngine(p).run(setOf(Metric.BODY), now, 7)
        assertNull(p.cursors[Metric.BODY])
        assertEquals(setOf(Metric.BODY), result.failed)
    }
    @Test fun `cancellation propagates rather than producing a successful checkpoint`() = runTest {
        val p = Port()
        p.onRead = { _, _ -> throw CancellationException("Stopped") }
        try {
            TransferEngine(p).run(setOf(Metric.BODY), now, 7)
            fail("Expected cancellation")
        } catch (_: CancellationException) { assertTrue(p.cursors.isEmpty()) }
    }
    @Test fun `repeated result page fails safely before writing it again`() = runTest {
        val p = Port()
        p.onRead = { _, _ -> SourcePage(listOf(point), "same") }
        assertEquals(setOf(Metric.BODY), TransferEngine(p).run(setOf(Metric.BODY), now, 7).failed)
        assertEquals(2, p.tokens.size)
        assertEquals(1, p.batches.size)
        assertTrue(p.cursors.isEmpty())
    }
    @Test fun `Samsung stable continuation token advances through distinct pages and empty end page`() = runTest {
        val p = Port()
        var page = 0
        p.onRead = { _, _ ->
            page++
            SourcePage(if (page <= 3) listOf(point.copy(uid = "source-$page")) else emptyList(), "stable-handle")
        }
        val result = TransferEngine(p).run(setOf(Metric.BODY), now, 7)
        assertTrue(result.failed.isEmpty())
        assertEquals(3, result.written)
        assertEquals(listOf(null, "stable-handle", "stable-handle", "stable-handle"), p.tokens)
        assertEquals(now, p.cursors[Metric.BODY])
    }
    @Test fun `empty decoded workout pages retain raw source identity and continue`() = runTest {
        val p = Port()
        var page = 0
        p.onRead = { _, _ ->
            page++
            when (page) {
                1 -> SourcePage(emptyList(), "stable", listOf("parent-with-no-sessions"))
                2 -> SourcePage(listOf(point), "stable")
                else -> SourcePage(emptyList(), "stable")
            }
        }
        val result = TransferEngine(p).run(setOf(Metric.BODY), now, 7)
        assertTrue(result.failed.isEmpty())
        assertEquals(1, result.written)
        assertEquals(now, p.cursors[Metric.BODY])
    }
    @Test fun `repeated data page is rejected even when its token changes`() = runTest {
        val p = Port()
        var page = 0
        p.onRead = { _, _ -> SourcePage(listOf(point), "token-${++page}") }
        val result = TransferEngine(p).run(setOf(Metric.BODY), now, 7)
        assertEquals(setOf(Metric.BODY), result.failed)
        assertEquals(1, p.batches.size)
        assertNull(p.cursors[Metric.BODY])
    }
    @Test fun `large oxygen series is written in bounded batches`() = runTest {
        val p = Port()
        p.onRead = { _, _ -> SourcePage(listOf(point.copy(samples = (0..249).map {
            Sample(point.start.plusSeconds(it.toLong()), 98.0)
        })), null) }
        val result = TransferEngine(p).run(setOf(Metric.OXYGEN), now, 7)
        assertEquals(250, result.written)
        assertEquals(listOf(100, 100, 50), p.batches.map { it.size })
    }
    @Test fun `small readings share one insert instead of one IPC call each`() = runTest {
        val p = Port()
        p.onRead = { _, _ -> SourcePage((0..99).map { point.copy(uid = "source-$it") }, null) }
        assertEquals(100, TransferEngine(p).run(setOf(Metric.BODY), now, 7).written)
        assertEquals(listOf(100), p.batches.map { it.size })
    }
    @Test fun `overlap is bounded by requested backfill and replays recently edited readings`() {
        assertEquals(now.minusSeconds(7 * 86400), SyncPolicy.start(now, 7, null))
        assertEquals(now.minusSeconds(3 * 86400), SyncPolicy.start(now, 7, now.minusSeconds(86400)))
        assertEquals(now.minusSeconds(7 * 86400), SyncPolicy.start(now, 7, now.minusSeconds(90 * 86400)))
    }
}
