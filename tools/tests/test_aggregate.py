import contextlib
import csv
import io
import json
import re
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import aggregate


def run(flags=(), schema=4):
    return {
        "schema_version": schema, "kind": "benchmark", "run_id": "20260910-120000-000",
        "profile": {"id": "camera2-standard-v1"}, "metric_definition_version": "metrics-0.3",
        "stats_method": "nearest_rank", "clock": "elapsedRealtimeNanos",
        "comparison_contract_id": "camera2-standard-v1|metrics-0.3|nearest_rank|elapsedRealtimeNanos",
        "validity": {"flags": list(flags), "scoring_eligible": True},
        "subject": {"build_label": 'SW,"42"\n한글', "commit": "=1+1"},
        "endpoint": {"logicalCameraId": "0", "physicalCameraId": None},
        "metrics": [{"id": "2.2", "value": 123.5, "p95": None, "n": 9, "timeout": False}],
    }


class AggregateTests(unittest.TestCase):
    def test_export_contract_matches_android_sources(self):
        # These implementations ship independently; fail CI when their shared contract drifts.
        domain = Path(__file__).resolve().parents[2] / "app/src/main/java/dev/halcamera/benchmark/domain"
        validity = (domain / "RunValidity.kt").read_text(encoding="utf-8")
        version = re.search(r'const val VERSION = "([^"]+)"', validity)
        self.assertIsNotNone(version)
        self.assertEqual(version.group(1), aggregate.VALIDITY_RULE_VERSION)
        flags = re.findall(r'ValidityFlag\("([A-Z_]+)", (true|false), (true|false), (true|false)\)', validity)
        self.assertTrue(flags, "ValidityFlag declarations were not found")
        self.assertEqual({f[0] for f in flags}, aggregate.KNOWN_FLAGS)
        for column, blockers in enumerate((aggregate.MEASUREMENT_BLOCKERS,
                                           aggregate.COMPARISON_BLOCKERS,
                                           aggregate.SCORING_BLOCKERS), start=1):
            self.assertEqual({f[0] for f in flags if f[column] == "true"}, blockers)
        csv_source = (domain / "BenchmarkCsv.kt").read_text(encoding="utf-8")
        for name, expected in (("runColumns", aggregate.RUN_COLUMNS), ("metricColumns", aggregate.METRIC_COLUMNS)):
            declaration = re.search(r'val ' + name + r' = listOf\((.*?)\)', csv_source, re.S)
            self.assertIsNotNone(declaration)
            self.assertEqual(re.findall(r'"([^"]+)"', declaration.group(1)), expected)
        codec = (domain / "BenchmarkReportCodec.kt").read_text(encoding="utf-8")
        readable = re.search(r'READABLE_SCHEMA_VERSIONS = (\d+)\.\.(\d+)', codec)
        self.assertIsNotNone(readable)
        first, last = map(int, readable.groups())
        for schema in range(first, last + 1):
            aggregate.normalize(run(schema=schema))
        with self.assertRaises(ValueError):
            aggregate.normalize(run(schema=first - 1))
        with self.assertRaises(ValueError):
            aggregate.normalize(run(schema=last + 1))

    def test_output_failure_is_fatal_and_not_reported_as_a_corrupt_input(self):
        class FailingWriter:
            def writerow(self, row):
                pass

            def writerows(self, rows):
                raise OSError("disk full")

        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            (folder / "valid.json").write_text(json.dumps(run()), encoding="utf-8")
            with patch.object(aggregate.csv, "writer", return_value=FailingWriter()):
                with self.assertRaisesRegex(OSError, "disk full"):
                    self.invoke(folder)

    def invoke(self, folder, *args):
        output, error = io.StringIO(newline=""), io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(error):
            status = aggregate.main([str(folder), *args])
        return status, list(csv.DictReader(io.StringIO(output.getvalue()))), error.getvalue()

    def test_flags_override_stored_booleans_in_all_readable_schemas(self):
        for schema in (3, 4, 5):
            for flag in ("CHARGING", "PROFILE_DRAFT", "DEBUGGABLE_BUILD", "RECORD_NOT_MEASURED"):
                normalized = aggregate.normalize(run([flag], schema))
                self.assertFalse(normalized["validity"]["scoring_eligible"])
                self.assertTrue(normalized["validity"]["comparison_eligible"])
        for flag in ("ABORTED", "THERMAL_HIGH", "FUTURE_FLAG"):
            self.assertFalse(aggregate.normalize(run([flag]))["validity"]["comparison_eligible"])

    def test_missing_rule_version_uses_current_rules_but_preserves_recorded_version(self):
        for missing in (None, 7, "", "null"):
            data = run()
            data["validity"]["validity_rule_version"] = missing
            self.assertEqual("validity-v3", aggregate.normalize(data)["validity"]["validity_rule_version"])
        data = run()
        data["validity"]["validity_rule_version"] = "validity-v2"
        self.assertEqual("validity-v2", aggregate.normalize(data)["validity"]["validity_rule_version"])

    def test_default_and_comparison_filter_with_round_trip_csv(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            for name, flags in (("clean", []), ("charging", ["CHARGING"]), ("bad", ["ABORTED"])):
                (folder / f"{name}.json").write_text(json.dumps(run(flags)), encoding="utf-8")
            (folder / "index.json").write_text("{}")
            status, rows, _ = self.invoke(folder)
            self.assertEqual(0, status)
            self.assertEqual(1, len(rows))
            self.assertEqual('SW,"42"\n한글', rows[0]["subject.build_label"])
            self.assertEqual("'=1+1", rows[0]["subject.commit"])
            self.assertEqual("123.5", rows[0]["metric.value"])
            self.assertEqual("", rows[0]["metric.p95"])
            self.assertEqual("false", rows[0]["metric.timeout"])
            self.assertEqual(2, len(self.invoke(folder, "--eligibility", "comparison_eligible")[1]))
            self.assertEqual(3, len(self.invoke(folder, "--eligibility", "all")[1]))
            self.assertEqual([], self.invoke(folder, "--endpoint", "1")[1])
            self.assertEqual([], self.invoke(folder, "--profile", "missing")[1])

    def test_corrupt_input_reports_partial_failure_but_preserves_valid_runs(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            (folder / "valid.json").write_text(json.dumps(run()), encoding="utf-8")
            (folder / "corrupt.json").write_text("{")
            (folder / "future.json").write_text(json.dumps(run(schema=6)))
            status, rows, error = self.invoke(folder)
            self.assertEqual(1, status)
            self.assertEqual(1, len(rows))
            self.assertIn("unreadable 2", error)

    def test_schema_five_recording_metrics_are_exported(self):
        data = run(schema=5)
        data["profile"] = {"id": "camera2-standard-v2", "record_duration_ms": 9000}
        data["comparison_contract_id"] = data["comparison_contract_id"].replace("standard-v1", "standard-v2")
        data["metrics"] = [{"id": "3.1", "value": 42.5, "n": 4, "timeout": False}]
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            (folder / "recording.json").write_text(json.dumps(data), encoding="utf-8")
            status, rows, error = self.invoke(folder)
            self.assertEqual(0, status, error)
            self.assertEqual(1, len(rows))
            self.assertEqual("3.1", rows[0]["metric.id"])
            self.assertEqual("42.5", rows[0]["metric.value"])
            self.assertEqual("camera2-standard-v2", rows[0]["profile.id"])

    def test_empty_metrics_retains_one_row_and_invalid_contract_is_rejected(self):
        data = run()
        data["metrics"] = []
        rows = list(aggregate.rows(aggregate.normalize(data)))
        self.assertEqual(1, len(rows))
        self.assertEqual(len(aggregate.HEADERS), len(rows[0]))
        data["comparison_contract_id"] = "wrong"
        with self.assertRaises(ValueError):
            aggregate.normalize(data)

    def test_never_overwrites_source_and_can_write_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            source = folder / "run.json"
            original = json.dumps(run())
            source.write_text(original)
            with self.assertRaises(SystemExit):
                self.invoke(folder, "-o", str(source))
            self.assertEqual(original, source.read_text())
            self.invoke(folder, "-o", str(folder / "out.csv"))
            with (folder / "out.csv").open(encoding="utf-8", newline="") as exported:
                self.assertEqual(1, len(list(csv.DictReader(exported))))

    def test_spreadsheet_formula_escaping_does_not_change_numeric_values(self):
        for value in ("=1", "+1", "-1", "@SUM(1)", "  =1", "\tfoo"):
            self.assertEqual("'" + value, aggregate.cell(value))
        self.assertEqual(-1.5, aggregate.cell(-1.5))


if __name__ == "__main__":
    unittest.main()
