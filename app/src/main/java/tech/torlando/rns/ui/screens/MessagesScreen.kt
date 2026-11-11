package tech.torlando.rns.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tech.torlando.rns.data.ChatMessage
import tech.torlando.rns.data.Conversation
import tech.torlando.rns.viewmodel.TransportViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    viewModel: TransportViewModel,
    onOpenConversation: (String) -> Unit,
    onOpenPropagation: () -> Unit,
) {
    val messenger = viewModel.messenger
    val conversations by messenger.conversations.collectAsState()
    val propagation by messenger.propagation.collectAsState()
    val status by messenger.status.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }

    LaunchedEffect(Unit) { messenger.refreshConversations() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Messages")
                        val p = propagation
                        val subtitle = when {
                            p == null -> null
                            p.syncBusy -> "Syncing: ${p.syncStateLabel}"
                            p.activeNode != null -> "via ${p.activeNodeName ?: shortHash(p.activeNode)}"
                            else -> "No propagation node"
                        }
                        subtitle?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    val p = propagation
                    if (p?.syncBusy == true) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(
                            onClick = { messenger.syncMessages() },
                            enabled = status.running && p?.activeNode != null,
                        ) { Icon(Icons.Filled.Sync, contentDescription = "Sync with propagation node") }
                    }
                    IconButton(onClick = onOpenPropagation) {
                        Icon(Icons.Filled.Cloud, contentDescription = "Propagation nodes")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Edit, contentDescription = "New conversation")
            }
        },
    ) { innerPadding ->
        if (conversations.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Forum,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("No conversations yet", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Pick a peer in Nodes or add one by hash",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                items(conversations, key = { it.peer }) { c ->
                    ConversationRow(
                        conversation = c,
                        onClick = { onOpenConversation(c.peer) },
                        onLongClick = { pendingDelete = c },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showAdd) {
        AddContactDialog(
            viewModel = viewModel,
            onDismiss = { showAdd = false },
            onAdded = { hash ->
                showAdd = false
                onOpenConversation(hash)
            },
        )
    }

    pendingDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete conversation?") },
            text = { Text("All messages with ${c.name ?: shortHash(c.peer)} will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    messenger.deleteConversation(c.peer)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(conversation: Conversation, onClick: () -> Unit, onLongClick: () -> Unit) {
    val name = conversation.name ?: shortHash(conversation.peer)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PeerAvatar(name, conversation.peer, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatMessageTime(conversation.lastTimestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (conversation.lastIncoming) "" else "You: ") + conversation.lastContent,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (conversation.unread > 0) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            "${conversation.unread}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    viewModel: TransportViewModel,
    peerHash: String,
    onNavigateBack: () -> Unit,
) {
    val messenger = viewModel.messenger
    val messages by messenger.messages.collectAsState()
    val nodes by messenger.nodes.collectAsState()
    val conversations by messenger.conversations.collectAsState()
    val status by messenger.status.collectAsState()
    val propagation by messenger.propagation.collectAsState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var method by remember(propagation?.deliveryMethod) { mutableStateOf(propagation?.deliveryMethod ?: "direct") }

    val node = nodes.firstOrNull { it.hash == peerHash }
    val name = node?.name ?: conversations.firstOrNull { it.peer == peerHash }?.name ?: shortHash(peerHash)

    DisposableEffect(peerHash) {
        messenger.openConversation(peerHash)
        onDispose { messenger.closeConversation() }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PeerAvatar(name, peerHash, size = 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val subtitle = when {
                                node == null -> shortHash(peerHash)
                                node.reachable && node.hops != null -> "${shortHash(peerHash)} · ${node.hops} hop${if (node.hops == 1) "" else "s"}"
                                node.reachable -> shortHash(peerHash)
                                else -> "${shortHash(peerHash)} · no path"
                            }
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item { Spacer(Modifier.height(8.dp)) }
                if (messages.isEmpty()) {
                    item {
                        Text(
                            "No messages yet. Say hello!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(messages, key = { it.id }) { msg ->
                    MessageBubble(
                        message = msg,
                        onRetry = { retryMethod -> messenger.retryMessage(msg.id, retryMethod) },
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }

            if (!status.running) {
                Text(
                    "Reticulum is not running. Start it to send messages.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val viaNode = method == "propagated"
                IconButton(onClick = { method = if (viaNode) "direct" else "propagated" }) {
                    Icon(
                        if (viaNode) Icons.Filled.Cloud else Icons.Filled.Bolt,
                        contentDescription = if (viaNode) "Sending via propagation node" else "Sending directly",
                        tint = if (viaNode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text(if (method == "propagated") "Message (via propagation node)" else "Message") },
                    maxLines = 5,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    enabled = draft.isNotBlank() && !sending && status.running,
                    onClick = {
                        val text = draft
                        sending = true
                        scope.launch {
                            if (messenger.sendMessage(peerHash, text, method)) draft = ""
                            sending = false
                        }
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(message: ChatMessage, onRetry: (String?) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val incoming = message.incoming
    var menuOpen by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (incoming) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text("Copy") }, onClick = {
                menuOpen = false
                clipboard.setText(AnnotatedString(message.content))
                Toast.makeText(context, "Message copied", Toast.LENGTH_SHORT).show()
            })
            if (!incoming && message.failed) {
                DropdownMenuItem(text = { Text("Retry directly") }, onClick = {
                    menuOpen = false
                    onRetry("direct")
                })
                DropdownMenuItem(text = { Text("Retry via propagation node") }, onClick = {
                    menuOpen = false
                    onRetry("propagated")
                })
            }
        }
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (incoming) 4.dp else 16.dp,
                bottomEnd = if (incoming) 16.dp else 4.dp,
            ),
            color = if (incoming) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .combinedClickable(
                    onClick = { if (!incoming && message.failed) menuOpen = true },
                    onLongClick = { menuOpen = true },
                ),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(message.content, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (incoming && !message.signatureOk) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = "Signature not verified",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    if (message.propagated) {
                        Icon(
                            Icons.Filled.Cloud,
                            contentDescription = "Via propagation node",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        formatMessageTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!incoming) {
                        Spacer(Modifier.width(4.dp))
                        StateIcon(message.state, message.propagated)
                    }
                }
                if (!incoming && message.failed) {
                    Text(
                        "Not delivered · tap to retry",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun StateIcon(state: String, propagated: Boolean) {
    val (icon, label) = when (state) {
        "delivered" -> Icons.Filled.DoneAll to "Delivered"
        "sent" -> if (propagated) Icons.Filled.CloudDone to "Stored on propagation node" else Icons.Filled.Done to "Sent"
        "failed", "rejected", "cancelled" -> Icons.Filled.ErrorOutline to state.replaceFirstChar { it.uppercase() }
        else -> Icons.Filled.Schedule to "Sending"
    }
    val tint = if (state in setOf("failed", "rejected", "cancelled")) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(14.dp))
}
