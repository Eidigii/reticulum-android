package tech.torlando.rns.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tech.torlando.rns.data.NodeEntry
import tech.torlando.rns.viewmodel.TransportViewModel

private enum class NodeFilter(val label: String, val type: String?) {
    All("All", null),
    Peers("Peers", "peer"),
    Propagation("Propagation", "propagation"),
    NomadNet("NomadNet", "nomadnet"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodesScreen(
    viewModel: TransportViewModel,
    onOpenConversation: (String) -> Unit,
    onOpenIdentity: () -> Unit,
) {
    val messenger = viewModel.messenger
    val nodes by messenger.nodes.collectAsState()
    val status by messenger.status.collectAsState()
    var filter by remember { mutableStateOf(NodeFilter.All) }
    var query by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }

    val visible = nodes.filter { n ->
        (filter.type == null || n.type == filter.type) &&
            (query.isBlank() || n.displayName.contains(query, ignoreCase = true) || n.hash.contains(query.lowercase()))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nodes (${nodes.size})") },
                actions = {
                    IconButton(onClick = onOpenIdentity) {
                        Icon(Icons.Filled.Badge, contentDescription = "My identity")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add contact")
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search name or hash") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NodeFilter.entries.forEach { f ->
                        FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                    }
                }
            }
            if (!status.running) {
                item {
                    Text(
                        "Reticulum is not running. Start it from Home to hear announces.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (visible.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Filled.CellTower,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(48.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                if (nodes.isEmpty()) "No nodes heard yet" else "No nodes match",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "Nodes appear here as their announces arrive",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                items(visible, key = { it.hash }) { node ->
                    NodeCard(
                        node = node,
                        onClick = { if (node.type == "peer") onOpenConversation(node.hash) },
                        onToggleFavorite = { messenger.setFavorite(node.hash, !node.favorite) },
                        onRequestPath = { messenger.requestPath(node.hash) },
                        onRemove = { messenger.removeNode(node.hash) },
                        onUseAsPropagation = { messenger.usePropagationNode(node.hash) },
                    )
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    if (showAdd) {
        AddContactDialog(
            onDismiss = { showAdd = false },
            onAdded = { hash ->
                showAdd = false
                onOpenConversation(hash)
            },
            viewModel = viewModel,
        )
    }
}

@Composable
private fun NodeCard(
    node: NodeEntry,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRequestPath: () -> Unit,
    onRemove: () -> Unit,
    onUseAsPropagation: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PeerAvatar(node.displayName, node.hash)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (node.favorite) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = "Favorite",
                            tint = Color(0xFFFFC107),
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        node.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    shortHash(node.hash),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val hops = when {
                    !node.reachable -> "no path"
                    node.hops == null -> "path known"
                    node.hops == 1 -> "1 hop"
                    else -> "${node.hops} hops"
                }
                val via = node.interfaceName?.let { " · $it" } ?: ""
                Text(
                    "${node.typeLabel} · $hops$via",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Heard ${formatAgo(node.lastHeard)}" + if (node.heardCount > 0) " · ${node.heardCount}×" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (node.type == "peer") {
                        DropdownMenuItem(text = { Text("Message") }, onClick = {
                            menuOpen = false
                            onClick()
                        })
                    }
                    if (node.type == "propagation" && node.pnEnabled) {
                        DropdownMenuItem(text = { Text("Use as propagation node") }, onClick = {
                            menuOpen = false
                            onUseAsPropagation()
                        })
                    }
                    DropdownMenuItem(
                        text = { Text(if (node.favorite) "Remove from favorites" else "Add to favorites") },
                        onClick = {
                            menuOpen = false
                            onToggleFavorite()
                        },
                    )
                    DropdownMenuItem(text = { Text("Request path") }, onClick = {
                        menuOpen = false
                        onRequestPath()
                    })
                    DropdownMenuItem(text = { Text("Copy hash") }, onClick = {
                        menuOpen = false
                        clipboard.setText(AnnotatedString(node.hash))
                        Toast.makeText(context, "Hash copied", Toast.LENGTH_SHORT).show()
                    })
                    DropdownMenuItem(text = { Text("Remove") }, onClick = {
                        menuOpen = false
                        onRemove()
                    })
                }
            }
        }
    }
}

@Composable
fun AddContactDialog(
    viewModel: TransportViewModel,
    onDismiss: () -> Unit,
    onAdded: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add contact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste an LXMF destination hash or an lxma:// contact link.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.trim() },
                    label = { Text("Hash or link") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(64) },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val hash = viewModel.messenger.addContact(text, name)
                        busy = false
                        if (hash != null) onAdded(hash)
                    }
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
