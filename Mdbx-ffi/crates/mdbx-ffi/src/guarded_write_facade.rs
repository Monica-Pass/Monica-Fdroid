//! Optimistic editor writes. The causal frontier is checked inside the same
//! IMMEDIATE transaction as objects, history, branch heads and sync deltas.
use std::collections::BTreeMap;

use mdbx_storage::error::StorageError;
use mdbx_storage::repo::{
    BranchRepo, CommitContext, CommitOperation, OperationCoordinator, OperationExecution,
    WriteOperationRequest,
};
use sha2::{Digest, Sha256};

use super::{MdbxBranchInfo, MdbxFfiError, MdbxVault, MdbxWriteCommand, MdbxWriteOperationResult};

#[uniffi::export]
impl MdbxVault {
    /// A stale editor never overwrites an intervening commit, including a commit
    /// on another branch. Replaying the same operation ID and intent is idempotent.
    pub fn execute_guarded_write_operation(
        &self,
        expected_vault_id: String,
        expected_branches: Vec<MdbxBranchInfo>,
        operation_id: String,
        commands: Vec<MdbxWriteCommand>,
    ) -> Result<MdbxWriteOperationResult, MdbxFfiError> {
        if expected_vault_id != self.vault_id || expected_branches.is_empty()
            || expected_branches.len() > 256
        {
            return Err(StorageError::Validation("invalid editor revision".into()).into());
        }
        let mut expected = BTreeMap::new();
        for branch in &expected_branches {
            super::validate_uuid(&branch.branch_id, "branch_id")?;
            super::validate_uuid(&branch.head_commit_id, "head_commit_id")?;
            if expected.insert(branch.branch_id.clone(), branch.head_commit_id.clone()).is_some() {
                return Err(StorageError::Validation("duplicate editor branch".into()).into());
            }
        }
        let main: Vec<_> = expected_branches.iter().filter(|b| b.branch_name == "main").collect();
        if main.len() != 1 {
            return Err(StorageError::Validation("editor branch is ambiguous".into()).into());
        }
        let prepared = OperationCoordinator::prepare(
            WriteOperationRequest::new(operation_id, "native-password-editor",
                commands.into_iter().map(Into::into).collect())
                .with_branch_id(main[0].branch_id.clone()),
        )?;
        let mut hash = Sha256::new();
        hash.update(b"mdbx-guarded-editor-v1");
        hash.update(expected_vault_id.as_bytes());
        for (id, head) in &expected { hash.update(id.as_bytes()); hash.update(head.as_bytes()); }
        hash.update(prepared.intent_hash());
        let operation = CommitOperation::new(
            prepared.operation_id(), prepared.operation_kind(), "", prepared.commit_kind(),
            prepared.change_scope(), prepared.changed_objects().to_vec(),
        ).with_branch_id(main[0].branch_id.clone()).with_intent_hash(hash.finalize().to_vec());
        let conn = self.conn.lock().map_err(MdbxFfiError::from)?;
        let ctx = CommitContext::new(self.device_id.clone());
        let execution = ctx.run_operation(&conn, operation, |scoped| {
            let actual: BTreeMap<_, _> = BranchRepo::list(&conn)?.into_iter()
                .map(|b| (b.branch_id, b.head_commit_id)).collect();
            if actual != expected || conn.vault_id()? != expected_vault_id {
                return Err(StorageError::ConstraintViolation("editor revision changed; reload before saving".into()));
            }
            prepared.apply(&conn, scoped)
        })?;
        let (commit_id, already_committed) = match execution {
            OperationExecution::Applied { commit_id, .. } => (commit_id, false),
            OperationExecution::AlreadyCommitted { commit_id } => (commit_id, true),
        };
        Ok(super::write_facade::write_operation_result(
            prepared.changed_objects(), commit_id, already_committed,
        ))
    }
}
