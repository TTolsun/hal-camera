#!/usr/bin/env python3
"""Export schema 3/4 benchmark JSON files to the same metric-row CSV as the app."""

import argparse
import csv
import json
import sys
from pathlib import Path

RUN_COLUMNS = [
    "run_id", "exported_at_utc", "comparison_contract_id", "profile.id",
    "metric_definition_version", "stats_method", "clock", "regression_rule_version", "scoring_rule_version",
    "device.manufacturer", "device.model", "device.build_display", "device.fingerprint",
    "device.vendor_fingerprint", "device.camera_info_version", "app.version_name", "app.version_code", "app.debuggable",
    "subject.build_label", "subject.commit", "subject.branch", "subject.note",
    "endpoint.logicalCameraId", "endpoint.physicalCameraId", "endpoint.role",
    "env.thermal_start", "env.thermal_max", "env.thermal_end", "env.battery_start", "env.battery_end",
    "env.charging", "env.power_save_mode", "validity.measurement_valid", "validity.comparison_eligible",
    "validity.scoring_eligible", "validity.validity_rule_version", "validity.flags", "aborted",
]
METRIC_COLUMNS = ["id", "category", "unit", "value", "p50", "p95", "min", "max", "n", "timeout", "unknown_reason"]
HEADERS = RUN_COLUMNS + [f"metric.{key}" for key in METRIC_COLUMNS]

# Keep in sync with RunValidity.kt. Unknown flags fail closed for comparison/scoring.
MEASUREMENT_BLOCKERS = {"ABORTED", "HARD_FAILURE", "PROFILE_UNSUPPORTED", "INSUFFICIENT_SAMPLES"}
COMPARISON_BLOCKERS = MEASUREMENT_BLOCKERS | {"CADENCE_NOT_FIXED", "THERMAL_HIGH", "POWER_SAVE_MODE"}
SCORING_BLOCKERS = COMPARISON_BLOCKERS | {"CHARGING", "BATTERY_LOW", "PROFILE_DRAFT", "DEBUGGABLE_BUILD"}
KNOWN_FLAGS = SCORING_BLOCKERS | {"PREFLIGHT_MISMATCH", "THERMAL_CHANGED", "LABEL_MISSING"}


def normalize(run):
    """Validate the report envelope and derive eligibility from flags, as the app does."""
    if not isinstance(run, dict) or run.get("schema_version") not in (3, 4) or run.get("kind") != "benchmark":
        raise ValueError("expected benchmark schema 3 or 4")
    if not isinstance(run.get("run_id"), str) or not run["run_id"].strip():
        raise ValueError("missing run_id")
    components = [run.get("profile", {}).get("id"), run.get("metric_definition_version"),
                  run.get("stats_method"), run.get("clock")]
    if not all(isinstance(value, str) and value.strip() for value in components):
        raise ValueError("missing comparison contract component")
    if run.get("comparison_contract_id") != "|".join(components):
        raise ValueError("comparison_contract_id mismatch")
    validity = run.get("validity")
    if not isinstance(validity, dict) or not isinstance(validity.get("flags"), list):
        raise ValueError("missing validity flags")
    if not all(isinstance(flag, str) for flag in validity["flags"]):
        raise ValueError("invalid validity flag")
    flags = set(validity["flags"])
    unknown = flags - KNOWN_FLAGS
    validity.update(measurement_valid=not bool(flags & MEASUREMENT_BLOCKERS),
                    comparison_eligible=not bool(flags & COMPARISON_BLOCKERS or unknown),
                    scoring_eligible=not bool(flags & SCORING_BLOCKERS or unknown))
    validity["flags"] = list(dict.fromkeys(validity["flags"]))
    validity.setdefault("validity_rule_version", "validity-v2")
    metrics = run.get("metrics")
    if not isinstance(metrics, list) or any(not isinstance(m, dict) or not m.get("id") for m in metrics):
        raise ValueError("invalid metrics")
    return run


def at_path(run, path):
    value = run
    for key in path.split("."):
        value = value.get(key) if isinstance(value, dict) else None
    return value


def cell(value):
    if value is None:
        return ""
    if isinstance(value, bool):
        return str(value).lower()
    if isinstance(value, list):
        return ";".join(map(str, value))
    if isinstance(value, str) and (value.lstrip().startswith(("=", "+", "-", "@")) or value.startswith(("\t", "\r", "\n"))):
        return "'" + value
    return value


def rows(run):
    prefix = [cell(at_path(run, key)) for key in RUN_COLUMNS]
    for metric in run["metrics"] or [{}]:
        yield prefix + [cell(metric.get(key)) for key in METRIC_COLUMNS]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder", type=Path, help="Folder containing benchmark JSON files (index.json is ignored)")
    parser.add_argument("-o", "--output", type=Path, help="CSV output; stdout when omitted")
    parser.add_argument("--eligibility", choices=("scoring_eligible", "comparison_eligible", "all"), default="scoring_eligible")
    parser.add_argument("--profile", help="Restrict to a profile ID")
    parser.add_argument("--endpoint", help="Restrict to endpoint key, e.g. 0 or 0.2")
    args = parser.parse_args(argv)
    if not args.folder.is_dir():
        parser.error(f"not a directory: {args.folder}")
    sources = sorted((p for p in args.folder.glob("*.json") if p.name != "index.json"), reverse=True)
    if args.output and (args.output.suffix.lower() == ".json" or args.output.resolve() in {p.resolve() for p in sources}):
        parser.error("output must not overwrite a JSON source or index")
    output = args.output.open("w", encoding="utf-8", newline="") if args.output else sys.stdout
    exported = skipped = invalid = 0
    try:
        writer = csv.writer(output, lineterminator="\r\n")
        writer.writerow(HEADERS)
        for path in sources:
            try:
                run = normalize(json.loads(path.read_text(encoding="utf-8-sig"),
                                           parse_constant=lambda value: (_ for _ in ()).throw(ValueError(f"non-finite number: {value}"))))
                endpoint = run.get("endpoint", {})
                key = str(endpoint.get("logicalCameraId", ""))
                if endpoint.get("physicalCameraId") is not None:
                    key += "." + str(endpoint["physicalCameraId"])
                if ((args.eligibility != "all" and not run["validity"][args.eligibility]) or
                        (args.profile and run["profile"]["id"] != args.profile) or (args.endpoint and key != args.endpoint)):
                    skipped += 1
                    continue
                output_rows = list(rows(run))
            except (ValueError, TypeError, AttributeError, OSError) as exc:
                invalid += 1
                print(f"Skipped {path.name}: {exc}", file=sys.stderr)
                continue
            # Output failures are fatal; they do not make an input report corrupt.
            writer.writerows(output_rows)
            exported += 1
    finally:
        if args.output:
            output.close()
    print(f"Exported {exported} runs; filtered {skipped}; unreadable {invalid}.", file=sys.stderr)
    return 1 if invalid else 0


if __name__ == "__main__":
    raise SystemExit(main())
