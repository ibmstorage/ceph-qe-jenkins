// Method to fetch test suites from metadata

import main.groovy.cephJobLib.cephciConfigs


def call(Map parameters) {
    // Get python script from jenkins resource
    def fetchTestSuites = "scripts/fetchTestSuites.py"

    // Set command
    def cmd = [cephciConfigs.PYTHON, "${fetchTestSuites}"]

    // Get mandatory parameters
    def build = parameters.get("rhBuild")
    if (!build) {
        throw new RuntimeException(
            "Mandatory parameter 'rhBuild' not provided")
    }
    cmd += ["-b", "${build}"]

    def fg = parameters.get("fg")
    if (!fg) {
        throw new RuntimeException(
            "Mandatory parameter 'fg' not provided")
    }
    cmd += ["--fg", "${fg}"]

    def job = parameters.get("job")
    if (!job) {
        throw new RuntimeException(
            "Mandatory parameter 'job' not provided")
    }
    cmd += ["-j", "${job}"]

    def platform = parameters.get("platform")
    if (!platform) {
        throw new RuntimeException(
            "Mandatory parameter 'platform' not provided")
    }
    cmd += ["-p", "${platform}"]

    def cloud = parameters.get("cloudType")
    if (!cloud) {
        throw new RuntimeException(
            "Mandatory parameter 'cloudType' not provided")
    }
    cmd += ["-c", "${cloud}"]

    // Get optional parameters
    def build_type = parameters.get("buildType")
    if (build_type) {
        cmd += ["--build-type", "${build_type}"]
    }

    def cluster_config = parameters.get("clusterConfig")
    if (cluster_config) {
        cmd += ["--cluster-config", "${cluster_config}"]
    }

    def tag = parameters.get("testTag")
    if (tag) {
        def tagList = tag.split(",").collect { it.trim() }
        tagList.each { t ->
            cmd += ["-t", t]
        }
    }

    def output = parameters.get("output")
    if (output) {
        cmd += ["-o", "${output}"]
    }

    // Execute python script and parse output
    results = sh(returnStdout: true, script: cmd.join(" "))
    def resultsData = readJSON text: results

    // Return results data
    return resultsData
}
