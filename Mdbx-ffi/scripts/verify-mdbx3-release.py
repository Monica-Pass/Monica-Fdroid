#!/usr/bin/env python3
"""Verify a staged MDBX3 Android drop-in release directory."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path


EXPECTED_ABIS = {
    "arm64-v8a": ("elf", "aarch64"),
    "armeabi-v7a": ("elf", "arm"),
    "x86_64": ("elf", "x86_64"),
}
EXPECTED_LIBRARY_NAME = "libmdbx_ffi.so"
EXPECTED_NEEDED = {"libc.so", "libdl.so", "libm.so"}
EXPECTED_MIN_LOAD_ALIGNMENT = {
    "arm64-v8a": 16 * 1024,
    "armeabi-v7a": 4 * 1024,
    "x86_64": 16 * 1024,
}

# These sets are the MDBX2 mdbx-ffi release profile (baseline commit
# 1070dee739dbe564806654359b6c6caa68156de5). They intentionally cover the
# compiled capability manifest, not CLI-only or development-only features.
EXPECTED_ENABLED_STORAGE_CAPABILITIES = {
    "mdbx.storage.authenticated-encryption",
    "mdbx.storage.bounded-sync-state",
    "mdbx.storage.collection-profiles",
    "mdbx.storage.commit-history",
    "mdbx.storage.conflicts",
    "mdbx.storage.external-blob-lifecycle",
    "mdbx.storage.external-blob-references",
    "mdbx.storage.external-blob-replication",
    "mdbx.storage.external-blob-transfer",
    "mdbx.storage.filesystem-blob-store",
    "mdbx.storage.generic-metadata",
    "mdbx.storage.generic-objects",
    "mdbx.storage.key-epochs",
    "mdbx.storage.mdbx1-compatibility",
    "mdbx.storage.payload-migrations",
    "mdbx.storage.recovery",
    "mdbx.storage.snapshots",
    "mdbx.storage.synchronization",
    "mdbx.storage.tiga-policy",
}
EXPECTED_DISABLED_STORAGE_CAPABILITIES = {
    "mdbx.storage.benchmarks",
    "mdbx.storage.derived-search-index",
    "mdbx.storage.kdbx-binary-export",
    "mdbx.storage.kdbx-binary-import",
    "mdbx.storage.kdbx-json-export",
    "mdbx.storage.kdbx-json-import",
}
EXPECTED_ENABLED_SYNC_CAPABILITIES = {
    "authenticated-bundle-v1",
    "authenticated-state-root-v1",
    "blob-chunk-transfer-v1",
    "blob-manifest-paging-v1",
    "blob-transfer-resume-v1",
    "commit-inventory-paging-v1",
    "delta-inventory-paging-v1",
    "incremental-bundle-v4",
    "incremental-resume-v1",
}
EXPECTED_DISABLED_SYNC_CAPABILITIES = {"bundle-zstd-v1"}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def binary_identity(path: Path) -> tuple[str, str]:
    data = path.read_bytes()
    if not data.startswith(b"\x7fELF") or len(data) < 20:
        raise ValueError(f"{path} is not a complete ELF shared object")
    byte_order = "little" if data[5] == 1 else "big" if data[5] == 2 else None
    if byte_order is None:
        raise ValueError(f"{path} has an unsupported ELF byte order")
    machine_id = int.from_bytes(data[18:20], byte_order)
    machine = {40: "arm", 62: "x86_64", 183: "aarch64"}.get(
        machine_id, f"elf-machine-{machine_id}"
    )
    return "elf", machine


def load_json(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"JSON root must be an object: {path}")
    return value


def resolve_readelf(requested: Path | None, artifacts: dict) -> Path:
    if requested is not None:
        path = requested.resolve()
        if not path.is_file():
            raise ValueError(f"llvm-readelf does not exist: {path}")
        return path
    nm_value = artifacts.get("ffi_abi", {}).get("symbol_inspector")
    if isinstance(nm_value, str) and nm_value:
        nm_path = Path(nm_value)
        suffix = ".exe" if nm_path.suffix.lower() == ".exe" else ""
        sibling = nm_path.with_name(f"llvm-readelf{suffix}")
        if sibling.is_file():
            return sibling.resolve()
    discovered = shutil.which("llvm-readelf")
    if discovered:
        return Path(discovered).resolve()
    raise ValueError("llvm-readelf is required to verify Android ELF release properties")


def inspect_elf_contract(path: Path, readelf: Path, minimum_alignment: int) -> dict:
    completed = subprocess.run(
        [str(readelf), "-d", "-n", "-lW", str(path)],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if completed.returncode != 0:
        detail = completed.stderr.strip() or completed.stdout.strip()
        raise ValueError(f"llvm-readelf failed for {path}: {detail}")
    output = completed.stdout
    needed = sorted(set(re.findall(r"\(NEEDED\).*?\[([^\]]+)\]", output)))
    sonames = re.findall(r"\(SONAME\).*?\[([^\]]+)\]", output)
    if len(sonames) > 1:
        raise ValueError(f"multiple SONAME entries found in {path}")
    build_ids = re.findall(r"Build ID:\s*([0-9A-Fa-f]+)", output)
    if len(build_ids) != 1 or len(build_ids[0]) != 40:
        raise ValueError(f"{path} must retain exactly one SHA-1 GNU build ID")

    load_segments = []
    pattern = re.compile(
        r"^\s*LOAD\s+(0x[0-9A-Fa-f]+)\s+(0x[0-9A-Fa-f]+)\s+"
        r"0x[0-9A-Fa-f]+\s+0x[0-9A-Fa-f]+\s+0x[0-9A-Fa-f]+\s+.*?"
        r"(0x[0-9A-Fa-f]+)\s*$"
    )
    for line in output.splitlines():
        match = pattern.match(line)
        if match:
            offset, virtual_address, alignment = (
                int(value, 16) for value in match.groups()
            )
            load_segments.append((offset, virtual_address, alignment))
    if not load_segments:
        raise ValueError(f"no ELF LOAD segments found in {path}")
    for offset, virtual_address, alignment in load_segments:
        if alignment < minimum_alignment:
            raise ValueError(
                f"{path} has LOAD alignment below {minimum_alignment} bytes"
            )
        if (virtual_address - offset) % minimum_alignment != 0:
            raise ValueError(
                f"{path} has a LOAD segment incompatible with "
                f"{minimum_alignment}-byte pages"
            )
    return {
        "needed": needed,
        "soname": sonames[0] if sonames else None,
        "build_id": build_ids[0].lower(),
        "minimum_load_alignment_bytes": min(
            alignment for _, _, alignment in load_segments
        ),
        "load_segments": len(load_segments),
        "page_size_compatibility": (
            "android-16k" if minimum_alignment == 16 * 1024 else "android-4k-arm32"
        ),
    }


def verify(root: Path, abi_baseline_path: Path, requested_readelf: Path | None) -> dict:
    root = root.resolve()
    report_root = root / "reports"
    manifest = load_json(report_root / "mdbx3-build-manifest.json")
    abi = load_json(report_root / "mdbx3-android-ffi-abi.json")
    artifacts = load_json(report_root / "mdbx3-android-artifacts.json")
    readelf = resolve_readelf(requested_readelf, artifacts)
    abi_baseline = load_json(abi_baseline_path)
    if abi_baseline.get("uniffi_contract_version") != 30:
        raise ValueError("ABI baseline contract version must be 30")
    if len(abi_baseline.get("required_symbols", [])) != 535:
        raise ValueError("ABI baseline must contain 535 required symbols")
    if len(abi_baseline.get("checksums", {})) != 237:
        raise ValueError("ABI baseline must contain 237 checksum entries")

    runtime = manifest.get("runtime", {})
    required_runtime = {
        "runtime_name": "MDBX3",
        "storage_format": "MDBX-2",
        "writable_storage_format": "MDBX-2",
        "current_schema_version": 17,
        "ffi_namespace": "mdbx_ffi",
        "native_library_name": "mdbx_ffi",
        "android_shared_object_name": EXPECTED_LIBRARY_NAME,
        "compatibility_profile": "mdbx3-mdbx2-drop-in-v1",
    }
    for key, expected in required_runtime.items():
        if runtime.get(key) != expected:
            raise ValueError(f"manifest runtime {key} must be {expected!r}")

    capabilities = manifest.get("capabilities", {})
    actual_enabled_storage = set(capabilities.get("enabled_storage_capability_ids", []))
    actual_disabled_storage = set(
        capabilities.get("disabled_optional_storage_capability_ids", [])
    )
    actual_enabled_sync = set(capabilities.get("enabled_sync_capability_ids", []))
    actual_disabled_sync = set(
        capabilities.get("disabled_optional_sync_capability_ids", [])
    )
    if actual_enabled_storage != EXPECTED_ENABLED_STORAGE_CAPABILITIES:
        raise ValueError(
            "MDBX3 enabled storage capabilities differ from the MDBX2 release profile: "
            f"missing={sorted(EXPECTED_ENABLED_STORAGE_CAPABILITIES - actual_enabled_storage)}, "
            f"unexpected={sorted(actual_enabled_storage - EXPECTED_ENABLED_STORAGE_CAPABILITIES)}"
        )
    if actual_disabled_storage != EXPECTED_DISABLED_STORAGE_CAPABILITIES:
        raise ValueError(
            "MDBX3 disabled storage capabilities differ from the MDBX2 release profile: "
            f"missing={sorted(EXPECTED_DISABLED_STORAGE_CAPABILITIES - actual_disabled_storage)}, "
            f"unexpected={sorted(actual_disabled_storage - EXPECTED_DISABLED_STORAGE_CAPABILITIES)}"
        )
    if actual_enabled_sync != EXPECTED_ENABLED_SYNC_CAPABILITIES:
        raise ValueError(
            "MDBX3 enabled sync capabilities differ from the MDBX2 release profile: "
            f"missing={sorted(EXPECTED_ENABLED_SYNC_CAPABILITIES - actual_enabled_sync)}, "
            f"unexpected={sorted(actual_enabled_sync - EXPECTED_ENABLED_SYNC_CAPABILITIES)}"
        )
    if actual_disabled_sync != EXPECTED_DISABLED_SYNC_CAPABILITIES:
        raise ValueError(
            "MDBX3 disabled sync capabilities differ from the MDBX2 release profile: "
            f"missing={sorted(EXPECTED_DISABLED_SYNC_CAPABILITIES - actual_disabled_sync)}, "
            f"unexpected={sorted(actual_disabled_sync - EXPECTED_DISABLED_SYNC_CAPABILITIES)}"
        )

    if abi.get("status") != "compatible":
        raise ValueError("Android ABI report is not compatible")
    if abi.get("required_symbols") != 535:
        raise ValueError("Android ABI report must require 535 symbols")
    abi_entries = {entry.get("label"): entry for entry in abi.get("artifacts", [])}
    if set(abi_entries) != set(EXPECTED_ABIS):
        raise ValueError("Android ABI report must contain exactly the three supported ABIs")
    for label, entry in abi_entries.items():
        if entry.get("status") != "compatible" or entry.get("verified_symbols") != 535:
            raise ValueError(f"ABI entry is incomplete for {label}")

    if artifacts.get("profile") != "mdbx-runtime-artifact-report-v1":
        raise ValueError("unexpected artifact report profile")
    if artifacts.get("build_profile") != "mdbx3-release":
        raise ValueError("artifact report was not produced by mdbx3-release")
    if artifacts.get("artifact_postprocess") != "llvm-strip --strip-all":
        raise ValueError("artifacts must be postprocessed with llvm-strip --strip-all")
    artifact_entries = {entry.get("label"): entry for entry in artifacts.get("artifacts", [])}
    if set(artifact_entries) != set(EXPECTED_ABIS):
        raise ValueError("artifact report must contain exactly the three supported ABIs")

    verified = []
    for label, (container, machine) in EXPECTED_ABIS.items():
        library = root / "android-jniLibs" / label / EXPECTED_LIBRARY_NAME
        if not library.is_file():
            raise ValueError(f"missing staged library: {library}")
        if library.name != EXPECTED_LIBRARY_NAME:
            raise ValueError(f"unexpected library basename for {label}")
        actual_identity = binary_identity(library)
        if actual_identity != (container, machine):
            raise ValueError(
                f"{label} has identity {actual_identity}, expected {(container, machine)}"
            )
        entry = artifact_entries[label]
        if entry.get("file_name") != EXPECTED_LIBRARY_NAME:
            raise ValueError(f"artifact report has an unexpected basename for {label}")
        if int(entry.get("bytes", -1)) != library.stat().st_size:
            raise ValueError(f"artifact size report does not match {label}")
        actual_hash = sha256(library)
        if entry.get("sha256") != actual_hash:
            raise ValueError(f"artifact SHA-256 does not match {label}")
        elf = inspect_elf_contract(
            library, readelf, EXPECTED_MIN_LOAD_ALIGNMENT[label]
        )
        if set(elf["needed"]) != EXPECTED_NEEDED:
            raise ValueError(
                f"{label} dynamic dependencies differ from the MDBX2 baseline: "
                f"{elf['needed']}"
            )
        if elf["soname"] is not None:
            raise ValueError(
                f"{label} must preserve the MDBX2 absent-SONAME contract"
            )
        verified.append(
            {
                "label": label,
                "bytes": library.stat().st_size,
                "sha256": actual_hash,
                **elf,
            }
        )

    build_ids = [entry["build_id"] for entry in verified]
    if len(build_ids) != len(set(build_ids)):
        raise ValueError("each Android ABI artifact must have a unique build ID")

    comparison = artifacts.get("comparison", {})
    totals = comparison.get("totals", {})
    if int(totals.get("byte_delta", 0)) >= 0:
        raise ValueError("MDBX3 raw artifact total must be smaller than the baseline")
    if int(totals.get("deflate_byte_delta", 0)) >= 0:
        raise ValueError("MDBX3 compressed artifact total must not regress")

    return {
        "profile": "mdbx3-release-gate-v1",
        "status": "ready",
        "runtime": "MDBX3",
        "storage_format": "MDBX-2",
        "verified_symbols": 535,
        "verified_checksums": 237,
        "uniffi_contract_version": 30,
        "capabilities": {
            "enabled_storage": len(actual_enabled_storage),
            "disabled_storage_optional": len(actual_disabled_storage),
            "enabled_sync": len(actual_enabled_sync),
            "disabled_sync_optional": len(actual_disabled_sync),
            "status": "compatible-with-mdbx2-profile",
        },
        "elf_inspector": str(readelf),
        "artifacts": verified,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=Path("target/mdbx3-android"),
        help="staged MDBX3 Android output root",
    )
    parser.add_argument("--output", type=Path)
    parser.add_argument("--readelf", type=Path)
    parser.add_argument(
        "--abi-baseline",
        type=Path,
        default=Path("crates/mdbx-ffi/abi/mdbx2-uniffi-bindings-v1.json"),
    )
    args = parser.parse_args()
    try:
        result = verify(args.root, args.abi_baseline, args.readelf)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"MDBX3 release gate failed: {error}", file=sys.stderr)
        return 1
    rendered = json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
