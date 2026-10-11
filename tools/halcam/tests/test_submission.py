import unittest
from halcam.cli import parser, app_command
from halcam.protocol import CliError
from halcam.submission import make_request


class SubmissionTests(unittest.TestCase):
    def request(self, argv, spec=None):
        args = parser().parse_args(argv)
        operation = app_command(args)
        hello = {"commands": [operation]}
        if spec is not None:
            hello["command_specs"] = {operation: spec}
        return make_request(args, operation, hello)

    def test_generic_cts_uses_cases_not_options_and_keeps_order(self):
        keys = ["custom:fast_on_off", "vendored:android.hardware.camera2.cts.RecordingTest#testBasicRecording"]
        for argv in (["run", "cts.run", "--cases", ",".join(keys)],
                     ["cts", "run", "--case", keys[0], "--case", keys[1], "--output", "out"]):
            data = self.request(argv)
            self.assertEqual(data["params"], {"cases": keys})
            self.assertEqual(data["execution_timeout_ms"], 1_800_000)

    def test_generic_cts_rejects_missing_duplicate_and_misplaced_cases(self):
        for argv in (["run", "cts.run"], ["run", "cts.run", "--cases", "custom:a,custom:a"],
                     ["run", "cts.run", "--cases", ""], ["run", "capture", "--cases", "custom:a"]):
            with self.subTest(argv=argv), self.assertRaises(CliError):
                self.request(argv)

    def test_named_and_generic_photo_have_identical_params(self):
        options = ["--camera", "1", "--raw-size", "640x480", "--yuv-size", "off", "--jpeg-size", "off",
                   "--fps", "15-30", "--zoom", "2", "--iso", "100", "--exposure-ns", "1000000"]
        photo = self.request(["capture", "--output", "out", *options])
        generic = self.request(["run", "capture", *options])
        self.assertEqual(photo["params"], generic["params"])
        self.assertEqual(photo["params"]["streams"]["raw_size"], "640x480")
        self.assertEqual(photo["params"]["options"]["zoom"], "2")

    def test_named_and_generic_recording_have_identical_params(self):
        options = ["--camera", "0", "--video-size", "1280x720", "--video-fps", "30", "--codec", "HEVC",
                   "--stabilization", "VIDEO", "--flash", "TORCH", "--no-audio"]
        named = self.request(["record", "start", *options])
        generic = self.request(["run", "record.start", *options])
        self.assertEqual(named["params"], generic["params"])
        self.assertEqual(named["execution_timeout_ms"], 3_600_000)

    def test_preview_stop_carries_no_implicit_camera(self):
        self.assertEqual(self.request(["preview", "stop"])["params"], {})

    def test_video_preview_and_stream_listing_accept_mode(self):
        for argv in (["preview", "--mode", "Video"], ["streams", "--mode", "Video"],
                     ["run", "preview", "--mode", "Video"]):
            self.assertEqual(self.request(argv)["params"]["mode"], "Video")
        with self.assertRaises(CliError):
            self.request(["run", "capture", "--mode", "Video"])

    def test_duplicate_typed_and_generic_fields_never_silently_override(self):
        for argv in (["run", "capture", "--zoom", "2", "--option", "zoom=3"],
                     ["run", "capture", "--raw-size", "640x480", "--stream", "raw_size=1280x720"]):
            with self.subTest(argv=argv), self.assertRaises(CliError):
                self.request(argv)

    def test_advertised_contract_drives_defaults_and_required_options(self):
        spec = dict(camera=False, engine=False, streams=False, audio=False, foreground=False,
                    default_timeout_ms=120000, options=["run"], required_options=["run"])
        request = self.request(["run", "results.show", "--run", "20261011-123000-123"], spec)
        self.assertEqual(request["execution_timeout_ms"], 120000)
        self.assertNotIn("camera_id", request["params"])
        for argv in (["run", "results.show"], ["run", "results.show", "--run", "id", "--zoom", "2"],
                     ["run", "results.show", "--run", "id", "--camera", "0"]):
            with self.subTest(argv=argv), self.assertRaises(CliError):
                self.request(argv, spec)

    def test_named_and_generic_benchmark_keep_subject_labels(self):
        options = ["--build", "Candidate: A", "--note", "Same room"]
        named = self.request(["benchmark", "run", "--output", "out", *options])
        generic = self.request(["run", "benchmark.run", *options])
        self.assertEqual(named["params"], generic["params"])

    def test_named_and_generic_pip_capture_have_identical_params(self):
        options = ["--pip-type", "physical", "--pip-camera", "2", "--pip-preview-size", "640x480",
                   "--pip-photo-size", "640x480", "--pip-x", "0.1", "--pip-y", "0.2", "--pip-width", "0.3", "--pip-height", "0.4"]
        named = self.request(["capture", "--output", "out", *options])
        generic = self.request(["run", "capture", *options])
        self.assertEqual(named["params"], generic["params"])
        self.assertEqual(named["params"]["options"]["pip_camera"], "2")
