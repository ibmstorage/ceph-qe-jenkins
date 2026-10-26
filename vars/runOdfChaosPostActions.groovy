/**
 * Post-build actions for the odf-chaos pipeline only.
 *
 * Do not call this from odf-interop or fdf-interop. Those jobs stay on
 * runInteropPostActions / sendInteropEmail.
 *
 * Parameters:
 *   product   - default odf
 *   cosPrefix - COS upload path prefix (default odf-chaos)
 *   suites    - list of [id, label, passedEnv] for per-stage junit + email rows
 */
def call(Map parameters) {
    def product = (parameters.product ?: 'odf').toString().toLowerCase()
    def cosPrefix = parameters.cosPrefix ?: 'odf-chaos'
    def suites = parameters.suites ?: defaultChaosSuites()

    def envName = env.ENV_NAME ?: ""
    def clusterName = env.OCP_CLUSTER_NAME ?: env.CLUSTER_NAME ?: ""
    def hasCluster = env.TEARDOWN_REQUIRED == 'true' && envName
    def clusterId = env.CLUSTER_ID ?: ''
    def paths = (hasCluster && clusterId) ? cephInteropPaths(envName: envName, clusterId: clusterId) : null
    def venvDir = "${env.WORKSPACE}/ceph-interop/.venv"

    def contextPath = env.CONTEXT_PATH ?: ''
    def configurationPath = env.CONFIGURATION_PATH ?: ''
    // Retain the cluster on non-success so failures can be debugged live.
    // Teardown still runs on SUCCESS only.
    def buildResult = (currentBuild.currentResult ?: currentBuild.result ?: 'SUCCESS').toString()
    def retainOnFailure = !(buildResult in ['SUCCESS', 'NOT_BUILT'])
    if (env.TEARDOWN_REQUIRED == 'true' && envName && clusterId && fileExists("${venvDir}/bin/ceph-interop")) {
        if (retainOnFailure) {
            echo "Skipping teardown: build result is ${buildResult} " +
                "(cluster retained for debug: env=${envName}, cluster=${clusterName ?: 'n/a'}, id=${clusterId})"
        } else if (!contextPath) {
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
            }
        }
    } catch (Exception e) {
        echo "Artifact archival failed (non-fatal): ${e.message}"
    }

    def logDir = "ocs-ci-logs-${env.BUILD_NUMBER}"
    try {
        dir("ocs-ci") {
            // Secret-free runtime configs only — never archive auth-ibmcloud.yaml.
            if (fileExists('chaos-ibmcloud-platform-config.yaml')) {
                archiveArtifacts artifacts: 'chaos-ibmcloud-platform-config.yaml', allowEmptyArchive: true
            }
            if (fileExists('chaos-vdbench-runtime-config.yaml')) {
                archiveArtifacts artifacts: 'chaos-vdbench-runtime-config.yaml', allowEmptyArchive: true
            }
            if (fileExists(logDir)) {
                // Copy platform/vdbench configs into the log dir so the COS tarball includes them.
                sh """
                    cp -f chaos-ibmcloud-platform-config.yaml ${logDir}/ 2>/dev/null || true
                    cp -f chaos-vdbench-runtime-config.yaml ${logDir}/ 2>/dev/null || true
                """
                archiveArtifacts artifacts: "${logDir}/*", allowEmptyArchive: true
                // Keep junit and HTML in the COS tarball so per-stage reports are downloadable.
                sh "tar czf ${logDir}.tar.gz ${logDir}/ 2>/dev/null || true"
                if (fileExists("${logDir}.tar.gz")) {
                    archiveArtifacts artifacts: "${logDir}.tar.gz"
                }
            }
        }
    } catch (Exception e) {
        echo "ocs-ci artifact archival failed (non-fatal): ${e.message}"
    }

    def chaosSummaries = []
    def anyExecuted = false
    def anyFailed = false
    for (suite in suites) {
        def summary = publishChaosSuite(logDir, suite)
        chaosSummaries << summary
        if (summary.executed) {
            anyExecuted = true
            def failedCount = (summary.fail ?: 0) as int
            def errorCount = (summary.error ?: 0) as int
            if (summary.passedFlag == 'false' || failedCount > 0 || errorCount > 0) {
                anyFailed = true
            }
        }
    }
    if (anyExecuted) {
        env.CHAOS_PASSED = anyFailed ? 'false' : 'true'
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
            def ocsciTar = "ocs-ci/${logDir}.tar.gz"
            if (fileExists(ocsciTar)) { filesToUpload << ocsciTar }
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
        sendOdfChaosEmail(
            envName: envName,
            clusterName: clusterName,
            hasCluster: hasCluster,
            product: product,
            chaosSummaries: chaosSummaries,
            cosResultsUrl: cosResultsUrl,
        )
    } catch (Exception e) {
        echo "Email notification failed: ${e.message}"
    }

    dir("ceph-interop") {
        sh 'rm -f configs/credentials.yaml configs/pull-secret.json 2>/dev/null || true'
    }
    dir("ocs-ci") {
        sh '''
            rm -f auth-ibmcloud.yaml \
                chaos-ibmcloud-platform-config.yaml \
                chaos-vdbench-runtime-config.yaml \
                interop-runtime-config.yaml \
                chaos-runtime-config.yaml \
                data/pull-secret 2>/dev/null || true
        '''
    }
}

private Map publishChaosSuite(String logDir, Map suite) {
    def id = suite.id.toString()
    def label = (suite.label ?: id).toString()
    def passedEnv = (suite.passedEnv ?: '').toString()
    def passedFlag = passedEnv ? (readEnvFlag(passedEnv) ?: '') : ''
    def junitPath = "ocs-ci/${logDir}/chaos-${id}-junit-results.xml"
    def htmlPath = "ocs-ci/${logDir}/chaos-${id}-report.html"
    def executed = (passedFlag?.trim() ?: '') != '' || fileExists(junitPath)
    def summary = [
        id         : id,
        label      : label,
        passedFlag : passedFlag,
        executed   : executed,
        junitPath  : junitPath,
        htmlPath   : htmlPath,
        total      : null,
        pass       : null,
        fail       : null,
        error      : null,
        skip       : null,
    ]
    if (!fileExists(junitPath)) {
        return summary
    }
    // Keep Jenkins junit UI publish; email counts come from XML parse so
    // pytest <error> (teardown) is not folded into Failed.
    try {
        junit testResults: junitPath, allowEmptyResults: true
        summary.executed = true
    } catch (Exception ignored) {
        echo "Could not publish junit for chaos suite ${id}"
    }
    def parsed = parseChaosJunitCounts(junitPath)
    if (parsed) {
        summary.pass = parsed.pass
        summary.fail = parsed.fail
        summary.error = parsed.error
        summary.skip = parsed.skip
        summary.total = parsed.total
        summary.executed = true
    }
    return summary
}

/**
 * Count pass / failure / error / skip from a pytest JUnit XML file.
 * Distinguishes <failure> (assertion) from <error> (setup/teardown).
 * Uses a short Python parse so Jenkins CPS does not choke on XmlSlurper.
 */
private Map parseChaosJunitCounts(String junitPath) {
    try {
        def absPath = "${env.WORKSPACE}/${junitPath}"
        def line = sh(
            script: """
export CHAOS_JUNIT_PATH='${absPath}'
python3 - <<'PY'
import os
import xml.etree.ElementTree as ET
root = ET.parse(os.environ["CHAOS_JUNIT_PATH"]).getroot()
pass_n = fail_n = error_n = skip_n = 0
for tc in root.iter("testcase"):
    kids = {c.tag for c in list(tc)}
    if "error" in kids:
        error_n += 1
    elif "failure" in kids:
        fail_n += 1
    elif "skipped" in kids:
        skip_n += 1
    else:
        pass_n += 1
total = pass_n + fail_n + error_n + skip_n
print(f"{pass_n},{fail_n},{error_n},{skip_n},{total}")
PY
""",
            returnStdout: true,
        ).trim()
        def parts = line.tokenize(',')
        if (parts.size() != 5) {
            echo "Unexpected JUnit parse output for ${junitPath}: ${line}"
            return null
        }
        return [
            pass : parts[0] as int,
            fail : parts[1] as int,
            error: parts[2] as int,
            skip : parts[3] as int,
            total: parts[4] as int,
        ]
    } catch (Exception e) {
        echo "Could not parse JUnit XML at ${junitPath}: ${e.message}"
        return null
    }
}

private String readEnvFlag(String name) {
    try {
        def value = env.getProperty(name)
        return value == null ? '' : value.toString()
    } catch (Exception ignored) {
        return ''
    }
}

private List defaultChaosSuites() {
    return [
        [id: 'container',  label: 'Container',          passedEnv: 'CHAOS_CONTAINER_PASSED'],
        [id: 'app-outage', label: 'Application Outage', passedEnv: 'CHAOS_APP_OUTAGE_PASSED'],
        [id: 'network',    label: 'Network',            passedEnv: 'CHAOS_NETWORK_PASSED'],
        [id: 'hog',        label: 'Hog',                passedEnv: 'CHAOS_HOG_PASSED'],
        [id: 'node',       label: 'Node',               passedEnv: 'CHAOS_NODE_PASSED'],
        [id: 'noobaa',     label: 'NooBaa',             passedEnv: 'CHAOS_NOOBAA_PASSED'],
        [id: 'random',     label: 'Random',             passedEnv: 'CHAOS_RANDOM_PASSED'],
    ]
}
