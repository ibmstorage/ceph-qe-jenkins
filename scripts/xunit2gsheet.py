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
  xunit2gsheet.py --version <ver> --resultdir <path> --gsheet <id> --service-account <path_to_credentials>
  xunit2gsheet.py (-h | --help)

Options:
  -v --version <ver>                          Ceph version (e.g. 19.2.0-190).
  -d --resultdir <path>                       Path to logs directory.
  -s --gsheet <id>                            Google Sheet ID to update.
  -a --service-account <path>                 Path to Google service account JSON file.
  -h --help                                   Show this help message.
"""

import os
import sys
import time
import xml.etree.ElementTree as ET
from docopt import docopt
import gspread
from googleapiclient.discovery import build
from google.oauth2.service_account import Credentials
from gspread_formatting import cellFormat, format_cell_range, textFormat, set_frozen, format_cell_ranges, color

# --- Constants ---
SHEET_PROPERTIES = "Properties"
SHEET_SUMMARY = "Summary"
SHEET_TEST_STATUS = "Test Case Status"
SHEET_TEST_TIMINGS = "Test Case Timings"


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

        stats.append({
            "Test Suite Name": name,
            "Total Tests": total,
            "Passed": passed_count,
            "Failures": failures,
            "Errors": errors,
            "Skipped": skipped,
            "Success Rate": success_rate_str,
            "Time (seconds)": time_sec,
        })
    return stats

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
            if suite_name_value and suite_name_value.endswith('.yaml'):
                # Get the directory name just before the .yaml file
                normalized_path = os.path.normpath(suite_name_value)
                path_parts = normalized_path.split(os.sep)
                if len(path_parts) >= 2:
                    dir_name = path_parts[-2]
                    component = dir_name.upper()

        for case in suite.findall(".//testcase"):
            testcase_name = case.get("name", "")

            # Default values
            polarion_id = ""
            status = "Passed"

            # Get Polarion ID if exists
            prop = case.find(".//property[@name='polarion-testcase-id']")
            if prop is not None:
                polarion_id = prop.get("value", "").strip()

            # Determine test status
            failure_tag = case.find("failure")
            if failure_tag is not None:
                status = "Failed"

            statuses.append({
                "Test Suite Name": suite_name,
                "Component": component,
                "Test Case Name": testcase_name,
                "polarion-testcase-id": polarion_id,
                "Status": status,
            })

            # Get time taken (float)
            try:
                time_taken = f"{float(case.get('time', 0)):.2f}"
            except ValueError:
                time_taken = 0.0
            timings.append({
                "Test Suite Name": suite_name,
                "Component": component,
                "Test Case Name": testcase_name,
                "Status": status,
                "Time (seconds)": time_taken,
            })
    return statuses, timings

def _auto_resize_columns(worksheet):
    """Auto-resize columns in a worksheet using Google Sheets API."""
    values = worksheet.get_all_values()
    if not values:
        return

    num_cols = len(values[0])
    creds = worksheet.spreadsheet.client.auth
    service = build('sheets', 'v4', credentials=creds)
    sheet_id = worksheet.id

    request_body = {
        "requests": [
            {
                "autoResizeDimensions": {
                    "dimensions": {
                        "sheetId": sheet_id,
                        "dimension": "COLUMNS",
                        "startIndex": 0,
                        "endIndex": num_cols
                    }
                }
            }
        ]
    }

    service.spreadsheets().batchUpdate(
        spreadsheetId=worksheet.spreadsheet.id,
        body=request_body
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
    standard_headers = ["Test Suite Name", "Test Case Name", "Component", "polarion-testcase-id"]
    version_cols = []
    
    for idx, col in enumerate(header):
        col_stripped = col.strip()
        # If column doesn't match standard headers, it's likely a version column
        if col_stripped not in standard_headers and not col_stripped.startswith("Status") and not col_stripped.startswith("Time"):
            version_cols.append(idx)
    
    if not version_cols:
        return  # No version columns to format

    # Use Google Sheets API directly for efficient conditional formatting
    try:
        service = build('sheets', 'v4', credentials=worksheet.spreadsheet.client.auth)
        sheet_id = worksheet.id
        
        requests = []
        
        # First, clear all existing conditional formatting for the Status columns
        # Get existing conditional format rules to delete them
        try:
            spreadsheet = service.spreadsheets().get(spreadsheetId=worksheet.spreadsheet.id).execute()
            for sheet in spreadsheet.get('sheets', []):
                if sheet.get('properties', {}).get('sheetId') == sheet_id:
                    conditional_format_rules = sheet.get('conditionalFormatRules', [])
                    # Delete all existing rules for this sheet
                    for idx in range(len(conditional_format_rules)):
                        requests.append({
                            "deleteConditionalFormatRule": {
                                "index": idx,
                                "sheetId": sheet_id
                            }
                        })
        except Exception as e:
            print(f"Warning: Could not clear existing conditional formatting: {e}")
        
        # Add conditional formatting rules for better performance
        # This creates a single batch request with all rules
        for col_idx in version_cols:
            
            # Green formatting rule for "passed"
            requests.append({
                "addConditionalFormatRule": {
                    "index": 0,
                    "rule": {
                        "ranges": [{
                            "sheetId": sheet_id,
                            "startRowIndex": 1,  # Skip header
                            "endRowIndex": num_rows,
                            "startColumnIndex": col_idx,
                            "endColumnIndex": col_idx + 1
                        }],
                        "booleanRule": {
                            "condition": {
                                "type": "TEXT_CONTAINS",
                                "values": [{"userEnteredValue": "passed"}]
                            },
                            "format": {
                                "backgroundColor": {"red": 0.85, "green": 0.92, "blue": 0.83}
                            }
                        }
                    }
                }
            })
            
            # Red formatting rule for "failed"
            requests.append({
                "addConditionalFormatRule": {
                    "index": 1,
                    "rule": {
                        "ranges": [{
                            "sheetId": sheet_id,
                            "startRowIndex": 1,
                            "endRowIndex": num_rows,
                            "startColumnIndex": col_idx,
                            "endColumnIndex": col_idx + 1
                        }],
                        "booleanRule": {
                            "condition": {
                                "type": "TEXT_CONTAINS",
                                "values": [{"userEnteredValue": "failed"}]
                            },
                            "format": {
                                "backgroundColor": {"red": 0.96, "green": 0.8, "blue": 0.8}
                            }
                        }
                    }
                }
            })
            
            # White formatting rule for empty cells (lowest priority - acts as default)
            # This ensures cells become white when text is removed
            requests.append({
                "addConditionalFormatRule": {
                    "index": 2,
                    "rule": {
                        "ranges": [{
                            "sheetId": sheet_id,
                            "startRowIndex": 1,
                            "endRowIndex": num_rows,
                            "startColumnIndex": col_idx,
                            "endColumnIndex": col_idx + 1
                        }],
                        "booleanRule": {
                            "condition": {
                                "type": "BLANK"
                            },
                            "format": {
                                "backgroundColor": {"red": 1.0, "green": 1.0, "blue": 1.0}
                            }
                        }
                    }
                }
            })
        
        # Execute batch update
        if requests:
            service.spreadsheets().batchUpdate(
                spreadsheetId=worksheet.spreadsheet.id,
                body={"requests": requests}
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

def update_dynamic_worksheet(spreadsheet_obj, sheet_name, data, ceph_version, value_type="Time"):
    """
    Update Google Sheet with dynamic columns per Ceph version.

    Behavior:
    - For value_type == "Time":
        * Adds only Time column with Ceph version as header (no Status column).
        * No color formatting applied (timings sheet).
    - For value_type == "Status":
        * Adds only Status column with Ceph version as header.
        * Applies color formatting to the new version column (Passed/Failed).
    """
    worksheet = get_or_create_worksheet(spreadsheet_obj, sheet_name)
    
    # Fetch existing sheet data
    all_values = worksheet.get_all_values()
    header = all_values[0] if all_values and len(all_values[0]) > 0 else []
    existing_rows = all_values[1:] if all_values and len(all_values) > 1 else []
    
    # Ensure base headers exist (using set for faster lookup)
    required_headers = ["Test Suite Name", "Test Case Name", "Component"]
    if value_type == "Status":
        required_headers.append("polarion-testcase-id")
    
    header_set = set(header)
    for col in required_headers:
        if col not in header_set:
            header.append(col)
            header_set.add(col)

    # Determine column names and data keys
    # Both Status and Timings sheets use only Ceph version number as header
    version_col_name = ceph_version
    dynamic_cols = [version_col_name]

    # Insert new columns (optimized: create header indices map)
    insert_base_idx = len(required_headers)
    header_indices = {col: idx for idx, col in enumerate(header)}
    
    for col in reversed(dynamic_cols):
        if col in header_indices:
            old_idx = header_indices[col]
            # Remove the header
            moved_header = header.pop(old_idx)
            # Update indices after pop
            header_indices = {col: (idx if idx < old_idx else idx - 1) for col, idx in header_indices.items() if col != moved_header}
            
            # Remove and store the column data for all rows (optimized)
            moved_col_data = [row.pop(old_idx) if len(row) > old_idx else "" for row in existing_rows]
            
            # Insert header and column data at new position
            header.insert(insert_base_idx, moved_header)
            for col_name, idx in header_indices.items():
                if idx >= insert_base_idx:
                    header_indices[col_name] = idx + 1
            header_indices[moved_header] = insert_base_idx
            
            for r_idx, row in enumerate(existing_rows):
                row.insert(insert_base_idx, moved_col_data[r_idx])
        else:
            header.insert(insert_base_idx, col)
            header_indices[col] = insert_base_idx
            for col_name, idx in header_indices.items():
                if idx >= insert_base_idx:
                    header_indices[col_name] = idx + 1
            for row in existing_rows:
                row.insert(insert_base_idx, "")

    # Update header row
    worksheet.update(values=[header], range_name="A1")
    time.sleep(2)
    set_frozen(worksheet, 1)
    fmt = cellFormat(textFormat=textFormat(bold=True))
    format_cell_range(worksheet, f"A1:{gspread.utils.rowcol_to_a1(1, len(header))}", fmt)

    # Cache column indices for performance
    idx_suite = header.index("Test Suite Name")
    idx_case = header.index("Test Case Name")
    
    # Build map of existing rows (optimized with pre-check)
    sheet_row_map = {}
    for row in existing_rows:
        if len(row) > max(idx_suite, idx_case):
            key = (row[idx_suite], row[idx_case])
            sheet_row_map[key] = row

    # Pre-calculate column indices for performance
    version_col_idx = header.index(version_col_name) if version_col_name in header else -1
    header_len = len(header)

    # Merge new data (optimized with pre-calculated indices)
    for entry in data:
        key = (entry["Test Suite Name"], entry["Test Case Name"])
        
        # Start with empty row of correct length
        new_row = ["" for _ in header]
        
        # Fill in all fields from entry
        for col_name in entry:
            if col_name in header:
                col_idx = header.index(col_name)
                new_row[col_idx] = entry[col_name]
        
        # Populate data based on sheet type
        if value_type == "Time":
            # Timings sheet: populate only Time column (no Status column)
            if version_col_idx >= 0:
                new_row[version_col_idx] = entry.get("Time (seconds)", "")
        elif value_type == "Status":
            # Status sheet: populate Status column
            if version_col_idx >= 0:
                new_row[version_col_idx] = entry.get("Status", "")

        # Merge with existing data if key exists
        if key in sheet_row_map:
            existing = sheet_row_map[key]
            # Merge: For Time and Status sheets, overwrite version columns completely with new data
            # For other sheets, prefer new data if present
            merged = []
            for i in range(header_len):
                # For version column, always use new data (even if empty)
                if i == version_col_idx:
                    merged.append(new_row[i])
                else:
                    # For other columns, prefer new data if present, else keep existing
                    merged.append(new_row[i] if new_row[i] else (existing[i] if i < len(existing) else ""))
            sheet_row_map[key] = merged
        else:
            sheet_row_map[key] = new_row

    # write final data
    final_rows = list(sheet_row_map.values())
    if final_rows:
        worksheet.update(
            values=final_rows,
            range_name=f"A2:{gspread.utils.rowcol_to_a1(len(final_rows)+1, len(header))}"
        )
        time.sleep(2)

    _auto_resize_columns(worksheet)
    # Only apply color formatting to Status sheets (not Timings sheets)
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

def update_gsheet(google_credentials, sheet_id, properties, stats, testcases, timings, ceph_version):
    """Update all four worksheets in the Google Sheet."""
    if properties and stats and testcases and timings:
        columns = ["Test Suite Name",
                   "Total Tests",
                   "Passed",
                   "Failures",
                   "Errors",
                   "Skipped",
                   "Success Rate",
                   f"Time (seconds)"
                   ]
        client = get_gsheet_client(google_credentials)
        spreadsheet = client.open_by_key(sheet_id)
        
        update_worksheet(spreadsheet, SHEET_PROPERTIES, properties)
        update_worksheet(spreadsheet, SHEET_SUMMARY, stats, columns_order=columns)
        update_dynamic_worksheet(spreadsheet, SHEET_TEST_STATUS, testcases, ceph_version, value_type="Status")
        update_dynamic_worksheet(spreadsheet, SHEET_TEST_TIMINGS, timings, ceph_version)
    else:
        print("Error: Missing data to update Gsheet.")

def main(ceph_version, resultdir, gsheet_id, google_credentials):
    results_dir = os.path.join(resultdir, "results")

    print(f"\nParsing results from: {results_dir}")
    print(f"Ceph Version: {ceph_version}")
    print(f"Google Sheet ID: {gsheet_id}\n")

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
            print(f"Parsing '{os.path.basename(xml_file_path)}' for version '{ceph_version}'...")

            try:
                tree = ET.parse(xml_file_path)
                root = tree.getroot()
            except Exception as e:
                print(f"Error parsing XML file '{xml_file_path}': {e}")
                return None

            properties, stats, testcases, timings = parse_xml_attributes(root, filename, ceph_version)
            all_properties.extend(properties)
            all_stats.extend(stats)
            all_testcases.extend(testcases)
            all_test_timings.extend(timings)

    update_gsheet(google_credentials, gsheet_id, all_properties, all_stats, all_testcases, all_test_timings, ceph_version)

    
if __name__ == "__main__":
    args = docopt(__doc__)

    ceph_version = args["--version"]
    resultdir = args["--resultdir"]
    gsheet_id = args["--gsheet"]
    google_credentials = args["--service-account"]

    main(ceph_version, resultdir, gsheet_id, google_credentials)
