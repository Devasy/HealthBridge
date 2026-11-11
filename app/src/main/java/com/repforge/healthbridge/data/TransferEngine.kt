package com.repforge.healthbridge.data

import androidx.health.connect.client.records.Record
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

interface TransferPort {
    suspend fun read(metric: Metric, start: Instant, end: Instant, token: String?): SourcePage
    suspend fun write(records: List<Record>)
    fun checkpoint(metric: Metric): Instant?
    fun checkpoint(metric: Metric, time: Instant)
}

data class TransferResult(val written: Int, val failed: Set<Metric>, val failureKinds: Map<Metric, String> = emptyMap())

class TransferEngine(private val port: TransferPort,
    private val mapper: (Metric, SourcePoint) -> List<Record> = RecordMapper::map) {
    suspend fun run(
        metrics: Set<Metric>, now: Instant, days: Int,
        progress: suspend (Metric, Int) -> Unit = { _, _ -> },
    ): TransferResult {
        var written = 0
        val failed = mutableSetOf<Metric>()
        val failureKinds = mutableMapOf<Metric, String>()
        for (metric in metrics) {
            try {
                val start = SyncPolicy.start(now, days, port.checkpoint(metric))
                var token: String? = null
                val pages = PageProgress()
                do {
                    coroutineContext.ensureActive()
                    progress(metric, written)
                    val page = port.read(metric, start, now, token)
                    val next = pages.next(page.next, page.identities)
                    // Combine small points to avoid waking Health Connect once per reading.
                    // Bound intermediate allocations to one mapped source point and one batch.
                    val batch = ArrayList<Record>(SyncPolicy.WRITE_BATCH_SIZE)
                    suspend fun flush() {
                        if (batch.isEmpty()) return
                        port.write(batch.toList())
                        written += batch.size
                        batch.clear()
                    }
                    for (point in page.points) {
                        for (record in mapper(metric, point)) {
                            batch += record
                            // Route and series records can be large; avoid combining many in one IPC.
                            if (metric == Metric.EXERCISE || batch.size == SyncPolicy.WRITE_BATCH_SIZE) flush()
                        }
                    }
                    flush()
                    token = next
                } while (token != null)
                // A failed page/write never advances the cursor; deterministic IDs make retry safe.
                port.checkpoint(metric, now)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failed += metric
                failureKinds[metric] = failure.javaClass.simpleName
            }
        }
        return TransferResult(written, failed, failureKinds)
    }
}
