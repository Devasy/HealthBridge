package com.repforge.healthbridge.strava

import android.app.Activity
import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.repforge.healthbridge.data.*
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StravaState(val relay: String = "", val athlete: Long? = null, val day: LocalDate = LocalDate.now(),
    val workouts: List<SourcePoint> = emptyList(), val uploads: Map<String, UploadEntry> = emptyMap(),
    val busy: Boolean = false, val scanned: Boolean = false, val message: String? = null)

class StravaViewModel(app: Application) : AndroidViewModel(app) {
    private val storage = StravaStorage(app)
    private val http = StravaHttp(storage)
    private val reader = SamsungReader(app)
    private val mutable = MutableStateFlow(StravaState(relay = storage.relay, athlete = storage.tokens?.athleteId))
    val state = mutable.asStateFlow()
    private var export: SourcePoint? = null

    fun setDay(day: LocalDate) {
        if (!state.value.busy) mutable.update { it.copy(day = day, workouts = emptyList(), uploads = emptyMap(), scanned = false) }
    }
    fun setRelay(value: String) {
        if (!state.value.busy && state.value.athlete == null) mutable.update { it.copy(relay = value) }
    }
    fun clearMessage() { mutable.update { it.copy(message = null) } }
    fun connect(open: (Uri) -> Unit) = task {
        check(state.value.athlete == null)
        val relay = OAuthPolicy.relay(state.value.relay)
        http.config(relay)
        val attempt = OAuthPolicy.attempt(relay)
        withContext(Dispatchers.IO) { storage.relay = relay; storage.attempt = attempt }
        val uri = Uri.parse("$relay/authorize").buildUpon().appendQueryParameter("state", attempt.state)
            .appendQueryParameter("challenge", OAuthPolicy.challenge(attempt)).build()
        open(uri)
    }
    fun callback(uri: Uri) = task {
        val attempt = requireNotNull(withContext(Dispatchers.IO) { storage.attempt }) { "No pending Strava connection. Connect again." }
        val code = OAuthPolicy.code(uri.toString(), attempt)
        try {
            val tokens = http.exchange(code, attempt)
            mutable.update { it.copy(athlete = tokens.athleteId, message = "Strava connected. Select a workout to upload.") }
            refreshUploads()
        } finally { withContext(Dispatchers.IO) { storage.attempt = null } }
    }
    fun disconnect() = task {
        withContext(Dispatchers.IO) { storage.disconnect() }
        mutable.update { it.copy(athlete = null, uploads = emptyMap(), message = "Disconnected on this phone. Revoke Health Bridge in Strava settings to remove its authorization there.") }
    }
    fun permissions(activity: Activity) = task {
        HealthDataService.getStore(getApplication<Application>()).requestPermissions(setOf(
            Permission.of(DataTypes.EXERCISE, AccessType.READ), Permission.of(DataTypes.EXERCISE_LOCATION, AccessType.READ)), activity)
    }
    fun scan() = task {
        check(Metric.EXERCISE in reader.granted(setOf(Metric.EXERCISE))) { "Allow Samsung workout reads first." }
        val day = state.value.day
        val zone = ZoneId.systemDefault()
        val start = day.atStartOfDay(zone).toInstant()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant()
        val points = withContext(Dispatchers.IO) {
            val found = linkedMapOf<String, SourcePoint>()
            var token: String? = null
            val seen = mutableSetOf<String>()
            do {
                val page = reader.page(Metric.EXERCISE, start, end, token)
                page.points.filter { TcxEncoder.supported(it) && it.start >= start && it.start < end && it.end != null && it.end > it.start }
                    .forEach { found[it.uid] = it }
                token = page.next
                check(token == null || seen.add(token)) { "Samsung returned a repeated workout page." }
                check(found.size <= 1000 && seen.size <= 32) { "Too many workouts for one day." }
            } while (token != null)
            found.values.sortedByDescending { it.start }
        }
        mutable.update { it.copy(workouts = points, scanned = true, uploads = emptyMap(), message = if (points.isEmpty()) "No runs, walks or hikes start on this day." else null) }
        refreshUploads()
    }
    fun upload(workout: SourcePoint) = task {
        val athlete = requireNotNull(state.value.athlete) { "Connect Strava first." }
        // Refresh credentials before recording a POST attempt; auth errors are safe to retry.
        val port = http.uploadPort()
        val entry = withContext(Dispatchers.IO) { StravaSyncEngine(storage, port).sync(athlete, workout) }
        mutable.update { it.copy(uploads = it.uploads + (workout.uid to entry), message = entry.message) }
    }
    fun prepareExport(workout: SourcePoint) { export = workout }
    fun writeExport(uri: Uri?) {
        val point = export; export = null
        if (uri == null) return
        task {
            requireNotNull(point) { "Select the workout again before exporting." }
            withContext(Dispatchers.IO) {
                val stream = requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "wt"))
                stream.bufferedWriter(Charsets.UTF_8).use { it.write(TcxEncoder.encode(point)) }
            }
            mutable.update { it.copy(message = "TCX saved. You can upload it through the Strava website.") }
        }
    }
    private suspend fun refreshUploads() {
        val current = state.value
        val athlete = current.athlete ?: return
        val uploads = withContext(Dispatchers.IO) { current.workouts.mapNotNull { point ->
            storage.get(StravaSyncEngine.key(athlete, point.uid))?.let { point.uid to it }
        }.toMap() }
        mutable.update { it.copy(uploads = uploads) }
    }
    private fun task(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val message = if (e is IllegalArgumentException || e is IllegalStateException) e.message
                    else null
                mutable.update { it.copy(message = message ?: "Could not complete this step. Check your connection and permissions, then try again.") }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
