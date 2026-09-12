"""ADB transport. No host shell, arbitrary remote command, or text file transfer."""

import base64
import json
import re
import subprocess
from .protocol import CliError, decode_response, package_name, raise_app_error


class Adb:
    def __init__(self, executable="adb", serial=None, package="dev.halcamera", timeout=15):
        self.executable, self.serial = executable, serial
        self.package = package_name(package)
        self.timeout = timeout
        self.authority = f"content://{self.package}.cli"

    def argv(self, *args):
        return [self.executable] + (["-s", self.serial] if self.serial else []) + list(args)

    def run(self, *args):
        try:
            result = subprocess.run(self.argv(*args), capture_output=True, timeout=self.timeout,
                                    check=False, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        except FileNotFoundError:
            raise CliError("ADB_NOT_FOUND", "Install Android Platform Tools or pass --adb") from None
        except subprocess.TimeoutExpired:
            raise CliError("CONNECTION_LOST", "ADB timed out; query the request before retrying") from None
        except OSError as error:
            raise CliError("CONNECTION_LOST", str(error)) from error
        if result.returncode:
            raise CliError("CONNECTION_LOST", result.stderr.decode("utf-8", "replace").strip() or "ADB failed")
        return result.stdout

    def devices(self):
        raw = self.run("devices", "-l").decode("utf-8", "replace")
        devices = []
        for line in raw.splitlines():
            fields = line.split()
            if len(fields) >= 2 and not line.startswith(("List of", "*")):
                devices.append({"serial": fields[0], "state": fields[1], "details": " ".join(fields[2:])})
        return devices

    def select(self):
        devices = self.devices()
        if self.serial:
            selected = next((d for d in devices if d["serial"] == self.serial), None)
            if selected is None:
                raise CliError("DEVICE_NOT_FOUND", "Requested device is not connected")
        else:
            if not devices:
                raise CliError("DEVICE_NOT_FOUND", "Connect and authorize an Android device")
            if len(devices) != 1:
                raise CliError("MULTIPLE_DEVICES", "Pass --serial; more than one ADB connection is present")
            selected = devices[0]
        if selected["state"] == "unauthorized":
            raise CliError("DEVICE_UNAUTHORIZED", "Approve this PC's ADB connection on the device")
        if selected["state"] != "device":
            raise CliError("CONNECTION_LOST", f"Device is {selected['state']}")
        self.serial = selected["serial"]
        return self

    def installed(self):
        raw = self.run("shell", "pm", "path", "--user", "0", self.package)
        if not raw.startswith(b"package:"):
            raise CliError("APP_NOT_INSTALLED", f"Install {self.package} for Android user 0")
        user = self.run("shell", "am", "get-current-user").decode().strip()
        if user != "0":
            raise CliError("UNSUPPORTED_USER", "CLI v1 supports Android user 0 only")

    def read(self, path):
        return decode_response(self.run("exec-out", "content", "read", "--uri", self.authority + path))

    def call(self, method, payload):
        raw_json = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
        if len(raw_json) > 8192:
            raise CliError("INVALID_ARGUMENT", "Request exceeds 8 KiB")
        encoded = base64.urlsafe_b64encode(raw_json).decode().rstrip("=")
        output = self.run("shell", "content", "call", "--uri", self.authority,
                          "--method", method, "--arg", encoded)
        match = re.fullmatch(rb"\s*Result: Bundle\[\{halcam_v1=([A-Za-z0-9_-]+)\}\]\s*", output)
        if not match:
            raise CliError("PROTOCOL_ERROR", "Unexpected content call response")
        value = match[1]
        try:
            response = decode_response(base64.b64decode(value + b"=" * (-len(value) % 4), altchars=b"-_", validate=True))
        except ValueError:
            raise CliError("PROTOCOL_ERROR", "Invalid base64 response") from None
        return response

    def launch(self):
        # The app rechecks operation ownership on main before navigating to LIVE.
        output = self.run("shell", "am", "start", "-W", "-n",
                          f"{self.package}/dev.halcamera.cli.CliLaunchActivity")
        if b"Error" in output or b"Status: ok" not in output:
            raise CliError("APP_NOT_FOREGROUND", output.decode("utf-8", "replace").strip())

    def hello(self):
        return raise_app_error(self.read("/v1/hello"))
