package com.repforge.healthbridge.data

import java.security.MessageDigest

/** Samsung continuation handles can remain unchanged while their result pages advance. */
class PageProgress {
    private val seen = HashSet<String>()
    private var pages = 0
    fun next(token: String?, identities: List<String>): String? {
        check(++pages <= 100000) { "Samsung page limit exceeded" }
        if (identities.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256")
        for (identity in identities) {
            val bytes = identity.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(bytes)
        }
        val signature = digest.digest().joinToString("") { "%02x".format(it) }
        check(seen.add(signature)) { "Samsung repeated a result page without progress" }
        return token?.takeIf { it.isNotBlank() }
    }
}
