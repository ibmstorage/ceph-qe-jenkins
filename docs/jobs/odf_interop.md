# ODF Interop Jenkins Pipeline

## Overview

The ODF Interop pipeline automates the deployment and testing of OpenShift Container Platform (OCP) with OpenShift Data Foundation (ODF) on IBM Cloud infrastructure. It provides end-to-end testing capabilities for Ceph storage integration with ODF.

## ceph-interop CLI (framework migration)

This pipeline uses the **new ceph-interop framework** (not legacy `run.py`):

| Action | Command |
|--------|---------|
| Deploy | `ceph-interop deploy --context … --env-name ENV_NAME` |
| IO workloads | `ceph-interop run-tests --env-name ENV_NAME --suite tests/workloads -m workload` |
| Teardown | `ceph-interop teardown --env-name ENV_NAME --level all` |

Setup installs the CLI with `pip install --no-build-isolation -e .` (dependencies from `pyproject.toml`).

### Identity model

| Concept | Jenkins param / env | ceph-interop flag |
|---------|---------------------|-------------------|
| Workspace key | `ENV_NAME` (default `interop-BUILD_NUMBER`) | `--env-name` |
| OpenShift cluster name | Auto-generated; read from `state.json` after deploy | (internal) |
| Cluster slot in context | From deployment context YAML (e.g. `ocp-1`) | `--cluster-id` (set from context after Update Config) |

Deprecated: `CLUSTER_NAME` is an alias for `ENV_NAME` only — it no longer sets the OpenShift cluster name.

### Artifact paths

```
ceph-interop/work/context/{ENV_NAME}/ocp-1/
├── state.json
├── bin/oc
├── openshift/auth/kubeconfig
└── workload-report-*.txt
```

Configuration and deployment context are taken from the **ceph-interop** checkout (`configs/configuration.yaml` and `deployments/infra/ocp/ocp-ibm-vpc-odf-internal-storage.yaml`), patched per build into isolated files under `deployments/jenkins/`:

- `deployments/jenkins/interop-{ENV_NAME}.yaml` — deployment context
- `deployments/jenkins/interop-{ENV_NAME}-configuration.yaml` — patched profiles (checkout `configs/configuration.yaml` is not modified)

## Pipeline Capabilities

### Workflow

The pipeline always executes the complete end-to-end workflow:
- Deploys OCP cluster on IBM Cloud VPC
- Installs ODF operator with custom Ceph image
- Runs IO workload verification (FIO tests on RBD and CephFS)
- **Tears down all resources automatically (mandatory)**

## Key Features

### Automated Cluster Management
- **Environment name**: `interop-<build-number>` (or `ENV_NAME` override); OpenShift name auto-generated as `ocp-ibmc-<4hex>`
- **IBM Cloud VPC deployment**: Uses existing VPC infrastructure
- **DNS management**: Automatic Route53 configuration via AWS
- **Credential management**: Secure injection from Jenkins secrets

### Storage Testing
- **Custom Ceph images**: Test specific Ceph versions/builds
- **ODF operator channels**: Support for stable and nightly builds
- **IO workload verification**: FIO-based testing on RBD and CephFS
- **Configurable PVC count**: Currently ignored by `tests/workloads` (hardcoded `pvc_count=1` until ceph-interop adds `--interop-pvc-count`)

### Artifact Collection
- **Cluster state**: `work/context/{ENV_NAME}/ocp-1/state.json`
- **Authentication files**: `work/context/{ENV_NAME}/ocp-1/openshift/auth/`
- **Workload reports**: `work/context/{ENV_NAME}/ocp-1/workload-report-*.txt`
- **Complete cluster artifacts**: `cluster-artifacts-{ENV_NAME}.tar.gz`

## Required Parameters

### Essential Configuration

| Parameter | Description | Example |
|-----------|-------------|---------|
| `ENV_NAME` | (empty) | ceph-interop `--env-name` (default `odf-interop-BUILD_NUMBER`) |
| `CEPH_IMAGE` | Ceph container image to test (REQUIRED) | `quay.io/rhceph-ci/rhceph@sha256:abc123...` |
| `OCP_VERSION` | OpenShift version (optional if auto-resolved) | `4.21.10` |

**Supported RHCS versions:** 8.0, 8.1 (squid) and 9.0, 9.1, 9.2 (tentacle). Unsupported versions (e.g. reef / RHCS 7) are detected and the build exits gracefully with status `NOT_BUILT`.

The pipeline resolves the OCP patch version **dynamically** via the [OpenShift Release API](https://openshift-release.apps.ci.l2s4.p1.openshiftapps.com). It tries the latest stable (released) version first; if no GA release exists for that OCP minor, it falls back to the latest nightly automatically.

**RHCS → interop version mapping** (same minor for OCP and ODF/FDF):

| RHCS | Upstream Ceph | Ceph Stream | OCP / ODF(FDF) |
|------|---------------|-------------|----------------|
| 8.0  | 19.2.0-xxx    | squid       | 4.18           |
| 8.1  | 19.2.1-xxx    | squid       | 4.20           |
| 9.0  | 20.1.0-xxx    | tentacle    | 4.21           |
| 9.1  | 20.2.1-xxx    | tentacle    | 4.22           |
| 9.2  | 20.2.2-xxx    | tentacle    | 5.0            |

RHCS 9.2 resolves OCP from the **5.0** release stream (`5.0.0-0.nightly`, `stable-5.0` operator channel, catalog tags `5.0.*` / `v5.0*`). OCP 4.23 is the legacy co-release label for the same generation.

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
| `ODF_OPERATOR_CHANNEL` | (empty) | Optional OLM channel e.g. `stable-5.0` (auto `stable-{odfMinor}` from matrix when empty) |

### ODF catalog resolution (`ODF_CHANNEL=nightly`, no `ODF_CATALOG_IMAGE`)

The pipeline runs [`scripts/ocs-build.sh`](../../scripts/ocs-build.sh):

1. Paginates Quay for `*.konflux` tags matching the ODF minor (`filter_tag_name=like:{minor}.`)
2. Returns the **highest** tag by version sort (newest dev build), e.g. `quay.io/rhceph-dev/ocs-registry:5.0.0-42.konflux`
3. Use `ocs-build.sh --stable` locally to match the `latest-stable-{minor}.X` digest instead (older stable-aligned build)

Returns a **tag-based** pull spec suitable for catalog source deployment.

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
| `EMAIL_ADDRESS` | devops DL + stakeholders | Notification email recipients (comma-separated) |

IBM Cloud SSH key names are fixed in the Jenkins configuration template (`ceph-qe-sa`, `ceph-qe-sa-svc`); they are not job parameters.

### Repository Configuration

| Parameter | Default | Description |
|-----------|---------|-------------|
| `CEPH_INTEROP_REPO` | `https://github.ibm.com/ceph-qe-ng/ceph-interop.git` | Deployment automation repository |
| `CEPH_INTEROP_BRANCH` | `new` | Branch to checkout (framework on `new`; `main` is legacy) |

## Pipeline Stages

### 1. Initialize Environment
- Sets `INTEROP_PRODUCT` (default `odf`) and `ENV_NAME`
- OpenShift cluster name is resolved after deploy from `state.json`

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
- Installs `pip install --no-build-isolation -e .` from `pyproject.toml` (registers `ceph-interop` CLI)

### 5. Prepare Credentials
- Writes `configs/credentials.yaml` with `accounts.ibm_qe.ibmcloud` (matches `credential_profile: ibm_qe`)

### 6. Update Config
- Writes per-build `deployments/jenkins/interop-{ENV_NAME}-configuration.yaml` (from checkout `configs/configuration.yaml`) and `deployments/jenkins/interop-{ENV_NAME}.yaml` (deployment context)
- Patches deployment context (`deployments/jenkins/interop-{ENV_NAME}.yaml`)

### 7. Deploy OCP + ODF
- `ceph-interop deploy --context … --env-name ENV_NAME`

### 8. Run IO Workloads
- `ceph-interop run-tests --suite tests/workloads -m workload`

### 9. Teardown (Mandatory)
- `ceph-interop teardown --env-name ENV_NAME --level all`

## Post-Build Actions

### Artifact Archival
- `work/context/{ENV_NAME}/ocp-1/state.json`
- `cluster-artifacts-{ENV_NAME}.tar.gz`
- `work/context/{ENV_NAME}/ocp-1/openshift/auth/*`
- `work/context/{ENV_NAME}/ocp-1/workload-report-*.txt`

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

Matches what **bvt-ci** passes when `TRIGGER_DATA_FOUNDATION_INTEROP` is enabled:

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

1. **Re-run the seed job** on `main` — updates `odf-interop` (Acceptance + Tier-1 stages, 48h timeout, email layout)
2. **Manually delete** the Jenkins job `odf-interop-test-pipeline` (display name: Interop : ODF-Ceph Testing [TEST]) — seed uses `removeAction: IGNORE`, so it is not auto-removed
3. **Smoke test** before relying on BVT downstream:
   - Manual run with only `CEPH_IMAGE` + `RHCS_BUILD_VERSION` (mimics BVT) — confirm `released` OCP/ODF resolution
   - Confirm Acceptance (must pass) + Tier-1 (non-blocking) stages and dual OCS-CI email section
4. **Migrate saved configs** (if still needed): move any saved `ODF_NIGHTLY_INDEX` values to `ODF_CATALOG_IMAGE`, or use `ODF_CHANNEL=nightly` for auto-resolve; move explicit `stable-4.xx` values from old `ODF_CHANNEL` to `ODF_OPERATOR_CHANNEL`

## Related Documentation

- [FDF Interop Pipeline](./fdf_interop.md)
- [Cleanup Registry Images](./cleanup_registry_images.md)
- [Sync Manifest Images](./sync_manifest_images.md)
- [ceph-interop Repository](https://github.ibm.com/ceph-qe-ng/ceph-interop)

## Support

For issues or questions:
- Email: ibm-ceph-qe-devops-alerts-dg@wwpdl.vnet.ibm.com
- Repository: https://github.ibm.com/ceph-qe-ng/jenkins
