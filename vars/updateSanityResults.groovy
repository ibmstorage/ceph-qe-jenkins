// Method to update Sanity results to manifest file

def call(Map parameters) {
    // Check for results parameters
    def resultsSummary = parameters.get("resultsSummary")
    if (!resultsSummary) {
        throw new RuntimeException(
            "Mandatory results parameters are not provided"
        )
    }

    // Get sanity results
    def results = readJSON file: resultsSummary

    // Check Sanity status - all suites must pass
    def failedSuites = []
    results.each { suite ->
        if (suite.status != "PASS") {
            failedSuites.add(suite.name)
        }
    }

    if (failedSuites.size() > 0) {
        echo "Sanity failed for suites: ${failedSuites.join(', ')}"
        echo "Not updating results to manifest"
        return
    }

    echo "All sanity suites passed. Proceeding to update manifest."

    // Check for job parameters
    def job = parameters.get("job")
    if (!job) {
        throw new RuntimeException(
            "Mandatory parameter `job` not provided"
        )
    }

    // Set build parameters
    def buildInfo = [:]

    // Check for mandatory job parameters
    def buildType = parameters.get("buildType")
    def rhBuild = parameters.get("rhBuild")
    if (!buildType || !rhBuild) {
        throw new RuntimeException(
            "Mandatory job parameters are not provided"
        )
    }

    // Set recipe path
    buildInfo.recipe = "${buildType.toLowerCase()}"
    if (buildType.toLowerCase() == "rh") {
        buildInfo.recipe = "redhat"
    }
    buildInfo.recipe += "/${rhBuild}.yaml"

    // Check for mandatory build parameters
    def platform = parameters.get("platform")
    def cephVersion = parameters.get("cephVersion")
    def rhsCephRepo = parameters.get("rhsCephRepo")
    def containerImage = parameters.get("containerImage")
    if (!platform || !cephVersion || !rhsCephRepo || !containerImage) {
        throw new RuntimeException(
            "Mandatory build parameters are not provided"
        )
    }

    // Set build info for build parameters
    buildInfo.version = cephVersion
    buildInfo.repositories = [:]
    buildInfo.repositories."${platform}" = rhsCephRepo
    buildInfo.images = [:]
    buildInfo.images."ceph-base" = containerImage

    // Add custom images if provided
    def customImages = parameters.get("customImages")
    if (customImages) {
        customImages.each { key, value ->
            buildInfo.images."${key}" = value
        }
    }

    // Write to manifest
    writeToManifest(
        buildInfo: buildInfo, buildTag: "stable", job: job
    )
}
