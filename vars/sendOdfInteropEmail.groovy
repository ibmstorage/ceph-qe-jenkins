/**
 * Build and send the ODF interop HTML email notification.
 *
 * Kept in the shared library (vars/) so the main Jenkinsfile pipeline body
 * stays well within the JVM 64 KB bytecode-per-method limit after CPS
 * transformation.
 *
 * Required parameters:
 *   clusterName  - String, name of the deployed cluster (may be empty)
 *   hasCluster   - boolean, whether a cluster was provisioned
 *
 * Optional parameters:
 *   testSummary   - Map with keys total/pass/fail/skip (from junit step), or null
 *   cosResultsUrl - String, IBM COS URL where logs/tarballs were uploaded
 */
def call(Map parameters) {
    def clusterName   = parameters.clusterName ?: ''
    def hasCluster    = parameters.hasCluster ?: false
    def testSummary   = parameters.testSummary
    def cosResultsUrl = parameters.cosResultsUrl ?: ''

    try {
        def status = currentBuild.currentResult ?: 'UNKNOWN'

        def statusMap = [
            'SUCCESS':  [ mark: '[PASS]', captionBg: '#4CAF50', labelBg: '#C6EFCE', valueBg: '#E8F5E9' ],
            'FAILURE':  [ mark: '[FAIL]', captionBg: '#E53935', labelBg: '#F4CCCC', valueBg: '#FDE8E8' ],
            'ABORTED':  [ mark: '[ABORT]', captionBg: '#FF9800', labelBg: '#FFF3E0', valueBg: '#FFF8E1' ],
            'UNSTABLE': [ mark: '[WARN]', captionBg: '#FFC107', labelBg: '#FFF9C4', valueBg: '#FFFDE7' ],
        ]
        def colors = statusMap[status] ?: statusMap['FAILURE']

        def lab = "background-color: ${colors.labelBg}; border: 1px solid #d0d0d0; padding: 10px 14px; vertical-align: top; width: 34%; font-size: 16px;"
        def val = "background-color: ${colors.valueBg}; border: 1px solid #d0d0d0; padding: 10px 14px; vertical-align: top; font-size: 16px; line-height: 1.55;"
        def cephValStyle = "${val} word-break: break-all; font-size: 15px; font-family: Consolas, Monaco, 'Courier New', monospace; line-height: 1.45;"

        def htmlEsc = { s ->
            if (s == null) { return '' }
            return s.toString().replace('&', '&amp;')
                .replace('<', '&lt;')
                .replace('>', '&gt;')
                .replace('"', '&quot;')
        }

        def ocpVer = env.RESOLVED_OCP_VERSION ?: 'N/A'
        def odfCh  = env.RESOLVED_ODF_CHANNEL ?: 'default'

        def ocpVersion = env.RESOLVED_OCP_VERSION ?: ''
        def ocpChannel = env.RESOLVED_OCP_CHANNEL ?: 'released'
        def ocpMirrorBase = (ocpChannel == 'nightly')
            ? 'https://mirror.openshift.com/pub/openshift-v4/clients/ocp-dev-preview'
            : 'https://mirror.openshift.com/pub/openshift-v4/clients/ocp'
        def ocpCell = ocpVersion
            ? "<a href=\"${ocpMirrorBase}/${ocpVersion}/\">${ocpVersion}</a> (${ocpChannel})"
            : 'N/A'

        def ocpPullSpec = env.RESOLVED_OCP_PULL_SPEC?.trim() ?: ''
        def ocpImageRow = ""
        if (ocpPullSpec) {
            ocpImageRow = """
            <tr>
                <td style="${lab}"><b>OCP Image</b></td>
                <td style="${cephValStyle}">${htmlEsc(ocpPullSpec)}</td>
            </tr>"""
        }

        def odfChannel = env.RESOLVED_ODF_OPERATOR_CHANNEL?.trim()
            ?: env.RESOLVED_ODF_CHANNEL?.trim()
            ?: ''
        def odfCell = 'default'
        if (odfChannel) {
            def odfVersion = odfChannel.replaceAll(/^.*?(\d+\.\d+).*/, '$1')
            def odfLink = "https://access.redhat.com/documentation/en-us/red_hat_openshift_data_foundation/${odfVersion}"
            odfCell = "<a href=\"${odfLink}\">${htmlEsc(odfChannel)}</a>"
        }

        def odfCatalogImage = env.RESOLVED_ODF_CATALOG_IMAGE?.trim()
            ?: env.RESOLVED_ODF_NIGHTLY_INDEX?.trim()
            ?: params.ODF_CATALOG_IMAGE?.trim()
            ?: params.ODF_NIGHTLY_INDEX?.trim()
            ?: ''
        def odfImageRow = ""
        if (odfCatalogImage) {
            odfImageRow = """
            <tr>
                <td style="${lab}"><b>ODF Image</b></td>
                <td style="${cephValStyle}">${htmlEsc(odfCatalogImage)}</td>
            </tr>"""
        }

        def cephImage = params.CEPH_IMAGE ?: ''
        def cephImageRow = ""
        if (cephImage) {
            cephImageRow = """
        <tr>
            <td style="${lab}"><b>Ceph Image</b></td>
            <td style="${cephValStyle}">${cephImage}</td>
        </tr>"""
        }

        def ocsciExecuted = fileExists("ocs-ci/ocs-ci-logs-${env.BUILD_NUMBER}")
        def ocsciStatusCell = ''
        if (testSummary) {
            def p = testSummary.pass  ?: 0
            def f = testSummary.fail  ?: 0
            def s = testSummary.skip  ?: 0
            def t = testSummary.total ?: 0
            ocsciStatusCell = "<span style=\"color:#2E7D32;font-weight:bold;\">Passed: ${p}</span>" +
                " &nbsp; <span style=\"color:#C62828;font-weight:bold;\">Failed: ${f}</span>" +
                " &nbsp; <span style=\"color:#EF6C00;font-weight:bold;\">Skipped: ${s}</span>" +
                " &nbsp; (Total: ${t})"
        } else if (ocsciExecuted && status == 'SUCCESS') {
            ocsciStatusCell = 'Passed'
        } else if (ocsciExecuted) {
            ocsciStatusCell = 'Failed'
        } else {
            ocsciStatusCell = 'Not executed'
        }
        def ocsciReportRow = """
            <tr>
                <td style="${lab}"><b>ocs-ci test status</b></td>
                <td style="${val}">${ocsciStatusCell}</td>
            </tr>"""

        def cephMeta = readCephMetadata()
        def cephNice    = cephMeta.cephNice
        def cephCommit  = cephMeta.cephCommit
        def cephRelName = cephMeta.cephRelName

        def cephForSubject = buildCephSubjectToken(cephNice)

        def subject = "Ceph-ODF Interop Report: ${status} (ODF ${odfCh} + Ceph ${cephForSubject} + OCP ${ocpVer})"

        def cephMetaRows = """
        <tr>
            <td style="${lab}"><b>Ceph version</b></td>
            <td style="${cephValStyle}">${htmlEsc(cephNice)}</td>
        </tr>
        <tr>
            <td style="${lab}"><b>Ceph commit</b></td>
            <td style="${cephValStyle}">${htmlEsc(cephCommit)}</td>
        </tr>
        <tr>
            <td style="${lab}"><b>Ceph release name</b></td>
            <td style="${cephValStyle}">${htmlEsc(cephRelName)}</td>
        </tr>"""

        def deployPassed = (env.DEPLOY_PASSED == 'true')
        def workloadOk   = (env.WORKLOAD_PASSED == 'true')
        def deployResult = deployPassed ? 'Pass' : (status == 'ABORTED' ? 'Not run' : 'Fail')
        def tcBar = (status == 'SUCCESS') ? '#43A047' : ((status == 'ABORTED') ? '#FB8C00' : '#E53935')
        def tcHdrBg = (status == 'SUCCESS') ? '#E8F5E9' : ((status == 'ABORTED') ? '#FFF8E1' : '#FFEBEE')
        def tcHdrFg = (status == 'SUCCESS') ? '#1B5E20' : ((status == 'ABORTED') ? '#E65100' : '#B71C1C')
        def tcSectionHeader = """
            <tr>
                <td colspan="2" style="background-color: ${tcHdrBg}; color: ${tcHdrFg}; border: 1px solid #d0d0d0; border-top: 3px solid ${tcBar}; padding: 10px 14px; font-size: 108%; font-weight: bold;">
                    Test case execution
                </td>
            </tr>"""
        def tcRow2 = { String title, String resultStr ->
            """                        <tr>
                <td style="${lab} vertical-align: middle;"><b>${htmlEsc(title)}</b></td>
                <td style="${val} font-weight: bold; vertical-align: middle; text-align: right; white-space: nowrap;">${htmlEsc(resultStr)}</td>
            </tr>"""
        }

        def testCaseRowsHtml = buildTestCaseRows(
            clusterName, hasCluster, workloadOk, deployResult,
            tcSectionHeader, tcRow2
        )

        def infrastructureRow = """
        <tr>
            <td style="${lab}"><b>Infrastructure</b></td>
            <td style="${val}">IBM Cloud</td>
        </tr>"""

        def duration = 'N/A'
        try { duration = currentBuild.durationString.replace(' and counting', '') } catch (Exception ignored) {}

        def cosLogsRow = ''
        if (cosResultsUrl) {
            cosLogsRow = """
            <tr>
                <td style="${lab}"><b>Logs</b></td>
                <td style="${val}"><a href="${cosResultsUrl}">OCS-CI Tests Report</a></td>
            </tr>"""
        }

        def body = """
        <html>
        <body style="margin: 0; padding: 0;">
        <table style="font-family: 'Segoe UI', Arial, Helvetica, sans-serif; border-collapse: collapse; width: 100%; max-width: 720px; table-layout: fixed; background-color: ${colors.valueBg};">
            <caption style="background-color: ${colors.captionBg}; color: #FFFFFF; border: 1px solid #c0c0c0; height: 44px; font-size: 124%; font-weight: bold; padding: 10px 12px;">
                ${colors.mark} CEPH-ODF INTEROP TESTING -- ${status}
            </caption>
            <colgroup><col style="width: 34%;" /><col style="width: 66%;" /></colgroup>
            <tr>
                <td style="${lab}"><b>OCP Version</b></td>
                <td style="${val}">${ocpCell}</td>
            </tr>
${ocpImageRow}
            <tr>
                <td style="${lab}"><b>ODF Version</b></td>
                <td style="${val}">${odfCell}</td>
            </tr>
${odfImageRow}
${cephImageRow}
${cephMetaRows}
${infrastructureRow}
            <tr>
                <td style="${lab}"><b>IBM Region</b></td>
                <td style="${val}">${params.IBM_REGION ?: 'N/A'}</td>
            </tr>
            <tr>
                <td style="${lab}"><b>VPC</b></td>
                <td style="${val}">${params.VPC_NAME ?: 'N/A'}</td>
            </tr>
            <tr>
                <td style="${lab}"><b>Build</b></td>
                <td style="${val}"><a href="${env.BUILD_URL}">${env.JOB_NAME} #${env.BUILD_NUMBER}</a></td>
            </tr>
            <tr>
                <td style="${lab}"><b>Duration</b></td>
                <td style="${val}">${duration}</td>
            </tr>
${cosLogsRow}
${ocsciReportRow}
${testCaseRowsHtml}
        </table>
        </body>
        </html>
        """.stripIndent()

        def attachFiles = []

        // Cluster state file
        if (hasCluster) {
            def stateJson = "ceph-interop/configs/${clusterName}-state.json"
            if (fileExists(stateJson)) { attachFiles << stateJson }
        }

        // ocs-ci JUnit results (small file, safe to attach)
        def logDir = "ocs-ci-logs-${env.BUILD_NUMBER}"
        if (fileExists("ocs-ci/${logDir}/ocs-ci-test-junit-results.xml")) {
            attachFiles << "ocs-ci/${logDir}/ocs-ci-test-junit-results.xml"
        }

        def attachPattern = attachFiles.join(', ')
        if (attachFiles) {
            echo "Email: attaching ${attachFiles.size()} file(s): ${attachPattern}"
        }

        emailext(
            mimeType: 'text/html',
            subject: "${subject}",
            body: "${body}",
            from: 'ceph-qe-sa@ibm.com',
            to: "${params.EMAIL_ADDRESS ?: ''}",
            attachmentsPattern: "${attachPattern}"
        )
    } catch (Exception e) {
        echo "Email notification failed: ${e.message}"
    }
}

// ── Helper methods ──────────────────────────────────────────────────────────

private Map readCephMetadata() {
    def cephNice = 'N/A'
    def cephCommit = 'N/A'
    def cephRelName = 'N/A'
    try {
        def metaPath = "${env.WORKSPACE}/.cephadm-resolve-${env.BUILD_NUMBER ?: '0'}.json"
        if (fileExists(metaPath)) {
            def meta = readJSON file: metaPath
            if (meta?.ceph_git_nice_ver) { cephNice = meta.ceph_git_nice_ver.toString() }
            if (meta?.ceph_git_ver)      { cephCommit = meta.ceph_git_ver.toString() }
            if (meta?.ceph_release_name) { cephRelName = meta.ceph_release_name.toString() }
            echo "Email: Ceph version=${cephNice}, commit=${cephCommit}, release=${cephRelName}"
        } else {
            echo "Email: ${metaPath} not found (Ceph metadata stage may have been skipped or failed)"
        }
    } catch (Exception e) {
        echo "Email: could not read cephadm-resolve JSON -- ${e.message}"
    }
    return [cephNice: cephNice, cephCommit: cephCommit, cephRelName: cephRelName]
}

private String buildCephSubjectToken(String cephNice) {
    if (cephNice && cephNice != 'N/A') {
        def token = cephNice.toString().replaceAll(/[\r\n\u0000-\u001F]+/, ' ').trim()
        return token.length() > 100 ? token.substring(0, 97) + '...' : token
    }
    return 'N/A'
}

private String buildTestCaseRows(
    String clusterName, boolean hasCluster, boolean workloadOk, String deployResult,
    String tcSectionHeader, Closure tcRow2
) {
    def testCaseRowsHtml = ""
    if (hasCluster) {
        try {
            def reportPath = sh(
                script: "ls ceph-interop/work/clusters/${clusterName}/workload-report-*.txt 2>/dev/null | head -1",
                returnStdout: true
            ).trim()
            if (reportPath && fileExists(reportPath)) {
                def text = readFile(reportPath)
                def allowedScNames = ['ocs-storagecluster-ceph-rbd', 'ocs-storagecluster-cephfs'] as Set
                def scNames = new LinkedHashSet()

                def scm = text =~ /(?i)\b(ocs-storagecluster-ceph-(?:rbd|cephfs))\b/
                while (scm.find()) {
                    scNames << scm.group(1).toLowerCase()
                }
                text.split('\n').each { line ->
                    def t = line.trim()
                    def m = (t =~ /(?i)storageclass[^:]*:\s*(\S+)/)
                    if (m.find()) {
                        def scVal = m.group(1).toLowerCase()
                        if (allowedScNames.contains(scVal)) {
                            scNames << scVal
                        }
                    }
                    m = (t =~ /(?i)storageclassname[^:]*:\s*(\S+)/)
                    if (m.find()) {
                        def scVal = m.group(1).toLowerCase()
                        if (allowedScNames.contains(scVal)) {
                            scNames << scVal
                        }
                    }
                }

                def bwLines = new LinkedHashSet()
                def iopsLines = new LinkedHashSet()
                def latLines = new LinkedHashSet()
                text.split('\n').each { line ->
                    def t = line.trim()
                    if (!t) { return }
                    def u = t.toLowerCase()
                    if (u.contains('kib/s') || u.contains('mib/s') || u.contains('gib/s') ||
                            (u.contains('bw') && (u.contains('=') || u.contains('('))) ||
                            u.contains('throughput') || (u.contains('read:') && u.contains('/s')) ||
                            (u.contains('write:') && u.contains('/s'))) {
                        bwLines << t
                    }
                    if (u.contains('iops') || u =~ /(?i)\b\d+\s*iops\b/) {
                        iopsLines << t
                    }
                    if (u.contains('lat') && (u.contains('usec') || u.contains('msec') || u.contains('clat') ||
                            u.contains('slat') || u.contains('avg=') || u.contains(' min=') || u.contains(' max='))) {
                        latLines << t
                    }
                }
                def hasIoMetrics = !bwLines.isEmpty() || !iopsLines.isEmpty() || !latLines.isEmpty()

                def bodyRows = new StringBuilder()
                bodyRows.append(tcSectionHeader)
                bodyRows.append(tcRow2('OCP + ODF deployment', deployResult))

                if (scNames.isEmpty()) {
                    def r = workloadOk ? 'Partial' : 'Fail'
                    bodyRows.append(tcRow2('Storage class validation', r))
                } else {
                    scNames.each { sc ->
                        def refs = 0
                        text.split('\n').each { ln ->
                            if (ln.toLowerCase().contains(sc)) {
                                refs++
                            }
                        }
                        def r = (workloadOk && refs > 0) ? 'Pass' : (workloadOk ? 'Partial' : 'Fail')
                        bodyRows.append(tcRow2("Storage class validation -- ${sc}", r))
                    }
                }

                def ioRes = (workloadOk && hasIoMetrics) ? 'Pass' : (workloadOk ? 'Partial' : 'Fail')
                bodyRows.append(tcRow2('IO metrics (FIO / workload)', ioRes))

                testCaseRowsHtml = bodyRows.toString()
            }
        } catch (Exception ignored) { /* no report */ }
    }
    if (!testCaseRowsHtml) {
        testCaseRowsHtml = """
            ${tcSectionHeader}
            ${tcRow2('OCP + ODF deployment', deployResult)}
            ${tcRow2('Storage class validation', 'Not run')}
            ${tcRow2('IO metrics (FIO / workload)', 'Not run')}"""
    }
    return testCaseRowsHtml
}
