#!/usr/bin/env python3
"""Freeze and verify the MDBX2 UniFFI ABI used by MDBX3 drop-in builds."""

from __future__ import annotations

import argparse
import ctypes
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any


PROFILE = "mdbx2-uniffi-bindings-v1"
EXTERNAL_FUNCTION_PATTERN = re.compile(r"\bexternal\s+fun\s+([A-Za-z0-9_]+)\s*\(")
CHECKSUM_PATTERN = re.compile(
    r"if\s*\(lib\.(uniffi_[A-Za-z0-9_]+_checksum_[A-Za-z0-9_]+)\(\)\s*!=\s*"
    r"(\d+)\.toShort\(\)\)"
)
CONTRACT_VERSION_PATTERN = re.compile(r"val\s+bindings_contract_version\s*=\s*(\d+)")
COMPONENT_NAME_PATTERN = re.compile(r'componentName\s*=\s*"([^"]+)"')


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_kotlin_bindings(path: Path) -> dict[str, Any]:
    text = path.read_text(encoding="utf-8")
    required_symbols = sorted(set(EXTERNAL_FUNCTION_PATTERN.findall(text)))
    checksum_pairs = CHECKSUM_PATTERN.findall(text)
    checksums = {name: int(value) for name, value in checksum_pairs}

    contract_match = CONTRACT_VERSION_PATTERN.search(text)
    component_match = COMPONENT_NAME_PATTERN.search(text)
    if contract_match is None:
        raise ValueError("Kotlin bindings do not declare a UniFFI contract version")
    if component_match is None:
        raise ValueError("Kotlin bindings do not declare a component name")
    if not required_symbols:
        raise ValueError("Kotlin bindings contain no external native functions")
    if not checksums:
        raise ValueError("Kotlin bindings contain no API checksum checks")

    missing_checksum_symbols = sorted(set(checksums) - set(required_symbols))
    if missing_checksum_symbols:
        raise ValueError(
            "checksum functions are absent from native declarations: "
            + ", ".join(missing_checksum_symbols)
        )

    return {
        "profile": PROFILE,
        "component_name": component_match.group(1),
        "uniffi_contract_version": int(contract_match.group(1)),
        "generated_bindings_sha256": sha256_file(path),
        "required_symbols": required_symbols,
        "checksums": dict(sorted(checksums.items())),
    }


def write_snapshot(args: argparse.Namespace) -> int:
    bindings_path = Path(args.bindings).resolve()
    output_path = Path(args.output).resolve()
    snapshot = parse_kotlin_bindings(bindings_path)
    snapshot["source_commit"] = args.source_commit
    snapshot["source_library_name"] = args.source_library_name

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(snapshot, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(
        json.dumps(
            {
                "profile": snapshot["profile"],
                "symbols": len(snapshot["required_symbols"]),
                "checksums": len(snapshot["checksums"]),
                "output": str(output_path),
            },
            sort_keys=True,
        )
    )
    return 0


def load_baseline(path: Path) -> dict[str, Any]:
    baseline = json.loads(path.read_text(encoding="utf-8"))
    if baseline.get("profile") != PROFILE:
        raise ValueError(f"unsupported ABI baseline profile: {baseline.get('profile')!r}")
    if baseline.get("component_name") != "mdbx_ffi":
        raise ValueError("ABI baseline does not describe the mdbx_ffi component")
    if not isinstance(baseline.get("required_symbols"), list):
        raise ValueError("ABI baseline required_symbols must be a list")
    if not isinstance(baseline.get("checksums"), dict):
        raise ValueError("ABI baseline checksums must be an object")
    return baseline


def parse_labeled_library(value: str) -> tuple[str, Path]:
    label, separator, raw_path = value.partition("=")
    if not separator or not label or not raw_path:
        raise argparse.ArgumentTypeError("library must use LABEL=PATH")
    return label, Path(raw_path).resolve()


def emit_report(report: dict[str, Any], output: str | None, *, error: bool = False) -> None:
    rendered = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if output:
        output_path = Path(output).resolve()
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(rendered, encoding="utf-8")
    print(rendered, end="", file=sys.stderr if error else sys.stdout)


def require_symbol(library: ctypes.CDLL, name: str) -> Any:
    try:
        return getattr(library, name)
    except AttributeError as error:
        raise RuntimeError(f"required MDBX2 UniFFI symbol is missing: {name}") from error


def verify_library(args: argparse.Namespace) -> int:
    baseline_path = Path(args.baseline).resolve()
    library_path = Path(args.library).resolve()
    baseline = load_baseline(baseline_path)
    library = ctypes.CDLL(str(library_path))

    missing_symbols: list[str] = []
    for name in baseline["required_symbols"]:
        try:
            require_symbol(library, name)
        except RuntimeError:
            missing_symbols.append(name)

    contract_name = "ffi_mdbx_ffi_uniffi_contract_version"
    contract_function = require_symbol(library, contract_name)
    contract_function.argtypes = []
    contract_function.restype = ctypes.c_int32
    actual_contract = int(contract_function())
    expected_contract = int(baseline["uniffi_contract_version"])

    checksum_mismatches: list[dict[str, Any]] = []
    for name, expected_value in baseline["checksums"].items():
        function = require_symbol(library, name)
        function.argtypes = []
        function.restype = ctypes.c_uint16
        actual_value = int(function())
        if actual_value != int(expected_value):
            checksum_mismatches.append(
                {"symbol": name, "expected": int(expected_value), "actual": actual_value}
            )

    if missing_symbols or actual_contract != expected_contract or checksum_mismatches:
        report = {
            "profile": baseline["profile"],
            "library": str(library_path),
            "missing_symbols": missing_symbols,
            "contract_version": {
                "expected": expected_contract,
                "actual": actual_contract,
            },
            "checksum_mismatches": checksum_mismatches,
            "status": "incompatible",
        }
        emit_report(report, args.output, error=True)
        return 1

    emit_report(
        {
            "profile": baseline["profile"],
            "library": str(library_path),
            "verified_symbols": len(baseline["required_symbols"]),
            "verified_checksums": len(baseline["checksums"]),
            "uniffi_contract_version": actual_contract,
            "status": "compatible",
        },
        args.output,
    )
    return 0


def exported_symbols(nm_path: Path, library_path: Path) -> set[str]:
    completed = subprocess.run(
        [str(nm_path), "-D", "--defined-only", str(library_path)],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if completed.returncode != 0:
        detail = completed.stderr.strip() or completed.stdout.strip()
        raise RuntimeError(
            f"dynamic symbol inspection failed for {library_path}: {detail}"
        )

    symbols: set[str] = set()
    for line in completed.stdout.splitlines():
        fields = line.split()
        if fields:
            symbols.add(fields[-1].split("@", 1)[0])
    return symbols


def verify_exports(args: argparse.Namespace) -> int:
    baseline_path = Path(args.baseline).resolve()
    nm_path = Path(args.nm).resolve()
    baseline = load_baseline(baseline_path)
    libraries = args.library
    labels = [label for label, _ in libraries]
    if len(labels) != len(set(labels)):
        raise ValueError("library labels must be unique")

    required_symbols = set(baseline["required_symbols"])
    artifacts: list[dict[str, Any]] = []
    incompatible = False
    for label, library_path in libraries:
        symbols = exported_symbols(nm_path, library_path)
        missing_symbols = sorted(required_symbols - symbols)
        incompatible = incompatible or bool(missing_symbols)
        artifacts.append(
            {
                "label": label,
                "library": str(library_path),
                "exported_symbols": len(symbols),
                "verified_symbols": len(required_symbols) - len(missing_symbols),
                "missing_symbols": missing_symbols,
                "status": "incompatible" if missing_symbols else "compatible",
            }
        )

    report = {
        "profile": "mdbx2-uniffi-dynamic-exports-v1",
        "abi_baseline_profile": baseline["profile"],
        "abi_baseline_source_commit": baseline["source_commit"],
        "symbol_inspector": str(nm_path),
        "required_symbols": len(required_symbols),
        "artifacts": artifacts,
        "status": "incompatible" if incompatible else "compatible",
    }
    emit_report(report, args.output, error=incompatible)
    return 1 if incompatible else 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    snapshot = subparsers.add_parser(
        "snapshot", help="create an ABI baseline from generated Kotlin bindings"
    )
    snapshot.add_argument("--bindings", required=True)
    snapshot.add_argument("--output", required=True)
    snapshot.add_argument("--source-commit", required=True)
    snapshot.add_argument("--source-library-name", default="libmdbx_ffi.so")
    snapshot.set_defaults(handler=write_snapshot)

    verify = subparsers.add_parser(
        "verify", help="verify a current dynamic library against an ABI baseline"
    )
    verify.add_argument("--baseline", required=True)
    verify.add_argument("--library", required=True)
    verify.add_argument("--output")
    verify.set_defaults(handler=verify_library)

    exports = subparsers.add_parser(
        "verify-exports",
        help="verify cross-compiled dynamic exports with llvm-nm",
    )
    exports.add_argument("--baseline", required=True)
    exports.add_argument("--nm", required=True)
    exports.add_argument(
        "--library",
        action="append",
        required=True,
        type=parse_labeled_library,
    )
    exports.add_argument("--output")
    exports.set_defaults(handler=verify_exports)
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    try:
        return int(args.handler(args))
    except (OSError, ValueError, RuntimeError) as error:
        print(f"MDBX FFI ABI verification failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
