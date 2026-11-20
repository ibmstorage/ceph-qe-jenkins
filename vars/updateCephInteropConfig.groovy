/**
 * Render ceph-interop configuration.yaml and deployment context for a build.
 *
 * - configuration: per-build file deployments/jenkins/interop-{env}-configuration.yaml
 *   (patched from checkout configs/configuration.yaml via patch-ceph-interop-config.py)
 * - deployment context: deployments/jenkins/interop-{env}.yaml
 *
 * Parameters:
 *   product   - odf | fdf (required)
 *   envName   - ceph-interop --env-name (required)
 *   params      - Jenkins job params map
 *   contextFile - optional deployment filename under deployments/infra/ocp/
 *                 (default: product internal-storage context). Basename only.
 *
 * Sets env.CONTEXT_PATH, env.CONFIGURATION_PATH, and env.CLUSTER_ID.
 */
def call(Map parameters) {
    def product = (parameters.product ?: env.INTEROP_PRODUCT ?: 'odf').toString().trim().toLowerCase()
    if (!(product in ['odf', 'fdf'])) {
        throw new RuntimeException("updateCephInteropConfig: invalid product '${product}'")
    }

    def envName = parameters.envName?.toString()?.trim()
    if (!envName) {
        throw new RuntimeException('updateCephInteropConfig: envName is required')
    }

    def params = parameters.params ?: [:]
    def requestedClusterId = parameters.clusterId?.toString()?.trim() ?: ''

    def masterCount = (params.MASTER_COUNT?.trim() ?: '3') as Integer
    def requestedWorkers = (params.WORKER_COUNT?.trim() ?: '3') as Integer
    def deviceSize = (params.DEVICE_SIZE?.trim() ?: '50') as Integer
    def deviceCount = (params.DEVICE_COUNT?.trim() ?: '3') as Integer
    def ec = resolveEcScheme(scheme: params.EC_SCHEME, workerCount: requestedWorkers)
    if (ec.error) {
        throw new RuntimeException("updateCephInteropConfig: ${ec.error}")
    }
    def workerCount = ec.workerCount as Integer
    def ecDefaultPools = ec.enabled as boolean
    def ecDataChunks = ec.k as Integer
    def ecCodingChunks = ec.m as Integer
    def enableFips = asBool(params.ENABLE_FIPS)
    def cephImage = params.CEPH_IMAGE?.trim() ?: ''
    def ibmRegion = params.IBM_REGION?.trim() ?: 'eu-de'
    def vpcName = params.VPC_NAME?.trim() ?: 'vpc-ceph-integration'

    def ocpVersion = env.RESOLVED_OCP_VERSION?.trim() ?: ''
    def ocpChannel = env.RESOLVED_OCP_CHANNEL?.trim() ?: 'released'
    def odfOperatorChannel = env.RESOLVED_ODF_OPERATOR_CHANNEL?.trim()
        ?: env.RESOLVED_ODF_CHANNEL?.trim() ?: ''
    def catalogImage = env.RESOLVED_ODF_CATALOG_IMAGE?.trim()
        ?: env.RESOLVED_FDF_CATALOG_IMAGE?.trim()
        ?: env.RESOLVED_ODF_NIGHTLY_INDEX?.trim()
        ?: ''

    def ocpMinor = parseMinor(ocpVersion)
    def odfMinor = parseMinor(odfOperatorChannel) ?: ocpMinor

    def openshiftProfile = "ocp-${ocpMinor}-default"
    def serviceProfile = "stable-${odfMinor}"
    // Interop default: one device set per worker, replica 3.
    // Chaos passes deviceSetCount 1 (one disk per worker). Profile name follows
    // DEVICE_SIZE (odf-chaos default 200 → odf-1x200gi / osd-1x200gb).
    def deviceSetCount = (parameters.deviceSetCount ?: workerCount) as Integer
    def replicaCount = (parameters.replica ?: 3) as Integer
    def volumeProfile = "osd-${deviceCount}x${deviceSize}gb"
    def storageProfile = "odf-${deviceSetCount}x${deviceSize}gi"
    def lsoMax = "${deviceSize * 2}Gi"

    def baseContext = baseContextCheckoutPath(product, parameters.contextFile)
    if (!fileExists(baseContext)) {
        throw new RuntimeException(
            "updateCephInteropConfig: deployment context not found: ${baseContext} " +
            '(ensure CEPH_INTEROP_BRANCH is new or includes ocp-ibm-vpc-*-internal-storage.yaml)'
        )
    }

    def baseCtx = readYaml(file: baseContext)
    def clusterId = resolveClusterIdFromContext(baseCtx, requestedClusterId)
    def baseCluster = baseCtx?.context?.clusters?."${clusterId}"
    if (!baseCluster) {
        throw new RuntimeException("updateCephInteropConfig: cluster ${clusterId} missing in ${baseContext}")
    }
    def infraProfile = baseCluster.infra_profile?.toString()?.trim() ?: 'qe_eu'
    def dnsProfile = baseCluster.dns_profile?.toString()?.trim() ?: 'route53_qe'

    def baseConfigPath = 'configs/configuration.yaml'
    if (!fileExists(baseConfigPath)) {
        throw new RuntimeException(
            "updateCephInteropConfig: ${baseConfigPath} not found in ceph-interop checkout " +
            '(set CEPH_INTEROP_BRANCH to new — main uses legacy configs/deployment.yaml)'
        )
    }

    sh 'mkdir -p deployments/jenkins'
    def contextPath = "deployments/jenkins/interop-${envName}.yaml"
    def configPath = "deployments/jenkins/interop-${envName}-configuration.yaml"

    def patchScript = "${env.WORKSPACE}/scripts/patch-ceph-interop-config.py"
    if (!fileExists(patchScript)) {
        throw new RuntimeException(
            "updateCephInteropConfig: ${patchScript} not found in workspace " +
            '(jenkins repo scripts/ must be checked out at workspace root)'
        )
    }

    def venvPython = "${env.WORKSPACE}/ceph-interop/.venv/bin/python3"
    if (!fileExists(venvPython)) {
        throw new RuntimeException(
            "updateCephInteropConfig: ${venvPython} not found — run Setup Environment before Update Config"
        )
    }

    def patchArgs = [
        product              : product,
        openshift_profile    : openshiftProfile,
        service_profile      : serviceProfile,
        infra_profile        : infraProfile,
        ocp_version          : ocpVersion,
        ocp_channel          : ocpChannel,
        ibm_region           : ibmRegion,
        vpc_name             : vpcName,
        volume_profile       : volumeProfile,
        storage_profile      : storageProfile,
        device_count         : deviceCount,
        device_size          : deviceSize,
        device_set_count     : deviceSetCount,
        replica              : replicaCount,
        worker_count         : workerCount,
        lso_max              : lsoMax,
        odf_operator_channel : odfOperatorChannel,
        catalog_image        : catalogImage,
        ceph_image           : cephImage,
        ec_default_pools     : ecDefaultPools,
        ec_data_chunks       : ecDataChunks,
        ec_coding_chunks     : ecCodingChunks,
        ec_failure_domain    : 'host',
        fips                 : enableFips,
    ]
    writeJSON file: 'configs/.jenkins-patch.json', json: patchArgs
    sh """
        set -euo pipefail
        '${venvPython}' '${patchScript}' '${baseConfigPath}' 'configs/.jenkins-patch.json' '${configPath}'
        rm -f 'configs/.jenkins-patch.json'
    """
    def contextData = buildDeploymentContext(
        product: product,
        clusterId: clusterId,
        infraProfile: infraProfile,
        dnsProfile: dnsProfile,
        openshiftProfile: openshiftProfile,
        masterCount: masterCount,
        workerCount: workerCount,
        serviceProfile: serviceProfile,
        storageProfile: storageProfile,
        volumeProfile: volumeProfile,
    )
    writeYaml(file: contextPath, data: contextData, overwrite: true)

    env.CONTEXT_PATH = contextPath
    env.CONFIGURATION_PATH = configPath
    env.CLUSTER_ID = clusterId

    echo 'updateCephInteropConfig:'
    echo "  product           : ${product}"
    echo "  envName           : ${envName}"
    echo "  clusterId         : ${clusterId}"
    echo "  contextPath       : ${contextPath}"
    echo "  configurationPath : ${configPath}"
    echo "  openshift_profile : ${openshiftProfile}"
    echo "  service_profile   : ${serviceProfile}"
    echo "  volume_profile    : ${volumeProfile}"
    echo "  storage_profile   : ${storageProfile}"
    echo "  infra_profile     : ${infraProfile}"
    echo "  OCP version       : ${ocpVersion} (${ocpChannel})"
    echo "  catalog_image     : ${catalogImage ?: '(redhat-operators / none)'}"
    echo "  ceph_image        : ${cephImage ?: '(none)'}"
    echo "  ec_scheme         : ${ec.scheme} (${ecDataChunks}+${ecCodingChunks}, enabled=${ecDefaultPools})"
    echo "  fips              : ${enableFips}"
    echo "  workers           : ${workerCount} (requested ${requestedWorkers})"

    env.EC_SCHEME = ec.scheme.toString()
    env.EC_DEFAULT_POOLS = ecDefaultPools ? 'true' : 'false'
    env.EC_DATA_CHUNKS = ecDataChunks.toString()
    env.EC_CODING_CHUNKS = ecCodingChunks.toString()
    env.ENABLE_FIPS = enableFips ? 'true' : 'false'

    return [
        contextPath      : contextPath,
        configurationPath: configPath,
        clusterId        : clusterId,
        openshiftProfile : openshiftProfile,
        serviceProfile   : serviceProfile,
        volumeProfile    : volumeProfile,
        storageProfile   : storageProfile,
    ]
}

private Map buildDeploymentContext(Map args) {
    def services
    if (args.product == 'fdf') {
        services = [
            fdf     : args.serviceProfile,
            storage : args.storageProfile,
            volumes : args.volumeProfile,
        ]
    } else {
        services = [
            odf: [
                profile: args.serviceProfile,
                storage: [
                    mode    : 'internal',
                    profile : args.storageProfile,
                    volumes : args.volumeProfile,
                ],
            ],
        ]
    }

    return [
        context: [
            clusters: [
                (args.clusterId): [
                    infra             : 'ibm_cloud',
                    infra_profile     : args.infraProfile,
                    dns_profile       : args.dnsProfile,
                    openshift_profile : args.openshiftProfile,
                    masters           : [count: args.masterCount],
                    workers           : [count: args.workerCount],
                    services          : services,
                ],
            ],
            clients: [:],
        ],
    ]
}

private String baseContextFileName(String product, String contextFile) {
    def requested = contextFile?.toString()?.trim()
    if (requested) {
        if (requested.contains('/') || requested.contains('..')) {
            throw new RuntimeException(
                "updateCephInteropConfig: contextFile must be a basename, got '${requested}'"
            )
        }
        return requested
    }
    return product == 'fdf'
        ? 'ocp-ibm-vpc-fdf-internal-storage.yaml'
        : 'ocp-ibm-vpc-odf-internal-storage.yaml'
}

private String baseContextCheckoutPath(String product, String contextFile) {
    return "deployments/infra/ocp/${baseContextFileName(product, contextFile)}"
}

private String resolveClusterIdFromContext(Map ctx, String requestedId) {
    def clusters = ctx?.context?.clusters
    if (!clusters || !(clusters instanceof Map) || clusters.isEmpty()) {
        throw new RuntimeException('updateCephInteropConfig: context.clusters is missing or empty')
    }
    def ids = clusters.keySet().collect { it.toString() }.sort()
    if (requestedId) {
        if (!ids.contains(requestedId)) {
            throw new RuntimeException(
                "updateCephInteropConfig: cluster ${requestedId} not in context (available: ${ids})"
            )
        }
        return requestedId
    }
    if (ids.size() == 1) {
        return ids[0]
    }
    throw new RuntimeException(
        "updateCephInteropConfig: context defines multiple clusters ${ids}; pass clusterId explicitly"
    )
}

private String parseMinor(String version) {
    def m = (version?.toString() ?: '') =~ /(\d+\.\d+)/
    return m.find() ? m.group(1) : '4.20'
}

private boolean asBool(Object value) {
    if (value instanceof Boolean) {
        return value
    }
    return value?.toString()?.trim()?.toLowerCase() in ['1', 'true', 'yes', 'on']
}
