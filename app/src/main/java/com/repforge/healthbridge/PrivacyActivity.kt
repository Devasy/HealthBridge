package com.repforge.healthbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.repforge.healthbridge.ui.BridgeTheme

class PrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BridgeTheme {
                Scaffold { insets ->
                    Column(Modifier.padding(insets).fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text("Your data, your connections", style = MaterialTheme.typography.headlineLarge)
                        Text("Health Bridge reads the measurements you select from Samsung Health and writes their equivalent records to Health Connect on this phone. Apps such as RepForge can read them only with their own Health Connect permissions.")
                        Text("Samsung Energy Score, Sleep Score and selected body-composition details are stored in this app's private database as a rolling 90-day history. Older entries are removed when the app next accesses the database. They are not uploaded or written into unrelated Health Connect records. Backup and device transfer are disabled. The app also stores settings, cursors and sync summaries; WorkManager stores scheduling information. There are no analytics or ads. Internet access is used for optional Strava authorization and uploads.")
                        Text("Sync and the Data explorer read all Samsung data types you permit, and the explorer can read respiratory rate from Health Connect. Recorded values and text fields are cached privately for up to 366 days to support year charts. Older entries are removed when the database is next opened. Imported respiratory exports remain on this phone and are labelled separately. Text/categorical fields are listed, not converted into numeric readings. Current profile fields are cached as a snapshot, not exported as historical measurements. With your Samsung exercise-location and Health Connect route-write permissions, recorded GPS coordinates, altitude and timestamped workout streams can be copied to Health Connect. Route coordinates can also appear in the private explorer cache. This imports existing recordings; the app does not activate GPS or record your location. Standard Health Connect sync and the explorer operate on this phone; optional Strava transfers are described below. Clear this app's storage to delete its local history and chart cache.")
                        Text("Samsung's Health Data SDK is a proprietary dependency. Developer Mode for Data Read is required for this development build; public distribution requires Samsung's approval.")
                        Text("To stop sharing, turn off background sync and revoke this app's access in Samsung Health and Health Connect. Existing exported data remains in Health Connect until you delete it there. Uninstalling this app stops future syncs.")
                        Text("Strava is optional. Only after you connect your account and confirm an upload does Health Bridge send that selected workout's TCX file, including available GPS coordinates, altitude, heart rate, cadence, speed and power, directly to Strava. Your Strava privacy settings apply. A configured HTTPS authorization service receives OAuth codes and tokens to exchange or refresh them with Strava; it does not receive workout files. The Strava client secret stays on that service. Tokens and pending authorization are encrypted using Android Keystore on this phone. Upload IDs and status are saved privately to prevent blind repeat uploads. Exporting a TCX file saves sensitive workout data to the location you choose. Disconnecting removes local tokens but does not delete uploaded activities or revoke access on Strava; manage those in Strava settings. Clearing app storage or uninstalling removes the local upload ledger, so duplicate prevention cannot be guaranteed after reinstalling.")
                        Text("Version 0.6.0 · Native Kotlin and Material 3 Expressive", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { finish() }) { Text("Back") }
                    }
                }
            }
        }
    }
}
