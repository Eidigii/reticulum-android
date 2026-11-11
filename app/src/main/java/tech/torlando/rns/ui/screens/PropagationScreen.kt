package tech.torlando.rns.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import tech.torlando.rns.data.NodeEntry
import tech.torlando.rns.data.PropagationStatus
import tech.torlando.rns.viewmodel.TransportViewModel

private val SyncIntervals = listOf(
    0 to "Manual only",
    5 * 60 to "Every 5 minutes",
    15 * 60 to "Every 15 minutes",
    30 * 60 to "Every 30 minutes",
    60 * 60 to "Every hour",
    6 * 60 * 60 to "Every 6 hours",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PropagationScreen(viewModel: TransportViewModel, onNavigateBack: () -> Unit) {
    val messenger = viewModel.messenger
    val propagation by messenger.propagation.collectAsState()
    val status by messenger.status.collectAsState()
    val nodes by messenger.nodes.collectAsState()
    var showManualEntry by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        messenger.refreshPropagation()
        messenger.refreshNodes()
    }

    val pnNodes = nodes.filter { it.type == "propagation" && it.hash != propagation?.ownNodeHash }
        .sortedWith(compareBy<NodeEntry>({ !it.pnEnabled }, { !it.reachable }, { it.hops ?: 999 }, { -it.lastHeard }))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Propagation nodes") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        val p = propagation
        if (p == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "Propagation nodes store messages for peers that are offline and hand them over when they sync. " +
                        "They act as store-and-forward gateways between parts of the network that are rarely online at the same time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { ActiveNodeCard(p, running = status.running, onSync = messenger::syncMessages, onCancel = messenger::cancelSync) }

            item { SectionTitle("Delivery") }
            item {
                SettingSwitch(
                    title = "Fall back to propagation node",
                    description = "When direct delivery fails, store the message on the propagation node instead",
                    checked = p.fallback,
                ) { messenger.setPropagationSettings(JSONObject().put("fallback", it)) }
            }
            item {
                Column {
                    Text("Default delivery method", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("direct" to "Direct", "propagated" to "Via propagation node").forEachIndexed { i, (value, label) ->
                            SegmentedButton(
                                selected = p.deliveryMethod == value,
                                onClick = { messenger.setPropagationSettings(JSONObject().put("deliveryMethod", value)) },
                                shape = SegmentedButtonDefaults.itemShape(i, 2),
                            ) { Text(label) }
                        }
                    }
                }
            }
            item {
                IntervalPicker(
                    label = "Automatic sync",
                    options = SyncIntervals,
                    current = p.syncInterval,
                ) { messenger.setPropagationSettings(JSONObject().put("syncInterval", it)) }
            }

            item { SectionTitle("Node selection") }
            item {
                SettingSwitch(
                    title = "Select automatically",
                    description = "Use the closest reachable propagation node heard on the network",
                    checked = p.auto,
                ) { messenger.setPropagationSettings(JSONObject().put("auto", it)) }
            }
            if (pnNodes.isEmpty()) {
                item {
                    Text(
                        "No propagation nodes heard yet. They appear as their announces arrive.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(pnNodes, key = { it.hash }) { node ->
                PropagationNodeRow(
                    node = node,
                    selected = node.hash == p.activeNode,
                    onSelect = { messenger.usePropagationNode(node.hash) },
                )
            }
            item {
                TextButton(onClick = { showManualEntry = true }) { Text("Enter node hash manually…") }
            }

            item { SectionTitle("Host a propagation node") }
            item {
                SettingSwitch(
                    title = "Run propagation node on this device",
                    description = "Store and forward messages for other peers. Uses storage, battery and bandwidth.",
                    checked = p.hostEnabled,
                ) { messenger.setPropagationSettings(JSONObject().put("hostEnabled", it)) }
            }
            item { HostingCard(p, onStorageChange = { messenger.setPropagationSettings(JSONObject().put("storageMb", it)) }, onRestart = viewModel::restartService) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showManualEntry) {
        ManualNodeDialog(
            onDismiss = { showManualEntry = false },
            onConfirm = {
                showManualEntry = false
                messenger.usePropagationNode(it)
            },
        )
    }
}

@Composable
private fun ActiveNodeCard(p: PropagationStatus, running: Boolean, onSync: () -> Unit, onCancel: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Active propagation node", style = MaterialTheme.typography.labelMedium)
            if (p.activeNode == null) {
                Text("None", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (p.auto) "Waiting to hear a propagation node announce" else "No node selected",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(p.activeNodeName ?: "Unnamed node", style = MaterialTheme.typography.titleMedium)
                Text(p.activeNode, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                val reach = when {
                    !running -> "Reticulum stopped"
                    !p.activeNodeReachable -> "No path yet"
                    p.activeNodeHops != null -> "${p.activeNodeHops} hop${if (p.activeNodeHops == 1) "" else "s"} away"
                    else -> "Reachable"
                }
                Text("${if (p.auto) "Auto-selected" else "Manually selected"} · $reach", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(12.dp))
            if (p.syncBusy) {
                LinearProgressIndicator(
                    progress = { p.syncProgress.toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
            }
            val last = if (p.lastSync > 0) {
                val result = p.lastSyncResult?.let { " · $it new message${if (it == 1) "" else "s"}" } ?: ""
                "Last sync ${formatAgo(p.lastSync)}: ${p.syncStateLabel}$result"
            } else {
                "Not synced yet"
            }
            Text(last, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSync, enabled = running && p.activeNode != null && !p.syncBusy) { Text("Sync now") }
                if (p.syncBusy) OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun PropagationNodeRow(node: NodeEntry, selected: Boolean, onSelect: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = node.pnEnabled, onClick = onSelect),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onSelect, enabled = node.pnEnabled)
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(node.name ?: "Unnamed node", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(shortHash(node.hash), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                val details = buildList {
                    if (!node.pnEnabled) add("propagation disabled")
                    add(
                        when {
                            !node.reachable -> "no path"
                            node.hops != null -> "${node.hops} hop${if (node.hops == 1) "" else "s"}"
                            else -> "reachable"
                        },
                    )
                    node.stampCost?.let { add("stamp cost $it") }
                    node.transferLimitKb?.let { add("limit ${it.toInt()} KB") }
                    add("heard ${formatAgo(node.lastHeard)}")
                }.joinToString(" · ")
                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun HostingCard(p: PropagationStatus, onStorageChange: (Int) -> Unit, onRestart: () -> Unit) {
    var storage by remember(p.storageMb) { mutableStateOf(p.storageMb.toString()) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when {
                    p.hosting -> "Running · ${p.storedMessages} stored message${if (p.storedMessages == 1) "" else "s"}"
                    p.hostEnabled -> "Enabled, not running"
                    else -> "Not hosting"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            p.ownNodeHash?.let {
                Text("Node hash: $it", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            p.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(
                value = storage,
                onValueChange = { v ->
                    storage = v.filter { it.isDigit() }.take(6)
                    storage.toIntOrNull()?.takeIf { it > 0 }?.let(onStorageChange)
                },
                label = { Text("Message storage limit (MB)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            if (p.hostingRestartNeeded) {
                Text(
                    "Restart Reticulum to ${if (p.hostEnabled) "start" else "stop"} the propagation node.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = onRestart) { Text("Restart now") }
            } else if (!p.hosting) {
                Text(
                    "Changes apply the next time Reticulum starts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntervalPicker(label: String, options: List<Pair<Int, String>>, current: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val text = options.firstOrNull { it.first == current }?.second ?: "Every ${current / 60} minutes"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = text,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (seconds, optionText) ->
                DropdownMenuItem(text = { Text(optionText) }, onClick = {
                    expanded = false
                    onSelect(seconds)
                })
            }
        }
    }
}

@Composable
private fun ManualNodeDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var hash by remember { mutableStateOf("") }
    val valid = hash.length == 32 && hash.all { it in "0123456789abcdef" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Propagation node hash") },
        text = {
            OutlinedTextField(
                value = hash,
                onValueChange = { hash = it.trim().lowercase().removePrefix("<").removeSuffix(">") },
                label = { Text("32 hex characters") },
                singleLine = true,
                isError = hash.isNotEmpty() && !valid,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(hash) }, enabled = valid) { Text("Use") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
