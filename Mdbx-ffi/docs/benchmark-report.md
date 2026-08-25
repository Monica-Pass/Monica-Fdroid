# MDBX Benchmark Report

本报告记录 MDBX storage benchmark harness 的 encrypted 与 compatibility 两组可复现 release 测量。encrypted 是 CLI 默认模式：每个 workload 的准备阶段配置默认 Multi password，附加经过验证的 keyring、active key epoch 和 session，业务字段使用 `MDBXFE2` envelope。compatibility 保留给旧测试路径和同机开销参照。

## Run Metadata

| Field | Value |
| --- | --- |
| Report format | `mdbx-benchmark-report-v1` |
| Source commit | `dac50d2744881310fe132d50deab42275190fdc6` |
| Profile | `release` |
| Iterations | `20` per operation |
| OS | Windows 11 Insider Preview, build `27919` |
| CPU | 12th Gen Intel(R) Core(TM) i7-12800HX, 16 cores / 24 logical processors |
| Rust | `rustc 1.86.0 (05f9846f8 2025-03-31)` |
| Primary mode | `encrypted`, default Multi password |
| Reference mode | `compatibility`, no attached keyring |
| Cargo command | `cargo run --release -p mdbx-cli -- benchmark --iterations 20 --mode <mode> --output <report.json>` |

原始 JSON 位于 `.codex-tasks/20260722-encrypted-benchmark/raw/encrypted-dac50d2.json` 和 `compatibility-dac50d2.json`，按仓库规则不进入 Git。每份报告记录 `storage_mode`、`field_encryption`、`unlock_policy`、commit 和主机环境。

## Results

主要结果采用 encrypted 模式。`avg us/op` 是单次运行的 `duration_us / ops`；`output bytes/op` 具有操作特定语义，不代表存储放大率。

| Operation | Ops | Avg us/op | Ops/s | Output bytes/op |
| --- | ---: | ---: | ---: | ---: |
| `vault_create` | 20 | 107,262.460 | 9.3 | 0 |
| `entry_create` | 20 | 517.660 | 1,931.8 | 77.5 |
| `entry_edit_small` | 20 | 440.210 | 2,271.6 | 32.5 |
| `search_by_title` | 20 | 116.810 | 8,561.3 | 170.5 |
| `attachment_create_small` | 20 | 944.460 | 1,058.8 | 27.5 |
| `attachment_rename` | 20 | 382.380 | 2,615.2 | 12.5 |
| `attachment_replace_1k` | 20 | 541.670 | 1,846.1 | 1,024.0 |
| `snapshot_create` | 20 | 738.140 | 1,354.7 | 50,186.0 |
| `vault_open` | 20 | 99,972.180 | 10.0 | 0 |
| `vault_compaction` | 20 | 17,868.020 | 56.0 | 1,083,801.6 |
| `sync_delta_materialize` | 20 | 96.840 | 10,326.3 | 5,813.95 |

encrypted suite 墙钟时间为 `6,584.010 ms`。其中每个 workload 的一次性 Multi password 配置位于操作计时区间外，但包含在 suite 墙钟时间内；`vault_create` 和 `vault_open` 明确把 KDF 配置或解锁成本计入每次操作。

## Compatibility Reference

同一 commit、主机和数据集上的 compatibility 结果用于观察字段加密与完整性处理的相对成本。倍率为 `encrypted avg / compatibility avg`，小于 1 的 compaction 属于单次主机噪声，不能解释为加密加速。

| Operation | Encrypted us/op | Compatibility us/op | Ratio |
| --- | ---: | ---: | ---: |
| `vault_create` | 107,262.460 | 5,864.830 | 18.29x |
| `entry_create` | 517.660 | 392.240 | 1.32x |
| `entry_edit_small` | 440.210 | 322.250 | 1.37x |
| `search_by_title` | 116.810 | 92.970 | 1.26x |
| `attachment_create_small` | 944.460 | 701.500 | 1.35x |
| `attachment_rename` | 382.380 | 314.130 | 1.22x |
| `attachment_replace_1k` | 541.670 | 365.260 | 1.48x |
| `snapshot_create` | 738.140 | 466.320 | 1.58x |
| `vault_open` | 99,972.180 | 6,165.860 | 16.21x |
| `vault_compaction` | 17,868.020 | 19,842.790 | 0.90x |
| `sync_delta_materialize` | 96.840 | 68.050 | 1.42x |

compatibility suite 墙钟时间为 `1,773.442 ms`。

## Dataset

The harness uses fixed, small datasets so smoke tests remain bounded:

- Search creates 100 projects and indexes 50 titles.
- Snapshot creates 20 projects with one login entry each.
- Compaction creates a synthetic 1,048,576-byte fragmentation table, deletes half the rows, then measures `PRAGMA wal_checkpoint(TRUNCATE); VACUUM;`.
- Sync delta materialization selects one existing entry and its commit, then measures envelope materialization and JSON encoding.
- Attachment replacement writes a 1 KiB inline payload per operation.

## Interpretation Limits

- This is one wall-clock run on one Windows host. No warm-up phase, percentile distribution, confidence interval, concurrency test, or cross-machine comparison is included.
- encrypted 模式使用正式 Multi password、verified keyring 和 epoch-tagged field encryption；它不包含 UI、网络、硬件密钥提示、跨语言 binding 或客户端序列化成本。
- workload 准备阶段的密码配置不计入普通写入项目；`vault_create` 与 `vault_open` 则有意包含 KDF 成本。
- `vault_create` and `vault_open` include file/schema setup and migration checks appropriate to the harness. They are not isolated SQLite open calls.
- `vault_compaction` measures maintenance against the synthetic fragmentation table. It does not predict compaction time for a particular user's vault.
- `output_bytes` reports encoded sync payload bytes or logical content/result bytes selected by the operation. It does not claim on-disk encryption overhead or WAL growth.
- KDBX values in `kdbx_reference_numbers()` remain estimates and are excluded from this measured report.

## Reproduction

CLI 默认运行 encrypted 模式并保留文本输出。JSON 可以打印或写入文件；compatibility 必须显式选择：

```powershell
cargo run --release -p mdbx-cli -- benchmark --iterations 20 --json
cargo run --release -p mdbx-cli -- benchmark --iterations 20 --mode encrypted --output .codex-tasks/encrypted.json
cargo run --release -p mdbx-cli -- benchmark --iterations 20 --mode compatibility --output .codex-tasks/compatibility.json
```

The JSON document contains the source commit, host metadata, fixed dataset description, per-operation durations, successful operation counts, output bytes, and the same limitations listed here.
