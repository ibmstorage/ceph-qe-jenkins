/**
 * Paths under ceph-qe-zypper work/context/{envName}/.
 *
 * Parameters:
 *   envName - required
 *   checkoutDir - relative checkout dir (default: ceph-qe-zypper)
 */
def call(Map parameters = [:]) {
    def envName = (parameters.envName ?: env.ENV_NAME ?: '').toString().trim()
    if (!envName) {
        error('scaleZypperPaths: envName is required')
    }
    def checkoutDir = (parameters.checkoutDir ?: 'ceph-qe-zypper').toString()
    def relRoot = "work/context/${envName}"
    def absRoot = "${env.WORKSPACE}/${checkoutDir}/${relRoot}"

    return [
        envName      : envName,
        checkoutDir  : checkoutDir,
        relRoot      : relRoot,
        absRoot      : absRoot,
        runYaml      : "${relRoot}/run.yaml",
        absRunYaml   : "${absRoot}/run.yaml",
        logsDir      : "${relRoot}/logs",
        absLogsDir   : "${absRoot}/logs",
        deployLog    : "${relRoot}/logs/deploy.log",
        absDeployLog : "${absRoot}/logs/deploy.log",
        runTestsLog  : "${relRoot}/logs/run-tests.log",
        absRunTestsLog: "${absRoot}/logs/run-tests.log",
        s3TestsLog   : "${relRoot}/logs/s3-tests.log",
        absS3TestsLog: "${absRoot}/logs/s3-tests.log",
        teardownLog  : "${relRoot}/logs/teardown.log",
        absTeardownLog: "${absRoot}/logs/teardown.log",
        reportsDir   : "${relRoot}/reports",
        absReportsDir: "${absRoot}/reports",
        junitXml     : "${relRoot}/reports/junit.xml",
        absJunitXml  : "${absRoot}/reports/junit.xml",
        reportHtml   : "${relRoot}/reports/report.html",
        absReportHtml: "${absRoot}/reports/report.html",
        clusterTar   : "cluster-artifacts-${envName}.tar.gz",
    ]
}
