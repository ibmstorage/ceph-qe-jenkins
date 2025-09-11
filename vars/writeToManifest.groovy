// Method to update manifest with builds

import main.groovy.cephJobLib.manifestConfigs


def call(Map parameters) {
    // Set commit message
    def commitMessage = "Update recipe"

    // Check for mandatory build parameters
    def buildInfo = parameters.buildInfo
    def buildTag = parameters.buildTag
    if (!buildInfo || !buildTag) {
        throw new RuntimeException(
            "Mandatory build parameters are not provided"
        )
    }

    // Get recipe file content
    recipeContent = readFromManifest(parameters)

    // Set recipe file
    commitMessage += " ${buildInfo.recipe}"
    def recipe = "${manifestConfigs.MANIFEST_DIR}/${buildInfo.recipe}"

    // Update images from build info
    buildInfo.images.each { key, val ->
        // Debug
        echo "Updating build image ${val} for ${key}"

        recipeContent."${buildTag}".images."${key}" = "${val}"
    }

    // Set build repositories and version
    if (buildInfo.repositories) {
        // Debug
        echo "Updating build version ${buildInfo.version}"

        recipeContent."${buildTag}".version = "${buildInfo.version}"
        recipeContent."${buildTag}".repositories = "${buildInfo.repositories}"

        commitMessage += " with version ${buildInfo.version}"
    }

    // Debug
    echo "Updating build content to ${buildInfo.recipe}"

    // Remove unnecessary keys from buildInfo
    build.remote("recipe")
    build.remote("platform")
    build.remote("type")

    // Write build info to recipe
    writeYaml file: recipe, data: recipeContent, overwrite: true

    // Change directory to manifest repo
    sh("cd ${manifestConfigs.MANIFEST_DIR} && git status")

    // Add new file to commit
    sh("git add .")

    // Commit changes
    sh("git commit -m '${commitMessage}'")

    // Push changes to repo
    // sh("git push HEAD:main")
}