#!/usr/bin/env bash
# The real Glide Phone app (not a test client) on an emulator against a real
# glide-phone-desktop build, isolated from the user's own daemon:
#   scripts/real-app-live-test.sh /path/to/glide-phone-desktop/target/debug/glide-phone
# Checks pairing from the app's onboarding, battery changes, a contact's avatar reaching the
# computer, and the computer's cursor going over to the phone and coming back.
#
# It clears the app's data, moves the real cursor for a few seconds (a virtual mouse pushes
# it into the right screen edge) and holds host TCP ports 1739-1764 while it runs (adb
# forward, so the computer can fetch payloads from inside the emulator).
set -euo pipefail
bin="$(realpath "${1:?glide-phone binary}")"
desktop_repo="$(cd "$(dirname "$bin")/../.." && pwd)"
here="$(cd "$(dirname "$0")/.." && pwd)"
pkg=org.mindenit.glidephone
adb="${ADB:-$HOME/Android/Sdk/platform-tools/adb} -s ${EMULATOR:-emulator-5554}"
export ADB_CMD="$adb"
work="$(mktemp -d)"
pid=""
call() { busctl --timeout=10 --address="$(cat "$work/bus")" call org.glide.Phone /org/glide/Phone org.glide.Phone "$@"; }
cleanup() {
  [ -s "$work/bus" ] && call SetShareInputEdge s "" >/dev/null 2>&1 || true # never leave the cursor captured
  [ -n "$pid" ] && kill "$pid" 2>/dev/null || true
  $adb forward --remove-all >/dev/null 2>&1 || true
  $adb emu redir del udp:17160 >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT
fail() { echo "FAILED: $*"; exit 1; }
ui() { # ui <regex> [tap]
  python3 - "$@" <<'PY'
import os, re, subprocess, sys, xml.etree.ElementTree as ET
adb = os.environ["ADB_CMD"].split()
subprocess.run(adb + ["shell", "uiautomator", "dump", "/sdcard/ui.xml"], capture_output=True)
root = ET.fromstring(subprocess.run(adb + ["shell", "cat", "/sdcard/ui.xml"], capture_output=True, text=True).stdout)
for n in root.iter("node"):
    if any(re.search(sys.argv[1], v or "") for v in (n.get("text"), n.get("content-desc"))):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        if len(sys.argv) > 2:
            subprocess.run(adb + ["shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2)])
        sys.exit(0)
sys.exit(1)
PY
}
wait_ui() { for _ in $(seq 20); do ui "$@" && return 0; sleep 1; done; return 1; }
devices() { busctl --json=short --address="$(cat "$work/bus")" call org.glide.Phone /org/glide/Phone org.glide.Phone ListDevicesJson |
  python3 -c "import json,sys; print(json.dumps(json.loads(json.load(sys.stdin)['data'][0])))"; }
field() { devices | python3 -c "import json,sys; d=json.load(sys.stdin); print(d[0]$1 if d else '')"; }

# The app, fresh, with the permissions a user grants.
(cd "$here" && ./gradlew -q :app:assembleDebug)
# A fresh install (not pm clear, which also takes the runtime permissions away).
$adb uninstall "$pkg" >/dev/null 2>&1 || true
$adb install -g "$here/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
$adb shell cmd notification allow_listener "$pkg/.GlideNotificationListener"
$adb shell settings put secure enabled_accessibility_services "$pkg/$pkg.GlideAccessibilityService"
$adb shell settings put secure accessibility_enabled 1
$adb shell dumpsys battery reset
$adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
wait_ui '^Почати$' tap || fail "no onboarding"

# The desktop, in its own D-Bus session.
mkdir -p "$work/config" "$work/data"
RUST_LOG=glide_phone=info XDG_CONFIG_HOME="$work/config" XDG_DATA_HOME="$work/data" setsid dbus-run-session -- \
  bash -c "echo \$DBUS_SESSION_BUS_ADDRESS > '$work/bus'; exec '$bin' --daemon" > "$work/daemon.log" 2>&1 &
for _ in $(seq 50); do [ -s "$work/bus" ] && [ -s "$work/config/glide/phone.json" ] && break; sleep 0.2; done
sleep 2
pid="$(pgrep -f -n -- "$bin --daemon")"
port="$(ss -ltnpH | grep "pid=$pid," | awk '{print $4}' | sed 's/.*://' | sort -n | head -1)"
id="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['device_id'])" "$work/config/glide/phone.json")"
name="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['device_name'])" "$work/config/glide/phone.json")"
echo "desktop $id on port $port"

# The emulator can't hear broadcasts from the host: send our identity in, and let the
# computer reach the phone's payload ports.
$adb emu redir add udp:17160:1716 >/dev/null
for p in $(seq 1739 1764); do $adb forward "tcp:$p" "tcp:$p" >/dev/null; done
announce() {
  python3 - "$id" "$port" <<'PY'
import json, socket, sys, time
body = {"deviceId": sys.argv[1], "deviceName": "glide-test", "deviceType": "desktop", "protocolVersion": 8,
        "tcpPort": int(sys.argv[2]), "glideProtocol": 1, "incomingCapabilities": [], "outgoingCapabilities": []}
packet = json.dumps({"id": int(time.time() * 1000), "type": "kdeconnect.identity", "body": body}) + "\n"
socket.socket(socket.AF_INET, socket.SOCK_DGRAM).sendto(packet.encode(), ("127.0.0.1", 17160))
PY
}
connect() {
  for _ in $(seq 10); do
    announce
    for _ in $(seq 6); do [ "$(field "['connected']")" = True ] && return 0; sleep 0.5; done
  done
  fail "the app did not connect"
}
sleep 2
ui '^Оновити$' tap || true # the onboarding's search stops after a while
sleep 1
connect

# Pairing from the onboarding, confirmed on both sides.
wait_ui '^Підключити$' tap || fail "the computer isn't offered"
for _ in $(seq 20); do [ "$(field "['pair_state']")" = RequestedByPeer ] && break; sleep 0.5; done
wait_ui '^Код збігається$' tap || fail "no pairing code"
call AcceptPair s "$(field "['id']")" >/dev/null
for _ in $(seq 20); do [ "$(field "['pair_state']")" = Paired ] && break; sleep 0.5; done
[ "$(field "['pair_state']")" = Paired ] || fail "not paired"
phone="$(field "['id']")"
echo "paired with $phone"
wait_ui '^Готово$' tap || true
for _ in 1 2 3; do ui '^(Далі|Готово|Пропустити)$' tap || true; sleep 1.5; done
connect
echo "connected as a trusted device"

# Battery changes reach the computer.
$adb shell dumpsys battery unplug
$adb shell dumpsys battery set level 37
for _ in $(seq 20); do devices | grep -q '"charge": 37' && break; sleep 0.5; done
devices | grep -q '"charge": 37, "is_charging": false' || fail "battery change didn't arrive"
echo "battery 37%, not charging"

# A contact's picture (not the app's icon) on the computer.
avatar="$(python3 - <<'PY'
import base64, struct, zlib
w = h = 96
rows = b"".join(b"\0" + b"".join(bytes(((x * 255) // w, (y * 255) // h, 160)) for x in range(w)) for y in range(h))
c = lambda t, d: struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
print(base64.b64encode(b"\x89PNG\r\n\x1a\n" + c(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
      + c(b"IDAT", zlib.compress(rows)) + c(b"IEND", b"")).decode())
PY
)"
$adb shell "cmd notification post -S messaging --conversation 'Тарас' --message 'Тарас:Привіт' -I 'data:base64,$avatar' -t 'Тарас' glideavatar 'Привіт'" >/dev/null
for _ in $(seq 30); do devices | grep -q glideavatar && break; sleep 0.5; done
icon="$(devices | python3 -c "
import json,sys
for n in json.load(sys.stdin)[0]['notifications']:
    if 'glideavatar' in n['id'] and n['has_avatar']: print(n['icon_path'])")"
[ -n "$icon" ] || fail "the notification arrived without the contact's picture"
echo "avatar shown: $icon"

# The computer's cursor goes over to the phone at the right edge and comes back.
[ "$(call SetShareInputEdge s right)" = "b true" ] || fail "the edge strip can't be shown"
sleep 1
nudge() { (cd "$desktop_repo" && GLIDE_NUDGE="$1" cargo test -q --offline --lib edge::tests::nudge_cursor -- --ignored >/dev/null 2>&1); }
nudge 400,0,20
grep -q "the cursor went over to the phone" "$work/daemon.log" || {
  sed 's/\x1b\[[0-9;]*m//g' "$work/daemon.log" | grep -vE "monitor|org.glide.Config" | tail -6; fail "the cursor didn't go over"; }
nudge -30,0,60
for _ in $(seq 10); do grep -q "the phone gave the cursor back" "$work/daemon.log" && break; sleep 0.5; done
grep -q "the phone gave the cursor back" "$work/daemon.log" || fail "the phone didn't give the cursor back"
echo "cursor went over and came back"
echo "REAL APP LIVE TEST PASSED"
