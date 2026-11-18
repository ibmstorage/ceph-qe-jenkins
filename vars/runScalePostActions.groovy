/**
 * Post-build for scale-ces: teardown (with --log-file) → archive → COS → email → scrub.
 *
 * Teardown matches ceph-zypper CLI:
 *   --env-name --level all [--credentials] [-v] [--ssh-log] [--log-file]
 */
def call(Map parameters = [:]) {
    def envName = (parameters.envName ?: env.ENV_NAME ?: '').toString().trim()
    def checkoutDir = (parameters.checkoutDir ?: 'ceph-qe-zypper').toString()
    def venvDir = parameters.venvDir ?: "${env.WORKSPACE}/${checkoutDir}/.venv"
    def skipTeardown = (parameters.skipTeardown != null)
        ? parameters.skipTeardown
        : boolParamTrue(params.SKIP_TEARDOWN, false)
    def teardownRequired = (env.TEARDOWN_REQUIRED == 'true')
    def paths = envName ? scaleZypperPaths(envName: envName, checkoutDir: checkoutDir) : null
    def verboseFlag = boolParamTrue(params.VERBOSE, false) ? '-v' : ''
    def sshLog = (params.SSH_LOG?.toString()?.trim() in ['full', 'summary']) \
        ? params.SSH_LOG.toString().trim() : 'full'

    // 1) Teardown with log file (no --context required by zypper teardown)
    if (teardownRequired && envName && !skipTeardown) {
        if (!fileExists("${venvDir}/bin/ceph-zypper")) {
            echo 'Skipping teardown: ceph-zypper not installed in venv'
        } else if (!fileExists("${checkoutDir}/configs/credentials.yaml")) {
            echo 'Skipping teardown: configs/credentials.yaml missing'
        } else {
            try {
                dir(checkoutDir) {
                    sh "mkdir -p '${paths.logsDir}'"
                    echo "Tearing down env: ${envName}"
                    sh """
                        ${venvDir}/bin/ceph-zypper teardown \\
                            --env-name '${envName}' \\
                            --level all \\
                            --credentials configs/credentials.yaml \\
                            ${verboseFlag} \\
                            --ssh-log '${sshLog}' \\
                            --log-file '${paths.teardownLog}'
                    """
                }
            } catch (Exception e) {
                echo "Teardown failed: ${e.message}"
                currentBuild.result = 'FAILURE'
            }
        }
    } else if (skipTeardown) {
        echo 'SKIP_TEARDOWN set — leaving IBM Cloud resources'
    }

    // 2) Archive artifacts + junit (paths are under checkout; fileExists is workspace-relative)
    def testSummary = null
    try {
        if (paths) {
            def ws = { String rel -> "${checkoutDir}/${rel}" }

            if (fileExists(ws(paths.junitXml))) {
                def result = junit testResults: ws(paths.junitXml), allowEmptyResults: true
                testSummary = [
                    total: result.totalCount,
                    pass : result.passCount,
                    fail : result.failCount,
                    skip : result.skipCount,
                ]
            }

            def archiveList = [
                paths.deployLog,
                paths.runTestsLog,
                paths.s3TestsLog,
                paths.teardownLog,
                paths.runYaml,
                paths.junitXml,
                paths.reportHtml,
            ].findAll { fileExists(ws(it)) }
                .collect { ws(it) }

            if (archiveList) {
                archiveArtifacts artifacts: archiveList.join(', '), allowEmptyArchive: true
            }

            dir(checkoutDir) {
                sh """
                    if [ -d '${paths.relRoot}' ]; then
                        tar czf '${paths.clusterTar}' \\
                            -C work/context \\
                            '${envName}' 2>/dev/null || true
                    fi
                """
            }
            if (fileExists(ws(paths.clusterTar))) {
                archiveArtifacts artifacts: ws(paths.clusterTar), allowEmptyArchive: true
            }
        }
    } catch (Exception e) {
        echo "Artifact archival failed (non-fatal): ${e.message}"
    }

    // 3) COS upload
    def cosRemote = 'ceph-qe-cos'
    def cosBucket = 'ceph-qe-logs'
    def cosBaseUrl = 'https://10.8.128.2/cephci-jenkins/ibm-cos'
    def cosBasePath = "${env.JOB_NAME ?: 'scale-ces'}/${env.BUILD_NUMBER}"
    def cosResultsUrl = ''
    try {
        def hasRclone = sh(
            script: "rclone listremotes 2>/dev/null | grep -q '^${cosRemote}:'",
            returnStatus: true
        ) == 0
        if (hasRclone && paths) {
            def ws = { String rel -> "${checkoutDir}/${rel}" }
            // Keep reports (junit/html) and logs in separate COS prefixes.
            def uploadGroups = [
                [
                    dest: "${cosBasePath}/logs/",
                    files: [
                        paths.deployLog,
                        paths.runTestsLog,
                        paths.s3TestsLog,
                        paths.teardownLog,
                    ],
                ],
                [
                    dest: "${cosBasePath}/reports/",
                    files: [
                        paths.junitXml,
                        paths.reportHtml,
                    ],
                ],
                [
                    dest: "${cosBasePath}/",
                    files: [
                        paths.clusterTar,
                        paths.runYaml,
                    ],
                ],
            ]
            def uploaded = 0
            for (group in uploadGroups) {
                def files = group.files.findAll { fileExists(ws(it)) }.collect { ws(it) }
                for (f in files) {
                    sh "rclone copy '${f}' '${cosRemote}:${cosBucket}/${group.dest}' --progress --transfers=8 --checkers=8"
                    uploaded++
                }
            }
            if (uploaded) {
                cosResultsUrl = "${cosBaseUrl}/${cosBasePath}/"
            }
        }
    } catch (Exception e) {
        echo "COS upload failed (non-fatal): ${e.message}"
    }

    // 4) Email
    try {
        sendScaleEmail(
            envName: envName,
            testSummary: testSummary,
            cosResultsUrl: cosResultsUrl,
            paths: paths,
        )
    } catch (Exception e) {
        echo "Email notification failed: ${e.message}"
    }

    // 5) Scrub secrets
    dir(checkoutDir) {
        sh 'rm -f configs/credentials.yaml .ci-ssh/id_rsa .ci-ssh/id_rsa.pub .ci-ssh/id_ed25519 .ci-ssh/id_ed25519.pub 2>/dev/null || true'
    }
}

private boolean boolParamTrue(Object value, boolean defaultValue) {
    if (value == null) {
        return defaultValue
    }
    if (value instanceof Boolean) {
        return value
    }
    return value.toString().trim().equalsIgnoreCase('true')
}
