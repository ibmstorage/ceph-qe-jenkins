// Method to validate konflux build message


def call(Map parameters) {
    // Set build config
    def buildInfo = [:]
    buildInfo.type = ""

    // Get mandatory parameter
    def buildMessage = parameters.get("buildMessage")
    if (!buildMessage) {
        throw new RuntimeException(
            "Mandatory buildMessage parameter not provided"
        )
    }

    // Convert build message to map
    buildMessage = readJSON text: buildMessage

    // Check for mandatory configs
    def application = buildMessage.get("application")
    if (!application) {
        throw new RuntimeException(
            "Mandatory build parameter application not provided"
        )
    }

    // Set recipe based on application
    if (application == "ceph-8-0") {
        buildInfo.recipe = "8.0.yaml"
    } else if(application == "ceph-8-1") {
        buildInfo.recipe = "8.1.yaml"
    } else if(application == "ceph-9-0") {
        buildInfo.recipe = "9.0.yaml"
    } else {
       throw new RuntimeException("Unsuported application ${application}")
    }

    // Debug
    echo "Recieved build info for application ${application}"

    // Set components
    if (buildMessage.get("components")) {
        buildInfo.images = [:]
        buildMessage.components.each { component ->
            // Check for build type
            if (!buildInfo.type) {
                if (component.containerImage.contains("ibm-ceph")) {
                    buildInfo.type = "ibm"
                }
                else if (
                    component.containerImage.contains("rh-osbs") ||
                    component.containerImage.contains("rhceph-dev")
                ) {
                    buildInfo.type = "redhat"
                }
                else {
                    currentBuild.result = "ABORTED"
                    error("Unsuported ceph base image ${component.containerImage}")
                }

                // Debug
                echo "Recieved build info for ${buildInfo.type} build"
            }

            // Update image key based on build recieved
            if(component.name.startsWith("ceph")) {
                buildInfo.images."ceph-base" = component.containerImage

                // Check for build platform
                if(component.containerImage.contains("rhel9")) {
                    buildInfo.platform = "rhel-9"
                }
                else if (component.containerImage.contains("rhel10")) {
                    buildInfo.platform = "rhel-10"
                }
                else {
                    throw new RuntimeException(
                        "Unsuported ceph base image ${component}"
                    )
                }

                // Debug
                echo "Recieved build info for platform ${buildInfo.platform}"
            }
            else if(component.name.startsWith("prometheus")) {
                buildInfo.images.prometheus_image = component.containerImage
            }
            else if (component.name.startsWith("prometheus-node-exporter")) {
                buildInfo.images.node_exporter_image = component.containerImage
            }
            else if (component.name.startsWith("grafana")) {
                buildInfo.images.grafana_image = component.containerImage
            }
            else if (component.name.startsWith("prometheus-alertmanager")) {
                buildInfo.images.alertmanager_image = component.containerImage
            }
            else if (component.name.startsWith("haproxy")) {
                buildInfo.images.haproxy_image = component.containerImage
            }
            else if (component.name.startsWith("keepalived")) {
                buildInfo.images.keepalived_image = component.containerImage
            }
            else if (component.name.startsWith("snmp-notifier")) {
                buildInfo.images.snmp_gateway_image = component.containerImage
            }
            else if (component.name.startsWith("logging-loki")) {
                buildInfo.images.loki_image = component.containerImage
            }
            else if (component.name.startsWith("promtail")) {
                buildInfo.images.promtail_image = component.containerImage
            }
            else if (component.name.startsWith("nvmeof")) {
                buildInfo.images.nvmeof_image = component.containerImage
            }
            else if (component.name.startsWith("nvmeof-cli")) {
                buildInfo.images.nvmeof_cli_image = component.containerImage
            }
            else if (component.name.startsWith("samba-server")) {
                buildInfo.images.samba_image = component.containerImage
            }
            else if (component.name.startsWith("samba-metrics")) {
                buildInfo.images.samba_metrics_image = component.containerImage
            }
            else if (component.name.startsWith("oauth2-proxy")) {
                buildInfo.images.oauth2_proxy_image = component.containerImage
            }
            else if (component.name.startsWith("nginx")) {
                buildInfo.images.nginx_image = component.containerImage
            }
            else {
                print("Unsupported image ${component.name}")
            }
        }

        // Debug
        echo "Recieved build images ${buildInfo.images}"
    }

    // Set repositories
    if (buildMessage.artifacts) {
        buildInfo.repositories = [:]
        buildMessage.artifacts.each { platform, repo ->
            // Check for build type
            if (!buildInfo.type) {
                if (repo.contains("IBM-CEPH")) {
                    buildInfo.type = "ibm"
                }
                else if (repo.contains("RHCEPH")) {
                    buildInfo.type = "redhat"
                }
                else {
                    currentBuild.result = "ABORTED"
                    error("Unsuported repository ${repo}")
                }
            }

            // Check for platform
            if(platform == "rhel9") {
                buildInfo.repositories."rhel-9" = repo
            }
            else if (platform == "rhel10") {
                buildInfo.repositories."rhel-10" = repo
            }
            else {
                throw new RuntimeException("Unsupported platform ${platform}")
            }
        }

        // Get ceph version
        buildInfo.version = fetchCephVersion(
            platform: "${buildInfo.platform}",
            rhsCephRepo: buildInfo.repositories."${buildInfo.platform}",
            buildType: "${buildInfo.type}"
        )

        // Debug
        echo "Recieved build info for version ${buildInfo.version}"
    }

    // Set recipe file
    buildInfo.recipe = "${buildInfo.type}/${buildInfo.recipe}"

    // Return build info
    return buildInfo
}