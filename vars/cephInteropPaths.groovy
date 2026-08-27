/**
 * Canonical ceph-interop artifact paths under work/context/{env-name}/{cluster-id}/.
 *
 * Parameters:
 *   envName    - ceph-interop --env-name (required)
 *   clusterId  - cluster key from deployment context (defaults to env.CLUSTER_ID)
 *   workDir    - relative work dir inside ceph-interop checkout (default: work)
 *   workspace  - Jenkins WORKSPACE (optional; defaults to env.WORKSPACE)
 */
def call(Map parameters = [:]) {
    def envName = parameters.envName?.toString()?.trim()
    if (!envName) {
        throw new RuntimeException('cephInteropPaths: envName is required')
    }

    def clusterId = parameters.clusterId?.toString()?.trim() ?: env.CLUSTER_ID?.toString()?.trim()
    if (!clusterId) {
        throw new RuntimeException('cephInteropPaths: clusterId is required (set by updateCephInteropConfig)')
    }
    def workDir = parameters.workDir?.toString()?.trim() ?: 'work'
    def workspace = parameters.workspace?.toString()?.trim() ?: env.WORKSPACE

    def relRoot = "${workDir}/context/${envName}/${clusterId}"
    def absRoot = "${workspace}/ceph-interop/${relRoot}"

    return [
        envName           : envName,
        clusterId         : clusterId,
        workDir           : workDir,
        relRoot           : relRoot,
        absRoot           : absRoot,
        stateJson         : "${relRoot}/state.json",
        absStateJson      : "${absRoot}/state.json",
        openshiftDir      : "${relRoot}/openshift",
        absOpenshiftDir   : "${absRoot}/openshift",
        kubeconfig        : "${relRoot}/openshift/auth/kubeconfig",
        absKubeconfig     : "${absRoot}/openshift/auth/kubeconfig",
        binDir            : "${relRoot}/bin",
        absBinDir         : "${absRoot}/bin",
        ocBinary          : "${relRoot}/bin/oc",
        absOcBinary       : "${absRoot}/bin/oc",
        workloadReportGlob: "${relRoot}/workload-report-*.txt",
        absWorkloadGlob   : "${absRoot}/workload-report-*.txt",
        envLogsDir        : "${workDir}/context/${envName}/logs",
        absEnvLogsDir     : "${workspace}/ceph-interop/${workDir}/context/${envName}/logs",
    ]
}
