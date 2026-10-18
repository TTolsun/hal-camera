"""The app's command specs (tests/fixtures/hello.json, checked by the app's CliCommandSpecTest) against this client."""
import json
from pathlib import Path
import unittest

from halcam.submission import (CAMERA_COMMANDS, DEFAULT_SECONDS, LEGACY, OPTION_KEYS, STREAM_KEYS, CONTROL_KEYS, PIP_KEYS,
                               spec)

FIXTURE = json.loads((Path(__file__).parent / "fixtures" / "hello.json").read_text(encoding="utf-8"))
SPECS = FIXTURE["command_specs"]


class SpecFixtureTests(unittest.TestCase):
    def test_legacy_lists_match_what_current_apks_advertise(self):
        # An APK without command_specs falls back to these lists, so they must say what the specs say.
        for field, names in LEGACY.items():
            with self.subTest(field=field):
                self.assertEqual(names, {name for name, item in SPECS.items() if item[field]})
        self.assertEqual(CAMERA_COMMANDS, {name for name, item in SPECS.items() if item["camera"]})
        self.assertEqual(DEFAULT_SECONDS, {name: item["default_timeout_ms"] // 1000 for name, item in SPECS.items()
                                           if item["default_timeout_ms"] != 30_000})
        for name in SPECS:
            with self.subTest(name=name):
                self.assertEqual(SPECS[name]["foreground"], spec({}, name, "foreground"))

    def test_every_advertised_option_has_a_flag(self):
        advertised = {key for item in SPECS.values() for key in item["options"]}
        self.assertEqual(set(), advertised - set(OPTION_KEYS))
        self.assertEqual(set(FIXTURE["stream_keys"]), set(STREAM_KEYS))
        self.assertEqual(set(CONTROL_KEYS), set(SPECS["capture"]["options"]) - set(PIP_KEYS))

    def test_advertised_fields_win_over_the_legacy_lists(self):
        hello = {"command_specs": {"capture": dict(SPECS["capture"], foreground=False)}}
        self.assertFalse(spec(hello, "capture", "foreground"))
        self.assertTrue(spec({}, "capture", "foreground"))
        # A field an older APK did not send yet still comes from the legacy list.
        older = {"command_specs": {"record.start": {key: value for key, value in SPECS["record.start"].items() if key != "recording"}}}
        self.assertTrue(spec(older, "record.start", "recording"))


if __name__ == "__main__":
    unittest.main()
