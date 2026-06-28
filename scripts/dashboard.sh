#!/usr/bin/env bash
# SixthSense — live dashboard launcher.
#
# Serves live-dashboard/ and wires the phone <-> Mac link so the dashboard shows
# the Galaxy S25 Ultra's live camera + on-device perception in real time. The
# dashboard is VISUALIZATION ONLY (CLAUDE.md) — it never feeds the assistive path.
#
# Usage:
#   scripts/dashboard.sh            # USB: adb forward :8080, serve, open browser
#   scripts/dashboard.sh --fake     # no phone: start the dev "fake phone" feeder
#   scripts/dashboard.sh --no-open  # don't auto-open the browser
#
# Why USB/adb-forward by default: the demo runs in airplane mode, so Wi-Fi LAN may
# be off. `adb forward tcp:8080 tcp:8080` tunnels the phone's WebSocket to the
# Mac's localhost over the USB cable, which works in airplane mode. For a Wi-Fi /
# phone-hotspot demo instead, set the Device box in the dashboard to the phone's
# LAN IP (printed below) and press Connect.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DASH_DIR="$ROOT/live-dashboard"
HTTP_PORT="${SIXTHSENSE_DASH_PORT:-5173}"
WS_PORT="${SIXTHSENSE_WS_PORT:-8080}"

FAKE=0; OPEN=1
for a in "$@"; do
  case "$a" in
    --fake) FAKE=1 ;;
    --no-open) OPEN=0 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown arg: $a" >&2; exit 2 ;;
  esac
done

bold() { printf "\033[1m%s\033[0m\n" "$1"; }
ok()   { printf "  \033[32m✓\033[0m %s\n" "$1"; }
warn() { printf "  \033[33m!\033[0m %s\n" "$1"; }

# shellcheck source=scripts/adb_common.sh
source "$SCRIPT_DIR/adb_common.sh" 2>/dev/null || true

HTTP_PID=""; FAKE_PID=""
cleanup() {
  [ -n "$HTTP_PID" ] && kill "$HTTP_PID" 2>/dev/null
  [ -n "$FAKE_PID" ] && kill "$FAKE_PID" 2>/dev/null
  [ "$FAKE" -eq 0 ] && ss_adb forward --remove "tcp:$WS_PORT" 2>/dev/null
  echo; echo "dashboard stopped."
}
trap cleanup INT TERM EXIT

bold "SixthSense live dashboard"
echo "  serving:  $DASH_DIR"
echo "  http://localhost:$HTTP_PORT   ·   device WS expected on :$WS_PORT"
echo

if [ "$FAKE" -eq 1 ]; then
  command -v node >/dev/null 2>&1 || { echo "node required for --fake" >&2; exit 1; }
  node "$DASH_DIR/dev/fake-phone.mjs" "$WS_PORT" &
  FAKE_PID=$!
  ok "fake-phone feeder running on ws://localhost:$WS_PORT (no device needed)"
else
  if ss_adb get-state >/dev/null 2>&1; then
    if ss_adb forward "tcp:$WS_PORT" "tcp:$WS_PORT" >/dev/null 2>&1; then
      ok "adb forward localhost:$WS_PORT -> device:$WS_PORT (USB, airplane-mode safe)"
    else
      warn "adb forward failed — connect over Wi-Fi instead (IP below)"
    fi
    # Phone Wi-Fi IP for the LAN / hotspot fallback.
    IP="$(ss_adb shell ip -f inet addr show wlan0 2>/dev/null | grep -o 'inet [0-9.]*' | awk '{print $2}' | head -1)"
    [ -n "$IP" ] && echo "  Wi-Fi fallback: set the Device box to  ws://$IP:$WS_PORT"
  else
    warn "no device via adb. Plug in the S25 (USB debugging) or use --fake, or"
    warn "set the dashboard Device box to the phone's ws://<ip>:$WS_PORT over Wi-Fi."
  fi
fi
echo
echo "  In the app: tap \"Start vision\" so the phone streams camera frames."
echo "  Press Ctrl-C to stop."
echo

command -v python3 >/dev/null 2>&1 || { echo "python3 required to serve" >&2; exit 1; }
( cd "$DASH_DIR" && python3 -m http.server "$HTTP_PORT" >/dev/null 2>&1 ) &
HTTP_PID=$!

sleep 1
[ "$OPEN" -eq 1 ] && command -v open >/dev/null 2>&1 && open "http://localhost:$HTTP_PORT"

wait "$HTTP_PID"
