package com.repforge.healthbridge.strava

import com.repforge.healthbridge.data.SourcePoint
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/** Sparse streams retain their own timestamps; absent readings stay absent. */
object TcxEncoder {
    val sports = setOf("RUNNING", "TREADMILL", "WALKING", "HIKING", "BACKPACKING")
    fun supported(p: SourcePoint) = p.text["exercise"] in sports

    fun encode(p: SourcePoint): String {
        require(supported(p)) { "Only runs, walks and hikes are supported." }
        val end = requireNotNull(p.end) { "This workout has no end time." }
        require(end > p.start) { "This workout has no elapsed duration." }
        val route = p.route.filter { it.time >= p.start && it.time < end &&
            it.latitude.isFinite() && it.latitude in -90.0..90.0 &&
            it.longitude.isFinite() && it.longitude in -180.0..180.0 }.associateBy { it.time }
        val streams = mapOf("heart" to 1.0..255.0, "speed" to 0.0..140.0,
            "cadence" to 0.0..510.0, "power" to 0.0..65535.0).mapValues { (field, range) ->
            p.series[field].orEmpty().filter { it.time >= p.start && it.time < end &&
                it.value.isFinite() && it.value in range }.associate { it.time to it.value }
        }
        val times = sortedSetOf<Instant>().apply {
            add(p.start); add(end); addAll(route.keys); streams.values.forEach { addAll(it.keys) }
        }
        val sport = when (p.text["exercise"]) { "WALKING" -> "Walking"; "HIKING", "BACKPACKING" -> "Hiking"; else -> "Running" }
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<TrainingCenterDatabase xmlns=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2\" ")
            append("xmlns:ae=\"http://www.garmin.com/xmlschemas/ActivityExtension/v2\"><Activities>")
            append("<Activity Sport=\"$sport\"><Id>${p.start}</Id><Lap StartTime=\"${p.start}\">")
            append("<TotalTimeSeconds>${Duration.between(p.start, end).toMillis() / 1000.0}</TotalTimeSeconds>")
            val distance = p.values["distanceMeters"]?.takeIf { it.isFinite() && it >= 0 }
            append("<DistanceMeters>${distance ?: 0.0}</DistanceMeters>")
            append("<Calories>${p.values["caloriesKcal"]?.takeIf { it.isFinite() && it in 0.0..65535.0 }?.roundToInt() ?: 0}</Calories>")
            append("<Intensity>Active</Intensity><TriggerMethod>Manual</TriggerMethod><Track>")
            for (time in times) {
                append("<Trackpoint><Time>$time</Time>")
                route[time]?.let { point ->
                    append("<Position><LatitudeDegrees>${point.latitude}</LatitudeDegrees><LongitudeDegrees>${point.longitude}</LongitudeDegrees></Position>")
                    point.altitude?.takeIf { it.isFinite() }?.let { append("<AltitudeMeters>$it</AltitudeMeters>") }
                }
                streams["heart"]?.get(time)?.let { append("<HeartRateBpm><Value>${it.roundToInt()}</Value></HeartRateBpm>") }
                val speed = streams["speed"]?.get(time)
                val cadence = streams["cadence"]?.get(time)
                val power = streams["power"]?.get(time)
                if (speed != null || cadence != null || power != null) {
                    append("<Extensions><ae:TPX>")
                    speed?.let { append("<ae:Speed>$it</ae:Speed>") }
                    cadence?.let { append("<ae:RunCadence>${(it / 2.0).roundToInt()}</ae:RunCadence>") }
                    power?.let { append("<ae:Watts>${it.roundToInt()}</ae:Watts>") }
                    append("</ae:TPX></Extensions>")
                }
                append("</Trackpoint>")
            }
            append("</Track></Lap><Notes>${escape(p.text["title"].orEmpty())}</Notes></Activity></Activities></TrainingCenterDatabase>")
        }
    }

    private fun escape(text: String) = text.filter { it >= ' ' || it in "\n\r\t" }
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
