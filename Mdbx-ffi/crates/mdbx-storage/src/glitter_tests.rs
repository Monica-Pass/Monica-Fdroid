use super::*;
use crate::init::{initialize_vault, initialize_vault_with_device_context, VaultInitParams};
use mdbx_core::tiga::{DeviceAssurance, DeviceContext, GLITTER_POLICY_VERSION};

fn trusted_device() -> DeviceContext {
    DeviceContext {
        assurance: DeviceAssurance::TrustedHardware,
        screen_capture_protection_available: true,
        ..Default::default()
    }
}

fn empty_glitter() -> VaultConnection {
    let conn = VaultConnection::open_in_memory().unwrap();
    initialize_vault_with_device_context(
        &conn,
        &VaultInitParams {
            default_tiga_mode: "glitter".into(),
            ..Default::default()
        },
        &trusted_device(),
    )
    .unwrap();
    conn
}

fn insert_wrapper(conn: &VaultConnection, kind: UnlockMethodType, params: &KdfParams) {
    conn.inner().execute(
        "INSERT INTO unlock_methods (method_id,method_type,kdf_profile_id,kdf_params_ct,wrapped_vault_key_ct,created_at,updated_at)
         VALUES (?1,?2,'argon2id',?3,X'00','test','test')",
        rusqlite::params![Uuid::new_v4().to_string(), kind.to_string(), params.to_json_bytes()],
    ).unwrap();
}

#[test]
fn glitter_bootstrap_is_portable_but_requires_combined_path() {
    let mut conn = VaultConnection::open_in_memory().unwrap();
    let params = VaultInitParams {
        default_tiga_mode: "glitter".into(),
        ..Default::default()
    };
    initialize_vault(&conn, &params).unwrap();
    assert_eq!(
        conn.inner()
            .query_row("SELECT count(*) FROM vault_meta", [], |row| row
                .get::<_, u32>(0))
            .unwrap(),
        1
    );
    assert_eq!(
        TigaService::get_policy_state(&conn).unwrap().policy_version,
        GLITTER_POLICY_VERSION
    );
    assert!(
        UnlockService::setup_password_with_mode(&mut conn, "password", TigaMode::Glitter).is_err()
    );
    assert!(UnlockService::setup_pin(&mut conn, "1234").is_err());
    assert!(UnlockService::setup_security_key(&mut conn, &[1; 32]).is_err());
    assert!(UnlockService::setup_password_security_key(
        &mut conn,
        " ",
        &[1; 32],
        TigaMode::Glitter
    )
    .is_err());
    for mode in [TigaMode::Sky, TigaMode::Multi, TigaMode::Power] {
        assert!(
            UnlockService::setup_password_security_key_with_device_context(
                &mut conn,
                "password",
                &[1; 32],
                mode,
                &trusted_device()
            )
            .is_err()
        );
    }
    assert!(
        UnlockService::setup_password_security_key_with_device_context(
            &mut conn,
            "password",
            &[1; 1],
            TigaMode::Glitter,
            &trusted_device()
        )
        .is_err()
    );
    assert!(UnlockService::list_methods(&conn).unwrap().is_empty());
    assert!(conn.keyring().is_none());
}

#[test]
fn glitter_default_apis_accept_portable_password_key_and_unicode_normalization() {
    let mut conn = VaultConnection::open_in_memory().unwrap();
    initialize_vault(
        &conn,
        &VaultInitParams {
            default_tiga_mode: "glitter".into(),
            ..Default::default()
        },
    )
    .unwrap();
    UnlockService::setup_password_security_key(&mut conn, "  café  ", &[7; 32], TigaMode::Glitter)
        .unwrap();
    assert_eq!(
        conn.authenticated_device_context().unwrap().assurance,
        DeviceAssurance::Standard
    );
    conn.clear_session();
    UnlockService::unlock_with_password_security_key(&mut conn, "cafe\u{301}", &[7; 32]).unwrap();
    assert!(conn.keyring().is_some());
    conn.check_runtime_authentication().unwrap();
}

#[test]
fn glitter_rejects_every_weak_alternative_before_unwrap() {
    let mut strong = KdfParams::for_password_with_mode(TigaMode::Glitter);
    strong.salt = vec![5; 16];
    for kind in [
        UnlockMethodType::Password,
        UnlockMethodType::Pin,
        UnlockMethodType::SecurityKey,
    ] {
        let mut conn = empty_glitter();
        insert_wrapper(&conn, UnlockMethodType::PasswordSecurityKey, &strong);
        insert_wrapper(&conn, kind, &strong);
        let error = UnlockService::unlock_with_password_security_key_and_device_context(
            &mut conn,
            "password",
            &[1; 32],
            &trusted_device(),
        )
        .unwrap_err();
        assert!(error.to_string().contains("weak or unsupported"));
        assert!(
            !UnlockService::assess_tiga_unlock_policy(&conn, TigaMode::Glitter)
                .unwrap()
                .satisfies_policy
        );
        assert!(conn.keyring().is_none());
    }
    for field in [
        "memory",
        "iterations",
        "parallelism",
        "output",
        "algorithm",
        "salt",
    ] {
        let mut conn = empty_glitter();
        insert_wrapper(&conn, UnlockMethodType::PasswordSecurityKey, &strong);
        let mut weak = strong.clone();
        match field {
            "memory" => weak.mem_limit_kib = 262144,
            "iterations" => weak.ops_limit = 1,
            "parallelism" => weak.parallelism = 1,
            "output" => weak.output_len = 16,
            "algorithm" => weak.algorithm = "argon2i".into(),
            _ => weak.salt = vec![1; 8],
        }
        insert_wrapper(&conn, UnlockMethodType::PasswordSecurityKey, &weak);
        let error = UnlockService::unlock_with_password_security_key_and_device_context(
            &mut conn,
            "password",
            &[1; 32],
            &trusted_device(),
        )
        .unwrap_err();
        assert!(
            error.to_string().contains("weak or unsupported"),
            "{field}: {error}"
        );
        assert!(
            !UnlockService::assess_tiga_unlock_policy(&conn, TigaMode::Glitter)
                .unwrap()
                .satisfies_policy
        );
        assert!(conn.keyring().is_none());
    }
}

#[test]
fn glitter_assessment_rejects_unbudgeted_strength_without_derivation() {
    let conn = empty_glitter();
    let mut params = KdfParams::for_password_with_mode(TigaMode::Glitter);
    params.salt = vec![5; 16];
    params.ops_limit = u32::MAX;
    assert!(
        params.meets_mode_strength(TigaMode::Glitter),
        "strength is not a resource budget"
    );
    insert_wrapper(&conn, UnlockMethodType::PasswordSecurityKey, &params);
    let assessment = UnlockService::assess_tiga_unlock_policy(&conn, TigaMode::Glitter).unwrap();
    assert!(!assessment.satisfies_policy);
    assert!(!assessment.has_required_combined_strength);
    assert!(conn.active_session().is_none());
    assert!(conn.keyring().is_none());
}

#[test]
fn glitter_rejects_excessive_kdf_parameters_before_any_unwrap_or_session() {
    let mut supported = KdfParams::for_password_with_mode(TigaMode::Glitter);
    supported.salt = vec![5; 16];
    for field in [
        "memory-max",
        "memory-plus",
        "ops-max",
        "ops-plus",
        "lanes-max",
        "lanes-plus",
        "output",
        "salt",
    ] {
        let mut conn = empty_glitter();
        insert_wrapper(&conn, UnlockMethodType::PasswordSecurityKey, &supported);
        let mut excessive = supported.clone();
        match field {
            "memory-max" => excessive.mem_limit_kib = u32::MAX,
            "memory-plus" => excessive.mem_limit_kib += 1,
            "ops-max" => excessive.ops_limit = u32::MAX,
            "ops-plus" => excessive.ops_limit += 1,
            "lanes-max" => excessive.parallelism = u32::MAX,
            "lanes-plus" => excessive.parallelism += 1,
            "output" => excessive.output_len = u32::MAX,
            _ => excessive.salt = vec![5; 65],
        }
        insert_wrapper(&conn, UnlockMethodType::PasswordSecurityKey, &excessive);
        // Assert the non-deriving preflight first: a regression must fail the
        // test before attempting a deliberately unbounded Argon2 workload.
        let early = UnlockService::validate_glitter_path(
            &conn,
            UnlockMethodType::PasswordSecurityKey,
            None,
        )
        .unwrap_err();
        assert!(
            early.to_string().contains("weak or unsupported"),
            "{field}: {early}"
        );
        let error = UnlockService::unlock_with_password_security_key_and_device_context(
            &mut conn,
            "password",
            &[1; 32],
            &trusted_device(),
        )
        .unwrap_err();
        assert!(
            error.to_string().contains("weak or unsupported"),
            "{field}: {error}"
        );
        assert!(
            !UnlockService::assess_tiga_unlock_policy(&conn, TigaMode::Glitter)
                .unwrap()
                .satisfies_policy
        );
        assert!(conn.active_session().is_none());
        assert!(conn.keyring().is_none());
    }
}

#[test]
fn glitter_bounds_slot_count_and_untrusted_columns_before_materialization() {
    let mut supported = KdfParams::for_password_with_mode(TigaMode::Glitter);
    supported.salt = vec![5; 64];
    let mut conn = empty_glitter();
    for _ in 0..8 {
        insert_wrapper(&conn, UnlockMethodType::PasswordSecurityKey, &supported);
    }
    let mut padded = supported.to_json_bytes();
    padded.resize(GLITTER_MAX_KDF_PARAMS_BYTES, b' ');
    conn.inner()
        .execute(
            "UPDATE unlock_methods SET kdf_params_ct = ?1, wrapped_vault_key_ct = zeroblob(256)",
            [padded],
        )
        .unwrap();
    assert_eq!(UnlockService::list_methods(&conn).unwrap().len(), 8);
    assert!(
        UnlockService::assess_tiga_unlock_policy(&conn, TigaMode::Glitter)
            .unwrap()
            .satisfies_policy
    );
    insert_wrapper(&conn, UnlockMethodType::PasswordSecurityKey, &supported);
    assert!(UnlockService::list_methods(&conn)
        .unwrap_err()
        .to_string()
        .contains("at most eight"));
    assert!(UnlockService::assess_tiga_unlock_policy(&conn, TigaMode::Glitter).is_err());
    assert!(
        UnlockService::unlock_with_password_security_key_and_device_context(
            &mut conn,
            "password",
            &[1; 32],
            &trusted_device()
        )
        .unwrap_err()
        .to_string()
        .contains("at most eight")
    );
    assert!(conn.active_session().is_none());

    for assignment in [
        "kdf_params_ct = zeroblob(4097)",
        "wrapped_vault_key_ct = zeroblob(257)",
        "kdf_params_ct = zeroblob(4194304)",
        "wrapped_vault_key_ct = zeroblob(4194304)",
        "method_id = printf('%0257d', 0)",
        "kdf_profile_id = printf('%0257d', 0)",
        "created_at = printf('%0129d', 0)",
        "updated_at = printf('%0129d', 0)",
        "method_type = 'unknown-method'",
    ] {
        let mut conn = empty_glitter();
        insert_wrapper(&conn, UnlockMethodType::PasswordSecurityKey, &supported);
        conn.inner()
            .execute_batch("PRAGMA ignore_check_constraints = ON;")
            .unwrap();
        conn.inner()
            .execute(&format!("UPDATE unlock_methods SET {assignment}"), [])
            .unwrap();
        for result in [
            UnlockService::list_methods(&conn).map(|_| ()),
            UnlockService::has_method_of_type(&conn, UnlockMethodType::PasswordSecurityKey)
                .map(|_| ()),
            UnlockService::assess_tiga_unlock_policy(&conn, TigaMode::Glitter).map(|_| ()),
        ] {
            assert!(
                result
                    .unwrap_err()
                    .to_string()
                    .contains("allocation limits"),
                "{assignment}"
            );
        }
        assert!(
            UnlockService::unlock_with_password_security_key_and_device_context(
                &mut conn,
                "password",
                &[1; 32],
                &trusted_device()
            )
            .unwrap_err()
            .to_string()
            .contains("allocation limits")
        );
        assert!(conn.active_session().is_none());
        assert!(conn.keyring().is_none());
    }
}

#[test]
fn glitter_marker_is_immutable_and_old_profiles_cannot_convert() {
    let conn = empty_glitter();
    let ctx = crate::repo::commit_ctx::CommitContext::new("test".into());
    for mode in [TigaMode::Sky, TigaMode::Multi, TigaMode::Power] {
        assert!(TigaService::set_global_default(&conn, &ctx, mode).is_err());
    }
    conn.inner()
        .execute("UPDATE vault_meta SET default_tiga_mode = 'sky'", [])
        .unwrap();
    assert!(UnlockService::is_glitter(&conn).is_err());
    let old = VaultConnection::open_in_memory().unwrap();
    initialize_vault(&old, &Default::default()).unwrap();
    assert!(TigaService::set_global_default(&old, &ctx, TigaMode::Glitter).is_err());
}

#[test]
fn glitter_wrapping_domain_cannot_be_opened_as_legacy() {
    let unlock_key = [7; 32];
    let vault_key = [9; 32];
    let wrapped = aead::encrypt(&unlock_key, &vault_key, GLITTER_VAULT_KEY_WRAP_AAD).unwrap();
    assert!(UnlockService::unwrap_vault_key(&unlock_key, &wrapped).is_err());
    assert_eq!(
        UnlockService::unwrap_vault_key_with_aad(&unlock_key, &wrapped, GLITTER_VAULT_KEY_WRAP_AAD)
            .unwrap()
            .as_slice(),
        &vault_key
    );
}
