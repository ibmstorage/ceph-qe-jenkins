# ODF Interop Jenkins Pipeline

## Overview

The ODF Interop pipeline automates the deployment and testing of OpenShift Container Platform (OCP) with OpenShift Data Foundation (ODF) on IBM Cloud infrastructure. It provides end-to-end testing capabilities for Ceph storage integration with ODF.

## Pipeline Capabilities

### Workflow

The pipeline always executes the complete end-to-end workflow:
- Deploys OCP cluster on IBM Cloud VPC
- Installs ODF operator with custom Ceph image
- Runs IO workload verification (FIO tests on RBD and CephFS)
- **Tears down all resources automatically (mandatory)**

## Key Features

### Automated Cluster Management
- **Auto-generated cluster names**: Format `ocp-ibmc-<build-number>-<random-hex>`
- **IBM Cloud VPC deployment**: Uses existing VPC infrastructure
- **DNS management**: Automatic Route53 configuration via AWS
- **Credential management**: Secure injection from Jenkins secrets

### Storage Testing
- **Custom Ceph images**: Test specific Ceph versions/builds
- **ODF operator channels**: Support for stable and nightly builds
- **IO workload verification**: FIO-based testing on RBD and CephFS
- **Configurable PVC count**: Test with multiple persistent volumes

### Artifact Collection
- **Cluster state files**: `state.json` and `configs/<cluster>-state.json`
- **Authentication files**: Kubeconfig and cluster credentials
- **Workload reports**: FIO test results and performance metrics
- **Complete cluster artifacts**: Compressed archive of cluster directory

## Required Parameters

### Essential Configuration

| Parameter | Description | Example |
|-----------|-------------|---------|
| `CEPH_IMAGE` | Ceph container image to test (REQUIRED) | `quay.io/rhceph-ci/rhceph@sha256:abc123...` |
| `OCP_CHANNEL` | OCP build channel | `released` (default) or `nightly` |
| `OCP_VERSION` | OpenShift version (optional if auto-resolved) | `4.21.10` |

**Supported RHCS versions:** 8.0, 8.1 (squid) and 9.0, 9.1, 9.2 (tentacle). Unsupported versions (e.g. reef / RHCS 7) are detected and the build exits gracefully with status `NOT_BUILT`.

The pipeline resolves the OCP patch version **dynamically** via the [OpenShift Release API](https://openshift-release.apps.ci.l2s4.p1.openshiftapps.com). It tries the latest stable (released) version first; if no GA release exists for that OCP minor, it falls back to the latest nightly automatically.

**RHCS → ODF version mapping:**

| RHCS | Upstream Ceph | Ceph Stream | ODF Minor |
|------|---------------|-------------|-----------|
| 8.0  | 19.2.0-xxx    | squid       | 4.18      |
| 8.1  | 19.2.1-xxx    | squid       | 4.20      |
| 9.0  | 20.1.0-xxx    | tentacle    | 4.21      |
| 9.1  | 20.2.1-xxx    | tentacle    | 4.22      |
| 9.2  | 20.2.2-xxx    | tentacle    | 4.23      |

For **released** ODF installs, the ceph-interop deployer queries the cluster `odf-operator` PackageManifest and picks the highest published `stable-M.m` channel ≤ the mapped minor (e.g. `stable-4.22` → `stable-4.21` until 4.22 ships in `redhat-operators`).

**Digest-only images** (e.g. `quay.io/.../rhceph@sha256:...` with no `rhceph-N-rhel*` in the path) cannot infer the RHCS version from the URL alone. Set **`RHCS_BUILD_VERSION`** (e.g. `9.1`); the pipeline maps it to the corresponding ODF minor and resolves the latest OCP version via API. **bvt-ci** passes **`CEPH_IMAGE`** and **`RHCS_BUILD_VERSION`** (from **`BUILD_VERSION`**).

### OCP

| Parameter | Default | Description |
|-----------|---------|-------------|
| `OCP_CHANNEL` | **released** | `released` or `nightly` |
| `OCP_VERSION` | (empty) | Optional explicit OCP version override |

### ODF

| Parameter | Default | Description |
|-----------|---------|-------------|
| `ODF_CHANNEL` | **released** | `released` or `nightly` — selects build source when no catalog image is provided |
| `ODF_CATALOG_IMAGE` | (empty) | Optional catalog index image; **takes precedence over `ODF_CHANNEL`** |
| `ODF_OPERATOR_CHANNEL` | (empty) | Optional OLM channel e.g. `stable-4.22` (auto `stable-{minor}` from matrix when empty) |

### ODF catalog resolution (`ODF_CHANNEL=nightly`, no `ODF_CATALOG_IMAGE`)

The pipeline runs [`scripts/ocs-build.sh`](../../scripts/ocs-build.sh):

1. Finds `latest-stable-{minor}.X` on `quay.io/rhceph-dev/ocs-registry`
2. Matches the konflux tag with the same manifest digest
3. Returns a **tag-based** pull spec, e.g. `quay.io/rhceph-dev/ocs-registry:4.22.0-105.konflux`

For **released** ODF (`ODF_CHANNEL=released`, no catalog image), the ceph-interop deployer uses `redhat-operators` and picks the highest published `stable-M.m` channel ≤ the mapped minor.

### Other optional parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `RHCS_BUILD_VERSION` | (empty) | RHCS version (e.g. `8.0`, `8.1`, `9.0`, `9.1`, `9.2`); maps to ODF minor and resolves OCP via API; required for digest-only auto OCP/ODF; optional with `rhceph-N-rhel*` image path |
| `IBM_REGION` | `eu-de` | IBM Cloud region |
| `VPC_NAME` | `vpc-ceph-integration` | IBM Cloud VPC name |
| `MASTER_COUNT` | `3` | Number of control plane nodes |
| `WORKER_COUNT` | `3` | Number of worker nodes (min: 3) |
| `DEVICE_SIZE` | `50` | OSD disk size in GB |
| `DEVICE_COUNT` | `3` | Number of OSD disks per worker |
| `PVC_COUNT` | `2` | PVCs per storage type for workload tests |
| `SSH_KEY_NAMES` | `ceph-qe-sa,ceph-qe-sa-svc` | IBM Cloud SSH keys |
| `EMAIL_ADDRESS` | devops DL + stakeholders | Notification email recipients (comma-separated) |

### Repository Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| `CEPH_INTEROP_REPO` | `https://github.ibm.com/ceph-qe-ng/ceph-interop.git` | Deployment automation repository |
| `CEPH_INTEROP_BRANCH` | `main` | Branch to checkout |

## Pipeline Stages

### 1. Initialize Cluster Name
- Generates unique cluster name automatically
- Format: `ocp-ibmc-<build-number><random-hex>`
- Sets `env.CLUSTER_NAME` for use throughout pipeline

### 2. Validate Parameters
- Validates all required parameters
- Checks credential availability
- Verifies worker count (≥3 for ODF)
- Sets build description and display name

### 3. Clone ceph-interop
- Checks out deployment automation repository
- Uses configurable repo URL and branch

### 4. Setup Environment
- Creates Python virtual environment
- Installs dependencies from requirements.txt
- Verifies Python 3.12+ availability

### 5. Prepare Credentials
- Writes credentials.yaml using Jenkins secrets
- Creates pull-secret.json for image registry access
- Sets restrictive file permissions

### 6. Update Config
- Reads existing deployment.yaml
- Updates with job parameters
- Configures OCP, ODF, and infrastructure settings

### 7. Deploy OCP + ODF
- Creates OCP cluster on IBM Cloud VPC
- Installs ODF operator with custom Ceph image
- Configures storage cluster

### 8. Run IO Workloads
- Creates PVCs on RBD and CephFS
- Runs FIO performance tests
- Generates workload reports

### 9. Teardown (Mandatory)
- Cleans up all cluster resources
- Removes VPC infrastructure
- Always executed at the end of the pipeline

## Post-Build Actions

### Artifact Archival
- `state.json` - Cluster state and metadata
- `configs/<cluster>-state.json` - Legacy state file
- `cluster-artifacts-<cluster>.tar.gz` - Complete cluster directory
- `work/clusters/<cluster>/openshift/auth/*` - Authentication files
- `work/clusters/<cluster>/workload-report-*.txt` - Test results

### Email Notifications
- Sent for all build results (success, failure, aborted)
- Includes cluster details, versions, build links, and triggering user
- **Ceph image metadata** from `cephadm version --verbose` (Ceph version string, git commit, release name) when the Ceph image metadata stage succeeds
- **Test case execution** (same HTML table as the status rows, appended after **Duration**): a full-width **Test case execution** header row, then rows with columns **Test** (short title only) | **Result** (Pass / Fail / Partial / Not run). Includes deployment, per–storage-class lines when the workload report lists allowed SCs, and **IO metrics (FIO / workload)** inferred from `workload-report-*.txt`
- Default `EMAIL_ADDRESS` includes the devops alerts list plus stakeholders (override per run via job parameter)
- Attaches cluster artifacts archive and state.json file
- Color-coded status indicators

### Failure Handling
- Credential cleanup (always executed)
- Best-effort artifact collection
- Teardown stage always runs (mandatory)

## Usage Examples

### BVT-style auto-resolve (released OCP + released ODF)

Matches what **bvt-ci** passes when `TRIGGER_ODF_INTEROP` is enabled:

```
CEPH_IMAGE=quay.io/rhceph-dev/rhceph-9-rhel9:...
RHCS_BUILD_VERSION=9.1   # if digest-only
```

### Minimal dev interop (nightly builds)

```
CEPH_IMAGE=quay.io/rhceph-dev/rhceph-9-rhel9:...
OCP_CHANNEL=nightly
ODF_CHANNEL=nightly
RHCS_BUILD_VERSION=9.1   # if digest-only
```

### Pinned ODF catalog (overrides ODF_CHANNEL)

```
CEPH_IMAGE=...
ODF_CATALOG_IMAGE=quay.io/rhceph-dev/ocs-registry:4.22.0-105.konflux
```

### Explicit OCP + ODF operator override

```
CEPH_IMAGE=...
OCP_VERSION=4.22.10
OCP_CHANNEL=released
ODF_OPERATOR_CHANNEL=stable-4.22
```

**Note**: The pipeline always runs the complete workflow (Deploy → Workload → ocs-ci → Teardown). Teardown runs only when deploy, workload, and ocs-ci stages all succeed.

## Build Description Format

The pipeline sets a clean, pipe-separated build description with the triggering user:

```
User: john.doe | Cluster: ocp-ibmc-29031a | OCP: 4.20.13 (released) | Ceph: rhceph@sha256:c32609c9cfeac...
```

## Prerequisites

### Jenkins Credentials (Configured via CasC)
- `ibmc-api-key` - IBM Cloud API key
- `ibm-cos-acces-key` - IBM COS HMAC access key
- `ibm-cos-secret-key` - IBM COS HMAC secret key
- `aws-systest-access` - AWS access key (for Route53)
- `aws-systest-secret-access` - AWS secret key (for Route53)
- `ocs-pull-secret` - OCP pull secret (Jenkins **file** credential; JSON content, CasC `secretBytes` from base64-encoded file)
- `ceph-qe-sa-pat` - GitHub PAT for repository access

### IBM Cloud Resources
- Existing VPC (`vpc-ceph-integration`)
- SSH keys registered in IBM Cloud
- Sufficient quota for compute resources

### Network Requirements
- Access to IBM Cloud API endpoints
- Access to AWS Route53 for DNS
- Access to container registries (quay.io, registry.redhat.io, etc.)

## Troubleshooting

### Common Issues

**Issue**: Cluster name shows as `null`
- **Cause**: Environment variable not set properly
- **Solution**: Check "Initialize Cluster Name" stage logs

**Issue**: Deployment fails with credential errors
- **Cause**: Missing or invalid Jenkins credentials
- **Solution**: Verify all required credentials in Jenkins

**Issue**: Worker count validation fails
- **Cause**: WORKER_COUNT < 3
- **Solution**: ODF requires minimum 3 workers

**Issue**: Teardown fails to find cluster
- **Cause**: Incorrect CLUSTER_NAME or cluster already removed
- **Solution**: Verify cluster name from previous build

### Debug Tips

1. **Check validation stage**: All parameters are logged
2. **Review state.json**: Contains cluster metadata and status
3. **Examine workload reports**: Shows storage performance metrics
4. **Check archived artifacts**: Complete cluster configuration available

## Rollout and migration

After merging the `odf-interop` branch to `main`:

1. **Re-run the seed job** on `main` — updates `odf-interop` job parameters (`ODF_NIGHTLY_INDEX` removed; `ODF_CATALOG_IMAGE`, `ODF_OPERATOR_CHANNEL`, and choice-based `ODF_CHANNEL` added)
2. **Smoke test** before relying on BVT downstream:
   - Manual run with only `CEPH_IMAGE` + `RHCS_BUILD_VERSION` (mimics BVT) — confirm `released` OCP/ODF resolution
   - Manual run with `OCP_CHANNEL=nightly` + `ODF_CHANNEL=nightly` — confirm dev catalog resolution
3. **Delete** the `odf-interop-test-pipeline` Jenkins job manually (seed uses `removeAction: IGNORE`, so it is not auto-removed)
4. **Migrate saved configs**: move any saved `ODF_NIGHTLY_INDEX` values to `ODF_CATALOG_IMAGE`, or use `ODF_CHANNEL=nightly` for auto-resolve; move explicit `stable-4.xx` values from old `ODF_CHANNEL` to `ODF_OPERATOR_CHANNEL`

## Related Documentation

- [Cleanup Registry Images](./cleanup_registry_images.md)
- [Sync Manifest Images](./sync_manifest_images.md)
- [ceph-interop Repository](https://github.ibm.com/ceph-qe-ng/ceph-interop)

## Support

For issues or questions:
- Email: ibm-ceph-qe-devops-alerts-dg@wwpdl.vnet.ibm.com
- Repository: https://github.ibm.com/ceph-qe-ng/jenkins
