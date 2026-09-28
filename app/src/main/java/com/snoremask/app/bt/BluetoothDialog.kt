package com.snoremask.app.bt

import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

data class BtDeviceInfo(val name: String, val address: String)

/**
 * "Auto-start on Bluetooth" config page. Lists paired devices with a "None"
 * option at the top (the default), highlights the current choice, and persists
 * selection immediately. Picking a real device also offers the battery-
 * optimization exemption needed for reliable background auto-start.
 *
 * Caller must ensure BLUETOOTH_CONNECT is granted (API 31+) before showing this.
 *
 * Selecting a row saves the choice immediately and keeps the dialog open.
 * [onClose] is called once, when the dialog is dismissed, with the final choice
 * (empty address == None) — the caller then wires up / tears down auto-start
 * (CompanionDeviceManager). Deferring that avoids launching a system dialog from
 * inside this one, which previously closed it before the choice was saved.
 */
@Composable
fun BluetoothDialog(
    onClose: (address: String, name: String) -> Unit
) {
    val context = LocalContext.current
    val devices = remember { loadBondedDevices(context) }
    var selectedAddr by remember { mutableStateOf(BtPrefs.getSelectedAddress(context) ?: "") }
    var selectedName by remember { mutableStateOf(BtPrefs.getSelectedName(context) ?: "") }

    // Show the saved device even if it isn't currently in the paired list.
    val rows = remember(devices, selectedAddr) {
        buildList {
            if (selectedAddr.isNotEmpty() && devices.none { it.address.equals(selectedAddr, true) }) {
                add(BtDeviceInfo(selectedName.ifEmpty { selectedAddr }, selectedAddr))
            }
            addAll(devices)
        }
    }

    fun close() = onClose(selectedAddr, selectedName)

    AlertDialog(
        onDismissRequest = { close() },
        confirmButton = { TextButton(onClick = { close() }) { Text("Done") } },
        title = { Text("Auto-start on Bluetooth") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "When the selected device connects, SnoreMasker starts automatically. " +
                        "The app must already be running (it may be in the background).",
                    style = typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    DeviceRow("None", "", selectedAddr.isEmpty()) {
                        selectedAddr = ""
                        selectedName = ""
                        BtPrefs.setSelected(context, null, null)
                    }
                    rows.forEach { d ->
                        DeviceRow(d.name, d.address, selectedAddr.equals(d.address, ignoreCase = true)) {
                            selectedAddr = d.address
                            selectedName = d.name
                            BtPrefs.setSelected(context, d.address, d.name)
                        }
                    }
                    if (devices.isEmpty()) {
                        Text(
                            "No paired devices found. Pair your earbuds in Android " +
                                "Settings, then reopen this list.",
                            style = typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun DeviceRow(
    name: String,
    address: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(name, style = typography.bodyLarge)
            if (address.isNotEmpty()) {
                Text(address, style = typography.bodySmall, color = colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Paired (bonded) devices. Requires BLUETOOTH_CONNECT on API 31+. */
fun loadBondedDevices(context: Context): List<BtDeviceInfo> = try {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    adapter?.bondedDevices
        ?.map { d -> BtDeviceInfo(runCatching { d.name }.getOrNull() ?: d.address, d.address) }
        ?.sortedBy { it.name.lowercase() }
        ?: emptyList()
} catch (_: SecurityException) {
    emptyList()
}
