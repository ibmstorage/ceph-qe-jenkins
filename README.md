# Jenkins

Ceph QE Jenkins Server Configuration

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for setup instructions, commit message conventions, metadata rules, and PR workflow.

## Documentation

- [Architecture Overview](docs/architecture.md) — how the system is structured, pipeline categories, build flow, shared library reference, and external systems
- [cephci_conf structure](docs/secrets/cephci_conf.md) — lab `~/.cephci.yaml` layout (nested + host-keyed registries for #6192)
- [ODF Interop Pipeline](docs/jobs/odf_interop.md) — deploying OCP + ODF on IBM Cloud for interoperability testing
- [FDF Interop Pipeline](docs/jobs/fdf_interop.md) — deploying OCP + IBM Fusion Data Foundation on IBM Cloud
- [Sync Manifest Images](docs/jobs/sync_manifest_images.md) — synchronising container images from the manifest repo to the local registry
- [Cleanup Registry Images](docs/jobs/cleanup_registry_images.md) — removing orphaned images from the internal NGINX registry
