use super::*;
use crate::init::{initialize_vault, VaultInitParams};
use crate::object_disclosure::{ObjectDisclosureLimits, ObjectDisclosureService};
use mdbx_core::model::{EntryType, UnlockMethodType};
use mdbx_core::tiga::{DeviceAssurance, SessionAssurance};
use mdbx_crypto::keyring::Keyring;

const NOW: i64 = 1_000;
fn setup() -> (VaultConnection, String, DeviceContext) {
    let mut conn = VaultConnection::open_in_memory().unwrap();
    initialize_vault(&conn, &VaultInitParams::default()).unwrap();
    attach(&mut conn);
    let ctx = CommitContext::new("proxy-test".into());
    let project = ProjectRepo::create(&conn, &ctx, "Keys", None, None).unwrap();
    let entry = EntryRepo::create(
        &conn,
        &ctx,
        &project.project_id,
        EntryType::Login,
        Some("Key"),
        &serde_json::json!({"password_plain":"synthetic-proxy-key"}),
    )
    .unwrap();
    (
        conn,
        entry.entry_id,
        DeviceContext {
            assurance: DeviceAssurance::Standard,
            ..Default::default()
        },
    )
}
fn attach(conn: &mut VaultConnection) {
    conn.attach_keyring(Keyring::from_vault_key(&[9; 32], b"proxy-test").unwrap());
    conn.attach_session(VaultSession {
        session_id: "same-id-even-after-unlock".into(),
        unlock_method: UnlockMethodType::Password,
        created_at: "test".into(),
        assurance: SessionAssurance::from_unlock_method(UnlockMethodType::Password, NOW),
    });
}
fn lease(conn: &VaultConnection, id: &str, device: &DeviceContext) -> CredentialUseLease {
    TigaService::authorize_credential_use(conn, id, "client-A", 3600, device, NOW).unwrap()
}
fn allowed(
    conn: &VaultConnection,
    lease: &CredentialUseLease,
    device: &DeviceContext,
    now: i64,
) -> bool {
    TigaService::evaluate_credential_use(conn, lease, "client-A", device, now)
        .is_ok_and(|decision| decision_allows(&decision))
}

#[test]
fn credential_use_survives_freshness_and_idle_without_extending_reveal() {
    let (mut conn, id, device) = setup();
    let lease = lease(&conn, &id, &device);
    assert!(allowed(&conn, &lease, &device, NOW + 1800));
    let data = ObjectDisclosureService::use_with_lease_and_limits(
        &conn,
        &lease,
        "client-A",
        &device,
        NOW + 1800,
        ObjectDisclosureLimits::default(),
    )
    .unwrap();
    assert!(String::from_utf8(data.object.payload_ct)
        .unwrap()
        .contains("synthetic-proxy-key"));
    assert!(ObjectDisclosureService::reveal_with_active_session(
        &mut conn,
        &id,
        &device,
        NOW + 1800
    )
    .is_err());
    assert_eq!(
        conn.active_session()
            .unwrap()
            .assurance
            .last_activity_at_unix_secs,
        NOW
    );
    assert_eq!(
        conn.active_session()
            .unwrap()
            .assurance
            .authenticated_at_unix_secs,
        NOW
    );
    assert!(!allowed(&conn, &lease, &device, NOW + 3600));
}

#[test]
fn credential_use_requires_fresh_authentication_and_original_policy() {
    let (conn, id, device) = setup();
    assert!(TigaService::authorize_credential_use(
        &conn,
        &id,
        "client-A",
        3600,
        &device,
        NOW + 301
    )
    .is_err());
    for duration in [0, 86401] {
        assert!(TigaService::authorize_credential_use(
            &conn, &id, "client-A", duration, &device, NOW
        )
        .is_err());
    }
    conn.inner()
        .execute("UPDATE vault_meta SET default_tiga_mode = 'power'", [])
        .unwrap();
    assert!(
        TigaService::authorize_credential_use(&conn, &id, "client-A", 3600, &device, NOW).is_err()
    );
}

#[test]
fn credential_use_clips_to_absolute_lifetime() {
    let (conn, id, device) = setup();
    let lease =
        TigaService::authorize_credential_use(&conn, &id, "client-A", 86400, &device, NOW + 30)
            .unwrap();
    assert_eq!(lease.expires_at_unix_secs(), NOW + 7200);
    assert!(allowed(&conn, &lease, &device, NOW + 7199));
    assert!(!allowed(&conn, &lease, &device, NOW + 7200));
    assert!(!allowed(&conn, &lease, &device, NOW + 40));
}

#[test]
fn credential_use_lock_reunlock_and_cross_connection_never_replay() {
    let (mut conn, id, device) = setup();
    let old = lease(&conn, &id, &device);
    conn.clear_session();
    attach(&mut conn);
    assert!(!allowed(&conn, &old, &device, NOW));
    let current = lease(&conn, &id, &device);
    let (other, _, _) = setup();
    assert!(!allowed(&other, &current, &device, NOW));
    let current = lease(&conn, &id, &device);
    conn.clear_session();
    assert!(!allowed(&conn, &current, &device, NOW));
}

#[test]
fn credential_use_revocation_audience_and_clock_are_fail_closed() {
    let (conn, id, device) = setup();
    let revoked = lease(&conn, &id, &device);
    revoked.revoke();
    assert!(!allowed(&conn, &revoked, &device, NOW));
    let wrong = lease(&conn, &id, &device);
    assert!(!decision_allows(
        &TigaService::evaluate_credential_use(&conn, &wrong, "client-B", &device, NOW).unwrap()
    ));
    assert!(!allowed(&conn, &wrong, &device, NOW));
    let rollback = lease(&conn, &id, &device);
    assert!(allowed(&conn, &rollback, &device, NOW + 10));
    assert!(!allowed(&conn, &rollback, &device, NOW + 9));
    assert!(!allowed(&conn, &rollback, &device, NOW + 11));
    let mut monotonic = lease(&conn, &id, &device);
    monotonic.deadline = Instant::now() - Duration::from_secs(1);
    assert!(!allowed(&conn, &monotonic, &device, NOW));
}

#[test]
fn credential_use_source_policy_and_device_changes_invalidate_lease() {
    let (conn, id, device) = setup();
    let changed = lease(&conn, &id, &device);
    conn.inner()
        .execute(
            "UPDATE entries SET head_commit_id = 'changed' WHERE entry_id = ?1",
            [&id],
        )
        .unwrap();
    assert!(!allowed(&conn, &changed, &device, NOW));
    let changed = lease(&conn, &id, &device);
    conn.inner()
        .execute("UPDATE vault_meta SET default_tiga_mode = 'sky'", [])
        .unwrap();
    assert!(!allowed(&conn, &changed, &device, NOW));
    let changed = lease(&conn, &id, &device);
    assert!(!allowed(&conn, &changed, &DeviceContext::default(), NOW));
    let deleted = lease(&conn, &id, &device);
    conn.inner()
        .execute("UPDATE entries SET deleted = 1 WHERE entry_id = ?1", [&id])
        .unwrap();
    assert!(!allowed(&conn, &deleted, &device, NOW));
}

#[test]
fn credential_use_audits_keep_old_enum_values_and_do_not_change_schema_or_sync() {
    let (conn, id, device) = setup();
    let schema = || {
        conn.inner()
            .query_row(
                "SELECT group_concat(sql) FROM sqlite_master WHERE sql IS NOT NULL",
                [],
                |r| r.get::<_, String>(0),
            )
            .unwrap()
    };
    let original_schema = schema();
    let commits = || {
        conn.inner()
            .query_row("SELECT count(*) FROM commits", [], |r| r.get::<_, i64>(0))
            .unwrap()
    };
    let original_commits = commits();
    let lease = lease(&conn, &id, &device);
    ObjectDisclosureService::use_with_lease_and_limits(
        &conn,
        &lease,
        "client-A",
        &device,
        NOW + 1000,
        ObjectDisclosureLimits::default(),
    )
    .unwrap();
    let audit = TigaService::list_security_audit_events(&conn, 100).unwrap();
    assert_eq!(audit.len(), 2);
    for event in audit {
        assert_eq!(event.operation, TigaOperation::RevealSecret);
        let serialized = serde_json::to_value(&event).unwrap();
        assert_eq!(serialized["operation"], "reveal-secret");
        assert!(serialized.get("lease").is_none());
        assert!(event.operation_id.is_none());
        assert!(event.commit_id.is_none());
    }
    assert_eq!(schema(), original_schema);
    assert_eq!(commits(), original_commits);
    lease.revoke();
    let error = ObjectDisclosureService::use_with_lease_and_limits(
        &conn,
        &lease,
        "client-A",
        &device,
        NOW + 1001,
        ObjectDisclosureLimits::default(),
    );
    assert!(matches!(error, Err(StorageError::Authorization(_))));
    assert_eq!(
        TigaService::list_security_audit_events(&conn, 100)
            .unwrap()
            .len(),
        3
    );
}
