"""Command-line entry point. Camera commands are never automatically replayed."""

import argparse
import json
import sys
import time
import uuid
from . import __version__
from .adb import Adb
from .download import collect
from .protocol import CliError, TERMINAL, identifier, raise_app_error, validate_request
from .presentation import progress, render


class ArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        raise CliError("INVALID_ARGUMENT", message)


def common(parser, root=False):
    default = None if root else argparse.SUPPRESS
    parser.add_argument("--serial", default=default)
    parser.add_argument("--adb", default="adb" if root else argparse.SUPPRESS)
    parser.add_argument("--package", default="dev.halcamera" if root else argparse.SUPPRESS)
    parser.add_argument("--json", action="store_true", default=False if root else argparse.SUPPRESS)


def parser():
    root = ArgumentParser(prog="halcam", description="Control HALCamera through ADB", formatter_class=argparse.RawDescriptionHelpFormatter,
                          epilog="Start with one task:\n  1. Check setup: halcam doctor\n  2. Take a photo: halcam capture --camera 0 --output ./captures\n  3. Record: halcam run record.start --no-audio\n     Finish: halcam control record.stop\n  4. Read results: halcam run results.list\n  5. Find files: halcam run gallery.list\nUse --json for complete machine-readable results.")
    common(root, True)
    root.add_argument("--version", action="version", version=__version__)
    commands = root.add_subparsers(dest="command", required=True)
    for name in ("devices", "doctor", "launch", "cameras", "streams", "preview", "capture", "probe", "status", "fetch", "cancel"):
        p = commands.add_parser(name)
        common(p)
        if name in ("preview", "capture", "streams"):
            p.add_argument("--camera", required=True)
            p.add_argument("--engine", choices=("Camera2", "CameraX"))
        if name in ("preview", "capture"):
            for option in ("preview-size", "yuv-size", "jpeg-size", "video-size", "video-fps", "codec"):
                p.add_argument("--" + option)
        if name in ("streams", "cameras", "preview", "capture", "probe"):
            execution_options(p)
        if name == "status":
            p.add_argument("--request")
        if name in ("fetch", "cancel"):
            p.add_argument("request")
        if name in ("capture", "probe", "fetch"):
            p.add_argument("--output", required=True)
            p.add_argument("--transfer-timeout", type=positive, default=60)
    cts = commands.add_parser("cts")
    common(cts)
    cts_commands = cts.add_subparsers(dest="cts_command", required=True)
    cases = cts_commands.add_parser("cases")
    common(cases)
    execution_options(cases)
    cts_run = cts_commands.add_parser("run")
    common(cts_run)
    cts_run.add_argument("--case", action="append", required=True, dest="cases",
                         help="Suite key from 'cts cases' (custom:… or vendored:…); repeat for several, run in checklist order")
    cts_run.add_argument("--output", required=True)
    cts_run.add_argument("--transfer-timeout", type=positive, default=60)
    execution_options(cts_run, 1800)
    benchmark = commands.add_parser("benchmark")
    common(benchmark)
    benchmark_commands = benchmark.add_subparsers(dest="benchmark_command", required=True)
    benchmark_run = benchmark_commands.add_parser("run")
    common(benchmark_run)
    benchmark_run.add_argument("--camera", default="0")
    benchmark_run.add_argument("--profile", choices=("camera2-standard-v2",), default="camera2-standard-v2")
    benchmark_run.add_argument("--output", required=True)
    benchmark_run.add_argument("--transfer-timeout", type=positive, default=60)
    execution_options(benchmark_run, 600)
    control = commands.add_parser("control", help="Control the current preview or CLI recording")
    common(control)
    control.add_argument("operation", choices=("record.stop", "record.snapshot", "live.set", "live.reset"))
    control.add_argument("--option", action="append", default=[], metavar="KEY=VALUE")
    run = commands.add_parser("run", help="Execute any command listed by doctor (advanced JSON-compatible path)",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="Examples:\n  halcam run burst --option count=3 --output ./photos\n  halcam run results.list\n  halcam run record.start --no-audio\nList available operations: halcam doctor --json")
    common(run)
    run.add_argument("operation")
    run.add_argument("--camera")
    run.add_argument("--engine", choices=("Camera2", "CameraX"))
    run.add_argument("--no-audio", action="store_true", help="Silent recording (record.start or dual.record)")
    run.add_argument("--option", action="append", default=[], metavar="KEY=VALUE")
    run.add_argument("--stream", action="append", default=[], metavar="KEY=VALUE")
    run.add_argument("--output")
    run.add_argument("--transfer-timeout", type=positive, default=60)
    execution_options(run, None)
    return root


def positive(value):
    number = float(value)
    if not 0 < number <= 3600:
        raise argparse.ArgumentTypeError("Must be greater than zero and at most 3600 seconds")
    return number


def execution_options(p, timeout=30):
    p.add_argument("--request-id")
    p.add_argument("--no-wait", action="store_true")
    p.add_argument("--timeout", type=positive, default=timeout, help="App execution deadline in seconds")
    p.add_argument("--wait-timeout", type=positive, default=None, help="PC waiting deadline; does not cancel app execution")


# Commands the app answers without a screen: nothing to bring to the foreground first.
SCREENLESS = ("streams", "cameras", "probe", "cts.cases")


def app_command(args):
    if args.command == "run":
        return args.operation
    if args.command == "benchmark":
        return "benchmark." + args.benchmark_command
    if args.command == "cts":
        return "cts." + args.cts_command
    return args.command


def request_status(adb, rid):
    data = adb.read(f"/v1/requests/{identifier(rid)}")
    if "state" not in data:
        raise_app_error(data)
    return validate_request(data, rid)


def wait(adb, rid, seconds, report=None):
    deadline = time.monotonic() + seconds
    previous = None
    while True:
        data = request_status(adb, rid)
        current = progress(data)
        if report and current != previous:
            report(current)
        previous = current
        if data["state"] in TERMINAL:
            return data
        if time.monotonic() >= deadline:
            raise CliError("WAIT_TIMEOUT", "PC wait expired; app may still be running. Use status --request and fetch.", request_id=rid)
        time.sleep(min(2, max(0, deadline - time.monotonic())))


def finish(adb, data, args):
    if getattr(args, "output", None) and data.get("artifacts"):
        data["downloaded_files"] = collect(adb, data, args.output, args.transfer_timeout)
    return data


def execute(args, context):
    adb = Adb(args.adb, args.serial, args.package)
    context["adb"] = adb
    if args.command == "devices":
        return {"protocol_version": 1, "devices": adb.devices(), "completed": True}
    adb.select().installed()
    if args.command == "launch":
        # Opening LIVE must not abort an operation that is already active.
        status = adb.read("/v1/status")
        if status.get("busy"):
            raise CliError("BUSY", "An app operation is already running")
        adb.launch()
        return {"protocol_version": 1, "completed": True, "camera_ready": False}
    hello = adb.hello()
    if args.command == "control":
        options = {}
        for item in args.option:
            key, sep, value = item.partition("=")
            if not sep or not key or not value or key in options:
                raise CliError("INVALID_ARGUMENT", "Use each KEY=VALUE once")
            options[key] = value
        payload = {"operation": args.operation}
        if options:
            payload["options"] = options
        return raise_app_error(adb.call("control", payload))
    if args.command == "doctor":
        status = raise_app_error(adb.read("/v1/status"))
        problem = ("DEVICE_LOCKED", "Unlock the device before using the camera") if hello.get("locked") or status.get("locked") else (
            ("PERMISSION_REQUIRED", "Allow camera access in HALCamera") if hello.get("camera_permission") is not True else (
                ("BUSY", "An app operation is running; wait for it to finish") if status.get("busy") else None))
        hello = dict(hello, ready=problem is None, busy=bool(status.get("busy")))
        if problem:
            hello["error"] = {"code": problem[0], "message": problem[1]}
        return hello
    if args.command == "status":
        return request_status(adb, args.request) if args.request else raise_app_error(adb.read("/v1/status"))
    if args.command in ("fetch", "cancel"):
        rid = identifier(args.request)
        if args.command == "fetch":
            data = request_status(adb, rid)
            if not data["completed"]:
                raise CliError("BUSY", "Request is not complete; use status before fetching", request_id=rid)
            return finish(adb, data, args)
        return raise_app_error(adb.call("cancel", {"protocol_version": 1, "request_id": rid}))

    if args.timeout is None:
        args.timeout = {"record.start": 3600, "dual.record": 3600, "cts.run": 1800, "benchmark.run": 600}.get(app_command(args), 30)
    rid = identifier(args.request_id) if args.request_id else str(uuid.uuid4())
    payload = {"protocol_version": 1, "request_id": rid, "command": app_command(args),
               "params": {}, "execution_timeout_ms": int(args.timeout * 1000)}
    if getattr(args, "camera", None) is not None:
        payload["params"]["camera_id"] = args.camera
    if getattr(args, "engine", None):
        payload["params"]["engine"] = args.engine
    streams = {key: getattr(args, key) for key in ("preview_size", "yuv_size", "jpeg_size", "video_size", "video_fps", "codec")
               if getattr(args, key, None) is not None}
    if streams:
        payload["params"]["streams"] = streams
    if hasattr(args, "profile"):
        payload["params"]["profile_id"] = args.profile
    if hasattr(args, "cases"):
        payload["params"]["cases"] = list(args.cases)

    if args.command == "run":
        if args.no_audio:
            if args.operation not in ("record.start", "dual.record"):
                raise CliError("INVALID_ARGUMENT", "--no-audio requires record.start or dual.record")
            payload["params"]["audio"] = False
        def pairs(items):
            values = {}
            for item in items:
                key, sep, value = item.partition("=")
                if not sep or not key or not value or key in values:
                    raise CliError("INVALID_ARGUMENT", "Use each KEY=VALUE once")
                values[key] = value
            return values
        if args.option:
            payload["params"]["options"] = pairs(args.option)
        if args.stream:
            payload["params"]["streams"] = pairs(args.stream)
        camera_commands = {"preview", "capture", "burst", "bracket", "record.start", "streams", "dual.preview", "dual.capture", "dual.record", "benchmark.run"}
        if args.operation in camera_commands:
            payload["params"].setdefault("camera_id", "0")
        if args.operation == "benchmark.run":
            payload["params"]["profile_id"] = "camera2-standard-v2"
        if args.operation not in hello.get("commands", []):
            raise CliError("INVALID_ARGUMENT", "This APK does not list that command; run doctor")

    previous = None
    if args.request_id:
        try:
            previous = request_status(adb, rid)
        except CliError as error:
            if error.code != "REQUEST_NOT_FOUND":
                raise
    if previous is None and app_command(args) not in SCREENLESS and not app_command(args).startswith(("results.", "baseline.", "gallery.", "settings.", "incidents.")) and app_command(args) != "dual.cameras":
        status = raise_app_error(adb.read("/v1/status"))
        if status.get("busy"):
            raise CliError("BUSY", "An app operation is already running")
        target_screen = "dual" if status.get("screen") == "dual" and app_command(args) in ("live.info", "events", "meter", "preview.stop") else "live"
        if not status.get("foreground") or status.get("screen") != target_screen:
            adb.launch()
            deadline = time.monotonic() + 10
            while True:
                status = raise_app_error(adb.read("/v1/status"))
                if status.get("foreground") and status.get("screen") == target_screen:
                    break
                if time.monotonic() >= deadline:
                    raise CliError("APP_NOT_FOREGROUND", "Unlock device and open HALCamera")
                time.sleep(0.25)
    # Reusing an ID still submits its payload once, so the app can detect conflicts.
    context["request_id"] = rid
    data = adb.call("submit", payload)
    if "state" not in data:
        if data.get("error"):
            context.pop("request_id", None)  # The app explicitly refused submission.
        raise_app_error(data)
    validate_request(data, rid)
    print(f"request_id={rid}", file=sys.stderr, flush=True)
    if args.no_wait:
        return data
    if app_command(args) in ("record.start", "dual.record"):
        deadline = time.monotonic() + (args.wait_timeout or 45)
        while not data.get("completed") and not (data.get("result") or {}).get("recording"):
            if time.monotonic() >= deadline:
                raise CliError("WAIT_TIMEOUT", "Recording may still be preparing; use status", request_id=rid)
            time.sleep(0.25)
            data = request_status(adb, rid)
        return data
    report = None if args.json else lambda message: print(message, file=sys.stderr, flush=True)
    data = wait(adb, rid, args.wait_timeout or (args.timeout + 15), report)
    return finish(adb, data, args)


def main(argv=None):
    raw_args = sys.argv[1:] if argv is None else argv
    if not raw_args:
        print(parser().epilog)
        print("All commands and options: halcam --help")
        return 0
    args = argparse.Namespace(json="--json" in raw_args)
    context = {}
    try:
        args = parser().parse_args(raw_args)
        data = execute(args, context)
        error = data.get("error")
        code = CliError(error["code"], error.get("message", "")).exit_code if error else 0
        if data.get("state") == "cancelled":
            code = 130
    except KeyboardInterrupt:
        rid = context.get("request_id")
        if rid:
            try:
                context["adb"].call("cancel", {"protocol_version": 1, "request_id": rid})
            except (CliError, KeyboardInterrupt):
                pass
        data = CliError("CANCELLED", "Interrupted; query request status to confirm cancellation", request_id=rid).as_dict()
        code = 130
    except CliError as error:
        error.request_id = error.request_id or context.get("request_id")
        data, code = error.as_dict(), error.exit_code
    except OSError as error:
        failure = CliError("DOWNLOAD_FAILED", str(error), request_id=context.get("request_id"))
        data, code = failure.as_dict(), failure.exit_code
    if args.json:
        print(json.dumps(data, ensure_ascii=False, separators=(",", ":")))
    else:
        print(render(data, args))
    return code
