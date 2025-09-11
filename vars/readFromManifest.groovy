// Method to read builds from manifest

import main.groovy.cephJobLib.manifestConfigs


def call(Map parameters) {
    // Check for mandatory build parameters
    def buildInfo = parameters.buildInfo
    if (!buildInfo) {
        throw new RuntimeException(
            "Mandatory parameter buildInfo not provided"
        )
    }

    // Get recipe file path
    def recipe = buildInfo.recipe
    if (!recipe) {
        throw new RuntimeException(
            "Recipe file missing from build info"
        )
    }

    // Checkout manifest repo to agent
    checkout([
        $class: "GitSCM",
        branches: [
            [name:  "${manifestConfigs.MANIFEST_BRANCH}"]
        ],
        userRemoteConfigs: [
            [url: "${manifestConfigs.MANIFEST_REPO}"]
        ],
        extensions: [
            [
                $class: "RelativeTargetDirectory",
                relativeTargetDir: "${manifestConfigs.MANIFEST_DIR}"
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

    // Debug
    echo "Reading build content from recipe ${recipe}"

    // Get recipe file contents
    def recipeContent = readYaml file: "${manifestConfigs.MANIFEST_DIR}/${recipe}"

    // Return content
    return recipeContent
}