#!/usr/bin/env bash
# Read-only environment check for SixthSense dev on macOS. Prints status only.
set -uo pipefail

bold()  { printf "\n\033[1m%s\033[0m\n" "$1"; }
have()  { command -v "$1" >/dev/null 2>&1; }
line()  { printf "  %-16s %s\n" "$1" "$2"; }

bold "macOS"
line "Version:" "$(sw_vers -productName 2>/dev/null) $(sw_vers -productVersion 2>/dev/null)"
line "Arch:"    "$(uname -m)"

bold "Android Studio"
if [ -d "/Applications/Android Studio.app" ]; then
  line "App:" "/Applications/Android Studio.app"
else
  line "App:" "NOT FOUND — install from https://developer.android.com/studio"
fi

bold "Android SDK / adb"
line "ANDROID_HOME:" "${ANDROID_HOME:-(unset)}"
if have adb; then
  line "adb:" "$(command -v adb)"
elif [ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]; then
  line "adb:" "$HOME/Library/Android/sdk/platform-tools/adb (not on PATH)"
else
  line "adb:" "NOT FOUND — install SDK Platform Tools, set ANDROID_HOME"
fi

bold "Connected devices (adb devices -l)"
if have adb; then
  adb devices -l 2>/dev/null | sed 's/^/  /'
elif [ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]; then
  "$HOME/Library/Android/sdk/platform-tools/adb" devices -l 2>/dev/null | sed 's/^/  /'
else
  echo "  (adb unavailable — skipped)"
fi

bold "Java"
if have java; then java -version 2>&1 | sed 's/^/  /'; else line "java:" "NOT FOUND"; fi

bold "Node / npm"
if have node; then line "node:" "$(node --version)"; else line "node:" "NOT FOUND"; fi
if have npm;  then line "npm:"  "$(npm --version)";  else line "npm:"  "NOT FOUND"; fi

bold "Python / uv"
if have python3; then line "python3:" "$(python3 --version 2>&1)"; else line "python3:" "NOT FOUND"; fi
if have uv; then line "uv:" "$(uv --version 2>&1)"; else line "uv:" "NOT FOUND — curl -LsSf https://astral.sh/uv/install.sh | sh"; fi

bold "Claude Code"
if have claude; then line "claude:" "$(command -v claude)"; else line "claude:" "NOT FOUND"; fi

bold "Optional"
if have scrcpy; then line "scrcpy:" "$(command -v scrcpy)"; else line "scrcpy:" "not installed (brew install scrcpy)"; fi
if have qai-hub; then line "qai-hub:" "$(command -v qai-hub)"; else line "qai-hub:" "not installed (pip install qai-hub)"; fi

echo
echo "Done. Address any NOT FOUND items above before the hackathon."
