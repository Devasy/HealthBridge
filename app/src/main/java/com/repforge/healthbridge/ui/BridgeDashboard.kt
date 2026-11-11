package com.repforge.healthbridge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repforge.healthbridge.BridgeViewModel
import com.repforge.healthbridge.data.Metric
import com.repforge.healthbridge.data.bridgeMetrics
import java.text.DateFormat
import java.util.Date

@Composable
fun BridgeDashboard(
    model: BridgeViewModel,
    explore: () -> Unit,
    strava: () -> Unit,
    connectSamsung: () -> Unit, connectHealth: () -> Unit,
    openSamsung: () -> Unit, openHealth: () -> Unit,
    fingerprint: () -> String, privacy: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val ready = !state.loading && model.ready(state)
    val snackbar = remember { SnackbarHostState() }
    var setupExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); model.clearMessage() }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 600.dp).fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Health\nBridge", style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        FilledTonalIconButton(onClick = privacy, modifier = Modifier.size(52.dp)) {
                            Icon(Icons.Default.Info, contentDescription = "Privacy and app information")
                        }
                    }
                    Text("Samsung Health, connected.", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
                }
                item {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomEnd = 32.dp, bottomStart = 12.dp)) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(if (ready) Icons.Default.CheckCircle else Icons.Default.Refresh, null, Modifier.size(32.dp))
                                Text(if (state.busy) "Bridging your data" else if (state.loading) "Checking connections"
                                    else if (ready) "Ready when you are" else "Let's connect", style = MaterialTheme.typography.headlineSmall)
                            }
                            Text(if (state.busy) "Reading ${state.progress.lowercase()}…"
                                else if (ready) "Read all permitted Samsung categories. Compatible measurements go to Health Connect; Samsung-only details stay in the explorer."
                                else "Choose what to read, then connect Samsung Health. Health Connect is needed only for standard measurements.",
                                style = MaterialTheme.typography.bodyLarge)
                            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                            else Text("All available Samsung data, on your phone", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                item {
                    Button(onClick = model::sync, enabled = ready && !state.busy && !state.permissionBusy,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 64.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp)) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(if (state.busy) "Syncing" else "Sync now", style = MaterialTheme.typography.titleMedium)
                    }
                    if (state.selected.isEmpty()) Text("Select at least one measurement below.",
                        Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.lastRun > 0) {
                        Text("Last attempt · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(state.lastRun))}",
                            Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(state.report, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                item {
                    FilledTonalButton(onClick = explore, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text("Explore all data & charts")
                    }
                }
                item { SectionTitle("Connections") }
                item {
                    OutlinedButton(onClick = strava, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text("Strava · sync workouts")
                    }
                }
                item {
                    ConnectionRow("Samsung Health", "${state.samsungAllowed} of ${state.samsungTotal} Samsung read permissions allowed",
                        Icons.Default.Favorite, state.samsungAllowed == state.samsungTotal && state.samsungTotal > 0,
                        enabled = state.selected.isNotEmpty() && !state.permissionBusy && !state.busy,
                        onClick = connectSamsung, first = true)
                }
                item {
                    ConnectionRow("Health Connect", if (!state.healthAvailable) "Install or update to continue"
                        else if (model.healthReady(state)) "Measurements connected · Samsung scores stay in this app"
                        else "Allow writing standard measurements", Icons.Default.CheckCircle,
                        model.healthReady(state),
                        enabled = state.selected.isNotEmpty() && !state.permissionBusy && !state.busy,
                        onClick = connectHealth, first = false)
                }
                item {
                    TextButton(onClick = { setupExpanded = !setupExpanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (setupExpanded) "Hide setup help" else "Need help connecting?")
                        Icon(if (setupExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
                    }
                    AnimatedVisibility(setupExpanded) {
                        SetupHelp(fingerprint, openSamsung, openHealth)
                    }
                }
                item {
                    SectionTitle("Your Samsung insights")
                    Text("Latest available reading for each metric. Dates show when Samsung recorded it.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.insights.isEmpty()) Text("No saved scores yet. Connect Samsung Health and sync to read your recorded data.",
                        Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                }
                items(state.insights.size, key = { "insight:${state.insights[it].label}" }) { index ->
                    val insight = state.insights[index]
                    Surface(shape = groupShape(index, state.insights.size), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Column(Modifier.fillMaxWidth().padding(20.dp)) {
                            Text(insight.label, style = MaterialTheme.typography.titleMedium)
                            Text(insight.value, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 4.dp))
                            Text(insight.date, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                item {
                    SectionTitle("Measurements")
                    Text("Choose which measurements this bridge writes. Heart rate and sleep are optional because Samsung may already sync them. Avoid enabling both writers for the same categories. Scores, goals and detection results stay in the explorer when Health Connect has no equivalent. Samsung read access is separate from these export switches.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp))
                }
                items(bridgeMetrics.size, key = { bridgeMetrics[it].name }) { index ->
                    val metric = bridgeMetrics[index]
                    val unsupported = metric == Metric.SKIN && !state.loading && !state.skinAvailable
                    PreferenceRow(metric.title,
                        if (unsupported) "Needs a newer Health Connect version or Android update" else metric.description,
                        checked = metric in state.selected, enabled = !state.busy && !state.permissionBusy,
                        onChecked = { model.select(metric, it) },
                        shape = groupShape(index, bridgeMetrics.size))
                }
                item {
                    SectionTitle("Not exposed by Samsung's SDK")
                    Text("Respiratory rate, HRV and stress are not available through Samsung Health Data SDK 1.1.0. A number visible in Samsung Health is not necessarily available to other apps. We don't estimate replacements.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item { SectionTitle("Sync preferences") }
                item {
                    PreferenceRow("Background sync", "About every 6 hours, when battery allows. Off by default.",
                        state.background, !state.busy, model::setBackground, groupShape(0, 2))
                }
                item {
                    Surface(shape = groupShape(1, 2), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.fillMaxWidth().padding(20.dp)) {
                            Text("History to include", style = MaterialTheme.typography.titleMedium)
                            Text("First sync and manual backfill", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(7, 30, 90).forEach { days ->
                                    FilterChip(selected = state.days == days, onClick = { model.setDays(days) },
                                        label = { Text("$days days") }, enabled = !state.busy,
                                        modifier = Modifier.heightIn(min = 48.dp))
                                }
                            }
                        }
                    }
                }
                item {
                    Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        Text("On your phone. Under your control.\nNo account, analytics or internet access.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
}

private fun groupShape(index: Int, count: Int) = RoundedCornerShape(
    topStart = if (index == 0) 24.dp else 4.dp, topEnd = if (index == 0) 24.dp else 4.dp,
    bottomStart = if (index == count - 1) 24.dp else 4.dp, bottomEnd = if (index == count - 1) 24.dp else 4.dp,
)

@Composable
private fun ConnectionRow(title: String, description: String, icon: ImageVector, connected: Boolean,
    enabled: Boolean, onClick: () -> Unit, first: Boolean) {
    Surface(onClick = onClick, enabled = enabled, shape = groupShape(if (first) 0 else 1, 2),
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (connected) Icons.Default.CheckCircle else Icons.AutoMirrored.Filled.ArrowForward,
                if (connected) "Connected. Manage permissions" else "Connect", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun PreferenceRow(title: String, description: String, checked: Boolean, enabled: Boolean,
    onChecked: (Boolean) -> Unit, shape: RoundedCornerShape) {
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer) {
        // A single toggleable surface provides one TalkBack target for the entire preference.
        Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch,
            onValueChange = onChecked).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun SetupHelp(fingerprint: () -> String, openSamsung: () -> Unit, openHealth: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val certificate = remember { fingerprint() }
    Column(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("In Samsung Health, open Settings → About Samsung Health. Tap the version line 10 times, then enable Developer Mode for Data Read. Return here and connect.",
            style = MaterialTheme.typography.bodyMedium)
        Text("If your Samsung Health version asks for registration, use this app's package and signing fingerprint.",
            style = MaterialTheme.typography.bodyMedium)
        Text("com.repforge.healthbridge", style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { clipboard.setText(AnnotatedString("com.repforge.healthbridge\n$certificate")) }) {
            Text("Copy registration details")
        }
        TextButton(onClick = openSamsung) { Text("Open Samsung Health") }
        TextButton(onClick = openHealth) { Text("Open Health Connect") }
        Text("After syncing, allow RepForge to read these categories in Health Connect. New records are attributed to Health Bridge.",
            style = MaterialTheme.typography.bodyMedium)
    }
}
