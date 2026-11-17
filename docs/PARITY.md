# KDE Connect parity ledger

Reference: `kdeconnect-android` (`src/main/java/org/kde/kdeconnect`). States:

- **verified** — exercised live against a real peer or by an instrumented test on a device/emulator
- **implemented** — code + unit tests, not yet exercised live
- **missing** — not implemented

Desktop peer available for live checks: `glide-phone --daemon` (supports battery, clipboard,
findmyphone, mousepad, notification(+action/reply/request), ping, presenter, share, pair).
Everything else needs a loopback instrumented test (`ProtocolLoopbackTest` and friends, where
the test plays the peer) or a real KDE Connect desktop.

## Core protocol / backends

| Feature | Reference | State | Notes |
|---|---|---|---|
| Identity packet v8 (+ targetDeviceId / targetProtocolVersion) | `DeviceInfo`, `LanLinkProvider` | verified | loopback, both TLS roles |
| Protocol v7 peers (no encrypted identity, v7 code) | v7 paths | verified | loopback v7 handshake |
| UDP broadcast on start / network change / while unlinked | `LanLinkProvider.broadcastUdpIdentityPacket` | implemented | |
| Dial peers whose broadcast we receive (TCP client, TLS server) | `udpPacketReceived` | verified | loopback dialer ↔ listener |
| Accept incoming TCP (TLS client) | `tcpPacketReceived` | verified | daemon + loopback |
| Single UDP 1716 owner feeding the device list | — | implemented | |
| Rate limit per IP (1 s) and unpaired cap (42) | `rateLimitByIp`, `MAX_UNPAIRED_CONNECTIONS` | implemented | |
| Refuse protocol downgrade of trusted peers | `isProtocolDowngrade` | implemented | |
| mDNS announce + background discovery (`_kdeconnect._udp`) | `MdnsDiscovery` | implemented | |
| Custom device addresses | `CustomDevicesActivity` | implemented | |
| Trusted networks (+ CGNAT / ULA as LAN) | `TrustedNetworkHelper`, `isPrivateAddress` | implemented | unit tested |
| Bluetooth backend | `backends/bluetooth` | missing | |
| v8 pairing with verification code, 30 min skew | `PairingHandler` | verified | loopback: either side, reject + retry, stale timestamp |
| Unpair / re-pair on pair request over trusted link | `PairingHandler.packetReceived` | implemented | |
| Payload transfer (TLS, ports 1739-1764, pinned cert) | `LanLink.sendPayload/receivePayload` | verified | loopback incl. unpinned receiver refused |
| Small payloads queued per device (no port exhaustion) | — | implemented | |

## Plugins

| Plugin | State | Gaps |
|---|---|---|
| battery | verified | daemon |
| clipboard (+connect timestamps, skip sensitive, floating activity, QS tile, notification action) | verified | floating activity read on emulator |
| connectivityreport (per SIM, on change) | implemented | |
| contacts (vCard metadata, consent dialog) | implemented | |
| digitizer | implemented | |
| findmyphone (alarm stream, ringtone, flashlight, found-it screen) | implemented | |
| findremotedevice | implemented | |
| inputdevicesreceiver (`shareinputdevices*`) | implemented | needs accessibility service enabled |
| mousepad (send, keyboardstate, special keys, keystrokes share target) | verified | daemon |
| mousereceiver | implemented | AccessibilityService gestures + cursor |
| mpris (control PC, album art, loop/shuffle, media notification, volume keys) | implemented | notification verified on emulator |
| mprisreceiver (expose phone, album art payload) | implemented | |
| notifications (send, icons, conversations, per-app filter, privacy, locked-only) | implemented | filter verified on emulator |
| ping | verified | daemon |
| presenter | verified | daemon |
| receivenotifications (opt-in per device, icon payload, silent) | implemented | |
| remotekeyboard / remotekeyboardime | implemented | IME + AccessibilityService |
| runcommand (output console, setup, stop, notification action) | implemented | no widget / device controls |
| sftp (Android 11+, key + password login) | verified | loopback SSHD client login + list |
| sftp on Android 7–10 (SAF) | missing | |
| share (share target to chosen device, multi-file, update, progress, open) | verified | daemon |
| sms | implemented | no MMS / attachments |
| systemvolume | implemented | |
| telephony (cancel+missed, request_mute, thumbnail, blocked numbers) | implemented | |

## App surfaces

| Surface | State |
|---|---|
| Foreground service keeping links alive (+ clipboard/files/commands actions) | implemented |
| Start on boot / package replaced | implemented |
| Per-device plugin enable/disable (KDE defaults) | verified (settings gating test) |
| Quick-settings clipboard tile | implemented |
| Home widget, device controls (runcommand) | missing |
