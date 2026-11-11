package com.repforge.healthbridge.sync

import android.content.Context
import androidx.work.*
import com.repforge.healthbridge.data.*
import com.repforge.healthbridge.explorer.SamsungCatalog
import com.repforge.healthbridge.explorer.ExplorerStore
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex

class BridgeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!lock.tryLock()) return Result.retry()
        val prefs = BridgePreferences(applicationContext)
        try {
            if (inputData.getBoolean("periodic", false) && !prefs.background) return Result.success()
            val now = Instant.now()
            val gateway = HealthGateway(applicationContext)
            val selected = prefs.selected
            if (selected.isEmpty()) return Result.success()
            val reader = SamsungReader(applicationContext)
            val sourceGranted = reader.granted(selected)
            val sourceRouteGranted = Metric.EXERCISE in selected && reader.routeGranted()
            val targetGranted = if (gateway.available) gateway.client.permissionController.getGrantedPermissions() else emptySet()
            val eligible = selected.filter { metric ->
                val required = gateway.permissions(metric)
                metric in sourceGranted && (metric.localOnly || metric == Metric.BODY ||
                    (gateway.available && required.any { it in targetGranted }))
            }.toSet()
            val skipped = selected - eligible
            var saved = 0
            var sent = 0
            val insights = InsightsStore(applicationContext)
            val engine = TransferEngine(object : TransferPort {
                override suspend fun read(metric: Metric, start: Instant, end: Instant, token: String?): SourcePage {
                    val page = reader.page(metric, start, end, token)
                    if (metric.localOnly || metric == Metric.BODY) saved += insights.put(metric, page.points)
                    return page
                }
                override suspend fun write(records: List<androidx.health.connect.client.records.Record>) {
                    val allowed = records.filter { androidx.health.connect.client.permission.HealthPermission
                        .getWritePermission(it::class) in targetGranted }
                    if (allowed.isNotEmpty() && gateway.available) {
                        gateway.client.insertRecords(allowed)
                        sent += allowed.size
                    }
                }
                private fun completeAccess(metric: Metric) = metric.localOnly ||
                    (targetGranted.containsAll(gateway.permissions(metric)) && (metric != Metric.EXERCISE || sourceRouteGranted))
                override fun checkpoint(metric: Metric) = if (!completeAccess(metric)) null else prefs.checkpoint(metric)
                override fun checkpoint(metric: Metric, time: Instant) {
                    if (completeAccess(metric)) prefs.checkpoint(metric, time)
                }
            }, mapper = { metric, point ->
                if (metric == Metric.EXERCISE) {
                    val routeWrite = androidx.health.connect.client.permission.HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE in targetGranted
                    // With route-write access, updating a session with no route deletes its old route.
                    // Do not modify sessions if Samsung route access has been revoked; streams can still sync.
                    WorkoutRecordMapper.map(point, allowRoute = routeWrite,
                        writeSession = !(routeWrite && point.text["routeReadAllowed"] == "false"))
                } else RecordMapper.map(metric, point)
            })
            val result = try {
                engine.run(eligible, now, prefs.days) { metric, count ->
                    setProgress(workDataOf("metric" to metric.title, "count" to count))
                }
            } finally { insights.close() }
            // Permission scope is intentionally broader than the export toggles. Keep Samsung-only
            // fields (goals, scores, detection results and detailed workout fields) available in charts.
            var cachedSources = 0
            var cacheFailures = 0
            withContext(Dispatchers.IO) {
                val catalog = SamsungCatalog(applicationContext)
                val granted = catalog.granted()
                ExplorerStore.access(applicationContext) { output ->
                    for (source in catalog.sources.filter { it.key != "EXERCISE_LOCATION" }) {
                        val permission = com.samsung.android.sdk.health.data.permission.Permission.of(source.type,
                            com.samsung.android.sdk.health.data.permission.AccessType.READ)
                        if (permission !in granted) continue
                        val key = "catalog_cursor_${source.key}"
                        val checkpoint = prefs.raw.getLong(key, 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli)
                        val success = catalog.scan(source, granted, SyncPolicy.start(now, prefs.days, checkpoint), now,
                            ZoneId.systemDefault(), output) { title -> setProgressAsync(workDataOf("metric" to title)) }
                        if (success) {
                            prefs.raw.edit().putLong(key, now.toEpochMilli()).apply()
                            cachedSources++
                        } else cacheFailures++
                    }
                }
            }
            val issues = buildList {
                if (skipped.isNotEmpty()) add("Needs access or device support: ${skipped.joinToString { it.title }}.")
                if (result.failed.isNotEmpty()) add("Could not sync: ${result.failed.joinToString { "${it.title} (${result.failureKinds[it] ?: "read/write failure"})" }}. Check connections and try again.")
                val partial = eligible.filter { !it.localOnly && !targetGranted.containsAll(gateway.permissions(it)) }
                if (partial.isNotEmpty()) add("Some Health Connect writes need access: ${partial.joinToString { it.title }}. Permitted fields were exported; history will be retried.")
                if (Metric.EXERCISE in eligible && (!sourceRouteGranted ||
                    androidx.health.connect.client.permission.HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE !in targetGranted))
                    add("Workout routes need Samsung exercise-location and Health Connect route-write access. Missing access keeps workout history queued for backfill.")
                if (cacheFailures > 0) add("$cacheFailures Samsung chart sources could not be refreshed; see the explorer's source status.")
            }
            prefs.report(
                "$saved insight records saved. $sent records sent to Health Connect. $cachedSources Samsung sources refreshed for charts. ${issues.joinToString(" ")}".trim(),
                saved + sent, now,
            )
            return if (result.failed.isNotEmpty() && runAttemptCount < 2) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            prefs.report("Samsung Health could not be reached. Open it, check developer mode and reconnect.", 0, Instant.now())
            return if (runAttemptCount < 2) Result.retry() else Result.failure()
        } finally {
            lock.unlock()
        }
    }

    companion object {
        private val lock = Mutex()
        const val TAG = "health_bridge_sync"
        fun syncNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("bridge_manual", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<BridgeWorker>().addTag(TAG).addTag("manual")
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
        fun schedule(context: Context, enabled: Boolean) {
            val manager = WorkManager.getInstance(context)
            if (!enabled) { manager.cancelUniqueWork("bridge_periodic"); return }
            manager.enqueueUniquePeriodicWork("bridge_periodic", ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<BridgeWorker>(6, TimeUnit.HOURS)
                    .setInitialDelay(6, TimeUnit.HOURS)
                    .setInputData(workDataOf("periodic" to true))
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                    .addTag(TAG).build())
        }
    }
}
