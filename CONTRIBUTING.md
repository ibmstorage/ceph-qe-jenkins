# Contributing Guide

This document explains the conventions, tooling, and workflow expected for all contributions to `ibmstorage/ceph-qe-jenkins`.

---

## Table of Contents

1. [Pre-commit hooks](#pre-commit-hooks)
2. [Commit message format](#commit-message-format)
3. [Branch and PR workflow](#branch-and-pr-workflow)
4. [Adding or editing metadata](#adding-or-editing-metadata)
5. [Adding or editing pipelines](#adding-or-editing-pipelines)
6. [Adding or editing shared library steps](#adding-or-editing-shared-library-steps)
7. [Adding a new Ceph release](#adding-a-new-ceph-release)
8. [Documentation](#documentation)

---

## Pre-commit hooks

All checks run automatically before every commit. You only need to set this up once per clone.

### Installation

```bash
pip install pre-commit
pre-commit install          # hooks run on git commit
pre-commit install --hook-type commit-msg   # commit message check
```

### Running manually

```bash
# Check only the files you have changed
pre-commit run

# Check the entire repository (useful after pulling)
pre-commit run --all-files
```

### What the hooks check

| Hook | Scope | Auto-fixes |
|---|---|---|
| `trailing-whitespace` | All text files | ✅ removes trailing spaces |
| `end-of-file-fixer` | All text files | ✅ adds missing final newline |
| `check-merge-conflict` | All text files | ❌ |
| `check-added-large-files` | All files ≥ 1 MB | ❌ |
| `no-commit-to-branch` | `main` branch | ❌ blocks direct commits |
| `detect-private-key` | All files | ❌ |
| `check-yaml` | All `.yaml` files (except `casc.yaml`) | ❌ |
| `ruff` | `scripts/*.py` | ✅ fixes lint issues |
| `ruff-format` | `scripts/*.py` | ✅ reformats code |
| `validate-metadata` | `metadata/**/*.yaml` | ❌ reports errors, requires manual fix |
| `commit-msg-format` | Conventional Commits format + DCO sign-off | ❌ |

> **Note:** `casc.yaml` is excluded from generic YAML validation because it uses Jenkins JCasC-specific tags.

---

## Commit message format

All commit messages must follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>[(<scope>)]: <short description>

[optional body]

[optional footer(s)]
```

### Allowed types

| Type | When to use |
|---|---|
| `feat` | Adding a new test suite, pipeline, or job definition |
| `fix` | Correcting a bug in a pipeline, shared-library step, or metadata file |
| `docs` | Changes to documentation only |
| `refactor` | Code change that neither fixes a bug nor adds a feature |
| `chore` | Build tooling, dependency updates, CI config changes |
| `test` | Adding or updating test scripts inside `scripts/` |
| `ci` | Changes to Jenkins job definitions or Jenkinsfiles |
| `style` | Whitespace, formatting, missing semi-colons — no logic change |
| `revert` | Reverting a prior commit |

### Rules

- Subject line **≤ 72 characters**.
- Subject line must **not be empty**.
- If a body is included, it must be separated from the subject by a **blank line**.
- Subject must start with one of the type prefixes above.
- Every commit must carry a **`Signed-off-by`** trailer (see [DCO](#developer-certificate-of-origin-dco) below).

### Examples

```
feat(metadata): add tentacle SMB tier-1 regression suites

Adds 3 new SMB suites for the tentacle release covering live migration
and multi-client scenarios.

Closes #142
```

```
fix: correct global-conf paths in tentacle/nvmeof.yaml

All NvmeOF suites in tentacle metadata were still pointing to squid
cluster configs, causing tests to run against the wrong topology.
```

```
ci(bvt): add timeout option to bvt_ci Jenkinsfile
```

```
docs: add architecture overview to docs/
```

---

## Developer Certificate of Origin (DCO)

By contributing to this repository you certify that you wrote the code or have the right to submit it under the project's open-source license, and that you agree to the [Developer Certificate of Origin](https://developercertificate.org/).

You must add a `Signed-off-by` trailer to **every commit**. The easiest way is the `--signoff` flag:

```bash
git commit --signoff -m "fix: correct global-conf paths in nvmeof.yaml"
```

Git will append the trailer automatically using your configured `user.name` and `user.email`:

```
Signed-off-by: Your Name <you@example.com>
```

If you forgot to sign off on a recent commit:

```bash
# Amend the last commit
git commit --amend --signoff --no-edit

# Sign off all commits in a branch (interactive rebase)
git rebase --signoff HEAD~<N>
```

The `commit-msg-format` pre-commit hook rejects commits without a valid `Signed-off-by: Name <email>` trailer.

---

## Branch and PR workflow

1. **Never commit directly to `main`.** The `no-commit-to-branch` hook blocks this; CI will also reject direct pushes.
2. Create a feature branch from `main`:
   ```bash
   git checkout -b fix/tentacle-nvmeof-conf
   ```
3. Make your changes, commit with a conformant message.
4. Open a Pull Request against `main`.
5. At least one peer review is required before merging.
6. Squash-merge is preferred to keep the history clean.

---

## Adding or editing metadata

Test suite metadata lives in `metadata/<release>/<fg>.yaml`. The `validate-metadata` pre-commit hook checks every changed metadata file before the commit lands.

### File structure

```yaml
# metadata/<release>/<fg>.yaml

<job-key>:           # e.g. bvt, sanity, regression, upgrade, released
  metadata:
    cloud:
      - ibmc         # or aws
  suites:
    - name: "Unique human-readable name"          # must be unique within this job-key
      suite: suites/<release>/<fg>/tier-X_….yaml  # path relative to cephci repo
      global-conf: conf/<release>/<fg>/….yaml     # path relative to cephci repo
      build:         # optional — omit to run for all build types
        - rh         # or ibm, upstream
      platform:      # optional — omit to run on all platforms
        - rhel-9     # or rhel-8, rhel-10
      cloud:         # optional allowlist — suite only runs on listed clouds
        - ibmc
      exclude_cloud: # optional denylist — suite is skipped on listed clouds
        - aws
      vm-size:       # optional — cloud-keyed override
        ibmc: bx2-4x16
      tags:          # optional — used for tag-based filtering
        - bvt
      custom-config: # optional — key/value pairs passed to run.py via execTestSuites
        enable-fips-mode: true  # only this suite when using metadata-driven CI jobs
```

### Rules enforced by `validate-metadata`

| Rule | Why |
|---|---|
| Suite `name` must be unique within the same job-key | Duplicate names cause one suite to silently overwrite another in the parallel stage map |
| `suite` and `global-conf` are required | Missing either will cause the test executor to fail at runtime |
| `global-conf` must not reference a different Ceph release directory (e.g. `conf/squid/` inside `tentacle/`) | Cross-release cluster configs produce incorrect topologies |
| `build` values must be one of `rh`, `ibm`, `upstream` | Typos silently exclude a suite from all builds |
| `platform` values must be one of `rhel-8`, `rhel-9`, `rhel-10` | Typos silently exclude a suite from all platforms |
| `vm-size` cloud keys must be `ibmc` or `aws` | Unknown keys are silently ignored at runtime |
| `cloud` in `metadata` or suite must be `ibmc` or `aws` | Unknown values skip the entire job-key or suite silently |
| `exclude_cloud` in suite must be `ibmc` or `aws` | Unknown values fail validation to catch typos (e.g. `awss`) |

### Allowed `job-key` values

`bvt` · `sanity` · `regression` · `extended_regression` · `upgrade` · `sanity-upgrade` · `released` · `rook_defaults`

---

## Adding or editing pipelines

Jenkinsfiles live at `jobs/pipelines/<job-name>/Jenkinsfile`. Job DSL definitions live at `jobs/<job-family>.groovy`.

### Checklist for a new pipeline

- [ ] Add a `timeout()` option block (see `cleanup_registry_images/Jenkinsfile` for an example).
- [ ] Choose the correct agent label: `agents` (IBM Cloud CI), `agents-aws` (AWS CI), `infra` (sync/maintenance), `agents-interop` (ODF Interop).
- [ ] Use `buildDiscarder(logRotator(numToKeepStr: "30", daysToKeepStr: "90"))` for CI test pipelines; `numToKeepStr: "10"` is the minimum for any pipeline.
- [ ] Wrap `git push` calls in try-catch and fail the build explicitly on push failure.
- [ ] Use `def` for all variable declarations — implicit globals cause race conditions in parallel execution.
- [ ] Add the job DSL definition in the relevant `jobs/*.groovy` file and re-run the seed job after merging.

### Editing shared steps in `vars/`

- Always declare local variables with `def`.
- Throw `RuntimeException` (not `error()`) for missing mandatory parameters in shared-library steps so callers get a clear message.
- Wrap external commands (`sh`, `git`, Skopeo) in try-catch when transient failures are possible, and add `retry(3)` for network operations.
- Keep per-step documentation in the function's leading comment block.

---

## Adding or editing shared library steps

Steps in [`vars/`](vars/) are callable from any Jenkinsfile via `@Library("ceph-qe-libs@main") _`.

### Conventions

- File name = callable step name (e.g. `vars/syncBlueImages.groovy` → `syncBlueImages(…)`).
- The entry method must be `def call(Map parameters) { … }`.
- Validate all mandatory parameters at the top of `call()` before doing any work.
- Reference configuration constants from `src/main/groovy/cephJobLib/` rather than hardcoding values.
- Update [`docs/architecture.md`](docs/architecture.md#shared-library) when adding a new step.

---

## Adding a new Ceph release

1. **Create the metadata directory** and copy the FG YAML files from the previous release:
   ```bash
   cp -r metadata/tentacle metadata/main
   ```
2. **Update paths** inside every copied YAML so `suite:` and `global-conf:` reference the new release directory (e.g. `conf/main/…`, `suites/main/…`).
3. **Run the validator** to confirm no stale cross-release paths remain:
   ```bash
   python3 scripts/validate_metadata.py metadata/main/
   ```
4. **Add job DSL entries** in the relevant `jobs/*.groovy` files:
   - `jobs/bvt_ci.groovy` — add a `generate_jobs(…)` call for the new version
   - `jobs/sanity_ci.groovy` — add cron expression and release list
   - `jobs/regression_ci.groovy` — add FG → VPC mappings if needed
   - `jobs/upgrade_ci.groovy` — add upgrade path suites
5. **Re-run the seed job** in Jenkins after merging to generate the new pipeline jobs.
6. **Update documentation**:
   - Add the new release to the agent/pipeline tables in [`docs/architecture.md`](docs/architecture.md)
   - Add Z-stream release options to the job DSL files once GA patches begin

---

## Documentation

Documentation lives in [`docs/`](docs/).

| File | Purpose |
|---|---|
| [`docs/architecture.md`](docs/architecture.md) | High-level architecture: repo layout, pipelines, agents, shared library reference |
| [`docs/jobs/odf_interop.md`](docs/jobs/odf_interop.md) | ODF Interop pipeline parameters and usage |
| [`docs/jobs/fdf_interop.md`](docs/jobs/fdf_interop.md) | FDF Interop pipeline parameters and usage |
| [`docs/jobs/sync_manifest_images.md`](docs/jobs/sync_manifest_images.md) | Image sync from manifest repo |
| [`docs/jobs/cleanup_registry_images.md`](docs/jobs/cleanup_registry_images.md) | Registry image cleanup |

Keep documentation in sync with code changes:

- New pipeline → add a row to the pipeline category table in `docs/architecture.md`
- New shared library step → add a row to the `vars/` table in `docs/architecture.md`
- New external system → add a row to the External Systems table
- New credentials → add a row to the Credential Model table

Commit all documentation changes in the same commit as the code change they describe, using the `docs` type prefix only when the PR is documentation-only.
