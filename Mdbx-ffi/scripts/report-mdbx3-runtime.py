#!/usr/bin/env python3
"""Create a reproducible MDBX3 runtime artifact and size report."""

from __future__ import annotations

import argparse
import hashlib
import json
import platform
import sys
import zlib
from pathlib import Path
from typing import Any


PROFILE = "mdbx-runtime-artifact-report-v1"
ELF_MACHINE_NAMES = {
    40: "arm",
    62: "x86_64",
    183: "aarch64",
}
ANDROID_ABI_MACHINES = {
    "armeabi-v7a": "arm",
    "arm64-v8a": "aarch64",
    "x86_64": "x86_64",
}


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def parse_artifact(value: str) -> tuple[str, Path]:
    label, separator, raw_path = value.partition("=")
    if not separator or not label or not raw_path:
        raise argparse.ArgumentTypeError("artifact must use LABEL=PATH")
    return label, Path(raw_path).resolve()


def parse_named_value(value: str) -> tuple[str, str]:
    label, separator, raw_value = value.partition("=")
    if not separator or not label or not raw_value:
        raise argparse.ArgumentTypeError("value must use NAME=VALUE")
    return label, raw_value


def parse_input_file(value: str) -> tuple[str, Path]:
    label, raw_path = parse_named_value(value)
    return label, Path(raw_path).resolve()


def unique_mapping(values: list[tuple[str, Any]], description: str) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for label, value in values:
        if label in result:
            raise ValueError(f"duplicate {description} label: {label}")
        result[label] = value
    return result


def inspect_binary(data: bytes) -> dict[str, Any]:
    if data.startswith(b"\x7fELF"):
        if len(data) < 20:
            raise ValueError("ELF artifact is truncated")
        elf_class = {1: 32, 2: 64}.get(data[4])
        byte_order = {1: "little", 2: "big"}.get(data[5])
        if elf_class is None or byte_order is None:
            raise ValueError("ELF artifact has an unsupported class or byte order")
        machine = int.from_bytes(data[18:20], byte_order)
        return {
            "container": "elf",
            "bits": elf_class,
            "byte_order": byte_order,
            "machine": ELF_MACHINE_NAMES.get(machine, f"elf-machine-{machine}"),
            "machine_id": machine,
        }

    if data.startswith(b"MZ"):
        if len(data) < 64:
            raise ValueError("PE artifact is truncated")
        pe_offset = int.from_bytes(data[60:64], "little")
        if pe_offset + 6 > len(data) or data[pe_offset : pe_offset + 4] != b"PE\0\0":
            raise ValueError("PE artifact has an invalid header")
        machine = int.from_bytes(data[pe_offset + 4 : pe_offset + 6], "little")
        machine_names = {0x14C: "x86", 0x8664: "x86_64", 0xAA64: "aarch64"}
        return {
            "container": "pe",
            "bits": 64 if machine in {0x8664, 0xAA64} else 32,
            "byte_order": "little",
            "machine": machine_names.get(machine, f"pe-machine-{machine}"),
            "machine_id": machine,
        }

    if data[:4] in {
        b"\xcf\xfa\xed\xfe",
        b"\xfe\xed\xfa\xcf",
        b"\xca\xfe\xba\xbe",
        b"\xbe\xba\xfe\xca",
    }:
        return {"container": "mach-o", "bits": None, "byte_order": None, "machine": None}

    return {"container": "unknown", "bits": None, "byte_order": None, "machine": None}


def inspect_artifact(label: str, path: Path) -> dict[str, Any]:
    data = path.read_bytes()
    binary = inspect_binary(data)
    expected_machine = ANDROID_ABI_MACHINES.get(label)
    if expected_machine is not None:
        if binary["container"] != "elf":
            raise ValueError(f"{label} artifact is not ELF: {path}")
        if binary["machine"] != expected_machine:
            raise ValueError(
                f"{label} expects {expected_machine}, got {binary['machine']}: {path}"
            )

    return {
        "label": label,
        "path": str(path),
        "file_name": path.name,
        "bytes": len(data),
        "deflate_bytes": len(zlib.compress(data, level=9)),
        "sha256": sha256_bytes(data),
        "binary": binary,
    }


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"JSON root must be an object: {path}")
    return value


def baseline_artifacts(baseline: dict[str, Any]) -> dict[str, dict[str, Any]]:
    artifacts = baseline.get("artifacts", [])
    if not isinstance(artifacts, list):
        raise ValueError("baseline artifacts must be a list")
    return {
        artifact["label"]: artifact
        for artifact in artifacts
        if isinstance(artifact, dict) and isinstance(artifact.get("label"), str)
    }


def artifact_totals(artifacts: list[dict[str, Any]]) -> dict[str, int]:
    return {
        "bytes": sum(int(artifact["bytes"]) for artifact in artifacts),
        "deflate_bytes": sum(int(artifact["deflate_bytes"]) for artifact in artifacts),
    }


def add_comparison(report: dict[str, Any], baseline: dict[str, Any]) -> None:
    if baseline.get("target_platform") != report.get("target_platform"):
        raise ValueError("baseline target platform does not match current report")
    if baseline.get("artifact_postprocess") != report.get("artifact_postprocess"):
        raise ValueError("baseline artifact postprocess does not match current report")
    for key in ("rustc", "cargo", "cargo_ndk", "ndk", "linker"):
        if baseline.get("toolchain", {}).get(key) != report.get("toolchain", {}).get(key):
            raise ValueError(f"baseline {key} toolchain does not match current report")
    previous = baseline_artifacts(baseline)
    current_labels = {artifact["label"] for artifact in report["artifacts"]}
    if set(previous) != current_labels:
        raise ValueError("baseline artifact labels do not match current report")

    comparisons: list[dict[str, Any]] = []
    for artifact in report["artifacts"]:
        old = previous.get(artifact["label"])
        if old is None:
            continue
        old_bytes = int(old["bytes"])
        old_deflate = int(old["deflate_bytes"])
        comparisons.append(
            {
                "label": artifact["label"],
                "baseline_bytes": old_bytes,
                "current_bytes": artifact["bytes"],
                "byte_delta": artifact["bytes"] - old_bytes,
                "byte_ratio": artifact["bytes"] / old_bytes if old_bytes else None,
                "baseline_deflate_bytes": old_deflate,
                "current_deflate_bytes": artifact["deflate_bytes"],
                "deflate_byte_delta": artifact["deflate_bytes"] - old_deflate,
                "deflate_byte_ratio": (
                    artifact["deflate_bytes"] / old_deflate if old_deflate else None
                ),
            }
        )
    baseline_totals = artifact_totals(list(previous.values()))
    current_totals = artifact_totals(report["artifacts"])
    report["comparison"] = {
        "baseline": {
            "source_commit": baseline.get("source_commit"),
            "build_profile": baseline.get("build_profile"),
            "manifest_sha256": baseline.get("manifest_sha256"),
            "cargo_lock_sha256": baseline.get("inputs", {})
            .get("cargo_lock", {})
            .get("sha256"),
        },
        "current": {
            "source_commit": report.get("source_commit"),
            "build_profile": report.get("build_profile"),
            "manifest_sha256": report.get("manifest_sha256"),
            "cargo_lock_sha256": report.get("inputs", {})
            .get("cargo_lock", {})
            .get("sha256"),
        },
        "artifacts": comparisons,
        "totals": {
            "baseline_bytes": baseline_totals["bytes"],
            "current_bytes": current_totals["bytes"],
            "byte_delta": current_totals["bytes"] - baseline_totals["bytes"],
            "byte_ratio": (
                current_totals["bytes"] / baseline_totals["bytes"]
                if baseline_totals["bytes"]
                else None
            ),
            "baseline_deflate_bytes": baseline_totals["deflate_bytes"],
            "current_deflate_bytes": current_totals["deflate_bytes"],
            "deflate_byte_delta": (
                current_totals["deflate_bytes"] - baseline_totals["deflate_bytes"]
            ),
            "deflate_byte_ratio": (
                current_totals["deflate_bytes"] / baseline_totals["deflate_bytes"]
                if baseline_totals["deflate_bytes"]
                else None
            ),
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact", action="append", required=True, type=parse_artifact)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--baseline")
    parser.add_argument("--abi-report")
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--rustc-version", required=True)
    parser.add_argument("--cargo-version", required=True)
    parser.add_argument("--build-profile", required=True)
    parser.add_argument("--target-platform", required=True)
    parser.add_argument("--artifact-postprocess", default="none")
    parser.add_argument(
        "--source-tree-state",
        choices=("clean", "dirty", "unknown"),
        default="unknown",
    )
    parser.add_argument(
        "--toolchain-detail",
        action="append",
        default=[],
        type=parse_named_value,
    )
    parser.add_argument(
        "--input-file",
        action="append",
        default=[],
        type=parse_input_file,
    )
    args = parser.parse_args()

    try:
        artifacts = [inspect_artifact(label, path) for label, path in args.artifact]
        labels = [artifact["label"] for artifact in artifacts]
        if len(labels) != len(set(labels)):
            raise ValueError("artifact labels must be unique")

        toolchain_details = unique_mapping(args.toolchain_detail, "toolchain detail")
        input_paths = unique_mapping(args.input_file, "input file")
        inputs = {
            label: {
                "path": str(path),
                "bytes": path.stat().st_size,
                "sha256": sha256_bytes(path.read_bytes()),
            }
            for label, path in input_paths.items()
        }

        manifest_path = Path(args.manifest).resolve()
        report: dict[str, Any] = {
            "profile": PROFILE,
            "source_commit": args.source_commit,
            "source_tree_state": args.source_tree_state,
            "build_profile": args.build_profile,
            "target_platform": args.target_platform,
            "artifact_postprocess": args.artifact_postprocess,
            "toolchain": {
                **toolchain_details,
                "rustc": args.rustc_version,
                "cargo": args.cargo_version,
                "python": platform.python_version(),
                "host": platform.platform(),
            },
            "inputs": inputs,
            "manifest": load_json(manifest_path),
            "manifest_sha256": sha256_bytes(manifest_path.read_bytes()),
            "artifacts": artifacts,
            "totals": artifact_totals(artifacts),
        }
        if args.abi_report:
            abi_report_path = Path(args.abi_report).resolve()
            abi_report = load_json(abi_report_path)
            if abi_report.get("status") != "compatible":
                raise ValueError("FFI ABI export report is not compatible")
            report["ffi_abi"] = abi_report
            report["ffi_abi_report_sha256"] = sha256_bytes(abi_report_path.read_bytes())
        if args.baseline:
            add_comparison(report, load_json(Path(args.baseline).resolve()))

        output_path = Path(args.output).resolve()
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(
            json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        print(json.dumps(report, ensure_ascii=False, sort_keys=True))
        return 0
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(f"MDBX3 artifact report failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
