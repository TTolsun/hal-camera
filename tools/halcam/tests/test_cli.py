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


if __name__ == "__main__":
    unittest.main()
