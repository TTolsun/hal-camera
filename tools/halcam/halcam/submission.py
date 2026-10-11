"""Normalize both friendly and generic commands into one app submission contract."""

import uuid
from .protocol import CliError, identifier

STREAM_KEYS = ("preview_size", "yuv_size", "jpeg_size", "raw_size", "fps", "stabilization", "video_size", "video_fps", "codec", "pip_preview_size", "pip_photo_size")
CONTROL_KEYS = ("zoom", "ev", "flash", "ae_lock", "af_lock", "iso", "exposure_ns", "focus", "wb", "gains", "matrix")
LABEL_KEYS = ("build", "commit", "branch", "note")
PIP_KEYS = ("pip_type", "pip_camera", "pip_x", "pip_y", "pip_width", "pip_height")


def pairs(items, initial=None):
    values = dict(initial or {})
    for item in items:
        key, sep, value = item.partition("=")
        if not sep or not key or not value or key in values:
            raise CliError("INVALID_ARGUMENT", "Use each option once; expected KEY=VALUE")
        values[key] = value
    return values


# Compatibility defaults for APKs predating command_specs. New APKs advertise their contract.
CAMERA_COMMANDS = {"preview", "capture", "burst", "bracket", "record.start", "streams", "dual.preview", "dual.capture", "dual.record", "benchmark.run"}
DEFAULT_SECONDS = {"record.start": 3600, "dual.record": 3600, "cts.run": 1800, "benchmark.run": 600}
OPTION_KEYS = CONTROL_KEYS + PIP_KEYS + LABEL_KEYS + ("count", "interval_ms", "x", "y", "meter", "run", "reference", "media", "confirm", "first", "second", "incident", "limit")


def make_request(args, operation, hello):
    spec = hello.get("command_specs", {}).get(operation)
    if args.command == "run" and operation not in hello.get("commands", []):
        raise CliError("INVALID_ARGUMENT", "This APK does not list that command; run doctor")
    timeout = args.timeout
    if timeout is None:
        timeout = spec["default_timeout_ms"] / 1000 if spec else DEFAULT_SECONDS.get(operation, 30)
    rid = identifier(args.request_id) if args.request_id else str(uuid.uuid4())
    payload = {"protocol_version": 1, "request_id": rid, "command": operation,
               "params": {}, "execution_timeout_ms": int(timeout * 1000)}
    params = payload["params"]
    camera = getattr(args, "camera", None)
    if camera is not None:
        params["camera_id"] = camera
    if spec.get("camera") if spec else operation in CAMERA_COMMANDS:
        params.setdefault("camera_id", "0")
    if getattr(args, "engine", None):
        params["engine"] = args.engine
    if getattr(args, "mode", None):
        if operation not in ("preview", "streams"):
            raise CliError("INVALID_ARGUMENT", "--mode is only for preview or streams")
        params["mode"] = args.mode
    streams = {key: getattr(args, key) for key in STREAM_KEYS if getattr(args, key, None) is not None}
    options = {key: getattr(args, key) for key in OPTION_KEYS if getattr(args, key, None) is not None}
    streams = pairs(getattr(args, "stream", []), streams)
    options = pairs(getattr(args, "option", []), options)
    if streams:
        params["streams"] = streams
    if options:
        params["options"] = options
    profile = getattr(args, "profile", None)
    if profile is not None and operation != "benchmark.run":
        raise CliError("INVALID_ARGUMENT", "--profile is only for benchmark.run")
    if profile is not None or operation == "benchmark.run":
        params["profile_id"] = profile or "camera2-standard-v2"
    if getattr(args, "no_audio", False):
        if operation not in ("record.start", "dual.record"):
            raise CliError("INVALID_ARGUMENT", "--no-audio requires record.start or dual.record")
        params["audio"] = False
    if getattr(args, "cases", None):
        if operation != "cts.run":
            raise CliError("INVALID_ARGUMENT", "--cases is only for cts.run")
        cases = [key for item in args.cases for key in item.split(",")]
        if len(cases) > 64 or len(set(cases)) != len(cases) or any(not key.startswith(("custom:", "vendored:")) or not 1 <= len(key) <= 256 for key in cases):
            raise CliError("INVALID_ARGUMENT", "Use up to 64 distinct suite keys from cts cases")
        params["cases"] = cases
    if operation == "cts.run" and not params.get("cases"):
        raise CliError("INVALID_ARGUMENT", "cts.run requires --cases KEY[,KEY...]")
    if spec:
        for field in ("camera", "engine", "streams", "audio"):
            key = "camera_id" if field == "camera" else field
            if key in params and not spec[field]:
                raise CliError("INVALID_ARGUMENT", f"{operation} does not accept {field}")
        if options.keys() - set(spec["options"]):
            raise CliError("INVALID_ARGUMENT", f"Unexpected option for {operation}")
        missing = set(spec["required_options"]) - options.keys()
        if missing:
            raise CliError("INVALID_ARGUMENT", f"{operation} requires {', '.join(sorted(missing))}")
    return payload
