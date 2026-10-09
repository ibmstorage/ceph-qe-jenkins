# `secrets/cephci_conf` (CephCI `.cephci.yaml`)

Lab file mounted into Jenkins and injected onto agents as **`~/.cephci.yaml`**.

| | |
|---|---|
| **On disk (controller)** | `/secrets/cephci_conf` |
| **CasC credential** | id `cephci-conf`, fileName `.cephci.yaml` (`casc.yaml`) |
| **Agent path** | `/home/jenkins/.cephci.yaml` via `setupCephCiRepo` (`cp -n` from credential) |

cephci reads this file for registry login, cloud defaults, vault/ldap/keystone helpers, and related lab config.

**Do not commit real passwords.** Keep secrets only in the mounted `/secrets/` volume. This doc shows structure with placeholders.

---

## Dual registry model

Keep **both** blocks while jobs still run on cephci `main` and on [#6192](https://github.com/red-hat-storage/cephci/pull/6192) (`feat/host-keyed-registries`):

| Block | Used by | Lookup |
|---|---|---|
| `credentials.registry.<vendor>.<tier>` | Current cephci `main` (product / build_type heuristics) | Nested IBM/RH tiers |
| Top-level `registries:<hostname>` | #6192 host-keyed login (`bootstrap-registry`, `upgrade-registry`, CDN prereq) | Exact registry hostname |

Usernames/passwords for a given host must match across both blocks (e.g. `credentials.registry.ibm.cp` ↔ `registries.cp.icr.io`).

`product` / `production` under `registries:` are optional metadata and are **not** used for auth lookup.

---

## Structure (matches current lab file)

```yaml
datacenter: <lab-datacenter>   # e.g. eu-de

# --- Legacy top-level subscription / single-registry helpers (older cephci callers) ---
stage_credentials:
  serverurl: <RH-STAGE-SUBSCRIPTION-SERVER>
  baseurl: <RH-STAGE-CDN-BASEURL>     # note: lab file may use a typo key "b4aseurl"; prefer baseurl
  username: <RH-STAGE-USER>
  password: <RH-STAGE-PASS>

cdn_credentials:
  serverurl: <RH-CDN-SUBSCRIPTION-SERVER>
  baseurl: <RH-CDN-BASEURL>
  username: <RH-CDN-USER>
  password: <RH-CDN-PASS>

rh_registry_credentials:
  registry: registry.redhat.io
  username: <RH-CDN-USER>
  password: <RH-CDN-PASS>

ibm_registry_credentials:
  registry: cp.stg.icr.io
  username: <IBM-STAGE-USER>
  password: <IBM-STAGE-PASS>

# --- Nested credentials (required for cephci main) ---
credentials:
  cloud:
    ibmc:
      access-key: <IBMC-ACCESS-KEY>
      service-url: <IBMC-IAAS-URL>
      private_key_path: <PATH-TO-SSH-PRIVATE-KEY>
      zone_name: <DNS-ZONE>
      vpc_name: <DEFAULT-VPC>
      zone_id_model_name: <ZONE>
      resource_group_id: <RESOURCE-GROUP-ID>
      dns_svc_id: <DNS-SERVICE-ID>

  # OLD nested registry map — do not remove while jobs run on main.
  registry:
    ibm:
      cdn:
        registry: cp.icr.io
        username: <IBM-CDN-USER>
        password: <IBM-CDN-PASS>
      stage:
        registry: cp.stg.icr.io
        username: <IBM-STAGE-USER>
        password: <IBM-STAGE-PASS>
      preprod:
        registry: preprod.icr.io
        username: <IBM-PREPROD-USER>
        password: <IBM-PREPROD-PASS>
    rh:
      cdn:
        registry: registry.redhat.io
        username: <RH-CDN-USER>
        password: <RH-CDN-PASS>
      stage:
        registry: registry.stage.redhat.io
        username: <RH-STAGE-USER>
        password: <RH-STAGE-PASS>

# --- NEW host-keyed registries (required for cephci #6192) ---
# Same secrets as credentials.registry.* above, keyed by hostname.
registries:
  cp.icr.io:
    production: true
    product: ibm
    username: <IBM-CDN-USER>
    password: <IBM-CDN-PASS>

  cp.stg.icr.io:
    product: ibm
    username: <IBM-STAGE-USER>
    password: <IBM-STAGE-PASS>

  preprod.icr.io:
    product: ibm
    username: <IBM-PREPROD-USER>
    password: <IBM-PREPROD-PASS>

  registry.redhat.io:
    production: true
    product: rh
    username: <RH-CDN-USER>
    password: <RH-CDN-PASS>

  registry.stage.redhat.io:
    product: rh
    username: <RH-STAGE-USER>
    password: <RH-STAGE-PASS>

# --- Lab services (keep existing values) ---
keystone:
  ibmc:
    url: <KEYSTONE-IBMC-URL>
  openstack:
    url: <KEYSTONE-OSP-URL>

ldap:
  ibmc:
    url: <LDAP-IBMC-URL>
  openstack:
    url: <LDAP-OSP-URL>

vault:
  ibmc:
    url: <VAULT-IBMC-URL>
    agent:
      auth: <AUTH>
      engine: <ENGINE>
      role-id: <ROLE-ID>
      secret-id: <SECRET-ID>
      prefix: <PREFIX>
  openstack:
    url: <VAULT-OSP-URL>
    agent:
      auth: <AUTH>
      engine: <ENGINE>
      role-id: <ROLE-ID>
      secret-id: <SECRET-ID>
      prefix: <PREFIX>

gklm_config:
  ibmc:
    gklm_ip: <GKLM-IP>
    gklm_user: <GKLM-USER>
    gklm_password: <GKLM-PASS>
    gklm_node_username: <NODE-USER>
    gklm_node_password: <NODE-PASS>
    gklm_hostname: <HOSTNAME>
  openstack:
    gklm_ip: <GKLM-IP>
    gklm_user: <GKLM-USER>
    gklm_password: <GKLM-PASS>
    gklm_node_username: <NODE-USER>
    gklm_node_password: <NODE-PASS>
    gklm_hostname: <HOSTNAME>

repos:
  rgw_configs:
    git_url: <CONFIGS-GIT-URL>
    dest: <DEST-PATH>
    oauth_token: <GIT-OAUTH-TOKEN>

ibm_cloud:
  api-key: <IBMC-API-KEY>
```

---

## After changing the file

1. Update the mounted `/secrets/cephci_conf` on the Jenkins controller (ops).
2. Reload CasC / restart so credential `cephci-conf` picks up new `secretBytes`.
3. On agents, `setupCephCiRepo` uses `cp -n` — existing `~/.cephci.yaml` is **not** overwritten. Remove or replace `/home/jenkins/.cephci.yaml` on agents if credentials changed.

---

## Related

- [Architecture — Credential Model](../architecture.md#credential-model)
- CasC binding: `casc.yaml` → credential id `cephci-conf`
- Agent inject: `vars/setupCephCiRepo.groovy`
- cephci host-keyed registries: [red-hat-storage/cephci#6192](https://github.com/red-hat-storage/cephci/pull/6192)
