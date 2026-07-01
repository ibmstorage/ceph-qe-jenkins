#!/usr/bin/env python3
"""
Validate metadata YAML files under metadata/<release>/<fg>.yaml.

Checks performed:
  - Valid YAML syntax
  - Top-level key is a recognised job type
  - Required fields (suite, global-conf) are non-empty strings
  - build/platform/cloud/vm-size values are drawn from allowed sets
  - global-conf and suite paths do not reference a different Ceph release
    (e.g. squid paths in tentacle metadata)
  - No duplicate suite names within the same job section

Exit code: 0 if all checks pass, 1 if any error is found.
Warnings are printed but do not fail the check.
"""

import argparse
import os
import sys
import yaml


# ---------------------------------------------------------------------------
# Known-good value sets
# ---------------------------------------------------------------------------

VALID_JOB_KEYS = {
    "bvt",
    "sanity",
    "regression",
    "extended_regression",
    "extended_regression_1",
    "extended_regression_2",
    "upgrade",
    "sanity-upgrade",
    "released",
}

VALID_BUILDS = {"rh", "ibm", "upstream"}
VALID_CLOUDS = {"ibmc", "aws"}
VALID_PLATFORMS = {"rhel-8", "rhel-9", "rhel-10"}

# Release names that appear in file paths (e.g. conf/squid/…)
RELEASE_PATH_NAMES = {"pacific", "quincy", "reef", "squid", "tentacle", "main"}


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _release_from_path(file_path: str) -> str:
    """Return the Ceph release name inferred from the file's directory."""
    # e.g. metadata/tentacle/nvmeof.yaml  →  "tentacle"
    parts = file_path.replace("\\", "/").split("/")
    for part in parts:
        if part in RELEASE_PATH_NAMES:
            return part
    return ""


def _cross_release_refs(value: str, own_release: str) -> list[str]:
    """Return any release names found in *value* that are not *own_release*."""
    if not value or not own_release:
        return []
    return [r for r in RELEASE_PATH_NAMES if r != own_release and f"/{r}/" in value]


# ---------------------------------------------------------------------------
# Validation logic
# ---------------------------------------------------------------------------


def validate_file(file_path: str) -> tuple[list[str], list[str]]:
    """Validate a single metadata YAML file.

    Returns (errors, warnings) where each item is a human-readable message
    that already includes the file path and suite name for context.
    """
    errors: list[str] = []
    warnings: list[str] = []
    own_release = _release_from_path(file_path)

    # ---- YAML parse --------------------------------------------------------
    try:
        with open(file_path, encoding="utf-8") as fh:
            data = yaml.safe_load(fh)
    except yaml.YAMLError as exc:
        errors.append(f"{file_path}: YAML parse error: {exc}")
        return errors, warnings
    except OSError as exc:
        errors.append(f"{file_path}: cannot open file: {exc}")
        return errors, warnings

    if not isinstance(data, dict):
        errors.append(
            f"{file_path}: top-level value must be a mapping, got {type(data).__name__}"
        )
        return errors, warnings

    # ---- Per job-key -------------------------------------------------------
    for job_key, job_data in data.items():
        loc = f"{file_path}#{job_key}"

        if job_key not in VALID_JOB_KEYS:
            warnings.append(
                f"{loc}: unrecognised job key {job_key!r} (expected one of {sorted(VALID_JOB_KEYS)})"
            )

        if not isinstance(job_data, dict):
            errors.append(
                f"{loc}: job section must be a mapping, got {type(job_data).__name__}"
            )
            continue

        # ---- metadata section ----------------------------------------------
        meta = job_data.get("metadata", {}) or {}
        for cloud in meta.get("cloud", []):
            if cloud not in VALID_CLOUDS:
                errors.append(
                    f"{loc}/metadata: unknown cloud {cloud!r} (allowed: {sorted(VALID_CLOUDS)})"
                )

        # ---- suites section ------------------------------------------------
        suites = job_data.get("suites") or []
        if not isinstance(suites, list):
            errors.append(f"{loc}: 'suites' must be a list")
            continue

        seen_names: set[str] = set()

        for idx, suite in enumerate(suites):
            if not isinstance(suite, dict):
                errors.append(f"{loc}/suites[{idx}]: suite entry must be a mapping")
                continue

            name = suite.get("name") or f"suite[{idx}]"
            sloc = f'{loc}/"{name}"'

            # Duplicate names
            if name in seen_names:
                errors.append(f"{sloc}: duplicate suite name within this job section")
            seen_names.add(name)

            # Required fields
            suite_path = suite.get("suite") or ""
            global_conf = suite.get("global-conf") or ""

            if not suite_path:
                errors.append(f"{sloc}: missing required field 'suite'")
            if not global_conf:
                errors.append(f"{sloc}: missing required field 'global-conf'")

            # Cross-release path references
            for field, value in [("suite", suite_path), ("global-conf", global_conf)]:
                wrong = _cross_release_refs(value, own_release)
                for bad_release in wrong:
                    # Treat cross-release global-conf as an error;
                    # cross-release suite path is a warning (may be intentional).
                    msg = (
                        f"{sloc}: '{field}' references {bad_release!r} path inside "
                        f"{own_release!r} metadata: {value}"
                    )
                    if field == "global-conf":
                        errors.append(msg)
                    else:
                        warnings.append(msg)

            # build values
            for build in suite.get("build", []):
                if build not in VALID_BUILDS:
                    errors.append(
                        f"{sloc}: unknown build type {build!r} (allowed: {sorted(VALID_BUILDS)})"
                    )

            # platform values
            for platform in suite.get("platform", []):
                if platform not in VALID_PLATFORMS:
                    errors.append(
                        f"{sloc}: unknown platform {platform!r} (allowed: {sorted(VALID_PLATFORMS)})"
                    )

            # vm-size cloud keys
            vm_size = suite.get("vm-size")
            if isinstance(vm_size, dict):
                for cloud_key in vm_size:
                    if cloud_key not in VALID_CLOUDS:
                        errors.append(
                            f"{sloc}: unknown cloud {cloud_key!r} in 'vm-size' "
                            f"(allowed: {sorted(VALID_CLOUDS)})"
                        )

    return errors, warnings


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "files",
        nargs="*",
        help="Metadata YAML files to validate. Defaults to all files under metadata/.",
    )
    parser.add_argument(
        "--warn-only",
        action="store_true",
        help="Print errors as warnings and always exit 0 (useful for auditing).",
    )
    args = parser.parse_args(argv)

    if args.files:
        targets = args.files
    else:
        targets = []
        for root, _dirs, files in os.walk("metadata"):
            for fname in files:
                if fname.endswith(".yaml"):
                    targets.append(os.path.join(root, fname))

    all_errors: list[str] = []
    all_warnings: list[str] = []

    for path in sorted(targets):
        errs, warns = validate_file(path)
        all_errors.extend(errs)
        all_warnings.extend(warns)

    for msg in all_warnings:
        print(f"WARN  {msg}", file=sys.stderr)

    for msg in all_errors:
        label = "WARN " if args.warn_only else "ERROR"
        print(f"{label} {msg}", file=sys.stderr)

    if all_errors:
        count = len(all_errors)
        noun = "error" if count == 1 else "errors"
        if args.warn_only:
            print(
                f"validate_metadata: {count} {noun} found (--warn-only, exiting 0)",
                file=sys.stderr,
            )
            return 0
        print(f"validate_metadata: {count} {noun} found", file=sys.stderr)
        return 1

    print(
        f"validate_metadata: all {len(targets)} file(s) passed "
        f"({len(all_warnings)} warning(s))",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
