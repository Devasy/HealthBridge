package com.repforge.healthbridge.strava

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repforge.healthbridge.data.SourcePoint
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun StravaScreen(model: StravaViewModel, back: () -> Unit, open: (Uri) -> Unit,
    permissions: () -> Unit, chooseDate: () -> Unit, export: (SourcePoint) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<SourcePoint?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); model.clearMessage() } }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                TextButton(onClick = back) { Text("Back") }
                Text("Strava sync", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                Text("Your Samsung workouts, sent by Health Bridge.", style = MaterialTheme.typography.bodyLarge)
            }
            item { StravaConnection(state, model::setRelay, { model.connect(open) }, model::disconnect) }
            item {
                FilledTonalButton(onClick = permissions, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Allow Samsung workouts & routes") }
                OutlinedButton(onClick = chooseDate, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(state.day.format(DateTimeFormatter.ofPattern("d MMMM yyyy")))
                }
                Button(onClick = { model.scan() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Read workouts for this day") }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            }
            if (state.scanned && state.workouts.isEmpty()) item { Text("No runs, walks or hikes found. Choose another day or check Samsung permissions.") }
            items(state.workouts, key = { it.uid }) { point ->
                WorkoutUploadCard(point, state.uploads[point.uid], state.busy, state.athlete != null,
                    upload = { entry -> if (entry?.phase == UploadPhase.PROCESSING || entry?.phase == UploadPhase.SUBMITTING) model.upload(point) else confirm = point },
                    export = { export(point) }, open = open)
            }
            item {
                Text("Uploads happen only when you choose one. GPS, heart rate and other available workout measurements go directly to Strava and use your Strava privacy settings. Other services may already have uploaded the same workout.", style = MaterialTheme.typography.bodySmall)
                Text("Samsung → Health Connect sync is independent and continues to work without Strava.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    confirm?.let { point ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Upload this workout?") },
            text = { Text("Send this workout and its available GPS route, HR, cadence, speed and power to your connected Strava account? Check Strava first if another app may have uploaded it.") },
            confirmButton = { TextButton(onClick = { confirm = null; model.upload(point) }) { Text("Upload to Strava") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
    }
}

@Composable
private fun StravaConnection(state: StravaState, setRelay: (String) -> Unit, connect: () -> Unit, disconnect: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (state.athlete != null) "Strava connected" else "Connect your Strava account", style = MaterialTheme.typography.titleLarge)
            if (state.athlete != null) {
                Text("Account ${state.athlete} · upload history stays on this phone")
                TextButton(onClick = disconnect, enabled = !state.busy) { Text("Disconnect on this phone") }
            } else {
                Text("One-time setup needs an HTTPS authorization service. Your Strava app secret stays on that service. You can export TCX files below without connecting.")
                OutlinedTextField(value = state.relay, onValueChange = setRelay, label = { Text("Authorization service URL") },
                    placeholder = { Text("https://your-service.example") }, singleLine = true, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth())
                Button(onClick = connect, enabled = !state.busy && state.relay.isNotBlank()) { Text("Connect Strava") }
            }
        }
    }
}

@Composable
private fun WorkoutUploadCard(point: SourcePoint, entry: UploadEntry?, busy: Boolean, connected: Boolean,
    upload: (UploadEntry?) -> Unit, export: () -> Unit, open: (Uri) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(point.text["title"]?.takeIf { it.isNotBlank() } ?: point.text["exercise"].orEmpty().lowercase().replaceFirstChar { it.titlecase() },
                style = MaterialTheme.typography.titleLarge)
            val local = point.start.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
            val distance = point.values["distanceMeters"]?.let { String.format(Locale.getDefault(), "%.2f km", it / 1000) } ?: "Distance unavailable"
            Text("$local · $distance · ${java.time.Duration.between(point.start, point.end).toMinutes()} min")
            Text("GPS ${point.route.size} points · HR ${point.series["heart"].orEmpty().size} · Cadence ${point.series["cadence"].orEmpty().size} · Speed ${point.series["speed"].orEmpty().size}",
                style = MaterialTheme.typography.bodyMedium)
            if (point.text["routeReadAllowed"] != "true") Text("GPS access is missing. Allow Samsung exercise location before uploading a route.", color = MaterialTheme.colorScheme.error)
            entry?.let { Text(it.message, style = MaterialTheme.typography.bodyMedium) }
            if (entry?.activityId != null) {
                Button(onClick = { open(Uri.parse("https://www.strava.com/activities/${entry.activityId}")) }) { Text("View on Strava") }
            } else if (entry?.phase !in setOf(UploadPhase.UNKNOWN, UploadPhase.DUPLICATE, UploadPhase.SYNCED)) {
                Button(onClick = { upload(entry) }, enabled = connected && !busy) {
                    Text(when (entry?.phase) {
                        UploadPhase.PROCESSING, UploadPhase.SUBMITTING -> "Check upload status"
                        UploadPhase.FAILED -> "Retry upload"
                        else -> "Upload to Strava"
                    })
                }
            }
            TextButton(onClick = export, enabled = !busy) { Text("Save TCX file") }
        }
    }
}
