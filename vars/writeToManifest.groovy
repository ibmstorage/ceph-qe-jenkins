// Method to update manifest with builds

import main.groovy.cephJobLib.jobConfigs
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

    // Get job directory
    def job = parameters.get("job")
    def jobDir = parameters.get("jobDir")
    if (!jobDir) {
        if (!job) {
            throw new RuntimeException(
                "Either job or jobDir parameter must be provided"
            )
        }
        jobDir = [
            jobConfigs.MANIFEST_DIR_PATH, job, env.BUILD_NUMBER, manifestConfigs.MANIFEST_DIR
        ].join("/")
        parameters.jobDir = jobDir
    }

    // Get recipe file content
    def recipeContent = readFromManifest(recipe: buildInfo.recipe, jobDir: jobDir)

    // Initialize tag map if not present
    if (!recipeContent."${buildTag}") {
        recipeContent."${buildTag}" = [:]
    }

    // Set recipe file
    commitMessage += " ${buildInfo.recipe}"
    def recipe = "${jobDir}/${buildInfo.recipe}"


    // Initialize images map if not present
    if (!recipeContent."${buildTag}".images) {
        recipeContent."${buildTag}".images = [:]
    }

    // Update images from build info
    buildInfo.images.each { key, val ->
        // Debug
        echo "Updating build image ${val} for ${key}"
        recipeContent."${buildTag}".images."${key}" = "${val}"
    }

    if (buildInfo.repositories) {
        // Debug
        echo "Updating build version ${buildInfo.version}"

        // Update ceph version
        recipeContent."${buildTag}".version = "${buildInfo.version}"

        // Initialize repositories map if not present
        if (!recipeContent."${buildTag}".repositories) {
            recipeContent."${buildTag}".repositories = [:]
        }

        // Update repositories if present
        buildInfo.repositories.each { platform, repo ->
            // Debug
            echo "Updating build repository ${repo} for ${platform}"
            recipeContent."${buildTag}".repositories."${manifestConfigs.DEFAULT_DC}"."${platform}" = "${repo}"
        }

        // Update commit message
        commitMessage += " with version ${buildInfo.version}"
    }

    // Debug
    echo "Updating build content to ${recipe}"

    // Write build info to recipe
    writeYaml file: recipe, data: recipeContent, overwrite: true

    // Change directory to manifest repo
    dir(jobDir) {
        // Get diff and decide whether to commit based on diff results
        def gitDiff = sh(
            script: "git diff",
            returnStdout: true
        ).trim()

        env.CEPH_BASE_CHANGED = "false"
        if (gitDiff) {
            echo "Changes detected:\n${gitDiff}"

            // Check git diff for actual changes (+/-) to ceph-base
            def cephBaseChanged = sh(
                script: """
                    git diff |
                    grep -qE '^[+-].*ceph-base:' &&
                    echo true ||
                    echo false
                """,
                returnStdout: true
            ).trim()

            env.CEPH_BASE_CHANGED = cephBaseChanged

            // Add changed files
            sh("git add .")

            // Commit changes (shell injection hardening tracked separately)
            sh("git commit -m '${commitMessage}'")

            // Push changes to repo — retry on transient network failures,
            // abort with a clear message if the remote was never updated
            try {
                retry(3) {
                    sh("git push origin HEAD:main")
                }
            } catch (hudson.AbortException e) {
                error(
                    "Failed to push manifest update to origin/main after retries. " +
                    "Local commit exists but remote was NOT updated. Cause: ${e.message}"
                )
            }
        } else {
            echo "No changes to commit"
        }
    }
}
