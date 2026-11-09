# Jenkins Repository — Architecture Overview

This document describes the high-level architecture of the `ibmstorage/ceph-qe-jenkins` repository: how the system is structured, how components interact, and how a build progresses from source to tested artifact.

---

## Table of Contents

1. [Repository Layout](#repository-layout)
2. [Jenkins Controller](#jenkins-controller)
3. [Agent Fleet](#agent-fleet)
4. [Pipeline Categories](#pipeline-categories)
5. [End-to-End Build Flow](#end-to-end-build-flow)
6. [Shared Library](#shared-library)
7. [Manifest Repository](#manifest-repository)
8. [External Systems](#external-systems)
9. [Credential Model](#credential-model)
10. [Job DSL and Seed Mechanism](#job-dsl-and-seed-mechanism)

---

## Repository Layout

```
jenkins/
├── casc.yaml                    # Jenkins Configuration as Code (controller config)
├── Containerfile                # Container image definition for the Jenkins controller
├── Containerfile.agent          # Container image definition for Jenkins agents
├── plugins.txt                  # Pinned plugin list (loaded at image build time)
├── jobs/
│   ├── seed.groovy              # Master seed job (bootstraps all other jobs)
│   ├── *.groovy                 # Job DSL files — one per pipeline family
│   └── pipelines/
│       └── <job-name>/
│           └── Jenkinsfile      # Pipeline implementation for each job
├── vars/                        # Shared library steps (callable from any Jenkinsfile)
├── src/main/groovy/cephJobLib/  # Shared library config classes
├── metadata/                    # YAML test-suite metadata per Ceph release
│   ├── reef/
│   ├── squid/
│   └── tentacle/
├── scripts/                     # Python helper scripts (e.g. fetchTestSuites.py)
├── views/                       # Jenkins dashboard view definitions
├── templates/                   # Email/report Jinja templates
├── docs/                        # Documentation (this file lives here)
└── secrets/                     # Mount point for externally injected secrets (not in git)
```

---

## Jenkins Controller

The controller is deployed as a container built from [`Containerfile`](../Containerfile). Its entire configuration is expressed as code and applied at startup:

| Concern | Mechanism |
|---|---|
| System config (agents, SMTP, URLs) | [`casc.yaml`](../casc.yaml) — Configuration as Code plugin |
| Plugin installation | [`plugins.txt`](../plugins.txt) — loaded by `jenkins-plugin-cli` at image build time |
| Credential injection | Secrets are mounted at `/secrets/` by the container runtime and read by casc.yaml via `${readFile:/secrets/…}` |
| Job/view creation | The `seed` freestyle job runs Job DSL on every `jobs/*.groovy` file |
| Shared library | This repo itself is registered as the `ceph-qe-libs@main` global library |

The controller runs **zero executors** (`numExecutors: 0`). All work runs on remote agents.

---

## Agent Fleet

Five permanent agents are declared in `casc.yaml`, each with a dedicated label:

| Agent | Label | Purpose |
|---|---|---|
| `infra-vm-jenkins-agent-01` | `agents` | General IBM Cloud test execution |
| `infra-vm-jenkins-agent-02` | `infra` | Infrastructure & image sync jobs |
| `infra-vm-jenkins-agent-03` | `agents` | General IBM Cloud test execution |
| `infra-vm-jenkins-agent-aws-01` | `agents-aws` | AWS test execution |
| `agent-interop` | `agents-interop` | ODF Interop long-running deployments |

All agents share the same remote root at `/data/jenkins` and are connected over SSH.

Pipelines select their agent by label:

```
CLOUD_TYPE == 'aws'  →  agents-aws
ODF Interop          →  agents-interop
Sync / Infra jobs    →  infra
All other CI jobs    →  agents  (IBM Cloud)
```

---

## Pipeline Categories

### 1. CI Test Pipelines

These pipelines provision cloud VMs, execute test suites from the CephCI framework in parallel, clean up resources, and publish results.

| Pipeline | Job Name Pattern | Purpose |
|---|---|---|
| BVT | `{reef,squid,tentacle}-bvt-ci` | Quick build-verification gate; triggered by Listener |
| Sanity | `{reef,squid,tentacle}-sanity-ci`, `tentacle-sanity-fips-ci` | Broader smoke test; skips if build unchanged (weekly FIPS run for Tentacle) |
| Rook Defaults | `tentacle-rook-defaults-ci` | Tentacle cephci suites with `--custom-config apply-odf-defaults=true` (metadata: `metadata/tentacle/rook.yaml`) |
| Regression | `*-regression-ci` | Full regression per Feature Group (FG) |
| Extended Regression | `*-extended-regression-ci` | Deeper coverage for specific FGs |
| Upgrade | `*-upgrade-ci` | Validates upgrade paths between Ceph versions |
| Sanity Upgrade | `*-sanity-upgrade-ci` | Sanity check of upgrade paths |
| Released | `*-released-ci` | Tests against a released (GA) build |
| RC Respin | `{squid,tentacle}-respin-ci` | RC respin gate; manual trigger |
| Test Executor | `test-executor` | Ad-hoc manual test run against a single suite |

**RC Respin**: `RELEASE` selects the `respin` or `rc` section in `qe-ceph-manifest` (`{redhat|ibm}/{BUILD_VERSION}.yaml`); Validate aborts if that section is missing or incomplete (build team must publish a complete `respin`/`rc` block, same expectation as BVT). Suites come from `metadata/{squid,tentacle}/common.yaml` (`respin:`), with wave split by metadata `tags` (via `fetchTestSuites`). The pipeline runs **BVT → sanity → upgrades** in sequence (parallel suites within each wave). 

All of these follow the **same six-stage pattern**:

```
Validate → Setup → Fetch Suites → Get Stages → Execute Suites (parallel) → Post
```

See [Shared Pipeline Pattern](#shared-pipeline-pattern) below.

### 2. Image & Repository Sync Pipelines

These pipelines mirror container images and RPM repositories from upstream registries to the internal NGINX registry/server so that test agents can reach them without external network access.

| Pipeline | Job Name | Purpose |
|---|---|---|
| Listener | `ceph-build-konflux-listener` | Receives Konflux webhooks; drives image sync and triggers BVT |
| Sync Blue Images | `sync-blue-ceph-images` | Sync IBM container images to local registry; optionally triggers BVT |
| Sync Manifest Images | `sync-manifest-images` | Sync all images listed in the manifest repo to local registry |
| Sync IBM Build & Publish | `sync-ibm-build-publish` | Orchestrates image sync + repo sync + manifest update for IBM builds |
| Sync Blue Repo | `sync-blue-ceph-repos` | Sync IBM RPM repositories to the internal NGINX server |
| Sync RHCS Repo | `sync-rhcs-repo` | Sync Red Hat Ceph RPM repositories |
| Sync Blue Compose | `sync-blue-compose` | Copy compose archive from IBM Artifactory |
| Sync Mirror | `sync-mirror` | Mirror OS RPM repositories |

### 3. Interop Pipelines (ODF + FDF)

| Pipeline | Job Name | Jenkinsfile | Purpose |
|---|---|---|---|
| ODF Interop | `odf-interop` | `jobs/pipelines/odf_interop/Jenkinsfile` | Deploy OCP + ODF, IO workloads, ocs-ci acceptance + tier-1 |
| FDF Interop | `fdf-interop` | `jobs/pipelines/fdf_interop/Jenkinsfile` | Deploy OCP + FDF + IO workloads (no ocs-ci) |

The ODF job is triggered by `bvt-ci` when `BUILD_TYPE=rh` and `TRIGGER_DATA_FOUNDATION_INTEROP=true`. FDF is triggered when `BUILD_TYPE=ibm` and the same flag is set (e.g. from `sync-blue-ceph-images`).

### 4. Maintenance Pipelines

| Pipeline | Job Name | Purpose |
|---|---|---|
| Cleanup VMs | `cleanup-vms` | Detect and optionally delete orphaned IBM Cloud VMs |
| Cleanup Registry Images | `cleanup-registry-images` | Remove orphaned images from local registry |
| DNS Cleanup | `dns-cleanup` | Detect and optionally delete orphaned IBM Cloud DNS entries |

---

## End-to-End Build Flow

### Automated Path (IBM/Red Hat Konflux build)

```
┌─────────────────────────────────────────────────────────────────────┐
│  Konflux Build System                                               │
│  (produces new container images)                                    │
└───────────────────────────┬─────────────────────────────────────────┘
                            │  webhook payload (JSON)
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│  ceph-build-konflux-listener                                        │
│  1. Parse build message → extract images, version, build type       │
│  2. Write build metadata to qe-ceph-manifest (nightly section)      │
│  3. Sync container images to local NGINX registry                   │
│  4. If ceph-base image changed → trigger {release}-bvt-ci           │
└───────────────────────────┬─────────────────────────────────────────┘
                            │  build(job: '{release}-bvt-ci')
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│  {reef,squid,tentacle}-bvt-ci                                       │
│  1. generateJobConfigs → read manifest for build info               │
│  2. setupCephCiRepo → clone cephci, create Python venv              │
│  3. fetchTestSuites → load suite metadata for 'bvt' FG             │
│  4. Run all suites in parallel on IBM Cloud / AWS VMs               │
│  5. cleanupCluster after each suite                                 │
│  6. publishTestResults → Report Portal, Google Sheets, email, COS   │
│  7. If SUCCESS + TRIGGER_DATA_FOUNDATION_INTEROP → odf-interop (RH) or fdf-interop (IBM) │
└────────────┬──────────────────────────────┬────────────────────────┘
             │  (downstream)                │  (manual / scheduled)
             ▼                              ▼
┌────────────────────────┐     ┌────────────────────────────────────┐
│  odf-interop           │     │  sanity-ci / regression-ci /       │
│  Deploy OCP + ODF      │     │  upgrade-ci / extended-regression  │
│  Run FIO workloads     │     │  released-ci / test-executor       │
│  Run ocs-ci tests      │     └────────────────────────────────────┘
│  Teardown cluster      │
└────────────────────────┘
```

### IBM Build Publish Path

```
IBM Build System
      │  (manual trigger or automation)
      ▼
sync-ibm-build-publish
  ├─ sync-blue-ceph-images   (mirrors container images)
  ├─ sync-blue-ceph-repos    (mirrors RPM repos to NGINX)
  └─ syncBuildInfoToGithubManifest  (writes ibm/{version}.yaml)
```

---

## Shared Pipeline Pattern

Every CI test pipeline (BVT, Sanity, Regression, Upgrade, Released, Extended Regression, Sanity Upgrade, Test Executor) follows the same structural template:

```groovy
stage("Validate")      → generateJobConfigs(job: "…")
stage("Setup")         → setupCephCiRepo(…)
stage("Fetch Suites")  → fetchTestSuites(…)
stage("Get Stages")    → build a map of { suiteName → closure }
stage("Execute Suites")→ parallel(testSuitesMap)
post { always }        → publishTestResults(…)
```

Each suite closure:
1. Binds per-suite fields (`suite`, `custom_config`, etc.) before building the parallel stage map (avoids Groovy late-binding bugs)
2. Calls `execTestSuites()` with `stageCustomConfig` and `suiteCustomConfig` from metadata, which invokes `cephci/run.py` inside the Python venv
3. On completion (success or failure), calls `cleanupCluster()` in a `finally` block
4. Generates an `xunit.xml` result file collected by `publishTestResults`

**Metadata-driven suite selection:**
Test suites are not hardcoded. `fetchTestSuites()` invokes `scripts/fetchTestSuites.py` which reads YAML files under `metadata/{ceph-release}/{fg}.yaml`, filtering by platform, build type, and tags. This makes it possible to add or remove test cases without touching any Jenkinsfile.

Per-suite `custom-config` in metadata (for example `enable-fips-mode: true`) is exposed as `suite.custom_config` and passed to `execTestSuites` as `--custom-config key=value` on that suite only. Metadata CI jobs do not expose `ENABLE_FIPS_MODE`; use suite metadata for FIPS in CI, or test-executor / `CUSTOM_CONFIG` for ad-hoc runs.

---

## Shared Library

This repository is registered as the global Jenkins shared library `ceph-qe-libs`. Every Jenkinsfile begins with:

```groovy
@Library("ceph-qe-libs@main") _
```

### `vars/` — callable pipeline steps

| Step | Purpose |
|---|---|
| `generateJobConfigs` | Validate params; read manifest; build unified `jobConfig` map for the pipeline |
| `setupCephCiRepo` | Clone cephci git repo, create Python venv, inject credentials |
| `fetchTestSuites` | Query `fetchTestSuites.py` and return structured suite list |
| `execTestSuites` | Merge job and metadata `custom-config`; build and execute `run.py`; validate `xunit.xml` output |
| `cleanupCluster` | Destroy provisioned cloud resources after a test |
| `publishTestResults` | Generate results, post to Report Portal / Google Sheets, upload to COS, send email |
| `readFromManifest` | Clone `qe-ceph-manifest` and parse a YAML recipe file |
| `writeToManifest` | Update a YAML recipe file and push to `qe-ceph-manifest` |
| `checkForNewBuilds` | Compare nightly vs stable versions in manifest to detect new builds |
| `validateKonfluxBuilds` | Parse Konflux webhook JSON; extract build type, version, images |
| `syncBlueImages` | Skopeo-copy a list of container images to the local NGINX registry |
| `syncContainerImage` | Skopeo-copy a single container image (routes by source registry) |
| `syncManifestImages` | Clone manifest repo, extract all images, batch sync to registry |
| `syncBlueRepos` | SSH to NGINX server; run containerised repo-sync for each RPM repo |
| `processCephBaseImage` | Introspect IBM Ceph image via podman; extract version, release, OS platform |
| `fetchCephImageMetadata` | Run `cephadm version` inside container to get Ceph version metadata |
| `fetchCephVersion` | Run `dnf repoquery` inside UBI container to read Ceph package version |
| `updateBvtResults` / `updateSanityResults` | Parse result JSON; promote to `stable` in manifest if PASS (sanity copies full `nightly` section) |
| `checkImageInRegistry` | HTTP-check whether an image already exists in the local registry |
| `cleanupRegistryImages` | Remove orphaned images from local NGINX registry |
| `generatePrefixString` | Generate a short random suffix for VM/resource naming |

> For full parameter and return-value documentation for every step, see [`docs/vars-reference.md`](vars-reference.md).

### `src/main/groovy/cephJobLib/` — configuration classes

| Class | Purpose |
|---|---|
| `cephciConfigs` | CephCI git repo URL/branch, Python version, venv path, cloud defaults, inventory file paths |
| `ibmcConfigs` | IBM Cloud COS bucket, NGINX registry hostname/port/IP, Brew registry, repo paths |
| `jobConfigs` | Build artifact directory paths, result file names, email template name |
| `manifestConfigs` | Manifest repo SSH URL, default branch, clone directory, default datacenter |
| `rpConfigs` | Report Portal hostname, project names (`rhcs`, `ibmceph`, `upstream`), data router URL |
| `mailerConfigs` | Cost-report mailer repo URL, email subjects, recipient DL addresses |

---

## Manifest Repository

`qe-ceph-manifest` (GitHub: `ibmstorage/qe-ceph-manifest`) acts as the **shared build state store** between pipelines.

### Recipe file structure

Each vendor/version combination has a YAML recipe file (e.g., `ibm/9.1.yaml`, `redhat/8.1.yaml`):

```yaml
nightly:
  version: "20.1.0-200"
  images:
    ceph-base: cp.stg.icr.io/cp/ibm-ceph/ceph-rhel9:v9.1-200
    prometheus_image: cp.stg.icr.io/cp/ibm-ceph/prometheus-rhel9:v9.1-200
    # … more images …
  repositories:
    eu-de:
      rhel-9: http://10.243.80.5/repos/ceph/testing/ibm/9.1/rhel-9/…
      rhel-10: http://10.243.80.5/repos/ceph/testing/ibm/9.1/rhel-10/…
    default:
      rhel-9: https://…

stable:
  version: "20.1.0-195"
  images: { … }
  repositories: { … }
```

### Who reads and writes it

| Operation | Who | When |
|---|---|---|
| **Write** nightly | `listener` / `sync-ibm-build-publish` | On every new Konflux / IBM build |
| **Read** for test config | `generateJobConfigs` (via `readFromManifest`) | Start of every CI pipeline |
| **Write** stable | `updateBvtResults` / `updateSanityResults` | After BVT / Sanity passes |
| **Read** to check new build | `checkForNewBuilds` | Start of sanity-ci |

---

## External Systems

| System | Used by | Purpose |
|---|---|---|
| **IBM Cloud VPC** | All CI test pipelines | Provision VSI test VMs |
| **IBM Cloud COS** (`ceph-qe-logs`) | `publishTestResults`, ODF Interop | Archive test logs |
| **AWS EC2** | CI test pipelines (CLOUD_TYPE=aws) | Alternative test VM platform |
| **AWS Route53** | ODF Interop | DNS for OCP cluster |
| **IBM Container Registry** (`cp.icr.io`, `cp.stg.icr.io`) | Sync pipelines | Source for IBM Ceph images |
| **Red Hat Registries** (`registry.redhat.io`, `brew.registry.redhat.io`, `quay.io`) | Sync pipelines | Source for RH images |
| **Internal NGINX Registry** (`nginx-vm-01.qe.ceph.lab:5000`) | All CI pipelines | Local mirror for all container images |
| **Internal NGINX Repo Server** (`nginx-vm-01.qe.ceph.lab`) | CI test pipelines | Local mirror for RPM repositories |
| **GitHub** (`red-hat-storage/cephci`) | All CI pipelines | Test automation framework |
| **GitHub IBM** (`ibmstorage/qe-ceph-manifest`) | Listener, generateJobConfigs, write/read | Shared build state store |
| **Report Portal** (`reportportal-ocs3.apps.dno…`) | `publishTestResults` | Test result dashboards |
| **Google Sheets** | `publishTestResults` | Test result spreadsheets |
| **SendGrid** (`smtp.sendgrid.net:587`) | `publishTestResults`, ODF Interop | Email notifications |
| **Red Hat Data Router** (`datarouter.ccitredhat.com`) | `publishTestResults` | RHCS result ingestion |
| **Konflux** | `listener` (receives from) | Build system that triggers CI via webhook |
| **IBM Artifactory (TaaS)** | `sync-blue-compose` | Source for IBM compose archives |

---

## Credential Model

All secrets are stored **outside the container image** in a `/secrets/` volume mount. `casc.yaml` reads them at controller startup via `${readFile:/secrets/…}`. No secrets are baked into git.

Key credential IDs and their usage:

| Credential ID | Type | Used by |
|---|---|---|
| `osp-cred` | File (YAML) | All IBM Cloud CI pipelines — global cephci cloud config |
| `cephci-conf` | File (YAML → `.cephci.yaml`) | CephCI lab config on agents (`~/.cephci.yaml`); source `/secrets/cephci_conf` — see [structure](secrets/cephci_conf.md) |
| `aws-osp-cred` | File (YAML) | AWS CI pipelines |
| `all-cr-auth` | File (JSON) | Skopeo authentication for all container registries |
| `ceph-qe-sa-pat` | Username+Password | Git clone of IBM GitHub repos (manifest, jenkins) |
| `gh-ceph-qe-sa-pat` | String | GitHub.com PAT |
| `icr-token` / `stg-icr-token` | String | IBM Container Registry authentication |
| `vmLoginAccessKey` / `vmLoginAccessKey2` | SSH key | SSH login to provisioned test VMs |
| `jenkinsAgentSSHKey` / `awsAgentSSHKey` | SSH key | Jenkins controller → agent connection |
| `ibmc-api-key` / `ibmc-account-id` | String | ODF Interop IBM Cloud operations |
| `ibm-cos-acces-key` / `ibm-cos-secret-key` | String | rclone upload to IBM COS |
| `aws-systest-access` / `aws-systest-secret-access` | String | ODF Interop Route53 DNS |
| `ocs-pull-secret` | File (JSON) | OCP pull secret for ODF Interop |
| `ibm-si-tenant` | File (JSON → `si-tenant.json`) | Call-home Storage Insights tenant; source `/secrets/ibm_si_tenant` |
| `gcp-service-account` | File (JSON) | Google Sheets result publishing |
| `send-grid-api` | Username+Password | SMTP via SendGrid |
| `repo_access_key` | File | SSH key for RPM repo sync to NGINX |
| `sps-api-key` | String | Secure Pipeline Services BVT checker |

---

## Job DSL and Seed Mechanism

Jenkins jobs are not created manually. They are generated by the **seed job** (`jobs/seed.groovy`), which runs Job DSL against every `jobs/*.groovy` file.

### Bootstrap sequence

```
1. casc.yaml  →  creates the seed freestyle job from /usr/share/jenkins/seed.groovy
2. seed job   →  clones this repo, runs Job DSL on jobs/*.groovy
3. jobs/*.groovy  →  creates all pipelineJob() definitions
4. Each pipelineJob  →  reads its Jenkinsfile from jobs/pipelines/<name>/Jenkinsfile
```

### Job DSL patterns

Each `jobs/*.groovy` file typically:
- Defines a `generate_jobs(data)` function that adds a config map to `jobsConfiguration`
- Calls `generate_jobs(…)` once per Ceph version (reef, squid, tentacle) with version-specific parameters
- Iterates `jobsConfiguration` to call `pipelineJob(name) { … }` for each entry

This means **adding a new Ceph version** requires only editing the relevant `jobs/*.groovy` file and re-running the seed — the Jenkinsfile itself requires no changes.

### Views

Dashboard views are declared in `views/infra_view.groovy` and group jobs by regex:

| View | Regex |
|---|---|
| Reef | `.*reef.*` |
| Squid | `.*squid.*` |
| Tentacle | `.*tentacle.*` |
| Executor | `.*executor.*` |
| Infrastructure | `.*(sync\|dns-cleanup\|cleanup_vms).*` |
| Reports | `jira-reporter-.*` |

---

## Related Documentation

- [cephci_conf / `.cephci.yaml` structure](secrets/cephci_conf.md)
- [ODF Interop Pipeline](jobs/odf_interop.md)
- [FDF Interop Pipeline](jobs/fdf_interop.md)
- [Sync Manifest Images](jobs/sync_manifest_images.md)
- [Cleanup Registry Images](jobs/cleanup_registry_images.md)
