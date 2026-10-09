import argparse
import io
import unittest
from unittest.mock import patch

from halcam.cli import main, wait
from halcam.presentation import render

RID = "b616d5cc-7706-4983-b49e-4e4d5c0ef816"


def record(state="succeeded", **extra):
    return dict(protocol_version=1, request_id=RID, state=state,
                completed=state in ("succeeded", "cancelled"), artifacts=[], error=None, **extra)


class PresentationTests(unittest.TestCase):
    def test_doctor_distinguishes_connection_from_camera_readiness(self):
        from halcam.cli import execute, parser
        for permission, locked, busy, expected in ((False, False, False, "PERMISSION_REQUIRED"),
                (True, True, False, "DEVICE_LOCKED"), (True, False, True, "BUSY"), (True, False, False, None)):
            with self.subTest(expected=expected), patch("halcam.cli.Adb") as factory:
                adb = factory.return_value
                adb.hello.return_value = {"commands": ["capture"], "camera_permission": permission, "locked": locked}
                adb.read.return_value = {"busy": busy, "locked": locked}
                data = execute(parser().parse_args(["doctor"]), {})
                self.assertEqual(data["ready"], expected is None)
                self.assertEqual((data.get("error") or {}).get("code"), expected)
                if expected:
                    self.assertNotIn("Ready.", render(data, argparse.Namespace()))

    def test_permanent_errors_do_not_repeat_the_failed_operation(self):
        for code in ("CLI_DISABLED", "REQUEST_NOT_FOUND", "ARTIFACT_EXPIRED"):
            text = render(dict(completed=False, request_id=RID, error={"code": code, "message": "Unavailable"}), argparse.Namespace())
            self.assertNotIn("Next: halcam doctor", text)
            self.assertNotIn("Next: halcam status --request", text)
            self.assertIn("Lab > ADB CLI" if code == "CLI_DISABLED" else "gallery.list", text)

    def test_retention_preview_explains_no_change_and_uses_python_confirmation(self):
        for limit in (0, 10):
            for deleting in ([], ["run-a", "run-b"]):
                data = record(result={"run_limit": limit, "applied": False, "delete_runs": deleting})
                text = render(data, argparse.Namespace(serial="phone"))
                self.assertTrue(text.startswith("Preview only. Nothing has changed."))
                self.assertIn(f"halcam --serial phone run settings.limit --option limit={limit} --option confirm=true", text)
                data["result"]["applied"] = True
                self.assertNotIn("confirm=true", render(data, argparse.Namespace()))

    def test_no_arguments_shows_start_tasks_without_contacting_device(self):
        with patch("halcam.cli.Adb") as adb, patch("sys.stdout", new_callable=io.StringIO) as output:
            self.assertEqual(main([]), 0)
            self.assertIn("Start with one task", output.getvalue())
            adb.assert_not_called()

    def test_partial_files_take_priority_over_retry(self):
        data = record("cancelled")
        data.update(error={"code": "CANCELLED", "message": "Stopped"}, artifacts=[{"name": "saved.jpg"}])
        text = render(data, argparse.Namespace(serial="phone"))
        self.assertIn(f"halcam --serial phone fetch {RID} --output ./captures", text)
        self.assertNotIn("Next: halcam --serial phone doctor", text)

    def test_timeout_preserves_request_and_device_for_status(self):
        data = dict(request_id=RID, completed=False, error={"code": "WAIT_TIMEOUT", "message": "Still running"})
        self.assertIn(f"Next: halcam --serial phone status --request {RID}", render(data, argparse.Namespace(serial="phone")))

    def test_stop_response_is_not_reported_as_a_new_recording(self):
        text = render(record("running", result={"recording": True}), argparse.Namespace(operation="record.stop"))
        self.assertIn("Stop requested", text)
        self.assertIn("status --request", text)
        self.assertNotIn("Recording started", text)

    def test_results_list_does_not_silently_hide_entries(self):
        rows = [{"run_id": str(i)} for i in range(30)]
        text = render(record(result={"runs": rows}), argparse.Namespace())
        self.assertEqual(text.count('"run_id"'), 30)

    def test_completed_capture_does_not_claim_it_is_still_capturing(self):
        text = render(record(progress={"saved": 3, "total": 3, "phase": "capturing"}), argparse.Namespace())
        self.assertIn("3/3 photos saved", text)
        self.assertNotIn("(capturing)", text)

    def test_progress_is_reported_only_when_changed(self):
        running = record("running", progress={"saved": 1, "total": 3})
        done = record()
        updates = []
        with patch("halcam.cli.request_status", side_effect=[running, running, done]), patch("halcam.cli.time.sleep"):
            self.assertEqual(wait(None, RID, 10, updates.append), done)
        self.assertEqual(updates, ["running: 1/3 photos saved (capture)", "succeeded"])

    def test_human_error_has_action_and_json_mode_stays_structured(self):
        with patch("halcam.cli.Adb.devices", return_value=[]), patch("sys.stdout", new_callable=io.StringIO) as output:
            self.assertEqual(main(["doctor"]), 3)
            self.assertIn("Next: halcam devices", output.getvalue())

    def test_silent_recording_payload_and_misuse_rejection(self):
        from halcam.cli import execute, parser
        from halcam.protocol import CliError
        with patch("halcam.cli.Adb") as factory:
            adb = factory.return_value
            adb.hello.return_value = {"commands": ["record.start"]}
            adb.read.return_value = {"foreground": True, "screen": "live", "busy": False}
            adb.call.side_effect = lambda method, payload: record("running", result={"recording": True}) | {"request_id": payload["request_id"]}
            execute(parser().parse_args(["run", "record.start", "--no-audio"]), {})
            self.assertIs(adb.call.call_args.args[1]["params"]["audio"], False)
            with self.assertRaises(CliError):
                execute(parser().parse_args(["run", "burst", "--no-audio"]), {})

    def test_busy_before_submission_never_suggests_nonexistent_request(self):
        with patch("halcam.cli.Adb") as factory, patch("sys.stdout", new_callable=io.StringIO) as output:
            adb = factory.return_value
            adb.hello.return_value = {"commands": ["burst"]}
            adb.read.return_value = {"foreground": True, "screen": "live", "busy": True}
            self.assertNotEqual(main(["run", "burst"]), 0)
            self.assertIn("Next: halcam status", output.getvalue())
            self.assertNotIn("Request:", output.getvalue())
            adb.call.assert_not_called()

    def test_app_rejection_has_no_phantom_id_but_uncertain_transport_keeps_it(self):
        from halcam.protocol import CliError
        with patch("halcam.cli.Adb") as factory, patch("sys.stdout", new_callable=io.StringIO) as output:
            adb = factory.return_value
            adb.hello.return_value = {"commands": ["results.delete"]}
            adb.call.return_value = {"error": {"code": "CONFIRM_REQUIRED", "message": "Confirm the listed ID"}}
            main(["run", "results.delete", "--option", "run=example"])
            self.assertNotIn("Request:", output.getvalue())
            self.assertIn("confirm=true", output.getvalue())
            output.seek(0)
            output.truncate()
            adb.read.side_effect = CliError("REQUEST_NOT_FOUND", "No earlier request")
            adb.call.side_effect = CliError("CONNECTION_LOST", "Connection closed")
            main(["run", "results.delete", "--option", "run=example", "--request-id", RID])
            self.assertIn(f"status --request {RID}", output.getvalue())
