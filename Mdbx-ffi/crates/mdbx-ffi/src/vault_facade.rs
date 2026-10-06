#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MdbxMigrationInfo {
    pub initialized: bool,
    pub format_version: Option<String>,
    pub schema_version: Option<u32>,
    pub min_reader_version: Option<String>,
    pub min_writer_version: Option<String>,
    pub requires_upgrade: bool,
    pub unknown_critical_extensions: bool,
    pub target_format_version: String,
    pub target_schema_version: u32,
}

impl From<MigrationInfo> for MdbxMigrationInfo {
    fn from(value: MigrationInfo) -> Self {
        Self {
            initialized: value.initialized,
            format_version: value.format_version,
            schema_version: value.schema_version,
            min_reader_version: value.min_reader_version,
            min_writer_version: value.min_writer_version,
            requires_upgrade: value.requires_upgrade,
            unknown_critical_extensions: value.unknown_critical_extensions,
            target_format_version: value.target_format_version,
            target_schema_version: value.target_schema_version,
        }
    }
}

use std::path::Path;
use std::sync::Arc;

use mdbx_core::tiga::TigaMode;
use mdbx_storage::backup::BackupService;
use mdbx_storage::connection::{PendingVaultCreation, VaultConnection};
use mdbx_storage::init::{initialize_vault, initialize_vault_with_device_context, VaultInitParams};
use mdbx_storage::migration::{inspect_migration_path, upgrade_path, MigrationInfo};
use mdbx_storage::runtime::VaultRuntime;
use mdbx_storage::unlock::UnlockService;
use zeroize::Zeroizing;

use super::{MdbxBackupInfo, MdbxDeviceContext, MdbxFfiError, MdbxTigaMode, MdbxVault};

#[uniffi::export]
pub fn create_vault(
    path: String,
    password: String,
    device_id: String,
) -> Result<Arc<MdbxVault>, MdbxFfiError> {
    create_vault_with_tiga_mode(path, password, device_id, MdbxTigaMode::Multi)
}

/// Read migration metadata without opening the vault for writing.
#[uniffi::export]
pub fn inspect_vault_migration(path: String) -> Result<MdbxMigrationInfo, MdbxFfiError> {
    Ok(inspect_migration_path(Path::new(&path))?.into())
}

/// Create a verified portable backup without writable open, unlock, or
/// automatic migration of the source vault.
#[uniffi::export]
pub fn create_portable_backup(
    source_path: String,
    destination: String,
) -> Result<MdbxBackupInfo, MdbxFfiError> {
    Ok(
        BackupService::create_portable_copy_path(Path::new(&source_path), Path::new(&destination))?
            .into(),
    )
}

/// Explicitly run the storage-core migration after the client has inspected,
/// backed up, and obtained user consent. The compatibility `open_vault` path
/// remains automatic for callers that do not need this orchestration.
#[uniffi::export]
pub fn upgrade_vault(path: String) -> Result<MdbxMigrationInfo, MdbxFfiError> {
    upgrade_path(Path::new(&path))?;
    Ok(inspect_migration_path(Path::new(&path))?.into())
}

#[uniffi::export]
pub fn create_vault_with_tiga_mode(
    path: String,
    password: String,
    device_id: String,
    mode: MdbxTigaMode,
) -> Result<Arc<MdbxVault>, MdbxFfiError> {
    let mode: TigaMode = mode.into();
    if mode == TigaMode::Glitter {
        return Err(MdbxFfiError::from(
            mdbx_storage::error::StorageError::Validation(
                "Glitter requires direct password + key-file creation".into(),
            ),
        ));
    }
    let mut creation = PendingVaultCreation::begin(Path::new(&path))?;
    let init = initialize_vault(
        creation.connection(),
        &VaultInitParams {
            default_tiga_mode: mode.to_string(),
            device_id: device_id.clone(),
            ..Default::default()
        },
    )?;
    let password = Zeroizing::new(password);
    UnlockService::setup_password_with_mode(creation.connection_mut(), password.as_str(), mode)?;
    let conn = creation.commit();
    Ok(Arc::new(MdbxVault {
        conn: VaultRuntime::from_connection(conn),
        device_id,
        vault_id: init.vault_id,
    }))
}

/// Create atomically with only a combined wrapper. Standard portable clients
/// need no hardware authentication; capability assertions must remain truthful.
#[uniffi::export]
pub fn create_vault_with_password_security_key(
    path: String,
    password: String,
    key_material: Vec<u8>,
    device_id: String,
    mode: MdbxTigaMode,
    device_context: MdbxDeviceContext,
) -> Result<Arc<MdbxVault>, MdbxFfiError> {
    let password = Zeroizing::new(password);
    let key_material = Zeroizing::new(key_material);
    let mode: TigaMode = mode.into();
    let device = device_context.into_core(&device_id);
    if mode == TigaMode::Glitter {
        UnlockService::validate_glitter_device(&device)?;
    }
    let mut creation = PendingVaultCreation::begin(Path::new(&path))?;
    let init = initialize_vault_with_device_context(
        creation.connection(),
        &VaultInitParams {
            default_tiga_mode: mode.to_string(),
            device_id: device_id.clone(),
            ..Default::default()
        },
        &device,
    )?;
    UnlockService::setup_password_security_key_with_device_context(
        creation.connection_mut(),
        password.as_str(),
        key_material.as_slice(),
        mode,
        &device,
    )?;
    Ok(Arc::new(MdbxVault {
        conn: VaultRuntime::from_connection(creation.commit()),
        device_id,
        vault_id: init.vault_id,
    }))
}

#[uniffi::export]
pub fn open_vault(
    path: String,
    password: String,
    device_id: String,
) -> Result<Arc<MdbxVault>, MdbxFfiError> {
    let mut conn = VaultConnection::open(Path::new(&path))?;
    let password = Zeroizing::new(password);
    UnlockService::unlock_with_password(&mut conn, password.as_str())?;
    let vault_id = read_vault_id(&conn)?;
    Ok(Arc::new(MdbxVault {
        conn: VaultRuntime::from_connection(conn),
        device_id,
        vault_id,
    }))
}

#[uniffi::export]
pub fn open_vault_with_security_key(
    path: String,
    key_material: Vec<u8>,
    device_id: String,
) -> Result<Arc<MdbxVault>, MdbxFfiError> {
    let mut conn = VaultConnection::open(Path::new(&path))?;
    let key_material = Zeroizing::new(key_material);
    UnlockService::unlock_with_security_key(&mut conn, key_material.as_slice())?;
    let vault_id = read_vault_id(&conn)?;
    Ok(Arc::new(MdbxVault {
        conn: VaultRuntime::from_connection(conn),
        device_id,
        vault_id,
    }))
}

#[uniffi::export]
pub fn open_vault_with_password_security_key(
    path: String,
    password: String,
    key_material: Vec<u8>,
    device_id: String,
) -> Result<Arc<MdbxVault>, MdbxFfiError> {
    let mut conn = VaultConnection::open(Path::new(&path))?;
    let password = Zeroizing::new(password);
    let key_material = Zeroizing::new(key_material);
    UnlockService::unlock_with_password_security_key(
        &mut conn,
        password.as_str(),
        key_material.as_slice(),
    )?;
    let vault_id = read_vault_id(&conn)?;
    Ok(Arc::new(MdbxVault {
        conn: VaultRuntime::from_connection(conn),
        device_id,
        vault_id,
    }))
}

#[uniffi::export]
pub fn open_vault_with_password_security_key_and_device_context(
    path: String,
    password: String,
    key_material: Vec<u8>,
    device_id: String,
    device_context: MdbxDeviceContext,
) -> Result<Arc<MdbxVault>, MdbxFfiError> {
    let password = Zeroizing::new(password);
    let key_material = Zeroizing::new(key_material);
    let device = device_context.into_core(&device_id);
    let mut conn = VaultConnection::open(Path::new(&path))?;
    UnlockService::unlock_with_password_security_key_and_device_context(
        &mut conn,
        password.as_str(),
        key_material.as_slice(),
        &device,
    )?;
    let vault_id = read_vault_id(&conn)?;
    Ok(Arc::new(MdbxVault {
        conn: VaultRuntime::from_connection(conn),
        device_id,
        vault_id,
    }))
}

fn read_vault_id(conn: &VaultConnection) -> Result<String, MdbxFfiError> {
    conn.vault_id().map_err(MdbxFfiError::from)
}

#[cfg(test)]
mod glitter_tests {
    use super::*;
    use crate::MdbxDeviceAssurance;

    fn portable_device() -> MdbxDeviceContext {
        MdbxDeviceContext {
            assurance: MdbxDeviceAssurance::Standard,
            screen_capture_protection_available: false,
            secure_clipboard_available: false,
            secure_temp_files_available: false,
        }
    }

    #[test]
    fn glitter_rejects_unsupported_creation_without_files() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory
            .path()
            .join("unsupported.mdbx")
            .to_string_lossy()
            .into_owned();
        assert!(create_vault_with_tiga_mode(
            path.clone(),
            "password".into(),
            "test".into(),
            MdbxTigaMode::Glitter
        )
        .is_err());
        assert_eq!(std::fs::read_dir(directory.path()).unwrap().count(), 0);
        // Failure after file reservation is rolled back by PendingVaultCreation.
        assert!(create_vault_with_password_security_key(
            path,
            "password".into(),
            vec![],
            "test".into(),
            MdbxTigaMode::Glitter,
            portable_device()
        )
        .is_err());
        assert_eq!(std::fs::read_dir(directory.path()).unwrap().count(), 0);
    }

    #[test]
    fn glitter_real_portable_roundtrip_enforces_both_factors() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory
            .path()
            .join("glitter.mdbx")
            .to_string_lossy()
            .into_owned();
        let vault = create_vault_with_password_security_key(
            path.clone(),
            "correct password".into(),
            vec![7; 32],
            "test".into(),
            MdbxTigaMode::Glitter,
            portable_device(),
        )
        .unwrap();
        {
            let conn = vault.conn.lock().unwrap();
            let methods = UnlockService::list_methods(&conn).unwrap();
            assert_eq!(methods.len(), 1);
            assert_eq!(
                methods[0].method_type,
                mdbx_core::model::UnlockMethodType::PasswordSecurityKey
            );
            assert!(
                UnlockService::assess_tiga_unlock_policy(&conn, TigaMode::Glitter)
                    .unwrap()
                    .satisfies_policy
            );
            assert_eq!(
                mdbx_storage::tiga::TigaService::resolve_vault_policy(&conn)
                    .unwrap()
                    .policy
                    .policy_version,
                3
            );
        }
        let project = vault
            .create_project("Glitter functional test".into())
            .unwrap();
        let entry = vault
            .create_entry(
                project.project_id,
                "login".into(),
                "account".into(),
                r#"{"username":"alice","password":"verified secret"}"#.into(),
            )
            .unwrap();
        assert!(vault
            .get_object_summary(entry.entry_id.clone())
            .unwrap()
            .is_some());
        drop(vault);
        assert!(
            !inspect_vault_migration(path.clone())
                .unwrap()
                .unknown_critical_extensions
        );
        assert!(open_vault(path.clone(), "correct password".into(), "test".into()).is_err());
        assert!(open_vault_with_security_key(path.clone(), vec![7; 32], "test".into()).is_err());
        assert!(open_vault_with_password_security_key_and_device_context(
            path.clone(),
            "wrong password".into(),
            vec![7; 32],
            "test".into(),
            portable_device()
        )
        .is_err());
        assert!(open_vault_with_password_security_key_and_device_context(
            path.clone(),
            "correct password".into(),
            vec![8; 32],
            "test".into(),
            portable_device()
        )
        .is_err());
        let reopened = open_vault_with_password_security_key(
            path.clone(),
            "correct password".into(),
            vec![7; 32],
            "another-device".into(),
        )
        .unwrap();
        let summary = reopened
            .get_object_summary(entry.entry_id.clone())
            .unwrap()
            .unwrap();
        assert_eq!(summary.title, "account");
        assert!(reopened
            .reveal_object(entry.entry_id.clone())
            .unwrap()
            .object
            .unwrap()
            .payload_json
            .contains("verified secret"));
        let copy_path = directory
            .path()
            .join("portable-copy.mdbx")
            .to_string_lossy()
            .into_owned();
        create_portable_backup(path.clone(), copy_path.clone()).unwrap();
        let copied = open_vault_with_password_security_key(
            copy_path,
            "correct password".into(),
            vec![7; 32],
            "third-device".into(),
        )
        .unwrap();
        assert_eq!(
            copied
                .get_object_summary(entry.entry_id.clone())
                .unwrap()
                .unwrap()
                .title,
            "account"
        );
        drop(copied);
        assert!(reopened
            .setup_local_security_key_unlock_with_device_context(vec![9; 32], portable_device())
            .is_err());
        assert!(reopened
            .set_tiga_profile(
                MdbxTigaMode::Sky,
                Some("test downgrade".into()),
                None,
                portable_device()
            )
            .is_err());
        let original_session = reopened
            .conn
            .lock()
            .unwrap()
            .active_session()
            .unwrap()
            .clone();
        reopened
            .setup_password_security_key_unlock(
                "backup password".into(),
                vec![9; 32],
                portable_device(),
            )
            .unwrap();
        {
            let conn = reopened.conn.lock().unwrap();
            let active = conn.active_session().unwrap();
            assert_eq!(active.session_id, original_session.session_id);
            assert_eq!(
                active.assurance.authenticated_at_unix_secs,
                original_session.assurance.authenticated_at_unix_secs
            );
        }
        drop(reopened);
        let backup = open_vault_with_password_security_key_and_device_context(
            path.clone(),
            "backup password".into(),
            vec![9; 32],
            "test".into(),
            portable_device(),
        )
        .unwrap();
        assert!(backup.get_object_summary(entry.entry_id).unwrap().is_some());
        drop(backup);
        // Even coherently replacing mode/version/extension cannot bypass the
        // authenticated header. No-context legacy open must not gain access.
        let raw = rusqlite::Connection::open(&path).unwrap();
        raw.execute("UPDATE vault_meta SET default_tiga_mode='power', tiga_policy_version=2, critical_extensions=''", []).unwrap();
        let trigger_sql: String = raw.query_row("SELECT sql FROM sqlite_master WHERE name='trg_vault_meta_header_auth_no_pending_downgrade'", [], |row| row.get(0)).unwrap();
        raw.execute_batch(
            "DROP TRIGGER trg_vault_meta_header_auth_no_pending_downgrade;
            UPDATE vault_meta SET header_integrity_profile='pending', header_integrity_tag=NULL;",
        )
        .unwrap();
        raw.execute_batch(&trigger_sql).unwrap();
        drop(raw);
        let error = open_vault_with_password_security_key(
            path,
            "correct password".into(),
            vec![7; 32],
            "test".into(),
        )
        .err()
        .unwrap();
        assert!(
            error.to_string().contains("incorrect credential"),
            "{error}"
        );
    }
}
