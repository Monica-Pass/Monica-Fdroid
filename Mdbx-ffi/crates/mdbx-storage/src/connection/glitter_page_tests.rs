//! Synthetic session fixtures isolate SQL cache semantics, not hardware attestation.
use super::*;
use crate::init::{initialize_vault, VaultInitParams};
use crate::repo::{CommitContext, EntryRepo, ObjectSummaryRepo, ProjectRepo};
use crate::unlock::UnlockService;
use mdbx_core::model::{ObjectSummaryPage, ObjectTypeId, UnlockMethodType};
use mdbx_core::tiga::{DeviceAssurance, SessionAssurance};

fn fixture(mut conn: VaultConnection) -> (VaultConnection, CommitContext, String, Vec<String>) {
    initialize_vault(&conn, &VaultInitParams::default()).unwrap();
    UnlockService::setup_password(&mut conn, "page cache test password").unwrap();
    // Actual encrypted fields and keyring, synthetic combined/hardware session.
    conn.glitter_runtime = true;
    let mut session = conn.active_session().unwrap().clone();
    session.unlock_method = UnlockMethodType::PasswordSecurityKey;
    session.assurance = SessionAssurance::from_unlock_method(
        UnlockMethodType::PasswordSecurityKey,
        chrono::Utc::now().timestamp(),
    );
    conn.attach_session(session);
    conn.bind_authenticated_device_context(DeviceContext {
        assurance: DeviceAssurance::TrustedHardware,
        screen_capture_protection_available: true,
        ..Default::default()
    })
    .unwrap();
    let ctx = CommitContext::new("page-cache-test".into());
    let project = ProjectRepo::create(&conn, &ctx, "Folder", None, None)
        .unwrap()
        .project_id;
    let ids = (0..3)
        .map(|index| {
            EntryRepo::create(
                &conn,
                &ctx,
                &project,
                ObjectTypeId::Login,
                Some(&format!("Title {index}")),
                &serde_json::json!({"password":"never cache this payload"}),
            )
            .unwrap()
            .entry_id
        })
        .collect();
    (conn, ctx, project, ids)
}

fn page(conn: &VaultConnection, project: &str) -> ObjectSummaryPage {
    ObjectSummaryRepo::list(conn, project, None, 200, None).unwrap()
}

#[test]
fn glitter_pages_reuse_one_complete_page_without_revisiting_titles() {
    let (conn, _, project, _) = fixture(VaultConnection::open_in_memory().unwrap());
    let expected = page(&conn, &project);
    let before = conn.metadata_cache_stats();
    assert_eq!(page(&conn, &project), expected);
    let after = conn.metadata_cache_stats();
    assert_eq!(
        after.hits - before.hits,
        1,
        "one page hit, not three separate title hits"
    );
    assert_eq!(after.misses, before.misses);
    assert_eq!(after.entries, before.entries);
    assert!(
        after.entries >= 4,
        "three object titles plus one page; project titles may also be retained"
    );
}

#[test]
fn glitter_pages_invalidate_after_own_updates_deletion_and_rolled_back_writes() {
    let (conn, ctx, project, ids) = fixture(VaultConnection::open_in_memory().unwrap());
    let original = page(&conn, &project);
    assert_eq!(page(&conn, &project), original);
    let mut entry = EntryRepo::get_by_id(&conn, &ids[0]).unwrap().unwrap();
    entry.title_ct = Some(b"Changed title".to_vec());
    EntryRepo::update(&conn, &ctx, &entry).unwrap();
    let changed = page(&conn, &project);
    assert_eq!(
        changed
            .items
            .iter()
            .find(|item| item.object_id == ids[0])
            .unwrap()
            .title
            .as_deref(),
        Some(b"Changed title".as_slice())
    );
    EntryRepo::soft_delete(&conn, &ctx, &ids[0]).unwrap();
    let deleted = page(&conn, &project);
    assert_eq!(deleted.items.len(), 2);
    assert!(!deleted.items.iter().any(|item| item.object_id == ids[0]));

    let before = conn.summary_pages.lock().unwrap().stats();
    conn.inner()
        .execute_batch("BEGIN; UPDATE entries SET deleted = 1; ROLLBACK;")
        .unwrap();
    assert_eq!(page(&conn, &project), deleted);
    assert_eq!(
        conn.summary_pages.lock().unwrap().stats().misses,
        before.misses + 1,
        "total_changes must invalidate even rolled-back writes"
    );
    conn.inner().execute_batch("BEGIN;").unwrap();
    let temp_title = crate::crypto_layer::encrypt_field(
        &conn,
        crate::crypto_layer::FieldKeyPurpose::Metadata,
        b"Rolled back title",
        "entry",
        &ids[1],
        "title",
    )
    .unwrap();
    conn.inner()
        .execute(
            "UPDATE entries SET title_ct = ?1 WHERE entry_id = ?2",
            rusqlite::params![temp_title, &ids[1]],
        )
        .unwrap();
    assert!(page(&conn, &project)
        .items
        .iter()
        .any(|item| item.title.as_deref() == Some(b"Rolled back title".as_slice())));
    assert_eq!(
        conn.summary_pages.lock().unwrap().stats().entries,
        0,
        "no transaction page may be retained"
    );
    conn.inner().execute_batch("ROLLBACK;").unwrap();
    assert_eq!(page(&conn, &project), deleted);
}

#[test]
fn glitter_pages_detect_external_updates_deletion_and_corrupted_ciphertext() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("cache.mdbx");
    let (conn, _, project, ids) = fixture(VaultConnection::create(&path).unwrap());
    let writer = Connection::open(&path).unwrap();
    let ciphertext = conn
        .with_immediate_transaction(|| {
            crate::crypto_layer::encrypt_field(
                &conn,
                crate::crypto_layer::FieldKeyPurpose::Metadata,
                b"External update",
                "entry",
                &ids[0],
                "title",
            )
        })
        .unwrap();
    page(&conn, &project);
    writer
        .execute(
            "UPDATE entries SET title_ct = ?1 WHERE entry_id = ?2",
            rusqlite::params![ciphertext, &ids[0]],
        )
        .unwrap();
    assert!(page(&conn, &project)
        .items
        .iter()
        .any(|item| item.title.as_deref() == Some(b"External update".as_slice())));
    writer
        .execute(
            "UPDATE entries SET deleted = 1 WHERE entry_id = ?1",
            [&ids[0]],
        )
        .unwrap();
    assert_eq!(page(&conn, &project).items.len(), 2);
    writer
        .execute(
            "UPDATE entries SET title_ct = X'00' WHERE entry_id = ?1",
            [&ids[1]],
        )
        .unwrap();
    assert!(
        ObjectSummaryRepo::list(&conn, &project, None, 200, None).is_err(),
        "a cached valid title must not hide an externally corrupted envelope"
    );
    assert_eq!(conn.summary_pages.lock().unwrap().stats().entries, 0);
}

#[test]
fn glitter_pages_bypass_read_transactions_and_preserve_their_snapshot() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("snapshot.mdbx");
    let (conn, _, project, ids) = fixture(VaultConnection::create(&path).unwrap());
    let original = page(&conn, &project);
    let writer = Connection::open(&path).unwrap();
    conn.inner().execute_batch("BEGIN;").unwrap();
    // Pin a read snapshot before the other connection commits.
    conn.inner()
        .query_row("SELECT COUNT(*) FROM entries", [], |row| {
            row.get::<_, i64>(0)
        })
        .unwrap();
    writer
        .execute(
            "UPDATE entries SET deleted = 1 WHERE entry_id = ?1",
            [&ids[0]],
        )
        .unwrap();
    assert_eq!(page(&conn, &project), original);
    assert_eq!(conn.summary_pages.lock().unwrap().stats().entries, 0);
    conn.inner().execute_batch("COMMIT;").unwrap();
    assert_eq!(page(&conn, &project).items.len(), 2);
}

#[test]
fn glitter_pages_do_not_store_a_miss_across_different_sql_versions() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("miss-race.mdbx");
    let (conn, _, project, ids) = fixture(VaultConnection::create(&path).unwrap());
    let before = conn.summary_page_stamp().unwrap().unwrap();
    assert!(conn
        .cached_summary_page("deterministic-race", before)
        .is_none());
    let old = page(&conn, &project);
    Connection::open(&path)
        .unwrap()
        .execute(
            "UPDATE entries SET deleted = 1 WHERE entry_id = ?1",
            [&ids[0]],
        )
        .unwrap();
    conn.cache_summary_page("deterministic-race".into(), before, &old)
        .unwrap();
    assert_eq!(conn.summary_pages.lock().unwrap().stats().entries, 0);
    assert_eq!(page(&conn, &project).items.len(), 2);
}

#[test]
fn glitter_pages_are_bound_to_validated_query_scope_type_size_and_cursor() {
    let (conn, ctx, project, ids) = fixture(VaultConnection::open_in_memory().unwrap());
    let other = ProjectRepo::create(&conn, &ctx, "Other", None, None)
        .unwrap()
        .project_id;
    EntryRepo::soft_delete(&conn, &ctx, &ids[0]).unwrap();
    let all = page(&conn, &project);
    assert_eq!(all.items.len(), 2);
    assert!(page(&conn, &other).items.is_empty());
    assert!(
        ObjectSummaryRepo::list(&conn, &project, Some(&ObjectTypeId::Note), 200, None)
            .unwrap()
            .items
            .is_empty()
    );
    let first = ObjectSummaryRepo::list(&conn, &project, None, 1, None).unwrap();
    assert_eq!(first.items, all.items[..1]);
    let second =
        ObjectSummaryRepo::list(&conn, &project, None, 1, first.next_cursor.as_deref()).unwrap();
    assert_eq!(second.items, all.items[1..]);
    let deleted =
        ObjectSummaryRepo::list_deleted_by_collection(&conn, &project, None, 1, None).unwrap();
    assert_eq!(deleted.items[0].object_id, ids[0]);
    assert!(deleted.items[0].deleted);
    assert_eq!(
        ObjectSummaryRepo::list_deleted_all(&conn, None, 1, None).unwrap(),
        deleted
    );
    assert_eq!(
        ObjectSummaryRepo::list(&conn, &project, None, 1, None).unwrap(),
        first
    );
    assert!(ObjectSummaryRepo::list(&conn, &other, None, 1, first.next_cursor.as_deref()).is_err());
    assert!(ObjectSummaryRepo::list(
        &conn,
        &project,
        Some(&ObjectTypeId::Note),
        1,
        first.next_cursor.as_deref()
    )
    .is_err());
    assert!(ObjectSummaryRepo::list(&conn, &project, None, 0, None).is_err());
    assert!(ObjectSummaryRepo::list(&conn, &project, None, 201, None).is_err());
    assert!(ObjectSummaryRepo::list(&conn, &project, None, 1, Some("invalid cursor")).is_err());
}

#[test]
fn glitter_pages_wipe_on_key_or_session_replacement_lock_and_monotonic_expiry() {
    let (mut conn, _, project, _) = fixture(VaultConnection::open_in_memory().unwrap());
    let old = page(&conn, &project);
    let before = conn.summary_page_stamp().unwrap().unwrap();
    assert_eq!(conn.summary_pages.lock().unwrap().stats().entries, 1);
    conn.attach_session(conn.active_session().unwrap().clone());
    assert_eq!(conn.summary_pages.lock().unwrap().stats().entries, 0);
    assert!(
        conn.summary_page_stamp().unwrap().is_some(),
        "portable cache does not require hardware evidence"
    );
    conn.cache_summary_page("old-session".into(), before, &old)
        .unwrap();
    assert_eq!(conn.summary_pages.lock().unwrap().stats().entries, 0);

    let (mut conn, _, project, _) = fixture(VaultConnection::open_in_memory().unwrap());
    page(&conn, &project);
    conn.attach_keyring(
        mdbx_crypto::keyring::Keyring::from_vault_key(&[7; 32], b"test-key-replacement").unwrap(),
    );
    assert_eq!(conn.summary_pages.lock().unwrap().stats().entries, 0);

    let (mut conn, _, project, _) = fixture(VaultConnection::open_in_memory().unwrap());
    page(&conn, &project);
    conn.clear_session();
    assert_eq!(conn.metadata_cache_stats().entries, 0);
    assert!(conn.summary_page_stamp().unwrap().is_none());

    let (mut conn, _, project, _) = fixture(VaultConnection::open_in_memory().unwrap());
    page(&conn, &project);
    conn.glitter_auth_deadline = Some(Instant::now() - Duration::from_secs(1));
    assert!(conn.summary_page_stamp().unwrap().is_none());
    assert_eq!(conn.summary_pages.lock().unwrap().stats().entries, 0);
    assert!(conn.check_runtime_authentication().is_err());
    assert_eq!(conn.metadata_cache_stats().entries, 0);
}
