use mdbx_ffi::*;
use std::sync::{Arc, Barrier};
use uuid::Uuid;

#[test]
fn independent_native_connections_cannot_both_commit_the_same_editor_baseline() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("guarded.mdbx").to_string_lossy().into_owned();
    let password = "guarded-editor-synthetic".to_string();
    let first = create_vault_with_tiga_mode(path.clone(), password.clone(), "first".into(), MdbxTigaMode::Sky).unwrap();
    let folder = Uuid::new_v4().to_string();
    let id = Uuid::new_v4().to_string();
    first.execute_write_operation(Uuid::new_v4().to_string(), "fixture".into(), vec![
        MdbxWriteCommand::CreateProject { project_id: folder.clone(), title: "fixture".into() },
        MdbxWriteCommand::CreateEntry { entry_id: id.clone(), project_id: folder.clone(), entry_type: "login".into(), title: "old".into(), payload_json: "{}".into() },
    ]).unwrap();
    let second = open_vault(path, password, "second".into()).unwrap();
    let expected = first.list_branches().unwrap();
    let vault_id = first.info().vault_id;
    let barrier = Arc::new(Barrier::new(2));
    let handles: Vec<_> = [first.clone(), second.clone()].into_iter().enumerate().map(|(index, vault)| {
        let expected = expected.clone(); let vault_id = vault_id.clone(); let barrier = barrier.clone();
        let id = id.clone(); let folder = folder.clone();
        std::thread::spawn(move || {
            barrier.wait();
            vault.execute_guarded_write_operation(vault_id, expected, Uuid::new_v4().to_string(), vec![
                MdbxWriteCommand::UpdateEntry { entry_id: id, project_id: folder, entry_type: "login".into(),
                    title: format!("writer-{index}"), payload_json: "{}".into() },
            ])
        })
    }).collect();
    let outcomes: Vec<_> = handles.into_iter().map(|h| h.join().unwrap()).collect();
    assert_eq!(outcomes.iter().filter(|r| r.is_ok()).count(), 1);
    let history = first.list_commit_history(100, None).unwrap();
    assert_eq!(history.items.iter().filter(|h| h.operation_kind.as_deref() == Some("native-password-editor")).count(), 1);
    assert!(first.get_object_summary(id).unwrap().unwrap().title.starts_with("writer-"));
}
