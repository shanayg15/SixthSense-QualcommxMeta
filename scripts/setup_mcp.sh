#!/usr/bin/env bash
# Register the SixthSense MCP server with Claude Code (project scope).
# Safe: verifies tooling and registers; does not install APKs or touch the phone.
set -euo pipefail

# Repo root = parent of this script's directory.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT"

bold() { printf "\033[1m%s\033[0m\n" "$1"; }
ok()   { printf "  \033[32m✓\033[0m %s\n" "$1"; }
warn() { printf "  \033[33m!\033[0m %s\n" "$1"; }

bold "SixthSense MCP setup"
echo "Repo root: $ROOT"
echo

# --- claude CLI ---
if command -v claude >/dev/null 2>&1; then
  ok "claude CLI found"
else
  warn "claude CLI not found. Install Claude Code, then re-run this script."
  warn "Docs: https://docs.claude.com/claude-code"
  exit 1
fi

# --- adb ---
if command -v adb >/dev/null 2>&1; then
  ok "adb found ($(command -v adb))"
elif [ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]; then
  ok "adb found at default SDK path"
  warn "Add to ~/.zshrc: export ANDROID_HOME=\"\$HOME/Library/Android/sdk\" && export PATH=\"\$ANDROID_HOME/platform-tools:\$PATH\""
else
  warn "adb not found. Install Android SDK Platform Tools and set ANDROID_HOME."
  warn "  export ANDROID_HOME=\"\$HOME/Library/Android/sdk\""
  warn "  export PATH=\"\$ANDROID_HOME/platform-tools:\$PATH\""
fi

# --- MCP environment (uv preferred, venv fallback) ---
REG_CMD=()
if command -v uv >/dev/null 2>&1; then
  ok "uv found — preparing MCP environment"
  ( cd "$ROOT/mcp" && uv sync >/dev/null 2>&1 ) || warn "uv sync skipped (will resolve on first run)"
  REG_CMD=(uv --directory "$ROOT/mcp" run sixthsense_mcp.py)
else
  warn "uv not found. Install it: curl -LsSf https://astral.sh/uv/install.sh | sh"
  warn "Falling back to python3 venv + pip."
  if [ ! -d "$ROOT/mcp/.venv" ]; then
    python3 -m venv "$ROOT/mcp/.venv"
  fi
  # shellcheck disable=SC1091
  source "$ROOT/mcp/.venv/bin/activate"
  pip install --quiet --upgrade pip
  pip install --quiet "mcp[cli]" httpx
  deactivate
  ok "venv ready at mcp/.venv"
  REG_CMD=("$ROOT/mcp/.venv/bin/python" "$ROOT/mcp/sixthsense_mcp.py")
fi

# --- Register with Claude Code (project scope) ---
echo
bold "Registering MCP server 'sixthsense' (project scope)"
claude mcp remove sixthsense >/dev/null 2>&1 || true
claude mcp add --transport stdio --scope project sixthsense -- "${REG_CMD[@]}"
ok "Registered. Verify with: claude mcp list   (and /mcp inside Claude Code)"

# --- Test prompts ---
echo
bold "Try these prompts in Claude Code:"
cat <<'EOF'
  Use the sixthsense MCP server to list adb devices.
  Use MCP to build the debug APK.
  Use MCP to install and launch the app.
  Use MCP to turn mock mode on and read logs.
  Use MCP to send a right belt test at intensity 200.
  Use MCP to ask the voice agent what's ahead of me.
  Use MCP to check dashboard status.
EOF
