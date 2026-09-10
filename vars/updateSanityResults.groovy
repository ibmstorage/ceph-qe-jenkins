// Promote nightly manifest section to stable after a passing sanity run

def call(Map parameters) {
    def manifestSection = parameters.get("manifestSection")
    if (!manifestSection) {
        throw new RuntimeException(
            "Mandatory parameter 'manifestSection' is not provided"
        )
    }

    // Skip before validating promotion-only params (stable/rc runs must not fail)
    if (manifestSection.toLowerCase() != "nightly") {
        echo "Skipped manifest promotion: manifest section is '${manifestSection}', only nightly runs promote to stable."
        return
    }

    def resultsSummary = parameters.get("resultsSummary")
    if (!resultsSummary) {
        throw new RuntimeException(
            "Mandatory parameter 'resultsSummary' is not provided"
        )
    }

    def nightlySection = parameters.get("nightlySection")
    if (!nightlySection) {
        throw new RuntimeException(
            "Mandatory parameter 'nightlySection' is not provided"
        )
    }

    def job = parameters.get("job")
    if (!job) {
        throw new RuntimeException(
            "Mandatory parameter 'job' is not provided"
        )
    }

    def buildType = parameters.get("buildType")
    def rhBuild = parameters.get("rhBuild")
    if (!buildType || !rhBuild) {
        throw new RuntimeException(
            "Mandatory parameters 'buildType' and 'rhBuild' are not provided"
        )
    }

    // resultsSummary is a path to results.json written by publishTestResults
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
        echo "Not promoting nightly to stable in manifest"
        return
    }

    def bt = buildType.toLowerCase()
    def recipeVendor = bt == "rh" ? "redhat" : bt
    def recipeFile = "${recipeVendor}/${rhBuild}.yaml"

    echo "All sanity suites passed. Promoting nightly section to stable in ${recipeFile}."

    // Write to manifest
    writeToManifest(
        recipe: recipeFile,
        buildTag: "stable",
        sectionContent: nightlySection,
        sectionSource: "nightly",
        job: job
    )
}
