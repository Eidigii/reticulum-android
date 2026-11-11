package tech.torlando.rns.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import tech.torlando.rns.data.InterfaceStatus
import tech.torlando.rns.stats.data.InterfaceHistoryPoint
import tech.torlando.rns.stats.data.formatBytes
import tech.torlando.rns.stats.ui.TrafficSpeedChart
import tech.torlando.rns.viewmodel.TransportViewModel

private val StatusOnline = Color(0xFF4CAF50)
private val StatusConnecting = Color(0xFFFFC107)
private val StatusOffline = Color(0xFF9E9E9E)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterfaceStatsScreen(
    interfaceName: String,
    viewModel: TransportViewModel,
    onNavigateBack: () -> Unit,
) {
    val allStats by viewModel.interfaceStats.collectAsState()
    val allHistory by viewModel.interfaceHistory.collectAsState()

    val stats = allStats.firstOrNull { it.name == interfaceName }
    val history = allHistory[interfaceName] ?: emptyList()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(interfaceName) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Status card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val statusText = when (stats?.status) {
                        InterfaceStatus.ONLINE -> "Online"
                        InterfaceStatus.CONNECTING -> "Connecting"
                        InterfaceStatus.RECONNECTING -> "Reconnecting"
                        InterfaceStatus.DETACHED -> "Detached"
                        else -> "Offline"
                    }
                    val statusColor = when (stats?.status) {
                        InterfaceStatus.ONLINE -> StatusOnline
                        InterfaceStatus.CONNECTING, InterfaceStatus.RECONNECTING -> StatusConnecting
                        else -> StatusOffline
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Circle,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }

                    if (stats != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stats.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // RX/TX totals
            if (stats != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatCard(
                        label = "Received",
                        value = formatBytes(stats.rxb),
                        icon = Icons.Filled.ArrowDownward,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        label = "Transmitted",
                        value = formatBytes(stats.txb),
                        icon = Icons.Filled.ArrowUpward,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(16.dp))

                stats.radioDiagnostics?.let { raw ->
                    runCatching { JSONObject(raw) }.getOrNull()?.let { diag ->
                        RadioDiagnosticsCard(diag)
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }

            // Traffic speed chart
            TrafficSpeedChart(
                history = history,
                title = "Traffic Speed",
                rxColor = MaterialTheme.colorScheme.primary,
                txColor = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun RadioDiagnosticsCard(diag: JSONObject) {
    val rxFrames = diag.optInt("rx_frames")
    val ok = diag.optInt("rx_ifac_ok")
    val missing = diag.optInt("rx_ifac_missing")
    val unexpected = diag.optInt("rx_ifac_unexpected")
    val invalid = diag.optInt("rx_ifac_invalid")
    val txFrames = diag.optInt("tx_frames")
    val txErrors = diag.optInt("tx_errors")
    val ifacOn = diag.optBoolean("ifac_enabled")
    val lastRx = if (diag.isNull("last_rx_at")) null else diag.optDouble("last_rx_at")
    val rssi = if (diag.isNull("last_rssi")) null else diag.optInt("last_rssi")
    val snr = if (diag.isNull("last_snr")) null else diag.optDouble("last_snr")

    val hint = when {
        txErrors > 0 && txFrames == 0 ->
            "Nothing could be written to the RNode. Check the Bluetooth/USB connection."
        rxFrames == 0 ->
            "No LoRa frames received yet. If the other node has announced, its frequency, " +
                "bandwidth, spreading factor or coding rate differ, or it is out of range."
        missing > 0 && ok == 0 ->
            "Frames arrive without IFAC. Remove the network name/passphrase here, or add the same ones on the other node."
        unexpected > 0 && ok == 0 ->
            "The other node uses IFAC. Set the same network name, passphrase and IFAC size here."
        invalid > 0 && ok == 0 ->
            "IFAC does not match. Network name, passphrase and IFAC size must be identical on both nodes " +
                "(RNode default is 64 bits; TCP/UDP default is 128 bits)."
        else -> null
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("LoRa diagnostics", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            DiagLine(
                "Radio",
                "%.3f MHz · %d kHz · SF%d · CR4/%d · %d dBm".format(
                    diag.optLong("frequency") / 1_000_000.0,
                    diag.optLong("bandwidth") / 1000,
                    diag.optInt("spreading_factor"),
                    diag.optInt("coding_rate"),
                    diag.optInt("tx_power"),
                ),
            )
            DiagLine("IFAC", if (ifacOn) "on, ${diag.optInt("ifac_size") * 8} bits" else "off")
            DiagLine("Frames sent", if (txErrors > 0) "$txFrames ($txErrors failed)" else "$txFrames")
            DiagLine("Frames received", "$rxFrames")
            if (rxFrames > 0) {
                DiagLine("Accepted", "$ok")
                if (missing > 0) DiagLine("Dropped: no IFAC", "$missing")
                if (unexpected > 0) DiagLine("Dropped: unexpected IFAC", "$unexpected")
                if (invalid > 0) DiagLine("Dropped: wrong IFAC", "$invalid")
            }
            if (lastRx != null) {
                val ago = ((System.currentTimeMillis() / 1000.0) - lastRx).toLong().coerceAtLeast(0)
                DiagLine("Last frame", "${ago}s ago")
            }
            if (rssi != null) DiagLine("Last RSSI / SNR", "$rssi dBm / ${snr ?: "?"} dB")
            if (hint != null) {
                Spacer(Modifier.height(4.dp))
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun DiagLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}
