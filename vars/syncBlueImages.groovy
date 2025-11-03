// Synchronize IBM Storage Ceph Container Images to local CR

def call(Map parameters) {

    println("Debug - Inside syncBlueImages workflow")
    def local_cr = " docker://registry.qe.ceph.eu.lab:5000/"
    def baseCmd = "skopeo copy --dest-tls-verify=false --authfile "

    parameters.images.stripIndent().trim().eachLine { image ->
        def srcImage = "docker://" + image
        def target_repo = "ibm-stage/"

        // Process the image
        def imageSplit = image.tokenize('/')
        def imageRegistry = imageSplit.removeAt(0)
        def imageAndTag = imageSplit.join('/')

        // Identify the local image repository to be used
        if ( imageRegistry == "cp.stg.icr.io" ) {
            target_repo = "ibm-stage/"
        } else if ( imageRegistry == "cp.icr.io" ) {
            target_repo = "ibm-prod/"
        } else if ( imageRegistry == "registry.stage.redhat.io" ) {
            target_repo = "rh-stage/"
        } else if ( imageRegistry == "quay.io" ) {
            target_repo = "rh-stage/"
        } else if ( imageRegistry == "registry.redhat.io" ) {
            target_repo = "rh-prod/"
        } else if ( imageRegistry == "brew.registry.redhat.io" ) {
            target_repo = "rh-stage/"
        }

        withCredentials([file(credentialsId: "all-cr-auth", variable: "CR_AUTH_FILE")]) {
            def cmdStr = baseCmd + '\${CR_AUTH_FILE} '
            cmdStr += srcImage
            cmdStr += local_cr
            cmdStr += target_repo
            cmdStr += imageAndTag

            sh(script: cmdStr)
        }
    }
}
