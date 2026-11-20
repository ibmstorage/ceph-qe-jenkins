# ODF Chaos Jenkins Pipeline

Left-shift Krkn chaos on an IBM Cloud OCP + ODF cluster with an internal Ceph storage cluster. The job deploys the same way as [ODF Interop](./odf_interop.md), patches the operator CSV with `CEPH_IMAGE`, then runs the full ocs-ci `@chaos` suite instead of FIO and acceptance.

`odf-interop` and `fdf-interop` are not part of this job. They keep calling `runInteropPostActions` / `sendInteropEmail`. This pipeline uses `runOdfChaosPostActions` and `sendOdfChaosEmail`.

## Trigger

Manual **Build with Parameters** only. There is no cron and no BVT downstream trigger.

After merge to `main`, re-run the seed job so `jobs/odf_chaos.groovy` creates the `odf-chaos` job.

## Workflow

| Stage | What it does |
|---|---|
| Initialize / Validate | Same RHCS → OCP/ODF matrix as odf-interop (`validateInteropJob` product `odf`) |
| Clone / setup ceph-interop | Python 3.12 venv, `pip install -e .` |
| Ceph image metadata | `fetchCephImageMetadata` when `CEPH_IMAGE` is set |
| Deploy OCP + ODF | `ceph-interop deploy` on IBM Cloud VPC; CSV `CEPH_IMAGE` patch |
| Clone / setup ocs-ci | Python 3.11 venv |
| Prepare ocs-ci Config | Platform YAML + VDBENCH override + separate AUTH (see below) |
| Chaos - Container | `test_krkn_container_chaos.py` |
| Chaos - Application Outage | enabled (moderate) |
| Chaos - Network | enabled (moderate) |
| Chaos - Node | enabled (IBM Cloud stop/start; needs AUTH + region) |
| Chaos - NooBaa | enabled (MCG conf; no VDBENCH override) |
| Chaos - Hog | still off — includes MAXIMUM CPU/RAM stress |
| Chaos - Random | still off — needs podman/docker + krknctl on the agent |
| Post | Teardown on SUCCESS only (retain on failure), archive, COS, email |

A failing chaos stage is marked failed and the build result becomes `FAILURE`, but later chaos stages still run (`catchError`). Teardown runs in `post` only when the build result is `SUCCESS`; on `FAILURE` / `UNSTABLE` / `ABORTED` the cluster is retained for live debug (artifacts and email still run).

Default `ENV_NAME` is `odf-chaos-<BUILD_NUMBER>`. Timeout is 48 hours, same as odf-interop.

Empty `OCS_CI_TESTS` adds `-k 'not high-12pods'` so random pod-kill stays at BASIC and STRENGTH (`basic` / `moderate`). Set `OCS_CI_TESTS=ALL` to drop that filter. Hog still runs its unified BASIC + STRENGTH + MAXIMUM case (CPU/RAM, not extra OSD capacity). Container kill-signal tests are a single unified run, not three YAML stress levels.

## Chaos test list

All modules live under `tests/cross_functional/krkn_chaos/` in ocs-ci and are marked `@chaos`.

| Email / artifact id | Modules | ocs-ci conf stack |
|---|---|---|
| `container` | `test_krkn_container_chaos.py` | `krkn_chaos_config.yaml` + platform + VDBENCH + AUTH |
| `app-outage` | `test_krkn_application_outage_scenarios.py` | same |
| `network` | `test_krkn_network_chaos_scenarios.py` | same |
| `hog` | `test_krkn_hog_scenarios.py` | same |
| `node` | `test_krkn_node_scenarios.py` | same |
| `noobaa` | `test_krkn_noobaa_chaos.py`, `test_krkn_noobaa_container_chaos.py`, `test_krkn_noobaa_node_disruption.py` | `krkn_noobaa_chaos_config.yaml` + platform + AUTH (**no** VDBENCH) |
| `random` | `test_random_chaos.py` | `krkn_chaos_config.yaml` + platform + VDBENCH + AUTH |

There is **no per-suite runtime YAML** for Container / Network / Hog / Node / Random — they share the same IBM platform + VDBENCH sizing. Only NooBaa skips the VDBENCH file so `MCG_WORKLOAD` is not overwritten (`applyRuntimeOverride: false`).

`OCS_CI_TESTS` is optional extra pytest args applied on every stage. It does not replace the module paths. Empty applies `-k 'not high-12pods'`. `ALL` runs every collected case, including `high-12pods`. A value that already contains `-k` replaces the default filter.

Each stage writes:

```
ocs-ci/ocs-ci-logs-<BUILD>/chaos-<suite>-junit-results.xml
ocs-ci/ocs-ci-logs-<BUILD>/chaos-<suite>-report.html
```

Chaos `run-ci` uses `PYTHONUNBUFFERED=1`, `-vvv`, `--log-level DEBUG`, and `--collect-logs` for live verbose console output.

Krkn telemetry archives (`events.json`) go under ocs-ci `data/krkn_telemetry` (not `/tmp`), via `KrknConfigGenerator` defaults in the ocs-ci branch used by the job — not a `run-ci` argument.

## Prepare ocs-ci Config files

Written under `ocs-ci/` before chaos stages:

| File | Contents | Archived? |
|---|---|---|
| `chaos-ibmcloud-platform-config.yaml` | `RUN`, `DEPLOYMENT`, `ENV_DATA` (`platform: ibm_cloud`, region, skip_*), `UI_SELENIUM` | Yes |
| `chaos-vdbench-runtime-config.yaml` | `ENV_DATA.krkn_config` VDBENCH + bgops (snap/clone kept, interval 120s, verify threads 4) for ~200Gi usable | Yes |
| `auth-ibmcloud.yaml` | `AUTH.ibmcloud` api_key + account_id only (`chmod 600`) | **Never** (deleted in post) |

Conf merge order in `runOdfChaosSuite` (later wins):

```text
--ocsci-conf <suite base: krkn_chaos_config.yaml | krkn_noobaa_chaos_config.yaml>
--ocsci-conf chaos-ibmcloud-platform-config.yaml
--ocsci-conf chaos-vdbench-runtime-config.yaml   # if applyRuntimeOverride
--ocsci-conf auth-ibmcloud.yaml
```

No baremetal deployment conf is layered in. Node chaos uses `platform: ibm_cloud` from the platform file. Do not set `AUTH.ibmcloud.api_endpoint` to the VPC Gen2 URL; Krkn builds `IBMC_URL` from region.

## How reports reach the email

`runOdfChaosPostActions`:

1. Tears down the IBM Cloud cluster (`ceph-interop teardown --level all`) **only on SUCCESS**. On failure the cluster is kept and the log names the retained `ENV_NAME` / cluster id.
2. Archives cluster artifacts, secret-free runtime YAMLs, and the ocs-ci log directory (JUnit and HTML included). Runtime configs are also copied into the logs tarball for COS.
3. Publishes each `chaos-<suite>-junit-results.xml` with the Jenkins `junit` step for the UI, then **parses the XML** for email counts so pytest `<failure>` and `<error>` stay distinct (Jenkins `failCount` folds errors into failures).
4. Uploads `cluster-artifacts-*.tar.gz` and `ocs-ci-logs-*.tar.gz` to COS at `odf-chaos/<BUILD_NUMBER>/`.
5. Calls `sendOdfChaosEmail`.
6. Deletes `auth-ibmcloud.yaml` and other runtime / pull-secret files.

The email has a **Test case execution** row for OCP + ODF deploy (no FIO row) and an **OCS-CI Chaos Tests** section with one row per suite:

- Counts when JUnit was published: **Passed / Failed / Errors / Skipped / Total**
- `Not run` when that stage never started
- `Failed` when the stage ran but did not produce counts

JUnit files that exist are attached. If any suite failed with assertion failures, the body includes an action-required note about the patched Ceph image. If the only problems are pytest **errors** (for example PVC teardown timeouts), the note asks to review teardown messages before blaming the image. Subject shape: `Ceph-ODF Chaos Report: <STATUS> (...)`.

## Dependencies

Already on `agents-interop` (same as odf-interop): Python 3.11 and 3.12, git, IBM Cloud and COS credentials, ocs pull-secret, AWS keys for Route53.

Chaos-specific:

| Dependency | Why |
|---|---|
| Clone `https://github.com/redhat-chaos/krkn.git` | `scripts/setup_krkn.sh` during the `krkn_setup` fixture (separate venv under `ocs-ci/data/krkn`) |
| Download krknctl from `krkn-chaos.gateway.scarf.sh` | Random stage |
| `podman` or `docker` on the agent | `krknctl_setup`; Random fails and later stages are already done if this is missing |
| Cluster pull of `quay.io/pakamble/vdbench:latest` | VDBENCH workload during chaos |
| Healthy internal ODF (RBD + CephFS) | All non-NooBaa stages |
| NooBaa / MCG | NooBaa stage |
| IBM API key + account id + `ENV_DATA.region` | Node stop/start/reboot. Do not set `AUTH.ibmcloud.api_endpoint` to the VPC URL; `ibmcloud login -a` needs the cloud endpoint (CLI default). Krkn builds `IBMC_URL` from the region |

Not required: Chrome for chaos itself, CNV, KMS, on-prem RGW, Warp, encrypted PVCs.

## Cluster vs upstream Krkn config

Deploy context is `ocp-ibm-vpc-odf-chaos.yaml`: IBM VPC, 3 masters, 3 workers, internal StorageCluster, profiles `odf-1x200gi` / `osd-1x200gb`.

`DEVICE_SIZE` / `DEVICE_COUNT` default to `200` / `1`. The config step passes `deviceSetCount: 1` and `replica: 3`: 3 OSD PVCs, 600Gi raw, about 200Gi usable. `odf-interop` still uses `odf-3x50gi`. `CEPH_IMAGE` is patched onto the ODF CSV during deploy.

Stages other than NooBaa load ocs-ci `conf/ocsci/krkn_chaos_config.yaml` and deep-merge `chaos-vdbench-runtime-config.yaml`. Only fields that do not fit this build are overridden. Patterns, `krkn_version`, and unused RGW/MCG/Warp blocks stay in the base file.

| Upstream `krkn_chaos_config.yaml` | This pipeline |
|---|---|
| `workloads`: VDBENCH + RGW | `VDBENCH` only. NooBaa stage uses `krkn_noobaa_chaos_config.yaml` (`MCG_WORKLOAD`) |
| VDBENCH 5 + 5 × 50Gi, `block.size` 15g, loop 20 | 2 + 2 × 10Gi, `block.size` 5g, threads 8, elapsed 300, loop 5 |
| Background ops include aggressive clone/snapshot and longevity | snapshot + clone lifecycle kept (lighter path); interval **120**s; concurrent ops **2**; no aggressive/longevity. `max_verification_threads` **4** |
| IBM node URL often set as `api_endpoint` | `api_key` + `account_id` + `region` only |

Workload PVCs are deleted after each test (`pvc_factory` finalizer). PVs follow the StorageClass reclaim policy. That frees space between stages; 200Gi usable covers the mid-test peak of VDBENCH plus light snapshot/clone.

There is no `chaos_level` key in the upstream conf. BASIC / STRENGTH / MAXIMUM in the container module docstring are not a config switch.

ocs-ci does not ship a Jenkins job for chaos in this workspace. The tests are white-squad (`@chaos`) and the node scenarios know AWS, Azure, IBM Cloud, vSphere, and baremetal. Pod, network, and hog scenarios are platform-agnostic on a healthy internal ODF cluster.

## Parameters

Same matrix inputs as odf-interop: `CEPH_IMAGE`, `RHCS_BUILD_VERSION`, `OCP_CHANNEL`, `OCP_VERSION`, `ODF_CHANNEL`, `ODF_CATALOG_IMAGE`, `ODF_OPERATOR_CHANNEL`, IBM region/VPC, master/worker counts, device size/count, ocs-ci repo/branch/version, `EMAIL_ADDRESS`.

Defaults match odf-interop SCM: Jenkinsfile from `ibmstorage/ceph-qe-jenkins` `@main` via `gh-ceph-qe-sa-pat`, shared library `ceph-qe-libs@main`, and `CEPH_INTEROP_REPO` / `CEPH_INTEROP_BRANCH` → `ibmstorage/ceph-qe-interop` `main` (includes `ocp-ibm-vpc-odf-chaos.yaml`).

There is no `PVC_COUNT` (no FIO stage). `DEVICE_SIZE` defaults to `200`. `OCS_CI_TESTS` defaults to empty, which still applies the high-pod-kill filter described above.

## Files

| Path | Role |
|---|---|
| `jobs/odf_chaos.groovy` | Job DSL |
| `jobs/pipelines/odf_chaos/Jenkinsfile` | Pipeline |
| `vars/runOdfChaosPostActions.groovy` | Teardown on SUCCESS only (retain cluster on failure), archive (no AUTH), junit + XML parse, COS, email |
| `vars/sendOdfChaosEmail.groovy` | Per-suite HTML email (Passed/Failed/Errors/Skipped) |

## Related

- [ODF Interop Pipeline](./odf_interop.md) — acceptance + FIO, BVT-triggered
- [FDF Interop Pipeline](./fdf_interop.md) — FDF deploy + FIO, no ocs-ci
