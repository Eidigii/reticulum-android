package tech.torlando.rns.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import tech.torlando.rns.service.ServiceState
import tech.torlando.rns.viewmodel.TransportViewModel

private const val MONITOR_POLL_MS = 2000L
private const val PREVIEW_COUNT = 8

private data class MonitorRow(
    val hash: String,
    val name: String?,
    val primary: String,
    val secondary: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen(viewModel: TransportViewModel, onNavigateBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network monitor") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) { MonitorContent(viewModel) }
    }
}

@Composable
private fun MonitorContent(viewModel: TransportViewModel) {
    val serviceState by viewModel.serviceState.collectAsState()
    var monitor by remember { mutableStateOf<JSONObject?>(null) }
    var section by remember { mutableStateOf("announces") }
    var showAll by remember { mutableStateOf(false) }

    val running = serviceState is ServiceState.Running
    LaunchedEffect(running) {
        while (running) {
            viewModel.messenger.getMonitor()?.let { monitor = it }
            delay(MONITOR_POLL_MS)
        }
    }

    if (!running) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Service not running", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Start the transport service to see network activity", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val m = monitor
    val paths = m?.optJSONArray("paths") ?: JSONArray()
    val recent = m?.optJSONArray("recentAnnounces") ?: JSONArray()
    val queue = m?.optJSONArray("announceQueue") ?: JSONArray()

    val rows: List<MonitorRow> = when (section) {
        "paths" -> paths.objects().map { p ->
            val via = p.optStringOrNull("via")
            MonitorRow(
                hash = p.getString("hash"),
                name = p.optStringOrNull("name"),
                primary = "${hopsLabel(p.optInt("hops"))} · ${p.optString("interface", "?")}",
                secondary = (if (via != null && p.optInt("hops") > 1) "via ${shortHash(via)} · " else "") +
                    "updated ${formatAgo(p.optDouble("timestamp"))} · expires ${formatIn(p.optDouble("expires"))}",
            )
        }
        "queue" -> queue.objects().map { a ->
            MonitorRow(
                hash = a.getString("hash"),
                name = a.optStringOrNull("name"),
                primary = "${hopsLabel(a.optInt("hops"))} · from ${a.optString("interface", "?")}",
                secondary = "retries ${a.optInt("retries")} · rebroadcast ${formatIn(a.optDouble("retransmitAt"))}",
            )
        }
        else -> recent.objects().map { r ->
            val hops = if (r.isNull("hops")) "? hops" else hopsLabel(r.optInt("hops"))
            MonitorRow(
                hash = r.getString("hash"),
                name = r.optStringOrNull("name"),
                primary = "${r.optString("kind", "Other")} · $hops · ${r.optStringOrNull("interface") ?: "?"}",
                secondary = formatAgo(r.optDouble("timestamp")) + if (r.optBoolean("pathResponse")) " · path response" else "",
            )
        }
    }
    val visible = if (showAll) rows else rows.take(PREVIEW_COUNT)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryTile("Known paths", "${paths.length()}", Modifier.weight(1f))
                SummaryTile("Announces heard", "${m?.optInt("totalAnnounces") ?: 0}", Modifier.weight(1f))
                SummaryTile("Rebroadcast queue", "${queue.length()}", Modifier.weight(1f))
            }
            if (m != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = (if (m.optBoolean("transportEnabled")) "Transport mode on: announces are rebroadcast" else "Transport mode off: announces are not rebroadcast") +
                        (if (!m.isNull("monitoringSince")) " · since ${formatAgo(m.optDouble("monitoringSince"))}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = section == "announces", onClick = { section = "announces"; showAll = false }, label = { Text("Announces") })
                FilterChip(selected = section == "paths", onClick = { section = "paths"; showAll = false }, label = { Text("Paths") })
                FilterChip(selected = section == "queue", onClick = { section = "queue"; showAll = false }, label = { Text("Queue") })
            }
        }

        if (m == null) {
            item { EmptyHint("Loading…") }
        } else if (rows.isEmpty()) {
            item {
                EmptyHint(
                    when (section) {
                        "paths" -> "No paths known yet. Paths appear when announces are received."
                        "queue" -> "Nothing waiting to be rebroadcast."
                        else -> "No announces heard since the service started."
                    },
                )
            }
        } else {
            items(visible, key = { section + it.hash }) { MonitorCard(it) }
            if (rows.size > PREVIEW_COUNT) {
                item {
                    TextButton(onClick = { showAll = !showAll }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (showAll) "Show less" else "Show all ${rows.size}")
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun SummaryTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MonitorCard(row: MonitorRow) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.name != null) {
                    Text(row.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                }
                Text(shortHash(row.hash), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(row.primary, style = MaterialTheme.typography.bodySmall)
            Text(row.secondary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun hopsLabel(hops: Int) = if (hops == 1) "1 hop" else "$hops hops"

private fun formatIn(epochSeconds: Double): String {
    val delta = (epochSeconds - System.currentTimeMillis() / 1000.0).toLong()
    return when {
        delta <= 0 -> "now"
        delta < 60 -> "in ${delta}s"
        delta < 3600 -> "in ${delta / 60}m"
        delta < 86400 -> "in ${delta / 3600}h"
        else -> "in ${delta / 86400}d"
    }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }
