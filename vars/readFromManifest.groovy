// Method to read builds from manifest

import main.groovy.cephJobLib.jobConfigs
import main.groovy.cephJobLib.manifestConfigs


def call(Map parameters) {
    // Get recipe file path
    def recipe = parameters.get("recipe")
    if (!recipe) {
        throw new RuntimeException(
            "Mandatory parameter 'recipe' is not provided"
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
    }

    // Checkout manifest repo to agent
    checkout([
        $class: "GitSCM",
        branches: [
            [name:  "${manifestConfigs.MANIFEST_BRANCH}"]
        ],
        userRemoteConfigs: [
            [
                url: "${manifestConfigs.MANIFEST_REPO}",
                credentialsId: "ceph-qe-sa-pat"
            ]
        ],
        extensions: [
            [
                $class: "RelativeTargetDirectory",
                relativeTargetDir: "${jobDir}"
            ],
            [
                $class: "CloneOption",
                depth: 1,
                noTags: true,
                shallow: true,
                timeout: 10,
                reference: ""
            ]
        ]
    ])

    // Check if recipe file exists
    if (!fileExists("${jobDir}/${recipe}")) {
        echo "Recipe file ${recipe} does not exist, creating a new one"
        writeFile file: "${jobDir}/${recipe}", text: ""
        return [:]
    }

    // Debug
    echo "Reading build content from recipe ${recipe}"

    // Read recipe file
    def recipePath = "${jobDir}/${recipe}"
    def recipeContent
    try {
        recipeContent = readYaml file: recipePath
    } catch (Exception e) {
        error("Failed to read/parse YAML from manifest recipe file: ${recipePath}. Error: ${e}")
    }

    if (recipeContent == null) {
        error("Manifest recipe YAML parsed to null: ${recipePath}")
    }

    // Return content
    return recipeContent
}
