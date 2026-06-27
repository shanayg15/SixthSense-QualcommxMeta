#!/usr/bin/env python3
"""SixthSense MCP server — a development & debugging command center.

This is a local stdio MCP server (FastMCP) used ONLY for development automation:
building, installing, launching, log inspection, debug broadcasts, dashboard
checks, and Qualcomm AI Hub status. It is NEVER part of the live assistive
runtime — it touches the phone only through `adb` during development.

IMPORTANT (MCP protocol): stdout is reserved for the protocol. Never print normal
logs to stdout. Use stderr (see `_log`) for any diagnostics.
"""

from __future__ import annotations

import glob
import os
import shlex
import subprocess
import sys
from pathlib import Path
from typing import Optional

from mcp.server.fastmcp import FastMCP

# --------------------------------------------------------------------------- #
# Configuration via environment variables (all optional, with safe defaults).
# --------------------------------------------------------------------------- #
#   SIXTHSENSE_ROOT     repo root (defaults to the parent of this file's dir)
#   ANDROID_HOME        Android SDK location (for locating adb)
#   SIXTHSENSE_PACKAGE  Android application id (default: com.sixthsense)
#   DASHBOARD_URL       dashboard URL for status checks
# --------------------------------------------------------------------------- #

DEFAULT_PACKAGE = "com.sixthsense"
DEFAULT_DASHBOARD_URL = "http://127.0.0.1:5173"
DEFAULT_TIMEOUT = 120  # seconds

mcp = FastMCP("sixthsense")


def _log(msg: str) -> None:
    """Diagnostics go to stderr only — stdout is the MCP transport."""
    print(f"[sixthsense-mcp] {msg}", file=sys.stderr, flush=True)


def _repo_root() -> Path:
    env = os.environ.get("SIXTHSENSE_ROOT")
    if env:
        return Path(env).expanduser().resolve()
    # This file lives at <root>/mcp/sixthsense_mcp.py
    return Path(__file__).resolve().parent.parent


def _android_dir() -> Path:
    return _repo_root() / "android"


def _adb_path() -> str:
    """Resolve an adb executable.

    Order: ANDROID_HOME/platform-tools, the macOS default SDK location, then a
    bare `adb` on PATH.
    """
    candidates = []
    android_home = os.environ.get("ANDROID_HOME")
    if android_home:
        candidates.append(Path(android_home) / "platform-tools" / "adb")
    candidates.append(Path.home() / "Library" / "Android" / "sdk" / "platform-tools" / "adb")
    for c in candidates:
        if c.exists():
            return str(c)
    return "adb"  # fall back to PATH; _run will report if it's missing


def _package() -> str:
    return os.environ.get("SIXTHSENSE_PACKAGE", DEFAULT_PACKAGE)


def _run(cmd: list[str], cwd: Optional[Path] = None, timeout: int = DEFAULT_TIMEOUT) -> dict:
    """Run a subprocess safely (no shell), returning stdout/stderr/exit code."""
    printable = " ".join(shlex.quote(c) for c in cmd)
    _log(f"run: {printable}" + (f" (cwd={cwd})" if cwd else ""))
    try:
        proc = subprocess.run(
            cmd,
            cwd=str(cwd) if cwd else None,
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
        return {
            "command": printable,
            "exit_code": proc.returncode,
            "stdout": proc.stdout,
            "stderr": proc.stderr,
        }
    except FileNotFoundError as e:
        return {
            "command": printable,
            "exit_code": 127,
            "stdout": "",
            "stderr": f"Executable not found: {e}. Is it installed and on PATH? "
            "For adb, set ANDROID_HOME or install Android SDK Platform Tools.",
        }
    except subprocess.TimeoutExpired:
        return {
            "command": printable,
            "exit_code": 124,
            "stdout": "",
            "stderr": f"Command timed out after {timeout}s.",
        }


def _clamp(value: int, low: int, high: int) -> int:
    return max(low, min(high, int(value)))


# --------------------------------------------------------------------------- #
# Tools
# --------------------------------------------------------------------------- #


@mcp.tool()
def adb_devices() -> dict:
    """List connected Android devices (`adb devices -l`)."""
    return _run([_adb_path(), "devices", "-l"])


@mcp.tool()
def gradle_build(task: str = "assembleDebug") -> dict:
    """Build the Android app from the `android/` directory.

    Uses `./gradlew` if present, else a system `gradle`, else returns
    instructions to open the project in Android Studio (which generates the
    Gradle wrapper on first sync).
    """
    android = _android_dir()
    if not android.exists():
        return {"exit_code": 1, "stdout": "", "stderr": f"Android dir not found: {android}"}

    gradlew = android / "gradlew"
    if gradlew.exists():
        return _run([str(gradlew), task], cwd=android, timeout=600)

    from shutil import which

    if which("gradle"):
        return _run(["gradle", task], cwd=android, timeout=600)

    return {
        "exit_code": 1,
        "stdout": "",
        "stderr": (
            "No Gradle wrapper (android/gradlew) and no system `gradle` found.\n"
            "Open the `android/` folder in Android Studio once — it will sync and "
            "generate the Gradle wrapper. After that, `gradle_build` will use "
            "`./gradlew`. Alternatively install Gradle (`brew install gradle`) and "
            "run `gradle wrapper` inside android/."
        ),
    }


@mcp.tool()
def install_debug_apk(apk_path: Optional[str] = None) -> dict:
    """Install the debug APK with `adb install -r`.

    If apk_path is not given, searches the standard Android output locations.
    """
    if apk_path is None:
        android = _android_dir()
        primary = android / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
        if primary.exists():
            apk_path = str(primary)
        else:
            matches = glob.glob(
                str(android / "**" / "build" / "outputs" / "apk" / "debug" / "*debug*.apk"),
                recursive=True,
            )
            if matches:
                apk_path = matches[0]
    if not apk_path or not Path(apk_path).exists():
        return {
            "exit_code": 1,
            "stdout": "",
            "stderr": (
                "No debug APK found. Build it first (gradle_build) or pass apk_path. "
                "Expected at android/app/build/outputs/apk/debug/app-debug.apk."
            ),
        }
    return _run([_adb_path(), "install", "-r", apk_path], timeout=300)


@mcp.tool()
def launch_app(package_name: Optional[str] = None) -> dict:
    """Launch the app via the LAUNCHER intent (monkey)."""
    pkg = package_name or _package()
    return _run(
        [_adb_path(), "shell", "monkey", "-p", pkg, "-c",
         "android.intent.category.LAUNCHER", "1"]
    )


@mcp.tool()
def adb_logcat(lines: int = 250) -> dict:
    """Dump recent filtered logcat lines.

    Filters: SixthSenseScene:I SixthSenseMCP:I AndroidRuntime:E *:S
    """
    n = _clamp(lines, 1, 5000)
    return _run([
        _adb_path(), "logcat", "-d", "-t", str(n),
        "SixthSenseScene:I", "SixthSenseMCP:I", "AndroidRuntime:E", "*:S",
    ])


@mcp.tool()
def clear_logcat() -> dict:
    """Clear the device log buffer (`adb logcat -c`)."""
    return _run([_adb_path(), "logcat", "-c"])


@mcp.tool()
def belt_test(left: int = 0, center: int = 0, right: int = 200, pattern: int = 0) -> dict:
    """DEBUG-ONLY: send a belt test broadcast (com.sixthsense.DEBUG_BELT).

    Intensities clamped to 0-255, pattern to 0-2. Only do this when explicitly asked.
    """
    l = _clamp(left, 0, 255)
    c = _clamp(center, 0, 255)
    r = _clamp(right, 0, 255)
    p = _clamp(pattern, 0, 2)
    return _run([
        _adb_path(), "shell", "am", "broadcast",
        "-a", "com.sixthsense.DEBUG_BELT",
        "--ei", "l", str(l),
        "--ei", "c", str(c),
        "--ei", "r", str(r),
        "--ei", "p", str(p),
    ])


@mcp.tool()
def set_mock_mode(enabled: bool = True) -> dict:
    """DEBUG-ONLY: toggle mock scene mode (com.sixthsense.DEBUG_MOCK)."""
    return _run([
        _adb_path(), "shell", "am", "broadcast",
        "-a", "com.sixthsense.DEBUG_MOCK",
        "--ez", "enabled", "true" if enabled else "false",
    ])


@mcp.tool()
def ask_voice_agent(question: str = "what's ahead of me?") -> dict:
    """DEBUG-ONLY: ask the on-device voice agent (com.sixthsense.DEBUG_ASK)."""
    return _run([
        _adb_path(), "shell", "am", "broadcast",
        "-a", "com.sixthsense.DEBUG_ASK",
        "--es", "q", question,
    ])


@mcp.tool()
def dashboard_status(url: Optional[str] = None) -> dict:
    """Check whether the local dashboard dev server is responding."""
    target = url or os.environ.get("DASHBOARD_URL", DEFAULT_DASHBOARD_URL)
    try:
        import httpx

        resp = httpx.get(target, timeout=5.0)
        return {
            "exit_code": 0,
            "url": target,
            "status_code": resp.status_code,
            "responding": True,
            "stderr": "",
        }
    except Exception as e:  # noqa: BLE001 - report any connection failure plainly
        return {
            "exit_code": 1,
            "url": target,
            "responding": False,
            "stderr": f"Dashboard not responding at {target}: {e}. "
            "Start it with start_dashboard() / `npm run dev`.",
        }


@mcp.tool()
def start_dashboard() -> dict:
    """Return the command to run the dashboard (does not start a hanging server)."""
    dash = _repo_root() / "dashboard"
    if not dash.exists():
        return {
            "exit_code": 1,
            "stderr": f"Dashboard directory not found at {dash}.",
        }
    return {
        "exit_code": 0,
        "stderr": "",
        "note": "Run this in a separate terminal (not inside MCP):",
        "command": "cd dashboard && npm install && npm run dev -- --host 127.0.0.1",
    }


@mcp.tool()
def qaihub_status() -> dict:
    """Check Qualcomm AI Hub CLI availability and recent jobs."""
    from shutil import which

    if not which("qai-hub"):
        return {
            "exit_code": 1,
            "stderr": (
                "`qai-hub` CLI not found. Install with `pip install qai-hub` and "
                "configure your token (see docs/model_export_plan.md). Qualcomm AI "
                "Hub is the recommended path for QNN .pte export from macOS."
            ),
        }
    return _run(["qai-hub", "list-jobs"], timeout=60)


if __name__ == "__main__":
    _log(f"starting sixthsense MCP (root={_repo_root()}, adb={_adb_path()})")
    mcp.run()
