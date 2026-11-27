/**
 * HTML emailext for Storage Scale CES pipeline results.
 *
 * Parameters:
 *   envName, testSummary (total/pass/fail/skip), cosResultsUrl, paths (from scaleZypperPaths)
 */
def call(Map parameters = [:]) {
    def envName = parameters.envName ?: env.ENV_NAME ?: ''
    def testSummary = parameters.testSummary
    def cosResultsUrl = parameters.cosResultsUrl ?: ''
    def paths = parameters.paths

    try {
        def status = currentBuild.currentResult ?: 'UNKNOWN'
        def statusMap = [
            'SUCCESS' : [mark: '[PASS]', captionBg: '#4CAF50', labelBg: '#C6EFCE', valueBg: '#E8F5E9'],
            'FAILURE' : [mark: '[FAIL]', captionBg: '#E53935', labelBg: '#F4CCCC', valueBg: '#FDE8E8'],
            'ABORTED' : [mark: '[ABORT]', captionBg: '#FF9800', labelBg: '#FFF3E0', valueBg: '#FFF8E1'],
            'UNSTABLE': [mark: '[WARN]', captionBg: '#FFC107', labelBg: '#FFF9C4', valueBg: '#FFFDE7'],
        ]
        def colors = statusMap[status] ?: statusMap['FAILURE']
        def lab = "background-color: ${colors.labelBg}; border: 1px solid #d0d0d0; padding: 10px 14px; vertical-align: top; width: 34%; font-size: 16px;"
        def val = "background-color: ${colors.valueBg}; border: 1px solid #d0d0d0; padding: 10px 14px; vertical-align: top; font-size: 16px; line-height: 1.55;"

        def htmlEsc = { s ->
            if (s == null) { return '' }
            return s.toString().replace('&', '&amp;')
                .replace('<', '&lt;')
                .replace('>', '&gt;')
                .replace('"', '&quot;')
        }

        def installer = (params.INSTALLER_FILENAME ?: params.INSTALLER_EXPORT ?: 'N/A').toString()
        def installerSource = (params.INSTALLER_SOURCE ?: 'local').toString()
        def contextPath = (params.CONTEXT ?: env.CONTEXT_PATH ?: '').toString()
        def suite = (params.PYTEST_SUITE ?: env.PYTEST_SUITE ?: 'tests/sanity').toString()
        def marker = (params.PYTEST_MARKER ?: env.PYTEST_MARKER ?: 'sanity').toString()
        def protocolsLabel = (params.PROTOCOLS_LABEL ?: env.PROTOCOLS_LABEL ?: 'nfs, smb').toString()
        def testStageLabel = (params.TEST_STAGE_LABEL ?: env.TEST_STAGE_LABEL ?: 'Sanity (pytest)').toString()
        def nodesLabel = (params.NODES_LABEL ?: env.NODES_LABEL ?: '3 Scale + protocol/gpfs clients (context default)').toString()

        def duration = 'N/A'
        try {
            duration = currentBuild.durationString.replace(' and counting', '')
        } catch (Exception ignored) {}

        def deployPassed = (env.DEPLOY_PASSED == 'true')
        def testsPassed = (env.TESTS_PASSED == 'true')
        def deployResult = deployPassed ? 'Pass' : (status == 'ABORTED' && env.DEPLOY_PASSED != 'false' ? 'Not run' : (env.TEARDOWN_REQUIRED == 'true' ? 'Fail' : 'Not run'))
        def testsResult
        if (env.TESTS_PASSED == null || env.TESTS_PASSED == '') {
            testsResult = deployPassed ? 'Not run' : 'Not run'
        } else if (testsPassed) {
            testsResult = formatTestSummary(testSummary, 'Pass')
        } else {
            testsResult = formatTestSummary(testSummary, 'Fail')
        }

        def tcBar = (status == 'SUCCESS') ? '#43A047' : ((status == 'ABORTED') ? '#FB8C00' : '#E53935')
        def tcHdrBg = (status == 'SUCCESS') ? '#E8F5E9' : ((status == 'ABORTED') ? '#FFF8E1' : '#FFEBEE')
        def tcHdrFg = (status == 'SUCCESS') ? '#1B5E20' : ((status == 'ABORTED') ? '#E65100' : '#B71C1C')

        def cosLogsRow = ''
        if (cosResultsUrl) {
            cosLogsRow = """
            <tr>
                <td style="${lab}"><b>Logs</b></td>
                <td style="${val}"><a href="${cosResultsUrl}">COS artifacts</a></td>
            </tr>"""
        } else {
            cosLogsRow = """
            <tr>
                <td style="${lab}"><b>Logs</b></td>
                <td style="${val}">See Jenkins archived artifacts (deploy / run-tests / teardown logs)</td>
            </tr>"""
        }

        def subject = "Storage Scale CES Report: ${status} (Scale ${installer} + ${envName ?: 'n/a'})"

        def body = """
        <html>
        <body style="margin: 0; padding: 0;">
        <table style="font-family: 'Segoe UI', Arial, Helvetica, sans-serif; border-collapse: collapse; width: 100%; max-width: 720px; table-layout: fixed; background-color: ${colors.valueBg};">
            <caption style="background-color: ${colors.captionBg}; color: #FFFFFF; border: 1px solid #c0c0c0; height: 44px; font-size: 124%; font-weight: bold; padding: 10px 12px;">
                ${colors.mark} STORAGE SCALE CES TESTING -- ${status}
            </caption>
            <colgroup><col style="width: 34%;" /><col style="width: 66%;" /></colgroup>
            <tr>
                <td style="${lab}"><b>Scale installer</b></td>
                <td style="${val}">${htmlEsc(installer)} (${htmlEsc(installerSource)})</td>
            </tr>
            <tr>
                <td style="${lab}"><b>Context</b></td>
                <td style="${val}">${htmlEsc(contextPath)}</td>
            </tr>
            <tr>
                <td style="${lab}"><b>Env name</b></td>
                <td style="${val}">${htmlEsc(envName)}</td>
            </tr>
            <tr>
                <td style="${lab}"><b>Infrastructure</b></td>
                <td style="${val}">IBM Cloud — eu-de / vpc-rgw (qe_eu_scale)</td>
            </tr>
            <tr>
                <td style="${lab}"><b>Nodes / clients</b></td>
                <td style="${val}">${htmlEsc(nodesLabel)}</td>
            </tr>
            <tr>
                <td style="${lab}"><b>Protocols</b></td>
                <td style="${val}">${htmlEsc(protocolsLabel)}</td>
            </tr>
            <tr>
                <td style="${lab}"><b>Suite / marker</b></td>
                <td style="${val}">${htmlEsc(suite)} -m ${htmlEsc(marker)}</td>
            </tr>
            <tr>
                <td style="${lab}"><b>Build</b></td>
                <td style="${val}"><a href="${env.BUILD_URL}">${env.JOB_NAME} #${env.BUILD_NUMBER}</a></td>
            </tr>
            <tr>
                <td style="${lab}"><b>Duration</b></td>
                <td style="${val}">${htmlEsc(duration)}</td>
            </tr>
${cosLogsRow}
            <tr>
                <td colspan="2" style="background-color: ${tcHdrBg}; color: ${tcHdrFg}; border: 1px solid #d0d0d0; border-top: 3px solid ${tcBar}; padding: 10px 14px; font-size: 108%; font-weight: bold;">
                    Test case execution
                </td>
            </tr>
            <tr>
                <td style="${lab}"><b>Deploy</b></td>
                <td style="${val} font-weight: bold; text-align: right;">${htmlEsc(deployResult)}</td>
            </tr>
            <tr>
                <td style="${lab}"><b>${htmlEsc(testStageLabel)}</b></td>
                <td style="${val} font-weight: bold; text-align: right;">${htmlEsc(testsResult)}</td>
            </tr>
        </table>
        </body>
        </html>
        """.stripIndent()

        // Same transport as BVT publishTestResults / emailext + SendGrid.
        // from must match jobConfigs.CEPHCI_EMAIL (ceph-qe-sa@ibm.com).
        def emailAddr = (params.EMAIL_ADDRESS ?: '').toString().trim()
        if (!emailAddr) {
            echo 'EMAIL_ADDRESS is empty — skipping emailext (BVT would fail publishTestResults here)'
            return
        }
        echo "Send results to ${emailAddr}"

        def attachFiles = []
        if (paths) {
            def checkoutDir = paths.checkoutDir ?: 'ceph-qe-zypper'
            def runYaml = "${checkoutDir}/${paths.runYaml}"
            def junit = "${checkoutDir}/${paths.junitXml}"
            if (fileExists(runYaml)) { attachFiles << runYaml }
            if (fileExists(junit)) { attachFiles << junit }
        }
        def attachPattern = attachFiles.join(', ')
        if (attachFiles) {
            echo "Email: attaching ${attachFiles.size()} file(s): ${attachPattern}"
        } else {
            // emailext with empty attachmentsPattern can misbehave on some plugin versions
            attachPattern = ''
        }

        def mailArgs = [
            mimeType: 'text/html',
            subject : "${subject}",
            body    : "${body}",
            from    : 'ceph-qe-sa@ibm.com',
            to      : emailAddr,
        ]
        if (attachPattern) {
            mailArgs.attachmentsPattern = attachPattern
        }
        emailext(mailArgs)
        echo "emailext submitted: subject=${subject}"
    } catch (Exception e) {
        echo "Email notification failed: ${e.message}"
        e.printStackTrace()
    }
}

private String formatTestSummary(Map summary, String label) {
    if (!summary) {
        return label
    }
    def pass = summary.pass ?: 0
    def fail = summary.fail ?: 0
    def skip = summary.skip ?: 0
    return "${label} — ${pass} passed, ${fail} failed, ${skip} skipped"
}
