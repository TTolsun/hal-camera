import base64
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from halcam.adb import Adb
from halcam.cli import parser, wait, main
from halcam.download import collect
from halcam.protocol import CliError, identifier, validate_request

RID = "b616d5cc-7706-4983-b49e-4e4d5c0ef816"


def response(state="succeeded"):
    return {"protocol_version": 1, "request_id": RID, "state": state,
            "completed": state in {"succeeded", "failed", "cancelled", "interrupted"},
            "error": {"code": "EXECUTION_FAILED", "message": "failed"} if state in {"failed", "cancelled", "interrupted"} else None, "artifacts": []}


class ProtocolTests(unittest.TestCase):
    def test_missing_package_is_not_a_connection_error(self):
        adb = Adb()
        for output in (b"", b"package:dev.halcamera.another\n"):
            with patch.object(adb, "run", return_value=output), self.assertRaises(CliError) as caught:
                adb.installed()
            self.assertEqual(caught.exception.code, "APP_NOT_INSTALLED")

    def test_shared_android_python_contract_fixture(self):
        fixture = json.loads((Path(__file__).parent.parent / "fixtures" / "protocol-v1.json").read_text())
        request = fixture["request"]
        record = validate_request(fixture["response"], identifier(request["request_id"]))
        self.assertEqual(CliError(record["error"]["code"], record["error"]["message"]).exit_code, 5)
        self.assertEqual(request["params"], {"camera_id": "0"})

    def test_globals_work_before_or_after_subcommands(self):
        for argv in [["--serial", "phone", "capture", "--camera", "0", "--output", "out", "--json"],
                     ["capture", "--serial", "phone", "--camera", "0", "--output", "out", "--json"],
                     ["--serial", "phone", "benchmark", "run", "--camera", "0", "--output", "out", "--json"]]:
            args = parser().parse_args(argv)
            self.assertEqual(args.serial, "phone")
            self.assertTrue(args.json)

    def test_request_id_rejects_traversal_and_noncanonical_uuid(self):
        for value in ["../file", "1-1-1-1-1", RID.upper(), "", RID + "/x"]:
            with self.assertRaises(CliError):
                identifier(value)

    def test_request_response_must_match_id_and_completion(self):
        for changed in [{"request_id": str(__import__("uuid").uuid4())}, {"state": "invented"},
                        {"completed": False}, {"artifacts": None}, {"state": []}, {"error": "invalid"}]:
            with self.assertRaises(CliError):
                validate_request(response() | changed, RID)

    def test_remote_payload_is_encoded_without_shell_syntax(self):
        adb = Adb(serial="phone")
        payload = {"protocol_version": 1, "text": "한글 ' \" ` $() ;\n &"}
        encoded = base64.urlsafe_b64encode(json.dumps(response()).encode()).rstrip(b"=")
        with patch.object(adb, "run", return_value=b"Result: Bundle[{halcam_v1=" + encoded + b"}]\n") as run:
            self.assertEqual(adb.call("submit", payload)["request_id"], RID)
        args = run.call_args.args
        self.assertEqual(args[0:3], ("shell", "content", "call"))
        self.assertRegex(args[-1], r"^[A-Za-z0-9_-]+$")
        self.assertEqual(json.loads(base64.urlsafe_b64decode(args[-1] + "=" * (-len(args[-1]) % 4))), payload)

    def test_zero_exit_code_with_exception_text_is_not_success(self):
        adb = Adb()
        with patch.object(adb, "run", return_value=b"Error while accessing provider: SecurityException"):
            with self.assertRaises(CliError) as caught:
                adb.call("submit", {})
            self.assertEqual(caught.exception.code, "PROTOCOL_ERROR")

    def test_device_selection_never_chooses_arbitrarily(self):
        adb = Adb()
        with patch.object(adb, "devices", return_value=[{"serial": "a", "state": "device"}, {"serial": "b", "state": "device"}]):
            with self.assertRaises(CliError) as caught:
                adb.select()
            self.assertEqual(caught.exception.code, "MULTIPLE_DEVICES")
            adb.serial = "b"
            self.assertEqual(adb.select().serial, "b")

    def test_unauthorized_device_has_specific_error(self):
        adb = Adb()
        with patch.object(adb, "devices", return_value=[{"serial": "a", "state": "unauthorized"}]):
            with self.assertRaises(CliError) as caught:
                adb.select()
            self.assertEqual(caught.exception.code, "DEVICE_UNAUTHORIZED")

    def test_polling_returns_terminal_failure_without_replaying(self):
        adb = Adb()
        with patch.object(adb, "read", return_value=response("failed")), patch.object(adb, "call") as call:
            self.assertEqual(wait(adb, RID, 1)["state"], "failed")
            call.assert_not_called()

    def test_wait_timeout_does_not_cancel_or_replay(self):
        adb = Adb()
        with patch.object(adb, "read", return_value=response("running")), patch.object(adb, "call") as call:
            with self.assertRaises(CliError) as caught:
                wait(adb, RID, 0)
            self.assertEqual(caught.exception.code, "WAIT_TIMEOUT")
            self.assertEqual(caught.exception.request_id, RID)
            call.assert_not_called()

    def test_json_error_has_one_document_and_nonzero_exit(self):
        with patch("halcam.cli.Adb.devices", return_value=[]), patch("sys.stdout", new_callable=io.StringIO) as stdout:
            code = main(["doctor", "--json"])
            self.assertEqual(code, 3)
            self.assertEqual(json.loads(stdout.getvalue())["error"]["code"], "DEVICE_NOT_FOUND")

    def test_parse_error_is_json_when_requested(self):
        with patch("sys.stdout", new_callable=io.StringIO) as stdout:
            self.assertEqual(main(["capture", "--json"]), 2)
            self.assertEqual(json.loads(stdout.getvalue())["error"]["code"], "INVALID_ARGUMENT")


class DownloadTests(unittest.TestCase):
    content = b"\xff\xd8test\x00\r\n\xff\xd9"

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.adb = Adb(serial="phone")
        self.data = response()
        self.data["artifacts"] = [{"artifact_id": "file-0", "name": "HAL_test_YUV.jpg", "mime_type": "image/jpeg",
            "size_bytes": len(self.content), "sha256": hashlib.sha256(self.content).hexdigest()}]

    def process(self, data):
        class Process:
            returncode = 0
            def __init__(self, args, stdout, **kwargs):
                stdout.write(data)
            def communicate(self, timeout):
                return None, b""
        return Process

    def test_binary_is_preserved_and_verified_files_are_reused(self):
        with patch("halcam.download.subprocess.Popen", self.process(self.content)):
            files = collect(self.adb, self.data, self.tmp.name)
        self.assertEqual(Path(files[0]).read_bytes(), self.content)
        with patch("halcam.download.subprocess.Popen") as process:
            self.assertEqual(collect(self.adb, self.data, self.tmp.name), files)
            process.assert_not_called()
        self.assertFalse(list(Path(self.tmp.name).rglob("*.part")))

    def test_corruption_never_creates_final_file(self):
        with patch("halcam.download.subprocess.Popen", self.process(b"truncated")):
            with self.assertRaises(CliError) as caught:
                collect(self.adb, self.data, self.tmp.name)
        self.assertEqual(caught.exception.code, "CHECKSUM_MISMATCH")
        self.assertFalse(list(Path(self.tmp.name).rglob("*.jpg")))
        self.assertFalse(list(Path(self.tmp.name).rglob("*.part")))

    def test_server_filename_cannot_escape_directory(self):
        for name in ["../photo.jpg", "C:\\photo.jpg", "name.jpg:stream", "CON", "CON.jpg", "photo.txt"]:
            self.data["artifacts"][0]["name"] = name
            with self.assertRaises(CliError):
                collect(self.adb, self.data, self.tmp.name)

    def test_existing_different_file_is_not_overwritten(self):
        directory = Path(self.tmp.name) / RID
        directory.mkdir()
        target = directory / "HAL_test_YUV.jpg"
        target.write_bytes(b"user data")
        with self.assertRaises(CliError) as caught:
            collect(self.adb, self.data, self.tmp.name)
        self.assertEqual(caught.exception.code, "OUTPUT_EXISTS")
        self.assertEqual(target.read_bytes(), b"user data")

    def test_malformed_artifact_is_protocol_error(self):
        self.data["artifacts"] = [None]
        with self.assertRaises(CliError) as caught:
            collect(self.adb, self.data, self.tmp.name)
        self.assertEqual(caught.exception.code, "PROTOCOL_ERROR")


class ProbeAndCtsTests(unittest.TestCase):
    """The commands added for Probe and CTS: their argv shapes, the payloads they submit and when the app is launched."""

    def submitted(self, argv, status=None):
        submitted = {}
        status = status or {"protocol_version": 1, "foreground": True, "screen": "live", "busy": False}

        def call(method, payload):
            submitted["method"], submitted["payload"] = method, payload
            return response("accepted") | {"completed": False, "error": None}

        def read(path):
            if path == "/v1/status":
                return status
            raise CliError("REQUEST_NOT_FOUND", "unknown")

        def launch():
            submitted["launched"] = True
            status.update(foreground=True, screen="live")

        with patch.object(Adb, "select", lambda self: self), patch.object(Adb, "installed", lambda self: self), \
                patch.object(Adb, "hello", lambda self: {"protocol_version": 1, "enabled": True}), \
                patch.object(Adb, "call", lambda self, m, p: call(m, p)), patch.object(Adb, "read", lambda self, p: read(p)), \
                patch.object(Adb, "launch", lambda self: launch()), patch("halcam.cli.wait", return_value=response()), \
                patch("halcam.cli.collect", return_value=[]):
            self.assertEqual(main(argv + ["--request-id", RID, "--json"]), 0)
        submitted.setdefault("launched", False)
        return submitted

    def test_probe_submits_without_camera_and_without_launching(self):
        with patch("sys.stdout", new_callable=io.StringIO):
            got = self.submitted(["--serial", "phone", "probe", "--output", "out"],
                                 status={"protocol_version": 1, "foreground": False, "screen": None, "busy": False})
        self.assertEqual(got["payload"]["command"], "probe")
        self.assertEqual(got["payload"]["params"], {})
        self.assertEqual(got["payload"]["execution_timeout_ms"], 30000)
        self.assertFalse(got["launched"])

    def test_cts_cases_lists_without_launching(self):
        with patch("sys.stdout", new_callable=io.StringIO):
            got = self.submitted(["--serial", "phone", "cts", "cases"],
                                 status={"protocol_version": 1, "foreground": False, "screen": None, "busy": False})
        self.assertEqual(got["payload"]["command"], "cts.cases")
        self.assertEqual(got["payload"]["params"], {})
        self.assertFalse(got["launched"])

    def test_cts_run_sends_every_case_in_order_and_needs_live(self):
        with patch("sys.stdout", new_callable=io.StringIO):
            got = self.submitted(["--serial", "phone", "cts", "run", "--case", "custom:fast_on_off",
                                  "--case", "vendored:android.hardware.camera2.cts.RecordingTest#testBasicRecording", "--output", "out"],
                                 status={"protocol_version": 1, "foreground": False, "screen": None, "busy": False})
        self.assertTrue(got["launched"])
        self.assertEqual(got["payload"]["command"], "cts.run")
        self.assertEqual(got["payload"]["params"], {"cases": ["custom:fast_on_off", "vendored:android.hardware.camera2.cts.RecordingTest#testBasicRecording"]})
        self.assertEqual(got["payload"]["execution_timeout_ms"], 1800000)

    def test_cts_run_requires_a_case_and_an_output(self):
        for argv in (["cts", "run", "--output", "out"], ["cts", "run", "--case", "custom:fast_on_off"], ["cts"], ["probe"]):
            with self.assertRaises(CliError) as caught:
                parser().parse_args(argv)
            self.assertEqual(caught.exception.code, "INVALID_ARGUMENT")

    def test_text_artifacts_are_accepted_by_the_download_name_filter(self):
        body = b"HAL CAM CTS suite\n"
        data = response() | {"artifacts": [{"artifact_id": "file-1", "name": "cts-suite.txt", "mime_type": "text/plain",
                                            "size_bytes": len(body), "sha256": hashlib.sha256(body).hexdigest()}]}

        class Process:
            returncode = 0
            def __init__(self, args, stdout, **kwargs):
                stdout.write(body)
            def communicate(self, timeout):
                return None, b""

        with tempfile.TemporaryDirectory() as tmp, patch("halcam.download.subprocess.Popen", Process):
            files = collect(Adb(serial="phone"), data, tmp)
            self.assertTrue(files[0].endswith("cts-suite.txt"))
            self.assertEqual(Path(files[0]).read_bytes(), body)


if __name__ == "__main__":
    unittest.main()
