package com.repforge.healthbridge.explorer

import android.app.Activity
import android.app.Application
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ExplorerState(val sources: List<SourceStatus> = emptyList(), val selected: String = "HC_RESPIRATORY",
    val fields: List<FieldInfo> = emptyList(), val field: String = "", val range: ChartRange = ChartRange.WEEK,
    val anchor: LocalDate = LocalDate.now(), val data: ChartData = ChartData(), val busy: Boolean = false,
    val progress: String = "", val message: String? = null)

class ExplorerViewModel(app: Application) : AndroidViewModel(app) {
    private val catalog = SamsungCatalog(app)
    private val mutable = MutableStateFlow(ExplorerState())
    val state = mutable.asStateFlow()
    private var scanJob: Job? = null
    private var queryJob: Job? = null
    init { reload() }
    private fun window(s: ExplorerState): Pair<Instant, Instant> {
        val zone = ZoneId.systemDefault()
        return s.range.start(s.anchor, zone) to minOf(s.anchor.plusDays(1).atStartOfDay(zone).toInstant(), Instant.now())
    }
    fun reload() {
        queryJob?.cancel()
        queryJob = viewModelScope.launch {
            val snapshot = mutable.value
            try {
                val result = withContext(Dispatchers.IO) { ExplorerStore.access(getApplication()) { db ->
                    val statuses = db.statuses()
                    val sources = listOf(SourceStatus("HC_RESPIRATORY", "Respiratory rate · Health Connect",
                        statuses["HC_RESPIRATORY"] ?: "Not scanned. Reads the same source as RepForge's Flutter inspector."),
                        SourceStatus(RespiratoryImport.SOURCE, "Respiratory rate · Samsung export",
                            statuses[RespiratoryImport.SOURCE] ?: "Import your Samsung personal-data ZIP or respiratory CSV.")) +
                        catalog.sources.map { SourceStatus(it.key, it.title, statuses[it.key] ?: "Not scanned · ${it.fieldNames.size} public SDK fields") }
                    val fields = db.fields(snapshot.selected)
                    val field = snapshot.field.takeIf { current -> fields.any { it.key == current } }
                        ?: fields.firstOrNull { it.key in setOf("respiratory_rate", "respiration_rate") }?.key
                        ?: fields.firstOrNull()?.key.orEmpty()
                    val (start, end) = window(snapshot)
                    Triple(sources, fields, field to if (field.isBlank()) ChartData() else db.chart(snapshot.selected, field,
                        start, end, snapshot.range == ChartRange.DAY, ZoneId.systemDefault()))
                } }
                mutable.update { it.copy(sources = result.first, fields = result.second,
                    field = result.third.first, data = result.third.second) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message("Could not read the local chart cache. Try scanning again.") }
        }
    }
    fun source(key: String) { mutable.update { it.copy(selected = key, field = "", data = ChartData()) }; reload() }
    fun field(key: String) { mutable.update { it.copy(field = key) }; reload() }
    fun range(range: ChartRange) { mutable.update { it.copy(range = range) }; reload() }
    fun date(date: LocalDate) { mutable.update { it.copy(anchor = minOf(date, LocalDate.now())) }; reload() }
    fun inspectWorkout(date: LocalDate) {
        mutable.update { it.copy(selected = "EXERCISE", field = "", range = ChartRange.DAY,
            anchor = minOf(date, LocalDate.now()), data = ChartData()) }
        reload()
        scan(false)
    }
    fun message(text: String?) { mutable.update { it.copy(message = text) } }
    fun requestSamsung(activity: Activity) = viewModelScope.launch {
        try { catalog.request(activity); reload() }
        catch (c: CancellationException) { throw c }
        catch (_: Exception) { message("Samsung permissions could not be requested. Check Developer Mode for Data Read.") }
    }
    fun respiratoryPermissions(): Set<String> {
        val permissions = mutableSetOf(HealthPermission.getReadPermission(RespiratoryRateRecord::class))
        if (HealthConnectClient.getSdkStatus(getApplication()) == HealthConnectClient.SDK_AVAILABLE) {
            val hc = HealthConnectClient.getOrCreate(getApplication())
            if (hc.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE)
                permissions += "android.permission.health.READ_HEALTH_DATA_HISTORY"
        }
        return permissions
    }
    fun scan(all: Boolean) {
        if (mutable.value.busy) return
        val snapshot = mutable.value
        scanJob = viewModelScope.launch {
            mutable.update { it.copy(busy = true, progress = "Checking access") }
            try {
                withContext(Dispatchers.IO) { ExplorerStore.access(getApplication()) { output ->
                    val (start, end) = window(snapshot)
                    if (all || snapshot.selected == "HC_RESPIRATORY") {
                        try { scanRespiratory(output, start, end) }
                        catch (c: CancellationException) {
                            output.status("HC_RESPIRATORY", "Respiratory rate", "Scan canceled. This range's cached results may be partial.")
                            throw c
                        }
                        catch (_: Exception) { output.status("HC_RESPIRATORY", "Respiratory rate", "Health Connect read failed. Cached results may be partial. Check respiratory/history permissions.") }
                    }
                    val chosen = catalog.sources.filter { all || it.key == snapshot.selected }
                    if (chosen.isNotEmpty()) {
                        val granted = catalog.granted()
                        for (source in chosen) catalog.scan(source, granted, start, end, ZoneId.systemDefault(), output) { text ->
                            mutable.update { it.copy(progress = text) }
                        }
                    }
                } }
                message("Scan finished. Empty or denied sources are listed separately.")
            } catch (c: CancellationException) { throw c }
            catch (_: Exception) { message("Scan could not finish. Check Samsung Health access and try again.") }
            finally { mutable.update { it.copy(busy = false) }; reload() }
        }
    }
    private suspend fun scanRespiratory(output: ExplorerStore, start: Instant, end: Instant) {
        val key = "HC_RESPIRATORY"
        if (HealthConnectClient.getSdkStatus(getApplication()) != HealthConnectClient.SDK_AVAILABLE) {
            output.status(key, "Respiratory rate", "Health Connect is unavailable"); return
        }
        val hc = HealthConnectClient.getOrCreate(getApplication())
        val permissions = hc.permissionController.getGrantedPermissions()
        if (HealthPermission.getReadPermission(RespiratoryRateRecord::class) !in permissions) {
            output.status(key, "Respiratory rate", "Allow respiratory read access first"); return
        }
        var token: String? = null
        var count = 0
        val tokens = mutableSetOf<String>()
        do {
            currentCoroutineContext().ensureActive()
            mutable.update { it.copy(progress = "Respiratory rate: $count readings") }
            val response = hc.readRecords(ReadRecordsRequest(RespiratoryRateRecord::class,
                TimeRangeFilter.between(start, end), pageSize = 1000, pageToken = token))
            if (token == null) output.clearRange(key, start, end)
            output.put(key, response.records.map { Observation(it.metadata.id, "respiratory_rate", it.time,
                it.rate, unit = "breaths/min", origin = "Health Connect: ${it.metadata.dataOrigin.packageName}") })
            count += response.records.size
            token = response.pageToken
            check(token == null || tokens.add(token))
        } while (token != null)
        val history = "android.permission.health.READ_HEALTH_DATA_HISTORY" in permissions
        output.status(key, "Respiratory rate", "$count actual readings found.${if (history) "" else " Older history may require history permission."}")
    }
    fun cancel() { scanJob?.cancel() }
    fun importRespiratory(uri: Uri) {
        if (mutable.value.busy) return
        scanJob = viewModelScope.launch {
            mutable.update { it.copy(busy = true, progress = "Importing respiratory export") }
            try {
                val count = withContext(Dispatchers.IO) { ExplorerStore.access(getApplication()) { db ->
                    val importContext = currentCoroutineContext()
                    val batch = mutableListOf<Observation>()
                    val input = getApplication<Application>().contentResolver.openInputStream(uri) ?: error("File unavailable")
                    val count = input.use { RespiratoryImport.read(it, emit = { observation ->
                        importContext.ensureActive()
                        batch += observation
                        if (batch.size >= 500) { db.put(RespiratoryImport.SOURCE, batch); batch.clear() }
                    }, check = { importContext.ensureActive() }) }
                    db.put(RespiratoryImport.SOURCE, batch)
                    db.status(RespiratoryImport.SOURCE, "Respiratory export", "$count values parsed. Only the last year is cached; summary and raw fields remain distinct.")
                    count
                } }
                source(RespiratoryImport.SOURCE)
                message("Imported $count respiratory values. Export dates are preserved.")
            } catch (c: CancellationException) { throw c }
            catch (_: Exception) { message("Could not import this file. Choose Samsung's personal-data ZIP or a respiratory CSV.") }
            finally { mutable.update { it.copy(busy = false) }; reload() }
        }
    }
}
