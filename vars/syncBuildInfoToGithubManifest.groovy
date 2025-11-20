// Method to sync build information to GitHub manifest repository
// IBM only - Always updates 'nightly' section, and optionally 'rc' section if releaseCandidate is enabled

import main.groovy.cephJobLib.jobConfigs
import main.groovy.cephJobLib.manifestConfigs

def call(Map parameters) {
    // Validate required parameters
    def cephVersion = parameters.cephVersion
    def cephBuildVersion = parameters.cephBuildVersion
    def images = parameters.images
    def repos = parameters.repos
    def releaseCandidate = parameters.get("releaseCandidate", false)

    // Hardcode product to IBM
    def product = "ibm"

    // Determine manifest file path - IBM only
    def manifestFile = "ibm/${cephVersion}.yaml"
    def jobDir = [
        jobConfigs.MANIFEST_DIR_PATH, "sync-ibm-build", env.BUILD_NUMBER, manifestConfigs.MANIFEST_DIR
    ].join("/")

    println("Syncing build information to GitHub Ceph QE manifest:")
    println("  Product: ${product}")
    println("  File: ${manifestFile}")
    println("  Section: nightly" + (releaseCandidate ? " and rc" : ""))
    println("  Ceph Build Version: ${cephBuildVersion}")

    // Checkout manifest repository
    checkout([
        $class: "GitSCM",
        branches: [[name: "*/${manifestConfigs.MANIFEST_BRANCH}"]],
        userRemoteConfigs: [
            [
                url: "${manifestConfigs.MANIFEST_REPO}",
                credentialsId: "ceph-qe-sa-pat"
            ]
        ],
        extensions: [
            [
                $class: "RelativeTargetDirectory",
                relativeTargetDir: jobDir
            ],
            [
                $class: "CloneOption",
                depth: 1,
                noTags: true,
                shallow: true,
                timeout: 10
            ]
        ]
    ])

    // Change to manifest directory
    dir(jobDir) {
        // Read existing manifest content
        def manifestContent = [:]
        if (fileExists(manifestFile)) {
            try {
                manifestContent = readYaml file: manifestFile
                println("Found existing manifest file, updating nightly section")
            } catch (Exception e) {
                println("Warning: Could not read existing manifest file: ${e.message}")
                println("Creating new manifest file")
            }
        } else {
            println("Manifest file does not exist, creating new one")
        }

        // Initialize nightly section if not present
        if (!manifestContent.nightly) {
            manifestContent.nightly = [:]
        }

        // Update version with provided cephBuildVersion
        manifestContent.nightly.version = cephBuildVersion.trim()
        println("  Version: ${cephBuildVersion}")

        // Initialize repositories structure if not present
        if (!manifestContent.nightly.repositories) {
            manifestContent.nightly.repositories = [:]
        }
        if (!manifestContent.nightly.repositories.default) {
            manifestContent.nightly.repositories.default = [:]
        }
        if (!manifestContent.nightly.repositories."eu-de") {
            manifestContent.nightly.repositories."eu-de" = [:]
        }

        // Parse and update repositories
        // Repos are provided as URLs, e.g.:
        // https://public.dhe.ibm.com/.../IBM-CEPH-9.0-...-rhel9.repo
        // https://public.dhe.ibm.com/.../IBM-CEPH-9.0-...-rhel10.repo
        def repoList = repos.split("\r?\n").findAll { it.trim() }
        repoList.each { repoLine ->
            def trimmed = repoLine.trim()
            if (trimmed.isEmpty()) {
                return
            }

            def repoUrl = trimmed
            def platform = null

            // Determine platform from URL content (rhel9 or rhel10 in filename or path)
            // Check for rhel10 first (to avoid matching rhel9 in rhel10)
            if (trimmed.contains("rhel10") || trimmed.contains("rhel-10") || 
                trimmed.contains("/rhel10/") || trimmed.contains("/rhel-10/") ||
                trimmed.contains("-rhel10.") || trimmed.contains("-rhel-10.")) {
                platform = "rhel-10"
            } else if (trimmed.contains("rhel9") || trimmed.contains("rhel-9") || 
                       trimmed.contains("/rhel9/") || trimmed.contains("/rhel-9/") ||
                       trimmed.contains("-rhel9.") || trimmed.contains("-rhel-9.")) {
                platform = "rhel-9"
            } else {
                // Error if cannot determine RHEL version
                error("Unable to determine RHEL version from repository URL. URL: ${trimmed}")
            }

            // Update default repository
            manifestContent.nightly.repositories.default[platform] = repoUrl
            println("  Repository (default/${platform}): ${repoUrl}")

            // Derive eu-de URL from default URL by detecting product from URL
            def euDeUrl = repoUrl
            def cephMajorVersion = cephVersion.split("\\.")[0]
            
            def eudeplatform = platform.replace("-", "")
            // IBM repository: Replace public.dhe.ibm.com with repo.qe.ceph.eu.lab
            euDeUrl = "http://repo.qe.ceph.lab/repos/ceph/testing/ibm/${cephMajorVersion}/${eudeplatform}/${cephBuildVersion}/"
            
            manifestContent.nightly.repositories."eu-de"[platform] = euDeUrl
            println("  Repository (eu-de/${platform}): ${euDeUrl}")
        }

        // Initialize images map if not present
        if (!manifestContent.nightly.images) {
            manifestContent.nightly.images = [:]
        }

        // Parse and update images
        // Images are provided as container image URLs, e.g.:
        // registry/cp/ceph/alloy-rhel10:v9.0-5968.20251009190059.e5b34f6
        // registry/cp/ceph/ceph-9-rhel9:v9.0-7377.20251112200656.38f3db6
        def imageList = images.split("\r?\n").findAll { it.trim() }
        imageList.each { imageLine ->
            def trimmed = imageLine.trim()
            if (trimmed.isEmpty()) {
                return
            }

            def imageUrl = trimmed
            def imageName = null

            // Extract image name from URL by detecting patterns
            // Check for specific image names first (more specific patterns first)
            if (trimmed.contains("nvmeof-cli")) {
                imageName = "nvmeof_cli_image"
            } else if (trimmed.contains("samba-metrics")) {
                imageName = "samba_metrics_image"
            } else if (trimmed.contains("samba-server")) {
                imageName = "samba_image"
            } else if (trimmed.contains("prometheus-node-exporter")) {
                imageName = "node_exporter_image"
            } else if (trimmed.contains("prometheus-alertmanager")) {
                imageName = "alertmanager_image"
            } else if (trimmed.contains("prometheus") && !trimmed.contains("alertmanager") && !trimmed.contains("node-exporter")) {
                imageName = "prometheus_image"
            } else if (trimmed.contains("oauth2-proxy")) {
                imageName = "oauth2_proxy_image"
            } else if (trimmed.contains("nvmeof") && !trimmed.contains("nvmeof-cli")) {
                imageName = "nvmeof_image"
            } else if (trimmed.contains("crimson")) {
                imageName = "crimson_image"
            } else if (trimmed.contains("ceph-base") || trimmed.matches(/.*\/ceph-\d+-rhel\d+.*/)) {
                // Matches ceph-base or ceph-8-rhel9, ceph-9-rhel9, ceph-10-rhel10, etc.
                imageName = "ceph-base"
            } else if (trimmed.contains("cephcsi")) {
                imageName = "ceph_csi_image"
            } else if (trimmed.contains("grafana")) {
                imageName = "grafana_image"
            } else if (trimmed.contains("haproxy")) {
                imageName = "haproxy_image"
            } else if (trimmed.contains("keepalived")) {
                imageName = "keepalived_image"
            } else if (trimmed.contains("logging-loki") || trimmed.contains("loki")) {
                imageName = "loki_image"
            } else if (trimmed.contains("nginx")) {
                imageName = "nginx_image"
            } else if (trimmed.contains("snmp-notifier") || trimmed.contains("snmp")) {
                imageName = "snmp_gateway_image"
            } else if (trimmed.contains("alloy")) {
                imageName = "alloy_image"
            } else if (trimmed.contains("promtail")) {
                imageName = "promtail_image"
            } else {
                imageName = "unknown"
                println("  Warning: Could not determine image name from URL, using 'unknown'")
                println("  URL: ${trimmed}")
            }
            
            // Store the image
            manifestContent.nightly.images[imageName] = imageUrl
            println("  Image (${imageName}): ${imageUrl}")
        }

        // If releaseCandidate is enabled, copy nightly section to rc section
        if (releaseCandidate == true) {
            println("Release Candidate mode enabled: copying nightly section to rc section")
            // Deep copy the nightly section to rc
            manifestContent.rc = [
                version: manifestContent.nightly.version,
                repositories: [
                    default: [:],
                    "eu-de": [:]
                ],
                images: [:]
            ]

            // Copy repositories
            if (manifestContent.nightly.repositories?.default) {
                manifestContent.nightly.repositories.default.each { platform, url ->
                    manifestContent.rc.repositories.default[platform] = url
                }
            }
            if (manifestContent.nightly.repositories?."eu-de") {
                manifestContent.nightly.repositories."eu-de".each { platform, url ->
                    manifestContent.rc.repositories."eu-de"[platform] = url
                }
            }

            // Copy images
            if (manifestContent.nightly.images) {
                manifestContent.nightly.images.each { imageName, imageUrl ->
                    manifestContent.rc.images[imageName] = imageUrl
                }
            }
            
            println("  Successfully copied nightly section to rc section")
        }

        // Write updated manifest
        println("Writing updated manifest to ${manifestFile}")
        writeYaml file: manifestFile, data: manifestContent, overwrite: true

        // Commit mechanism: Configure git user
        sh("git config user.name 'ceph-qe-sa'")
        sh("git config user.email 'ceph-qe-sa@ibm.com'")

        // Check git status to see if there are changes
        def gitStatus = sh(
            script: "git status --porcelain",
            returnStdout: true
        ).trim()

        if (gitStatus) {
            println("Changes detected:")
            println(gitStatus)

            // Stage changes
            sh("git add ${manifestFile}")

            // Create commit message
            def commitMessage = "Update ${product} manifest ${manifestFile}"
            if (releaseCandidate) {
                commitMessage += " - nightly and rc sections\n\n"
            } else {
                commitMessage += " - nightly section\n\n"
            }
            commitMessage += "Product: ${product}\n"
            commitMessage += "Ceph Version: ${cephVersion}\n"
            commitMessage += "Ceph Build Version: ${cephBuildVersion}\n"
            if (releaseCandidate) {
                commitMessage += "Sections: nightly, rc\n"
            } else {
                commitMessage += "Section: nightly\n"
            }
            commitMessage += "Build: ${env.BUILD_NUMBER}\n"
            commitMessage += "Build URL: ${env.BUILD_URL}"

            // Commit changes
            sh("git commit -m '${commitMessage}'")

            // Push changes to manifest repository
            sh("git push origin HEAD:${manifestConfigs.MANIFEST_BRANCH}")

            println("Successfully committed and pushed manifest update to ${manifestConfigs.MANIFEST_REPO}")
            println("  Branch: ${manifestConfigs.MANIFEST_BRANCH}")
            println("  File: ${manifestFile}")
        } else {
            println("No changes detected in manifest file, skipping commit and push")
        }
    }
}

