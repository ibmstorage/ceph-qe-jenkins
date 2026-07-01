// Method to check if nightly and stable builds are different
// Helper: Parse "20.1.0-101" → [20,1,0,101]
List<Integer> parseVersion(String version) {
    def parts = version.split('-')
    def main = parts[0]
    def build = parts.size() > 1 ? parts[-1] : "0"

    def nums = main.tokenize('.').collect { it.toInteger() }
    nums << build.toInteger()
    return nums
}

// Helper: Compare versions
boolean isNewer(String nightly, String stable) {
    def n = parseVersion(nightly)
    def s = parseVersion(stable)

    int len = Math.max(n.size(), s.size())
    for (int i = 0; i < len; i++) {
        int nv = i < n.size() ? n[i] : 0
        int sv = i < s.size() ? s[i] : 0
        if (nv > sv) return true
        if (nv < sv) return false
    }
    return false
}

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

     if (!isNewer(nightlyVersion, stableVersion)) {
        echo "Nightly and stable builds are identical (version: ${nightlyVersion})"
        echo "Skipping sanity tests."
        return false
    }

    // Check if nightly has newer content
    echo "New build detected!"
    echo "  - Nightly: ${nightlyVersion}"
    echo "  - Stable:  ${stableVersion}"
    echo "Proceeding with sanity tests."

    return true  // Builds are different, proceed with tests
}
