"""Short human output; --json remains the complete automation contract."""

import json
import os
import shlex
import subprocess


def command(args, *parts):
    words = ["halcam"]
    for key in ("serial", "adb", "package"):
        value = getattr(args, key, None)
        if value and value != {"adb": "adb", "package": "dev.halcamera"}.get(key):
            words.extend(("--" + key, value))
    words.extend(parts)
    return subprocess.list2cmdline(words) if os.name == "nt" else shlex.join(words)


def progress(data):
    detail = data.get("progress") or {}
    state = data.get("state", "waiting")
    if "saved" in detail and "total" in detail:
        phase = "" if data.get("completed") else f" ({detail.get('phase', 'capture')})"
        return f"{state}: {detail['saved']}/{detail['total']} photos saved{phase}"
    return state


def render(data, args):
    error = data.get("error")
    result = data.get("result") or {}
    rid = data.get("request_id")
    stopping = getattr(args, "operation", None) == "record.stop" and not data.get("completed")
    snapshot = getattr(args, "operation", None) == "record.snapshot"
    lines = []
    if error:
        lines.append(f"{error['code']}: {error.get('message', '')}")
    elif stopping:
        lines.append("Stop requested. The video is still being saved.")
    elif snapshot:
        lines.append("Snapshot requested. Check status for snapshot_count or snapshot_error.")
    elif result.get("recording"):
        lines.append("Recording started. Stop to save the video.")
    elif data.get("busy"):
        lines.append("Busy. An app operation is running.")
    elif "applied" in result:
        lines.append("Retention limit applied." if result["applied"] else "Preview only. Nothing has changed.")
    else:
        lines.append(progress(data) if data.get("state") else "Ready.")
    if rid:
        lines.append(f"Request: {rid}")
    files = data.get("downloaded_files") or []
    artifacts = data.get("artifacts") or []
    if files:
        lines.append(f"Saved {len(files)} verified file(s):")
        lines.extend(str(path) for path in files)
    elif artifacts:
        lines.append(f"{len(artifacts)} file(s) available, including any saved before cancellation.")
    # Lists and summaries are the requested result, so keep every entry visible.
    for key in ("summary", "comparison", "runs", "media", "incidents", "cameras", "cases", "streams", "devices", "delete_runs"):
        value = result.get(key, data.get(key))
        if value is not None:
            lines.append(f"{key}:")
            if isinstance(value, list):
                lines.extend(json.dumps(row, ensure_ascii=False) for row in value)
                if not value:
                    lines.append("(none)")
            else:
                lines.append(value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, indent=2))
    for key in ("deleted", "baseline", "run_limit", "applied", "camera_ready", "zoom", "snapshot_pending", "snapshot_count", "snapshot_error"):
        if key in result or key in data:
            lines.append(f"{key}: {result.get(key, data.get(key))}")
    if error and error["code"] in ("DEVICE_NOT_FOUND", "DEVICE_UNAUTHORIZED", "MULTIPLE_DEVICES", "ADB_NOT_FOUND"):
        next_step = command(args, "devices") if error["code"] != "ADB_NOT_FOUND" else "Install Android Platform Tools, then run halcam doctor."
    elif error and error["code"] == "CLI_DISABLED":
        next_step = "Open HALCamera > Lab > ADB CLI and enable access."
    elif error and error["code"] in ("REQUEST_NOT_FOUND", "ARTIFACT_EXPIRED"):
        next_step = command(args, "run", "gallery.list") + " (saved photos/videos may still be available; do not repeat capture)"
    elif (stopping or snapshot) and rid:
        next_step = command(args, "status", "--request", rid)
    elif result.get("recording"):
        next_step = command(args, "control", "record.stop")
    elif artifacts and not files and rid:
        next_step = command(args, "fetch", rid, "--output", "./captures")
    elif error and error["code"] == "BUSY":
        next_step = command(args, "status")
    elif rid and not data.get("completed"):
        next_step = command(args, "status", "--request", rid)
    elif error and error["code"] == "CONFIRM_REQUIRED":
        next_step = "Check the listed ID, then repeat the delete command with --option confirm=true."
    elif error and error["code"] == "INVALID_ARGUMENT":
        next_step = command(args, "--help")
    elif error and error["code"] == "PREFLIGHT_FAILED":
        parts = ["streams", "--camera", getattr(args, "camera", None) or "0"]
        if getattr(args, "engine", None):
            parts.extend(("--engine", args.engine))
        next_step = command(args, *parts)
    elif error and error["code"] == "DEVICE_LOCKED":
        next_step = "Unlock the device, then run " + command(args, "doctor")
    elif error and error["code"] == "PERMISSION_REQUIRED":
        next_step = "Allow the named permission in HALCamera. For silent recording, use --no-audio."
    elif "applied" in result and not result["applied"]:
        next_step = command(args, "run", "settings.limit", "--option", f"limit={result['run_limit']}", "--option", "confirm=true")
    elif error:
        next_step = command(args, "doctor")
    else:
        next_step = None
    if next_step:
        lines.append("Next: " + next_step)
    if not error and not result and "commands" in data:
        lines.append(f"Connected. {len(data['commands'])} commands available. Use --json to list capabilities.")
    elif result and not any(key in result for key in ("summary", "comparison", "runs", "media", "incidents", "cameras", "cases", "streams")):
        lines.append("Use --json for complete result details.")
    return "\n".join(lines)
