#[derive(Debug, Clone, uniffi::Record)]
pub struct VaultInfo {
    pub vault_id: String,
    pub device_id: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MdbxBackupInfo {
    pub vault_id: String,
    pub format_version: String,
    pub schema_version: u32,
    pub file_size_bytes: u64,
}

impl From<VaultBackupInfo> for MdbxBackupInfo {
    fn from(value: VaultBackupInfo) -> Self {
        Self {
            vault_id: value.vault_id,
            format_version: value.format_version,
            schema_version: value.schema_version,
            file_size_bytes: value.file_size_bytes,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum MdbxHealthIssueSeverity {
    Info,
    Warning,
    Error,
    Critical,
}

impl From<IssueSeverity> for MdbxHealthIssueSeverity {
    fn from(value: IssueSeverity) -> Self {
        match value {
            IssueSeverity::Info => Self::Info,
            IssueSeverity::Warning => Self::Warning,
            IssueSeverity::Error => Self::Error,
            IssueSeverity::Critical => Self::Critical,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MdbxHealthIssue {
    pub severity: MdbxHealthIssueSeverity,
    pub category: String,
    pub description: String,
}

impl From<HealthIssue> for MdbxHealthIssue {
    fn from(value: HealthIssue) -> Self {
        Self {
            severity: value.severity.into(),
            category: value.category,
            description: value.description,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MdbxHealthCheckResult {
    pub healthy: bool,
    pub issues: Vec<MdbxHealthIssue>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum MdbxHealthRepairItemKind {
    MissingTombstone,
    DuplicateTombstones,
    ActiveObjectTombstoneConflict,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MdbxHealthRepairItem {
    pub repair_id: String,
    pub kind: MdbxHealthRepairItemKind,
    pub object_type: String,
    pub object_id: String,
    pub tombstone_count: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MdbxHealthRepairBlocker {
    pub category: String,
    pub description: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MdbxHealthRepairPlan {
    pub token: String,
    pub automatic_items: Vec<MdbxHealthRepairItem>,
    pub conflict_items: Vec<MdbxHealthRepairItem>,
    pub blockers: Vec<MdbxHealthRepairBlocker>,
    pub can_apply: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum MdbxHealthRepairChoice {
    KeepContent,
    DeleteObject,
    Cancel,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MdbxHealthRepairDecision {
    pub repair_id: String,
    pub choice: MdbxHealthRepairChoice,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum MdbxHealthRepairStatus {
    Applied,
    Cancelled,
    NoChanges,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MdbxHealthRepairApplyResult {
    pub status: MdbxHealthRepairStatus,
    pub snapshot_id: Option<String>,
    pub commit_id: Option<String>,
    pub repaired_count: u64,
    pub already_committed: bool,
    pub health: MdbxHealthCheckResult,
}

/// Constant-time aggregate metadata used by client diagnostics screens.
///
/// Counts are read directly from authenticated vault tables while the vault
/// session lock is held. Payload ciphertext is never selected or returned.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct MdbxVaultDiagnosticsSummary {
    pub commit_count: u64,
    pub tombstone_count: u64,
    pub branch_count: u64,
    pub device_count: u64,
    pub snapshot_count: u64,
    pub unresolved_conflict_count: u64,
    pub project_count: u64,
    pub deleted_project_count: u64,
    pub entry_count: u64,
    pub deleted_entry_count: u64,
    pub attachment_count: u64,
    pub deleted_attachment_count: u64,
    pub external_attachment_count: u64,
    pub original_attachment_bytes: u64,
    pub stored_attachment_bytes: u64,
}

impl From<HealthCheckResult> for MdbxHealthCheckResult {
    fn from(value: HealthCheckResult) -> Self {
        Self {
            healthy: value.healthy,
            issues: value.issues.into_iter().map(Into::into).collect(),
        }
    }
}

impl From<HealthRepairItemKind> for MdbxHealthRepairItemKind {
    fn from(value: HealthRepairItemKind) -> Self {
        match value {
            HealthRepairItemKind::MissingTombstone => Self::MissingTombstone,
            HealthRepairItemKind::DuplicateTombstones => Self::DuplicateTombstones,
            HealthRepairItemKind::ActiveObjectTombstoneConflict => {
                Self::ActiveObjectTombstoneConflict
            }
        }
    }
}

impl From<HealthRepairItem> for MdbxHealthRepairItem {
    fn from(value: HealthRepairItem) -> Self {
        Self {
            repair_id: value.repair_id,
            kind: value.kind.into(),
            object_type: value.object_type,
            object_id: value.object_id,
            tombstone_count: u64::from(value.tombstone_count),
        }
    }
}

impl From<HealthRepairBlocker> for MdbxHealthRepairBlocker {
    fn from(value: HealthRepairBlocker) -> Self {
        Self {
            category: value.category,
            description: value.description,
        }
    }
}

impl From<HealthRepairPlan> for MdbxHealthRepairPlan {
    fn from(value: HealthRepairPlan) -> Self {
        let can_apply = value.can_apply();
        Self {
            token: value.token,
            automatic_items: value.automatic_items.into_iter().map(Into::into).collect(),
            conflict_items: value.conflict_items.into_iter().map(Into::into).collect(),
            blockers: value.blockers.into_iter().map(Into::into).collect(),
            can_apply,
        }
    }
}

impl From<MdbxHealthRepairChoice> for HealthRepairChoice {
    fn from(value: MdbxHealthRepairChoice) -> Self {
        match value {
            MdbxHealthRepairChoice::KeepContent => Self::KeepContent,
            MdbxHealthRepairChoice::DeleteObject => Self::DeleteObject,
            MdbxHealthRepairChoice::Cancel => Self::Cancel,
        }
    }
}

impl From<HealthRepairStatus> for MdbxHealthRepairStatus {
    fn from(value: HealthRepairStatus) -> Self {
        match value {
            HealthRepairStatus::Applied => Self::Applied,
            HealthRepairStatus::Cancelled => Self::Cancelled,
            HealthRepairStatus::NoChanges => Self::NoChanges,
        }
    }
}

impl From<HealthRepairApplyResult> for MdbxHealthRepairApplyResult {
    fn from(value: HealthRepairApplyResult) -> Self {
        Self {
            status: value.status.into(),
            snapshot_id: value.snapshot_id,
            commit_id: value.commit_id,
            repaired_count: u64::from(value.repaired_count),
            already_committed: value.already_committed,
            health: value.health.into(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MdbxTombstonePurgeBlocker {
    pub code: String,
    pub device_id: Option<String>,
    pub commit_id: Option<String>,
    pub timestamp: Option<String>,
    pub dependent_object_type: Option<String>,
    pub dependent_object_count: Option<u64>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MdbxTombstoneRecord {
    pub tombstone_id: String,
    pub target_object_type: String,
    pub target_object_id: String,
    pub delete_clock: String,
    pub deleted_by_device_id: String,
    pub deleted_at: String,
    pub purge_eligible_at: Option<String>,
    pub delete_commit_id: Option<String>,
}

impl From<Tombstone> for MdbxTombstoneRecord {
    fn from(value: Tombstone) -> Self {
        Self {
            tombstone_id: value.tombstone_id,
            target_object_type: value.target_object_type.to_string(),
            target_object_id: value.target_object_id,
            delete_clock: value.delete_clock,
            deleted_by_device_id: value.deleted_by_device_id,
            deleted_at: value.deleted_at,
            purge_eligible_at: value.purge_eligible_at,
            delete_commit_id: value.delete_commit_id,
        }
    }
}

impl From<TombstonePurgeBlocker> for MdbxTombstonePurgeBlocker {
    fn from(value: TombstonePurgeBlocker) -> Self {
        match value {
            TombstonePurgeBlocker::RetentionNotScheduled => Self::new("retention-not-scheduled"),
            TombstonePurgeBlocker::RetentionPeriodActive { eligible_at } => Self {
                timestamp: Some(eligible_at),
                ..Self::new("retention-period-active")
            },
            TombstonePurgeBlocker::InvalidRetentionTimestamp { value } => Self {
                timestamp: Some(value),
                ..Self::new("invalid-retention-timestamp")
            },
            TombstonePurgeBlocker::MissingDeleteCommit => Self::new("missing-delete-commit"),
            TombstonePurgeBlocker::DeleteCommitMissing { commit_id } => Self {
                commit_id: Some(commit_id),
                ..Self::new("delete-commit-missing")
            },
            TombstonePurgeBlocker::TargetMissing => Self::new("target-missing"),
            TombstonePurgeBlocker::TargetNotDeleted => Self::new("target-not-deleted"),
            TombstonePurgeBlocker::UnresolvedConflict => Self::new("unresolved-conflict"),
            TombstonePurgeBlocker::DeviceHasNotAcknowledgedDelete { device_id } => Self {
                device_id: Some(device_id),
                ..Self::new("device-has-not-acknowledged-delete")
            },
            TombstonePurgeBlocker::DependentObjectsRemain { object_type, count } => Self {
                dependent_object_type: Some(object_type),
                dependent_object_count: Some(count),
                ..Self::new("dependent-objects-remain")
            },
            TombstonePurgeBlocker::UnsupportedTargetType => Self::new("unsupported-target-type"),
        }
    }
}

impl MdbxTombstonePurgeBlocker {
    fn new(code: &str) -> Self {
        Self {
            code: code.to_string(),
            device_id: None,
            commit_id: None,
            timestamp: None,
            dependent_object_type: None,
            dependent_object_count: None,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MdbxTombstonePurgeEligibility {
    pub tombstone_id: String,
    pub eligible: bool,
    pub blockers: Vec<MdbxTombstonePurgeBlocker>,
}

impl From<TombstonePurgeEligibility> for MdbxTombstonePurgeEligibility {
    fn from(value: TombstonePurgeEligibility) -> Self {
        Self {
            tombstone_id: value.tombstone_id,
            eligible: value.eligible,
            blockers: value.blockers.into_iter().map(Into::into).collect(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MdbxTombstonePurgeScheduleResult {
    pub tombstone_id: String,
    pub purge_eligible_at: String,
    pub commit_id: String,
}

impl From<TombstonePurgeScheduleResult> for MdbxTombstonePurgeScheduleResult {
    fn from(value: TombstonePurgeScheduleResult) -> Self {
        Self {
            tombstone_id: value.tombstone_id,
            purge_eligible_at: value.purge_eligible_at,
            commit_id: value.commit_id,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct MdbxPermanentPurgeReceipt {
    pub purge_id: String,
    pub tombstone_id: String,
    pub target_object_type: String,
    pub target_object_id: String,
    pub delete_commit_id: String,
    pub purge_commit_id: String,
    pub delete_clock: String,
    pub retention_eligible_at: String,
    pub purged_by_device_id: String,
    pub purged_at: String,
    pub integrity_tag: Vec<u8>,
}

impl From<PermanentPurgeReceipt> for MdbxPermanentPurgeReceipt {
    fn from(value: PermanentPurgeReceipt) -> Self {
        Self {
            purge_id: value.purge_id,
            tombstone_id: value.tombstone_id,
            target_object_type: value.target_object_type,
            target_object_id: value.target_object_id,
            delete_commit_id: value.delete_commit_id,
            purge_commit_id: value.purge_commit_id,
            delete_clock: value.delete_clock,
            retention_eligible_at: value.retention_eligible_at,
            purged_by_device_id: value.purged_by_device_id,
            purged_at: value.purged_at,
            integrity_tag: value.integrity_tag,
        }
    }
}

use std::path::Path;

use mdbx_core::model::Tombstone;
use mdbx_storage::backup::{BackupService, VaultBackupInfo};
use mdbx_storage::error::StorageError;
use mdbx_storage::health_repair::{
    HealthRepairApplyResult, HealthRepairBlocker, HealthRepairChoice, HealthRepairDecision,
    HealthRepairItem, HealthRepairItemKind, HealthRepairPlan, HealthRepairService,
    HealthRepairStatus,
};
use mdbx_storage::recovery::{HealthCheckResult, HealthIssue, IssueSeverity, RecoveryVerifier};
use mdbx_storage::repo::{
    CommitContext, PermanentPurgeReceipt, TombstonePurgeBlocker, TombstonePurgeEligibility,
    TombstonePurgeScheduleResult, TombstoneRepo,
};
use mdbx_storage::tiga_policy::TigaAuthorizationContext;

use super::{unix_now, MdbxDeviceContext, MdbxFfiError, MdbxVault};

#[uniffi::export]
impl MdbxVault {
    pub fn info(&self) -> VaultInfo {
        VaultInfo {
            vault_id: self.vault_id.clone(),
            device_id: self.device_id.clone(),
        }
    }

    pub fn create_backup(&self, destination: String) -> Result<MdbxBackupInfo, MdbxFfiError> {
        let conn = self.conn.lock().map_err(|_| MdbxFfiError::LockPoisoned)?;
        Ok(BackupService::create_portable_copy(&conn, Path::new(&destination))?.into())
    }

    pub fn health_check(&self) -> Result<MdbxHealthCheckResult, MdbxFfiError> {
        let conn = self.conn.lock().map_err(|_| MdbxFfiError::LockPoisoned)?;
        Ok(RecoveryVerifier::full_health_check(&conn)?.into())
    }

    pub fn plan_health_repair(&self) -> Result<MdbxHealthRepairPlan, MdbxFfiError> {
        let conn = self.conn.lock().map_err(|_| MdbxFfiError::LockPoisoned)?;
        Ok(HealthRepairService::plan(&conn)?.into())
    }

    pub fn apply_health_repair(
        &self,
        plan_token: String,
        operation_id: String,
        decisions: Vec<MdbxHealthRepairDecision>,
    ) -> Result<MdbxHealthRepairApplyResult, MdbxFfiError> {
        let conn = self.conn.lock().map_err(|_| MdbxFfiError::LockPoisoned)?;
        let ctx = CommitContext::new(self.device_id.clone());
        let decisions = decisions
            .into_iter()
            .map(|decision| HealthRepairDecision {
                repair_id: decision.repair_id,
                choice: decision.choice.into(),
            })
            .collect::<Vec<_>>();
        Ok(HealthRepairService::apply(&conn, &ctx, &plan_token, &operation_id, &decisions)?.into())
    }

    pub fn diagnostics_summary(&self) -> Result<MdbxVaultDiagnosticsSummary, MdbxFfiError> {
        let conn = self.conn.lock().map_err(|_| MdbxFfiError::LockPoisoned)?;
        let values = conn
            .inner()
            .query_row(
                "SELECT
                    (SELECT COUNT(*) FROM commits),
                    (SELECT COUNT(*) FROM tombstones),
                    (SELECT COUNT(*) FROM branches),
                    (SELECT COUNT(*) FROM device_heads),
                    (SELECT COUNT(*) FROM snapshots),
                    (SELECT COUNT(*) FROM conflicts WHERE resolution = 'unresolved'),
                    (SELECT COUNT(*) FROM projects WHERE deleted = 0),
                    (SELECT COUNT(*) FROM projects WHERE deleted <> 0),
                    (SELECT COUNT(*) FROM entries WHERE deleted = 0),
                    (SELECT COUNT(*) FROM entries WHERE deleted <> 0),
                    (SELECT COUNT(*) FROM attachments WHERE deleted = 0),
                    (SELECT COUNT(*) FROM attachments WHERE deleted <> 0),
                    (SELECT COUNT(*) FROM attachments
                        WHERE deleted = 0 AND storage_mode = 'external-hash-ref'),
                    (SELECT COALESCE(SUM(original_size), 0) FROM attachments WHERE deleted = 0),
                    (SELECT COALESCE(SUM(stored_size), 0) FROM attachments WHERE deleted = 0)",
                [],
                |row| {
                    Ok([
                        row.get::<_, i64>(0)?,
                        row.get::<_, i64>(1)?,
                        row.get::<_, i64>(2)?,
                        row.get::<_, i64>(3)?,
                        row.get::<_, i64>(4)?,
                        row.get::<_, i64>(5)?,
                        row.get::<_, i64>(6)?,
                        row.get::<_, i64>(7)?,
                        row.get::<_, i64>(8)?,
                        row.get::<_, i64>(9)?,
                        row.get::<_, i64>(10)?,
                        row.get::<_, i64>(11)?,
                        row.get::<_, i64>(12)?,
                        row.get::<_, i64>(13)?,
                        row.get::<_, i64>(14)?,
                    ])
                },
            )
            .map_err(StorageError::from)?;
        let values = values
            .into_iter()
            .enumerate()
            .map(|(index, value)| nonnegative_diagnostic_value(index, value))
            .collect::<Result<Vec<_>, _>>()?;
        Ok(MdbxVaultDiagnosticsSummary {
            commit_count: values[0],
            tombstone_count: values[1],
            branch_count: values[2],
            device_count: values[3],
            snapshot_count: values[4],
            unresolved_conflict_count: values[5],
            project_count: values[6],
            deleted_project_count: values[7],
            entry_count: values[8],
            deleted_entry_count: values[9],
            attachment_count: values[10],
            deleted_attachment_count: values[11],
            external_attachment_count: values[12],
            original_attachment_bytes: values[13],
            stored_attachment_bytes: values[14],
        })
    }

    pub fn evaluate_tombstone_purge_eligibility(
        &self,
        tombstone_id: String,
        now: String,
    ) -> Result<MdbxTombstonePurgeEligibility, MdbxFfiError> {
        let conn = self.conn.lock().map_err(|_| MdbxFfiError::LockPoisoned)?;
        Ok(TombstoneRepo::evaluate_purge_eligibility(&conn, &tombstone_id, &now)?.into())
    }

    pub fn find_tombstone_by_target(
        &self,
        target_object_id: String,
    ) -> Result<Option<MdbxTombstoneRecord>, MdbxFfiError> {
        let conn = self.conn.lock().map_err(|_| MdbxFfiError::LockPoisoned)?;
        Ok(TombstoneRepo::find_by_target(&conn, &target_object_id)?.map(Into::into))
    }

    pub fn find_permanent_purge_receipt_by_tombstone(
        &self,
        tombstone_id: String,
    ) -> Result<Option<MdbxPermanentPurgeReceipt>, MdbxFfiError> {
        let conn = self.conn.lock().map_err(|_| MdbxFfiError::LockPoisoned)?;
        Ok(TombstoneRepo::find_purge_receipt_by_tombstone(&conn, &tombstone_id)?.map(Into::into))
    }

    pub fn find_permanent_purge_receipt_by_target(
        &self,
        target_object_type: String,
        target_object_id: String,
    ) -> Result<Option<MdbxPermanentPurgeReceipt>, MdbxFfiError> {
        let conn = self.conn.lock().map_err(|_| MdbxFfiError::LockPoisoned)?;
        Ok(TombstoneRepo::find_purge_receipt_by_target(
            &conn,
            &target_object_type,
            &target_object_id,
        )?
        .map(Into::into))
    }

    pub fn schedule_tombstone_purge(
        &self,
        tombstone_id: String,
        purge_eligible_at: String,
        device: MdbxDeviceContext,
    ) -> Result<MdbxTombstonePurgeScheduleResult, MdbxFfiError> {
        let conn = self.conn.lock().map_err(|_| MdbxFfiError::LockPoisoned)?;
        let session = conn.active_session().cloned();
        let device = device.into_core(&self.device_id);
        let ctx = CommitContext::new(self.device_id.clone());
        let (result, _) = TombstoneRepo::schedule_purge_authorized(
            &conn,
            &ctx,
            &tombstone_id,
            &purge_eligible_at,
            TigaAuthorizationContext {
                session: session.as_ref(),
                device: &device,
                now_unix_secs: unix_now(),
            },
        )?;
        Ok(result.into())
    }

    pub fn purge_tombstone(
        &self,
        tombstone_id: String,
        device: MdbxDeviceContext,
    ) -> Result<MdbxPermanentPurgeReceipt, MdbxFfiError> {
        let conn = self.conn.lock().map_err(|_| MdbxFfiError::LockPoisoned)?;
        let session = conn.active_session().cloned();
        let device = device.into_core(&self.device_id);
        let ctx = CommitContext::new(self.device_id.clone());
        let (receipt, _) = TombstoneRepo::purge_authorized(
            &conn,
            &ctx,
            &tombstone_id,
            TigaAuthorizationContext {
                session: session.as_ref(),
                device: &device,
                now_unix_secs: unix_now(),
            },
        )?;
        Ok(receipt.into())
    }
}

fn nonnegative_diagnostic_value(index: usize, value: i64) -> Result<u64, MdbxFfiError> {
    u64::try_from(value).map_err(|_| MdbxFfiError::Storage {
        message: format!("negative diagnostics value at column {index}"),
    })
}
