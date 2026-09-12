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
    root = ArgumentParser(prog="halcam", description="Control HALCamera through ADB")
    common(root, True)
    root.add_argument("--version", action="version", version=__version__)
    commands = root.add_subparsers(dest="command", required=True)
    for name in ("devices", "doctor", "launch", "cameras", "preview", "capture", "status", "fetch", "cancel"):
        p = commands.add_parser(name)
        common(p)
        if name in ("preview", "capture"):
            p.add_argument("--camera", required=True)
        if name in ("cameras", "preview", "capture"):
            execution_options(p)
        if name == "status":
            p.add_argument("--request")
        if name in ("fetch", "cancel"):
            p.add_argument("request")
        if name in ("capture", "fetch"):
            p.add_argument("--output", required=True)
            p.add_argument("--transfer-timeout", type=positive, default=60)
    benchmark = commands.add_parser("benchmark")
    common(benchmark)
    run = benchmark.add_subparsers(dest="benchmark_command", required=True).add_parser("run")
    common(run)
    run.add_argument("--camera", required=True)
    run.add_argument("--profile", default="camera2-standard-v1")
    run.add_argument("--output", required=True)
    run.add_argument("--transfer-timeout", type=positive, default=60)
    execution_options(run, 180)
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


def request_status(adb, rid):
    data = adb.read(f"/v1/requests/{identifier(rid)}")
    if "state" not in data:
        raise_app_error(data)
    return validate_request(data, rid)


def wait(adb, rid, seconds):
    deadline = time.monotonic() + seconds
    while True:
        data = request_status(adb, rid)
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
        # Opening LIVE must not abort a benchmark that is already active.
        status = adb.read("/v1/status")
        if status.get("busy"):
            raise CliError("BUSY", "An app operation is already running")
        adb.launch()
        return {"protocol_version": 1, "completed": True, "camera_ready": False}
    hello = adb.hello()
    if args.command == "doctor":
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

    rid = identifier(args.request_id) if args.request_id else str(uuid.uuid4())
    context["request_id"] = rid
    print(f"request_id={rid}", file=sys.stderr, flush=True)
    payload = {"protocol_version": 1, "request_id": rid,
               "command": "benchmark.run" if args.command == "benchmark" else args.command,
               "params": {}, "execution_timeout_ms": int(args.timeout * 1000)}
    if hasattr(args, "camera"):
        payload["params"]["camera_id"] = args.camera
    if hasattr(args, "profile"):
        payload["params"]["profile_id"] = args.profile

    previous = None
    if args.request_id:
        try:
            previous = request_status(adb, rid)
        except CliError as error:
            if error.code != "REQUEST_NOT_FOUND":
                raise
    if previous is None and args.command != "cameras":
        status = raise_app_error(adb.read("/v1/status"))
        if status.get("busy"):
            raise CliError("BUSY", "An app operation is already running", request_id=rid)
        if not status.get("foreground") or status.get("screen") != "live":
            adb.launch()
            deadline = time.monotonic() + 10
            while True:
                status = raise_app_error(adb.read("/v1/status"))
                if status.get("foreground") and status.get("screen") == "live":
                    break
                if time.monotonic() >= deadline:
                    raise CliError("APP_NOT_FOREGROUND", "Unlock device and open HALCamera", request_id=rid)
                time.sleep(0.25)
    # Reusing an ID still submits its payload once, so the app can detect conflicts.
    data = adb.call("submit", payload)
    if "state" not in data:
        raise_app_error(data)
    validate_request(data, rid)
    if args.no_wait:
        return data
    data = wait(adb, rid, args.wait_timeout or (args.timeout + 15))
    return finish(adb, data, args)


def main(argv=None):
    raw_args = sys.argv[1:] if argv is None else argv
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
        print(json.dumps(data, ensure_ascii=False, indent=2))
    return code
