package com.snoremask.app

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.snoremask.app.audio.MaskState
import com.snoremask.app.service.MaskingService

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("snoremask", Context.MODE_PRIVATE)
        MaskState.baseLevel.value = prefs.getFloat(KEY_BASE, 0.08f)
        MaskState.maxLevel.value = prefs.getFloat(KEY_MAX, 0.50f)
        MaskState.sensitivity.value = prefs.getFloat(KEY_SENS, 0.5f)

        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AppScaffold(
                    onPersist = { key, value -> prefs.edit().putFloat(key, value).apply() }
                )
            }
        }
    }

    companion object {
        const val KEY_BASE = "base_level"
        const val KEY_MAX = "max_level"
        const val KEY_SENS = "sensitivity"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(onPersist: (String, Float) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("SnoreMasker") },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.Menu, contentDescription = "Menu")
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("About") },
                            onClick = {
                                menuOpen = false
                                showAbout = true
                            }
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        HomeScreen(
            modifier = Modifier.padding(innerPadding),
            onPersist = onPersist
        )
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        title = { Text("SnoreMasker") },
        text = {
            Column {
                Text("Version ${BuildConfig.VERSION_NAME}", style = typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Masks a partner's snoring with adaptive brown noise played " +
                        "through your Bluetooth earbuds. The phone listens for snoring " +
                        "and gently swells the noise to cover it, then fades back to a " +
                        "quiet baseline.",
                    style = typography.bodySmall
                )
            }
        }
    )
}

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onPersist: (String, Float) -> Unit
) {
    val context = LocalContext.current
    val running by MaskState.running.collectAsStateWithLifecycle()
    val masking by MaskState.masking.collectAsStateWithLifecycle()
    val base by MaskState.baseLevel.collectAsStateWithLifecycle()
    val max by MaskState.maxLevel.collectAsStateWithLifecycle()
    val sensitivity by MaskState.sensitivity.collectAsStateWithLifecycle()
    val micLevel by MaskState.micLevel.collectAsStateWithLifecycle()

    // Request mic (required for detection) + notifications, then start the service.
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { MaskingService.start(context) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(
                        color = when {
                            running && masking -> Color(0xFFE8C547) // swelled — snore
                            running -> Color(0xFF4CAF50)            // armed, quiescent
                            else -> Color(0xFF666666)               // stopped
                        }
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        text = when {
                            running && masking -> "Snore detected — masking"
                            running -> "Listening — quiescent floor"
                            else -> "Stopped"
                        },
                        style = typography.titleMedium
                    )
                }
                Spacer(Modifier.height(16.dp))

                // Live mic meter.
                Text("Mic level", style = typography.bodySmall)
                LinearProgressIndicator(
                    progress = { micLevel },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                )

                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    if (running) {
                        MaskingService.stop(context)
                    } else {
                        val perms = buildList {
                            add(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }.toTypedArray()
                        permLauncher.launch(perms)
                    }
                }) {
                    Text(if (running) "Stop" else "Start")
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        LevelSlider(
            label = "Quiescent level",
            help = "Soft brown noise that plays all night.",
            value = base,
            onChange = { MaskState.baseLevel.value = it },
            onCommit = { onPersist(MainActivity.KEY_BASE, base) }
        )
        Spacer(Modifier.height(16.dp))
        LevelSlider(
            label = "Max level (during snoring)",
            help = "Ceiling the masker swells up to when snoring is detected.",
            value = max,
            onChange = { MaskState.maxLevel.value = it },
            onCommit = { onPersist(MainActivity.KEY_MAX, max) }
        )
        Spacer(Modifier.height(16.dp))
        LevelSlider(
            label = "Sensitivity",
            help = "Higher triggers masking on quieter sounds. Place the phone near the snorer.",
            value = sensitivity,
            onChange = { MaskState.sensitivity.value = it },
            onCommit = { onPersist(MainActivity.KEY_SENS, sensitivity) }
        )
    }
}

@Composable
private fun StatusDot(color: Color) {
    Surface(color = color, shape = CircleShape, modifier = Modifier.size(14.dp)) {}
}

@Composable
private fun LevelSlider(
    label: String,
    help: String,
    value: Float,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("$label: ${(value * 100).toInt()}%", style = typography.bodyLarge)
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onCommit,
            valueRange = 0f..1f
        )
        Text(help, style = typography.bodySmall, textAlign = TextAlign.Start, color = colorScheme.onSurfaceVariant)
    }
}
