import glob
import json
import logging
import os
import shutil
import sys
import xml.etree.ElementTree as ET

from docopt import docopt

LOG_FORMAT = "%(asctime)s (%(name)s) [%(levelname)s] - %(message)s"
LOG = None

doc = """
This script generates report portal payload directory

    Usage:
        updateResultsDir.py --logs <PATH>
            (--results-dir <PATH>)
            [--results-summary <PATH>]
            [--backup <PATH>]

        updatePayloadDir.py --help

    Options:
        -h --help                   Shows the command usage
        --logs <PATH>               Logs directory
        --results-dir <PATH>         RP Payload directory
        --results-summary <PATH>    Result summary
        --backup <PATH>             Backup directory
"""

ATTACHMENTS = "attachments"
RESULTS = "results"
XUNIT = "xunit.xml"
SUMMARY = "summary.json"


def _set_logging_env():
    global LOG

    # Create and configure logger
    logging.basicConfig(format=LOG_FORMAT, level=logging.INFO)

    # Create log object
    LOG = logging.getLogger()

    # Set the threshold of LOG to DEBUG
    LOG.setLevel(logging.DEBUG)


def _get_testsuite_summary(attrib):
    # Get test suite details
    name, time = attrib.get("name"), float(attrib.get("time")) / 60

    # Get test suite count
    total, failed, skipped = (
        int(attrib.get("tests")),
        int(attrib.get("failures")),
        int(attrib.get("skipped")),
    )

    # Set test suite status
    status = "FAIL" if failed > 0 else "PASS"

    # Get passed test cases
    passed = total - (failed + skipped)

    # Create dict with details
    return dict(
        name=name,
        status=status,
        total=total,
        passed=passed,
        failed=failed,
        skipped=skipped,
        time=round(time),
    )


def _update_testcase_failure(testcase):
    # Get test case name
    _testcase = testcase.attrib.get("name").replace(" ", "_")
    for prop in testcase:
        if not (prop.tag == "failure" and prop.attrib.get("type") == "error"):
            continue

        # Update test case with failure details
        errors = glob.glob(os.path.join(logs, suite, f"{_testcase}*.err"))
        if not errors:
            LOG.error(f"No error file present for testcase '{_testcase}'")
            continue

        LOG.info(f"Updating errors for testcase '{_testcase}' with error '{errors[0]}'")
        prop.text = ""
        with open(errors[0], "r") as _f:
            for _line in _f.readlines():
                _line = (
                    _line.replace("\033[95m", "")
                    .replace("\033[92m", "")
                    .replace("\033[91m", "")
                    .replace("\033[0m", "")
                    .replace("\033[1m", "")
                    .strip()
                )
                prop.text += f"\n{_line}"


def generate_results(results, logs, suite):
    result = None

    # Check for xUnit file
    source = os.path.join(logs, suite, XUNIT)
    if not os.path.exists(source):
        LOG.info(
            f"No xUnit file generated for test suite {suite},"
            " returning status as 'FAIL'"
        )
        return dict(
            name=suite,
            status="FAIL",
            total=0,
            passed=0,
            failed=0,
            skipped=0,
            time=0,
        )

    # Create xUnit specific to test suite file
    root = ET.parse(source).getroot()
    for child in root:
        # Check for test suite tag
        if not child.tag == "testsuite":
            continue

        # Get test suite results summary
        result = _get_testsuite_summary(child.attrib)
        comments = []

        # Only capture testcase results for BVT suites
        testcase_results = []
        is_bvt = "build_verfification_test" in suite.lower()
        LOG.info(f"Is BVT suite: {is_bvt}")

        for testcase in child:
            # Check for test case details
            if not testcase.tag == "testcase":
                continue

            # Update test case failure
            _update_testcase_failure(testcase)

            # get comments from testcase
            for prop in testcase.findall(".//property"):
                if prop.get("name") == "comments":
                    comments.append(prop.get("value"))

            # NEW: Build per-test result only for BVT
            if is_bvt:
                tc_name = testcase.attrib.get("name")
                tc_time = float(testcase.attrib.get("time", 0.0))

                failure_tag = testcase.find("failure")
                status = "Failed" if failure_tag is not None else "Passed"

                testcase_results.append(
                    {"name": tc_name, "status": status, "time": tc_time}
                )
        result["comments"] = ", ".join(comments) if comments else ""

        # Attach testcase details only for BVT suites
        if is_bvt:
            result["testcases"] = testcase_results
    # Update testsuite to result
    xunit = os.path.join(results, f"{os.path.basename(suite)}.xml")
    LOG.info(f"Updating xunit results for '{xunit}'")

    with open(xunit, "wb") as _f:
        _f.write(ET.tostring(root))

    return result


def geterate_attachments(attachments, logs, suite):
    # Create attachment directory
    attachment = os.path.join(attachments, suite)
    try:
        os.mkdir(attachment)
    except FileExistsError:
        LOG.info(f"Attachment {attachment} is already present, skipping ....")

    # Creating test directories inside test suite path with logs and err files
    suite_path = os.path.join(logs, suite)
    for test in [f for f in os.listdir(suite_path) if f.endswith(".log")]:
        path = os.path.join(attachments, suite, test)
        _file, _ = os.path.splitext(os.path.basename(path))

        # Check for error, log file and it's size
        err_file = os.path.join(logs, suite, f"{_file}.err")
        log_file = os.path.join(logs, suite, f"{_file}.log")
        if not (
            os.path.exists(log_file)
            and os.path.exists(err_file)
            and os.stat(err_file).st_size
        ):
            continue

        # Copy error file to attachments
        path = os.path.join(attachments, suite, _file)

        LOG.info(f"Creating suite directory '{path}'")
        try:
            os.mkdir(path)
        except FileExistsError:
            LOG.info(f"Suite {path} is already present, skipping ....")

        LOG.info(f"Copying error '{err_file}' and log '{log_file}' files")
        try:
            shutil.copy(err_file, path)
        except FileExistsError:
            LOG.info(f"Error {err_file} file already present, skipping ....")

        try:
            shutil.copy(log_file, path)
        except FileExistsError:
            LOG.info(f"Logs {log_file} file already present, skipping ....")


def generate_payload_directories(results_dir):
    # Create attachment and results directory
    attachments = os.path.join(results_dir, ATTACHMENTS)
    results = os.path.join(results_dir, RESULTS)

    # Check if attachments directory is present
    if os.path.exists(results_dir):
        LOG.info(f"Results '{results_dir}' directory already present")
    else:
        LOG.info(f"Creating results '{results_dir}' directory")
        os.mkdir(results_dir)

    if os.path.exists(attachments):
        LOG.info(f"Attachment '{attachments}' directory already present")
    else:
        LOG.info(f"Creating attachment '{attachments}' directory")
        os.mkdir(attachments)

    if os.path.exists(results):
        LOG.info(f"Attachment '{results}' directory already present")
    else:
        LOG.info(f"Creating results '{results}' directory")
        os.mkdir(results)

    return attachments, results


if __name__ == "__main__":
    # Set logging enviroment
    _set_logging_env()

    # Describe script parameters
    args = docopt(doc)

    # Get script parameters
    results_dir = args.get("--results-dir")
    logs = args.get("--logs")
    summary = args.get("--results-summary")
    backup = args.get("--backup")

    # Set results summary path
    summary = summary if summary else os.path.join(results_dir, SUMMARY)
    LOG.info(f"Results summary path - {summary}")

    # remove tmp file from log path
    LOG.info("Removing all temp files")
    [
        shutil.rmtree(os.path.join(logs, d), ignore_errors=False)
        for d in os.listdir(logs)
        if d.endswith("tmp")
    ]

    # Generate payload directories
    attachments, results = generate_payload_directories(results_dir)

    # Get suites list from logs directory
    suites = [
        suite
        for suite in os.listdir(logs)
        if not (suite.endswith(".log") or suite.endswith(".yaml"))
    ]
    if not suites:
        LOG.info("No test suites are present, exiting ....")
        sys.exit(0)

    # Generate report portal payloads and result summary
    result = []
    for suite in suites:
        LOG.info(f"Generating attachments and results for suite '{suite}'")
        geterate_attachments(attachments, logs, suite)
        _result = generate_results(results, logs, suite)
        result.append(_result)

    # Write output to file
    with open(summary, "w") as fp:
        LOG.info(f"Updating results summary to '{summary}'")
        json.dump(result, fp)

    # Exit if backup options is not set
    if not backup:
        sys.exit(0)

    # Copy logs to backup path
    try:
        LOG.info(f"Copying all logs '{logs}' to '{backup}'")
        shutil.copytree(logs, backup, dirs_exist_ok=True)
    except FileExistsError:
        LOG.info(f"Logs {logs} are already present, skipping ....")
