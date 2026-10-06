//! Optional session-bound capabilities for the portable Glitter path.

use mdbx_storage::connection::VaultConnection;
use mdbx_storage::runtime::RuntimeLockPoisoned;

use super::{conservative_ffi_device_context, MdbxDeviceContext, MdbxFfiError, MdbxVault};

impl<T> From<std::sync::PoisonError<T>> for MdbxFfiError {
    fn from(_: std::sync::PoisonError<T>) -> Self {
        Self::LockPoisoned
    }
}

impl From<RuntimeLockPoisoned> for MdbxFfiError {
    fn from(error: RuntimeLockPoisoned) -> Self {
        match error {
            RuntimeLockPoisoned::Poisoned => Self::LockPoisoned,
            RuntimeLockPoisoned::AuthenticationRequired => Self::Storage {
                message: "Glitter requires an active password + key-file session".to_string(),
            },
        }
    }
}

pub(crate) fn bound_or_conservative_device_context(conn: &VaultConnection) -> MdbxDeviceContext {
    if conn.is_glitter_session() {
        if let Some(device) = conn.authenticated_device_context() {
            return MdbxDeviceContext {
                assurance: device.assurance.into(),
                secure_clipboard_available: device.secure_clipboard_available,
                screen_capture_protection_available: device.screen_capture_protection_available,
                secure_temp_files_available: device.secure_temp_files_available,
            };
        }
    }
    conservative_ffi_device_context()
}

impl MdbxVault {
    pub(crate) fn default_device_context(&self) -> Result<MdbxDeviceContext, MdbxFfiError> {
        let conn = self.conn.read().map_err(MdbxFfiError::from)?;
        Ok(bound_or_conservative_device_context(&conn))
    }
}

/// Aggregate measurements expose neither titles nor object identities.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct MdbxMetadataCacheStats {
    pub hits: u64,
    pub misses: u64,
    pub entries: u64,
    pub retained_bytes: u64,
    pub byte_limit: u64,
}

#[uniffi::export]
impl MdbxVault {
    pub fn metadata_cache_stats(&self) -> Result<MdbxMetadataCacheStats, MdbxFfiError> {
        let conn = self.conn.read().map_err(MdbxFfiError::from)?;
        let stats = conn.metadata_cache_stats();
        Ok(MdbxMetadataCacheStats {
            hits: stats.hits,
            misses: stats.misses,
            entries: stats.entries as u64,
            retained_bytes: stats.retained_bytes as u64,
            byte_limit: stats.byte_limit as u64,
        })
    }
}
