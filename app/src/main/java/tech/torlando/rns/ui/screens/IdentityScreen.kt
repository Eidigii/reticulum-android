package tech.torlando.rns.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.torlando.rns.viewmodel.MessengerResult
import tech.torlando.rns.viewmodel.TransportViewModel

private val AnnounceIntervals = listOf(
    0 to "Off (manual only)",
    5 * 60 to "Every 5 minutes",
    15 * 60 to "Every 15 minutes",
    30 * 60 to "Every 30 minutes",
    60 * 60 to "Every hour",
    6 * 60 * 60 to "Every 6 hours",
)

private enum class PendingExport { Encrypted, Raw }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentityScreen(viewModel: TransportViewModel, onNavigateBack: () -> Unit) {
    val messenger = viewModel.messenger
    val identity by messenger.identity.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { messenger.refreshIdentity() }

    var nameField by remember(identity?.displayName) { mutableStateOf(identity?.displayName ?: "") }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showRawConfirm by remember { mutableStateOf(false) }
    var exportBytes by remember { mutableStateOf<ByteArray?>(null) }
    var importBytes by remember { mutableStateOf<ByteArray?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val bytes = exportBytes
        exportBytes = null
        if (uri == null || bytes == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }.isSuccess
            }
            Toast.makeText(context, if (ok) "Identity exported" else "Export failed", Toast.LENGTH_SHORT).show()
        }
    }

    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null) {
                Toast.makeText(context, "Could not read file", Toast.LENGTH_SHORT).show()
                return@launch
            }
            when (val r = messenger.importIdentity(bytes, null)) {
                is MessengerResult.Err -> if (r.needsPassphrase) {
                    importBytes = bytes
                    importError = null
                } else {
                    Toast.makeText(context, r.message, Toast.LENGTH_LONG).show()
                }
                is MessengerResult.Ok -> Unit
            }
        }
    }

    fun launchExport(passphrase: String?, kind: PendingExport) {
        scope.launch {
            val bytes = messenger.exportIdentity(passphrase) ?: return@launch
            exportBytes = bytes
            val base = (identity?.displayName?.ifBlank { null } ?: "identity").replace(Regex("[^A-Za-z0-9_-]"), "_")
            createDocument.launch(if (kind == PendingExport.Encrypted) "$base.rnsid" else "$base.identity")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Identity") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    identity?.let { id ->
                        IconButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, id.contactUri)
                            }
                            context.startActivity(Intent.createChooser(send, "Share contact link"))
                        }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share contact link")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        val id = identity
        if (id == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Loading identity…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (id.identityChanged) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Identity changed", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Restart Reticulum to start using the new identity. Until then, announces and messages are paused.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { viewModel.restartService() }) { Text("Restart now") }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Destination hash", style = MaterialTheme.typography.labelMedium)
                    Text(id.destinationHash, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                }
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(id.destinationHash))
                    Toast.makeText(context, "Hash copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy") }
            }

            Spacer(Modifier.height(16.dp))
            QrCode(id.contactUri, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(8.dp))
            Text(
                "Share this code or the contact link (top right). Others can add you from the Nodes tab by pasting the link or your hash.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = nameField,
                onValueChange = { nameField = it.take(64) },
                label = { Text("Display name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            val dirty = nameField.trim() != id.displayName
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { messenger.setDisplayName(nameField) }, enabled = dirty) {
                    Text(if (dirty) "Save" else "Saved")
                }
                TextButton(onClick = { nameField = id.displayName }, enabled = dirty) { Text("Revert") }
            }
            Text(
                "Your name is included in announces so peers can label you. Saving triggers an immediate re-announce.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("Announce", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { messenger.announce() },
                    enabled = id.running && !id.identityChanged,
                ) { Text("Send announce") }
                OutlinedButton(onClick = { showResetConfirm = true }) { Text("Reset identity…") }
            }
            Text(
                when {
                    !id.running -> "Start Reticulum to send announces."
                    else -> "Last announce: ${formatAgo(id.lastAnnounce)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            AnnounceIntervalPicker(id.announceInterval) { messenger.setAnnounceInterval(it) }
            Text(
                "Automatic announces are sent while Reticulum is running. Keep the interval long on slow links like LoRa.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("Backup", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showExportDialog = true }) { Text("Export identity…") }
                OutlinedButton(onClick = { openDocument.launch(arrayOf("*/*")) }) { Text("Import identity…") }
            }
            Text(
                "Exports are encrypted with a passphrase. Anyone with both the file and the passphrase can impersonate you. " +
                    "Import also accepts raw RNS identity files (rnsd, Sideband, NomadNet), which are unencrypted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { showRawConfirm = true }) { Text("Export raw identity (RNS, unencrypted)…") }

            Spacer(Modifier.height(8.dp))
            Text(
                "Identity hash: ${id.identityHash}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset identity?") },
            text = {
                Text("A new key pair and destination hash will be generated. Contacts will need your new address. Export a backup first if you may want the old identity back.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    messenger.resetIdentity()
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") } },
        )
    }

    if (showExportDialog) {
        PassphraseDialog(
            title = "Export identity",
            message = "Choose a passphrase to encrypt the exported file.",
            confirmRequired = true,
            error = null,
            onDismiss = { showExportDialog = false },
            onConfirm = { pass ->
                showExportDialog = false
                launchExport(pass, PendingExport.Encrypted)
            },
        )
    }

    if (showRawConfirm) {
        AlertDialog(
            onDismissRequest = { showRawConfirm = false },
            title = { Text("Export unencrypted?") },
            text = { Text("The raw file contains your private key in plain form. Anyone who gets it can impersonate you.") },
            confirmButton = {
                TextButton(onClick = {
                    showRawConfirm = false
                    launchExport(null, PendingExport.Raw)
                }) { Text("Export") }
            },
            dismissButton = { TextButton(onClick = { showRawConfirm = false }) { Text("Cancel") } },
        )
    }

    importBytes?.let { bytes ->
        PassphraseDialog(
            title = "Import identity",
            message = "This file is encrypted. Enter its passphrase. Importing replaces your current identity.",
            confirmRequired = false,
            error = importError,
            onDismiss = { importBytes = null },
            onConfirm = { pass ->
                scope.launch {
                    when (val r = messenger.importIdentity(bytes, pass)) {
                        is MessengerResult.Ok -> importBytes = null
                        is MessengerResult.Err -> importError = r.message
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnnounceIntervalPicker(current: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = AnnounceIntervals.firstOrNull { it.first == current }?.second ?: "Every ${current / 60} minutes"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Automatic announce") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AnnounceIntervals.forEach { (seconds, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onSelect(seconds)
                    },
                )
            }
        }
    }
}

@Composable
private fun PassphraseDialog(
    title: String,
    message: String,
    confirmRequired: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val mismatch = confirmRequired && confirm.isNotEmpty() && pass != confirm
    val valid = pass.length >= (if (confirmRequired) 8 else 1) && (!confirmRequired || pass == confirm)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("Passphrase") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = if (confirmRequired) ({ Text("At least 8 characters") }) else null,
                )
                if (confirmRequired) {
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = { Text("Confirm passphrase") },
                        singleLine = true,
                        isError = mismatch,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        supportingText = if (mismatch) ({ Text("Passphrases do not match") }) else null,
                    )
                }
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(pass) }, enabled = valid) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
