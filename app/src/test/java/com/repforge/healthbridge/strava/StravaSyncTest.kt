package com.repforge.healthbridge.strava

import com.repforge.healthbridge.data.*
import java.io.StringReader
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.xml.sax.InputSource

class StravaSyncTest {
    private val start = Instant.parse("2025-01-01T12:00:00Z")
    private fun workout() = SourcePoint("source:session:1", start, start.plusSeconds(600), start, null,
        values = mapOf("distanceMeters" to 1600.0, "caloriesKcal" to 140.0),
        text = mapOf("exercise" to "RUNNING", "title" to "A & B <run>"),
        series = mapOf("heart" to listOf(Sample(start, 140.0), Sample(start.plusSeconds(3), 150.0)),
            "speed" to listOf(Sample(start, 3.0), Sample(start.plusSeconds(2), 0.0)),
            "cadence" to listOf(Sample(start, 168.0)), "power" to listOf(Sample(start, 230.0))),
        route = listOf(RoutePoint(start, 12.0, 77.0, 900.0), RoutePoint(start.plusSeconds(4), 12.001, 77.002)))

    @Test fun `TCX preserves separate sample timestamps and converts step cadence to strides`() {
        val xml = TcxEncoder.encode(workout())
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        fun count(name: String) = doc.getElementsByTagNameNS("*", name).length
        assertEquals(5, count("Trackpoint"))
        assertEquals(2, count("Position"))
        assertEquals(2, count("HeartRateBpm"))
        assertEquals(2, count("Speed"))
        assertEquals("84", doc.getElementsByTagNameNS("*", "RunCadence").item(0).textContent)
        assertEquals("230", doc.getElementsByTagNameNS("*", "Watts").item(0).textContent)
        assertTrue(xml.contains("A &amp; B &lt;run&gt;"))
        assertTrue(xml.contains("<DistanceMeters>1600.0</DistanceMeters>"))
        assertTrue(xml.contains("<Trackpoint><Time>${start.plusSeconds(600)}</Time></Trackpoint>"))
    }

    @Test fun `TCX omits invalid or out of session samples without invented positions`() {
        val p = workout().copy(route = listOf(RoutePoint(start, Double.NaN, 77.0)),
            series = mapOf("heart" to listOf(Sample(start.minusSeconds(1), 120.0),
                Sample(start.plusSeconds(1), Double.NaN), Sample(start.plusSeconds(600), 140.0))))
        val xml = TcxEncoder.encode(p)
        assertFalse(xml.contains("<Position>"))
        assertFalse(xml.contains("<HeartRateBpm>"))
        assertFalse(xml.contains("NaN"))
        assertTrue(xml.contains("<Trackpoint><Time>$start</Time>"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unsupported sport is not mislabeled as a run`() { TcxEncoder.encode(workout().copy(text = mapOf("exercise" to "SWIMMING"))) }

    private class Journal : UploadJournal {
        val rows = mutableMapOf<String, UploadEntry>()
        override fun get(key: String) = rows[key]
        override fun put(entry: UploadEntry) { rows[entry.key] = entry }
    }
    private class Port : StravaUploadPort {
        var submissions = 0
        var polls = 0
        var failSubmit = false
        var complete = true
        var rejected = false
        override suspend fun submit(tcx: String, externalId: String, name: String): Long {
            submissions++
            if (failSubmit) throw java.io.IOException("connection lost")
            return 17
        }
        override suspend fun poll(uploadId: Long): UploadStatus {
            polls++
            return UploadStatus(if (complete && !rejected) 42 else null, rejected = rejected)
        }
    }

    @Test fun `successful upload is never posted twice including after source edits`() = runTest {
        val journal = Journal(); val port = Port(); val engine = StravaSyncEngine(journal, port)
        assertEquals(UploadPhase.SYNCED, engine.sync(1, workout()).phase)
        engine.sync(1, workout().copy(updated = start.plusSeconds(60)))
        assertEquals(1, port.submissions)
        assertEquals(42L, journal.rows.values.single().activityId)
        engine.sync(2, workout())
        assertEquals(2, port.submissions)
    }

    @Test fun `pending upload resumes polling without a second submission`() = runTest {
        val journal = Journal(); val port = Port().apply { complete = false }
        val engine = StravaSyncEngine(journal, port)
        assertEquals(UploadPhase.PROCESSING, engine.sync(1, workout()).phase)
        port.complete = true
        assertEquals(UploadPhase.SYNCED, engine.sync(1, workout()).phase)
        assertEquals(1, port.submissions)
        assertEquals(2, port.polls)
    }

    @Test fun `lost POST response blocks blind retry`() = runTest {
        val journal = Journal(); val port = Port().apply { failSubmit = true }
        val engine = StravaSyncEngine(journal, port)
        assertEquals(UploadPhase.UNKNOWN, engine.sync(1, workout()).phase)
        engine.sync(1, workout())
        assertEquals(1, port.submissions)
    }

    @Test fun `process death after submitting is treated as ambiguous`() = runTest {
        val journal = Journal(); val port = Port()
        val key = StravaSyncEngine.key(1, workout().uid)
        journal.put(UploadEntry(key, UploadPhase.SUBMITTING))
        assertEquals(UploadPhase.UNKNOWN, StravaSyncEngine(journal, port).sync(1, workout()).phase)
        assertEquals(0, port.submissions)
    }

    @Test fun `definitive processing rejection allows an explicit fresh submission`() = runTest {
        val journal = Journal(); val port = Port().apply { rejected = true }
        val engine = StravaSyncEngine(journal, port)
        assertEquals(UploadPhase.FAILED, engine.sync(1, workout()).phase)
        port.rejected = false
        assertEquals(UploadPhase.SYNCED, engine.sync(1, workout()).phase)
        assertEquals(2, port.submissions)
    }
}
