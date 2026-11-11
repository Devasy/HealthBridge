package com.repforge.healthbridge.strava

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StravaHttp(private val storage: StravaStorage) {
    suspend fun config(relay: String) = withContext(Dispatchers.IO) {
        val response = request("GET", "${OAuthPolicy.relay(relay)}/config")
        check(response.first == 200 && response.second["client_id"]?.asString?.all { it.isDigit() } == true) {
            "Could not reach the authorization service. Check its HTTPS address."
        }
    }
    suspend fun exchange(code: String, attempt: OAuthAttempt): StravaTokens = withContext(Dispatchers.IO) {
        val fields = JsonObject().apply { addProperty("grant_type", "authorization_code"); addProperty("code", code)
            addProperty("state", attempt.state); addProperty("verifier", attempt.verifier) }
        val response = request("POST", "${attempt.relay}/token", fields.toString().toByteArray(), "application/json")
        check(response.first == 200) { "Strava connection failed. Connect again." }
        parseTokens(response.second, null).also { storage.tokens = it }
    }
    suspend fun uploadPort(): StravaUploadPort = withContext(Dispatchers.IO) {
        var tokens = requireNotNull(storage.tokens) { "Connect Strava first." }
        if (tokens.expires <= System.currentTimeMillis() / 1000 + 120) {
            val fields = JsonObject().apply { addProperty("grant_type", "refresh_token"); addProperty("refresh_token", tokens.refresh) }
            val response = request("POST", "${OAuthPolicy.relay(storage.relay)}/token", fields.toString().toByteArray(), "application/json")
            check(response.first == 200) { "Strava authorization expired or the service is unavailable. Connect again if needed." }
            tokens = parseTokens(response.second, tokens.athleteId).also { storage.tokens = it }
        }
        ApiPort(tokens.access)
    }
    private fun parseTokens(o: JsonObject, athlete: Long?): StravaTokens {
        val id = o["athlete"]?.asJsonObject?.get("id")?.asLong ?: requireNotNull(athlete)
        require(id > 0)
        return StravaTokens(id, o["access_token"].asString, o["refresh_token"].asString, o["expires_at"].asLong).also {
            require(it.access.isNotBlank() && it.refresh.isNotBlank())
        }
    }
    private inner class ApiPort(private val token: String) : StravaUploadPort {
        override suspend fun submit(tcx: String, externalId: String, name: String): Long = withContext(Dispatchers.IO) {
            val boundary = "HealthBridge${UUID.randomUUID()}"
            val body = ByteArrayOutputStream()
            fun text(value: String) { body.write(value.toByteArray(Charsets.UTF_8)) }
            fun field(key: String, value: String) { text("--$boundary\r\nContent-Disposition: form-data; name=\"$key\"\r\n\r\n$value\r\n") }
            field("data_type", "tcx.gz"); field("external_id", externalId); field("name", name.take(200))
            text("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$externalId.gz\"\r\nContent-Type: application/gzip\r\n\r\n")
            val compressed = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(tcx.toByteArray(Charsets.UTF_8)) } }.toByteArray()
            body.write(compressed); text("\r\n--$boundary--\r\n")
            val (status, result) = request("POST", "https://www.strava.com/api/v3/uploads", body.toByteArray(), "multipart/form-data; boundary=$boundary", token)
            if (status in 400..499) throw UploadRejected(result.toString().contains("duplicate", ignoreCase = true))
            if (status != 201) throw IOException("Upload response unavailable")
            result["id"]?.asLong?.takeIf { it > 0 } ?: throw IOException("Upload identifier unavailable")
        }
        override suspend fun poll(uploadId: Long): UploadStatus = withContext(Dispatchers.IO) {
            val (status, result) = request("GET", "https://www.strava.com/api/v3/uploads/$uploadId", bearer = token)
            check(status == 200)
            val error = result["error"]?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
            UploadStatus(result["activity_id"]?.takeUnless { it.isJsonNull }?.asLong?.takeIf { it > 0 },
                rejected = error != null, duplicate = error?.contains("duplicate", ignoreCase = true) == true)
        }
    }
    private fun request(method: String, url: String, body: ByteArray? = null, type: String? = null, bearer: String? = null): Pair<Int, JsonObject> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method; connection.connectTimeout = 15000; connection.readTimeout = 20000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true; connection.setFixedLengthStreamingMode(body.size)
                connection.setRequestProperty("Content-Type", type)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = input?.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 1024 * 1024) {
                    val size = stream.read(buffer)
                    if (size < 0) break
                    output.write(buffer, 0, size)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            check(bytes.size <= 1024 * 1024) { "Response too large" }
            val parsed = runCatching { JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject }.getOrDefault(JsonObject())
            return status to parsed
        } finally { connection.disconnect() }
    }
}
