package com.repforge.healthbridge.explorer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private fun number(value: Double?) = value?.let { String.format(Locale.getDefault(), "%.2f", it) } ?: "—"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerScreen(model: ExplorerViewModel, onBack: () -> Unit, samsungAccess: () -> Unit,
    respiratoryAccess: () -> Unit, importFile: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var sourceMenu by remember { mutableStateOf(false) }
    var fieldMenu by remember { mutableStateOf(false) }
    var catalogExpanded by remember { mutableStateOf(false) }
    var dateDialog by remember { mutableStateOf(false) }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); model.message(null) } }
    val selected = state.sources.find { it.key == state.selected }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        TopAppBar(title = { Text("Data explorer") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 680.dp).fillMaxWidth(), contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Text("Every available reading", style = MaterialTheme.typography.headlineMedium)
                    Text("Browse the public Samsung SDK catalog, scan your permitted records, and chart the numeric fields. Text and categorical values appear in the record list.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp))
                }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChartRange.entries.forEach { range -> FilterChip(state.range == range, { model.range(range) },
                            label = { Text(range.label) }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) }
                    }
                    TextButton(onClick = { dateDialog = true }, enabled = !state.busy) { Text("Ending ${state.anchor} · change date") }
                    Text(if (state.range == ChartRange.DAY) "All recorded points in the selected local-calendar day."
                        else "Daily min–max, median and mean. Missing days stay empty.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Range changes use cached data. Scan this source to fetch the selected dates.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    Box {
                        OutlinedButton(onClick = { sourceMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(selected?.title ?: "Choose a data source")
                        }
                        DropdownMenu(sourceMenu, { sourceMenu = false }, modifier = Modifier.heightIn(max = 420.dp)) {
                            state.sources.forEach { source -> DropdownMenuItem(text = { Text(source.title) }, onClick = {
                                sourceMenu = false; model.source(source.key)
                            }) }
                        }
                    }
                    selected?.let { Text(it.detail, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                    if (state.fields.isNotEmpty()) Box(Modifier.padding(top = 8.dp)) {
                        FilledTonalButton(onClick = { fieldMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(fieldLabel(state.field))
                        }
                        DropdownMenu(fieldMenu, { fieldMenu = false }, modifier = Modifier.heightIn(max = 400.dp)) {
                            state.fields.forEach { field -> DropdownMenuItem(text = {
                                Text("${fieldLabel(field.key)} (${field.count} cached values)")
                            }, onClick = { fieldMenu = false; model.field(field.key) }) }
                        }
                    }
                }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { model.scan(false) }, enabled = !state.busy && state.selected != RespiratoryImport.SOURCE) { Text("Scan this source") }
                        FilledTonalButton(onClick = { model.scan(true) }, enabled = !state.busy) { Text("Scan all types") }
                    }
                    if (state.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                        Text(state.progress, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = model::cancel) { Text("Cancel scan") }
                    }
                }
                item {
                    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(if (state.field.isBlank()) "No recorded values yet" else fieldLabel(state.field), style = MaterialTheme.typography.titleLarge)
                            Text(state.data.resolution.ifBlank { "No demo or interpolated readings" }, style = MaterialTheme.typography.bodySmall)
                            if (state.data.points.isEmpty()) {
                                Text("No numeric readings found for this field and date range. Grant access and scan, choose another date, or import a respiratory export. Text values, when available, are listed below.")
                            } else {
                                MetricChart(state.data, state.range == ChartRange.DAY)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Stat("Readings", state.data.count.toString())
                                    Stat("Minimum", number(state.data.min))
                                    Stat("Maximum", number(state.data.max))
                                    Stat("Mean", number(state.data.mean))
                                    if (state.range == ChartRange.DAY) Stat("Median", number(state.data.median))
                                }
                                Text(state.data.unit.ifBlank { "Unit not specified by this field" }, style = MaterialTheme.typography.labelMedium)
                                if (state.range != ChartRange.DAY) Text("The chart's solid line is the daily median. Range bars show daily min–max; the dashed line shows the daily mean. Tap a day for exact statistics.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                item {
                    Text("Access & respiratory data", style = MaterialTheme.typography.titleLarge)
                    Text("The SDK does not expose respiratory rate. Health Connect is the source used by the Flutter debug inspector. If it has no real records, import Samsung Health → Settings → Download personal data. The ZIP includes respiratory summaries and any raw binning samples present in your export.",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(samsungAccess, enabled = !state.busy) { Text("Allow Samsung reads") }
                        OutlinedButton(respiratoryAccess, enabled = !state.busy) { Text("Allow respiratory reads") }
                        OutlinedButton(importFile, enabled = !state.busy) { Text("Import respiratory ZIP / CSV") }
                    }
                }
                item {
                    TextButton(onClick = { catalogExpanded = !catalogExpanded }) {
                        Text(if (catalogExpanded) "Hide source catalog" else "Show all ${state.sources.size} sources")
                    }
                }
                if (catalogExpanded) items(state.sources.size, key = { "catalog:${state.sources[it].key}" }) { index ->
                    val source = state.sources[index]
                    Surface(onClick = { model.source(source.key) }, shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(source.title, style = MaterialTheme.typography.titleMedium)
                            Text(source.detail, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (state.data.rows.isNotEmpty()) {
                    item {
                        Text("Latest recorded values", style = MaterialTheme.typography.titleLarge)
                        Text("Showing up to 100 rows. The chart includes all numeric readings in the selected range.", style = MaterialTheme.typography.bodySmall)
                    }
                    items(state.data.rows.size) { index ->
                        val row = state.data.rows[index]
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text("${row.value} ${state.data.unit}", style = MaterialTheme.typography.titleMedium)
                            Text(row.time.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss")), style = MaterialTheme.typography.bodySmall)
                            Text(row.origin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    if (dateDialog) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = state.anchor.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { dateDialog = false }, confirmButton = {
            TextButton(onClick = {
                dateState.selectedDateMillis?.let { model.date(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                dateDialog = false
            }) { Text("Show date") }
        }, dismissButton = { TextButton({ dateDialog = false }) { Text("Cancel") } }) { DatePicker(dateState) }
    }
}

@Composable private fun Stat(label: String, value: String) {
    Column { Text(value, style = MaterialTheme.typography.titleMedium); Text(label, style = MaterialTheme.typography.labelSmall) }
}

@Composable
private fun MetricChart(data: ChartData, raw: Boolean) {
    val points = data.points
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    var selected by remember(points) { mutableStateOf<ChartPoint?>(null) }
    val low = points.minOf { it.min }
    val high = points.maxOf { it.max }
    val padding = ((high - low) * .08).coerceAtLeast(if (high == low) 1.0 else .01)
    val bottom = low - padding
    val top = high + padding
    val first = points.first().time.toEpochMilli()
    val last = points.last().time.toEpochMilli()
    Row(Modifier.fillMaxWidth().height(230.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.width(60.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(number(top), style = MaterialTheme.typography.labelSmall)
            Text(number((top + bottom) / 2), style = MaterialTheme.typography.labelSmall)
            Text(number(bottom), style = MaterialTheme.typography.labelSmall)
        }
        Canvas(Modifier.weight(1f).fillMaxHeight().semantics {
            contentDescription = "${data.count} readings. Minimum ${number(data.min)}, maximum ${number(data.max)}, mean ${number(data.mean)} ${data.unit}."
        }.pointerInput(points) {
            detectTapGestures { offset ->
                val target = first.toDouble() + (offset.x / size.width).coerceIn(0f, 1f).toDouble() * (last - first).toDouble()
                selected = points.minByOrNull { abs(it.time.toEpochMilli() - target) }
            }
        }) {
            fun x(p: ChartPoint) = if (first == last) size.width / 2 else ((p.time.toEpochMilli() - first).toDouble() / (last - first) * size.width).toFloat()
            fun y(value: Double) = ((top - value) / (top - bottom) * size.height).toFloat()
            for (fraction in listOf(0f, .5f, 1f)) drawLine(grid, Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction))
            val median = Path(); val mean = Path()
            points.forEachIndexed { index, p ->
                val px = x(p)
                if (!raw) drawLine(primary.copy(alpha = .22f), Offset(px, y(p.min)), Offset(px, y(p.max)),
                    strokeWidth = (size.width / points.size * .6f).coerceIn(1f, 12f))
                val gap = index == 0 || (!raw && p.time.epochSecond - points[index - 1].time.epochSecond > 36 * 3600)
                if (gap) { median.moveTo(px, y(p.median)); mean.moveTo(px, y(p.mean)) }
                else { median.lineTo(px, y(p.median)); mean.lineTo(px, y(p.mean)) }
                if (points.size < 100) drawCircle(primary, 2.5.dp.toPx(), Offset(px, y(p.median)))
            }
            drawPath(median, primary, style = Stroke(width = 2.dp.toPx()))
            if (!raw) drawPath(mean, secondary, style = Stroke(width = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 4.dp.toPx()))))
            selected?.let { p ->
                drawLine(grid, Offset(x(p), 0f), Offset(x(p), size.height))
                drawCircle(primary, 5.dp.toPx(), Offset(x(p), y(p.median)))
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val formatter = DateTimeFormatter.ofPattern(if (raw) "HH:mm" else "dd MMM")
        Text(points.first().time.atZone(ZoneId.systemDefault()).format(formatter), style = MaterialTheme.typography.labelSmall)
        Text(points.last().time.atZone(ZoneId.systemDefault()).format(formatter), style = MaterialTheme.typography.labelSmall)
    }
    selected?.let { p ->
        Text("${p.time.atZone(ZoneId.systemDefault()).toLocalDate()} · n=${p.count}\nMin ${number(p.min)} · Max ${number(p.max)} · Median ${number(p.median)} · Mean ${number(p.mean)} ${data.unit}",
            style = MaterialTheme.typography.bodySmall)
    }
}
