"""
LXMF messenger for the Android app: local identity, announces, a list of
nodes heard via announces, direct and propagated messaging, syncing from
propagation nodes, and optionally hosting a propagation node.

All public functions return JSON-serialisable dicts/lists so the Kotlin side
can marshal them across the service AIDL boundary as JSON strings.
"""

import base64
import json
import os
import sqlite3
import threading
import time
import uuid

import RNS
import RNS.vendor.umsgpack as msgpack

APP_NAME = "lxmf"
DEFAULT_DISPLAY_NAME = "Anonymous Peer"
DEFAULT_ANNOUNCE_INTERVAL = 30 * 60
MIN_ANNOUNCE_INTERVAL = 5 * 60
MAX_NODES = 1000
CONTACT_URI_SCHEME = "lxma://"
EXPORT_FORMAT = "rns-android-identity"

NODE_TYPE_PEER = "peer"
NODE_TYPE_PROPAGATION = "propagation"
NODE_TYPE_NOMADNET = "nomadnet"

METHOD_DIRECT = "direct"
METHOD_PROPAGATED = "propagated"
DEFAULT_SYNC_INTERVAL = 60 * 60
MIN_SYNC_INTERVAL = 5 * 60
DEFAULT_PROPAGATION_STORAGE_MB = 100
JOB_TICK = 2

SYNC_STATE_NAMES = {
    0x00: "idle",
    0x01: "path_requested",
    0x02: "link_establishing",
    0x03: "link_established",
    0x04: "request_sent",
    0x05: "receiving",
    0x06: "response_received",
    0x07: "complete",
    0xF0: "no_path",
    0xF1: "link_failed",
    0xF2: "transfer_failed",
    0xF3: "no_identity",
    0xF4: "no_access",
    0xFE: "failed",
}
SYNC_BUSY_STATES = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06}

STATE_NAMES = {
    0x00: "generating",
    0x01: "outbound",
    0x02: "sending",
    0x04: "sent",
    0x08: "delivered",
    0xFD: "rejected",
    0xFE: "cancelled",
    0xFF: "failed",
}

_lock = threading.RLock()
_storage = None
_identity = None
_settings = None
_nodes = {}
_db = None
_router = None
_delivery_destination = None
_running = False
_last_announce = 0.0
_nodes_version = 0
_messages_version = 0
_new_incoming = []
_identity_changed = False
_announce_thread = None
_last_sync = 0.0
_hosting = False
_propagation_error = None


# --------------------------------------------------------------------------
# Storage
# --------------------------------------------------------------------------

def _path(name):
    return os.path.join(_storage, name)


def _ensure_storage(storage_path):
    global _storage
    if _storage is None:
        _storage = os.path.join(storage_path, "messenger")
        os.makedirs(_storage, exist_ok=True)


def _load_settings():
    global _settings
    if _settings is not None:
        return _settings
    _settings = {
        "display_name": DEFAULT_DISPLAY_NAME,
        "announce_interval": DEFAULT_ANNOUNCE_INTERVAL,
        "propagation_auto": True,
        "propagation_node": None,
        "propagation_fallback": True,
        "delivery_method": METHOD_DIRECT,
        "sync_interval": DEFAULT_SYNC_INTERVAL,
        "host_propagation": False,
        "propagation_storage_mb": DEFAULT_PROPAGATION_STORAGE_MB,
    }
    try:
        with open(_path("settings.json"), "r", encoding="utf-8") as f:
            _settings.update(json.load(f))
    except FileNotFoundError:
        pass
    except Exception as e:
        RNS.log(f"Messenger: could not read settings: {e}", RNS.LOG_ERROR)
    return _settings


def _save_settings():
    tmp = _path("settings.json.tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(_settings, f)
    os.replace(tmp, _path("settings.json"))


def _load_identity():
    global _identity
    if _identity is not None:
        return _identity
    identity_path = _path("identity")
    if os.path.isfile(identity_path):
        _identity = RNS.Identity.from_file(identity_path)
    if _identity is None:
        _identity = RNS.Identity()
        _identity.to_file(identity_path)
        RNS.log("Messenger: generated new identity", RNS.LOG_NOTICE)
    return _identity


def _replace_identity(new_identity):
    global _identity, _identity_changed
    new_identity.to_file(_path("identity"))
    _identity = new_identity
    if _router is not None:
        _identity_changed = True


def _load_nodes():
    global _nodes
    try:
        with open(_path("nodes.json"), "r", encoding="utf-8") as f:
            _nodes = {n["hash"]: n for n in json.load(f)}
    except FileNotFoundError:
        _nodes = {}
    except Exception as e:
        RNS.log(f"Messenger: could not read nodes: {e}", RNS.LOG_ERROR)
        _nodes = {}


def _save_nodes():
    tmp = _path("nodes.json.tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(list(_nodes.values()), f)
    os.replace(tmp, _path("nodes.json"))


def _open_db():
    global _db
    if _db is not None:
        return _db
    _db = sqlite3.connect(_path("messages.db"), check_same_thread=False)
    _db.execute(
        """CREATE TABLE IF NOT EXISTS messages (
            id TEXT PRIMARY KEY,
            lxm_hash TEXT,
            peer TEXT NOT NULL,
            incoming INTEGER NOT NULL,
            title TEXT,
            content TEXT,
            timestamp REAL NOT NULL,
            state TEXT NOT NULL,
            read INTEGER NOT NULL DEFAULT 0,
            signature_ok INTEGER NOT NULL DEFAULT 1
        )"""
    )
    columns = {row[1] for row in _db.execute("PRAGMA table_info(messages)")}
    if "method" not in columns:
        _db.execute(f"ALTER TABLE messages ADD COLUMN method TEXT NOT NULL DEFAULT '{METHOD_DIRECT}'")
    _db.execute("CREATE INDEX IF NOT EXISTS idx_messages_peer ON messages(peer, timestamp)")
    _db.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_lxm ON messages(lxm_hash) WHERE lxm_hash IS NOT NULL")
    _db.commit()
    return _db


def init(storage_path):
    """Load identity, settings, nodes and message store. Safe without RNS running."""
    with _lock:
        _ensure_storage(storage_path)
        _load_settings()
        _load_identity()
        if not _nodes:
            _load_nodes()
        _open_db()
    return get_status()


# --------------------------------------------------------------------------
# Announce handling (nodes list)
# --------------------------------------------------------------------------

def _decode_peer_name(app_data):
    try:
        import LXMF
        return LXMF.display_name_from_app_data(app_data)
    except Exception:
        return None


def _decode_nomadnet_name(app_data):
    try:
        return app_data.decode("utf-8").strip() if app_data else None
    except Exception:
        return None


def _decode_propagation_name(app_data):
    try:
        import LXMF
        name = LXMF.pn_name_from_app_data(app_data)
        return name.strip() if name else None
    except Exception:
        return None


def _decode_propagation_info(app_data):
    """Capabilities advertised in a propagation node announce, or None if unusable."""
    try:
        import LXMF
        if not LXMF.pn_announce_data_is_valid(app_data):
            return None
        data = msgpack.unpackb(app_data)
        return {
            "pn_enabled": bool(data[2]),
            "transfer_limit_kb": data[3],
            "sync_limit_kb": data[4],
            "stamp_cost": data[5][0],
        }
    except Exception:
        return None


class _AnnounceHandler:
    def __init__(self, aspect_filter, node_type, name_decoder):
        self.aspect_filter = aspect_filter
        self.receive_path_responses = True
        self.node_type = node_type
        self.name_decoder = name_decoder

    def received_announce(self, destination_hash, announced_identity, app_data, announce_packet_hash, is_path_response):
        try:
            _record_announce(destination_hash, announced_identity, app_data, self.node_type, self.name_decoder, is_path_response)
        except Exception as e:
            RNS.log(f"Messenger: error recording announce: {e}", RNS.LOG_ERROR)


def _record_announce(destination_hash, announced_identity, app_data, node_type, name_decoder, is_path_response):
    global _nodes_version
    if _delivery_destination is not None and destination_hash == _delivery_destination.hash:
        return
    if _router is not None and destination_hash == _router.propagation_destination.hash:
        return
    hex_hash = destination_hash.hex()
    now = time.time()
    hops = RNS.Transport.hops_to(destination_hash)
    iface = RNS.Transport.next_hop_interface(destination_hash)
    name = name_decoder(app_data)

    with _lock:
        node = _nodes.get(hex_hash)
        if node is None:
            node = {
                "hash": hex_hash,
                "type": node_type,
                "name": None,
                "identity": None,
                "first_heard": now,
                "heard_count": 0,
                "favorite": False,
            }
            _nodes[hex_hash] = node
        if name:
            node["name"] = name
        if announced_identity is not None:
            node["identity"] = announced_identity.hash.hex()
        node["last_heard"] = now
        if not is_path_response:
            node["heard_count"] = node.get("heard_count", 0) + 1
        node["hops"] = hops if hops != RNS.Transport.PATHFINDER_M else None
        node["interface"] = str(iface) if iface is not None else None
        if node_type == NODE_TYPE_PROPAGATION:
            info = _decode_propagation_info(app_data)
            node.update(info or {"pn_enabled": False})
            node["pn_valid"] = info is not None

        if len(_nodes) > MAX_NODES:
            evictable = sorted(
                (n for n in _nodes.values() if not n.get("favorite")),
                key=lambda n: n.get("last_heard", 0),
            )
            for n in evictable[: len(_nodes) - MAX_NODES]:
                del _nodes[n["hash"]]

        _nodes_version += 1
        _save_nodes()


# --------------------------------------------------------------------------
# Lifecycle (requires RNS running)
# --------------------------------------------------------------------------

def start(storage_path):
    """Start the LXMF router and announce handlers. Call after RNS.Reticulum()."""
    global _router, _delivery_destination, _running, _announce_thread, _identity_changed
    import LXMF

    init(storage_path)
    with _lock:
        if _running:
            return get_status()

        _router = LXMF.LXMRouter(
            identity=_identity, storagepath=_storage, name=_settings.get("display_name") or None,
        )
        _delivery_destination = _router.register_delivery_identity(
            _identity, display_name=_settings.get("display_name") or None,
        )
        # LXMF only installs default app data when a name exists at registration;
        # path responses must carry the name even if it is set later.
        _delivery_destination.set_default_app_data(lambda: _router.get_announce_app_data(_delivery_destination.hash))
        _router.register_delivery_callback(_on_lxmf_delivery)
        _identity_changed = False

        RNS.Transport.register_announce_handler(_AnnounceHandler(APP_NAME + ".delivery", NODE_TYPE_PEER, _decode_peer_name))
        RNS.Transport.register_announce_handler(_AnnounceHandler(APP_NAME + ".propagation", NODE_TYPE_PROPAGATION, _decode_propagation_name))
        RNS.Transport.register_announce_handler(_AnnounceHandler("nomadnetwork.node", NODE_TYPE_NOMADNET, _decode_nomadnet_name))
        import network_monitor
        network_monitor.start()

        if _settings.get("host_propagation"):
            _start_hosting()

        _running = True
        _apply_propagation_node()

    _announce_thread = threading.Thread(target=_background_job, daemon=True)
    _announce_thread.start()
    return get_status()


def _start_hosting():
    global _hosting, _propagation_error
    try:
        storage_mb = int(_settings.get("propagation_storage_mb") or DEFAULT_PROPAGATION_STORAGE_MB)
        _router.set_message_storage_limit(megabytes=max(storage_mb, 1))
        _router.enable_propagation()
        _hosting = True
        _propagation_error = None
        RNS.log("Messenger: hosting LXMF propagation node", RNS.LOG_NOTICE)
    except Exception as e:
        _hosting = False
        _propagation_error = f"Could not start propagation node: {e}"
        RNS.log(f"Messenger: {_propagation_error}", RNS.LOG_ERROR)


def _background_job():
    time.sleep(3)
    announce()
    while _running:
        time.sleep(JOB_TICK)
        try:
            interval = int(_settings.get("announce_interval", 0) or 0)
            if interval > 0 and time.time() - _last_announce >= interval:
                announce()
            _apply_propagation_node()
            sync_interval = int(_settings.get("sync_interval", 0) or 0)
            if sync_interval > 0 and time.time() - _last_sync >= sync_interval:
                if _router.get_outbound_propagation_node() is not None and not _sync_busy():
                    sync_messages()
        except Exception as e:
            RNS.log(f"Messenger: background job error: {e}", RNS.LOG_ERROR)


def announce():
    """Send an LXMF delivery announce for the local identity."""
    global _last_announce
    with _lock:
        if not _running or _router is None or _delivery_destination is None:
            return {"ok": False, "error": "Reticulum is not running"}
        if _identity_changed:
            return {"ok": False, "error": "Identity changed. Restart Reticulum to apply it"}
        _delivery_destination.display_name = _settings.get("display_name") or None
        _router.announce(_delivery_destination.hash)
        if _hosting:
            _router.name = _settings.get("display_name") or None
            _router.announce_propagation_node()
        _last_announce = time.time()
    return {"ok": True, "lastAnnounce": _last_announce}


# --------------------------------------------------------------------------
# Propagation nodes
# --------------------------------------------------------------------------

def _usable_propagation_nodes():
    own = _router.propagation_destination.hash.hex() if _router is not None else None
    candidates = []
    for n in _nodes.values():
        if n.get("type") != NODE_TYPE_PROPAGATION or n["hash"] == own:
            continue
        if not n.get("pn_enabled"):
            continue
        has_path = RNS.Transport.has_path(bytes.fromhex(n["hash"])) if _running else False
        hops = RNS.Transport.hops_to(bytes.fromhex(n["hash"])) if has_path else None
        candidates.append((n, has_path, hops))
    return candidates


def _select_propagation_node():
    """Return the hex hash of the propagation node to use, or None."""
    if not _settings.get("propagation_auto", True):
        return _settings.get("propagation_node") or None
    candidates = _usable_propagation_nodes()
    if not candidates:
        return None
    candidates.sort(key=lambda c: (not c[1], c[2] if c[2] is not None else 999, -c[0].get("last_heard", 0)))
    return candidates[0][0]["hash"]


def _apply_propagation_node():
    if _router is None:
        return
    selected = _select_propagation_node()
    current = _router.get_outbound_propagation_node()
    if selected is None:
        if current is not None:
            _router.outbound_propagation_node = None
        return
    selected_bytes = bytes.fromhex(selected)
    if current != selected_bytes:
        _router.set_outbound_propagation_node(selected_bytes)
        RNS.log(f"Messenger: outbound propagation node is now {selected}", RNS.LOG_NOTICE)
        if not RNS.Transport.has_path(selected_bytes):
            RNS.Transport.request_path(selected_bytes)


def _sync_busy():
    return _router is not None and _router.propagation_transfer_state in SYNC_BUSY_STATES


def sync_messages():
    """Download messages waiting for us on the outbound propagation node."""
    global _last_sync
    if not _running or _router is None:
        return {"ok": False, "error": "Reticulum is not running"}
    if _identity_changed:
        return {"ok": False, "error": "Identity changed. Restart Reticulum to apply it"}
    _apply_propagation_node()
    if _router.get_outbound_propagation_node() is None:
        return {"ok": False, "error": "No propagation node selected or heard yet"}
    if _sync_busy():
        return {"ok": False, "error": "A sync is already in progress"}
    _last_sync = time.time()
    _router.request_messages_from_propagation_node(_identity)
    return {"ok": True}


def cancel_sync():
    if _router is not None:
        _router.cancel_propagation_node_requests()
    return {"ok": True}


def get_propagation():
    with _lock:
        selected = None
        if _router is not None and _router.get_outbound_propagation_node() is not None:
            selected = _router.get_outbound_propagation_node().hex()
        elif _router is None:
            selected = _select_propagation_node()
        node = _nodes.get(selected) if selected else None
        state = _router.propagation_transfer_state if _router is not None else 0
        own = _router.propagation_destination.hash.hex() if _router is not None else None
        stored = len(getattr(_router, "propagation_entries", {}) or {}) if _hosting else 0
        return {
            "auto": bool(_settings.get("propagation_auto", True)),
            "manualNode": _settings.get("propagation_node"),
            "fallback": bool(_settings.get("propagation_fallback", True)),
            "deliveryMethod": _settings.get("delivery_method", METHOD_DIRECT),
            "syncInterval": int(_settings.get("sync_interval", 0) or 0),
            "hostEnabled": bool(_settings.get("host_propagation", False)),
            "storageMb": int(_settings.get("propagation_storage_mb") or DEFAULT_PROPAGATION_STORAGE_MB),
            "hosting": _hosting,
            "hostingRestartNeeded": _running and bool(_settings.get("host_propagation", False)) != _hosting,
            "ownNodeHash": own if _hosting else None,
            "storedMessages": stored,
            "error": _propagation_error,
            "activeNode": selected,
            "activeNodeName": node.get("name") if node else None,
            "activeNodeReachable": bool(selected and _running and RNS.Transport.has_path(bytes.fromhex(selected))),
            "activeNodeHops": (RNS.Transport.hops_to(bytes.fromhex(selected))
                               if selected and _running and RNS.Transport.has_path(bytes.fromhex(selected)) else None),
            "syncState": SYNC_STATE_NAMES.get(state, "unknown"),
            "syncBusy": state in SYNC_BUSY_STATES,
            "syncProgress": float(_router.propagation_transfer_progress) if _router is not None else 0.0,
            "lastSync": _last_sync,
            "lastSyncResult": _router.propagation_transfer_last_result if _router is not None else None,
        }


def set_propagation_settings(args):
    with _lock:
        if "auto" in args:
            _settings["propagation_auto"] = bool(args["auto"])
        if "node" in args:
            node = (args["node"] or "").strip().lower() or None
            if node is not None:
                try:
                    if len(bytes.fromhex(node)) != RNS.Reticulum.TRUNCATED_HASHLENGTH // 8:
                        raise ValueError()
                except ValueError:
                    return {"ok": False, "error": "Propagation node hash must be 32 hex characters"}
            _settings["propagation_node"] = node
            if node is not None and "auto" not in args:
                _settings["propagation_auto"] = False
        if "fallback" in args:
            _settings["propagation_fallback"] = bool(args["fallback"])
        if "deliveryMethod" in args and args["deliveryMethod"] in (METHOD_DIRECT, METHOD_PROPAGATED):
            _settings["delivery_method"] = args["deliveryMethod"]
        if "syncInterval" in args:
            seconds = int(args["syncInterval"])
            _settings["sync_interval"] = max(seconds, MIN_SYNC_INTERVAL) if seconds > 0 else 0
        if "hostEnabled" in args:
            _settings["host_propagation"] = bool(args["hostEnabled"])
        if "storageMb" in args:
            _settings["propagation_storage_mb"] = max(int(args["storageMb"]), 1)
        _save_settings()
        if _running:
            _apply_propagation_node()
    result = get_propagation()
    result["ok"] = True
    return result


# --------------------------------------------------------------------------
# Identity
# --------------------------------------------------------------------------

def _delivery_hash_for(identity):
    return RNS.Destination.hash(identity, APP_NAME, "delivery")


def get_identity():
    with _lock:
        identity = _load_identity()
        dest_hash = _delivery_hash_for(identity)
        return {
            "destinationHash": dest_hash.hex(),
            "identityHash": identity.hash.hex(),
            "publicKey": identity.get_public_key().hex(),
            "contactUri": f"{CONTACT_URI_SCHEME}{dest_hash.hex()}:{identity.get_public_key().hex()}",
            "displayName": _settings.get("display_name", ""),
            "announceInterval": int(_settings.get("announce_interval", 0) or 0),
            "lastAnnounce": _last_announce,
            "running": _running,
            "identityChanged": _identity_changed,
        }


def set_display_name(name):
    name = (name or "").strip()[:64]
    with _lock:
        _settings["display_name"] = name
        _save_settings()
    if _running:
        announce()
    return get_identity()


def set_announce_interval(seconds):
    seconds = int(seconds)
    if seconds > 0:
        seconds = max(seconds, MIN_ANNOUNCE_INTERVAL)
    with _lock:
        _settings["announce_interval"] = max(seconds, 0)
        _save_settings()
    return get_identity()


def reset_identity():
    with _lock:
        _replace_identity(RNS.Identity())
    return get_identity()


def export_identity(passphrase=None):
    """Return the identity as base64. Encrypted with the passphrase if given, else raw RNS format."""
    with _lock:
        prv = _load_identity().get_private_key()
    if not passphrase:
        return {"ok": True, "data": base64.b64encode(prv).decode("ascii"), "encrypted": False}

    from cryptography.fernet import Fernet
    salt = os.urandom(16)
    key = _derive_export_key(passphrase, salt)
    token = Fernet(key).encrypt(prv).decode("ascii")
    blob = json.dumps({
        "format": EXPORT_FORMAT,
        "version": 1,
        "kdf": "scrypt",
        "n": 2 ** 15, "r": 8, "p": 1,
        "salt": base64.b64encode(salt).decode("ascii"),
        "token": token,
    }).encode("utf-8")
    return {"ok": True, "data": base64.b64encode(blob).decode("ascii"), "encrypted": True}


def _derive_export_key(passphrase, salt, n=2 ** 15, r=8, p=1):
    from cryptography.hazmat.primitives.kdf.scrypt import Scrypt
    kdf = Scrypt(salt=salt, length=32, n=n, r=r, p=p)
    return base64.urlsafe_b64encode(kdf.derive(passphrase.encode("utf-8")))


def import_identity(data_b64, passphrase=None):
    """Import an identity from raw RNS bytes or an encrypted export."""
    try:
        data = base64.b64decode(data_b64)
    except Exception:
        return {"ok": False, "error": "Could not read file"}

    prv = None
    if len(data) == 64:
        prv = data
    else:
        try:
            blob = json.loads(data.decode("utf-8"))
        except Exception:
            return {"ok": False, "error": "Not a valid identity file"}
        if blob.get("format") != EXPORT_FORMAT:
            return {"ok": False, "error": "Unsupported identity file format"}
        if not passphrase:
            return {"ok": False, "needsPassphrase": True, "error": "Passphrase required"}
        try:
            from cryptography.fernet import Fernet, InvalidToken
            key = _derive_export_key(
                passphrase, base64.b64decode(blob["salt"]),
                int(blob.get("n", 2 ** 15)), int(blob.get("r", 8)), int(blob.get("p", 1)),
            )
            prv = Fernet(key).decrypt(blob["token"].encode("ascii"))
        except InvalidToken:
            return {"ok": False, "needsPassphrase": True, "error": "Wrong passphrase"}
        except Exception as e:
            return {"ok": False, "error": f"Could not decrypt identity: {e}"}

    identity = RNS.Identity.from_bytes(prv)
    if identity is None:
        return {"ok": False, "error": "Identity key is invalid"}
    with _lock:
        _replace_identity(identity)
    result = get_identity()
    result["ok"] = True
    return result


# --------------------------------------------------------------------------
# Nodes
# --------------------------------------------------------------------------

def list_nodes():
    with _lock:
        nodes = [dict(n) for n in _nodes.values()]
    for n in nodes:
        try:
            h = bytes.fromhex(n["hash"])
            if RNS.Transport.has_path(h):
                n["hops"] = RNS.Transport.hops_to(h)
                n["reachable"] = True
            else:
                n["reachable"] = False
        except Exception:
            n["reachable"] = False
    nodes.sort(key=lambda n: (not n.get("favorite", False), -n.get("last_heard", 0)))
    return nodes


def add_contact(text, name=None):
    """Add a peer from a bare destination hash or an lxma://<hash>:<pubkey> link."""
    global _nodes_version
    text = (text or "").strip()
    if text.lower().startswith(CONTACT_URI_SCHEME):
        text = text[len(CONTACT_URI_SCHEME):]
    parts = text.split(":")
    hex_hash = parts[0].strip().lower()
    try:
        dest_hash = bytes.fromhex(hex_hash)
    except ValueError:
        return {"ok": False, "error": "Invalid destination hash"}
    if len(dest_hash) != RNS.Reticulum.TRUNCATED_HASHLENGTH // 8:
        return {"ok": False, "error": "Destination hash must be 32 hex characters"}

    identity_hex = None
    public_key_hex = None
    if len(parts) > 1 and parts[1]:
        try:
            pub = bytes.fromhex(parts[1].strip())
            identity = RNS.Identity(create_keys=False)
            identity.load_public_key(pub)
        except Exception as e:
            return {"ok": False, "error": f"Invalid public key: {e}"}
        if _delivery_hash_for(identity) != dest_hash:
            return {"ok": False, "error": "Public key does not match destination hash"}
        RNS.Identity.remember(RNS.Identity.get_random_hash(), dest_hash, pub, None)
        identity_hex = identity.hash.hex()
        public_key_hex = pub.hex()

    with _lock:
        node = _nodes.get(hex_hash)
        if node is None:
            node = {
                "hash": hex_hash, "type": NODE_TYPE_PEER, "name": None, "identity": identity_hex,
                "first_heard": time.time(), "last_heard": 0, "heard_count": 0,
                "hops": None, "interface": None, "favorite": True,
            }
            _nodes[hex_hash] = node
        else:
            node["favorite"] = True
        if name:
            node["name"] = name.strip()
        if identity_hex:
            node["identity"] = identity_hex
            node["public_key"] = public_key_hex
        _nodes_version += 1
        _save_nodes()

    if _running and not RNS.Transport.has_path(dest_hash):
        RNS.Transport.request_path(dest_hash)
    return {"ok": True, "hash": hex_hash}


def set_favorite(hex_hash, favorite):
    global _nodes_version
    with _lock:
        node = _nodes.get(hex_hash)
        if node is None:
            return {"ok": False, "error": "Unknown node"}
        node["favorite"] = bool(favorite)
        _nodes_version += 1
        _save_nodes()
    return {"ok": True}


def remove_node(hex_hash):
    global _nodes_version
    with _lock:
        if _nodes.pop(hex_hash, None) is not None:
            _nodes_version += 1
            _save_nodes()
    return {"ok": True}


def request_path(hex_hash):
    if not _running:
        return {"ok": False, "error": "Reticulum is not running"}
    RNS.Transport.request_path(bytes.fromhex(hex_hash))
    return {"ok": True}


# --------------------------------------------------------------------------
# Messaging
# --------------------------------------------------------------------------

def _bump_messages():
    global _messages_version
    _messages_version += 1


def _on_lxmf_delivery(lxm):
    import LXMF
    try:
        peer = lxm.source_hash.hex()
        content = lxm.content_as_string() or ""
        title = lxm.title_as_string() or ""
        timestamp = lxm.timestamp or time.time()
        lxm_hash = lxm.hash.hex() if lxm.hash else None
        with _lock:
            db = _open_db()
            method = METHOD_PROPAGATED if lxm.method == LXMF.LXMessage.PROPAGATED else METHOD_DIRECT
            cur = db.execute(
                "INSERT OR IGNORE INTO messages (id, lxm_hash, peer, incoming, title, content, timestamp, state, read, signature_ok, method)"
                " VALUES (?, ?, ?, 1, ?, ?, ?, 'delivered', 0, ?, ?)",
                (str(uuid.uuid4()), lxm_hash, peer, title, content, timestamp, 1 if lxm.signature_validated else 0, method),
            )
            db.commit()
            if cur.rowcount > 0:
                _bump_messages()
                _new_incoming.append({"peer": peer, "content": content})
    except Exception as e:
        RNS.log(f"Messenger: error storing incoming message: {e}", RNS.LOG_ERROR)


def _peer_name(hex_hash):
    node = _nodes.get(hex_hash)
    if node and node.get("name"):
        return node["name"]
    try:
        import LXMF
        app_data = RNS.Identity.recall_app_data(bytes.fromhex(hex_hash), _no_use=True)
        name = LXMF.display_name_from_app_data(app_data) if app_data else None
        if name:
            return name
    except Exception:
        pass
    return None


def _set_state(msg_id, lxm):
    state = STATE_NAMES.get(lxm.state, "unknown")
    with _lock:
        db = _open_db()
        db.execute(
            "UPDATE messages SET state = ?, lxm_hash = COALESCE(lxm_hash, ?) WHERE id = ?",
            (state, lxm.hash.hex() if lxm.hash else None, msg_id),
        )
        db.commit()
        _bump_messages()


def _identity_from_contact(hex_hash, dest_hash):
    """Rebuild a peer identity from a public key saved with a manually added contact."""
    node = _nodes.get(hex_hash)
    pub_hex = node.get("public_key") if node else None
    if not pub_hex:
        return None
    try:
        pub = bytes.fromhex(pub_hex)
        RNS.Identity.remember(RNS.Identity.get_random_hash(), dest_hash, pub, None)
        return RNS.Identity.recall(dest_hash)
    except Exception as e:
        RNS.log(f"Messenger: could not restore contact key: {e}", RNS.LOG_ERROR)
        return None


def _check_can_send():
    if not _running or _router is None:
        return "Reticulum is not running"
    if _identity_changed:
        return "Identity changed. Restart Reticulum to apply it"
    return None


def _resolve_destination(hex_hash):
    """Return (destination, error)."""
    try:
        dest_hash = bytes.fromhex(hex_hash)
    except ValueError:
        return None, "Invalid destination hash"
    identity = RNS.Identity.recall(dest_hash)
    if identity is None:
        identity = _identity_from_contact(hex_hash, dest_hash)
    if identity is None:
        RNS.Transport.request_path(dest_hash)
        return None, "This peer's keys are not known yet. A path request was sent; try again once it announces."
    return RNS.Destination(identity, RNS.Destination.OUT, RNS.Destination.SINGLE, APP_NAME, "delivery"), None


def _propagation_available():
    _apply_propagation_node()
    return _router is not None and _router.get_outbound_propagation_node() is not None


def _set_method(msg_id, method):
    with _lock:
        db = _open_db()
        db.execute("UPDATE messages SET method = ?, state = 'outbound' WHERE id = ?", (method, msg_id))
        db.commit()
        _bump_messages()


def _dispatch(msg_id, destination, content, method):
    import LXMF
    desired = LXMF.LXMessage.PROPAGATED if method == METHOD_PROPAGATED else LXMF.LXMessage.DIRECT
    lxm = LXMF.LXMessage(destination, _delivery_destination, content, title="", desired_method=desired)

    def on_failed(m):
        _set_state(msg_id, m)
        if (method == METHOD_DIRECT and m.state == LXMF.LXMessage.FAILED
                and _settings.get("propagation_fallback", True) and _propagation_available()):
            RNS.log(f"Messenger: direct delivery failed, retrying {msg_id} via propagation node", RNS.LOG_NOTICE)
            _set_method(msg_id, METHOD_PROPAGATED)
            try:
                _dispatch(msg_id, destination, content, METHOD_PROPAGATED)
            except Exception as e:
                RNS.log(f"Messenger: propagation fallback failed: {e}", RNS.LOG_ERROR)

    lxm.register_delivery_callback(lambda m: _set_state(msg_id, m))
    lxm.register_failed_callback(on_failed)
    _router.handle_outbound(lxm)
    _set_state(msg_id, lxm)


def send_message(hex_hash, content, method=None):
    content = (content or "").strip()
    if not content:
        return {"ok": False, "error": "Message is empty"}
    error = _check_can_send()
    if error:
        return {"ok": False, "error": error}
    method = method or _settings.get("delivery_method", METHOD_DIRECT)
    if method == METHOD_PROPAGATED and not _propagation_available():
        return {"ok": False, "error": "No propagation node selected or heard yet"}

    destination, error = _resolve_destination(hex_hash)
    if error:
        return {"ok": False, "error": error}

    msg_id = str(uuid.uuid4())
    with _lock:
        db = _open_db()
        db.execute(
            "INSERT INTO messages (id, lxm_hash, peer, incoming, title, content, timestamp, state, read, method)"
            " VALUES (?, NULL, ?, 0, '', ?, ?, 'outbound', 1, ?)",
            (msg_id, hex_hash, content, time.time(), method),
        )
        db.commit()
        _bump_messages()

    _dispatch(msg_id, destination, content, method)
    return {"ok": True, "id": msg_id}


def retry_message(msg_id, method=None):
    """Resend a failed outgoing message, optionally with a different delivery method."""
    error = _check_can_send()
    if error:
        return {"ok": False, "error": error}
    with _lock:
        row = _open_db().execute(
            "SELECT peer, content, method, state FROM messages WHERE id = ? AND incoming = 0", (msg_id,),
        ).fetchone()
    if row is None:
        return {"ok": False, "error": "Message not found"}
    peer, content, old_method, state = row
    if state not in ("failed", "rejected", "cancelled"):
        return {"ok": False, "error": "Only failed messages can be retried"}
    method = method or old_method or METHOD_DIRECT
    if method == METHOD_PROPAGATED and not _propagation_available():
        return {"ok": False, "error": "No propagation node selected or heard yet"}
    destination, error = _resolve_destination(peer)
    if error:
        return {"ok": False, "error": error}
    with _lock:
        db = _open_db()
        db.execute(
            "UPDATE messages SET method = ?, state = 'outbound', lxm_hash = NULL WHERE id = ?", (method, msg_id),
        )
        db.commit()
        _bump_messages()
    _dispatch(msg_id, destination, content, method)
    return {"ok": True, "id": msg_id}


def list_conversations():
    with _lock:
        db = _open_db()
        rows = db.execute(
            """SELECT m.peer, m.content, m.timestamp, m.incoming, m.state,
                      (SELECT COUNT(*) FROM messages u WHERE u.peer = m.peer AND u.incoming = 1 AND u.read = 0)
               FROM messages m
               WHERE m.timestamp = (SELECT MAX(timestamp) FROM messages x WHERE x.peer = m.peer)
               GROUP BY m.peer
               ORDER BY m.timestamp DESC"""
        ).fetchall()
    return [
        {
            "peer": r[0], "name": _peer_name(r[0]), "lastContent": r[1], "lastTimestamp": r[2],
            "lastIncoming": bool(r[3]), "lastState": r[4], "unread": r[5],
        }
        for r in rows
    ]


def get_messages(hex_hash, limit=500):
    with _lock:
        db = _open_db()
        rows = db.execute(
            "SELECT id, incoming, content, timestamp, state, signature_ok, method FROM messages"
            " WHERE peer = ? ORDER BY timestamp DESC LIMIT ?",
            (hex_hash, int(limit)),
        ).fetchall()
    rows.reverse()
    return {
        "peer": hex_hash,
        "name": _peer_name(hex_hash),
        "messages": [
            {
                "id": r[0], "incoming": bool(r[1]), "content": r[2], "timestamp": r[3], "state": r[4],
                "signatureOk": bool(r[5]), "method": r[6],
            }
            for r in rows
        ],
    }


def mark_read(hex_hash):
    with _lock:
        db = _open_db()
        cur = db.execute("UPDATE messages SET read = 1 WHERE peer = ? AND read = 0", (hex_hash,))
        db.commit()
        if cur.rowcount > 0:
            _bump_messages()
    return {"ok": True}


def delete_conversation(hex_hash):
    with _lock:
        db = _open_db()
        db.execute("DELETE FROM messages WHERE peer = ?", (hex_hash,))
        db.commit()
        _bump_messages()
    return {"ok": True}


def take_new_incoming():
    """Return and clear messages received since the last call (for notifications)."""
    with _lock:
        items = list(_new_incoming)
        _new_incoming.clear()
    for item in items:
        item["name"] = _peer_name(item["peer"])
    return items


def get_status():
    with _lock:
        unread = 0
        if _db is not None:
            unread = _db.execute("SELECT COUNT(*) FROM messages WHERE incoming = 1 AND read = 0").fetchone()[0]
        return {
            "running": _running,
            "destinationHash": _delivery_hash_for(_identity).hex() if _identity else None,
            "displayName": (_settings or {}).get("display_name", ""),
            "lastAnnounce": _last_announce,
            "nodesVersion": _nodes_version,
            "messagesVersion": _messages_version,
            "unread": unread,
            "identityChanged": _identity_changed,
            "propagation": get_propagation() if _settings is not None else None,
        }


def get_monitor():
    import network_monitor
    with _lock:
        names = {h: n.get("name") for h, n in _nodes.items() if n.get("name")}
    if _delivery_destination is not None:
        names[_delivery_destination.hash.hex()] = ((_settings or {}).get("display_name") or "This device") + " (you)"
    return network_monitor.snapshot(names)


# --------------------------------------------------------------------------
# JSON dispatch for the Kotlin side
# --------------------------------------------------------------------------

_DISPATCH = {
    "get_identity": lambda a: get_identity(),
    "get_status": lambda a: get_status(),
    "set_display_name": lambda a: set_display_name(a.get("name", "")),
    "set_announce_interval": lambda a: set_announce_interval(a.get("seconds", 0)),
    "announce": lambda a: announce(),
    "reset_identity": lambda a: reset_identity(),
    "export_identity": lambda a: export_identity(a.get("passphrase")),
    "import_identity": lambda a: import_identity(a.get("data", ""), a.get("passphrase")),
    "list_nodes": lambda a: list_nodes(),
    "add_contact": lambda a: add_contact(a.get("text", ""), a.get("name")),
    "set_favorite": lambda a: set_favorite(a.get("hash", ""), a.get("favorite", False)),
    "remove_node": lambda a: remove_node(a.get("hash", "")),
    "request_path": lambda a: request_path(a.get("hash", "")),
    "send_message": lambda a: send_message(a.get("hash", ""), a.get("content", ""), a.get("method")),
    "retry_message": lambda a: retry_message(a.get("id", ""), a.get("method")),
    "get_propagation": lambda a: get_propagation(),
    "set_propagation_settings": lambda a: set_propagation_settings(a),
    "sync_messages": lambda a: sync_messages(),
    "cancel_sync": lambda a: cancel_sync(),
    "list_conversations": lambda a: list_conversations(),
    "get_messages": lambda a: get_messages(a.get("hash", ""), a.get("limit", 500)),
    "mark_read": lambda a: mark_read(a.get("hash", "")),
    "delete_conversation": lambda a: delete_conversation(a.get("hash", "")),
    "take_new_incoming": lambda a: take_new_incoming(),
    "get_monitor": lambda a: get_monitor(),
}


def call(storage_path, method, args_json):
    """Entry point from Kotlin: returns {"result": ...} or {"error": "..."} as a JSON string."""
    try:
        init(storage_path)
        handler = _DISPATCH.get(method)
        if handler is None:
            return json.dumps({"error": f"Unknown method: {method}"})
        args = json.loads(args_json) if args_json else {}
        return json.dumps({"result": handler(args)})
    except Exception as e:
        RNS.log(f"Messenger: {method} failed: {e}", RNS.LOG_ERROR)
        return json.dumps({"error": str(e)})
