![Reticulum Network Stack](scripts/rns_logo.png)

# Reticulum for Android

A shared instance daemon for [Reticulum](https://reticulum.network/) on Android. Runs the [reference Python implementation](https://github.com/markqvist/Reticulum) via [Chaquopy](https://chaquo.com/chaquopy/) as a background service, so other Reticulum apps on the device can share a single network stack without each needing to bundle their own.

This fork adds a built-in LXMF messenger (identity, nodes, messaging, propagation nodes), Interface Access Codes (IFAC) on every interface type, a network monitor, and a number of RNode/LoRa fixes and improvements. See [Changes in this fork](#changes-in-this-fork).

Similar in approach to [Columba](https://github.com/liberatedsystems/Columba_Mailing_List), which also embeds the Python RNS stack via Chaquopy. The Kotlin BLE/RNode bridge used here is extracted from Columba as well, since pyjnius is not available in the Chaquopy environment.

Reticulum is a cryptography-based networking protocol for building resilient, decentralized communication systems over any available transport — TCP, UDP, I2P, LoRa (via RNode), or local WiFi/BLE.

## Features

- Shared instance — other RNS apps on the device connect through this one
- Full Reticulum stack running as a foreground service (separate process for clean lifecycle)
- Configure and manage interfaces: TCP client/server, UDP, AutoInterface, I2P, RNode (LoRa)
- Interface Access Codes (network name / passphrase / IFAC size) on all interfaces
- RNode setup wizard with frequency/bandwidth/SF/CR configuration, editing, and radio settings export/import
- Optional custom image on the RNode's own screen (off by default)
- Opens when a supported LoRa radio is plugged in over USB
- TCP client wizard with optional SOCKS proxy support (for Tor)
- LXMF messenger: identity, announces, nodes list, direct and propagated messages, propagation node sync and hosting
- Network monitor: live announces, path table and rebroadcast queue
- Live interface stats, traffic monitoring and LoRa radio diagnostics
- Auto-discovery of peers on local networks

## Changes in this fork

Compared to [torlando-tech/reticulum-android](https://github.com/torlando-tech/reticulum-android) at `09476cf` (RNS 1.4.2, LXMF 1.1.0).

### Interface Access Codes (IFAC)

- Network name, passphrase and IFAC size fields for every interface type: TCP client (wizard), TCP server, UDP, AutoInterface, I2P and RNode.
- IFAC size is entered in bits, as in the Reticulum config file. Leaving it empty uses the RNS default: 64 bits for RNode, 128 bits for IP-based interfaces.
- The RNode interface derives its IFAC key exactly like the reference implementation, so it interoperates with `rnsd`, Sideband and other Reticulum nodes. Surrounding whitespace is trimmed from the name and passphrase, matching the reference config parser.

### LXMF messenger

The app now includes an LXMF client running inside the service process (`python/messenger.py`). It is compatible with Sideband, NomadNet, MeshChat and other LXMF clients.

- **Home**: identity card with your LXMF address, plus node and network stats.
- **Identity**:
  - Display name and an "Announce now" button.
  - Automatic announce interval (minimum 5 minutes).
  - LXMF address as text and as a QR code (`lxma://` contact link).
  - Export and import of the identity: raw RNS format, or encrypted with a passphrase (Scrypt + Fernet).
  - Reset to a new identity.
- **Nodes**:
  - Lists LXMF peers, propagation nodes and NomadNet nodes heard through announces, with hops, interface and last-heard time.
  - Favorites, add a contact by address or `lxma://` link, request a path, remove.
  - "Use as propagation node" for propagation nodes.
- **Messages**:
  - Conversation list with unread badges and notifications for incoming messages.
  - Chat view with delivery state (sending, sent, delivered, stored on propagation node, failed).
  - Choose direct or propagated delivery, and retry failed messages.
  - Falls back to the propagation node when direct delivery fails.
- **Propagation nodes**:
  - Pick the closest node automatically or choose one manually.
  - Sync messages manually or on an interval, with live sync progress.
  - Optionally host a propagation node on the phone, with a storage limit.

Messenger data (identity, settings, nodes, message database, LXMF storage) is stored in the app's private storage under `files/messenger/`.

### RNode / LoRa

- `RNodeInterface` now subclasses the RNS `Interface` base class. This provides announce queueing, held announces, path request accounting and egress control, which RNS 1.4.x expects.
- The on-air bitrate is computed from SF, bandwidth and CR, using the reference formula, so the announce airtime cap is correct.
- Short-term and long-term airtime limits are saved with the interface and applied to the radio when it connects.
- **Radio display:** the RNode keeps its own screen unless **Custom display on radio** is turned on in the RNode settings. That switch is off by default. Turning it on shows the Reticulum logo on the radio. With it off, the next connection restores the radio's normal display.
- **USB attach:** plugging a supported LoRa radio in over USB opens the app, or brings it to the front if it is already running. The first time, Android asks which app should open for that device.
- **LoRa diagnostics** on the interface stats screen:
  - The radio parameters reported back by the device.
  - Frames sent and received, plus the last RSSI and SNR.
  - Counts of frames dropped because of an IFAC mismatch (missing, unexpected or wrong), with a hint explaining the likely cause.
- **Editing an RNode**: the wizard opens with the saved device pre-selected. Interfaces set up with Custom settings go straight to those settings with the saved values. Saving updates the interface in place instead of adding a duplicate.
- **Custom settings first**: "Custom Settings" is the first and default option when adding an RNode, with regions and presets below it.
- **Radio settings export/import**:
  - Covers frequency, bandwidth, SF, CR, TX power, airtime limits, and IFAC (network name, passphrase and size). No Bluetooth or device details. The file includes the IFAC passphrase.
  - Export to a JSON file or the clipboard.
  - Import from a file or the clipboard. Import also accepts `rnsd` config snippets such as `frequency = 867200000`.

### Network monitor

- Rebuilt monitor screen, opened from Home, with live refresh:
  - **Announces**: every announce heard from any application, labelled by type, with names, hops and interface.
  - **Paths**: the path table, with names, hops, next hop and expiry.
  - **Queue**: the announce rebroadcast queue (transport mode).
- Fixed the path table and announce queue readers. The announce queue reported the wrong interface. Both now read Transport state from Python (`python/network_monitor.py`).

## Work in Progress

Not all features of RNS are fully tested in Reticulum for Android. Notably, the following features need further testing before being relied upon:

- Blackhole Management
- Interface Discovery
- Hosting a propagation node on the phone

## Missing Interfaces

Generally speaking, any interface which uses pyjnius in Sideband requires a bridge to Kotlin code to work in chaquopy. On initial release, this app includes a bluetooth and usb bridge for RNode, but not a serial interface.

## Requirements

- Android 8.0+ (API 26)
- For RNode: a compatible LoRa device connected via USB or Bluetooth. Connecting a supported radio over USB opens the app.

## Building

```
git clone <this repository>
cd reticulum-android
./gradlew assembleDebug
```

Requires JDK 17 and the Android SDK (set `sdk.dir` in `local.properties` or `ANDROID_HOME`). Python 3.11 dependencies (RNS, LXMF, cryptography) are fetched automatically by Chaquopy during the build. The pinned versions are in `python/requirements.txt`.

### Release builds

Release signing is read from environment variables, so no key material lives in the repository:

| Variable | Value |
|---|---|
| `KEYSTORE_FILE` | The keystore file, base64-encoded (`base64 -w 0 release.jks`) |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |

```
./gradlew :app:assembleRelease
```

Without these variables the release APK is built unsigned. A push to `main` runs [`.github/workflows/ci.yml`](.github/workflows/ci.yml) once: unit tests, then a signed APK from the same four values stored as GitHub Actions secrets. The release is tagged `v` plus `versionName` from `app/build.gradle.kts`, and it includes the APK, its checksum and the signing certificate fingerprints. Release notes are kept in [CHANGELOG.md](CHANGELOG.md).

Keystores, `signing.properties` and other key files are excluded by `.gitignore`. Keep your keystore backed up: updates must be signed with the same key to install over an existing version.

## License

MIT
