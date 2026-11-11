package com.repforge.healthbridge.strava

import org.junit.Assert.*
import org.junit.Test

class OAuthPolicyTest {
    @Test fun `relay is HTTPS origin without credentials query or path`() {
        assertEquals("https://bridge.example", OAuthPolicy.relay("https://bridge.example/"))
        for (url in listOf("http://bridge.example", "https://user:secret@bridge.example", "https://bridge.example/path",
            "https://bridge.example?token=secret", "https://bridge.example/#fragment")) {
            assertThrows(IllegalArgumentException::class.java) { OAuthPolicy.relay(url) }
        }
    }
    @Test fun `callback requires exact destination matching state and fresh attempt`() {
        val attempt = OAuthAttempt("state", "verifier", 1000, "https://bridge.example")
        assertEquals("valid", OAuthPolicy.code("healthbridge-strava://oauth/callback?state=state&code=valid", attempt, 2000))
        for (uri in listOf("healthbridge-strava://oauth/callback?state=wrong&code=valid",
            "healthbridge-strava://other/callback?state=state&code=valid",
            "healthbridge-strava://oauth/callback?state=state&error=access_denied",
            "healthbridge-strava://oauth/callback?state=state&code=a&code=b")) {
            assertThrows(IllegalArgumentException::class.java) { OAuthPolicy.code(uri, attempt, 2000) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            OAuthPolicy.code("healthbridge-strava://oauth/callback?state=state&code=valid", attempt, 700000)
        }
    }
}
