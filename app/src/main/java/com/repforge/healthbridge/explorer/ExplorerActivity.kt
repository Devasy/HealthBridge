package com.repforge.healthbridge.explorer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.health.connect.client.PermissionController
import com.repforge.healthbridge.ui.BridgeTheme

class ExplorerActivity : ComponentActivity() {
    private val model: ExplorerViewModel by viewModels()
    private val respiratoryAccess = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        model.scan(false)
    }
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::importRespiratory)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intent.getStringExtra("workout_date")?.let { value ->
            runCatching { java.time.LocalDate.parse(value) }.getOrNull()?.let {
                // Only this foreground inspection window stays awake; closing it restores normal timeout.
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                model.inspectWorkout(it)
            }
        }
        setContent { BridgeTheme {
            ExplorerScreen(model, onBack = { finish() },
                samsungAccess = { model.requestSamsung(this) },
                respiratoryAccess = {
                    try { respiratoryAccess.launch(model.respiratoryPermissions()) }
                    catch (_: Exception) { model.message("Health Connect is unavailable or needs an update.") }
                },
                importFile = { importFile.launch(arrayOf("application/zip", "application/x-zip-compressed", "text/*", "application/octet-stream", "application/json")) })
        } }
    }
}
