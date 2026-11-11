package com.repforge.healthbridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.repforge.healthbridge.ui.BridgeDashboard
import com.repforge.healthbridge.ui.BridgeTheme
import java.security.MessageDigest

class MainActivity : ComponentActivity() {
    private val model: BridgeViewModel by viewModels()
    private val healthPermission = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        model.refresh()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BridgeTheme {
                BridgeDashboard(model,
                    explore = { open(Intent(this, com.repforge.healthbridge.explorer.ExplorerActivity::class.java)) },
                    strava = { open(Intent(this, com.repforge.healthbridge.strava.StravaActivity::class.java)) },
                    connectSamsung = { model.requestSamsung(this) },
                    connectHealth = {
                        try {
                            if (HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE) {
                                val permissions = model.requiredHealthPermissions()
                                if (permissions.isNotEmpty()) healthPermission.launch(permissions)
                            } else open(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")))
                        } catch (_: Exception) { model.showMessage("Open Health Connect in Settings to manage access.") }
                    },
                    openSamsung = { packageManager.getLaunchIntentForPackage("com.sec.android.app.shealth")?.let(::open)
                        ?: model.showMessage("Install or enable Samsung Health first.") },
                    openHealth = { open(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) },
                    fingerprint = { fingerprint() },
                    privacy = { open(Intent(this, PrivacyActivity::class.java)) },
                )
            }
        }
        inspectRequestedWorkout(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        inspectRequestedWorkout(intent)
    }
    private fun inspectRequestedWorkout(intent: Intent) {
        val date = intent.getStringExtra("workout_date")?.let {
            runCatching { java.time.LocalDate.parse(it) }.getOrNull()
        } ?: return
        open(Intent(this, com.repforge.healthbridge.explorer.ExplorerActivity::class.java)
            .putExtra("workout_date", date.toString()))
    }
    override fun onResume() { super.onResume(); model.refresh() }
    private fun open(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) { model.showMessage("This app or settings screen is unavailable on this phone.") }
    }
    private fun fingerprint(): String = try {
        @Suppress("DEPRECATION")
        val info = packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
        val signature = info.signingInfo?.apkContentsSigners?.firstOrNull()
        signature?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray())
            .joinToString(":") { byte -> "%02X".format(byte) } } ?: "Unavailable"
    } catch (_: Exception) { "Unavailable" }
}
