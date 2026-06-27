#!/usr/bin/env bash
# Shared adb helper functions for SixthSense dev. Source this file:
#   source scripts/adb_common.sh
#
# These mirror what the MCP server does, for quick manual use. The DEBUG_*
# broadcasts only work on a `debug` build and are DEV-ONLY — never part of the
# assistive demo. Do not run belt/mock/ask helpers unless you intend to.

# Resolve adb: PATH, then ANDROID_HOME, then macOS default SDK path.
ss_adb() {
  if command -v adb >/dev/null 2>&1; then
    adb "$@"
  elif [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
    "$ANDROID_HOME/platform-tools/adb" "$@"
  elif [ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]; then
    "$HOME/Library/Android/sdk/platform-tools/adb" "$@"
  else
    echo "adb not found. Set ANDROID_HOME or install SDK Platform Tools." >&2
    return 127
  fi
}

SS_PACKAGE="${SIXTHSENSE_PACKAGE:-com.sixthsense}"

ss_devices()  { ss_adb devices -l; }
ss_logcat()   { ss_adb logcat -d -t "${1:-250}" SixthSenseScene:I SixthSenseMCP:I AndroidRuntime:E '*:S'; }
ss_clearlog() { ss_adb logcat -c; }
ss_launch()   { ss_adb shell monkey -p "$SS_PACKAGE" -c android.intent.category.LAUNCHER 1; }

# clamp helper
_ss_clamp() { local v=$1 lo=$2 hi=$3; (( v<lo )) && v=$lo; (( v>hi )) && v=$hi; echo "$v"; }

# DEV-ONLY debug broadcasts ---------------------------------------------------
# ss_belt LEFT CENTER RIGHT PATTERN   (0-255, 0-255, 0-255, 0-2)
ss_belt() {
  local l c r p
  l=$(_ss_clamp "${1:-0}" 0 255); c=$(_ss_clamp "${2:-0}" 0 255)
  r=$(_ss_clamp "${3:-0}" 0 255); p=$(_ss_clamp "${4:-0}" 0 2)
  ss_adb shell am broadcast -a com.sixthsense.DEBUG_BELT --ei l "$l" --ei c "$c" --ei r "$r" --ei p "$p"
}
# ss_mock on|off
ss_mock() {
  local en="true"; [ "${1:-on}" = "off" ] && en="false"
  ss_adb shell am broadcast -a com.sixthsense.DEBUG_MOCK --ez enabled "$en"
}
# ss_ask "what is ahead of me?"
ss_ask() {
  local q="${1:-what is ahead of me?}"
  ss_adb shell am broadcast -a com.sixthsense.DEBUG_ASK --es q "$q"
}

echo "[adb_common] helpers loaded: ss_devices ss_logcat ss_clearlog ss_launch ss_belt ss_mock ss_ask" >&2
