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
| Rate limit identity packets per IP / device id | `rateLimitByIp/DeviceId` | partial | per-device 3 s dial throttle only |
| Refuse protocol downgrade of trusted peers | `isProtocolDowngrade` | missing | |
| mDNS service registration (`_kdeconnect._udp`) | `MdnsDiscovery` | missing | phone only discovers |
| Custom device IP list (manual dial) | `updateCustomDevicesList` | missing | |
| Trusted-network restriction | `TrustedNetworkHelper` | missing | |
| Bluetooth backend | `backends/bluetooth` | missing | |
| v8 pairing with verification code, 30 min skew | `PairingHandler` | implemented | |
| Unpair / re-pair on pair request over trusted link | `PairingHandler.packetReceived` | implemented | daemon never sends pair on reconnect |
| Payload transfer (TLS, ports 1739-1764) | `LanLink.sendPayload/receivePayload` | implemented | |

## Plugins

| Plugin | State | Gaps |
|---|---|---|
| battery | implemented | |
| clipboard (+connect timestamps) | implemented | no QS tile / floating activity |
| connectivityreport | implemented | sent on connect only, not on change |
| contacts | implemented | no per-device consent prompt |
| digitizer | implemented | |
| findmyphone | implemented | no full-screen "found it" activity |
| findremotedevice | implemented | |
| inputdevicesreceiver | missing | `kdeconnect.shareinputdevices*` |
| mousepad (send) | implemented | no keyboardstate handling, no "send keystrokes" share target |
| mousereceiver | missing | needs AccessibilityService |
| mpris (control PC) | implemented | no album art, no media notification |
| mprisreceiver (expose phone) | implemented | no album art payload |
| notifications (send) | implemented | no icon payload, no per-app filter |
| ping | implemented | |
| presenter | implemented | |
| receivenotifications | implemented | |
| remotekeyboard / remotekeyboardime | missing | needs IME / AccessibilityService |
| runcommand | implemented | no commandStarted/Output/Finished, no setup request, no widget |
| sftp | missing | needs embedded SFTP server |
| share | implemented | no SEND/SEND_MULTIPLE target, single file only, no `share.request.update`, no progress |
| sms | implemented | no MMS / attachments |
| systemvolume | implemented | |
| telephony | implemented | no thumbnail, no blocked numbers |

## App surfaces

| Surface | State |
|---|---|
| Foreground service keeping links alive | implemented |
| Start on boot / package replaced | missing |
| Per-device plugin enable/disable | missing |
| Quick-settings tile, home widget, device controls | missing |
