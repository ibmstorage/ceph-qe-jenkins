/**
 * HTML email for the odf-chaos pipeline.
 *
 * One row per Krkn suite (junit counts when present). Does not send the
 * interop acceptance/tier-1 or FIO workload sections.
 *
 * Parameters:
 *   envName, clusterName, hasCluster, product, chaosSummaries, cosResultsUrl
 */
def call(Map parameters) {
    def envName = parameters.envName ?: env.ENV_NAME ?: ''
    def clusterName = parameters.clusterName ?: env.OCP_CLUSTER_NAME ?: env.CLUSTER_NAME ?: ''
    def hasCluster = parameters.hasCluster ?: false
    def chaosSummaries = parameters.chaosSummaries ?: []
    def cosResultsUrl = parameters.cosResultsUrl ?: ''
    def product = (parameters.product ?: env.INTEROP_PRODUCT ?: 'odf').toString().toLowerCase()
    def productLabel = product == 'fdf' ? 'FDF' : 'ODF'
    def clusterId = parameters.clusterId ?: env.CLUSTER_ID ?: ''

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
        def productCh = env.RESOLVED_ODF_OPERATOR_CHANNEL?.trim()
            ?: env.RESOLVED_ODF_CHANNEL?.trim()
            ?: params.ODF_OPERATOR_CHANNEL?.trim()
            ?: 'default'

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

        def catalogImage = env.RESOLVED_ODF_CATALOG_IMAGE?.trim()
            ?: env.RESOLVED_ODF_NIGHTLY_INDEX?.trim()
            ?: params.ODF_CATALOG_IMAGE?.trim()
            ?: ''
        def catalogImageRow = ""
        if (catalogImage) {
            catalogImageRow = """
            <tr>
                <td style="${lab}"><b>${productLabel} Catalog Image</b></td>
                <td style="${cephValStyle}">${htmlEsc(catalogImage)}</td>
            </tr>"""
        }

        def cephImage = params.CEPH_IMAGE ?: ''
        def cephImageRow = ""
        if (cephImage) {
            cephImageRow = """
        <tr>
            <td style="${lab}"><b>Ceph Image</b></td>
            <td style="${cephValStyle}">${htmlEsc(cephImage)}</td>
        </tr>"""
        }

        def cephMeta = readCephMetadata()
        def cephForSubject = buildCephSubjectToken(cephMeta.cephNice)
        def subject = "Ceph-${productLabel} Chaos Report: ${status} (${productLabel} ${productCh} + Ceph ${cephForSubject} + OCP ${ocpVer})"

        def anyChaosFail = false
        def anyAssertionFail = false
        def suiteRows = ''
        def attachFiles = []
        for (suite in chaosSummaries) {
            def executed = suite.executed == true
            def failedCount = (suite.fail ?: 0) as int
            def errorCount = (suite.error ?: 0) as int
            def suiteFailed = executed && (suite.passedFlag == 'false' || failedCount > 0 || errorCount > 0)
            if (suiteFailed) {
                anyChaosFail = true
            }
            if (failedCount > 0) {
                anyAssertionFail = true
            }
            def counts = null
            if (suite.total != null) {
                counts = [
                    pass : suite.pass ?: 0,
                    fail : suite.fail ?: 0,
                    error: suite.error ?: 0,
                    skip : suite.skip ?: 0,
                    total: suite.total ?: 0,
                ]
            }
            def cell = buildTestStatusCell(counts, executed, status)
            suiteRows += """
            <tr>
                <td style="${lab}"><b>${htmlEsc(suite.label ?: suite.id)}</b></td>
                <td style="${val}">${cell}</td>
            </tr>"""
            if (suite.junitPath && fileExists(suite.junitPath)) {
                attachFiles << suite.junitPath
            }
        }

        def chaosBar = anyChaosFail ? '#E65100' : '#1565C0'
        def chaosHdrBg = anyChaosFail ? '#FFF3E0' : '#E3F2FD'
        def chaosHdrFg = anyChaosFail ? '#E65100' : '#0D47A1'
        def chaosSectionHtml = """
            <tr>
                <td colspan="2" style="background-color: ${chaosHdrBg}; color: ${chaosHdrFg}; border: 1px solid #d0d0d0; border-top: 3px solid ${chaosBar}; padding: 10px 14px; font-size: 108%; font-weight: bold;">
                    OCS-CI Chaos Tests
                </td>
            </tr>
            ${suiteRows}"""

        def chaosNoteRow = ''
        if (anyChaosFail) {
            // Soften wording when the only JUnit problems are <error> (teardown), not <failure>.
            def noteText = (!anyAssertionFail)
                ? 'One or more Krkn chaos suites reported pytest errors (often setup/teardown such as PVC delete timeouts). Review the attached JUnit error messages and per-suite HTML reports before attributing this to the patched Ceph image.'
                : 'One or more Krkn chaos suites failed on the patched Ceph image. Review the attached JUnit files and the per-suite HTML reports in the build artifacts.'
            chaosNoteRow = """
            <tr>
                <td colspan="2" style="background-color: #FFF8E1; border: 1px solid #d0d0d0; border-left: 4px solid #F57C00; padding: 12px 14px; font-size: 14px; color: #3E2723; line-height: 1.6;">
                    <b style="color: #E65100;">&#9888; Action Required:</b> ${noteText}
                </td>
            </tr>"""
        }

        def deployPassed = (env.DEPLOY_PASSED == 'true')
        def deployResult = deployPassed ? 'Pass' : (status == 'ABORTED' ? 'Not run' : 'Fail')
        def tcBar = (status == 'SUCCESS') ? '#43A047' : ((status == 'ABORTED') ? '#FB8C00' : '#E53935')
        def tcHdrBg = (status == 'SUCCESS') ? '#E8F5E9' : ((status == 'ABORTED') ? '#FFF8E1' : '#FFEBEE')
        def tcHdrFg = (status == 'SUCCESS') ? '#1B5E20' : ((status == 'ABORTED') ? '#E65100' : '#B71C1C')
        def testCaseRowsHtml = """
            <tr>
                <td colspan="2" style="background-color: ${tcHdrBg}; color: ${tcHdrFg}; border: 1px solid #d0d0d0; border-top: 3px solid ${tcBar}; padding: 10px 14px; font-size: 108%; font-weight: bold;">
                    Test case execution
                </td>
            </tr>
            <tr>
                <td style="${lab} vertical-align: middle;"><b>OCP + ${productLabel} deployment</b></td>
                <td style="${val} font-weight: bold; vertical-align: middle; text-align: right; white-space: nowrap;">${htmlEsc(deployResult)}</td>
            </tr>"""

        def ibmRegion = params.IBM_REGION ?: 'N/A'
        def vpcName = params.VPC_NAME ?: 'N/A'

        def duration = 'N/A'
        try { duration = currentBuild.durationString.replace(' and counting', '') } catch (Exception ignored) {}

        def cosLogsRow = ''
        if (cosResultsUrl) {
            cosLogsRow = """
            <tr>
                <td style="${lab}"><b>Logs</b></td>
                <td style="${val}"><a href="${cosResultsUrl}">Chaos test reports</a></td>
            </tr>"""
        }

        def envRow = ''
        if (envName) {
            envRow = """
            <tr>
                <td style="${lab}"><b>Env name</b></td>
                <td style="${val}">${htmlEsc(envName)}</td>
            </tr>"""
        }
        def clusterRow = ''
        if (clusterName) {
            clusterRow = """
            <tr>
                <td style="${lab}"><b>Cluster name</b></td>
                <td style="${val}">${htmlEsc(clusterName)}</td>
            </tr>"""
        }

        def body = """
        <html>
        <body style="margin: 0; padding: 0;">
        <table style="font-family: 'Segoe UI', Arial, Helvetica, sans-serif; border-collapse: collapse; width: 100%; max-width: 720px; table-layout: fixed; background-color: ${colors.valueBg};">
            <caption style="background-color: ${colors.captionBg}; color: #FFFFFF; border: 1px solid #c0c0c0; height: 44px; font-size: 124%; font-weight: bold; padding: 10px 12px;">
                ${colors.mark} CEPH-${productLabel} CHAOS TESTING -- ${status}
            </caption>
            <colgroup><col style="width: 34%;" /><col style="width: 66%;" /></colgroup>
            <tr>
                <td style="${lab}"><b>OCP Version</b></td>
                <td style="${val}">${ocpCell}</td>
            </tr>
${ocpImageRow}
            <tr>
                <td style="${lab}"><b>${productLabel} Version</b></td>
                <td style="${val}">${odfCell}</td>
            </tr>
${catalogImageRow}
${cephImageRow}
            <tr>
                <td style="${lab}"><b>Ceph version</b></td>
                <td style="${cephValStyle}">${htmlEsc(cephMeta.cephNice)}</td>
            </tr>
            <tr>
                <td style="${lab}"><b>Ceph commit</b></td>
                <td style="${cephValStyle}">${htmlEsc(cephMeta.cephCommit)}</td>
            </tr>
            <tr>
                <td style="${lab}"><b>Ceph release name</b></td>
                <td style="${cephValStyle}">${htmlEsc(cephMeta.cephRelName)}</td>
            </tr>
${envRow}
${clusterRow}
            <tr>
                <td style="${lab}"><b>Infrastructure</b></td>
                <td style="${val}">IBM Cloud &mdash; ${htmlEsc(ibmRegion)} / ${htmlEsc(vpcName)}</td>
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
${testCaseRowsHtml}
${chaosSectionHtml}
${chaosNoteRow}
        </table>
        </body>
        </html>
        """.stripIndent()

        if (hasCluster && envName) {
            def paths = cephInteropPaths(envName: envName, clusterId: clusterId)
            def stateJson = "ceph-interop/${paths.stateJson}"
            if (fileExists(stateJson)) { attachFiles << stateJson }
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

private String buildTestStatusCell(Map summary, boolean suiteExecuted, String status) {
    if (summary) {
        def p = summary.pass  ?: 0
        def f = summary.fail  ?: 0
        def e = summary.error ?: 0
        def s = summary.skip  ?: 0
        def t = summary.total ?: 0
        return "<span style=\"color:#2E7D32;font-weight:bold;\">Passed: ${p}</span>" +
            " &nbsp; <span style=\"color:#C62828;font-weight:bold;\">Failed: ${f}</span>" +
            " &nbsp; <span style=\"color:#6A1B9A;font-weight:bold;\">Errors: ${e}</span>" +
            " &nbsp; <span style=\"color:#EF6C00;font-weight:bold;\">Skipped: ${s}</span>" +
            " &nbsp; (Total: ${t})"
    } else if (!suiteExecuted) {
        return 'Not run'
    } else if (status == 'SUCCESS') {
        return 'Passed'
    }
    return 'Failed'
}

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
