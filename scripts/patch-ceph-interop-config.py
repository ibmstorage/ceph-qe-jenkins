#!/usr/bin/env python3
"""Upsert Jenkins interop build profiles into a per-build configuration.yaml.

Reads the shipped base configuration.yaml, updates only the profile keys referenced
by the deployment context for this build, and writes a new output file (checkout
configs/configuration.yaml is never modified).

Usage:
    patch-ceph-interop-config.py <base-configuration.yaml> <patch-args.json> <output-configuration.yaml>
"""
from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

import yaml

DEFAULT_IBM_QE_EU: dict[str, Any] = {
    "credential_profile": "ibm_qe",
    "install_method": "ignition_upi",
    "region": "eu-de",
    "resource_group": "qe-infra",
    "vpc_name": "vpc-ceph-integration",
    "subnet_name": "sn-ceph-integration-01",
    "security_group_name": "sg-ceph-integration",
    "cos_instance": "Ceph-QE-COS",
    "cos_bucket": "ceph-qe-os-images",
    "cos_region": "au-syd",
    "ssh_key_names": ["ceph-qe-sa", "ceph-qe-sa-svc"],
    "ignition_hosting": "cos",
    "ignition_server": {
        "host": "10.243.80.5",
        "ssh_key_path": "",
        "remote_dir": "/data/logs/ignitions",
        "nginx_root": "/data",
        "user": "root",
    },
    "bootstrap": {"profile": "bx2-4x16"},
    "master": {"profile": "bx2-4x16"},
    "worker": {"profile": "bx2-16x64", "volume_profile": "osd-3x50gb"},
}


def load_yaml(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as handle:
        data = yaml.safe_load(handle)
    return data if isinstance(data, dict) else {}


def save_yaml(path: Path, data: dict[str, Any]) -> None:
    with path.open("w", encoding="utf-8") as handle:
        yaml.safe_dump(
            data,
            handle,
            default_flow_style=False,
            sort_keys=False,
            allow_unicode=True,
        )


def ensure_profiles(config: dict[str, Any], section: str) -> dict[str, Any]:
    block = config.setdefault(section, {})
    if not isinstance(block, dict):
        block = {}
        config[section] = block
    profiles = block.setdefault("profiles", {})
    if not isinstance(profiles, dict):
        profiles = {}
        block["profiles"] = profiles
    return profiles


def _as_bool(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    return str(value or "").strip().lower() in ("1", "true", "yes", "on")


def _ec_profile_fields(args: dict[str, Any]) -> dict[str, Any]:
    """Day-1 EC keys merged onto the ODF/FDF service profile."""
    return {
        "ec_default_pools": _as_bool(args.get("ec_default_pools", False)),
        "ec_data_chunks": int(args.get("ec_data_chunks", 2)),
        "ec_coding_chunks": int(args.get("ec_coding_chunks", 2)),
        "ec_failure_domain": str(args.get("ec_failure_domain") or "host"),
    }


def patch_configuration(config: dict[str, Any], args: dict[str, Any]) -> None:
    product = str(args.get("product", "odf")).lower()
    infra_profile = str(args.get("infra_profile", "qe_eu"))

    openshift_profiles = ensure_profiles(config, "openshift")
    openshift_profiles[str(args["openshift_profile"])] = {
        "base_name": "ocp-ibmc",
        "base_domain": "ceph-qe.rh-ocs.com",
        "version": args.get("ocp_version", ""),
        "channel": args.get("ocp_channel", "released"),
        "fips": _as_bool(args.get("fips", False)),
        "allow_unsigned_images": True,
        "network_type": "OVNKubernetes",
        "cluster_network_cidr": "10.128.0.0/14",
        "host_prefix": 23,
        "machine_network_cidr": "10.243.0.0/16",
        "service_network_cidr": "172.30.0.0/16",
    }

    ibm_profiles = ensure_profiles(config, "ibm_cloud")
    ibm = ibm_profiles.get(infra_profile)
    if not isinstance(ibm, dict):
        ibm = dict(DEFAULT_IBM_QE_EU)
    else:
        ibm = dict(ibm)
    ibm["region"] = args.get("ibm_region", ibm.get("region", "eu-de"))
    ibm["vpc_name"] = args.get("vpc_name", ibm.get("vpc_name", "vpc-ceph-integration"))
    # cos_region is where the COS bucket lives (au-syd); do not copy VPC region (eu-de).
    if not ibm.get("cos_region"):
        ibm["cos_region"] = DEFAULT_IBM_QE_EU["cos_region"]
    worker = ibm.get("worker")
    if not isinstance(worker, dict):
        worker = {"profile": "bx2-16x64"}
    else:
        worker = dict(worker)
    worker["volume_profile"] = args["volume_profile"]
    ibm["worker"] = worker
    ibm_profiles[infra_profile] = ibm

    volume_profiles = ensure_profiles(config, "volumes")
    volume_profiles[str(args["volume_profile"])] = {
        "count": int(args.get("device_count", 3)),
        "capacity_gb": int(args.get("device_size", 50)),
        "profile": "10iops-tier",
    }

    storage_profiles = ensure_profiles(config, "storage")
    device_size = int(args.get("device_size", 50))
    storage_profiles[str(args["storage_profile"])] = {
        "device_set_count": int(args.get("device_set_count", args.get("worker_count", 3))),
        "osd_size": f"{device_size}Gi",
        "replica": int(args.get("replica", 3)),
        "lso_min_device_size": "1Gi",
        "lso_max_device_size": args.get("lso_max", f"{device_size * 2}Gi"),
    }

    service_profile = str(args["service_profile"])
    catalog_image = args.get("catalog_image") or ""
    ceph_image = args.get("ceph_image") or ""
    operator_channel = args.get("odf_operator_channel") or ""

    if product == "fdf":
        fdf_profiles = ensure_profiles(config, "fdf")
        fdf_profiles[service_profile] = {
            "product": "fdf",
            "channel": operator_channel,
            "source": "isf-data-foundation-catalog",
            "catalog_source_name": "isf-data-foundation-catalog",
            "catalog_display_name": "IBM Fusion Data Foundation",
            "catalog_publisher": "IBM",
            "catalog_image": catalog_image,
            "ceph_image": ceph_image,
            "must_gather_image": "",
            "storage_profile": args["storage_profile"],
            **_ec_profile_fields(args),
        }
    else:
        odf_profiles = ensure_profiles(config, "odf")
        odf_profiles[service_profile] = {
            "product": "odf",
            "channel": operator_channel,
            "source": "redhat-operators",
            "catalog_source_name": "redhat-operators",
            "catalog_display_name": "OpenShift Data Foundation",
            "catalog_publisher": "Red Hat",
            "catalog_image": catalog_image,
            "nightly_index_image": catalog_image,
            "ceph_image": ceph_image,
            "must_gather_image": "",
            "storage_profile": args["storage_profile"],
            **_ec_profile_fields(args),
        }


def main() -> int:
    if len(sys.argv) != 4:
        print(
            "usage: patch-ceph-interop-config.py "
            "<base-configuration.yaml> <patch-args.json> <output-configuration.yaml>",
            file=sys.stderr,
        )
        return 2

    base_path = Path(sys.argv[1])
    args_path = Path(sys.argv[2])
    output_path = Path(sys.argv[3])
    args = json.loads(args_path.read_text(encoding="utf-8"))

    config = load_yaml(base_path)
    patch_configuration(config, args)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    save_yaml(output_path, config)

    product = str(args.get("product", "odf")).lower()
    service_profile = str(args["service_profile"])
    openshift_profile = str(args["openshift_profile"])

    print(f"wrote {output_path} (from {base_path})")
    print(f"  openshift.{openshift_profile}.version={args.get('ocp_version', '')}")
    print(
        f"  openshift.{openshift_profile}.fips="
        f"{_as_bool(args.get('fips', False))}"
    )
    if product == "fdf":
        fdf = ensure_profiles(config, "fdf")[service_profile]
        print(f"  fdf.{service_profile}.catalog_image={fdf.get('catalog_image', '')}")
        print(f"  fdf.{service_profile}.ceph_image={fdf.get('ceph_image', '')}")
    else:
        odf = ensure_profiles(config, "odf")[service_profile]
        print(f"  odf.{service_profile}.catalog_image={odf.get('catalog_image', '')}")
        print(f"  odf.{service_profile}.ceph_image={odf.get('ceph_image', '')}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
