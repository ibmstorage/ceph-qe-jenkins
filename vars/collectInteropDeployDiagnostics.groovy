/**
 * Collect cluster diagnostics after a failed OCP+ODF/FDF deploy.
 *
 * Must set KUBECONFIG to the cluster kubeconfig — bare `oc` falls back to
 * ~/.kube/config and typically cannot reach the cluster under test.
 *
 * Parameters:
 *   product   - odf | fdf (selects marketplace CatalogSource name)
 *   paths     - result of cephInteropPaths(...) (required)
 *   namespace - OpenShift namespace (default: openshift-storage)
 */
def call(Map parameters = [:]) {
    def product = (parameters.product ?: env.INTEROP_PRODUCT ?: 'odf').toString().toLowerCase()
    def paths = parameters.paths
    if (!paths?.absOcBinary || !paths?.absKubeconfig) {
        echo "Skipping deploy diagnostics: cephInteropPaths result missing oc/kubeconfig"
        return
    }

    def ns = (parameters.namespace ?: 'openshift-storage').toString()
    def oc = paths.absOcBinary
    def kubeconfig = paths.absKubeconfig
    def catalog = product == 'fdf' ? 'isf-data-foundation-catalog' : 'redhat-operators'

    echo "DEPLOY FAILED — collecting diagnostics (KUBECONFIG=${kubeconfig})"
    sh """
        export KUBECONFIG='${kubeconfig}'
        if [ ! -f "\$KUBECONFIG" ]; then
            echo "kubeconfig not found: \$KUBECONFIG — skipping oc diagnostics"
            exit 0
        fi
        if [ ! -f '${oc}' ]; then
            echo "oc binary not found: ${oc} — skipping oc diagnostics"
            exit 0
        fi
        echo "=== storagecluster ==="
        '${oc}' --kubeconfig "\$KUBECONFIG" get storagecluster ocs-storagecluster -n '${ns}' -o yaml 2>&1 || true
        echo "=== csv ==="
        '${oc}' --kubeconfig "\$KUBECONFIG" get csv -n '${ns}' 2>&1 || true
        echo "=== pods ==="
        '${oc}' --kubeconfig "\$KUBECONFIG" get pods -n '${ns}' -o wide 2>&1 || true
        echo "=== catalogsource ${catalog} ==="
        '${oc}' --kubeconfig "\$KUBECONFIG" get catalogsource -n openshift-marketplace '${catalog}' -o yaml 2>&1 || true
    """
}
