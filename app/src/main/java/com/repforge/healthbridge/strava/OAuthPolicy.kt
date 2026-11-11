package com.repforge.healthbridge.strava

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class OAuthAttempt(val state: String, val verifier: String, val created: Long, val relay: String)

object OAuthPolicy {
    fun relay(value: String): String {
        val uri = URI(value.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.rawQuery == null && uri.fragment == null && uri.path.orEmpty() in setOf("", "/")) {
            "Enter the HTTPS address of your authorization service, without a path."
        }
        return uri.toString().trimEnd('/')
    }
    fun attempt(relay: String, now: Long = System.currentTimeMillis()) = OAuthAttempt(random(), random(), now, relay(relay))
    fun challenge(attempt: OAuthAttempt): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(attempt.verifier.toByteArray(Charsets.US_ASCII)))
    fun code(callback: String, attempt: OAuthAttempt, now: Long = System.currentTimeMillis()): String {
        val uri = URI(callback)
        require(uri.scheme == "healthbridge-strava" && uri.host == "oauth" && uri.path == "/callback" &&
            uri.userInfo == null && uri.port == -1 && uri.fragment == null)
        require(now >= attempt.created && now - attempt.created < 600000) { "Authorization expired. Connect again." }
        val pairs = uri.rawQuery.orEmpty().split('&').map {
            val parts = it.split('=', limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }
        require(pairs.map { it.first }.distinct().size == pairs.size) { "Invalid authorization response." }
        val values = pairs.toMap()
        require(values["state"] == attempt.state) { "Authorization did not match this connection attempt." }
        require(values["error"] == null) { "Strava access was not granted. Connect again and allow activity uploads." }
        return requireNotNull(values["code"]?.takeIf { it.isNotBlank() && it.length <= 2048 })
    }
    private fun random() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
}
