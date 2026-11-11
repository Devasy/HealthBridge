package com.repforge.healthbridge.explorer

import java.time.Instant
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class RespiratoryImportTest {
    @Test fun `Samsung metadata row and quoted cells do not hide respiratory values`() {
        val csv = "com.samsung.health.respiratory_rate,6313013,2\r\nstart_time,average,lower_limit,upper_limit,comment,datauuid\r\n2026-10-27 01:00:00,15.2,12.0,18.0,\"a,b\",sample-1\r\n"
        val readings = mutableListOf<Observation>()
        assertEquals(3, RespiratoryImport.csv(csv.reader(), readings::add))
        assertEquals(Instant.parse("2026-10-27T01:00:00Z"), readings.first().time)
        assertEquals(15.2, readings.first().value!!, 0.0)
        assertTrue(readings.all { it.resolution == "Exported session summary" })
    }
    @Test fun `raw samples and summaries are distinct fields with honest resolution`() {
        val readings = mutableListOf<Observation>()
        RespiratoryImport.csv("time,respiratory_rate,average\n1793059200000,14.8,15.0\n".reader(), readings::add)
        assertEquals(2, readings.size)
        assertNotEquals(readings.first().field, readings.last().field)
        assertEquals("Exported raw sample", readings.first().resolution)
        assertEquals("breaths/min", readings.first().unit)
    }
    @Test fun `invalid timestamps missing values and nonfinite numbers are not plotted`() {
        val readings = mutableListOf<Observation>()
        RespiratoryImport.csv("start_time,respiratory_rate\nbad,14\n2026-10-27 01:00:00,NaN\n2026-10-27 01:01:00,\n".reader(), readings::add)
        assertTrue(readings.isEmpty())
    }
    @Test fun `correcting a value preserves the import identity`() {
        fun read(value: Double): Observation {
            val result = mutableListOf<Observation>()
            RespiratoryImport.csv("start_time,respiratory_rate,datauuid\n2026-10-27 01:00:00,$value,uid\n".reader(), result::add)
            return result.single()
        }
        assertEquals(read(14.0).id, read(15.0).id)
        assertNotEquals(read(14.0).value, read(15.0).value)
    }
    @Test fun `CSV escaped quotes and multiline cells stay in the same record`() {
        val rows = RespiratoryImport.csvRows("a,b\n\"two\nlines\",\"say \"\"yes\"\"\"\n".reader()).toList()
        assertEquals(2, rows.size)
        assertEquals("two\nlines", rows[1][0])
        assertEquals("say \"yes\"", rows[1][1])
    }
    @Test fun `ZIP import reads respiratory entries without extracting paths or unrelated health data`() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("export/com.samsung.health.respiratory_rate.20261027.csv"))
            zip.write("start_time,average\n2026-10-27 01:00:00,15.2\n".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("unrelated.csv")); zip.write("start_time,average\n2026-10-27 01:00:00,99\n".toByteArray()); zip.closeEntry()
        }
        val result = mutableListOf<Observation>()
        assertEquals(1, RespiratoryImport.read(bytes.toByteArray().inputStream(), result::add))
        assertEquals(15.2, result.single().value!!, 0.0)
    }
    @Test fun `cancellation is checked even when CSV has no recognized readings`() {
        var checks = 0
        try {
            RespiratoryImport.csv(("unrecognized,header\n".repeat(10_000)).reader(), {}, check = {
                if (++checks == 3) throw java.util.concurrent.CancellationException("Stopped")
            })
            fail("Expected cancellation")
        } catch (_: java.util.concurrent.CancellationException) { assertEquals(3, checks) }
    }
    @Test fun `cancellation interrupts unrelated ZIP entries before any observation emits`() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("unrelated.bin")); zip.write(ByteArray(100_000)); zip.closeEntry()
        }
        var checks = 0
        try {
            RespiratoryImport.read(bytes.toByteArray().inputStream(), {}, check = {
                if (++checks == 3) throw java.util.concurrent.CancellationException("Stopped")
            })
            fail("Expected cancellation")
        } catch (_: java.util.concurrent.CancellationException) { assertEquals(3, checks) }
    }
}
