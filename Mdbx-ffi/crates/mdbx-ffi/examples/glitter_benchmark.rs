//! Same-process, same-data comparison of real native encryption and title reads.
//! Run: cargo run --release -p mdbx-ffi --example glitter_benchmark -- 3000 3
//! Power capabilities are synthetic; Sky/Glitter use an ordinary Standard client.

use mdbx_ffi::{
    create_vault_with_password_security_key,
    open_vault_with_password_security_key_and_device_context, MdbxDeviceAssurance,
    MdbxDeviceContext, MdbxTigaMode, MdbxVault, MdbxWriteCommand,
};
use serde_json::{json, Value};
use std::time::Instant;
use uuid::Uuid;

fn device(mode: MdbxTigaMode) -> MdbxDeviceContext {
    let power = matches!(mode, MdbxTigaMode::Power);
    MdbxDeviceContext {
        assurance: if power {
            MdbxDeviceAssurance::TrustedHardware
        } else {
            MdbxDeviceAssurance::Standard
        },
        secure_clipboard_available: power,
        screen_capture_protection_available: power,
        secure_temp_files_available: false,
    }
}

fn all_titles(vault: &MdbxVault, collection: &str, expected: usize) {
    let mut cursor = None;
    let mut seen = 0;
    loop {
        let page = vault
            .list_object_summaries(collection.to_owned(), Some("login".to_owned()), 200, cursor)
            .unwrap();
        seen += page.items.len();
        cursor = page.next_cursor;
        if cursor.is_none() {
            break;
        }
    }
    assert_eq!(seen, expected);
}

fn micros(start: Instant) -> f64 {
    start.elapsed().as_secs_f64() * 1_000_000.0
}

fn percentile(values: &[f64], fraction: f64) -> f64 {
    let mut ordered = values.to_vec();
    ordered.sort_by(f64::total_cmp);
    ordered[((ordered.len() as f64 * fraction).ceil() as usize)
        .saturating_sub(1)
        .min(ordered.len() - 1)]
}

fn scenario(mode: MdbxTigaMode, count: usize, round: usize) -> Value {
    let dir = tempfile::tempdir().unwrap();
    let path = dir
        .path()
        .join("synthetic-benchmark.mdbx")
        .to_string_lossy()
        .into_owned();
    let password = "Synthetic Glitter benchmark passphrase 2026!";
    // Fixed synthetic factor keeps workload equivalent; never a real credential.
    let factor = vec![0xA5; 32];
    let device_id = "glitter-native-benchmark";
    let start = Instant::now();
    let vault = create_vault_with_password_security_key(
        path.clone(),
        password.to_owned(),
        factor.clone(),
        device_id.to_owned(),
        mode,
        device(mode),
    )
    .unwrap();
    let create_us = micros(start);
    let collection = vault
        .create_project("Synthetic performance collection".to_owned())
        .unwrap()
        .project_id;
    let ids: Vec<String> = (0..count).map(|_| Uuid::new_v4().to_string()).collect();
    let start = Instant::now();
    for (chunk_index, chunk) in ids.chunks(200).enumerate() {
        let commands = chunk.iter().enumerate().map(|(i, id)| MdbxWriteCommand::CreateEntry {
            entry_id: id.clone(), project_id: collection.clone(), entry_type: "login".to_owned(),
            title: format!("Synthetic service {:06} · account", chunk_index * 200 + i),
            payload_json: r#"{"username":"synthetic-user","password":"synthetic-secret","url":"https://example.invalid"}"#.to_owned(),
        }).collect();
        vault
            .execute_write_operation(
                Uuid::new_v4().to_string(),
                "benchmark-create".to_owned(),
                commands,
            )
            .unwrap();
    }
    let batch_create_us = micros(start);
    drop(vault);
    let start = Instant::now();
    let vault = open_vault_with_password_security_key_and_device_context(
        path.clone(),
        password.to_owned(),
        factor,
        device_id.to_owned(),
        device(mode),
    )
    .unwrap();
    let full_unlock_us = micros(start);
    let start = Instant::now();
    all_titles(&vault, &collection, count);
    let first_scan_us = micros(start);
    let mut scans = Vec::new();
    for _ in 0..20 {
        let start = Instant::now();
        all_titles(&vault, &collection, count);
        scans.push(micros(start));
    }
    let cache = vault.metadata_cache_stats().unwrap();
    let start = Instant::now();
    let commands = ids
        .iter()
        .take(50)
        .map(|id| MdbxWriteCommand::UpdateEntry {
            entry_id: id.clone(),
            project_id: collection.clone(),
            entry_type: "login".to_owned(),
            title: "Updated synthetic title".to_owned(),
            payload_json: r#"{"username":"synthetic-user","password":"updated-synthetic-secret"}"#
                .to_owned(),
        })
        .collect();
    vault
        .execute_write_operation(
            Uuid::new_v4().to_string(),
            "benchmark-update".to_owned(),
            commands,
        )
        .unwrap();
    let update_50_us = micros(start);
    assert_eq!(
        vault
            .get_object_summary(ids[0].clone())
            .unwrap()
            .unwrap()
            .title,
        "Updated synthetic title"
    );
    let disclosure = vault
        .reveal_object_with_device_context(ids[0].clone(), device(mode))
        .unwrap();
    assert!(
        disclosure.object.is_some(),
        "updated secret must remain readable through policy authorization"
    );
    let size = std::fs::metadata(&path).unwrap().len();
    drop(vault);
    json!({
        "mode": format!("{mode:?}"), "round": round, "entries": count,
        "create_us": create_us, "full_unlock_us": full_unlock_us,
        "batch_create_us": batch_create_us, "first_scan_us": first_scan_us,
        "warm_scan_p50_us": percentile(&scans, 0.5), "warm_scan_p95_us": percentile(&scans, 0.95),
        "warm_scan_samples_us": scans, "update_50_us": update_50_us,
        "cache_hits": cache.hits, "cache_misses": cache.misses,
        "cache_entries": cache.entries, "cache_retained_bytes": cache.retained_bytes,
        "cache_byte_limit": cache.byte_limit, "database_bytes_before_checkpoint": size,
        "read_after_update_verified": true, "authorized_secret_read_verified": true,
    })
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let count: usize = args.get(1).map(|s| s.parse().unwrap()).unwrap_or(3000);
    let rounds: usize = args.get(2).map(|s| s.parse().unwrap()).unwrap_or(3);
    assert!((50..=10_000).contains(&count) && (1..=5).contains(&rounds));
    println!(
        "{}",
        json!({"benchmark":"glitter-native-v1", "hardware_validation":false,
        "device_context":"Standard portable Sky/Glitter; synthetic Power capabilities; not hardware attestation",
        "factors":"same combined password and 32-byte factor on all profiles",
        "profile":"release", "os":std::env::consts::OS, "arch":std::env::consts::ARCH,
        "fresh_auth_extended":false, "scope":"paged title listing, batch writes, full unlock; excludes UI and network"})
    );
    let modes = [
        MdbxTigaMode::Sky,
        MdbxTigaMode::Power,
        MdbxTigaMode::Glitter,
    ];
    for round in 0..rounds {
        for offset in 0..modes.len() {
            println!(
                "{}",
                scenario(modes[(round + offset) % modes.len()], count, round)
            );
        }
    }
}
