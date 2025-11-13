// Method to check if nightly and stable builds are different

def call(Map parameters) {
    // Check for mandatory job parameters
    def buildType = parameters.get("buildType")
    def rhBuild = parameters.get("rhBuild")
    def job = parameters.get("job")
    
    if (!buildType || !rhBuild || !job) {
        throw new RuntimeException(
            "Mandatory parameters (buildType, rhBuild, job) are not provided"
        )
    }

    // Set recipe path
    def recipe = "${buildType.toLowerCase()}"
    if (buildType.toLowerCase() == "rh") {
        recipe = "redhat"
    }
    recipe += "/${rhBuild}.yaml"

    // Read recipe file from manifest repo
    def recipeContent = readFromManifest(recipe: recipe, job: job)

    // Check if recipe content is valid
    if (!recipeContent) {
        echo "No recipe found for ${recipe}. Proceeding with build."
        return true  // Proceed if no manifest exists
    }

    // Check if nightly and stable sections exist
    if (!recipeContent.nightly || !recipeContent.stable) {
        echo "Nightly or stable section not found in manifest. Proceeding with build."
        return true
    }

    // Check if version fields exist
    if (!recipeContent.nightly?.version || !recipeContent.stable?.version) {
        echo "Nightly or stable version field missing. Proceeding with build."
        return true
    }

    // Get versions
    def nightlyVersion = recipeContent.nightly.version
    def stableVersion = recipeContent.stable.version

    // Debug information
    echo "Nightly version: ${nightlyVersion}"
    echo "Stable version: ${stableVersion}"

    // Compare versions
    if (nightlyVersion <= stableVersion) {
        echo "Nightly and stable builds are identical (version: ${nightlyVersion})"
        echo "No new build to test. Skipping sanity tests."
        return false  // Builds are same, no need to run tests
    }

    // Check if nightly has newer content
    echo "New build detected!"
    echo "  - Nightly: ${nightlyVersion}"
    echo "  - Stable:  ${stableVersion}"
    echo "Proceeding with sanity tests."
    
    return true  // Builds are different, proceed with tests
}

