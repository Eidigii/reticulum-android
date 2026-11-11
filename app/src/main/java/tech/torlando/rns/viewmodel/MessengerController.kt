package tech.torlando.rns.viewmodel

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tech.torlando.rns.data.ChatMessage
import tech.torlando.rns.data.Conversation
import tech.torlando.rns.data.LocalIdentity
import tech.torlando.rns.data.MessengerStatus
import tech.torlando.rns.data.NodeEntry
import tech.torlando.rns.data.PropagationStatus
import tech.torlando.rns.service.IRnsService

/** Result of a messenger call: either a JSON value or a user-facing error. */
sealed class MessengerResult {
    data class Ok(val value: Any?) : MessengerResult()
    data class Err(val message: String, val needsPassphrase: Boolean = false) : MessengerResult()
}

class MessengerController(
    private val scope: CoroutineScope,
    private val service: () -> IRnsService?,
) {
    companion object {
        private const val TAG = "MessengerController"
    }

    private val _status = MutableStateFlow(MessengerStatus())
    val status: StateFlow<MessengerStatus> = _status.asStateFlow()

    private val _identity = MutableStateFlow<LocalIdentity?>(null)
    val identity: StateFlow<LocalIdentity?> = _identity.asStateFlow()

    private val _nodes = MutableStateFlow<List<NodeEntry>>(emptyList())
    val nodes: StateFlow<List<NodeEntry>> = _nodes.asStateFlow()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _openPeer = MutableStateFlow<String?>(null)
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _propagation = MutableStateFlow<PropagationStatus?>(null)
    val propagation: StateFlow<PropagationStatus?> = _propagation.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** One-shot user-facing messages (errors, confirmations). */
    val events: SharedFlow<String> = _events.asSharedFlow()

    private var lastNodesVersion = -1
    private var lastMessagesVersion = -1

    fun onServiceConnected() {
        lastNodesVersion = -1
        lastMessagesVersion = -1
        refreshIdentity()
        refreshNodes()
        refreshConversations()
        refreshPropagation()
    }

    fun applyStatus(status: MessengerStatus?) {
        if (status == null) return
        val previous = _status.value
        _status.value = status
        status.propagation?.let { _propagation.value = it }
        if (status.nodesVersion != lastNodesVersion) {
            lastNodesVersion = status.nodesVersion
            refreshNodes()
        }
        if (status.messagesVersion != lastMessagesVersion) {
            lastMessagesVersion = status.messagesVersion
            refreshConversations()
            _openPeer.value?.let { refreshMessages(it) }
        }
        if (previous.running != status.running ||
            previous.lastAnnounce != status.lastAnnounce ||
            previous.destinationHash != status.destinationHash ||
            previous.identityChanged != status.identityChanged
        ) {
            refreshIdentity()
        }
    }

    fun onServiceStopped() {
        _status.value = _status.value.copy(running = false)
        refreshIdentity()
        refreshPropagation()
    }

    // --- Propagation nodes ---

    fun refreshPropagation() {
        scope.launch {
            ((call("get_propagation") as? MessengerResult.Ok)?.value as? JSONObject)?.let {
                _propagation.value = PropagationStatus.fromJson(it)
            }
        }
    }

    /** Keys: auto, node, fallback, deliveryMethod, syncInterval, hostEnabled, storageMb. */
    fun setPropagationSettings(args: JSONObject) {
        scope.launch {
            when (val r = call("set_propagation_settings", args)) {
                is MessengerResult.Ok -> (r.value as? JSONObject)?.let { _propagation.value = PropagationStatus.fromJson(it) }
                is MessengerResult.Err -> _events.emit(r.message)
            }
        }
    }

    fun usePropagationNode(hash: String) {
        setPropagationSettings(JSONObject().put("auto", false).put("node", hash))
        scope.launch { _events.emit("Propagation node selected") }
    }

    fun syncMessages() {
        scope.launch {
            when (val r = call("sync_messages")) {
                is MessengerResult.Ok -> _events.emit("Syncing with propagation node…")
                is MessengerResult.Err -> _events.emit(r.message)
            }
            refreshPropagation()
        }
    }

    fun cancelSync() {
        scope.launch {
            call("cancel_sync")
            refreshPropagation()
        }
    }

    // --- Identity ---

    fun refreshIdentity() {
        scope.launch {
            (call("get_identity") as? MessengerResult.Ok)?.let { r ->
                (r.value as? JSONObject)?.let { _identity.value = LocalIdentity.fromJson(it) }
            }
        }
    }

    fun setDisplayName(name: String) = launchIdentityUpdate("set_display_name", JSONObject().put("name", name), "Name saved")

    fun setAnnounceInterval(seconds: Int) =
        launchIdentityUpdate("set_announce_interval", JSONObject().put("seconds", seconds), null)

    fun resetIdentity() = launchIdentityUpdate("reset_identity", JSONObject(), "New identity generated")

    fun announce() {
        scope.launch {
            when (val r = call("announce")) {
                is MessengerResult.Ok -> _events.emit("Announce sent")
                is MessengerResult.Err -> _events.emit(r.message)
            }
            refreshIdentity()
        }
    }

    private fun launchIdentityUpdate(method: String, args: JSONObject, success: String?) {
        scope.launch {
            when (val r = call(method, args)) {
                is MessengerResult.Ok -> {
                    (r.value as? JSONObject)?.let { _identity.value = LocalIdentity.fromJson(it) }
                    success?.let { _events.emit(it) }
                }
                is MessengerResult.Err -> _events.emit(r.message)
            }
        }
    }

    /** Returns the exported identity bytes, or null (an error event is emitted). */
    suspend fun exportIdentity(passphrase: String?): ByteArray? {
        val args = JSONObject().apply { if (!passphrase.isNullOrEmpty()) put("passphrase", passphrase) }
        return when (val r = call("export_identity", args)) {
            is MessengerResult.Ok -> {
                val data = (r.value as? JSONObject)?.optString("data") ?: return null
                android.util.Base64.decode(data, android.util.Base64.NO_WRAP)
            }
            is MessengerResult.Err -> {
                _events.emit(r.message)
                null
            }
        }
    }

    suspend fun importIdentity(bytes: ByteArray, passphrase: String?): MessengerResult {
        val args = JSONObject()
            .put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
            .apply { if (!passphrase.isNullOrEmpty()) put("passphrase", passphrase) }
        val r = call("import_identity", args)
        if (r is MessengerResult.Ok) {
            (r.value as? JSONObject)?.let { _identity.value = LocalIdentity.fromJson(it) }
            _events.emit("Identity imported")
        }
        return r
    }

    /** Path table, rebroadcast queue and recently heard announces (any app). */
    suspend fun getMonitor(): JSONObject? =
        (call("get_monitor") as? MessengerResult.Ok)?.value as? JSONObject

    // --- Nodes ---

    fun refreshNodes() {
        scope.launch {
            (call("list_nodes") as? MessengerResult.Ok)?.let { r ->
                _nodes.value = NodeEntry.listFromJson(r.value as? org.json.JSONArray)
            }
        }
    }

    suspend fun addContact(text: String, name: String?): String? {
        val args = JSONObject().put("text", text).apply { if (!name.isNullOrBlank()) put("name", name) }
        return when (val r = call("add_contact", args)) {
            is MessengerResult.Ok -> {
                refreshNodes()
                (r.value as? JSONObject)?.optString("hash")
            }
            is MessengerResult.Err -> {
                _events.emit(r.message)
                null
            }
        }
    }

    fun setFavorite(hash: String, favorite: Boolean) = fireAndRefreshNodes("set_favorite", JSONObject().put("hash", hash).put("favorite", favorite))

    fun removeNode(hash: String) = fireAndRefreshNodes("remove_node", JSONObject().put("hash", hash))

    fun requestPath(hash: String) {
        scope.launch {
            when (val r = call("request_path", JSONObject().put("hash", hash))) {
                is MessengerResult.Ok -> _events.emit("Path requested")
                is MessengerResult.Err -> _events.emit(r.message)
            }
        }
    }

    private fun fireAndRefreshNodes(method: String, args: JSONObject) {
        scope.launch {
            (call(method, args) as? MessengerResult.Err)?.let { _events.emit(it.message) }
            refreshNodes()
        }
    }

    fun nodeName(hash: String): String? = _nodes.value.firstOrNull { it.hash == hash }?.name

    // --- Messaging ---

    fun refreshConversations() {
        scope.launch {
            (call("list_conversations") as? MessengerResult.Ok)?.let { r ->
                _conversations.value = Conversation.listFromJson(r.value as? org.json.JSONArray)
            }
        }
    }

    fun openConversation(hash: String) {
        _openPeer.value = hash
        _messages.value = emptyList()
        refreshMessages(hash)
        scope.launch { call("mark_read", JSONObject().put("hash", hash)) }
    }

    fun closeConversation() {
        _openPeer.value = null
        _messages.value = emptyList()
    }

    private fun refreshMessages(hash: String) {
        scope.launch {
            (call("get_messages", JSONObject().put("hash", hash)) as? MessengerResult.Ok)?.let { r ->
                if (_openPeer.value != hash) return@let
                _messages.value = ChatMessage.listFromJson((r.value as? JSONObject)?.optJSONArray("messages"))
                if (_messages.value.any { it.incoming }) call("mark_read", JSONObject().put("hash", hash))
            }
        }
    }

    fun retryMessage(id: String, method: String?) {
        scope.launch {
            val args = JSONObject().put("id", id).apply { if (method != null) put("method", method) }
            when (val r = call("retry_message", args)) {
                is MessengerResult.Ok -> _openPeer.value?.let { refreshMessages(it) }
                is MessengerResult.Err -> _events.emit(r.message)
            }
        }
    }

    suspend fun sendMessage(hash: String, content: String, method: String? = null): Boolean {
        val args = JSONObject().put("hash", hash).put("content", content).apply { if (method != null) put("method", method) }
        return when (val r = call("send_message", args)) {
            is MessengerResult.Ok -> {
                refreshMessages(hash)
                true
            }
            is MessengerResult.Err -> {
                _events.emit(r.message)
                false
            }
        }
    }

    fun deleteConversation(hash: String) {
        scope.launch {
            call("delete_conversation", JSONObject().put("hash", hash))
            refreshConversations()
        }
    }

    // --- Transport ---

    private suspend fun call(method: String, args: JSONObject = JSONObject()): MessengerResult =
        withContext(Dispatchers.IO) {
            val svc = service() ?: return@withContext MessengerResult.Err("Service not connected")
            try {
                val raw = svc.messengerCall(method, args.toString())
                    ?: return@withContext MessengerResult.Err("No response")
                val obj = JSONObject(raw)
                if (obj.has("error")) return@withContext MessengerResult.Err(obj.optString("error"))
                val result = obj.opt("result")
                if (result is JSONObject && result.has("ok") && !result.optBoolean("ok")) {
                    MessengerResult.Err(
                        result.optString("error", "Failed"),
                        needsPassphrase = result.optBoolean("needsPassphrase", false),
                    )
                } else {
                    MessengerResult.Ok(result)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Messenger call $method failed", e)
                MessengerResult.Err(e.message ?: "Messenger call failed")
            }
        }
}
