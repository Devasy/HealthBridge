package com.repforge.healthbridge.strava

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.repforge.healthbridge.ui.BridgeTheme
import java.time.LocalDate

class StravaActivity : ComponentActivity() {
    private val model: StravaViewModel by viewModels()
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.garmin.tcx+xml")) { model.writeExport(it) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BridgeTheme { StravaScreen(model,
            back = { finish() }, open = ::open,
            permissions = { model.permissions(this) },
            chooseDate = {
                val day = model.state.value.day
                DatePickerDialog(this, { _, y, m, d -> model.setDay(LocalDate.of(y, m + 1, d)) }, day.year, day.monthValue - 1, day.dayOfMonth).show()
            }, export = { point -> model.prepareExport(point); export.launch("health-bridge-${point.start.epochSecond}.tcx") }) } }
        if (savedInstanceState == null) intent.data?.let(model::callback)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let(model::callback)
    }
    private fun open(uri: Uri) { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}
