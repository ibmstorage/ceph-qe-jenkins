import sys

import docopt
import yaml

# Set doc description
doc = """
This script fetches all the tests to be run for a pipeline based
on the RHCS versions
    Usage:
        getTestSuites.py (--build <build>)
            (--fg <FG>)
            (--job <job>)
            (--platform <RHEL>)
            (--cloud <cloud>)
            [--build-type <build-type>]
            [--tag <tag>]...
            [--output <file>]

        getTestSuites.py --help

    Options:
        -h --help               Shows the command usage.
        -b --build <str>        Ceph Build Version for which tests
                                to be fetched.
        --fg <str>              FG to get test suites.
        -j --job <str>          Jenkins job for which tests need
                                to be fetched.
        -p --platform <str>     OS platform version.
        -c --cloud <str>        Supported cloud platform.
        --build-type <str>      Build type [RH | IBM]
        -t --tag <str>          Tag to be used for filtering test scripts.
        -o --output <str>       Output file path.
"""


def resolve_vm_size(test, cloud):
    """Resolve cloud-specific vm-size from suite metadata.

    Only dict entries (e.g. ibmc/aws keys) are used. Legacy string
    vm-size values are ignored so they are not applied on every cloud.
    """
    raw = test.get("vm-size")
    if not isinstance(raw, dict) or not cloud:
        return ""
    return raw.get(cloud, "") or ""


def get_testsuite(
    test,
    platform,
    build=None,
    build_type=None,
    tag=None,
    cloud=None,
    stage_inventory=None,
):
    """Get testsuite and global config"""
    # Check for platform in test config
    if platform and test.get("platform") and platform not in test.get("platform"):
        return None

    # Check for version
    if build and test.get("version"):
        try:
            if float(build) not in test.get("version"):
                return None
        except ValueError:
            print(
                f"ERROR: --build '{build}' is not a valid numeric version "
                f"(e.g. 8.1, 9.2). Cannot filter suites by version.",
                file=sys.stderr,
            )
            sys.exit(1)

    # Check for build type in test config
    if build_type and build_type not in test.get("build", ["rh", "ibm", "upstream"]):
        return None

    # Check for cloud inclusion/exclusion in test config.
    # "cloud" is an allowlist — suite only runs on listed clouds.
    # "exclude_cloud" is a denylist — suite is skipped on listed clouds.
    suite_clouds = test.get("cloud")
    if isinstance(suite_clouds, str):
        suite_clouds = [suite_clouds]
    if cloud and suite_clouds and cloud.lower() not in [c.lower() for c in suite_clouds]:
        return None

    exclude_clouds = test.get("exclude_cloud")
    if isinstance(exclude_clouds, str):
        exclude_clouds = [exclude_clouds]
    if cloud and exclude_clouds and cloud.lower() in [c.lower() for c in exclude_clouds]:
        return None

    # Check if tag is in test config
    if tag and (
        not test.get("tags")
        or not all(t.lower() in [x.lower() for x in test.get("tags", [])] for t in tag)
    ):
        return None

    # Get inventory file based on cloud and platform
    # First check test-level inventory, then fall back to stage-level inventory
    inventory = None
    if cloud and test.get("inventory"):
        cloud_inventory = test.get("inventory", {}).get(cloud, {})
        if isinstance(cloud_inventory, dict) and platform:
            inventory = cloud_inventory.get(platform)
        elif isinstance(cloud_inventory, str):
            inventory = cloud_inventory
    elif cloud and stage_inventory:
        # Use stage-level inventory if test-level inventory is not present
        cloud_inventory = stage_inventory.get(cloud, {})
        if isinstance(cloud_inventory, dict) and platform:
            inventory = cloud_inventory.get(platform)
        elif isinstance(cloud_inventory, str):
            inventory = cloud_inventory

    # Create testsuites with details
    result = {
        "name": test.get("name", "")
        .replace(" ", "_")
        .replace(".", "_")
        .replace("-", "_"),
        "suite": test.get("suite", ""),
        "global_conf": test.get("global-conf", ""),
        "vm_size": resolve_vm_size(test, cloud),
        "custom_config": ",".join(
            [f"{k}={v}" for k, v in test.get("custom-config", {}).items()]
        ),
        "tags": list(test.get("tags") or []),
    }

    # Only add inventory if it's actually set
    if inventory:
        result["inventory"] = inventory

    return result


def get_testsuites(metadata, job, platform, cloud, build, build_type, tag):
    """Get stage and testsuites"""
    # Check for stages in metadata
    stages = metadata.get(job)
    if "metadata" in stages.keys():
        stages = {job: stages}

    # Set testsuite and iterate over stages
    testsuites = {}
    for stage in stages:
        tests = stages.get(stage, {}).get("suites", {})
        if not tests:
            continue

        stage_metadata = stages.get(stage, {}).get("metadata", {})
        if not stage_metadata:
            continue

        # Get stage-level inventory
        stage_inventory = stages.get(stage, {}).get("inventory", {})

        # Define metadata in testsuite
        testsuites[stage] = {"metadata": {}}

        # Get cloud details
        _cloud = stage_metadata.get("cloud")
        if _cloud and cloud not in _cloud:
            continue
        testsuites[stage]["metadata"]["cloud"] = cloud

        # Get global conf
        testsuites[stage]["metadata"]["globalConf"] = stage_metadata.get(
            "global-conf", {}
        ).get(build_type, "")

        # Check for custom-config
        testsuites[stage]["metadata"]["custom_config"] = ",".join(
            [f"{k}={v}" for k, v in stage_metadata.get("custom-config", {}).items()]
        )

        # Iterate over testsuite in stages
        for test in tests:
            testsuite = get_testsuite(
                test, platform, build, build_type, tag, cloud, stage_inventory
            )
            if not testsuite:
                continue

            if "suites" not in testsuites.get(stage, {}).keys():
                testsuites[stage]["suites"] = [testsuite]
                continue

            testsuites[stage]["suites"].append(testsuite)

    return testsuites


def get_metadata(build, fg):
    """Get metadata from URL"""
    # Set default file path
    file_path = ["metadata"]

    # Set major ceph version
    if build.startswith("7."):
        file_path.append("reef")

    elif build.startswith("8."):
        file_path.append("squid")

    elif build.startswith("9."):
        file_path.append("tentacle")

    elif build.startswith("10."):
        file_path.append("umbrella")

    elif build.startswith("11."):
        file_path.append("main")

    # Add focus group
    file_path.append(fg)

    # Create path with yaml
    file_path = f"{'/'.join(file_path)}.yaml"
    return read_metadata(file_path)


def write_output(data, output):
    """Write output"""
    # Print if output is not provided
    if not output:
        print(data)
        return

    # Write output to file
    with open(output, "w") as fp:
        fp.write(str(data))


def read_metadata(file_path):
    """Read metadata from file"""
    with open(file_path, "r") as fp:
        return yaml.safe_load(fp)


if __name__ == "__main__":
    # Get args with docopt
    args = docopt.docopt(doc)

    # Get script parameters from args
    build = args.get("--build").lower()
    job = args.get("--job").lower()
    fg = args.get("--fg").lower()
    platform = args.get("--platform").lower()
    cloud = args.get("--cloud").lower()
    build_type = args.get("--build-type")
    if build_type:
        build_type = build_type.lower()
    tag = args.get("--tag")
    if tag:
        tag = [t.lower() for t in tag]
    output = args.get("--output")

    # Get metadata
    metadata = get_metadata(build, fg)

    # Get testsuite with stages in dict
    testsuites = get_testsuites(metadata, job, platform, cloud, build, build_type, tag)

    # Write output
    write_output(testsuites, output)
