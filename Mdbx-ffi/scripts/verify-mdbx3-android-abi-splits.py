#!/usr/bin/env python3
"""Verify thin and universal MDBX3 Android ABI packages."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import zipfile
from pathlib import Path


EXPECTED_ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")
EXPECTED_MACHINES = {
    "arm64-v8a": "aarch64",
    "armeabi-v7a": "arm",
    "x86_64": "x86_64",
}
EXPECTED_MIN_LOAD_ALIGNMENT = {
    "arm64-v8a": 16 * 1024,
    "armeabi-v7a": 4 * 1024,
    "x86_64": 16 * 1024,
}
LIBRARY_NAME = "libmdbx_ffi.so"
REPORT_NAMES = (
    "mdbx3-build-manifest.json",
    "mdbx3-android-ffi-abi.json",
    "mdbx3-android-artifacts.json",
    "mdbx3-release-gate.json",
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"JSON root must be an object: {path}")
    return value


def package_members(path: Path) -> set[str]:
    with zipfile.ZipFile(path) as archive:
        if archive.testzip() is not None:
            raise ValueError(f"ZIP has a corrupt member: {path}")
        return {name.rstrip("/") for name in archive.namelist() if not name.endswith("/")}


def binary_machine(data: bytes) -> str:
    if not data.startswith(b"\x7fELF") or len(data) < 20:
        raise ValueError("native package member is not a complete ELF shared object")
    byte_order = "little" if data[5] == 1 else "big" if data[5] == 2 else None
    if byte_order is None:
        raise ValueError("native package member has an unsupported ELF byte order")
    machine_id = int.from_bytes(data[18:20], byte_order)
    return {40: "arm", 62: "x86_64", 183: "aarch64"}.get(
        machine_id, f"elf-machine-{machine_id}"
    )


def canonical_reports(package: Path) -> tuple[dict, dict, dict, dict, dict[str, bytes]]:
    with zipfile.ZipFile(package) as archive:
        raw = {
            name: archive.read(f"reports/{name}") for name in REPORT_NAMES
        }
    parsed = {
        name: json.loads(content.decode("utf-8")) for name, content in raw.items()
    }
    return (
        parsed["mdbx3-build-manifest.json"],
        parsed["mdbx3-android-ffi-abi.json"],
        parsed["mdbx3-android-artifacts.json"],
        parsed["mdbx3-release-gate.json"],
        raw,
    )


def validate_release_reports(
    manifest: dict, abi_report: dict, artifact_report: dict, gate: dict
) -> dict[str, dict]:
    runtime = manifest.get("runtime", {})
    if (
        runtime.get("runtime_name") != "MDBX3"
        or runtime.get("storage_format") != "MDBX-2"
        or runtime.get("android_shared_object_name") != LIBRARY_NAME
    ):
        raise ValueError("packaged runtime manifest is not MDBX3/MDBX-2 compatible")
    if abi_report.get("status") != "compatible" or gate.get("status") != "ready":
        raise ValueError("packaged ABI or release report is not ready")
    if gate.get("verified_symbols") != 535 or gate.get("verified_checksums") != 237:
        raise ValueError("packaged release report does not prove the frozen MDBX2 ABI")
    gate_artifacts = {
        entry.get("label"): entry for entry in gate.get("artifacts", [])
    }
    if set(gate_artifacts) != set(EXPECTED_ABIS):
        raise ValueError("packaged release gate does not contain all supported ABIs")
    build_ids = []
    for abi, entry in gate_artifacts.items():
        if entry.get("soname") is not None:
            raise ValueError(f"packaged release gate changed the SONAME contract for {abi}")
        if set(entry.get("needed", [])) != {"libc.so", "libdl.so", "libm.so"}:
            raise ValueError(f"packaged release gate has unexpected dependencies for {abi}")
        if int(entry.get("minimum_load_alignment_bytes", 0)) < EXPECTED_MIN_LOAD_ALIGNMENT[abi]:
            raise ValueError(f"packaged release gate has insufficient alignment for {abi}")
        expected_page_profile = (
            "android-16k" if abi != "armeabi-v7a" else "android-4k-arm32"
        )
        if entry.get("page_size_compatibility") != expected_page_profile:
            raise ValueError(f"packaged release gate has a wrong page profile for {abi}")
        build_id = entry.get("build_id")
        if not isinstance(build_id, str) or len(build_id) != 40:
            raise ValueError(f"packaged release gate lacks a SHA-1 build ID for {abi}")
        build_ids.append(build_id)
    if len(build_ids) != len(set(build_ids)):
        raise ValueError("packaged release gate build IDs are not unique")
    artifacts = {
        entry.get("label"): entry for entry in artifact_report.get("artifacts", [])
    }
    if set(artifacts) != set(EXPECTED_ABIS):
        raise ValueError("packaged artifact report does not contain all supported ABIs")
    return artifacts


def require_reports(members: set[str]) -> None:
    expected = {f"reports/{name}" for name in REPORT_NAMES}
    missing = sorted(expected - members)
    if missing:
        raise ValueError(f"package is missing reports: {', '.join(missing)}")


def verify_thin_package(
    root: Path,
    abi: str,
    record: dict,
    canonical_report_bytes: dict[str, bytes],
    canonical_artifacts: dict[str, dict],
) -> dict:
    package = root / record["package"]
    library = root / record["library"]
    if not package.is_file() or not library.is_file():
        raise ValueError(f"missing thin asset for {abi}")
    if package.stat().st_size != int(record["package_bytes"]):
        raise ValueError(f"package size mismatch for {abi}")
    if sha256(package) != record["package_sha256"]:
        raise ValueError(f"package hash mismatch for {abi}")
    if library.stat().st_size != int(record["library_bytes"]):
        raise ValueError(f"library size mismatch for {abi}")
    if sha256(library) != record["library_sha256"]:
        raise ValueError(f"library hash mismatch for {abi}")
    if record.get("install_path") != f"android-jniLibs/{abi}/{LIBRARY_NAME}":
        raise ValueError(f"install path mismatch for {abi}")

    members = package_members(package)
    require_reports(members)
    expected_library = f"android-jniLibs/{abi}/{LIBRARY_NAME}"
    if expected_library not in members:
        raise ValueError(f"thin package is missing {expected_library}")
    native_members = {
        member for member in members if member.startswith("android-jniLibs/")
    }
    if native_members != {expected_library}:
        raise ValueError(f"thin package contains unexpected native members for {abi}")
    with zipfile.ZipFile(package) as archive:
        packaged_library = archive.read(expected_library)
        for report_name, expected in canonical_report_bytes.items():
            if archive.read(f"reports/{report_name}") != expected:
                raise ValueError(f"thin package report mismatch for {abi}: {report_name}")
    if packaged_library != library.read_bytes():
        raise ValueError(f"standalone and packaged libraries differ for {abi}")
    if binary_machine(packaged_library) != EXPECTED_MACHINES[abi]:
        raise ValueError(f"ELF machine mismatch for {abi}")
    artifact = canonical_artifacts[abi]
    if len(packaged_library) != int(artifact.get("bytes", -1)):
        raise ValueError(f"artifact report size mismatch for {abi}")
    if hashlib.sha256(packaged_library).hexdigest() != artifact.get("sha256"):
        raise ValueError(f"artifact report hash mismatch for {abi}")
    return {"abi": abi, "bytes": package.stat().st_size, "sha256": sha256(package)}


def verify_universal_package(
    root: Path, index: dict, canonical_artifacts: dict[str, dict]
) -> dict:
    record = index["universal_package"]
    package = root / record["asset"]
    if not package.is_file():
        raise ValueError("missing universal package")
    if package.stat().st_size != int(record["bytes"]):
        raise ValueError("universal package size mismatch")
    if sha256(package) != record["sha256"]:
        raise ValueError("universal package hash mismatch")
    members = package_members(package)
    require_reports(members)
    expected = {
        f"android-jniLibs/{abi}/{LIBRARY_NAME}" for abi in EXPECTED_ABIS
    }
    native_members = {
        member for member in members if member.startswith("android-jniLibs/")
    }
    if native_members != expected:
        raise ValueError("universal package does not contain exactly the three ABI libraries")
    with zipfile.ZipFile(package) as archive:
        for abi in EXPECTED_ABIS:
            data = archive.read(f"android-jniLibs/{abi}/{LIBRARY_NAME}")
            artifact = canonical_artifacts[abi]
            if binary_machine(data) != EXPECTED_MACHINES[abi]:
                raise ValueError(f"universal package ELF machine mismatch for {abi}")
            if len(data) != int(artifact.get("bytes", -1)):
                raise ValueError(f"universal package size mismatch for {abi}")
            if hashlib.sha256(data).hexdigest() != artifact.get("sha256"):
                raise ValueError(f"universal package hash mismatch for {abi}")
    return {"bytes": package.stat().st_size, "sha256": sha256(package)}


def verify(root: Path) -> dict:
    root = root.resolve()
    index = load_json(root / "mdbx3-android-abi-index.json")
    if index.get("profile") != "mdbx3-android-abi-split-v1":
        raise ValueError("unexpected ABI split profile")
    if index.get("runtime") != "MDBX3" or index.get("storage_format") != "MDBX-2":
        raise ValueError("ABI split runtime/storage identity is invalid")
    if index.get("library_name") != LIBRARY_NAME:
        raise ValueError("ABI split library basename is invalid")
    if index.get("selection") != "install exactly one ABI package per Android process":
        raise ValueError("ABI split selection rule is invalid")

    records = {record.get("abi"): record for record in index.get("abis", [])}
    if set(records) != set(EXPECTED_ABIS):
        raise ValueError("ABI split index must contain exactly the supported ABIs")
    universal_path = root / index["universal_package"]["asset"]
    members = package_members(universal_path)
    require_reports(members)
    manifest, abi_report, artifact_report, gate, report_bytes = canonical_reports(
        universal_path
    )
    artifacts = validate_release_reports(manifest, abi_report, artifact_report, gate)
    source = index.get("source", {})
    if source.get("commit") != artifact_report.get("source_commit"):
        raise ValueError("ABI split index source commit does not match artifact report")
    if source.get("manifest_sha256") != artifact_report.get("manifest_sha256"):
        raise ValueError("ABI split index manifest hash does not match artifact report")
    thin = [
        verify_thin_package(root, abi, records[abi], report_bytes, artifacts)
        for abi in EXPECTED_ABIS
    ]
    universal = verify_universal_package(root, index, artifacts)
    return {
        "profile": "mdbx3-android-abi-split-v1",
        "status": "ready",
        "runtime": "MDBX3",
        "storage_format": "MDBX-2",
        "thin_packages": thin,
        "universal_package": universal,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root", type=Path, default=Path("target/mdbx3-android-abi-splits")
    )
    args = parser.parse_args()
    try:
        result = verify(args.root)
    except (OSError, ValueError, json.JSONDecodeError, zipfile.BadZipFile) as error:
        print(f"MDBX3 ABI split verification failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
