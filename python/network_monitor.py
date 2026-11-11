"""
Network monitor: a read-only view of RNS Transport state for the UI.

Reports the path table, the announce rebroadcast queue and a rolling log of
every announce heard (any application, not only LXMF), all as plain
JSON-serialisable values.
"""

import collections
import threading
import time

import RNS

MAX_RECENT_ANNOUNCES = 200

# Aspects we can label; anything else is reported as "other".
KNOWN_ASPECTS = [
    ("lxmf.delivery", "LXMF peer"),
    ("lxmf.propagation", "LXMF propagation node"),
    ("nomadnetwork.node", "Nomad Network node"),
    ("rnstransport.discovery.interface", "Interface discovery"),
    ("rnstransport.remote.management", "Remote management"),
    ("rnstransport.info.blackhole", "Blackhole list"),
    ("rnstransport.probe", "Probe"),
    ("rnstransport.network", "Network"),
    ("call.audio", "LXST call"),
]

_lock = threading.Lock()
_recent = collections.deque(maxlen=MAX_RECENT_ANNOUNCES)
_total_announces = 0
_started_at = None
_handler = None


def _aspect_of(destination_hash, identity):
    if identity is None:
        return None, "Unknown"
    for name, label in KNOWN_ASPECTS:
        try:
            if RNS.Destination.hash_from_name_and_identity(name, identity) == destination_hash:
                return name, label
        except Exception:
            pass
    return None, "Other"


def _decode_name(aspect, app_data):
    """Best-effort human-readable name from announce app data."""
    if not app_data:
        return None
    try:
        if aspect in ("lxmf.delivery", "lxmf.propagation"):
            import LXMF
            if aspect == "lxmf.delivery":
                return LXMF.display_name_from_app_data(app_data)
            return LXMF.pn_name_from_app_data(app_data) if hasattr(LXMF, "pn_name_from_app_data") else None
        if aspect == "nomadnetwork.node":
            return app_data.decode("utf-8", errors="replace")
    except Exception:
        return None
    return None


class _CatchAllHandler:
    def __init__(self):
        self.aspect_filter = None
        self.receive_path_responses = True

    def received_announce(self, destination_hash, announced_identity, app_data, announce_packet_hash, is_path_response):
        global _total_announces
        try:
            aspect, label = _aspect_of(destination_hash, announced_identity)
            hops = RNS.Transport.hops_to(destination_hash)
            iface = RNS.Transport.next_hop_interface(destination_hash)
            entry = {
                "hash": destination_hash.hex(),
                "identity": announced_identity.hash.hex() if announced_identity is not None else None,
                "aspect": aspect,
                "kind": label,
                "name": _decode_name(aspect, app_data),
                "hops": hops if hops != RNS.Transport.PATHFINDER_M else None,
                "interface": str(iface) if iface is not None else None,
                "pathResponse": bool(is_path_response),
                "timestamp": time.time(),
            }
            with _lock:
                _recent.appendleft(entry)
                _total_announces += 1
        except Exception as e:
            RNS.log(f"Network monitor: failed to record announce: {e}", RNS.LOG_ERROR)


def start():
    """Register the catch-all announce handler. Safe to call more than once."""
    global _handler, _started_at
    with _lock:
        if _handler is not None:
            return
        _handler = _CatchAllHandler()
        _started_at = time.time()
    RNS.Transport.register_announce_handler(_handler)


def _hex(value):
    if isinstance(value, (bytes, bytearray)):
        return bytes(value).hex()
    return None


def get_path_table():
    T = RNS.Transport
    rows = []
    with T.path_table_lock:
        items = list(T.path_table.items())
    for destination_hash, entry in items:
        try:
            iface = entry[T.IDX_PT_RVCD_IF] if hasattr(T, "IDX_PT_RVCD_IF") else entry[5]
            rows.append({
                "hash": destination_hash.hex(),
                "via": _hex(entry[1]),
                "hops": int(entry[2]),
                "timestamp": float(entry[0]),
                "expires": float(entry[3]),
                "interface": str(iface) if iface is not None else None,
            })
        except Exception as e:
            RNS.log(f"Network monitor: bad path entry: {e}", RNS.LOG_DEBUG)
    rows.sort(key=lambda r: (r["hops"], -r["timestamp"]))
    return rows


def get_announce_queue():
    T = RNS.Transport
    rows = []
    for destination_hash, entry in list(T.announce_table.items()):
        try:
            iface = entry[3]
            rows.append({
                "hash": destination_hash.hex(),
                "timestamp": float(entry[0]),
                "retransmitAt": float(entry[1]),
                "retries": int(entry[2]),
                "hops": int(entry[4]),
                "interface": str(iface) if iface is not None else None,
            })
        except Exception as e:
            RNS.log(f"Network monitor: bad announce entry: {e}", RNS.LOG_DEBUG)
    rows.sort(key=lambda r: r["retransmitAt"])
    return rows


def get_recent_announces(limit=MAX_RECENT_ANNOUNCES):
    with _lock:
        return list(_recent)[:limit]


def snapshot(names=None):
    """Everything the monitor screen shows. `names` maps hex hash -> display name."""
    names = names or {}
    paths = get_path_table()
    for p in paths:
        p["name"] = names.get(p["hash"])
    queue = get_announce_queue()
    for a in queue:
        a["name"] = names.get(a["hash"])
    recent = get_recent_announces()
    for r in recent:
        if not r.get("name"):
            r["name"] = names.get(r["hash"])
    T = RNS.Transport
    return {
        "paths": paths,
        "announceQueue": queue,
        "recentAnnounces": recent,
        "totalAnnounces": _total_announces,
        "monitoringSince": _started_at,
        "transportEnabled": bool(getattr(T, "owner", None) and RNS.Reticulum.transport_enabled()),
        "now": time.time(),
    }
