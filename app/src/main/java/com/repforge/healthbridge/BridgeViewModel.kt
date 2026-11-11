package com.repforge.healthbridge

import android.app.Activity
import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.repforge.healthbridge.data.*
import com.repforge.healthbridge.explorer.SamsungCatalog
import com.repforge.healthbridge.sync.BridgeWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BridgeState(
    val selected: Set<Metric> = emptySet(), val days: Int = 7, val background: Boolean = false,
    val samsung: Set<Metric> = emptySet(), val health: Set<String> = emptySet(),
    val healthAvailable: Boolean = false, val skinAvailable: Boolean = false,
    val loading: Boolean = true, val busy: Boolean = false, val permissionBusy: Boolean = false,
    val progress: String = "", val report: String = "", val lastRun: Long = 0,
    val message: String? = null,
    val insights: List<Insight> = emptyList(),
    val samsungAllowed: Int = 0, val samsungTotal: Int = 0,
)

class BridgeViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = BridgePreferences(app)
    private val gateway = HealthGateway(app)
    private val reader = SamsungReader(app)
    private val catalog = SamsungCatalog(app)
    private val mutable = MutableStateFlow(BridgeState())
    val state = mutable.asStateFlow()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> loadPreferences() }
    init {
        prefs.raw.registerOnSharedPreferenceChangeListener(listener)
        loadPreferences()
        viewModelScope.launch {
            WorkManager.getInstance(app).getWorkInfosByTagFlow(BridgeWorker.TAG).collect { infos ->
                val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                val queuedManual = infos.any { it.state == WorkInfo.State.ENQUEUED && "manual" in it.tags }
                mutable.update { it.copy(busy = running != null || queuedManual,
                    progress = running?.progress?.getString("metric") ?: "Preparing sync") }
                if (running == null) loadInsights()
            }
        }
    }
    private fun loadPreferences() {
        mutable.update { it.copy(selected = prefs.selected, days = prefs.days, background = prefs.background,
            report = prefs.raw.getString("report", "").orEmpty(), lastRun = prefs.raw.getLong("last_run", 0)) }
        loadInsights()
    }
    private fun loadInsights() = viewModelScope.launch {
        val selected = prefs.selected
        val insights = withContext(Dispatchers.IO) { InsightsStore(getApplication()).use { it.latest(selected) } }
        mutable.update { it.copy(insights = insights) }
    }
    fun refresh() = viewModelScope.launch {
        try {
            val available = gateway.available
            val health = if (available) gateway.client.permissionController.getGrantedPermissions() else emptySet()
            val samsung = try { reader.granted(prefs.selected) } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) { emptySet() }
            val allowed = try { withContext(Dispatchers.IO) { catalog.granted().size } }
                catch (c: CancellationException) { throw c } catch (_: Exception) { 0 }
            mutable.update { it.copy(healthAvailable = available, skinAvailable = available && gateway.skinAvailable,
                health = health, samsung = samsung, loading = false,
                samsungAllowed = allowed, samsungTotal = catalog.permissions().size) }
        } catch (c: CancellationException) { throw c }
        catch (_: Exception) { mutable.update { it.copy(loading = false, message = "Could not check connections. Try again.") } }
    }
    fun select(metric: Metric, enabled: Boolean) {
        prefs.selected = if (enabled) prefs.selected + metric else prefs.selected - metric
        refresh()
    }
    fun setDays(days: Int) { prefs.days = days }
    fun setBackground(enabled: Boolean) {
        prefs.background = enabled
        BridgeWorker.schedule(getApplication(), enabled)
    }
    fun requestSamsung(activity: Activity) = viewModelScope.launch {
        mutable.update { it.copy(permissionBusy = true) }
        try { catalog.request(activity) }
        catch (c: CancellationException) { throw c }
        catch (_: Exception) {
            mutable.update { it.copy(message = "Open Samsung Health, enable Developer Mode for Data Read, then connect again.") }
        } finally { mutable.update { it.copy(permissionBusy = false) } }
        refresh()
    }
    fun requiredHealthPermissions() = gateway.permissions(prefs.selected)
    fun healthReady(s: BridgeState): Boolean = s.healthAvailable && s.selected.filterNot { it.localOnly }.all {
        gateway.permissions(it).isNotEmpty() && s.health.containsAll(gateway.permissions(it))
    }
    fun ready(s: BridgeState): Boolean = s.selected.any {
        it in s.samsung && (it.localOnly || it == Metric.BODY || (s.healthAvailable &&
            gateway.permissions(it).any { permission -> permission in s.health }))
    }
    fun sync() { BridgeWorker.syncNow(getApplication()) }
    fun clearMessage() { mutable.update { it.copy(message = null) } }
    fun showMessage(message: String) { mutable.update { it.copy(message = message) } }
    override fun onCleared() { prefs.raw.unregisterOnSharedPreferenceChangeListener(listener) }
}
