import main.groovy.cephJobLib.ibmcConfigs

def call(Map parameters) {
    // Set auth path
    def authFile = "/home/jenkins/.docker/config.json"

    // Check for mandatory parameter
    def containerImage = parameters.get("containerImage")
    if (!containerImage) {
        throw new RuntimeException(
            "Mandatory parameter `containerImage` not provided."
        )
    }

    // Set container registry
    def containerImageList = containerImage.tokenize('/')
    def containerRegistry = containerImageList.removeAt(0)
    def imageNameAndTag = containerImageList.pop()
    def (imageName, containerTag) = imageNameAndTag.tokenize(':')

    containerImageList += [imageName]
    imageName = containerImageList.join("/")

    // Set source container image
    def srcContainerImage = ""
    if (containerRegistry == "registry-proxy.engineering.redhat.com") {
        srcContainerImage = "docker://${ibmcConfigs.REDHAT_BREW_REGISTRY_HOST}/${imageName}:${containerTag}"
    } else {
        srcContainerImage = "docker://${containerRegistry}/${imageName}:${containerTag}"
        imageName = imageName.replaceAll("@sha256", "")
    }

    // Set destination container image
    def destContainerImage = "docker://${ibmcConfigs.IBM_NGINX_REGISTRY_HOST}:${ibmcConfigs.IBM_NGINX_REGISTRY_PORT}/"
    destContainerImage += "rh-stage/${imageName}:${containerTag}"

    // Sync image to NGINX server
    def skopeoCmd = "skopeo copy --authfile ${authFile} "
    skopeoCmd += "--src-tls-verify=true --dest-tls-verify=false "
    skopeoCmd += "${srcContainerImage} ${destContainerImage}"

    // Execute the skopeo command
    sh(script: skopeoCmd)

    // Verify sync command
    def curlImages = "curl -s "
    curlImages += "http://${ibmcConfigs.IBM_NGINX_REGISTRY_HOST}:${ibmcConfigs.IBM_NGINX_REGISTRY_PORT}/"
    curlImages += "v2/rh-stage/${imageName}/tags/list | jq -M ."

    // Validate image push
    def stdout = sh(script: curlImages, returnStdout: true)
    echo "${stdout}"
}
