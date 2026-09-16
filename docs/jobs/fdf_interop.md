# FDF Interop Jenkins Pipeline

## Overview

The FDF Interop pipeline deploys **IBM Fusion Data Foundation (FDF)** on IBM Cloud VPC, then runs **FIO IO workloads** (RBD + CephFS via `ceph-interop run-tests`). There are **no ocs-ci** acceptance/tier-1 tests.

For the full ODF interop flow (workloads + ocs-ci), see [ODF Interop Pipeline](./odf_interop.md).

## Job

| Item | Value |
|------|-------|
| Jenkins job | `fdf-interop` |
| Job DSL | [`jobs/fdf_interop.groovy`](../../jobs/fdf_interop.groovy) |
| Pipeline | [`jobs/pipelines/fdf_interop/Jenkinsfile`](../../jobs/pipelines/fdf_interop/Jenkinsfile) |

## Workflow

```
Validate → Clone ceph-interop → Setup → Credentials → Config → Deploy OCP + FDF → IO workloads → Teardown
```

No ocs-ci stages. Success requires deploy and IO workload tests to pass.

## BVT trigger (IBM Ceph image)

Mirrors the ODF path for Red Hat builds:

```
sync-blue-ceph-images (IBM ceph-base) → {release}-bvt-ci (BUILD_TYPE=IBM, TRIGGER_DATA_FOUNDATION_INTEROP=true)
  → fdf-interop (CEPH_IMAGE + RHCS_BUILD_VERSION)
```

Enable `TRIGGER_DATA_FOUNDATION_INTEROP` on BVT when `BUILD_TYPE=IBM`. `sync-blue-ceph-images` sets this automatically when it triggers BVT for IBM images.

Version resolution uses the same RHCS matrix as [ODF Interop](./odf_interop.md) (RHCS 9.2 / Ceph 20.2.2 → OCP + FDF **5.0**).

## Differences from ODF Interop

| Aspect | ODF (`odf-interop`) | FDF (`fdf-interop`) |
|--------|---------------------|---------------------|
| Pipeline file | `odf_interop/Jenkinsfile` | `fdf_interop/Jenkinsfile` |
| Stages after deploy | IO workloads + ocs-ci acceptance + tier-1 | IO workloads only |
| Context (ceph-interop checkout) | `ocp-ibm-vpc-odf-internal-storage.yaml` | `ocp-ibm-vpc-fdf-internal-storage.yaml` |
| Catalog resolve | Quay konflux (`ocs-build.sh`) | Staging ICR (`fdf-catalog.sh`) |
| Default `ENV_NAME` | `odf-interop-{BUILD}` | `fdf-interop-{BUILD}` |
| BVT trigger | `BUILD_TYPE=RH` + `TRIGGER_DATA_FOUNDATION_INTEROP` | `BUILD_TYPE=IBM` + `TRIGGER_DATA_FOUNDATION_INTEROP` |

## FDF catalog auto-resolution

When `FDF_CATALOG_IMAGE` is empty:

```bash
skopeo list-tags docker://cp.stg.icr.io/cp/df/isf-data-foundation-catalog
```

[`scripts/fdf-catalog.sh`](../../scripts/fdf-catalog.sh) uses `skopeo list-tags` with the OpenShift pull secret (`--authfile`) against staging ICR and picks the highest `v{minor}*` tag for the resolved operator channel.

## Parameters

FDF job exposes deployment parameters only (no `OCS_CI_*`, `PVC_COUNT`, or `INTEROP_PRODUCT`):

- `CEPH_IMAGE` (required)
- `OCP_CHANNEL`, `OCP_VERSION`, `FDF_CHANNEL`, `FDF_CATALOG_IMAGE`, `FDF_OPERATOR_CHANNEL`
- `IBM_REGION`, `VPC_NAME`, `MASTER_COUNT`, `WORKER_COUNT`, `DEVICE_SIZE`, `DEVICE_COUNT`
- `ENV_NAME`, `EMAIL_ADDRESS`

## Artifacts

```
ceph-interop/work/context/{ENV_NAME}/ocp-1/
├── state.json
├── openshift/auth/kubeconfig
└── deployment-resolved.yaml
```

## Rollout

1. Re-run the **seed job** to register `fdf-interop`
2. Smoke-test a manual build on `agents-interop`
3. Verify `isf-data-foundation-catalog` CatalogSource in `openshift-marketplace`

## Related

- [ODF Interop Pipeline](./odf_interop.md)
