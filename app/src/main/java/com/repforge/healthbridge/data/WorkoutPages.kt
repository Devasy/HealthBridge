package com.repforge.healthbridge.data

import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.data.HealthDataPoint
import com.samsung.android.sdk.health.data.request.*
import java.time.*

/** Bound workout reads by UTC day; Samsung 7.1 can repeat its one-point continuation page. */
class WorkoutPages(private val store: HealthDataStore) {
    data class Page(val points: List<HealthDataPoint>, val next: String?, val identities: List<String>)
    suspend fun read(start: Instant, end: Instant, token: String?): Page {
        val from = token?.let(Instant::parse) ?: start
        require(from >= start && from < end)
        val stop = minOf(from.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(), end)
        val request = DataTypes.EXERCISE.readDataRequestBuilder
            .setInstantTimeFilter(InstantTimeFilter.of(from, stop)).setOrdering(Ordering.ASC).build()
        // No SDK page-size/token: the SDK transports large lists internally. One day's
        // parent records are decoded at a time, never the whole requested year.
        val response = store.readData(request)
        check(response.pageToken.isNullOrBlank()) { "Unexpected continuation on an unpaged workout window" }
        return Page(response.dataList, stop.takeIf { it < end }?.toString(),
            response.dataList.map { "${it.uid}:${it.updateTime}" }.ifEmpty { listOf("empty-window:$from") })
    }
}
