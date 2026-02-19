"""
xunit2gsheet — Parse test XMLs and update Google Sheet

Sheet Structure:
  The script maintains four worksheets in the Google Sheet:
    1. Properties - Metadata about test runs (build, version, etc.)
    2. Summary - Aggregated test suite results (pass/fail counts)
    3. Test Case Status - Individual test case statuses
    4. Test Case Timings - Individual test case execution times

Features:
  - Automatic column width adjustment based on content
  - Automatic component detection from suite names
  - Color-coded status columns (Passed/Failed/Error/Skipped)
  - Version-aware sorting of test results (newest first)
  - Smart merging of new results with existing data
  - Automatic spreadsheet renaming with build number

Note:
  - The script preserves existing data while adding new version columns
  - Test cases are matched by name and polarion-testcase-id when available
  - First run creates the worksheets if they don't exist

Service Account Setup:
  1. Create service account in Google Cloud Console
  2. Download JSON credentials file as 'service_account.json'
  3. Share your Google Sheet with the service account email
  4. Place JSON file in the same directory as this script

Reference:
  Google Sheets API documentation:
  https://developers.google.com/sheets/api/guides/concepts

Usage:
  xunit2gsheet.py --version <ver> --resultdir <path> --gsheet <name> --service-account <path_to_credentials>
  xunit2gsheet.py (-h | --help)

Options:
  -v --version <ver>                          Ceph version (e.g. 19.2.0-190).
  -d --resultdir <path>                       Path to logs directory.
  -s --gsheet <name>                          Google Sheet name to update.
  -a --service-account <path>                 Path to Google service account JSON file.
  -h --help                                   Show this help message.
"""

import os
import sys
import time
import xml.etree.ElementTree as ET

import gspread
from docopt import docopt
from google.oauth2.service_account import Credentials
from googleapiclient.discovery import build
from gspread_formatting import (cellFormat, color, format_cell_range,
                                format_cell_ranges, set_frozen, textFormat)

# --- Constants ---
SHEET_PROPERTIES = "Properties"
SHEET_SUMMARY = "Summary"
SHEET_TEST_STATUS = "Test Case Status"
SHEET_TEST_TIMINGS = "Test Case Timings"

# Magna log-server base URL
MAGNA_BASE_URL = "http://magna002.ceph.redhat.com/cephci-jenkins/ibm-cos/"
JENKINS_LOG_DIR_PREFIX = "/data/jenkins/ceph-builds/"


def get_gsheet_client(credentials_path):
    """
    Authenticates with Google Sheets API using service account credentials.
    Args:
        credentials_path (str): Path to service account JSON credentials file
    Returns:
        gspread.Client: Authenticated Google Sheets client
    """
    scopes = [
        "https://www.googleapis.com/auth/spreadsheets",
        "https://www.googleapis.com/auth/drive",
    ]
    creds = Credentials.from_service_account_file(credentials_path, scopes=scopes)
    return gspread.authorize(creds)


def parse_ceph_version(version_str):
    """
    Parse Ceph version string (e.g., "20.0.1-178") into comparable tuple.
    Returns tuple (major, minor, patch, build) for comparison.
    """
    try:
        parts = version_str.split("-", 1)
        version_part = parts[0]
        build_part = int(parts[1]) if len(parts) > 1 else 0

        version_nums = [int(x) for x in version_part.split(".")]
        while len(version_nums) < 3:
            version_nums.append(0)

        return (*version_nums, build_part)
    except (ValueError, IndexError):
        return (0, 0, 0, 0)


def get_or_create_worksheet(spreadsheet, sheet_name):
    """
    Gets a worksheet by name or creates it if it doesn't exist.
    Args:
        spreadsheet (gspread.Spreadsheet): The parent spreadsheet
        sheet_name (str): Name of worksheet to get/create
    Returns:
        gspread.Worksheet: The requested worksheet
    """
    try:
        worksheet = spreadsheet.worksheet(sheet_name)
    except gspread.exceptions.WorksheetNotFound:
        worksheet = spreadsheet.add_worksheet(title=sheet_name, rows="100", cols="20")
    return worksheet


def parse_xml_properties(xml_root, ceph_version):
    """
    Extracts all <property> name/value pairs from an XML file.
    Args:
        xml_file (str): Path to the xUnit XML file
        ceph_version (str): Ceph version
    """
    properties = {}

    for prop in xml_root.findall(".//property"):
        name = prop.get("name")
        value = prop.get("value", "")
        if name:
            if name == "ceph-version" and "--NA--" in value:
                value = ceph_version
            properties[name.strip()] = value.strip()
    return properties


def parse_testsuite_stats(xml_root):
    """Extract stats for each <testsuite> in the XML."""
    stats = []

    for suite in xml_root.findall(".//testsuite"):
        name = suite.get("name", "")
        total = int(suite.get("tests", 0))
        failures = int(suite.get("failures", 0))
        errors = int(suite.get("errors", 0))
        skipped = int(suite.get("skipped", 0))
        time_sec = float(suite.get("time", 0))
        passed_count = total - (failures + errors + skipped)
        success_rate_float = (passed_count / total * 100) if total > 0 else 0.0
        success_rate_str = f"{success_rate_float:.2f}%"

        stats.append(
            {
                "Test Suite Name": name,
                "Total Tests": total,
                "Passed": passed_count,
                "Failures": failures,
                "Errors": errors,
                "Skipped": skipped,
                "Success Rate": success_rate_str,
                "Time (seconds)": time_sec,
            }
        )
    return stats


def find_tag_case_insensitive(element, tag_name):
    """
    Find XML tag case-insensitively.
    Returns the element if found, None otherwise.
    """
    for variant in [tag_name.lower(), tag_name.capitalize(), tag_name.upper()]:
        tag = element.find(variant)
        if tag is not None:
            return tag
    return None


def parse_testcases(xml_root):
    """Extract each test case name, its Polarion ID, and status, execution time per test suite."""
    statuses, timings = [], []

    for suite in xml_root.findall(".//testsuite"):
        suite_name = suite.get("name", "")

        # Extract component from suite-name property
        component = "BVT"  # Default component
        suite_name_prop = suite.find(".//property[@name='suite-name']")
        if suite_name_prop is not None:
            suite_name_value = suite_name_prop.get("value", "").strip()
            if suite_name_value and suite_name_value.endswith(".yaml"):
                normalized_path = os.path.normpath(suite_name_value)
                path_parts = normalized_path.split(os.sep)
                if len(path_parts) >= 2:
                    dir_name = path_parts[-2]
                    component = dir_name.upper()

        # Extract log-dir and build Magna log-server URL for hyperlinks
        magna_log_url = ""
        log_dir_prop = suite.find(".//property[@name='log-dir']")
        if log_dir_prop is not None:
            log_dir = log_dir_prop.get("value", "").strip()
            # Convert log-dir path to Magna URL
            if log_dir.startswith(JENKINS_LOG_DIR_PREFIX):
                relative_path = log_dir[len(JENKINS_LOG_DIR_PREFIX):]
                magna_log_url = MAGNA_BASE_URL + relative_path.rstrip("/") + "/"
            # else:
            #     magna_log_url = MAGNA_BASE_URL + log_dir.lstrip("/").rstrip("/") + "/"

        for case in suite.findall(".//testcase"):
            testcase_name = case.get("name", "")

            # Default values
            polarion_id = ""
            status = "Passed"

            # Get Polarion ID if exists
            prop = case.find(".//property[@name='polarion-testcase-id']")
            if prop is not None:
                polarion_id = prop.get("value", "").strip()

            # Determine test status (check in priority order: failure/error > skipped > passed)
            failure_tag = find_tag_case_insensitive(case, "failure")
            error_tag = find_tag_case_insensitive(case, "error")
            skipped_tag = find_tag_case_insensitive(case, "skipped")

            if failure_tag is not None or error_tag is not None:
                status = "Failed"
            elif skipped_tag is not None:
                status = "Skipped"
            else:
                status = "Passed"

            # Create status with hyperlink if Magna log URL is available
            status_value = status
            if magna_log_url:
                # Create HYPERLINK formula for Google Sheets with Magna log-server URL
                status_value = f'=HYPERLINK("{magna_log_url}", "{status}")'

            statuses.append(
                {
                    "Test Suite Name": suite_name,
                    "Component": component,
                    "Test Case Name": testcase_name,
                    "polarion-testcase-id": polarion_id,
                    "Status": status_value,
                }
            )

            # Get time taken (float)
            try:
                time_taken = f"{float(case.get('time', 0)):.2f}"
            except ValueError:
                time_taken = 0.0
            timings.append(
                {
                    "Test Suite Name": suite_name,
                    "Component": component,
                    "Test Case Name": testcase_name,
                    "Status": status,
                    "Time (seconds)": time_taken,
                }
            )
    return statuses, timings


def _auto_resize_columns(worksheet):
    """Auto-resize columns in a worksheet using Google Sheets API."""
    values = worksheet.get_all_values()
    if not values:
        return

    num_cols = len(values[0])
    creds = worksheet.spreadsheet.client.auth
    service = build("sheets", "v4", credentials=creds)
    sheet_id = worksheet.id

    request_body = {
        "requests": [
            {
                "autoResizeDimensions": {
                    "dimensions": {
                        "sheetId": sheet_id,
                        "dimension": "COLUMNS",
                        "startIndex": 0,
                        "endIndex": num_cols,
                    }
                }
            }
        ]
    }

    service.spreadsheets().batchUpdate(
        spreadsheetId=worksheet.spreadsheet.id, body=request_body
    ).execute()


def _apply_status_color_formatting(worksheet):
    """
    Apply color formatting to version columns in the worksheet.
    - Green for 'Passed'
    - Red for 'Failed'
    - White (default) for anything else
    Optimized for large datasets using batch API calls.
    """
    # Fetch all values
    values = worksheet.get_all_values()
    if not values:
        return

    header = values[0]
    num_rows = len(values)

    # Identify version columns (these contain status values)
    # Version columns are those that don't start with standard headers
    standard_headers = [
        "Test Suite Name",
        "Test Case Name",
        "Component",
        "polarion-testcase-id",
    ]
    version_cols = []

    for idx, col in enumerate(header):
        col_stripped = col.strip()
        # If column doesn't match standard headers, it's likely a version column
        if (
            col_stripped not in standard_headers
            and not col_stripped.startswith("Status")
            and not col_stripped.startswith("Time")
        ):
            version_cols.append(idx)

    if not version_cols:
        return  # No version columns to format

    # Use Google Sheets API directly for efficient conditional formatting
    try:
        service = build("sheets", "v4", credentials=worksheet.spreadsheet.client.auth)
        sheet_id = worksheet.id

        requests = []

        # First, clear all existing conditional formatting for the Status columns
        # Get existing conditional format rules to delete them
        try:
            spreadsheet = (
                service.spreadsheets()
                .get(spreadsheetId=worksheet.spreadsheet.id)
                .execute()
            )
            for sheet in spreadsheet.get("sheets", []):
                if sheet.get("properties", {}).get("sheetId") == sheet_id:
                    conditional_format_rules = sheet.get("conditionalFormatRules", [])
                    # Delete all existing rules for this sheet
                    for idx in range(len(conditional_format_rules)):
                        requests.append(
                            {
                                "deleteConditionalFormatRule": {
                                    "index": idx,
                                    "sheetId": sheet_id,
                                }
                            }
                        )
        except Exception as e:
            print(f"Warning: Could not clear existing conditional formatting: {e}")

        # Add conditional formatting rules for better performance
        # This creates a single batch request with all rules
        # Priority order: Passed (green) > Failed (red) > Skipped (orange) > Blank (white)
        for col_idx in version_cols:

            # Green formatting rule for "Passed"
            requests.append(
                {
                    "addConditionalFormatRule": {
                        "index": 0,
                        "rule": {
                            "ranges": [
                                {
                                    "sheetId": sheet_id,
                                    "startRowIndex": 1,  # Skip header
                                    "endRowIndex": num_rows,
                                    "startColumnIndex": col_idx,
                                    "endColumnIndex": col_idx + 1,
                                }
                            ],
                            "booleanRule": {
                                "condition": {
                                    "type": "TEXT_CONTAINS",
                                    "values": [{"userEnteredValue": "Passed"}],
                                },
                                "format": {
                                    "backgroundColor": {
                                        "red": 0.85,
                                        "green": 0.92,
                                        "blue": 0.83,
                                    }
                                },
                            },
                        },
                    }
                }
            )

            # Red formatting rule for "Failed" (includes both failure and error tags)
            requests.append(
                {
                    "addConditionalFormatRule": {
                        "index": 1,
                        "rule": {
                            "ranges": [
                                {
                                    "sheetId": sheet_id,
                                    "startRowIndex": 1,
                                    "endRowIndex": num_rows,
                                    "startColumnIndex": col_idx,
                                    "endColumnIndex": col_idx + 1,
                                }
                            ],
                            "booleanRule": {
                                "condition": {
                                    "type": "TEXT_CONTAINS",
                                    "values": [{"userEnteredValue": "Failed"}],
                                },
                                "format": {
                                    "backgroundColor": {
                                        "red": 0.96,
                                        "green": 0.8,
                                        "blue": 0.8,
                                    }
                                },
                            },
                        },
                    }
                }
            )

            # Orange formatting rule for "Skipped"
            requests.append(
                {
                    "addConditionalFormatRule": {
                        "index": 2,
                        "rule": {
                            "ranges": [
                                {
                                    "sheetId": sheet_id,
                                    "startRowIndex": 1,
                                    "endRowIndex": num_rows,
                                    "startColumnIndex": col_idx,
                                    "endColumnIndex": col_idx + 1,
                                }
                            ],
                            "booleanRule": {
                                "condition": {
                                    "type": "TEXT_CONTAINS",
                                    "values": [{"userEnteredValue": "Skipped"}],
                                },
                                "format": {
                                    "backgroundColor": {
                                        "red": 1.0,
                                        "green": 0.65,
                                        "blue": 0.0,
                                    }
                                },
                            },
                        },
                    }
                }
            )

            # White formatting rule for empty cells (lowest priority - acts as default)
            # This ensures cells become white when text is removed
            requests.append(
                {
                    "addConditionalFormatRule": {
                        "index": 3,
                        "rule": {
                            "ranges": [
                                {
                                    "sheetId": sheet_id,
                                    "startRowIndex": 1,
                                    "endRowIndex": num_rows,
                                    "startColumnIndex": col_idx,
                                    "endColumnIndex": col_idx + 1,
                                }
                            ],
                            "booleanRule": {
                                "condition": {"type": "BLANK"},
                                "format": {
                                    "backgroundColor": {
                                        "red": 1.0,
                                        "green": 1.0,
                                        "blue": 1.0,
                                    }
                                },
                            },
                        },
                    }
                }
            )

        # Execute batch update
        if requests:
            service.spreadsheets().batchUpdate(
                spreadsheetId=worksheet.spreadsheet.id, body={"requests": requests}
            ).execute()

    except Exception as e:
        print(f"Warning: Failed to apply conditional formatting: {e}")


def update_worksheet(spreadsheet_obj, sheet_name, data, columns_order=None):
    """Write extracted XML properties into Google Sheet."""
    try:
        worksheet = get_or_create_worksheet(spreadsheet_obj, sheet_name)
        # Determine header
        if columns_order:
            header = columns_order
        else:
            header = sorted({key for row in data for key in row.keys()})

        # Prepare data rows
        rows = [[rd.get(k, "") for k in header] for rd in data]
        # Clear and rewrite sheet
        worksheet.clear()
        worksheet.append_row(header)
        worksheet.append_rows(rows)

        # Make header row bold
        header_format = cellFormat(textFormat=textFormat(bold=True))
        format_cell_range(worksheet, "1:1", header_format)
        _auto_resize_columns(worksheet)
        print(f"Worksheet '{sheet_name}' updated successfully...")
    except Exception as e:
        print(f"Failed to update worksheet '{sheet_name}': {e}")


def _read_sheet_data(worksheet, sheet_name, value_type):
    """Read existing sheet data and formulas (if Status sheet)."""
    try:
        all_values = worksheet.get_all_values()
        all_formulas = []
        if value_type == "Status":
            try:
                service = build(
                    "sheets", "v4", credentials=worksheet.spreadsheet.client.auth
                )
                result = (
                    service.spreadsheets()
                    .values()
                    .get(
                        spreadsheetId=worksheet.spreadsheet.id,
                        range=f"'{worksheet.title}'!A:ZZ",
                        valueRenderOption="FORMULA",
                    )
                    .execute()
                )
                all_formulas = result.get("values", [])
            except Exception as e:
                print(f"Warning: Could not read formulas from {sheet_name}: {e}")
                all_formulas = []
    except Exception as e:
        print(f"Warning: Could not read existing data from {sheet_name}: {e}")
        all_values = []
        all_formulas = []

    header = all_values[0] if all_values and len(all_values[0]) > 0 else []
    existing_rows = all_values[1:] if all_values and len(all_values) > 1 else []
    existing_formulas = (
        all_formulas[1:] if all_formulas and len(all_formulas) > 1 else []
    )

    return header, existing_rows, existing_formulas


def _ensure_base_headers(header, value_type):
    """Ensure required base headers exist in header list."""
    required_headers = ["Test Suite Name", "Test Case Name", "Component"]
    if value_type == "Status":
        required_headers.append("polarion-testcase-id")

    required_headers_set = set(required_headers)
    header_set = set(header)
    for col in required_headers:
        if col not in header_set:
            header.append(col)
            header_set.add(col)

    return required_headers, required_headers_set


def _insert_version_column(
    header,
    existing_rows,
    existing_formulas,
    version_col_name,
    required_headers,
    value_type,
):
    """Insert or move version column to correct position."""
    insert_base_idx = len(required_headers)
    header_indices = {col: idx for idx, col in enumerate(header)}

    if version_col_name in header_indices:
        old_idx = header_indices[version_col_name]
        moved_header = header.pop(old_idx)
        header_indices = {
            col: (idx if idx < old_idx else idx - 1)
            for col, idx in header_indices.items()
            if col != moved_header
        }

        moved_col_data = [
            row.pop(old_idx) if len(row) > old_idx else "" for row in existing_rows
        ]
        moved_formula_data = []
        if value_type == "Status" and existing_formulas:
            moved_formula_data = [
                formula_row.pop(old_idx) if len(formula_row) > old_idx else ""
                for formula_row in existing_formulas
            ]

        header.insert(insert_base_idx, moved_header)
        for col_name, idx in header_indices.items():
            if idx >= insert_base_idx:
                header_indices[col_name] = idx + 1
        header_indices[moved_header] = insert_base_idx

        for r_idx, row in enumerate(existing_rows):
            row.insert(insert_base_idx, moved_col_data[r_idx])
        if value_type == "Status" and existing_formulas:
            for r_idx, formula_row in enumerate(existing_formulas):
                if r_idx < len(moved_formula_data):
                    formula_row.insert(insert_base_idx, moved_formula_data[r_idx])
                else:
                    formula_row.insert(insert_base_idx, "")
    else:
        header.insert(insert_base_idx, version_col_name)
        header_indices[version_col_name] = insert_base_idx
        for col_name, idx in header_indices.items():
            if idx >= insert_base_idx:
                header_indices[col_name] = idx + 1
        for row in existing_rows:
            row.insert(insert_base_idx, "")
        if value_type == "Status" and existing_formulas:
            for formula_row in existing_formulas:
                formula_row.insert(insert_base_idx, "")

    return insert_base_idx


def _reorder_version_columns(
    header,
    existing_rows,
    existing_formulas,
    required_headers_set,
    insert_base_idx,
    value_type,
):
    """Reorder all version columns so newest is first."""
    version_columns = []
    for idx, col_name in enumerate(header):
        if col_name not in required_headers_set:
            version_columns.append((idx, col_name))

    if len(version_columns) > 1:
        version_data = {}
        version_formula_data = {}
        for old_idx, col_name in version_columns:
            version_data[col_name] = [
                row[old_idx] if old_idx < len(row) else "" for row in existing_rows
            ]
            if value_type == "Status" and existing_formulas:
                version_formula_data[col_name] = [
                    formula_row[old_idx] if old_idx < len(formula_row) else ""
                    for formula_row in existing_formulas
                ]

        version_columns_sorted = sorted(
            version_columns, key=lambda x: parse_ceph_version(x[1]), reverse=True
        )

        for old_idx, col_name in sorted(
            version_columns, reverse=True, key=lambda x: x[0]
        ):
            header.pop(old_idx)
            for row in existing_rows:
                if old_idx < len(row):
                    row.pop(old_idx)
            if value_type == "Status" and existing_formulas:
                for formula_row in existing_formulas:
                    if old_idx < len(formula_row):
                        formula_row.pop(old_idx)

        for new_pos, (_, col_name) in enumerate(version_columns_sorted):
            insert_pos = insert_base_idx + new_pos
            header.insert(insert_pos, col_name)
            for row_idx, row in enumerate(existing_rows):
                col_data = (
                    version_data[col_name][row_idx]
                    if row_idx < len(version_data[col_name])
                    else ""
                )
                row.insert(insert_pos, col_data)
            if value_type == "Status" and existing_formulas:
                for formula_row_idx, formula_row in enumerate(existing_formulas):
                    formula_data = (
                        version_formula_data[col_name][formula_row_idx]
                        if formula_row_idx < len(version_formula_data[col_name])
                        else ""
                    )
                    formula_row.insert(insert_pos, formula_data)


def _update_header_with_formatting(worksheet, header):
    """Update header row with formatting using batchUpdate."""
    try:
        service = build("sheets", "v4", credentials=worksheet.spreadsheet.client.auth)
        sheet_id = worksheet.id

        requests = [
            {
                "updateCells": {
                    "range": {
                        "sheetId": sheet_id,
                        "startRowIndex": 0,
                        "endRowIndex": 1,
                        "startColumnIndex": 0,
                        "endColumnIndex": len(header),
                    },
                    "rows": [
                        {
                            "values": [
                                {"userEnteredValue": {"stringValue": str(val)}}
                                for val in header
                            ]
                        }
                    ],
                    "fields": "userEnteredValue",
                }
            },
            {
                "repeatCell": {
                    "range": {
                        "sheetId": sheet_id,
                        "startRowIndex": 0,
                        "endRowIndex": 1,
                        "startColumnIndex": 0,
                        "endColumnIndex": len(header),
                    },
                    "cell": {"userEnteredFormat": {"textFormat": {"bold": True}}},
                    "fields": "userEnteredFormat.textFormat.bold",
                }
            },
            {
                "updateSheetProperties": {
                    "properties": {
                        "sheetId": sheet_id,
                        "gridProperties": {"frozenRowCount": 1},
                    },
                    "fields": "gridProperties.frozenRowCount",
                }
            },
        ]

        service.spreadsheets().batchUpdate(
            spreadsheetId=worksheet.spreadsheet.id, body={"requests": requests}
        ).execute()
    except Exception as e:
        print(f"Warning: Batch header update failed, using individual calls: {e}")
        worksheet.update(values=[header], range_name="A1")
        set_frozen(worksheet, 1)
        fmt = cellFormat(textFormat=textFormat(bold=True))
        format_cell_range(
            worksheet, f"A1:{gspread.utils.rowcol_to_a1(1, len(header))}", fmt
        )


def _build_data_maps(existing_rows, existing_formulas, header, idx_suite, idx_case):
    """Build maps of existing rows and formulas."""
    sheet_row_map = {}
    sheet_formula_map = {}
    for row_idx, row in enumerate(existing_rows):
        if len(row) > max(idx_suite, idx_case) and idx_suite >= 0 and idx_case >= 0:
            key = (row[idx_suite], row[idx_case])
            sheet_row_map[key] = row
            if row_idx < len(existing_formulas):
                formula_row = existing_formulas[row_idx]
                while len(formula_row) < len(header):
                    formula_row.append("")
                sheet_formula_map[key] = formula_row
    return sheet_row_map, sheet_formula_map


def _merge_new_data(
    data,
    sheet_row_map,
    sheet_formula_map,
    header,
    header_idx_map,
    version_col_idx,
    version_column_indices,
    value_type,
):
    """Merge new data with existing data, preserving formulas."""
    updated_keys = set()
    header_len = len(header)

    for entry in data:
        key = (entry.get("Test Suite Name", ""), entry.get("Test Case Name", ""))
        if not key[0] or not key[1]:
            continue

        updated_keys.add(key)
        new_row = ["" for _ in header]

        for col_name, value in entry.items():
            col_idx = header_idx_map.get(col_name)
            if col_idx is not None:
                new_row[col_idx] = value

        if value_type == "Time":
            if version_col_idx >= 0:
                new_row[version_col_idx] = entry.get("Time (seconds)", "")
        elif value_type == "Status":
            if version_col_idx >= 0:
                new_row[version_col_idx] = entry.get("Status", "")

        if key in sheet_row_map:
            existing = sheet_row_map[key]
            existing_formula_row = sheet_formula_map.get(key, [])
            merged = []
            for i in range(header_len):
                if i == version_col_idx:
                    merged.append(new_row[i])
                elif i in version_column_indices:
                    if (
                        value_type == "Status"
                        and existing_formula_row
                        and i < len(existing_formula_row)
                    ):
                        formula = existing_formula_row[i]
                        if formula and isinstance(formula, str) and formula.strip():
                            formula_upper = formula.strip().upper()
                            if formula_upper.startswith("=HYPERLINK("):
                                merged.append(formula.strip())
                            else:
                                merged.append(existing[i] if i < len(existing) else "")
                        else:
                            merged.append(existing[i] if i < len(existing) else "")
                    else:
                        merged.append(existing[i] if i < len(existing) else "")
                else:
                    merged.append(
                        new_row[i]
                        if new_row[i]
                        else (existing[i] if i < len(existing) else "")
                    )
            sheet_row_map[key] = merged
        else:
            sheet_row_map[key] = new_row

    return updated_keys


def _preserve_unupdated_rows(
    sheet_row_map,
    sheet_formula_map,
    updated_keys,
    header_len,
    version_column_indices,
    value_type,
):
    """Preserve existing rows that weren't updated (Status sheet only)."""
    if value_type == "Status":
        for key, existing_row in list(sheet_row_map.items()):
            if key not in updated_keys:
                existing_formula_row = sheet_formula_map.get(key, [])
                if existing_formula_row:
                    preserved_row = []
                    for i in range(header_len):
                        if i in version_column_indices and i < len(
                            existing_formula_row
                        ):
                            formula = existing_formula_row[i]
                            if formula and isinstance(formula, str) and formula.strip():
                                formula_upper = formula.strip().upper()
                                if formula_upper.startswith("=HYPERLINK("):
                                    preserved_row.append(formula.strip())
                                else:
                                    preserved_row.append(
                                        existing_row[i] if i < len(existing_row) else ""
                                    )
                            else:
                                preserved_row.append(
                                    existing_row[i] if i < len(existing_row) else ""
                                )
                        else:
                            preserved_row.append(
                                existing_row[i] if i < len(existing_row) else ""
                            )
                    sheet_row_map[key] = preserved_row


def _write_data_to_sheet(worksheet, final_rows, header):
    """Write final data to sheet using batchUpdate."""
    if not final_rows:
        return

    try:
        service = build("sheets", "v4", credentials=worksheet.spreadsheet.client.auth)
        sheet_id = worksheet.id

        rows_data = []
        for row in final_rows:
            row_values = []
            for col_idx, cell_value in enumerate(row):
                if col_idx < len(header):
                    if (
                        isinstance(cell_value, str)
                        and cell_value.strip()
                        and cell_value.strip().startswith("=")
                    ):
                        row_values.append(
                            {"userEnteredValue": {"formulaValue": cell_value.strip()}}
                        )
                    elif cell_value:
                        row_values.append(
                            {"userEnteredValue": {"stringValue": str(cell_value)}}
                        )
                    else:
                        row_values.append({"userEnteredValue": {"stringValue": ""}})
                else:
                    row_values.append({})
            rows_data.append({"values": row_values})

        end_row = len(final_rows) + 1
        request = {
            "updateCells": {
                "range": {
                    "sheetId": sheet_id,
                    "startRowIndex": 1,
                    "endRowIndex": end_row,
                    "startColumnIndex": 0,
                    "endColumnIndex": len(header),
                },
                "rows": rows_data,
                "fields": "userEnteredValue",
            }
        }

        service.spreadsheets().batchUpdate(
            spreadsheetId=worksheet.spreadsheet.id, body={"requests": [request]}
        ).execute()
    except Exception as e:
        print(f"Warning: Batch update failed, falling back to regular update: {e}")
        worksheet.update(
            values=final_rows,
            range_name=f"A2:{gspread.utils.rowcol_to_a1(len(final_rows)+1, len(header))}",
            value_input_option="USER_ENTERED",
        )

    if len(final_rows) > 1000:
        time.sleep(1)
    else:
        time.sleep(0.5)


def update_dynamic_worksheet(
    spreadsheet_obj, sheet_name, data, ceph_version, value_type="Time"
):
    """
    Update Google Sheet with dynamic columns per Ceph version.

    Behavior:
    - For value_type == "Time":
        * Adds only Time column with Ceph version as header (no Status column).
        * No color formatting applied (timings sheet).
    - For value_type == "Status":
        * Adds only Status column with Ceph version as header.
        * Applies color formatting to the new version column (Passed/Failed).
        * Adds HYPERLINK of Magna log-server URL for the test case to the Status column.
    """
    worksheet = get_or_create_worksheet(spreadsheet_obj, sheet_name)

    # Step 1: Read existing sheet data and formulas
    header, existing_rows, existing_formulas = _read_sheet_data(
        worksheet, sheet_name, value_type
    )

    # Step 2: Ensure base headers exist
    required_headers, required_headers_set = _ensure_base_headers(header, value_type)

    # Step 3: Insert or move version column to correct position
    version_col_name = ceph_version
    insert_base_idx = _insert_version_column(
        header,
        existing_rows,
        existing_formulas,
        version_col_name,
        required_headers,
        value_type,
    )

    # Step 4: Reorder version columns so newest is first
    _reorder_version_columns(
        header,
        existing_rows,
        existing_formulas,
        required_headers_set,
        insert_base_idx,
        value_type,
    )

    # Step 5: Update header with formatting
    _update_header_with_formatting(worksheet, header)
    time.sleep(0.5)

    # Step 6: Build data maps for existing rows and formulas
    header_idx_map = {col: idx for idx, col in enumerate(header)}
    idx_suite = header_idx_map.get("Test Suite Name", -1)
    idx_case = header_idx_map.get("Test Case Name", -1)
    version_column_indices = [
        idx for idx, col in enumerate(header) if col not in required_headers_set
    ]

    sheet_row_map, sheet_formula_map = _build_data_maps(
        existing_rows, existing_formulas, header, idx_suite, idx_case
    )

    # Step 7: Merge new data with existing data
    version_col_idx = header_idx_map.get(version_col_name, -1)
    updated_keys = _merge_new_data(
        data,
        sheet_row_map,
        sheet_formula_map,
        header,
        header_idx_map,
        version_col_idx,
        version_column_indices,
        value_type,
    )

    # Step 8: Preserve unupdated rows (Status sheet only)
    header_len = len(header)
    _preserve_unupdated_rows(
        sheet_row_map,
        sheet_formula_map,
        updated_keys,
        header_len,
        version_column_indices,
        value_type,
    )

    # Step 9: Write final data to sheet
    final_rows = list(sheet_row_map.values())
    _write_data_to_sheet(worksheet, final_rows, header)

    # Step 10: Apply formatting
    _auto_resize_columns(worksheet)
    if value_type == "Status":
        _apply_status_color_formatting(worksheet)
    print(f"Worksheet '{sheet_name}' updated successfully...")


def parse_xml_attributes(root, filename, ceph_version):
    """Extract attributes from the root <testsuites> element."""
    properties, stats, testcases, timings = [], [], [], []
    # File properties
    props = parse_xml_properties(root, ceph_version)
    props["source_file"] = filename
    properties.append(props)

    # Test suite stats
    suite_stats = parse_testsuite_stats(root)
    for stat in suite_stats:
        stat["source_file"] = filename
    stats.extend(suite_stats)

    # Test case details and timings
    status_data, timings_data = parse_testcases(root)
    for tc in status_data:
        tc["source_file"] = filename
    testcases.extend(status_data)

    for timing in timings_data:
        timing["source_file"] = filename
    timings.extend(timings_data)

    return properties, stats, testcases, timings


def update_gsheet(
    google_credentials, sheet_name, properties, stats, testcases, timings, ceph_version
):
    """Update all four worksheets in the Google Sheet."""
    if properties and stats and testcases and timings:
        columns = [
            "Test Suite Name",
            "Total Tests",
            "Passed",
            "Failures",
            "Errors",
            "Skipped",
            "Success Rate",
            f"Time (seconds)",
        ]
        client = get_gsheet_client(google_credentials)
        spreadsheet = client.open(sheet_name)

        update_worksheet(spreadsheet, SHEET_PROPERTIES, properties)
        update_worksheet(spreadsheet, SHEET_SUMMARY, stats, columns_order=columns)
        update_dynamic_worksheet(
            spreadsheet, SHEET_TEST_STATUS, testcases, ceph_version, value_type="Status"
        )
        update_dynamic_worksheet(spreadsheet, SHEET_TEST_TIMINGS, timings, ceph_version)
    else:
        print("Error: Missing data to update Gsheet.")


def main(ceph_version, resultdir, gsheet_name, google_credentials):
    results_dir = os.path.join(resultdir, "results")

    print(f"\nParsing results from: {results_dir}")
    print(f"Ceph Version: {ceph_version}")
    print(f"Google Sheet Name: {gsheet_name}\n")

    if not os.path.exists(google_credentials):
        print(f"Error: Google credentials file not found at '{google_credentials}'")
        sys.exit(1)

    if not os.path.isdir(results_dir):
        print("Invalid xml folder path.")
        sys.exit(2)

    all_properties, all_stats, all_testcases, all_test_timings = [], [], [], []
    for filename in os.listdir(results_dir):
        if filename.endswith(".xml"):
            xml_file_path = os.path.join(results_dir, filename)
            print(
                f"Parsing '{os.path.basename(xml_file_path)}' for version '{ceph_version}'..."
            )

            try:
                tree = ET.parse(xml_file_path)
                root = tree.getroot()
            except Exception as e:
                print(f"Error parsing XML file '{xml_file_path}': {e}")
                return None

            properties, stats, testcases, timings = parse_xml_attributes(
                root, filename, ceph_version
            )
            all_properties.extend(properties)
            all_stats.extend(stats)
            all_testcases.extend(testcases)
            all_test_timings.extend(timings)

    update_gsheet(
        google_credentials,
        gsheet_name,
        all_properties,
        all_stats,
        all_testcases,
        all_test_timings,
        ceph_version,
    )


if __name__ == "__main__":
    args = docopt(__doc__)

    ceph_version = args["--version"]
    resultdir = args["--resultdir"]
    gsheet_name = args["--gsheet"]
    google_credentials = args["--service-account"]

    main(ceph_version, resultdir, gsheet_name, google_credentials)
