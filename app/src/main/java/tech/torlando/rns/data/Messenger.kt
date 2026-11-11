package tech.torlando.rns.data

import org.json.JSONArray
import org.json.JSONObject

data class MessengerStatus(
    val running: Boolean = false,
    val destinationHash: String? = null,
    val displayName: String = "",
    val lastAnnounce: Double = 0.0,
    val nodesVersion: Int = 0,
    val messagesVersion: Int = 0,
    val unread: Int = 0,
    val identityChanged: Boolean = false,
    val propagation: PropagationStatus? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("running", running)
        put("destinationHash", destinationHash ?: JSONObject.NULL)
        put("displayName", displayName)
        put("lastAnnounce", lastAnnounce)
        put("nodesVersion", nodesVersion)
        put("messagesVersion", messagesVersion)
        put("unread", unread)
        put("identityChanged", identityChanged)
        put("propagation", propagation?.raw ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject?): MessengerStatus? {
            if (o == null) return null
            return MessengerStatus(
                running = o.optBoolean("running", false),
                destinationHash = o.optStringOrNull("destinationHash"),
                displayName = o.optString("displayName", ""),
                lastAnnounce = o.optDouble("lastAnnounce", 0.0),
                nodesVersion = o.optInt("nodesVersion", 0),
                messagesVersion = o.optInt("messagesVersion", 0),
                unread = o.optInt("unread", 0),
                identityChanged = o.optBoolean("identityChanged", false),
                propagation = o.optJSONObject("propagation")?.let { PropagationStatus.fromJson(it) },
            )
        }
    }
}

/** Propagation node selection, sync progress and local hosting state. */
data class PropagationStatus(
    val auto: Boolean,
    val manualNode: String?,
    val fallback: Boolean,
    val deliveryMethod: String,
    val syncInterval: Int,
    val hostEnabled: Boolean,
    val storageMb: Int,
    val hosting: Boolean,
    val hostingRestartNeeded: Boolean,
    val ownNodeHash: String?,
    val storedMessages: Int,
    val error: String?,
    val activeNode: String?,
    val activeNodeName: String?,
    val activeNodeReachable: Boolean,
    val activeNodeHops: Int?,
    val syncState: String,
    val syncBusy: Boolean,
    val syncProgress: Double,
    val lastSync: Double,
    val lastSyncResult: Int?,
    val raw: JSONObject,
) {
    val syncStateLabel: String
        get() = when (syncState) {
            "idle" -> "Idle"
            "path_requested" -> "Finding path…"
            "link_establishing" -> "Connecting…"
            "link_established" -> "Connected"
            "request_sent" -> "Requesting messages…"
            "receiving" -> "Receiving…"
            "response_received" -> "Processing…"
            "complete" -> "Complete"
            "no_path" -> "No path to node"
            "link_failed" -> "Could not connect"
            "transfer_failed" -> "Transfer failed"
            "no_identity" -> "Node did not accept identity"
            "no_access" -> "Access denied by node"
            else -> "Failed"
        }

    companion object {
        fun fromJson(o: JSONObject) = PropagationStatus(
            auto = o.optBoolean("auto", true),
            manualNode = o.optStringOrNull("manualNode"),
            fallback = o.optBoolean("fallback", true),
            deliveryMethod = o.optString("deliveryMethod", "direct"),
            syncInterval = o.optInt("syncInterval", 0),
            hostEnabled = o.optBoolean("hostEnabled", false),
            storageMb = o.optInt("storageMb", 100),
            hosting = o.optBoolean("hosting", false),
            hostingRestartNeeded = o.optBoolean("hostingRestartNeeded", false),
            ownNodeHash = o.optStringOrNull("ownNodeHash"),
            storedMessages = o.optInt("storedMessages", 0),
            error = o.optStringOrNull("error"),
            activeNode = o.optStringOrNull("activeNode"),
            activeNodeName = o.optStringOrNull("activeNodeName"),
            activeNodeReachable = o.optBoolean("activeNodeReachable", false),
            activeNodeHops = if (o.isNull("activeNodeHops") || !o.has("activeNodeHops")) null else o.optInt("activeNodeHops"),
            syncState = o.optString("syncState", "idle"),
            syncBusy = o.optBoolean("syncBusy", false),
            syncProgress = o.optDouble("syncProgress", 0.0),
            lastSync = o.optDouble("lastSync", 0.0),
            lastSyncResult = if (o.isNull("lastSyncResult") || !o.has("lastSyncResult")) null else o.optInt("lastSyncResult"),
            raw = o,
        )
    }
}

data class LocalIdentity(
    val destinationHash: String,
    val identityHash: String,
    val publicKey: String,
    val contactUri: String,
    val displayName: String,
    val announceInterval: Int,
    val lastAnnounce: Double,
    val running: Boolean,
    val identityChanged: Boolean,
) {
    companion object {
        fun fromJson(o: JSONObject) = LocalIdentity(
            destinationHash = o.optString("destinationHash"),
            identityHash = o.optString("identityHash"),
            publicKey = o.optString("publicKey"),
            contactUri = o.optString("contactUri"),
            displayName = o.optString("displayName", ""),
            announceInterval = o.optInt("announceInterval", 0),
            lastAnnounce = o.optDouble("lastAnnounce", 0.0),
            running = o.optBoolean("running", false),
            identityChanged = o.optBoolean("identityChanged", false),
        )
    }
}

data class NodeEntry(
    val hash: String,
    val type: String,
    val name: String?,
    val hops: Int?,
    val interfaceName: String?,
    val firstHeard: Double,
    val lastHeard: Double,
    val heardCount: Int,
    val favorite: Boolean,
    val reachable: Boolean,
    val pnEnabled: Boolean = false,
    val stampCost: Int? = null,
    val transferLimitKb: Double? = null,
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: "Unknown ${typeLabel.lowercase()}"

    val typeLabel: String
        get() = when (type) {
            "peer" -> "Peer"
            "propagation" -> "Propagation node"
            "nomadnet" -> "NomadNet node"
            else -> type
        }

    companion object {
        fun listFromJson(arr: JSONArray?): List<NodeEntry> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                NodeEntry(
                    hash = o.getString("hash"),
                    type = o.optString("type", "peer"),
                    name = o.optStringOrNull("name"),
                    hops = if (o.isNull("hops") || !o.has("hops")) null else o.optInt("hops"),
                    interfaceName = o.optStringOrNull("interface"),
                    firstHeard = o.optDouble("first_heard", 0.0),
                    lastHeard = o.optDouble("last_heard", 0.0),
                    heardCount = o.optInt("heard_count", 0),
                    favorite = o.optBoolean("favorite", false),
                    reachable = o.optBoolean("reachable", false),
                    pnEnabled = o.optBoolean("pn_enabled", false),
                    stampCost = if (o.isNull("stamp_cost") || !o.has("stamp_cost")) null else o.optInt("stamp_cost"),
                    transferLimitKb = if (o.isNull("transfer_limit_kb") || !o.has("transfer_limit_kb")) null else o.optDouble("transfer_limit_kb"),
                )
            }
        }
    }
}

data class Conversation(
    val peer: String,
    val name: String?,
    val lastContent: String,
    val lastTimestamp: Double,
    val lastIncoming: Boolean,
    val lastState: String,
    val unread: Int,
) {
    companion object {
        fun listFromJson(arr: JSONArray?): List<Conversation> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Conversation(
                    peer = o.getString("peer"),
                    name = o.optStringOrNull("name"),
                    lastContent = o.optString("lastContent", ""),
                    lastTimestamp = o.optDouble("lastTimestamp", 0.0),
                    lastIncoming = o.optBoolean("lastIncoming", false),
                    lastState = o.optString("lastState", ""),
                    unread = o.optInt("unread", 0),
                )
            }
        }
    }
}

data class ChatMessage(
    val id: String,
    val incoming: Boolean,
    val content: String,
    val timestamp: Double,
    val state: String,
    val signatureOk: Boolean,
    val method: String = "direct",
) {
    val failed: Boolean get() = state == "failed" || state == "rejected" || state == "cancelled"
    val propagated: Boolean get() = method == "propagated"

    companion object {
        fun listFromJson(arr: JSONArray?): List<ChatMessage> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ChatMessage(
                    id = o.getString("id"),
                    incoming = o.optBoolean("incoming", false),
                    content = o.optString("content", ""),
                    timestamp = o.optDouble("timestamp", 0.0),
                    state = o.optString("state", ""),
                    signatureOk = o.optBoolean("signatureOk", true),
                    method = o.optString("method", "direct"),
                )
            }
        }
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key, "").takeIf { it.isNotEmpty() }
