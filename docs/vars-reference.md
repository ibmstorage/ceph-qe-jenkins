# Shared Library — `vars/` Reference

This document describes every custom step defined in the `vars/` directory of the `ceph-qe-ng/jenkins` shared library. Each entry covers the method's **purpose**, **parameters**, and **return value**.

---

## Table of Contents

1. [checkForNewBuilds](#checkfornewbuilds)
2. [checkImageInRegistry](#checkimageinregistry)
3. [cleanupCluster](#cleanupcluster)
4. [cleanupRegistryImages](#cleanupregistryimages)
5. [copyBlueCompose](#copybluecompose)
6. [execTestSuites](#exectestsuites)
7. [fetchCephImageMetadata](#fetchcephimagemetadata)
8. [fetchCephVersion](#fetchcephversion)
9. [fetchTestSuites](#fetchtestsuites)
10. [generateJobConfigs](#generatejobconfigs)
11. [generatePrefixString](#generateprefixstring)
12. [listRegistryImages](#listregistryimages)
13. [parseManifestImages](#parsemanifestimages)
14. [processCephBaseImage](#processcephbaseimage)
15. [publishTestResults](#publishtestresults)
16. [readFromManifest](#readfrommanifest)
17. [resolveOdfInteropParams](#resolveodfinteropparams)
18. [sendOdfInteropEmail](#sendodfinteropemail)
19. [setupCephCiRepo](#setupcephcirepo)
20. [syncBlueImages](#syncblueimages)
21. [syncBlueRepos](#syncbluerepos)
22. [syncBuildInfoToGithubManifest](#syncbuildinfotogithubmanifest)
23. [syncContainerImage](#synccontainerimage)
24. [syncManifestImages](#syncmanifestimages)
25. [syncMirrorOsRpms](#syncmirrososrpms)
26. [syncRepository](#syncrepository)
27. [triggerRegistryGarbageCollection](#triggerregistrygarbagecollection)
28. [triggerSpsBvtChecker](#triggerspsbvtchecker)
29. [updateBvtResults](#updatebvtresults)
30. [updateReportPortal](#updatereportportal)
31. [updateSanityResults](#updatesanityresults)
32. [validateKonfluxBuilds](#validatekonfluxbuilds)
33. [writeToManifest](#writetomanifest)

---

## checkForNewBuilds

**File:** [`vars/checkForNewBuilds.groovy`](../vars/checkForNewBuilds.groovy)

Compares the `nightly` and `stable` build versions recorded in the qe-ceph-manifest recipe file and determines whether the nightly build is newer. Pipelines use this to skip sanity runs when there is nothing new to test.

### Parameters

| Name | Type | Required | Description |
|---|---|---|---|
| `buildType` | String | ✔ | Build vendor — `IBM`, `RH`, or `upstream` |
| `rhBuild` | String | ✔ | Build version string, e.g. `20.1.0-101` |
| `job` | String | ✔ | Jenkins job name used to locate the manifest recipe |

### Return Value

`Boolean` — `true` when the nightly version is newer than stable (proceed with tests), `false` when both versions are identical (skip tests). Also returns `true` when no manifest or version information exists.

### Internal Helpers

| Helper | Signature | Purpose |
|---|---|---|
| `parseVersion` | `parseVersion(String version) → List<Integer>` | Splits a version string such as `20.1.0-101` into `[20, 1, 0, 101]` for numeric comparison |
| `isNewer` | `isNewer(String nightly, String stable) → Boolean` | Returns `true` when `nightly` is strictly greater than `stable` using element-wise integer comparison |

---

## checkImageInRegistry

**File:** [`vars/checkImageInRegistry.groovy`](../vars/checkImageInRegistry.groovy)

Verifies that a container image is accessible in a remote registry by running `skopeo inspect` against the supplied URL.

### Parameters

| Name | Type | Required | Description |
|---|---|---|---|
| `imageUrl` | String | ✔ | Fully qualified image reference, e.g. `registry.redhat.io/rhceph/rhceph-5-rhel8:5.3` |

### Return Value

`Boolean` — `true` if the image is present and reachable, `false` otherwise.

---

## cleanupCluster

**File:** [`vars/cleanupCluster.groovy`](../vars/cleanupCluster.groovy)

Destroys a Ceph test cluster and releases its cloud resources. Supports IBM Cloud (`ibmc`) and AWS (`aws`) providers.

### Parameters

| Name | Type | Required | Description |
|---|---|---|---|
| `gitDir` | String | ✔ | Path to the checked-out `ceph-ci` directory on the agent |
| `venvDir` | String | ✔ | Path to the Python virtual environment |
| `cloudType` | String | ✔ | Cloud provider — `ibmc` or `aws` |
| `instanceName` | String | ✔ | Name of the cluster instance to remove |
| `ibmcCredFile` | String | ✔ | Filesystem path to the cloud credentials file |
| `vpcName` | String | ✗ | VPC name (IBM Cloud only) |
| `logLevel` | String | ✗ | Logging verbosity, e.g. `DEBUG` |

### Return Value

None. Executes the cleanup command in the calling pipeline's workspace.

---

## cleanupRegistryImages

**File:** [`vars/cleanupRegistryImages.groovy`](../vars/cleanupRegistryImages.groovy)

Identifies container images in the local QE registry that are no longer referenced by any manifest entry and deletes them. Optionally triggers a garbage-collection pass afterwards.

### Parameters

| Name | Type | Required | Description |
|---|---|---|---|
| `dryRun` | Boolean | ✗ | When `true` (default), print what would be deleted without making changes |
| `forceCleanup` | Boolean | ✗ | Proceed with deletion even when more than 50 % of images are orphaned |
| `skipGC` | Boolean | ✗ | Skip the garbage-collection step after deletion |
| `registryUrl` | String | ✔ | Base URL of the Docker V2 registry, e.g. `http://registry.qe.ceph.lab:5000` |

### Return Value

None. May invoke [`triggerRegistryGarbageCollection`](#triggerregistrygarbagecollection) as a side-effect.

### Internal Helpers

| Helper | Purpose |
|---|---|
| `buildExpectedImageSet` | Builds the set of image references that should be retained according to the manifest |
| `mapManifestToLocalImage` | Translates a manifest image URL to its local registry equivalent |
| `determineTargetRepo` | Chooses the local repository path (`rh-stage` or `ibm-stage`) for a given image |
| `deleteRegistryImage` | Issues the HTTP DELETE request for a single image tag |

---

## copyBlueCompose

**File:** [`vars/copyBlueCompose.groovy`](../vars/copyBlueCompose.groovy)

Copies a compose tarball from Artifactory to the IBM Cloud local repository mirror via a remote SSH/Podman command.

### Parameters

| Name | Type | Required | Description |
|---|---|---|---|
| `composeName` | String | ✔ | Name of the compose to copy, e.g. `IBM-CEPH-9.0-20251112.ci.12345` |

### Return Value

None.

---

## execTestSuites

**File:** [`vars/execTestSuites.groovy`](../vars/execTestSuites.groovy)

Invokes a Ceph CI test suite inside a cloud environment and publishes the resulting `xunit.xml` file as a Jenkins test report.

Builds the `run.py` command line, including `--custom-config` entries from the job, stage metadata, and per-suite metadata (see below).

### Parameters

| Name | Type | Required | Description |
|---|---|---|---|
| `gitDir` | String | ✔ | Checked-out `ceph-ci` directory |
| `venvDir` | String | ✔ | Python virtual environment directory |
| `cloudType` | String | ✔ | Cloud provider — `ibmc` or `aws` |
| `instanceName` | String | ✔ | Target cluster instance name |
| `release` | String | ✔ | Build release string |
| `platform` | String | ✔ | OS platform — `rhel-8`, `rhel-9`, or `rhel-10` |
| `globalConf` | String | ✔ | Path to the global configuration YAML |
| `logDir` | String | ✔ | Directory where logs are written |
| `suite` | String | ✔ | Test suite file path to execute |
| `ibmcCredFile` | String | ✔ | Path to the cloud credentials file |
| `inventory` | String | ✔ | Ansible inventory file path |
| `containerImage` | String | ✔ | Ceph container image URL |
| `buildType` | String | ✔ | Build type — `IBM` or `RH` |
| `customConfig` | List | ✗ | Job-level `--custom-config` pairs (from `CUSTOM_CONFIG` and manifest images via `generateJobConfigs`) |
| `stageCustomConfig` | String | ✗ | Comma-separated `key=value` pairs from stage metadata (`metadata.<job>.metadata.custom-config` in YAML) |
| `suiteCustomConfig` | String | ✗ | Comma-separated `key=value` pairs from suite metadata (`custom-config` on the suite entry). Passed by metadata-driven pipelines (BVT, sanity, regression, etc.) |
| `enableFipsMode` | Boolean | ✗ | Enable FIPS via `--custom-config enable-fips-mode=True`. Used when `suiteCustomConfig` is **not** passed (e.g. test-executor) |
| `enableFirewall` | Boolean | ✗ | Enable firewall during tests |
| `manifestSection` | String | ✗ | Manifest release section passed to `run.py` as `--build` |
| Additional flags | — | ✗ | Other parameters (`rhsCephRepo`, `vmFlavor`, `vpcName`, etc.) passed through to `run.py` |

### Custom-config merge order

When building `run.py` arguments, `execTestSuites` merges custom-config in this order (each entry becomes `--custom-config key=value`):

1. `customConfig` — job/manifest entries (copied into a new list so parallel suite stages do not mutate a shared list)
2. `stageCustomConfig` — stage-level metadata
3. `suiteCustomConfig` — per-suite metadata (e.g. `enable-fips-mode=true` from `metadata/*/common.yaml`)

IBM/RH product flags and ibmc/aws profile settings are appended after this block.

### FIPS behavior

| Caller | `ENABLE_FIPS_MODE` / `enableFipsMode` | Per-suite metadata `enable-fips-mode` |
|---|---|---|
| Metadata-driven CI jobs (BVT, sanity, regression, etc.) | Not available — use per-suite metadata | Applied when set on that suite |
| Test executor (no `suiteCustomConfig`) | Applied when the job parameter is true | N/A — use `CUSTOM_CONFIG` or `ENABLE_FIPS_MODE` |

### Return Value

None. Publishes JUnit XML results via the `junit` step when `xunit.xml` is found.

---

## fetchCephImageMetadata

**File:** [`vars/fetchCephImageMetadata.groovy`](../vars/fetchCephImageMetadata.groovy)

Pulls a container image and runs `cephadm version --verbose` to extract human-readable version metadata.

### Parameters

| Name | Type | Required | Description |
|---|---|---|---|
| `image` | String | ✔ | Full image reference (tag or digest) |
| `pullSecretFile` | String | ✔ | Path to the registry authentication file |

### Return Value

`Map` with the following keys, or an empty map on failure:

| Key | Type | Example |
|---|---|---|
| `ceph_git_nice_ver` | String | `20.2.1` |
| `ceph_git_ver` | String | `abc1234...` (full SHA) |
| `ceph_release_name` | String | `tentacle` |

---

## fetchCephVersion

**File:** [`vars/fetchCephVersion.groovy`](../vars/fetchCephVersion.groovy)

Queries a Ceph RPM repository using a UBI container image to determine the exact package version available.

### Parameters

| Name | Type | Required | Description |
|---|---|---|---|
| `platform` | String | ✔ | RHEL version — `rhel-8`, `rhel-9`, or `rhel-10` |
| `rhsCephRepo` | String | ✔ | Repository URL to query |
| `buildType` | String | ✔ | Build type — `IBM`, `RH`, or `upstream` |

### Return Value

`String` — the version string, e.g. `20.1.0`.

---

## fetchTestSuites

**File:** [`vars/fetchTestSuites.groovy`](../vars/fetchTestSuites.groovy)

Calls the `ceph-ci` metadata Python script to discover which test suites should run for a given build, platform, and feature group.

### Parameters

| Name | Type | Required | Description |
|---|---|---|---|
| `rhBuild` | String | ✔ | Build version, e.g. `20.1.0-101` |
| `fg` | String | ✔ | Feature group name |
| `job` | String | ✔ | Job type, e.g. `sanity` |
| `platform` | String | ✔ | Target platform |
| `cloudType` | String | ✔ | Cloud provider — `ibmc` or `aws` |
| `buildType` | String | ✗ | Build type filter |
| `clusterConfig` | String | ✗ | Cluster topology configuration |
| `testTag` | String | ✗ | Comma-separated test tags to filter suites |
| `output` | String | ✗ | Path for the output JSON file (defaults to a temp path) |

### Return Value

`Map` — parsed JSON structure from the metadata script containing suite names and their execution configurations. Each suite entry includes `custom_config` (comma-separated `key=value` string from YAML `custom-config`) when defined; stage-level entries appear under `metadata.custom_config`.

---

## generateJobConfigs

**File:** [`vars/generateJobConfigs.groovy`](../vars/generateJobConfigs.groovy)

Assembles a complete job configuration map from Jenkins build parameters, manifest content, and hard-coded defaults. This is the central configuration step called by most pipeline `Jenkinsfile`s.

### Parameters

Reads the following Jenkins `params` and the `parameters` argument map:

| Source | Name | Description |
|---|---|---|
| `parameters` | `job` | Job type string (e.g. `sanity`, `regression`, `released`) |
| `params` | `PREFIX` | Mandatory unique resource prefix |
| `params` | `BUILD_TYPE` | Build vendor — `IBM`, `RH`, or `upstream` |
| `params` | `BUILD_VERSION` | Version string, e.g. `20.1.0-101` |
| `params` | `RHEL_VERSION` | Target RHEL platform |
| `params` | `RELEASE` | Manifest section — `nightly`, `stable`, `rc`, or `released` |
| `params` | `CLOUD_TYPE` | Cloud provider — `ibmc` (default) or `aws` |
| `params` | `CEPHCI_REPO` | Override for the `ceph-ci` git URL |
| `params` | `BRANCH` | Override for the `ceph-ci` git branch |
| `params` | `CONTAINER_IMAGE` | Override for the Ceph container image |
| `params` | `REPO` | Override for the Ceph RPM repository URL |
| `params` | `INVENTORY` | Override for the Ansible inventory file |
| `params` | `CUSTOM_CONFIG` | Comma-separated extra `--custom-config` pairs |
| `params` | `ADDITIONAL_ARGS` | Comma-separated extra CLI arguments |
| `params` | `FG` | Feature group (regression/extended jobs) |
| `params` | `SUITE` | Test suite path (MTE jobs) |
| `params` | `ENABLE_FIPS_MODE` | Boolean — enable FIPS on **test-executor** only; metadata CI jobs (BVT, sanity, regression, etc.) do not expose this parameter — FIPS comes from per-suite metadata |
| `params` | `ENABLE_FIREWALL` | Boolean — enable firewall |
| `params` | `ENABLE_REPORT_PORTAL` | Boolean — post results to Report Portal |
| `params` | `ENABLE_GSHEET` | Boolean — post results to Google Sheets |
| `params` | `SKIP_SUBSCRIPTION` | Boolean — skip RHSM subscription step |
| `params` | `SKIP_SOS_REPORT` | Boolean — skip SOS report collection |
| `params` | `VPC_NAME` | IBM Cloud VPC name override |
| `params` | `VM_FLAVOR` | VM instance flavor override |
| `params` | `LOG_LEVEL` | Logging level |
| `params` | `HOTFIX_REPO` | Additional hotfix repository URL |
| `params` | `TAG` | Report Portal launch tag |

### Return Value

`Map` (`jobConfig`) — a flat configuration map passed to subsequent pipeline steps. Key fields include:

| Key | Description |
|---|---|
| `job` | Job type |
| `prefix` | Resource name prefix |
| `buildType` | Resolved build vendor |
| `rhBuild` / `release` | Build version |
| `platform` | RHEL version string |
| `cloudType` | Cloud provider |
| `containerImage` | Resolved Ceph container image URL |
| `rhsCephRepo` | Resolved Ceph RPM repository URL |
| `cephVersion` | Exact Ceph package version from the manifest |
| `gitUrl` / `gitBranch` / `gitDir` | `ceph-ci` repository settings |
| `venvDir` | Virtual environment path |
| `logDir` | Log output directory |
| `jobDir` | Composite job working directory |
| `resultsDir` / `resultsSummary` / `artifacts` / `emailBody` | Results artefact paths |
| `ibmcCredFile` / `credentialId` | Cloud credential file and Jenkins credential ID |
| `inventory` | Ansible inventory file path |
| `vpcName` | VPC name (IBM Cloud) or `null` (AWS) |
| `customConfig` | List of `key=value` strings |
| `rpLaunchName` / `rpLaunchDesc` / `rpLaunchAttributes` / `rpProjectName` / `rpLaunchUrl` | Report Portal launch metadata |
| `enableReportPortal` / `enableGoogleSheet` | Feature toggles |

---

## generatePrefixString

**File:** [`vars/generatePrefixString.groovy`](../vars/generatePrefixString.groovy)

Generates a short random lowercase alphanumeric string suitable for use as a unique resource-name prefix.

### Parameters

None.

### Return Value

`String` — a random lowercase alphanumeric prefix.

---

## listRegistryImages

**File:** [`vars/listRegistryImages.groovy`](../vars/listRegistryImages.groovy)

Queries the Docker Registry V2 API to enumerate all image tags in one or more repositories.

### Parameters

| Name | Type | Required | Description |
|---|---|---|---|
| `registryUrl` | String | ✗ | Base registry URL (default: `http://registry.qe.ceph.lab:5000`) |
| `repositories` | List | ✗ | Repository prefixes to list (default: `["rh-stage", "ibm-stage"]`) |

### Return Value

`Map` — repository prefix strings as keys, each mapped to a `List<String>` of fully qualified image URLs.

---

## parseManifestImages

**File:** [`vars/parseManifestImages.groovy`](../vars/parseManifestImages.groovy)

Reads the qe-ceph-manifest YAML files for a given vendor and release version and returns every referenced container image, grouped by manifest section.

### Parameters

| Name | Type | Required | Description |
|---|---|---|---|
| `manifestDir` | String | ✔ | Path to the checked-out manifest directory |
| `vendor` | String | ✔ | Vendor identifier — `ibm` or `redhat` |
| `releaseVersion` | String | ✔ | Release version string, e.g. `20.1.0` |

### Return Value

`Map` — manifest section names (`z0`, `rc`, `nightly`, `released`) as keys, each mapped to a `List<String>` of image URLs.

---

## processCephBaseImage

**File:** [`vars/processCephBaseImage.groovy`](../vars/processCephBaseImage.groovy)

Pulls a Ceph container image and inspects the `ceph-common` RPM inside it to derive the vendor, version components, Ceph release name, and OS platform. Supports both IBM (`cp.icr.io`, `cp.stg.icr.io`, `preprod.icr.io`) and Red Hat (`quay.io/rhceph-ci`, `rh-osbs`) registries. Authentication is handled via the shared `all-cr-auth` credential (same authfile used by `syncBlueImages` and `checkImageInRegistry`).

### Parameters

| Name | Type | Required | Description |
|---|---|---|---|
| `image` | String | ✔ | Fully qualified container image reference (IBM or RH registry) |

### Return Value

`Map` with the following keys:

| Key | Type | Description |
|---|---|---|
| `vendor` | String | `ibm` for IBM images, `redhat` for RH images |
| `buildType` | String | `IBM` or `RH` |
| `cephVersion` | String | Ceph package version stripped of OS suffix, e.g. `19.2.1-405` |
| `releaseVersion` | String | Product release version, e.g. `8.1` |
| `release` | String | Ceph release code name, e.g. `squid` |
| `osPlatform` | String | Target OS platform, e.g. `rhel-9` |

---

## publishTestResults

**File:** [`vars/publishTestResults.groovy`](../vars/publishTestResults.groovy)

Aggregates and distributes test results at the end of a pipeline run. Uploads logs to IBM Cloud Object Storage, optionally posts to Report Portal and Google Sheets, and sends an HTML summary email.

### Parameters

| Name | Type | Required | Description |
|---|---|---|---|
| `buildType` | String | ✔ | Build vendor |
| `rhBuild` | String | ✔ | Build version |
| `platform` | String | ✔ | Target platform |
| `cloudType` | String | ✔ | Cloud provider |
| `emailAddr` | String | ✔ | Recipient email address(es) |
| `logDir` | String | ✔ | Directory containing job logs |
| `resultsDir` | String | ✔ | Directory containing result files |
| `resultsSummary` | String | ✔ | Path to the results JSON summary file |
| `artifacts` | String | ✔ | Path to artefacts directory |
| `emailBody` | String | ✔ | Path to the rendered HTML email body |
| `rhsCephRepo` | String | ✔ | Ceph RPM repository URL |
| `containerImage` | String | ✔ | Ceph container image URL |
| `cephVersion` | String | ✔ | Exact Ceph version string |
| `rpLaunchName` | String | ✔ | Report Portal launch name |
| `rpLaunchDesc` | String | ✔ | Report Portal launch description |
| `rpLaunchAttributes` | String | ✔ | Report Portal launch attributes JSON |
| `rpProjectName` | String | ✔ | Report Portal project name |
| `enableReportPortal` | Boolean | ✗ | Post results to Report Portal (default: `false`) |
| `enableGoogleSheet` | Boolean | ✗ | Post results to Google Sheets (default: `false`) |
| `triggerSPS` | Boolean | ✗ | Trigger the SPS BVT checker (default: `false`) |

### Return Value

None.

---

## readFromManifest

**File:** [`vars/readFromManifest.groovy`](../vars/readFromManifest.groovy)

Reads and parses a YAML recipe file from the `qe-ceph-manifest` GitHub repository.

### Parameters

| Name | Type | Required | Description |
|---|---|---|---|
| `recipe` | String | ✔ | Relative path to the recipe file, e.g. `ibm/20.1.0.yaml` |
| `job` | String | ✔ (or `jobDir`) | Job name used to derive the manifest working directory |
| `jobDir` | String | ✔ (or `job`) | Explicit manifest working directory path |

### Return Value

`Map` — parsed YAML content of the recipe file, or an empty map if the file does not exist.

---

## resolveOdfInteropParams

**File:** [`vars/resolveOdfInteropParams.groovy`](../vars/resolveOdfInteropParams.groovy)

Resolves the OCP version, OCP channel, and ODF channel required for an ODF interoperability test run. Supports two modes: **BVT-style** (stream extracted from `JOB_NAME`) and **inferAuto** (stream inferred from `RHCS_BUILD_VERSION` or by inspecting the Ceph container image).

> Only the `squid` and `tentacle` Ceph streams are currently in the interop matrix. The method returns `null` for unsupported streams so the caller can exit gracefully.

### Parameters

| Name | Type | Required | Description |
|---|---|---|---|
| `inferAuto` | Boolean | ✗ | When `true`, infer parameters automatically (see below) |
| `cephImage` | String | ✗ (`inferAuto` only) | Ceph container image used to extract the version |
| `pullSecretFile` | String | ✗ (`inferAuto` only) | Registry auth file — required when `RHCS_BUILD_VERSION` is absent |
| `rhcsBuildVersion` | String | ✗ (`inferAuto` only) | RHCS version override, e.g. `9.1` |
| `jobName` | String | ✗ (BVT mode) | Jenkins job name — falls back to `env.JOB_NAME`. Must match `<stream>-bvt-ci` |
| `buildVersion` | String | ✗ (BVT mode) | Build version — falls back to `env.BUILD_VERSION` |
| `ocpVersionOverride` | String | ✗ | Override the OCP version from the matrix |
| `ocpChannelOverride` | String | ✗ | Override the OCP channel (`released` or `nightly`) |
| `odfChannelOverride` | String | ✗ | Override the ODF channel |

### Return Value

`Map` with the following keys, or `null` when the Ceph stream is not in the matrix:

| Key | Type | Example |
|---|---|---|
| `cephStream` | String | `tentacle` |
| `matrixKey` | String | `9.2` |
| `ocpVersion` | String | `5.0.0-0.nightly-2026-09-13-163645` |
| `ocpChannel` | String | `released` |
| `odfChannel` | String | `stable-5.0` |
| `ocsVersion` | String | `5.0` |

### Internal Helpers

| Helper | Purpose |
|---|---|
| `inferAuto(Map p)` | Resolves parameters from `RHCS_BUILD_VERSION` or by pulling the Ceph image |
| `parseLeadingMajor(String s, String label)` | Extracts the leading integer from a version string |
| `rhcsMajorToStream(int maj)` | Maps RHCS major version to Ceph stream: `8 → squid`, `9 → tentacle` |
| `rhcsToInteropMinor()` | Maps RHCS to aligned OCP/ODF minor (`9.2` → `5.0`) |
| `lookupRhcsInteropMinor(String rhcsVer)` | Matrix lookup or `null` |
| `upstreamCephVersionMap()` | Maps upstream Ceph (`20.2.2`) to RHCS (`9.2`) |
| `extractCephStream(String jobName)` | Extracts `<stream>` from a job name matching `<stream>-bvt-ci` |

---

## sendOdfInteropEmail

**File:** [`vars/sendOdfInteropEmail.groovy`](../vars/sendOdfInteropEmail.groovy)

Composes and delivers an HTML summary email for an ODF interoperability test run.

### Parameters

| Name | Type | Required | Description |
|---|---|---|---|
| `clusterName` | String | ✔ | Name of the deployed OCP cluster |
| `hasCluster` | Boolean | ✔ | Whether a cluster was successfully provisioned |
| `testSummary` | Map | ✗ | Test metrics with keys `total`, `pass`, `fail`, `skip` |
| `cosResultsUrl` | String | ✗ | IBM Cloud Object Storage URL for the full log archive |

### Return Value

None. Sends an email via the Jenkins `mail` step.

### Internal Helpers

| Helper | Purpose |
|---|---|
| `readCephMetadata()` | Reads Ceph version details from the workspace |
| `buildCephSubjectToken()` | Builds the version token used in the email subject line |
| `buildTestCaseRows()` | Generates the HTML table rows for individual test results |

---

## setupCephCiRepo

**File:** [`vars/setupCephCiRepo.groovy`](../vars/setupCephCiRepo.groovy)

Clones the `ceph-ci` repository, creates a Python virtual environment, installs dependencies, and writes the cloud credentials file.

### Parameters

| Name | Type | Required | Description |
|---|---|---|---|
| `python` | String | ✔ | Python binary to use, e.g. `python3.11` |
| `venvDir` | String | ✔ | Virtual environment directory path |
| `gitUrl` | String | ✔ | `ceph-ci` repository URL |
| `gitDir` | String | ✔ | Target directory for the git clone |
| `gitBranch` | String | ✔ | Branch to check out |
| `ibmcCredFile` | String | ✔ | Destination path for the cloud credentials file |
| `cloudType` | String | ✔ | Cloud provider — `ibmc` or `aws` |
| `credentialId` | String | ✗ | Jenkins credential ID (defaults to `osp-cred`) |

### Return Value

None.

---

## syncBlueImages

**File:** [`vars/syncBlueImages.groovy`](../vars/syncBlueImages.groovy)

Copies a set of container images from external registries to the local QE registry using `skopeo copy`.

### Parameters

| Name | Type | Required | Description |
|---|---|---|---|
| `images` | String | ✔ | Newline-separated list of fully qualified image URLs to synchronise |

### Return Value

None.

---

## syncBlueRepos

**File:** [`vars/syncBlueRepos.groovy`](../vars/syncBlueRepos.groovy)

Mirrors Ceph RPM repositories to the IBM Cloud nginx repository mirror over SSH.

### Parameters

| Name | Type | Required | Description |
|---|---|---|---|
| `repos` | String | ✔ | Newline-separated list of repository URLs to synchronise |

### Return Value

None.

---

## syncBuildInfoToGithubManifest

**File:** [`vars/syncBuildInfoToGithubManifest.groovy`](../vars/syncBuildInfoToGithubManifest.groovy)

Writes IBM build metadata — container images, repository URLs, and version strings — into the `qe-ceph-manifest` GitHub repository and commits the changes.

### Parameters

| Name | Type | Required | Description |
|---|---|---|---|
| `cephVersion` | String | ✔ | Ceph package version, e.g. `20.1.0` |
| `cephBuildVersion` | String | ✔ | Full build version string, e.g. `20.1.0-188.el9cp` |
| `images` | String | ✔ | Newline-separated container image URLs |
| `repos` | String | ✔ | Newline-separated repository URLs |
| `releaseCandidate` | Boolean | ✗ | When `true`, also copy the build info to the `rc` manifest section |

### Return Value

None.

---

## syncContainerImage

**File:** [`vars/syncContainerImage.groovy`](../vars/syncContainerImage.groovy)

Copies a single container image to the IBM nginx registry using `skopeo copy` and validates the copy with a `curl` tag-list check.

### Parameters

| Name | Type | Required | Description |
|---|---|---|---|
| `containerImage` | String | ✔ | Fully qualified source image URL |

### Return Value

None.

---

## syncManifestImages

**File:** [`vars/syncManifestImages.groovy`](../vars/syncManifestImages.groovy)

Iterates over images listed in the qe-ceph-manifest and synchronises any that are absent from the local registry. Delegates to [`syncBlueImages`](#syncblueimages) for the actual transfer.

### Parameters

| Name | Type | Required | Description |
|---|---|---|---|
| `vendor` | String | ✔ | Vendor — `ibm` or `redhat` |
| `manifestBranch` | String | ✗ | Manifest repository branch (default: `main`) |
| `releaseVersion` | String | ✗ | Limit sync to a specific release version |
| `dryRun` | Boolean | ✗ | Print actions without executing (default: `false`) |

### Return Value

None.

### Internal Helpers

| Helper | Purpose |
|---|---|
| `determineTargetRepo(String image)` | Chooses `rh-stage` or `ibm-stage` based on the image source registry |

---

## syncMirrorOsRpms

**File:** [`vars/syncMirrorOsRpms.groovy`](../vars/syncMirrorOsRpms.groovy)

Synchronises RHEL 8, 9, and 10 OS RPMs from the Red Hat CDN to the internal IBM mirror over SSH.

### Parameters

None (accepts an empty map for API consistency).

### Return Value

None.

---

## syncRepository

**File:** [`vars/syncRepository.groovy`](../vars/syncRepository.groovy)

Copies a Ceph RPM repository from IBM Cloud Object Storage to the nginx mirror and regenerates the repository metadata with `createrepo`.

### Parameters

| Name | Type | Required | Description |
|---|---|---|---|
| `buildVersion` | String | ✔ | Product build version, e.g. `9.0` |
| `platform` | String | ✔ | Target platform — `rhel-8`, `rhel-9`, or `rhel-10` |
| `cephVersion` | String | ✔ | Ceph package version, e.g. `20.1.0` |

### Return Value

None.

---

## triggerRegistryGarbageCollection

**File:** [`vars/triggerRegistryGarbageCollection.groovy`](../vars/triggerRegistryGarbageCollection.groovy)

Invokes Docker registry garbage collection on the nginx server. In dry-run mode the command is printed but not executed.

### Parameters

| Name | Type | Required | Description |
|---|---|---|---|
| `registryHost` | String | ✗ | Registry hostname (defaults to the configured QE registry host) |
| `dryRun` | Boolean | ✗ | Simulate execution without actually running GC (default: `false`) |

### Return Value

None.

---

## triggerSpsBvtChecker

**File:** [`vars/triggerSpsBvtChecker.groovy`](../vars/triggerSpsBvtChecker.groovy)

Submits Ceph BVT test results to the IBM Secure Pipeline Services (SPS) for validation by the Ceph build team.

### Parameters

| Name | Type | Required | Description |
|---|---|---|---|
| `wrkspace` | String | ✔ | Path to the Jenkins workspace directory |
| `results` | String | ✔ | JSON string containing test results |
| `product` | String | ✔ | Product identifier — `IBM` or `RH` |
| `cephVersion` | String | ✔ | Ceph version being validated |

### Return Value

None.

---

## updateBvtResults

**File:** [`vars/updateBvtResults.groovy`](../vars/updateBvtResults.groovy)

Reads the BVT results summary and, if the overall status is `PASS`, writes the build information to the manifest via [`writeToManifest`](#writetomanifest).

### Parameters

| Name | Type | Required | Description |
|---|---|---|---|
| `resultsSummary` | String | ✔ | Path to the JSON results summary file |
| `job` | String | ✔ | Job name |
| `buildType` | String | ✔ | Build vendor |
| `rhBuild` | String | ✔ | Build version |
| `platform` | String | ✔ | Target platform |
| `cephVersion` | String | ✔ | Ceph version |
| `rhsCephRepo` | String | ✔ | Ceph RPM repository URL |
| `containerImage` | String | ✔ | Ceph container image URL |
| `customImages` | Map | ✗ | Additional image key-value pairs to record in the manifest |

### Return Value

None.

---

## updateReportPortal

**File:** [`vars/updateReportPortal.groovy`](../vars/updateReportPortal.groovy)

Posts test results to a Report Portal instance using the `data-router` container.

### Parameters

| Name | Type | Required | Description |
|---|---|---|---|
| `resultsDir` | String | ✔ | Directory containing xUnit XML result files |
| `rpLaunchName` | String | ✔ | Launch name displayed in Report Portal |
| `rpLaunchDesc` | String | ✔ | Launch description |
| `rpLaunchAttributes` | String | ✔ | JSON array of launch attribute objects |
| `rpProjectName` | String | ✔ | Target Report Portal project name |
| `rpHostname` | String | ✗ | Report Portal hostname (uses default if omitted) |
| `rpHostUrl` | String | ✗ | Full Report Portal host URL override |
| `rpConfig` | String | ✗ | Path to an alternative Report Portal config file |

### Return Value

None.

---

## updateSanityResults

**File:** [`vars/updateSanityResults.groovy`](../vars/updateSanityResults.groovy)

Reads the sanity suite results summary and, when **all** suites report `PASS` and `manifestSection` is `nightly`, promotes the full `nightlySection` snapshot to the manifest `stable` section via [`writeToManifest`](#writetomanifest).

### Parameters

| Name | Type | Required | Description |
|---|---|---|---|
| `resultsSummary` | String | ✔* | Path to the JSON results summary file (`results.json`) written by `publishTestResults` |
| `manifestSection` | String | ✔ | Manifest section under test (`params.RELEASE`, e.g. `nightly` or `stable`). Non-`nightly` values skip promotion |
| `nightlySection` | Map | ✔* | Snapshot of the manifest `nightly` section from `generateJobConfigs` |
| `job` | String | ✔* | Job name |
| `buildType` | String | ✔* | Build vendor |
| `rhBuild` | String | ✔* | Build version |

\* Required only when promoting (`manifestSection` is `nightly`).

### Return Value

None.

---

## validateKonfluxBuilds

**File:** [`vars/validateKonfluxBuilds.groovy`](../vars/validateKonfluxBuilds.groovy)

Parses and validates the JSON message emitted by a Konflux build pipeline, extracting structured metadata for downstream pipeline steps.

### Parameters

| Name | Type | Required | Description |
|---|---|---|---|
| `buildMessage` | String | ✔ | Raw JSON build message received from Konflux |

### Return Value

`Map` with the following keys:

| Key | Type | Description |
|---|---|---|
| `application` | String | Konflux application name |
| `type` | String | Build vendor — `ibm` or `redhat` |
| `buildVersion` | String | Product build version |
| `recipe` | String | Manifest recipe path |
| `platform` | String | Target OS platform |
| `images` | List | Container image URLs produced by the build |
| `repositories` | List | RPM repository URLs produced by the build |
| `version` | String | Ceph package version |

---

## writeToManifest

**File:** [`vars/writeToManifest.groovy`](../vars/writeToManifest.groovy)

Updates a YAML recipe file in `qe-ceph-manifest` and pushes the commit to GitHub. Supports either a full manifest-section replace (`sectionContent`) or legacy field-by-field updates (`buildInfo`).

### Parameters

| Name | Type | Required | Description |
|---|---|---|---|
| `buildTag` | String | ✔ | Manifest section to update — `stable`, `nightly`, or `rc` |
| `job` | String | ✔ (or `jobDir`) | Job name used to derive the manifest working directory |
| `jobDir` | String | ✔ (or `job`) | Explicit manifest working directory path |
| `recipe` | String | ✗ | Recipe file path (preferred over `buildInfo.recipe`) |
| `sectionContent` | Map | ✗ | Full manifest section to replace `buildTag` with (sanity promotion) |
| `sectionSource` | String | ✗ | Source label for the commit message when using `sectionContent` |
| `buildInfo` | Map | ✗ | Legacy build details — `recipe`, `version`, `repositories`, and `images` |

Either `sectionContent` or a non-empty `buildInfo.images` map must be provided.

### Return Value

None.

---

*For the overall pipeline architecture and how these steps are composed into full pipelines, see [`docs/architecture.md`](architecture.md).*
