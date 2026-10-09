"""Exercise the APK's shell client with a fake device transport, without Python on the target."""
import json
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "app/src/main/assets/halcam.sh"
BASH = "C:/Program Files/Git/bin/bash.exe" if os.name == "nt" and Path("C:/Program Files/Git/bin/bash.exe").exists() else shutil.which("bash")
RID = "b616d5cc-7706-4983-b49e-4e4d5c0ef816"


@unittest.skipUnless(BASH, "A POSIX shell is needed for client tests")
class AdbShellTests(unittest.TestCase):
    def test_terminal_status_explains_partial_file_recovery_without_downloading(self):
        for state in ("cancelled", "failed", "interrupted"):
            for artifacts in ([], [{"artifact_id": "file-0", "name": "photo.jpg"}]):
                proc, calls = self.run_client(["status", RID], result={"state": state, "completed": True,
                    "error": {"code": "CANCELLED", "message": "Stopped"}, "artifacts": artifacts})
                self.assertEqual(proc.returncode, 1)
                self.assertIn(f"fetch {RID}" if artifacts else "No files are registered", proc.stdout)
                self.assertNotIn("/files", calls)

    def run_client(self, args, *, hello=None, busy=False, result=None, remember=False, corrupt=False, screen="live"):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            script = root / "halcam.sh"
            script.write_text(SCRIPT.read_text(encoding="utf-8").replace("LAST=/data/local/tmp/halcam-last-request", "LAST=./last")
                              .replace("dest=/sdcard/Download/HALCamera-cli/$rid", "dest=./downloads/$rid"), encoding="utf-8", newline="\n")
            if remember:
                (root / "last").write_text(RID)
            state = {"protocol_version": 1, "request_id": RID, "state": "succeeded", "completed": True, "artifacts": [], "error": None}
            if result:
                state.update(result)
            env = os.environ | {
                "TEST_HELLO": json.dumps(hello or {"protocol_version": 1, "enabled": True}, separators=(",", ":")),
                "TEST_STATUS": json.dumps({"protocol_version": 1, "screen": screen, "busy": busy}, separators=(",", ":")),
                "TEST_RESULT": json.dumps(state, separators=(",", ":")),
                "TEST_RID": RID,
                "TEST_MANIFEST": "file-0\trecording.mp4\t13\t" + ("0" * 64 if corrupt else hashlib.sha256(b"video\x00bytes\r\n").hexdigest()),
            }
            harness = r'''
content() {
    printf '%s\n' "$*" >> calls
    case "$*" in
        *v1/hello*) printf '%s' "$TEST_HELLO" ;;
        *v1/status*) printf '%s' "$TEST_STATUS" ;;
        */files*) printf '%s\n' "$TEST_MANIFEST" ;;
        */artifacts/*) printf 'video\000bytes\r\n' ;;
        *v1/requests*) printf '%s' "$TEST_RESULT" ;;
        *) printf 'Result: Bundle[{json=%s}]\n' "$TEST_RESULT" ;;
    esac
}
cat() {
    if [ "${1:-}" = /proc/sys/kernel/random/uuid ]; then printf '%s\n' "$TEST_RID"
    else command cat "$@"; fi
}
sha256sum() {
    if type -P sha256sum >/dev/null 2>&1; then command sha256sum "$@"
    else shasum -a 256 "$@"; fi
}
. ./halcam.sh "$@"
'''
            proc = subprocess.run([BASH, "-c", harness, "test", *args], cwd=root, env=env, capture_output=True, text=True, encoding="utf-8", timeout=10)
            calls = (root / "calls").read_text() if (root / "calls").exists() else ""
            self.downloads = {p.name: p.read_bytes() for p in (root / "downloads").rglob("*") if p.is_file()}
            return proc, calls

    def test_extended_capture_options_reach_provider(self):
        proc, calls = self.run_client(["burst", "--count", "3", "--raw-size", "640x480", "--ev", "-2", "--fps", "15-30"])
        self.assertEqual(proc.returncode, 0, proc.stderr)
        for value in ("--method burst", "count:s:3", "raw_size:s:640x480", "ev:s:-2", "fps:s:15-30"):
            self.assertIn(value, calls)

    def test_diagnostics_keep_the_dual_screen(self):
        proc, calls = self.run_client(["live", "info"], screen="dual")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("--method live.info", calls)

    def test_results_do_not_launch_camera(self):
        proc, calls = self.run_client(["results", "show", "--run", "20261009-123000-123"])
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("--method results.show", calls)
        self.assertNotIn("v1/status", calls)

    def test_benchmark_label_is_one_argument(self):
        proc, calls = self.run_client(["benchmark", "run", "--build", "Candidate A", "--note", "Same room"])
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("build:s:Candidate A", calls)
        self.assertIn("note:s:Same room", calls)

    def test_live_update_does_not_start_another_request(self):
        proc, calls = self.run_client(["live", "set", "--zoom", "2"])
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("--method live.set", calls)
        self.assertNotIn("request_id:s:", calls)
        self.assertNotIn("v1/status", calls)

    def test_help_starts_with_five_tasks(self):
        proc, calls = self.run_client(["help"])
        self.assertEqual(proc.returncode, 0)
        self.assertLess(len(proc.stdout.splitlines()), 12)
        self.assertIn("help all", proc.stdout)
        self.assertEqual(calls, "")

    def test_stream_options_reach_provider_as_strings(self):
        proc, calls = self.run_client(["preview", "--engine", "CameraX", "--preview-size", "1280x720", "--yuv-size", "off"])
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("engine:s:CameraX", calls)
        self.assertIn("preview_size:s:1280x720", calls)
        self.assertIn("yuv_size:s:off", calls)

    def test_stream_listing_does_not_launch_live(self):
        proc, calls = self.run_client(["streams", "--camera", "0", "--engine", "CameraX"])
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("--method streams", calls)
        self.assertNotIn("v1/status", calls)

    def test_stream_option_cannot_inject_shell_or_extra_delimiter(self):
        proc, calls = self.run_client(["preview", "--preview-size", "1280:720"])
        self.assertNotEqual(proc.returncode, 0)
        self.assertEqual(calls, "")

    def test_capture_waits_and_needs_no_user_generated_id(self):
        proc, calls = self.run_client(["capture", "--camera", "1"])
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("request_id=" + RID, proc.stdout)
        self.assertIn("--extra camera:s:1", calls)
        self.assertIn("--extra request_id:s:" + RID, calls)
        self.assertIn("v1/requests/" + RID, calls)

    def test_record_start_returns_when_actual_recording_has_started(self):
        proc, calls = self.run_client(["record", "start", "--no-audio"], result={"completed": False, "state": "running", "result": {"recording": True}})
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("Recording started", proc.stdout)
        self.assertIn("audio:b:false", calls)

    def test_record_stop_bypasses_busy_launch_check(self):
        proc, calls = self.run_client(["record", "stop"], busy=True)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("--method record.stop", calls)
        self.assertNotIn("v1/status", calls)

    def test_busy_does_not_submit_or_replace_last_request(self):
        proc, calls = self.run_client(["capture"], busy=True)
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("Another operation", proc.stderr)
        self.assertNotIn("--method capture", calls)

    def test_disabled_app_has_actionable_error_and_no_submit(self):
        proc, calls = self.run_client(["capture"], hello={"protocol_version": 1, "error": {"code": "CLI_DISABLED", "message": "Enable ADB CLI in the app"}})
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("Enable ADB CLI", proc.stderr)
        self.assertNotIn("--method capture", calls)

    def test_completed_failure_does_not_report_success(self):
        proc, _ = self.run_client(["capture"], result={"state": "failed", "error": {"code": "CAPTURE_FAILED"}})
        self.assertNotEqual(proc.returncode, 0)

    def test_cancelled_request_without_error_never_reports_photos_saved(self):
        proc, _ = self.run_client(["capture"], result={"state": "cancelled", "error": None})
        self.assertNotEqual(proc.returncode, 0)
        self.assertNotIn("Photos saved", proc.stdout)
        self.assertIn("fetch " + RID, proc.stderr)

    def test_doctor_checks_readiness_without_submitting_or_reading_last_request(self):
        proc, calls = self.run_client(["doctor"], remember=True, hello={
            "protocol_version": 1, "enabled": True, "camera_permission": True, "locked": False})
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("Basic setup is ready", proc.stdout)
        self.assertIn("v1/hello", calls)
        self.assertIn("v1/status", calls)
        self.assertNotIn("call", calls)
        self.assertNotIn("v1/requests", calls)

    def test_doctor_reports_permission_lock_and_busy_conditions(self):
        proc, calls = self.run_client(["doctor"], busy=True, hello={
            "protocol_version": 1, "enabled": True, "camera_permission": False, "locked": True})
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("Allow camera", proc.stderr)
        self.assertIn("Unlock", proc.stderr)
        self.assertIn("Another operation", proc.stderr)
        self.assertNotIn("call", calls)

    def test_doctor_disabled_explains_where_to_enable_cli(self):
        proc, calls = self.run_client(["doctor"], hello={
            "protocol_version": 1, "error": {"code": "CLI_DISABLED"}})
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("Lab > ADB CLI", proc.stderr)
        self.assertNotIn("v1/status", calls)

    def test_app_status_ignores_remembered_request(self):
        proc, calls = self.run_client(["status", "--app"], remember=True, busy=True)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("v1/status", calls)
        self.assertNotIn("v1/requests", calls)

    def test_status_recovers_last_request_without_resubmitting(self):
        proc, calls = self.run_client(["status"], remember=True)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("v1/requests/" + RID, calls)
        self.assertNotIn("call", calls)

    def test_binary_export_is_verified_and_provides_one_pull_command(self):
        proc, _ = self.run_client(["record", "stop"], result={"artifacts": [{"artifact_id": "file-0"}]})
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(self.downloads, {"recording.mp4": b"video\x00bytes\r\n"})
        self.assertEqual(proc.stdout.count("adb pull"), 1)

    def test_corrupt_export_never_publishes_final_file(self):
        proc, _ = self.run_client(["fetch", RID], result={"artifacts": [{"artifact_id": "file-0"}]}, corrupt=True)
        self.assertNotEqual(proc.returncode, 0)
        self.assertNotIn("recording.mp4", self.downloads)
        self.assertNotIn("adb pull", proc.stdout)

    def test_timeout_video_can_be_recovered_without_recording_again(self):
        proc, calls = self.run_client(["fetch", RID], result={"state": "failed", "error": {"code": "EXECUTION_TIMEOUT"}, "artifacts": [{"artifact_id": "file-0"}]})
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("recording.mp4", self.downloads)
        self.assertNotIn("--method", calls)

    def test_benchmark_submits_fixed_profile_and_can_return_without_waiting(self):
        proc, calls = self.run_client(["benchmark", "run", "--camera", "1", "--profile", "camera2-standard-v2", "--no-wait"])
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("--method benchmark.run", calls)
        self.assertIn("camera:s:1", calls)
        self.assertIn("profile:s:camera2-standard-v2", calls)
        self.assertIn("v1/status", calls)
        self.assertNotIn("v1/requests", calls)

    def test_benchmark_waits_for_report(self):
        proc, calls = self.run_client(["benchmark", "run"])
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("v1/requests/" + RID, calls)
        self.assertIn('"state":"succeeded"', proc.stdout)

    def test_invalid_usage_does_not_touch_device(self):
        for args in (["benchmark"], ["benchmark", "run", "--engine", "CameraX"],
                     ["benchmark", "run", "--profile", "camera2-standard-v1"],
                     ["benchmark", "run", "--no-audio"], ["capture", "--profile", "camera2-standard-v2"],
                     ["capture", "--camera"], ["capture", "--timeout", "0"], ["capture", "--timeout", "3601"], ["capture", "--typo"], ["status", "../foo"]):
            proc, calls = self.run_client(args)
            self.assertNotEqual(proc.returncode, 0, args)
            self.assertEqual(calls, "", args)
