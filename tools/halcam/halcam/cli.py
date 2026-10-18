"""Command-line entry point. Camera commands are never automatically replayed."""

import argparse
import json
import sys
import time
from . import __version__
from .adb import Adb
from .download import collect
from .protocol import CliError, TERMINAL, identifier, raise_app_error, validate_request
from .presentation import progress, render
from .submission import STREAM_KEYS, CONTROL_KEYS, PIP_KEYS, LABEL_KEYS, COMMAND_OPTION_KEYS, pairs, make_request, spec



class ArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        raise CliError("INVALID_ARGUMENT", message)


def common(parser, root=False):
    default = None if root else argparse.SUPPRESS
    parser.add_argument("--serial", default=default)
    parser.add_argument("--adb", default="adb" if root else argparse.SUPPRESS)
    parser.add_argument("--package", default="dev.halcamera" if root else argparse.SUPPRESS)
    parser.add_argument("--json", action="store_true", default=False if root else argparse.SUPPRESS)


def camera_options(p, streams=False, controls=False):
    p.add_argument("--camera", default="0")
    p.add_argument("--engine", choices=("Camera2", "CameraX"))
    for key in (STREAM_KEYS if streams else ()) + (CONTROL_KEYS + PIP_KEYS if controls else ()):
        p.add_argument("--" + key.replace("_", "-"))


def case_options(p, required=False):
    p.add_argument("--case", "--cases", action="append", dest="cases", required=required,
                   help="Suite key or comma-separated keys; repeat to select several")




def parser():
    root = ArgumentParser(prog="halcam", description="Control HALCamera through ADB", formatter_class=argparse.RawDescriptionHelpFormatter,
                          epilog="Start with one task:\n  1. Check setup: halcam doctor\n  2. Take a photo: halcam capture --camera 0 --output ./captures\n  3. Record: halcam record start --no-audio\n     Finish: halcam record stop --output ./videos\n  4. Read results: halcam run results.list\n  5. Find files: halcam run gallery.list\nLost a request ID: halcam requests, then halcam wait REQUEST_ID --output DIR\nUse --json for complete machine-readable results.")
    common(root, True)
    root.add_argument("--version", action="version", version=__version__)
    commands = root.add_subparsers(dest="command", required=True)
    for name in ("devices", "doctor", "launch", "cameras", "streams", "preview", "capture", "burst", "bracket", "probe", "status", "requests", "wait", "fetch", "cancel"):
        p = commands.add_parser(name)
        common(p)
        if name in ("preview", "capture", "burst", "bracket", "streams"):
            camera_options(p, name != "streams", name != "streams")
        if name in ("preview", "streams"):
            p.add_argument("--mode", choices=("Photo", "Video"))
        if name == "preview":
            p.add_argument("action", nargs="?", choices=("start", "stop"), default="start")
            p.set_defaults(camera=None)
        if name == "burst":
            p.add_argument("--count")
            p.add_argument("--interval-ms")
        if name in ("streams", "cameras", "preview", "capture", "burst", "bracket", "probe"):
            execution_options(p)
        if name == "status":
            p.add_argument("request_id", nargs="?")
            p.add_argument("--request", help="Legacy alias for the positional request ID")
            p.add_argument("--app", action="store_true", help="Read current app state (the default)")
        if name in ("wait", "fetch", "cancel"):
            p.add_argument("request")
        if name in ("capture", "burst", "bracket", "probe", "fetch"):
            p.add_argument("--output", required=True)
            p.add_argument("--transfer-timeout", type=positive, default=60)
        if name == "wait":
            # Recovery for --no-wait or an expired PC wait: download only when an output folder is given.
            p.add_argument("--output")
            p.add_argument("--transfer-timeout", type=positive, default=60)
            p.add_argument("--wait-timeout", type=positive, default=3600, help="PC waiting deadline; does not cancel app execution")
    cts = commands.add_parser("cts")
    common(cts)
    cts_commands = cts.add_subparsers(dest="cts_command", required=True)
    cases = cts_commands.add_parser("cases")
    common(cases)
    execution_options(cases)
    cts_run = cts_commands.add_parser("run")
    common(cts_run)
    case_options(cts_run, required=True)
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
    for key in LABEL_KEYS:
        benchmark_run.add_argument("--" + key)
    execution_options(benchmark_run, 600)
    record = commands.add_parser("record", help="Start, stop or snapshot a CLI recording")
    common(record)
    record_commands = record.add_subparsers(dest="record_command", required=True)
    record_start = record_commands.add_parser("start")
    common(record_start)
    camera_options(record_start, streams=True, controls=True)
    record_start.add_argument("--no-audio", action="store_true")
    execution_options(record_start, 3600)
    record_stop = record_commands.add_parser("stop")
    common(record_stop)
    record_stop.add_argument("--output")
    record_stop.add_argument("--transfer-timeout", type=positive, default=60)
    record_stop.add_argument("--wait-timeout", type=positive, default=60)
    common(record_commands.add_parser("snapshot"))
    multi = commands.add_parser("multi", help="List camera sets Multi can open together")
    common(multi)
    multi_commands = multi.add_subparsers(dest="multi_command", required=True)
    multi_cameras = multi_commands.add_parser("cameras")
    common(multi_cameras)
    execution_options(multi_cameras)
    pip = commands.add_parser("pip", help="Read PIP candidates for the current preview")
    common(pip)
    pip_commands = pip.add_subparsers(dest="pip_command", required=True)
    pip_sources = pip_commands.add_parser("sources")
    common(pip_sources)
    execution_options(pip_sources)
    control = commands.add_parser("control", help="Control the current preview or CLI recording")
    common(control)
    control.add_argument("operation", choices=("record.stop", "record.snapshot", "live.set", "live.reset"))
    control.add_argument("--option", action="append", default=[], metavar="KEY=VALUE")
    run = commands.add_parser("run", help="Execute any command listed by doctor (advanced JSON-compatible path)",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="Examples:\n  halcam run burst --option count=3 --output ./photos\n  halcam run results.list\n  halcam run record.start --no-audio\nList available operations: halcam doctor --json")
    common(run)
    run.add_argument("operation")
    camera_options(run, streams=True, controls=True)
    # Commands without a camera must not inherit the convenience default.
    run.set_defaults(camera=None)
    run.add_argument("--mode", choices=("Photo", "Video"))
    case_options(run)
    for key in COMMAND_OPTION_KEYS + LABEL_KEYS:
        run.add_argument("--" + key.replace("_", "-"))
    run.add_argument("--profile", choices=("camera2-standard-v2",))
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


def execution_options(p, timeout=None):
    p.add_argument("--request-id")
    p.add_argument("--no-wait", action="store_true")
    p.add_argument("--timeout", type=positive, default=timeout, help="App execution deadline in seconds")
    p.add_argument("--wait-timeout", type=positive, default=None, help="PC waiting deadline; does not cancel app execution")


def app_command(args):
    if args.command == "run":
        return args.operation
    if args.command == "benchmark":
        return "benchmark." + args.benchmark_command
    if args.command == "cts":
        return "cts." + args.cts_command
    if args.command == "record":
        return "record." + args.record_command
    if args.command == "pip":
        return "pip." + args.pip_command
    if args.command == "multi":
        return "multi." + args.multi_command
    if args.command == "preview" and args.action == "stop":
        return "preview.stop"
    return args.command


def request_status(adb, rid):
    data = adb.read(f"/v1/requests/{identifier(rid)}")
    if "state" not in data:
        raise_app_error(data)
    return validate_request(data, rid)


def request_list(adb):
    data = raise_app_error(adb.read("/v1/requests"))
    rows = data.get("requests")
    if not isinstance(rows, list) or any(not isinstance(row, dict) or not isinstance(row.get("request_id"), str)
                                         or not isinstance(row.get("state"), str) for row in rows):
        raise CliError("PROTOCOL_ERROR", "Invalid request list")
    return data


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
            raise CliError("WAIT_TIMEOUT", "PC wait expired; app may still be running. Use wait REQUEST_ID to keep waiting.", request_id=rid)
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
        options = pairs(args.option)
        payload = {"operation": args.operation}
        if options:
            payload["options"] = options
        return raise_app_error(adb.call("control", payload))
    if args.command == "record" and args.record_command in ("stop", "snapshot"):
        data = raise_app_error(adb.call("control", {"operation": app_command(args)}))
        if args.record_command == "snapshot":
            return data
        rid = identifier(data["request_id"])
        context["request_id"] = rid
        context["submitted"] = False
        validate_request(data, rid)
        return finish(adb, wait(adb, rid, args.wait_timeout), args)
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
        if (args.request and args.request_id) or (args.app and (args.request or args.request_id)):
            raise CliError("INVALID_ARGUMENT", "Use one request ID or --app")
        rid = args.request or args.request_id
        return request_status(adb, rid) if rid else raise_app_error(adb.read("/v1/status"))
    if args.command == "requests":
        return request_list(adb)
    if args.command == "wait":
        rid = identifier(args.request)
        context["request_id"] = rid
        # Waiting again owns nothing: Ctrl+C stops the PC wait and never cancels the app request.
        context["submitted"] = False
        report = None if args.json else lambda message: print(message, file=sys.stderr, flush=True)
        return finish(adb, wait(adb, rid, args.wait_timeout, report), args)
    if args.command in ("fetch", "cancel"):
        rid = identifier(args.request)
        if args.command == "fetch":
            data = request_status(adb, rid)
            if not data["completed"]:
                raise CliError("BUSY", "Request is not complete; use status before fetching", request_id=rid)
            return finish(adb, data, args)
        return raise_app_error(adb.call("cancel", {"protocol_version": 1, "request_id": rid}))

    payload = make_request(args, app_command(args), hello)
    rid = payload["request_id"]
    args.timeout = payload["execution_timeout_ms"] / 1000

    previous = None
    if args.request_id:
        try:
            previous = request_status(adb, rid)
        except CliError as error:
            if error.code != "REQUEST_NOT_FOUND":
                raise
    operation = app_command(args)
    # Screenless commands (queries, the library) need nothing brought to the foreground first.
    if previous is None and spec(hello, operation, "foreground"):
        status = raise_app_error(adb.read("/v1/status"))
        if status.get("busy"):
            raise CliError("BUSY", "An app operation is already running")
        target_screen = "dual" if status.get("screen") == "dual" and spec(hello, operation, "current_screen") else "live"
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
    context["submitted"] = True
    data = adb.call("submit", payload)
    if "state" not in data:
        if data.get("error"):
            context.pop("request_id", None)  # The app explicitly refused submission.
        raise_app_error(data)
    validate_request(data, rid)
    print(f"request_id={rid}", file=sys.stderr, flush=True)
    if args.no_wait:
        return data
    if spec(hello, operation, "recording"):
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
        if rid and context.get("submitted", True):
            try:
                context["adb"].call("cancel", {"protocol_version": 1, "request_id": rid})
            except (CliError, KeyboardInterrupt):
                pass
        message = "Stopped waiting; saving may continue. Query request status." if context.get("submitted") is False else "Interrupted; query request status to confirm cancellation"
        data = CliError("CANCELLED", message, request_id=rid).as_dict()
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
