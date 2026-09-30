// Method to update manifest with builds

import main.groovy.cephJobLib.jobConfigs
import main.groovy.cephJobLib.manifestConfigs


@NonCPS
def orderNestedMap(sourceMap, referenceMap = null) {
    if (!(sourceMap instanceof Map)) {
        return sourceMap
    }
    def result = new LinkedHashMap()
    if (referenceMap instanceof Map) {
        referenceMap.each { key, refValue ->
            if (sourceMap.containsKey(key)) {
                def value = sourceMap[key]
                result[key] = (value instanceof Map && refValue instanceof Map) ?
                    orderNestedMap(value, refValue) :
                    value
            }
        }
    }
    sourceMap.each { key, value ->
        if (!result.containsKey(key)) {
            result[key] = value instanceof Map ?
                orderNestedMap(value, null) :
                value
        }
    }
    return result
}

// Manifest sections use version -> repositories -> images key order for readable diffs.
// Extra top-level keys (e.g. future fields) are preserved and appended after these
// canonical keys; they are not dropped.
@NonCPS
def normalizeManifestSection(section, referenceSection = null) {
    if (section == null) {
        return null
    }
    def ordered = new LinkedHashMap()
    def topLevelKeys = ["version", "repositories", "images"]
    topLevelKeys.each { key ->
        if (section.containsKey(key)) {
            if (key == "images" || key == "repositories") {
                ordered[key] = orderNestedMap(
                    section[key],
                    referenceSection?."${key}"
                )
            } else {
                ordered[key] = section[key]
            }
        }
    }
    section.each { key, value ->
        if (!topLevelKeys.contains(key)) {
            ordered[key] = value
        }
    }
    return ordered
}


def call(Map parameters) {
    // Set commit message
    def commitMessage = "Update recipe"

    def buildInfo = parameters.get("buildInfo") ?: [:]
    def buildTag = parameters.get("buildTag")
    def sectionContent = parameters.get("sectionContent")
    // Prefer top-level 'recipe' parameter; 'buildInfo.recipe' is deprecated
    def recipe = parameters.get("recipe") ?: buildInfo.recipe

    if (!buildTag) {
        throw new RuntimeException(
            "Mandatory parameter 'buildTag' is not provided"
        )
    }

    if (!recipe) {
        throw new RuntimeException(
            "Mandatory parameter 'recipe' is not provided (via 'recipe' or 'buildInfo.recipe')"
        )
    }

    if (!sectionContent && (!buildInfo.images || buildInfo.images.isEmpty())) {
        throw new RuntimeException(
            "Either 'sectionContent' or 'buildInfo.images' (non-empty) must be provided"
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
    def recipeContent = readFromManifest(recipe: recipe, jobDir: jobDir)

    commitMessage += " ${recipe}"
    def recipePath = "${jobDir}/${recipe}"

    if (sectionContent) {
        def sectionSource = parameters.get("sectionSource")
        def sourceLabel = sectionSource ?: "provided content"
        echo "Replacing '${buildTag}' manifest section with ${sourceLabel}"
        // sectionContent is an isolated snapshot from the caller (e.g. generateJobConfigs)
        def sourceSection = sectionContent
        def orderReference = recipeContent."${buildTag}" ?: sourceSection
        recipeContent."${buildTag}" = normalizeManifestSection(
            sourceSection,
            orderReference
        )
        commitMessage += sectionSource ?
            " - copy ${sectionSource} to ${buildTag}" :
            " - replace ${buildTag} section"
    } else {
        // Initialize tag map if not present
        if (!recipeContent."${buildTag}") {
            recipeContent."${buildTag}" = [:]
        }

        // Initialize images map if not present
        if (!recipeContent."${buildTag}".images) {
            recipeContent."${buildTag}".images = [:]
        }

        // Update images from build info
        if (buildInfo.images) {
            buildInfo.images.each { key, val ->
                echo "Updating build image ${val} for ${key}"
                recipeContent."${buildTag}".images."${key}" = "${val}"
            }
        }

        if (buildInfo.repositories) {
            echo "Updating build version ${buildInfo.version}"

            // Update ceph version
            recipeContent."${buildTag}".version = "${buildInfo.version}"

            // Initialize repositories map if not present
            if (!recipeContent."${buildTag}".repositories) {
                recipeContent."${buildTag}".repositories = [:]
            }

            // Update repositories if present
            buildInfo.repositories.each { platform, repo ->
                echo "Updating build repository ${repo} for ${platform}"
                recipeContent."${buildTag}".repositories."${manifestConfigs.DEFAULT_DC}"."${platform}" = "${repo}"
            }

            // Update commit message
            commitMessage += " with version ${buildInfo.version}"
        }
    }

    echo "Writing manifest changes to ${recipePath}"

    // Write build info to recipe
    try {
        writeYaml file: recipePath, data: recipeContent, overwrite: true
    } catch (Exception e) {
        error("Failed to write YAML to manifest recipe file: ${recipePath}. Error: ${e}")
    }

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

            // Pass commit message via environment variable so shell never
            // interprets user-controlled content — no temp file, no staging risk.
            withEnv(["COMMIT_MSG=${commitMessage}"]) {
                sh('git commit -m "$COMMIT_MSG"')
            }

            // Push changes to repo — retry on transient network failures,
            // abort with a clear message if the remote was never updated
            try {
                retry(3) {
                    sh("git push origin HEAD:main")
                }
            } catch (hudson.AbortException e) {
                error(
                    "Failed to push manifest update to origin/main after retries. " +
                    "Local commit exists but remote was NOT updated. Cause: ${e}"
                )
            }
        } else {
            echo "No changes to commit"
        }
    }
}
