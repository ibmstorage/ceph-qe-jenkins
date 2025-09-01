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


def get_testsuite(
    test, platform, build=None, build_type=None, tag=None
):
    """Get testsuite and global config"""
    # Check for platform in test config
    if platform not in test.get("platform"):
        return None

    # Check for version
    if (
        build
        and test.get("version")
        and float(build) not in test.get("version")
    ):
        return None

    # Check for build type in test config
    if build_type and build_type not in test.get(
        "build", ["rh", "ibm", "upstream"]
    ):
        return None

    # Check if tag is in test config
    if tag and (
        not test.get("tags") or
        not all(
            t.lower() in [
                x.lower() for x in test.get("tags", [])
            ] for t in tag
        )
    ):
        return None

    # Create testsuites with details
    return {
        "name": test.get("name", ""),
        "suite": test.get("suite", ""),
        "global_conf": test.get("global-conf", ""),
        "vm_size": test.get("vm-size", ""),
        "custom_config": ",".join(
            [f"{k}={v}" for k, v in test.get("custom_config", {}).items()]
        )
    }

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

        metadata = stages.get(stage, {}).get("metadata", {})
        if not metadata:
            continue

        # Define metadata in testsuite
        testsuites[stage] = {"metadata": {}}

        # Get cloud details
        _cloud = metadata.get("cloud")
        if _cloud and cloud not in _cloud:
            continue
        testsuites[stage]["metadata"]["cloud"] = cloud

        # Get global conf
        testsuites[stage]["metadata"]["globalConf"] = metadata.get(
            "global-conf", {}).get(build_type, "")

        # Check for custom-config
        testsuites[stage]["metadata"]["custom_config"] = ",".join(
            [f"{k}={v}" for k, v in metadata.get("custom-config", {}).items()]
        )

        # Iterate over testsuite in stages
        for test in tests:
            testsuite = get_testsuite(test, platform, build, build_type, tag)
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
    if build.startswith("8."):
        file_path.append("squid")

    elif build.startswith("9."):
        file_path.append("tentacle")

    elif build.startswith("10."):
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
    testsuites = get_testsuites(
        metadata, job, platform, cloud, build, build_type, tag
    )

    # Write output
    write_output(testsuites, output)
