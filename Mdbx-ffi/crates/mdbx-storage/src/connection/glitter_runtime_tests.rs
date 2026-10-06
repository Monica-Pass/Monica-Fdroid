//! Runtime lifecycle tests use synthetic internal state; these are not hardware
//! attestation tests. Real KDF/factor roundtrips are covered by unlock/FFI tests.
use super::*;
use crate::init::{initialize_vault_with_device_context, VaultInitParams};
use crate::runtime::{RuntimeLockPoisoned, VaultRuntime};
use mdbx_core::model::UnlockMethodType;
use mdbx_core::tiga::{DeviceAssurance, SessionAssurance};

fn device() -> DeviceContext {
    DeviceContext {
        assurance: DeviceAssurance::Standard,
        ..Default::default()
    }
}

fn connection() -> VaultConnection {
    connection_for(VaultConnection::open_in_memory().unwrap())
}

fn connection_for(mut conn: VaultConnection) -> VaultConnection {
    initialize_vault_with_device_context(
        &conn,
        &VaultInitParams {
            default_tiga_mode: "glitter".into(),
            ..Default::default()
        },
        &device(),
    )
    .unwrap();
    conn.active_key_epoch_id = Some("synthetic-verified-epoch".into());
    conn.keyring = Some(Keyring::from_vault_key(&[7; 32], b"synthetic-runtime-test").unwrap());
    conn.attach_session(VaultSession {
        session_id: "synthetic-session".into(),
        unlock_method: UnlockMethodType::PasswordSecurityKey,
        created_at: chrono::Utc::now().to_rfc3339(),
        assurance: SessionAssurance::from_unlock_method(
            UnlockMethodType::PasswordSecurityKey,
            chrono::Utc::now().timestamp(),
        ),
    });
    conn.bind_authenticated_device_context(device()).unwrap();
    conn.metadata_cache
        .lock()
        .unwrap()
        .insert("entry", "synthetic", "title", b"ct", b"title");
    assert_eq!(conn.metadata_cache_stats().entries, 1);
    conn
}

#[test]
fn glitter_runtime_rejects_coherent_external_hot_profile_downgrade() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("hot-downgrade.mdbx");
    let mut conn = connection_for(VaultConnection::create(&path).unwrap());
    conn.check_runtime_authentication().unwrap();
    let writer = Connection::open(&path).unwrap();
    writer.execute_batch("DROP TRIGGER trg_vault_meta_header_auth_no_pending_downgrade;
        DROP TRIGGER trg_vault_meta_header_auth_invalidate;
        UPDATE vault_meta SET default_tiga_mode = 'sky', tiga_policy_version = 2,
            critical_extensions = '', header_integrity_profile = 'pending', header_integrity_tag = NULL;").unwrap();
    assert!(
        !crate::unlock::UnlockService::is_glitter(&conn).unwrap(),
        "the forged legacy metadata is coherent"
    );
    assert!(
        crate::tiga::TigaService::resolve_vault_policy(&conn).is_err(),
        "operation-level resolution must also fail closed"
    );
    assert!(crate::tiga::TigaService::read_session_remaining_secs(
        &conn,
        &mdbx_core::tiga::TigaScope::Vault,
        chrono::Utc::now().timestamp()
    )
    .is_err());
    assert!(
        conn.check_runtime_authentication().is_err(),
        "a live Glitter handle must not adopt Sky's weaker operations"
    );
    assert!(conn.active_session().is_none());
    assert!(conn.authenticated_device_context().is_none());
    assert!(conn.keyring().is_none());
    assert_eq!(conn.metadata_cache_stats().entries, 0);
    let runtime = VaultRuntime::from_connection(conn);
    assert!(matches!(
        runtime.read(),
        Err(RuntimeLockPoisoned::AuthenticationRequired)
    ));
    assert!(matches!(
        runtime.write(),
        Err(RuntimeLockPoisoned::AuthenticationRequired)
    ));
    assert!(matches!(
        runtime.lock(),
        Err(RuntimeLockPoisoned::AuthenticationRequired)
    ));
}

#[test]
fn glitter_runtime_expiry_wipes_state_and_denies_every_access_lane() {
    let mut conn = connection();
    conn.glitter_auth_deadline = Some(Instant::now() - Duration::from_secs(1));
    let runtime = VaultRuntime::from_connection(conn);
    let lease = runtime.acquire_reader();
    assert!(matches!(
        runtime.read(),
        Err(RuntimeLockPoisoned::AuthenticationRequired)
    ));
    assert!(matches!(
        runtime.write(),
        Err(RuntimeLockPoisoned::AuthenticationRequired)
    ));
    assert!(matches!(
        runtime.lock(),
        Err(RuntimeLockPoisoned::AuthenticationRequired)
    ));
    assert!(runtime.validate_reader(lease).is_err());
}

#[test]
fn glitter_runtime_monotonic_deadline_cannot_be_renewed_by_rebinding_or_reattaching() {
    let mut conn = connection();
    let fixed = Instant::now() + Duration::from_millis(100);
    conn.glitter_auth_deadline = Some(fixed);
    conn.bind_authenticated_device_context(device()).unwrap();
    assert_eq!(conn.glitter_auth_deadline, Some(fixed));
    let session = conn.active_session.clone().unwrap();
    conn.attach_session(session);
    assert_eq!(conn.glitter_auth_deadline, Some(fixed));
    assert!(conn.authenticated_device_context().is_none());
    assert_eq!(conn.metadata_cache_stats().entries, 0);
    conn.bind_authenticated_device_context(device()).unwrap();
    conn.glitter_auth_deadline = Some(Instant::now() - Duration::from_secs(1));
    // Even a wall-clock timestamp that looks fresh cannot revive monotonic expiry.
    conn.active_session
        .as_mut()
        .unwrap()
        .assurance
        .authenticated_at_unix_secs = chrono::Utc::now().timestamp();
    assert!(conn.check_runtime_authentication().is_err());
    assert!(conn.active_session().is_none());
    assert!(conn.keyring().is_none());
    assert_eq!(conn.metadata_cache_stats().entries, 0);
}

#[test]
fn glitter_runtime_does_not_require_hardware_evidence() {
    let mut conn = connection();
    assert!(conn
        .bind_authenticated_device_context(DeviceContext::default())
        .is_err());
    let mut no_screen = device();
    no_screen.screen_capture_protection_available = false;
    conn.bind_authenticated_device_context(no_screen).unwrap();
    conn.authenticated_device = None;
    conn.check_runtime_authentication().unwrap();
    assert_eq!(conn.metadata_cache_stats().entries, 1);
}

#[test]
fn glitter_runtime_allows_ordinary_session_past_old_sixty_second_gate() {
    let mut conn = connection();
    let mut session = conn.active_session.clone().unwrap();
    session.assurance.authenticated_at_unix_secs -= 301;
    conn.attach_session(session);
    conn.check_runtime_authentication().unwrap();
    assert!(conn.glitter_auth_deadline.unwrap() > Instant::now() + Duration::from_secs(6000));
    assert!(conn.active_session().is_some());
    let policy = TigaMode::Glitter.policy();
    let decision = policy.authorize(
        mdbx_core::tiga::TigaOperation::RevealSecret,
        mdbx_core::tiga::AuthorizationContext {
            session: Some(&conn.active_session().unwrap().assurance),
            device: &device(),
            now_unix_secs: chrono::Utc::now().timestamp(),
        },
    );
    assert_eq!(
        decision.outcome,
        mdbx_core::tiga::AuthorizationOutcome::RequireFreshAuthentication
    );
    conn.metadata_cache.lock().unwrap().insert(
        "entry",
        "after-300",
        "title",
        b"ct",
        b"still cached",
    );
    assert_eq!(conn.metadata_cache_stats().entries, 1);
}

#[test]
fn glitter_runtime_normal_idle_expiry_wipes_keys_and_caches() {
    let mut conn = connection();
    conn.active_session
        .as_mut()
        .unwrap()
        .assurance
        .last_activity_at_unix_secs -= 601;
    assert!(conn.check_runtime_authentication().is_err());
    assert!(conn.active_session().is_none());
    assert!(conn.keyring().is_none());
    assert_eq!(conn.metadata_cache_stats().entries, 0);
}

#[test]
fn glitter_runtime_lock_stays_fail_closed_when_an_unverified_session_is_attached() {
    let mut conn = connection();
    let session = conn.active_session.clone().unwrap();
    conn.clear_session();
    assert_eq!(conn.metadata_cache_stats().entries, 0);
    conn.attach_session(session);
    assert!(conn.is_glitter_session());
    assert!(conn.bind_authenticated_device_context(device()).is_err());
    assert!(conn.check_runtime_authentication().is_err());
}

#[test]
fn glitter_runtime_rejects_old_or_future_wall_clock_authentication() {
    for offset in [-7201, 60] {
        let mut conn = connection();
        let mut session = conn.active_session.clone().unwrap();
        session.session_id = format!("synthetic-offset-{offset}");
        session.assurance.authenticated_at_unix_secs = chrono::Utc::now().timestamp() + offset;
        conn.attach_session(session);
        assert!(conn.glitter_auth_deadline.is_none());
        assert!(conn.check_runtime_authentication().is_err());
    }
}

#[test]
fn glitter_runtime_cannot_treat_a_never_unlocked_file_as_a_legacy_handle() {
    let conn = VaultConnection::open_in_memory().unwrap();
    initialize_vault_with_device_context(
        &conn,
        &VaultInitParams {
            default_tiga_mode: "glitter".into(),
            ..Default::default()
        },
        &device(),
    )
    .unwrap();
    assert!(!conn.glitter_runtime);
    let runtime = VaultRuntime::from_connection(conn);
    assert!(matches!(
        runtime.read(),
        Err(RuntimeLockPoisoned::AuthenticationRequired)
    ));
    assert!(matches!(
        runtime.write(),
        Err(RuntimeLockPoisoned::AuthenticationRequired)
    ));
}

#[test]
fn glitter_runtime_applies_shorter_vault_overrides_and_rejects_corrupt_policy() {
    let mut conn = connection();
    let policy = mdbx_core::tiga::TigaPolicyOverride {
        idle_timeout_secs: Some(10),
        max_lifetime_secs: Some(10),
        ..Default::default()
    };
    crate::tiga_policy::TigaPolicyStore::put_override(
        &conn,
        &mdbx_core::tiga::TigaScope::Vault,
        &policy,
        None,
        "synthetic-device",
        None,
    )
    .unwrap();
    conn.check_runtime_authentication().unwrap();
    assert!(conn.glitter_auth_deadline.unwrap() <= Instant::now() + Duration::from_secs(10));
    conn.active_session
        .as_mut()
        .unwrap()
        .assurance
        .authenticated_at_unix_secs -= 11;
    assert!(conn.check_runtime_authentication().is_err());

    let mut conn = connection();
    conn.inner()
        .execute("UPDATE vault_meta SET tiga_policy_version = 999", [])
        .unwrap();
    assert!(conn.check_runtime_authentication().is_err());
    assert_eq!(conn.metadata_cache_stats().entries, 0);
}
