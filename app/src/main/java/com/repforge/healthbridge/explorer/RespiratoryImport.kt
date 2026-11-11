package com.repforge.healthbridge.explorer

import java.io.InputStream
import java.io.PushbackReader
import java.io.Reader
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

object RespiratoryImport {
    const val SOURCE = "IMPORT_RESPIRATORY"
    fun time(value: String): Instant? = try {
        val number = value.toDoubleOrNull()
        if (number != null) Instant.ofEpochMilli(if (kotlin.math.abs(number) < 100_000_000_000) (number * 1000).toLong() else number.toLong())
        else try { Instant.parse(value) } catch (_: Exception) {
            // Samsung export CSV timestamps are UTC; display them in the phone's local zone.
            LocalDateTime.parse(value.trim().replace(' ', 'T')).toInstant(ZoneOffset.UTC)
        }
    } catch (_: Exception) { null }

    fun csv(reader: Reader, emit: (Observation) -> Unit, check: () -> Unit = {}): Int {
        var header: List<String>? = null
        var count = 0
        for (row in csvRows(reader, check)) {
            check()
            if (header == null) {
                val keys = row.map { it.removePrefix("\uFEFF").trim().substringAfterLast('.').lowercase() }
                if (keys.any { it in setOf("start_time", "time", "timestamp") }) header = keys
                continue
            }
            val keys = header
            val values = keys.mapIndexed { index, name -> name to row.getOrElse(index) { "" } }.toMap()
            val instant = listOf("start_time", "time", "timestamp").firstNotNullOfOrNull { values[it]?.let(::time) } ?: continue
            for (field in listOf("respiratory_rate", "respiration_rate", "average", "upper_limit", "lower_limit")) {
                val value = values[field]?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: continue
                emit(reading(field, instant, value, values["datauuid"].orEmpty())); count++
            }
        }
        return count
    }

    private fun reading(field: String, time: Instant, value: Double, sourceId: String = ""): Observation {
        val id = MessageDigest.getInstance("SHA-256").digest("$sourceId|$field|$time".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return Observation(id, field, time, value, unit = "breaths/min",
            resolution = if (field in setOf("respiratory_rate", "respiration_rate")) "Exported raw sample" else "Exported session summary",
            origin = "Samsung personal-data export")
    }

    fun json(text: String, emit: (Observation) -> Unit, check: () -> Unit = {}): Int {
        check()
        var count = 0
        fun row(obj: JSONObject) {
            val time = listOf("start_time", "time", "timestamp").firstNotNullOfOrNull {
                if (obj.has(it)) time(obj.optString(it)) else null
            } ?: return
            for (field in listOf("respiratory_rate", "respiration_rate", "average", "upper_limit", "lower_limit")) {
                val value = if (obj.has(field)) obj.optDouble(field, Double.NaN) else Double.NaN
                if (value.isFinite()) { emit(reading(field, time, value, obj.optString("datauuid", ""))); count++ }
            }
        }
        val trimmed = text.trim().removePrefix("\uFEFF")
        val array = if (trimmed.startsWith('[')) JSONArray(trimmed) else {
            val root = JSONObject(trimmed)
            root.optJSONArray("data") ?: root.optJSONArray("samples") ?: JSONArray().put(root)
        }
        for (index in 0 until array.length()) { check(); array.optJSONObject(index)?.let(::row) }
        return count
    }

    fun read(input: InputStream, emit: (Observation) -> Unit, check: () -> Unit = {}): Int {
        val stream = java.io.PushbackInputStream(input.buffered(), 4)
        val magic = ByteArray(4); val size = stream.read(magic)
        if (size > 0) stream.unread(magic, 0, size)
        if (size >= 2 && magic[0] == 0x50.toByte() && magic[1] == 0x4b.toByte()) {
            var count = 0
            var bytes = 0L
            ZipInputStream(stream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    check()
                    val name = entry.name.lowercase()
                    val relevant = !entry.isDirectory && name.contains("respiratory") && (name.endsWith(".csv") || name.endsWith(".json"))
                    val buffer = if (relevant) java.io.ByteArrayOutputStream() else null
                    val chunk = ByteArray(8192)
                    var n = zip.read(chunk)
                    while (n != -1) {
                        check()
                        bytes += n
                        require(bytes <= 512L * 1024 * 1024) { "ZIP exceeds 512 MB uncompressed import limit; choose the respiratory CSV instead" }
                        if (buffer != null) {
                            require(buffer.size() + n <= 16 * 1024 * 1024) { "Respiratory entry exceeds import limit" }
                            buffer.write(chunk, 0, n)
                        }
                        n = zip.read(chunk)
                    }
                    if (buffer != null) {
                        val text = buffer.toString(Charsets.UTF_8.name())
                        count += if (name.endsWith(".csv")) csv(text.reader(), emit, check) else json(text, emit, check)
                    }
                    zip.closeEntry(); entry = zip.nextEntry
                }
            }
            return count
        }
        if (magic.firstOrNull()?.toInt()?.toChar() in listOf('[', '{')) {
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var n = stream.read(chunk)
            while (n != -1) {
                check(); require(buffer.size() + n <= 16 * 1024 * 1024) { "JSON exceeds import limit" }
                buffer.write(chunk, 0, n); n = stream.read(chunk)
            }
            return json(buffer.toString(Charsets.UTF_8.name()), emit, check)
        }
        return csv(stream.reader(Charsets.UTF_8), emit, check)
    }

    /** Streaming RFC4180 reader: supports quotes, commas and newlines inside cells. */
    fun csvRows(reader: Reader, check: () -> Unit = {}): Sequence<List<String>> = sequence {
        val input = PushbackReader(reader, 1)
        var quoted = false
        val cell = StringBuilder()
        val row = mutableListOf<String>()
        var chars = 0
        while (true) {
            if (chars++ % 4096 == 0) check()
            val code = input.read()
            if (code == -1) { if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); yield(row.toList()) }; break }
            val char = code.toChar()
            if (char == '"') {
                if (quoted) {
                    val next = input.read()
                    if (next == '"'.code) cell.append('"') else { quoted = false; if (next != -1) input.unread(next) }
                } else quoted = true
            } else if (char == ',' && !quoted) { row += cell.toString(); cell.clear() }
            else if ((char == '\n' || char == '\r') && !quoted) {
                if (char == '\r') { val next = input.read(); if (next != '\n'.code && next != -1) input.unread(next) }
                row += cell.toString(); cell.clear(); yield(row.toList()); row.clear()
            } else cell.append(char)
            require(cell.length <= 1_000_000 && row.size < 512) { "CSV row exceeds import limit" }
        }
    }
}
