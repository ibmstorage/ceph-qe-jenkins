/**
 * Load per-cluster state after deploy and populate env for ocs-ci / display.
 *
 * Parameters:
 *   envName    - ceph-interop --env-name (required)
 *   clusterId  - optional; defaults to env.CLUSTER_ID from updateCephInteropConfig
 *   required     - when true, error if state.json or cluster_name is missing (ocs-ci prep)
 *
 * Sets env.OCP_CLUSTER_NAME, env.CLUSTER_NAME (alias), updates build display/description.
 * Returns the state map (may be empty if file missing and required is false).
 */
def call(Map parameters = [:]) {
    def required = parameters.required ?: false
    def paths = cephInteropPaths(
        envName: parameters.envName,
        clusterId: parameters.clusterId,
        workDir: parameters.workDir,
        workspace: parameters.workspace,
    )

    def statePath = paths.absStateJson
    if (!fileExists(statePath)) {
        def msg = "readCephInteropClusterState: state not found at ${statePath}"
        if (required) {
            error("${msg} — deploy must complete before ocs-ci")
        }
        echo msg
        return [:]
    }

    def state = readJSON(file: statePath)
    def clusterName = state?.cluster_name?.toString()?.trim() ?: ''

    if (clusterName) {
        env.OCP_CLUSTER_NAME = clusterName
        env.CLUSTER_NAME = clusterName
        echo "readCephInteropClusterState: OpenShift cluster_name=${clusterName}"

        def existingDesc = currentBuild.description ?: ''
        def clusterLine = "Cluster: ${clusterName}"
        if (existingDesc.contains('\nCluster:')) {
            currentBuild.description = existingDesc.replaceFirst(
                /(?m)^Cluster: .*$/,
                java.util.regex.Matcher.quoteReplacement(clusterLine),
            )
        } else if (existingDesc) {
            currentBuild.description = "${existingDesc}\n${clusterLine}"
        } else {
            currentBuild.description = clusterLine
        }

        def display = currentBuild.displayName ?: "#${env.BUILD_NUMBER}"
        if (!display.contains(clusterName)) {
            currentBuild.displayName = "#${env.BUILD_NUMBER} - ${clusterName}"
        }
    } else {
        def msg = "readCephInteropClusterState: cluster_name missing in ${statePath}"
        if (required) {
            error("${msg} — deploy must complete before ocs-ci")
        }
        echo msg
    }

    return state
}
