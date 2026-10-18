import json
import unittest
from pathlib import Path
from unittest.mock import patch
from halcam.cli import parser, execute
from halcam.adb import Adb
from halcam.submission import make_request

HELLO = {"protocol_version": 1, "enabled": True, **json.loads((Path(__file__).parent / "fixtures/hello.json").read_text())}

class MultiCliTests(unittest.TestCase):
    def test_ordered_options_and_recording_deadline(self):
        for operation in ("preview", "capture", "record"):
            argv = ["multi", operation, "--cameras", "1,0", "--engine", "CameraX", "--preview-sizes", "640x480,1280x720"]
            if operation == "capture":
                argv += ["--output", "out"]
            if operation == "record":
                argv += ["--no-audio"]
            args = parser().parse_args(argv)
            request = make_request(args, "multi." + operation, HELLO)
            self.assertEqual(request["params"]["options"]["cameras"], "1,0")
            self.assertEqual(request["params"]["options"]["preview_sizes"], "640x480,1280x720")
            self.assertNotIn("camera_id", request["params"])
            self.assertEqual(request["execution_timeout_ms"], 3600000 if operation == "record" else 30000)
            self.assertEqual(request["params"].get("audio"), False if operation == "record" else None)

    def test_diagnostics_do_not_launch_live_over_multi(self):
        rid = "b616d5cc-7706-4983-b49e-4e4d5c0ef816"
        record = {"protocol_version": 1, "request_id": rid, "completed": True, "state": "succeeded", "artifacts": [], "error": None}
        with patch.object(Adb, "select", lambda self: self), patch.object(Adb, "installed"), \
                patch.object(Adb, "hello", return_value=HELLO), \
                patch.object(Adb, "read", return_value={"foreground": True, "screen": "multi", "busy": False}), \
                patch.object(Adb, "launch") as launch, patch.object(Adb, "call", return_value=record), \
                patch("halcam.cli.wait", return_value=record), patch("halcam.submission.uuid.uuid4", return_value=rid):
            execute(parser().parse_args(["run", "live.info"]), {})
            launch.assert_not_called()
