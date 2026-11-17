#!/usr/bin/env bash
# Runs LiveDesktopTest on an emulator against a real glide-phone-desktop build, isolated
# from the user's own daemon: private D-Bus session, private config, its own TCP port.
#   scripts/live-desktop-test.sh /path/to/glide-phone-desktop/target/debug/glide-phone
set -euo pipefail
desktop_bin="${1:?path to the glide-phone binary}"
adb="${ADB:-$HOME/Android/Sdk/platform-tools/adb} -s ${EMULATOR:-emulator-5554}"
work="$(mktemp -d)"
pid=""
cleanup() {
  # The daemon is a child of dbus-run-session; stop it by its PID.
  [ -n "$pid" ] && kill "$pid" 2>/dev/null || true
  kill "$runner" 2>/dev/null || true
  rm -rf "$work"
}
trap cleanup EXIT
mkdir -p "$work/config" "$work/data"

# The daemon in its own bus; the bus address is written for us to talk to it.
XDG_CONFIG_HOME="$work/config" XDG_DATA_HOME="$work/data" dbus-run-session -- bash -c \
  "echo \$DBUS_SESSION_BUS_ADDRESS > '$work/bus'; exec '$desktop_bin' --daemon" > "$work/daemon.log" 2>&1 &
runner=$!
for _ in $(seq 50); do [ -s "$work/bus" ] && [ -s "$work/config/glide/phone.json" ] && break; sleep 0.2; done
bus="$(cat "$work/bus")"
desktop_id="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['device_id'])" "$work/config/glide/phone.json")"
call() { busctl --address="$bus" call org.glide.Phone /org/glide/Phone org.glide.Phone "$@"; }
for _ in $(seq 50); do call ListDevicesJson >/dev/null 2>&1 && break; sleep 0.2; done
pid="$(pgrep -f -n -- "$desktop_bin --daemon")"
port="$(ss -ltnpH 2>/dev/null | grep "pid=$pid," | awk '{print $4}' | sed 's/.*://' | sort -n | head -1)"
echo "desktop $desktop_id on port $port (pid $pid)"

# Accept the phone's pairing request as the user would in the window.
(
  for _ in $(seq 120); do
    id="$(call ListDevicesJson 2>/dev/null | python3 -c "
import json,sys
raw=sys.stdin.read().split(' ',1)[1]
for d in json.loads(json.loads(raw)):
    if d['pair_state']=='RequestedByPeer': print(d['id']); break" 2>/dev/null || true)"
    if [ -n "$id" ]; then
      call AcceptPair s "$id" >/dev/null; echo "accepted $id"
      # Then what the computer sends to the phone.
      sleep 4
      call Ping s "$id" >/dev/null
      call ShareText ss "$id" "glide-live-text" >/dev/null
      call ControlPhoneInput ss "$id" '{"key":"ї"}' >/dev/null
      break
    fi
    sleep 0.5
  done
) &

$adb shell am instrument -w -e class org.mindenit.glidephone.LiveDesktopTest \
  -e desktopHost 10.0.2.2 -e desktopPort "$port" -e desktopId "$desktop_id" \
  org.mindenit.glidephone.test/androidx.test.runner.AndroidJUnitRunner | tee "$work/instrument.log"
grep -q '^OK (1 test)' "$work/instrument.log"

# What the desktop now shows for the phone.
state="$(call ListDevicesJson | cut -d' ' -f2-)"
python3 - "$state" <<'PY'
import json,sys
devices=json.loads(json.loads(sys.argv[1]))
phone=[d for d in devices if d['pair_state']=='Paired']
assert phone, devices
battery=phone[0]['battery']
assert battery and battery['charge']==42 and battery['is_charging'], battery
print("desktop sees the phone paired, battery", battery)
PY
echo "LIVE TEST PASSED"
