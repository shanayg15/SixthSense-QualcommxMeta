#!/usr/bin/env bash
# SixthSense — FULL DEMO runner. One command: build the app, install it on the
# Galaxy S25 (USB), keep the screen awake, launch the app, and serve the live
# dashboard with the phone link wired up.
#
#   scripts/demo.sh             # build + install + run the live dashboard (USB)
#   scripts/demo.sh --fake      # no phone: drive the dashboard from the dev feeder
#   scripts/demo.sh --no-build  # skip the gradle build (reuse the last APK)
#   scripts/demo.sh --no-open   # don't auto-open the browser
#
# Dev/demo tooling only — the assistive runtime is 100% on the phone (CLAUDE.md).
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ASSETS="$ROOT/android/app/src/main/assets/models"
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
PKG="com.sixthsense"

FAKE=0; BUILD=1; OPEN_ARG=""
for a in "$@"; do
  case "$a" in
    --fake) FAKE=1 ;;
    --no-build) BUILD=0 ;;
    --no-open) OPEN_ARG="--no-open" ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) echo "unknown arg: $a" >&2; exit 2 ;;
  esac
done

bold() { printf "\n\033[1m%s\033[0m\n" "$1"; }
ok()   { printf "  \033[32m✓\033[0m %s\n" "$1"; }
err()  { printf "  \033[31m✗\033[0m %s\n" "$1"; }
# shellcheck source=scripts/adb_common.sh
source "$SCRIPT_DIR/adb_common.sh" 2>/dev/null || true

# --- no-phone path: just run the dashboard against the fake feeder ------------
if [ "$FAKE" -eq 1 ]; then
  exec "$SCRIPT_DIR/dashboard.sh" --fake $OPEN_ARG
fi

bold "SixthSense full demo"

# 1. models (NOT in git — must be present for on-device vision to run) ---------
missing=0
for m in depth.pte yolo.pte; do
  if [ -s "$ASSETS/$m" ]; then ok "model present: $m ($(du -h "$ASSETS/$m" | cut -f1))"; else err "MISSING model: $ASSETS/$m"; missing=1; fi
done
[ -s "$ASSETS/qwen.pte" ] && ok "model present: qwen.pte (voice LLM, optional)" || echo "  · qwen.pte not bundled — voice agent uses rule-based answers (fine for the camera/belt demo)"
if [ "$missing" -eq 1 ]; then
  cat <<EOF

  The .pte model binaries are git-ignored (never committed). Drop them in:
      $ASSETS/
  You need at least depth.pte and yolo.pte. Get them from the team's shared
  models (AirDrop / drive) or re-export via ~/sixthsense-models, then re-run.
  (To preview the dashboard with no phone/models at all: scripts/demo.sh --fake)
EOF
  exit 1
fi

# 2. device connected + authorized -------------------------------------------
state="$(ss_adb get-state 2>/dev/null || true)"
if [ "$state" != "device" ]; then
  err "no authorized device (adb get-state = '${state:-none}')."
  echo "  Plug in the S25 via USB, enable USB debugging, and tap Allow on the phone."
  echo "  (Or run: scripts/demo.sh --fake  to demo the dashboard with no phone.)"
  exit 1
fi
ok "device: $(ss_adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')"

# 3. build --------------------------------------------------------------------
if [ "$BUILD" -eq 1 ]; then
  bold "Building app-debug.apk"
  export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null)}"
  ( cd "$ROOT/android" && ./gradlew assembleDebug --console=plain ) || { err "gradle build failed"; exit 1; }
fi
[ -s "$APK" ] || { err "APK not found: $APK (run without --no-build)"; exit 1; }
ok "APK ready ($(du -h "$APK" | cut -f1))"

# 4. install (clean reinstall on signature mismatch) --------------------------
bold "Installing on the phone"
if ! ss_adb install -r "$APK" >/dev/null 2>&1; then
  echo "  signature mismatch or update conflict — uninstalling + clean install"
  ss_adb uninstall "$PKG" >/dev/null 2>&1
  ss_adb install "$APK" >/dev/null 2>&1 || { err "install failed"; exit 1; }
fi
ss_adb shell pm path "$PKG" >/dev/null 2>&1 || { err "package not installed"; exit 1; }
ok "installed $PKG"

# 5. permissions + keep the screen awake while plugged in ---------------------
ss_adb shell pm grant "$PKG" android.permission.CAMERA >/dev/null 2>&1 && ok "camera permission granted"
ss_adb shell settings put global stay_on_while_plugged_in 3 >/dev/null 2>&1 && ok "screen stays on while plugged in (camera won't pause)"

# 6. launch the app so its WebSocket server is up -----------------------------
ss_adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 && ok "app launched"

bold "Live dashboard"
echo "  Opening the dashboard. When it loads:  tap \"Start vision\" on the phone."
echo

# 7. serve + adb-forward + open browser (this blocks until Ctrl-C) ------------
exec "$SCRIPT_DIR/dashboard.sh" $OPEN_ARG
