/**
 * Shared post-build actions for ODF/FDF interop pipelines.
 *
 * Parameters:
 *   product      - odf | fdf
 *   cosPrefix    - COS upload path prefix (odf-interop | fdf-interop)
 *   includeOcsci - publish junit + archive ocs-ci artifacts (default false)
 */
def call(Map parameters) {
    def product = (parameters.product ?: 'odf').toString().toLowerCase()
    def cosPrefix = parameters.cosPrefix ?: "${product}-interop"
    def includeOcsci = parameters.includeOcsci ?: false

    def envName = env.ENV_NAME ?: ""
    def clusterName = env.OCP_CLUSTER_NAME ?: env.CLUSTER_NAME ?: ""
    def hasCluster = env.TEARDOWN_REQUIRED == 'true' && envName
    def clusterId = env.CLUSTER_ID ?: ''
    def paths = (hasCluster && clusterId) ? cephInteropPaths(envName: envName, clusterId: clusterId) : null
    def venvDir = "${env.WORKSPACE}/ceph-interop/.venv"

    def contextPath = env.CONTEXT_PATH ?: ''
    def configurationPath = env.CONFIGURATION_PATH ?: ''
    if (env.TEARDOWN_REQUIRED == 'true' && envName && clusterId && fileExists("${venvDir}/bin/ceph-interop")) {
        if (!contextPath) {
            echo "Skipping teardown: CONTEXT_PATH not set (updateCephInteropConfig may not have run)"
        } else {
            try {
                dir("ceph-interop") {
                    def stateFile = paths?.stateJson ?: "work/context/${envName}/${clusterId}/state.json"
                    if (!fileExists(stateFile)) {
                        echo "Skipping teardown: no cluster state at ${stateFile}"
                    } else {
                        def configArg = configurationPath ?: 'configs/configuration.yaml'
                        echo "Tearing down env: ${envName} (context: ${contextPath}, configuration: ${configArg})"
                        sh """
                            ${venvDir}/bin/ceph-interop teardown \
                                --context '${contextPath}' \
                                --env-name '${envName}' \
                                --cluster-id '${clusterId}' \
                                --level all \
                                --credentials configs/credentials.yaml \
                                --configuration '${configArg}'
                        """
                    }
                }
            } catch (Exception e) {
                echo "Teardown failed: ${e.message}"
                currentBuild.result = 'FAILURE'
            }
        }
    }

    try {
        dir("ceph-interop") {
            if (hasCluster && paths) {
                if (fileExists(paths.stateJson)) {
                    archiveArtifacts artifacts: paths.stateJson
                }
                sh """
                    if [ -d "${paths.relRoot}" ]; then
                        tar czf "cluster-artifacts-${envName}.tar.gz" \
                            --exclude='bin' \
                            -C work/context/${envName} \
                            "${clusterId}" 2>/dev/null || true
                    fi
                """
                if (fileExists("cluster-artifacts-${envName}.tar.gz")) {
                    archiveArtifacts artifacts: "cluster-artifacts-${envName}.tar.gz"
                }
                if (fileExists("${paths.relRoot}/openshift/auth")) {
                    archiveArtifacts artifacts: "${paths.relRoot}/openshift/auth/*"
                }
                archiveArtifacts artifacts: "${paths.relRoot}/workload-report-*.txt", allowEmptyArchive: true
            }
        }
    } catch (Exception e) {
        echo "Artifact archival failed (non-fatal): ${e.message}"
    }

    def logDir = "ocs-ci-logs-${env.BUILD_NUMBER}"
    def acceptanceSummary = null
    def tier1Summary = null

    if (includeOcsci) {
        try {
            dir("ocs-ci") {
                if (fileExists(logDir)) {
                    archiveArtifacts artifacts: "${logDir}/*", allowEmptyArchive: true
                    sh "tar czf ${logDir}.tar.gz --exclude='*-report.html' --exclude='*-junit-results.xml' ${logDir}/ 2>/dev/null || true"
                    if (fileExists("${logDir}.tar.gz")) {
                        archiveArtifacts artifacts: "${logDir}.tar.gz"
                    }
                }
            }
        } catch (Exception e) {
            echo "ocs-ci artifact archival failed (non-fatal): ${e.message}"
        }

        try {
            def acceptanceJunit = "ocs-ci/${logDir}/acceptance-junit-results.xml"
            if (fileExists(acceptanceJunit)) {
                def result = junit testResults: acceptanceJunit, allowEmptyResults: true
                acceptanceSummary = [total: result.totalCount, pass: result.passCount, fail: result.failCount, skip: result.skipCount]
            }
        } catch (Exception ignored) {}
        try {
            def tier1Junit = "ocs-ci/${logDir}/tier1-junit-results.xml"
            if (fileExists(tier1Junit)) {
                def result = junit testResults: tier1Junit, allowEmptyResults: true
                tier1Summary = [total: result.totalCount, pass: result.passCount, fail: result.failCount, skip: result.skipCount]
            }
        } catch (Exception ignored) {}
    }

    def cosRemote = "ceph-qe-cos"
    def cosBucket = "ceph-qe-logs"
    def cosBaseUrl = "https://10.8.128.2/cephci-jenkins/ibm-cos"
    def cosBasePath = "${cosPrefix}/${env.BUILD_NUMBER}"
    def cosResultsUrl = ''
    try {
        def hasRclone = sh(script: "rclone listremotes 2>/dev/null | grep -q '^${cosRemote}:'", returnStatus: true) == 0
        if (hasRclone) {
            def filesToUpload = []
            def clusterTar = "ceph-interop/cluster-artifacts-${envName}.tar.gz"
            if (fileExists(clusterTar)) { filesToUpload << clusterTar }
            if (includeOcsci) {
                def ocsciTar = "ocs-ci/${logDir}.tar.gz"
                if (fileExists(ocsciTar)) { filesToUpload << ocsciTar }
            }
            for (f in filesToUpload) {
                sh "rclone copy ${f} ${cosRemote}:${cosBucket}/${cosBasePath}/ --progress --transfers=8 --checkers=8"
            }
            if (filesToUpload) {
                cosResultsUrl = "${cosBaseUrl}/${cosBasePath}/"
            }
        }
    } catch (Exception e) {
        echo "COS upload failed (non-fatal): ${e.message}"
    }

    try {
        sendInteropEmail(
            envName: envName,
            clusterName: clusterName,
            hasCluster: hasCluster,
            product: product,
            includeOcsci: includeOcsci,
            acceptanceSummary: acceptanceSummary,
            tier1Summary: tier1Summary,
            cosResultsUrl: cosResultsUrl,
        )
    } catch (Exception e) {
        echo "Email notification failed: ${e.message}"
    }

    dir("ceph-interop") {
        sh 'rm -f configs/credentials.yaml configs/pull-secret.json 2>/dev/null || true'
    }
    if (includeOcsci) {
        dir("ocs-ci") {
            sh 'rm -f interop-runtime-config.yaml data/pull-secret 2>/dev/null || true'
        }
    }
}
