# KDE Connect parity ledger

Reference: `kdeconnect-android` (`src/main/java/org/kde/kdeconnect`). States:

- **verified** — exercised live against a real peer or by an instrumented test on a device
- **implemented** — code + unit tests, not yet exercised live
- **missing** — not implemented

Desktop peer available for live checks: `glide-phone --daemon` (supports battery, clipboard,
findmyphone, mousepad, notification(+action/reply/request), ping, presenter, share, pair).
Everything else needs a loopback instrumented test or a real KDE Connect desktop.

## Core protocol / backends

| Feature | Reference | State | Notes |
|---|---|---|---|
| Identity packet v8 (+ targetDeviceId / targetProtocolVersion) | `DeviceInfo`, `LanLinkProvider` | implemented | |
| UDP broadcast on start / network change / while unlinked | `LanLinkProvider.broadcastUdpIdentityPacket` | implemented | cadence changed after last live proof |
| Dial peers whose broadcast we receive (TCP client, TLS server) | `udpPacketReceived` | implemented | |
| Accept incoming TCP (TLS client) | `tcpPacketReceived` | verified (pre-cadence change) | daemon connected back to :1716 |
| Rate limit per IP (1 s) and unpaired cap (42) | `rateLimitByIp`, `MAX_UNPAIRED_CONNECTIONS` | implemented | |
| Refuse protocol downgrade of trusted peers; accept v7 peers | `isProtocolDowngrade`, v7 paths | implemented | |
| mDNS announce + background discovery (`_kdeconnect._udp`) | `MdnsDiscovery` | implemented | |
| Custom device addresses | `CustomDevicesActivity` | implemented | |
| Trusted networks (+ CGNAT / ULA as LAN) | `TrustedNetworkHelper`, `isPrivateAddress` | implemented | |
| Bluetooth backend | `backends/bluetooth` | missing | |
| v8 pairing with verification code, 30 min skew | `PairingHandler` | implemented | |
| Unpair / re-pair on pair request over trusted link | `PairingHandler.packetReceived` | implemented | daemon never sends pair on reconnect |
| Payload transfer (TLS, ports 1739-1764) | `LanLink.sendPayload/receivePayload` | implemented | |

## Plugins

| Plugin | State | Gaps |
|---|---|---|
| battery | implemented | |
| clipboard (+connect timestamps, skip sensitive) | implemented | no QS tile / floating activity |
| connectivityreport (per SIM, on change) | implemented | |
| contacts (vCard metadata) | implemented | per-device switch, no first-use consent dialog |
| digitizer | implemented | |
| findmyphone (alarm stream, ringtone, flashlight, found-it screen) | implemented | |
| findremotedevice | implemented | |
| inputdevicesreceiver | missing | `kdeconnect.shareinputdevices*` |
| mousepad (send, keyboardstate, special keys, keystrokes share target) | implemented | |
| mousereceiver | missing | needs AccessibilityService |
| mpris (control PC, album art, loop/shuffle) | implemented | no media-style notification |
| mprisreceiver (expose phone, album art payload) | implemented | |
| notifications (send, icons, conversations) | implemented | no per-app filter / privacy options |
| ping | implemented | |
| presenter | implemented | |
| receivenotifications (opt-in per device) | implemented | no icon payload shown |
| remotekeyboard / remotekeyboardime | missing | needs IME / AccessibilityService |
| runcommand (output console, setup, stop) | implemented | no widget / device controls |
| sftp | missing | needs embedded SFTP server |
| share (share target, multi-file, update, progress, open) | implemented | |
| sms | implemented | no MMS / attachments |
| systemvolume | implemented | |
| telephony (cancel+missed, request_mute, thumbnail) | implemented | no blocked numbers |

## App surfaces

| Surface | State |
|---|---|
| Foreground service keeping links alive | implemented |
| Start on boot / package replaced | implemented |
| Per-device plugin enable/disable (KDE defaults) | implemented |
| Quick-settings tile, home widget, device controls | missing |
