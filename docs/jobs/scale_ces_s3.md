# Scale CES S3 Pipeline (`scale-ces-s3`)

Deploys IBM Storage Scale HA with **CES S3 only** (NooBaa NSFS) on IBM Cloud VPC via [ceph-qe-zypper](https://github.com/ibmstorage/ceph-qe-zypper), then runs the post-deploy **`tests/s3`** suite.

Shares `jobs/pipelines/scale_ces/Jenkinsfile` and the same `scale*` / `prepareScale*` / `sendScale*` vars as [`scale-ces`](scale_ces.md). Job DSL defaults point at the S3 context and suite.

## Job

| | |
|---|---|
| Job name | `scale-ces-s3` |
| Agent label | `agents-interop` |
| Timeout | 12 hours |
| Framework | `ceph-qe-zypper` (`ZYPPER_REPO` / `ZYPPER_BRANCH`) |
| Default context | `deployments/infra/scale/scale-ibm-vpc-ces-s3.yaml` |
| Post-deploy suite | `tests/s3` `-m s3` |

## Parameters

Defaults differ from `scale-ces`; other installer / email / teardown knobs match.

| Parameter | Default | Description |
|-----------|---------|-------------|
| `ENV_NAME` | `scale-ces-s3-{BUILD_NUMBER}` | `--env-name` (from `{JOB_NAME}-{BUILD_NUMBER}`) |
| `CONTEXT` | `deployments/infra/scale/scale-ibm-vpc-ces-s3.yaml` | Dedicated protocol nodes, `protocols: [s3]` |
| `PYTEST_SUITE` | `tests/s3` | Post-deploy `run-tests --suite` |
| `PYTEST_MARKER` | `s3` | pytest `-m` |
| `PROTOCOLS_LABEL` | `s3` | Email protocols row |
| `TEST_STAGE_LABEL` | `S3 (pytest)` | Email test-stage row |
| `NODES_LABEL` | `3 NSD + 1 admin + 2 protocol + 1 S3 client + 1 gpfs client` | Email topology |
| `SSH_LOG` | `full` | Same as [`scale-ces`](scale_ces.md): live remote CLI lines on deploy / run-tests / teardown |

## Stages

Same as [`scale-ces`](scale_ces.md): Initialize → Validate → Clone → Validate Context → Setup → Prepare Credentials → **Deploy** → **Run Tests** (`tests/s3 -m s3`) → post teardown/archive/email.

Equivalent CLI after deploy:

```bash
ceph-zypper run-tests \
  --context deployments/infra/scale/scale-ibm-vpc-ces-s3.yaml \
  --env-name "${ENV_NAME}" \
  --suite tests/s3 \
  --log-file work/context/${ENV_NAME}/logs/s3-tests.log \
  -m s3 \
  --junitxml=work/context/${ENV_NAME}/reports/junit.xml \
  --html=work/context/${ENV_NAME}/reports/report.html --self-contained-html
```

Jenkins adds `--credentials` plus junit/html; marker default is **`s3`**.

## Artifacts / COS

Same layout under `work/context/{ENV_NAME}/` as `scale-ces` (`reports/junit.xml`, `reports/report.html`, `logs/s3-tests.log`). Jenkins archives those paths; COS: `scale-ces-s3/{BUILD}/reports/` for xml/html and `…/logs/` for logs.

## Seed

Re-run **seed** after pushing `jobs/scale_ces_s3.groovy` (and Jenkinsfile/shared-lib changes) on `main`.

## Smoke checklist

1. Seed creates `scale-ces-s3`  
2. Zypper branch has `scale-ibm-vpc-ces-s3.yaml` + `tests/s3`  
3. Build defaults: deploy CES S3 → S3 junit → teardown  
4. Confirm email shows protocols `s3` and suite `tests/s3 -m s3`  
