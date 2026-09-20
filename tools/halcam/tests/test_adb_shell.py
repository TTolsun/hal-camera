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
    def run_client(self, args, *, hello=None, busy=False, result=None, remember=False, corrupt=False):
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
                "TEST_STATUS": json.dumps({"protocol_version": 1, "screen": "live", "busy": busy}, separators=(",", ":")),
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

    def test_invalid_usage_and_benchmark_do_not_touch_device(self):
        for args in (["benchmark", "run"], ["capture", "--camera"], ["capture", "--timeout", "0"], ["capture", "--timeout", "3601"], ["capture", "--typo"], ["status", "../foo"]):
            proc, calls = self.run_client(args)
            self.assertNotEqual(proc.returncode, 0, args)
            self.assertEqual(calls, "", args)
