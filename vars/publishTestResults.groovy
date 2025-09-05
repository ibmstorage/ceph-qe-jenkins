// Method to send email with test results

import main.groovy.cephJobLib.jobConfigs
import main.groovy.cephJobLib.ibmcConfigs


def call(Map parameters) {
    // Check for build parameters
    def buildType = parameters.get("buildType")
    def rhBuild = parameters.get("rhBuild")
    def platform = parameters.get("platform")
    if (!buildType || !rhBuild || !platform) {
        throw new RuntimeException(
            "Mandatory build parameters are not provided"
        )
    }

    // Check for job parameters
    def cloudType = parameters.get("cloudType")
    def emailAddr = parameters.get("emailAddr")
    def logDir = parameters.get("logDir")
    if (!cloudType || !emailAddr || !logDir) {
        throw new RuntimeException(
            "Mandatory job parameters are not provided"
        )
    }

    // Check for results parameters
    def resultsDir = parameters.get("resultsDir")
    def resultsSummary = parameters.get("resultsSummary")
    def artifacts = parameters.get("artifacts")
    def emailBody = parameters.get("emailBody")
    if (!resultsDir || !resultsSummary || !artifacts || !emailBody) {
        throw new RuntimeException(
            "Mandatory results parameters are not provided"
        )
    }

    // Check for ceph package parameters
    rhsCephRepo = parameters.get("rhsCephRepo")
    containerImage = parameters.get("containerImage")
    cephVersion = parameters.get("cephVersion")
    if (!rhsCephRepo || !containerImage || !cephVersion) {
        throw new RuntimeException(
            "Mandatory ceph package parameters are not provided"
        )
    }

    // Generate results summary
    def updateResultsDir = "scripts/updateResultsDir.py"
    sh([
        "${jobConfigs.PYTHON} ${updateResultsDir} --logs ${logDir} ",
        "--results-dir ${resultsDir} --results-summary ${resultsSummary}"
    ].join(" "))

    // Create test artifacts Content
    def testResult = readFile file: "${resultsSummary}"
    def artifactsContent = """{
        "BuildType": "${buildType}",
        "Release": "${rhBuild}",
        "CephVersion": "${cephVersion}",
        "RhelVersion": "${platform}",
        "CloudType": "${cloudType}",
        "Job": "${env.BUILD_URL}",
        "Repo": "${rhsCephRepo}",
        "Container": "${containerImage}",
        "jobName": "${env.JOB_NAME}",
        "Results": ${testResult}
    }"""
    writeJSON file: "${artifacts}", json: artifactsContent

    // Create email through jinja
    sh([
        "jinja -d ${artifacts}",
        "templates/${jobConfigs.EMAIL_TEMPLATE}",
        "-o ${emailBody}"
    ].join(" "))

    // Set subject
    def subject = "${env.JOB_NAME.toUpperCase()}-${buildType}-${cephVersion}"
    subject += " Test Run Results"

    // Send email
    def body = readFile(file: "${emailBody}")
    emailext(
        mimeType: 'text/html',
        subject: "${subject}",
        body: "${body}",
        from: "${jobConfigs.CEPHCI_EMAIL}",
        to: "${emailAddr}"
    )

    // Copy logs to IBM COS
    def ibmcCosBucketPath = logDir.replace(jobConfigs.JOB_DIR_PATH, "")
    sh([
        "rclone copy ${logDir}",
        "${ibmcConfigs.IBM_COS_CRN}:${ibmcConfigs.IBM_COS_LOG_BUCKET}${ibmcCosBucketPath}",
        "--progress --transfers=8 --checkers=8 --verbose"
    ].join(" "))
}
