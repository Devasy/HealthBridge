package com.repforge.healthbridge.strava

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class StravaTokens(val athleteId: Long, val access: String, val refresh: String, val expires: Long)

class StravaStorage(context: Context) : UploadJournal {
    private val prefs = context.getSharedPreferences("strava", Context.MODE_PRIVATE)
    var relay: String
        get() = prefs.getString("relay", "").orEmpty()
        set(value) { check(prefs.edit().putString("relay", value).commit()) }
    var tokens: StravaTokens?
        get() = readSecret("tokens")?.let { o -> StravaTokens(o["athlete"].asLong, o["access"].asString, o["refresh"].asString, o["expires"].asLong) }
        set(value) { writeSecret("tokens", value?.let { t -> JsonObject().apply {
            addProperty("athlete", t.athleteId); addProperty("access", t.access); addProperty("refresh", t.refresh); addProperty("expires", t.expires)
        } }) }
    var attempt: OAuthAttempt?
        get() = readSecret("attempt")?.let { o -> OAuthAttempt(o["state"].asString, o["verifier"].asString, o["created"].asLong, o["relay"].asString) }
        set(value) { writeSecret("attempt", value?.let { a -> JsonObject().apply {
            addProperty("state", a.state); addProperty("verifier", a.verifier); addProperty("created", a.created); addProperty("relay", a.relay)
        } }) }
    override fun get(key: String): UploadEntry? = prefs.getString("upload:$key", null)?.let {
        val o = JsonParser.parseString(it).asJsonObject
        UploadEntry(key, UploadPhase.valueOf(o["phase"].asString), o["upload"]?.asLong,
            o["activity"]?.asLong, o["message"]?.asString.orEmpty())
    }
    override fun put(entry: UploadEntry) {
        val o = JsonObject().apply {
            addProperty("phase", entry.phase.name); entry.uploadId?.let { addProperty("upload", it) }
            entry.activityId?.let { addProperty("activity", it) }; addProperty("message", entry.message)
        }
        check(prefs.edit().putString("upload:${entry.key}", o.toString()).commit()) { "Could not save upload status." }
    }
    fun disconnect() { tokens = null; attempt = null }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("healthbridge.strava", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("healthbridge.strava", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun readSecret(name: String): JsonObject? {
        val encrypted = prefs.getString(name, null) ?: return null
        return try {
            val bytes = Base64.getDecoder().decode(encrypted)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            JsonParser.parseString(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)).asJsonObject
        } catch (_: Exception) {
            check(prefs.edit().remove(name).commit())
            null
        }
    }
    private fun writeSecret(name: String, value: JsonObject?) {
        if (value == null) { check(prefs.edit().remove(name).commit()); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val bytes = cipher.iv + cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(name, Base64.getEncoder().encodeToString(bytes)).commit())
    }
}
