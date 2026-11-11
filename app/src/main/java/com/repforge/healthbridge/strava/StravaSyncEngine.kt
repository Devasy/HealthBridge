package com.repforge.healthbridge.strava

import com.repforge.healthbridge.data.SourcePoint
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class UploadPhase { SUBMITTING, PROCESSING, SYNCED, DUPLICATE, FAILED, UNKNOWN }
data class UploadEntry(val key: String, val phase: UploadPhase, val uploadId: Long? = null,
    val activityId: Long? = null, val message: String = "")
data class UploadStatus(val activityId: Long? = null, val rejected: Boolean = false, val duplicate: Boolean = false)
interface UploadJournal { fun get(key: String): UploadEntry?; fun put(entry: UploadEntry) }
interface StravaUploadPort {
    suspend fun submit(tcx: String, externalId: String, name: String): Long
    suspend fun poll(uploadId: Long): UploadStatus
}
class UploadRejected(val duplicate: Boolean = false) : Exception()

class StravaSyncEngine(private val journal: UploadJournal, private val port: StravaUploadPort) {
    suspend fun sync(athleteId: Long, workout: SourcePoint): UploadEntry = mutex.withLock {
        require(athleteId > 0)
        val key = key(athleteId, workout.uid)
        var entry = journal.get(key)
        if (entry?.phase in setOf(UploadPhase.SYNCED, UploadPhase.DUPLICATE, UploadPhase.UNKNOWN)) return@withLock entry!!
        if (entry?.phase == UploadPhase.SUBMITTING) {
            return@withLock entry.copy(phase = UploadPhase.UNKNOWN, message = uncertain).also(journal::put)
        }
        if (entry?.phase == UploadPhase.FAILED) entry = null
        if (entry?.uploadId == null) {
            val tcx = TcxEncoder.encode(workout)
            require(tcx.length <= 12 * 1024 * 1024) { "Workout is too large for this upload version. Save a TCX file instead." }
            entry = UploadEntry(key, UploadPhase.SUBMITTING, message = "Sending to Strava")
            journal.put(entry)
            try {
                val id = port.submit(tcx, "healthbridge-$key.tcx", workout.text["title"]?.takeIf { it.isNotBlank() }
                    ?: "Samsung ${workout.text["exercise"].orEmpty().lowercase()}")
                check(id > 0)
                entry = entry.copy(phase = UploadPhase.PROCESSING, uploadId = id, message = "Strava is processing")
                journal.put(entry)
            } catch (e: Exception) {
                val rejected = e as? UploadRejected
                val result = entry.copy(phase = when {
                    rejected?.duplicate == true -> UploadPhase.DUPLICATE
                    rejected != null -> UploadPhase.FAILED
                    else -> UploadPhase.UNKNOWN
                }, message = if (rejected != null) "Strava rejected this upload. Check your connection and account." else uncertain)
                journal.put(result)
                if (e is CancellationException) throw e
                return@withLock result
            }
        }
        val pending = entry
        try {
            val status = port.poll(requireNotNull(pending.uploadId))
            when {
                status.activityId != null -> pending.copy(phase = UploadPhase.SYNCED, activityId = status.activityId, message = "Synced to Strava")
                status.duplicate -> pending.copy(phase = UploadPhase.DUPLICATE, message = "Strava reports this activity already exists")
                status.rejected -> pending.copy(phase = UploadPhase.FAILED, message = "Strava could not process this file")
                else -> pending
            }.also(journal::put)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            pending.copy(message = "Status check failed. Check again to resume this upload.").also(journal::put)
        }
    }

    companion object {
        private val mutex = Mutex()
        private const val uncertain = "Upload outcome unknown. Check Strava before any manual re-upload; automatic retry is blocked."
        fun key(athleteId: Long, uid: String) = MessageDigest.getInstance("SHA-256")
            .digest("$athleteId:$uid".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
