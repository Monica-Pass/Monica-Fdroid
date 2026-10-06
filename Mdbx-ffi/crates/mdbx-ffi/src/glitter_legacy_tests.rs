use crate::*;
use mdbx_core::tiga::{TigaPolicyOverride, TigaScope};
use mdbx_storage::repo::CommitContext;
use mdbx_storage::tiga::TigaService;
use mdbx_storage::tiga_policy::TigaAuthorizationContext;

fn trusted_device() -> MdbxDeviceContext {
    MdbxDeviceContext {
        assurance: MdbxDeviceAssurance::TrustedHardware,
        screen_capture_protection_available: true,
        secure_clipboard_available: true,
        secure_temp_files_available: false,
    }
}

#[test]
fn glitter_legacy_payload_getters_fail_closed_and_explicit_reveal_respects_scope() {
    let directory = tempfile::tempdir().unwrap();
    let path = directory
        .path()
        .join("glitter.mdbx")
        .to_string_lossy()
        .into_owned();
    let vault = create_vault_with_password_security_key(
        path,
        "test password".into(),
        vec![7; 32],
        "test".into(),
        MdbxTigaMode::Glitter,
        trusted_device(),
    )
    .unwrap();
    let project = vault.create_project("fixture".into()).unwrap();
    let target = vault.create_project("target".into()).unwrap();
    let entry = vault
        .create_entry(
            project.project_id.clone(),
            "login".into(),
            "account".into(),
            r#"{"username":"alice","password":"must stay protected"}"#.into(),
        )
        .unwrap();
    let id = entry.entry_id.clone();
    let collection = project.project_id.clone();
    let before = vault.get_object_summary(id.clone()).unwrap().unwrap();
    let cases = [
        vault.get_object(collection.clone(), id.clone()).map(|_| ()),
        vault.list_objects(collection.clone(), None).map(|_| ()),
        vault.list_entries(collection.clone(), None).map(|_| ()),
        vault
            .list_deleted_entries(collection.clone(), None)
            .map(|_| ()),
        vault.get_object_relation("absent".into()).map(|_| ()),
        vault
            .list_object_relations_from(id.clone(), None)
            .map(|_| ()),
        vault.list_object_relations_to(id.clone(), None).map(|_| ()),
        vault.list_object_labels(collection.clone()).map(|_| ()),
        vault
            .restore_entry(collection.clone(), id.clone())
            .map(|_| ()),
        vault
            .move_entry(collection.clone(), id.clone(), target.project_id)
            .map(|_| ()),
        vault.list_commit_diff("absent".into()).map(|_| ()),
        vault
            .create_payload_migration_plan(collection.clone(), "login".into(), 1, 2, 1, None)
            .map(|_| ()),
    ];
    for result in cases {
        let error = result.unwrap_err();
        assert!(
            error
                .to_string()
                .contains("explicit policy-authorized reveal"),
            "{error}"
        );
    }
    assert_eq!(
        vault.get_object_summary(id.clone()).unwrap().unwrap(),
        before
    );
    let revealed = vault.reveal_object(id.clone()).unwrap().object.unwrap();
    assert!(revealed.payload_json.contains("must stay protected"));
    {
        let conn = vault.conn.lock().unwrap();
        let session = conn.active_session().cloned();
        let device = trusted_device().into_core("test");
        TigaService::set_policy_override_authorized(
            &conn,
            &CommitContext::new("test".into()),
            TigaScope::Entry {
                entry_id: id.clone(),
            },
            TigaPolicyOverride {
                minimum_auth_factors: Some(3),
                ..Default::default()
            },
            None,
            TigaAuthorizationContext {
                session: session.as_ref(),
                device: &device,
                now_unix_secs: crate::unix_now(),
            },
        )
        .unwrap();
    }
    assert!(vault.reveal_object(id.clone()).unwrap().object.is_none());
    assert!(vault.get_object_summary(id.clone()).unwrap().is_some());
    assert!(vault.get_object(collection, id).is_err());

    // Existing-mode compatibility remains available.
    let old = create_vault(
        directory
            .path()
            .join("multi.mdbx")
            .to_string_lossy()
            .into_owned(),
        "password".into(),
        "test".into(),
    )
    .unwrap();
    let project = old.create_project("legacy".into()).unwrap();
    let entry = old
        .create_entry(
            project.project_id.clone(),
            "login".into(),
            "account".into(),
            r#"{"password":"legacy value"}"#.into(),
        )
        .unwrap();
    assert!(old
        .get_object(project.project_id.clone(), entry.entry_id)
        .unwrap()
        .is_some());
    assert_eq!(old.list_entries(project.project_id, None).unwrap().len(), 1);
}
