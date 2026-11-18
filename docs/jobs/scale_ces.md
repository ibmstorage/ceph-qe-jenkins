# Scale CES Pipeline (`scale-ces`)

Deploys IBM Storage Scale HA + CES on IBM Cloud VPC via [ceph-qe-zypper](https://github.com/ibmstorage/ceph-qe-zypper), runs sanity pytest, archives logs/reports, emails results, and tears down.

Isolated from ODF/FDF interop: own Job DSL, Jenkinsfile, and `scale*` / `prepareScale*` / `sendScale*` shared-library vars.

## Job

| | |
|---|---|
| Job name | `scale-ces` |
| Agent label | `agents-interop` |
| Timeout | 12 hours |
| Framework | `ceph-qe-zypper` (`ZYPPER_REPO` / `ZYPPER_BRANCH`) |
| Default context | `deployments/infra/scale/scale-ibm-vpc-ces.yaml` |

## Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `ENV_NAME` | `scale-ces-{BUILD_NUMBER}` | `--env-name` |
| `ZYPPER_REPO` / `ZYPPER_BRANCH` | `git@github.com:sunilkumarn417/ceph-qe-zypper.git` / `main` | Checkout via agent SSH |
| `CONTEXT` | `deployments/infra/scale/scale-ibm-vpc-ces.yaml` | Deploy/test context |
| `INSTALLER_SOURCE` | `local` | `local` / `nfs` / `http` |
| `INSTALLER_EXPORT` | `/data/jenkins/workspace/artifacts` | Agent path, NFS export, or URL |
| `INSTALLER_FILENAME` | `Storage_Scale_Developer-6.0.2.0-x86_64-Linux-install` | Required when export is a folder |
| `PYTEST_MARKER` | `sanity` | pytest `-m` |
| `FRESH` | false | `--fresh` |
| `SKIP_TEARDOWN` | false | Keep cloud resources |
| `VERBOSE` | false | `-v` DEBUG only when enabled; `--log-file` always |
| `SSH_PUBLIC_KEY` | empty | Optional `--ssh-public-key` |
| `EMAIL_ADDRESS` | BVT + upgrade + odf-interop DLs | `emailext` recipients |

## Stages

1. **Initialize** — set `ENV_NAME`, display name  
2. **Validate** — installer path/filename on agent  
3. **Clone ceph-qe-zypper**  
4. **Validate Context** — context YAML exists in checkout  
5. **Setup** — zypper operator path: `python3.12 -m venv .venv` + `make install`, then `pytest-html`  
6. **Prepare Credentials** — `prepareScaleCredentials` (IBM + ed25519 SSH + `rh-cdn-access` RHSM)  
7. **Deploy** — `ceph-zypper deploy` (installer CLI flags; `--installer-filename` only if export is a folder; `--log-file …/logs/deploy.log`)  
8. **Run Sanity Tests** — `ceph-zypper run-tests --suite tests/sanity -m …` + junit/html + `--log-file`  
9. **post always** — `ceph-zypper teardown --level all` + archive + COS + `sendScaleEmail` + scrub credentials  

## Credentials

| Jenkins / agent | Use |
|-----------------|-----|
| `ibmc-api-key` (+ account / COS HMAC) | `credentials.yaml` → `accounts.ibm_qe.ibmcloud` |
| Agent `~/.ssh/id_ed25519` (prefer), else `id_rsa`, else `vmLoginAccessKey` | `ssh_private_key` → `.ci-ssh/id_ed25519` (IBM Cloud `ceph-qe-sa`) |
| `rh-cdn-access` (usernamePassword) | `rhel_subscription` username/password for BYOL RHSM |
| `ceph-qe-sa-pat` | Job DSL / shared-lib SCM for `ceph-qe-jenkins` only |
| Agent `~/.ssh/id_ed25519` | `ceph-qe-zypper` clone via `git@github.com:…` (no Jenkins git credential) |

Private keys and `credentials.yaml` are **not** archived; scrubbed in post.

## Artifacts

Under `ceph-qe-zypper/work/context/{ENV_NAME}/`:

```text
run.yaml
logs/deploy.log
logs/run-tests.log
logs/teardown.log
reports/junit.xml
reports/report.html
```

Also: `cluster-artifacts-{ENV}.tar.gz`, Jenkins **Test Result** from junit, COS under `scale-ces/{BUILD_NUMBER}/` when rclone remote `ceph-qe-cos` exists.

## Email

`sendScaleEmail` via `emailext` (HTML). Subject: `Storage Scale CES Report: {STATUS} (Scale {installer} + {ENV})`. Body includes installer, context, env, infra, marker, build link, duration, logs link, Deploy / Sanity rows. Attaches `run.yaml` + `junit.xml` when present.

## Seed / SCM branch

Job DSL `cpsScm` currently tracks `*/scale-pipeline` so Jenkins can load the Jenkinsfile before merge. After the PR merges to `main`, change that branch back to `*/main` and re-run **seed**.

Re-run **seed** whenever `jobs/scale_ces.groovy` changes so the job’s SCM tip updates.

## Smoke checklist

1. Push `scale-pipeline` → run **seed** so `scale-ces` exists and shared-lib vars load from `@scale-pipeline`  
2. Confirm fork `main` has the `vpc-rgw` lab profile (merge `ssh-key` if needed)  
3. Agent (`agents-interop`) has installer under `INSTALLER_EXPORT` / `INSTALLER_FILENAME`  
4. Build with defaults: deploy → sanity junit → teardown  
5. Confirm archived logs + junit + email  
6. Confirm IBM `{ENV}-*` resources are gone (unless `SKIP_TEARDOWN`)  

