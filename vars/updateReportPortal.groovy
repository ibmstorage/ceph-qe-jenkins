/*
    Library to update results to report portal
*/

import main.groovy.cephJobLib.cephciConfigs
import main.groovy.cephJobLib.rpConfigs

def call(Map parameters) {
    // Get payload directory
    def resultsDir = parameters.get("resultsDir")
    if (!resultsDir) {
        throw new RuntimeException(
            "Report Portal Payload dir is not provided")
    }

    // Check for results file
    dir("${resultsDir}/results") {
        if (!findFiles(glob: '*.xml')) {
            throw new RuntimeException("No results present under ${resultsDir}/results")
        }
    }

    // Get launcher details
    def rpLaunchName = parameters.get("rpLaunchName")
    def rpLaunchDesc = parameters.get("rpLaunchDesc")
    def rpLaunchAttributes = parameters.get("rpLaunchAttributes")
    if (!rpLaunchName || !rpLaunchDesc || !rpLaunchAttributes) {
        throw new RuntimeException(
            "Report Portal launcher name and/or description and/or attribute and/or url not provided")
    }

    def rpProjectName = parameters.get("rpProjectName")
    if (!rpProjectName) {
        throw new RuntimeException(
            "Report Portal project name not provided")
    }

    // Get service details
    def rpHostname = parameters.get("rpHostname", rpConfigs.HOSTNAME)
    def rpHostUrl = parameters.get("rpHostUrl", rpConfigs.HOST_URL)
    def rpConfig = parameters.get("rpConfig", "${resultsDir}/${rpConfigs.RP_CONFIG}")

    // Create config content
    def rpConfigContent = """{
        "targets": {
            "reportportal": {
                "config": {
                    "hostname": "${rpHostname}",
                    "project": "${rpProjectName}"
                },
                "processing": {
                    "apply_tfa": true,
                    "merge_launches": true,
                    "simple_xml": false,
                    "auto_dashboard": false,
                    "launch_with_class_name": true,
                    "property_filter": [".*"],
                    "debug": true,
                    "launch": {
                        "name": "${rpLaunchName}",
                        "description": "${rpLaunchDesc}",
                        "attributes": ${rpLaunchAttributes}
                    }
                },
                "tfa": {
                    "add_attributes": true,
                    "auto_finalize_defect_type": true,
                    "auto_finalization_threshold": 0.70
                }
            }
        }
    }"""
    writeFile file: rpConfig, text: rpConfigContent

    // Update report portal
    def content = ""
    withCredentials([usernamePassword(credentialsId: "oidc-sa", usernameVariable: "USERNAME", passwordVariable: "PASSWORD")]) {
        // Update results
        def cmd = [
            "podman run -v",
            "${resultsDir}:/data:Z ${rpConfigs.DATA_ROUTER_IMAGE} send",
            "--url ${rpConfigs.DATA_ROUTER_URL}",
            "--username ${USERNAME}",
            "--password ${PASSWORD}",
            "--metadata /data/${rpConfigs.RP_CONFIG}",
            "--results '/data/results/*.xml'",
        ]

        // Update results to report portal
        content = sh(script: cmd.join(" "), returnStdout: true).trim()
    }
    // validate cmd output
    if (content) {
        echo "Command executed successfully"
    } else {
        error "Command failed with exit code: ${status}"
    }
}
