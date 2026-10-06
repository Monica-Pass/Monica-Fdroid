//! Process-local credential use; no serialized policy, operation enum or schema changes.
use super::*;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::time::{Duration, Instant};

/// An opaque, non-serializable authorization issued only after fresh authentication.
/// Dropping/revoking it or changing the connection's authenticated state ends access.
/// Brokers must additionally enforce their audience's routes, budgets and revocation.
pub struct CredentialUseLease {
    object_id: String,
    audience: String,
    epoch: uuid::Uuid,
    policy: ResolvedTigaPolicy,
    entry: EntryIdentity,
    device: DeviceContext,
    constraints: Vec<AuthorizationConstraint>,
    expires_at: i64,
    deadline: Instant,
    last_checked: AtomicI64,
    revoked: AtomicBool,
}

#[derive(PartialEq, Eq)]
struct EntryIdentity {
    collection: String,
    kind: String,
    version: u32,
    head: String,
}

impl EntryIdentity {
    fn read(conn: &VaultConnection, object_id: &str) -> StorageResult<Self> {
        // No title or payload decryption before authorization.
        conn.inner().query_row(
            "SELECT project_id, entry_type, payload_schema_version, head_commit_id FROM entries WHERE entry_id = ?1 AND deleted = 0",
            [object_id],
            |row| Ok(Self { collection: row.get(0)?, kind: row.get(1)?, version: row.get(2)?, head: row.get(3)? }),
        ).map_err(StorageError::Database)
    }
}

impl CredentialUseLease {
    pub fn object_id(&self) -> &str {
        &self.object_id
    }
    pub fn expires_at_unix_secs(&self) -> i64 {
        self.expires_at
    }
    pub fn revoke(&self) {
        self.revoked.store(true, Ordering::Release);
    }
}

fn denied(reason: AuthorizationReason) -> AuthorizationDecision {
    AuthorizationDecision {
        outcome: AuthorizationOutcome::Deny,
        reasons: vec![reason],
        constraints: vec![],
        audit_required: true,
    }
}

impl TigaService {
    /// Human management entry point. The trusted caller must explicitly obtain consent for
    /// this object, audience and duration; never call automatically on an AI request.
    /// Admission requires an allowed fresh RevealSecret, even under profiles where ordinary
    /// reveal permits stale authentication. Duration is clipped to the original session's
    /// absolute policy deadline and 24 hours. Proxy use has its own idle/freshness lifetime;
    /// it neither renews nor modifies the ordinary session.
    pub fn authorize_credential_use(
        conn: &VaultConnection,
        object_id: &str,
        audience: &str,
        requested_seconds: u32,
        device: &DeviceContext,
        now: i64,
    ) -> StorageResult<CredentialUseLease> {
        if !(1..=86400).contains(&requested_seconds)
            || audience.is_empty()
            || audience.len() > 256
            || audience.chars().any(char::is_control)
        {
            return Err(StorageError::Validation(
                "invalid credential-use scope or duration".into(),
            ));
        }
        let scope = TigaScope::Entry {
            entry_id: object_id.to_owned(),
        };
        let context = TigaAuthorizationContext {
            session: conn.active_session(),
            device,
            now_unix_secs: now,
        };
        let result = conn.with_immediate_transaction(|| {
            let resolved = Self::resolve_policy_for_entry(conn, object_id)?;
            let mut evaluated = Self::evaluate_operation_with_evidence(
                conn,
                &scope,
                TigaOperation::RevealSecret,
                context,
            )?;
            if conn.keyring().is_none() || conn.active_session().is_none() {
                evaluated.decision = denied(AuthorizationReason::SessionMissing);
            } else if !conn
                .active_session()
                .unwrap()
                .assurance
                .is_fresh(resolved.policy.session.fresh_auth_window_secs, now)
            {
                evaluated.decision = denied(AuthorizationReason::AuthenticationStale);
            }
            // Always audit consent admission using the old, readable physical disclosure
            // operation. No new serialized enum values are introduced for older clients.
            record_authorization_event(
                conn,
                &scope,
                TigaOperation::RevealSecret,
                context,
                &evaluated.decision,
                evaluated.evidence.audit_context(None, None),
            )?;
            if !decision_allows(&evaluated.decision) {
                return Ok(Err(StorageError::Authorization(evaluated.decision)));
            }
            let session = conn.active_session().unwrap();
            let absolute = session
                .assurance
                .authenticated_at_unix_secs
                .checked_add(i64::from(resolved.policy.session.max_lifetime_secs))
                .ok_or_else(|| StorageError::Validation("invalid session deadline".into()))?;
            let expires_at = now
                .checked_add(i64::from(requested_seconds))
                .ok_or_else(|| StorageError::Validation("invalid credential-use deadline".into()))?
                .min(absolute);
            if expires_at <= now {
                return Ok(Err(StorageError::Authorization(denied(
                    AuthorizationReason::SessionExpired,
                ))));
            }
            Ok(Ok(CredentialUseLease {
                object_id: object_id.to_owned(),
                audience: audience.to_owned(),
                epoch: conn.credential_use_epoch,
                policy: resolved,
                entry: EntryIdentity::read(conn, object_id)?,
                device: device.clone(),
                constraints: evaluated.decision.constraints,
                expires_at,
                deadline: Instant::now() + Duration::from_secs((expires_at - now) as u64),
                last_checked: AtomicI64::new(now),
                revoked: AtomicBool::new(false),
            }))
        })?;
        result
    }

    /// Recheck a running request without decrypting payloads or touching session timestamps.
    /// Failures permanently close this lease, including clock rollback and policy changes.
    pub fn evaluate_credential_use(
        conn: &VaultConnection,
        lease: &CredentialUseLease,
        audience: &str,
        device: &DeviceContext,
        now: i64,
    ) -> StorageResult<AuthorizationDecision> {
        let result = (|| {
            if lease.revoked.load(Ordering::Acquire)
                || conn.keyring().is_none()
                || conn.active_session().is_none()
                || conn.credential_use_epoch != lease.epoch
            {
                return Ok(denied(AuthorizationReason::SessionMissing));
            }
            if now < lease.last_checked.fetch_max(now, Ordering::AcqRel)
                || now >= lease.expires_at
                || Instant::now() >= lease.deadline
            {
                return Ok(denied(AuthorizationReason::SessionExpired));
            }
            if audience != lease.audience
                || device != &lease.device
                || EntryIdentity::read(conn, &lease.object_id)? != lease.entry
                || Self::resolve_policy_for_entry(conn, &lease.object_id)? != lease.policy
            {
                return Ok(denied(AuthorizationReason::PolicyExceptionInvalid));
            }
            let mut constraints = lease.constraints.clone();
            if !constraints.contains(&AuthorizationConstraint::NoPlaintextPersistence) {
                constraints.push(AuthorizationConstraint::NoPlaintextPersistence);
            }
            Ok(AuthorizationDecision {
                outcome: AuthorizationOutcome::AllowWithConstraints,
                reasons: vec![],
                constraints,
                audit_required: true,
            })
        })();
        if !matches!(&result, Ok(decision) if decision_allows(decision)) {
            lease.revoke();
        }
        result
    }

    pub(crate) fn execute_credential_use<T>(
        conn: &VaultConnection,
        lease: &CredentialUseLease,
        audience: &str,
        device: &DeviceContext,
        now: i64,
        action: impl FnOnce() -> StorageResult<T>,
    ) -> StorageResult<(T, AuthorizationDecision)> {
        // Authorize and read in the same database transaction, including cross-connection writes.
        let result = conn.with_immediate_transaction(|| {
            let decision = Self::evaluate_credential_use(conn, lease, audience, device, now)?;
            let evidence = AuthorizationEvidence {
                policy_version: lease.policy.policy.policy_version,
                policy_fingerprint: Sha256::digest(
                    serde_json::to_vec(&lease.policy.policy).map_err(|_| {
                        StorageError::Validation("invalid credential-use policy".into())
                    })?,
                )
                .to_vec(),
            };
            record_authorization_event(
                conn,
                &TigaScope::Entry {
                    entry_id: lease.object_id.clone(),
                },
                TigaOperation::RevealSecret,
                TigaAuthorizationContext {
                    session: conn.active_session(),
                    device,
                    now_unix_secs: now,
                },
                &decision,
                evidence.audit_context(None, None),
            )?;
            if !decision_allows(&decision) {
                return Ok(Err(StorageError::Authorization(decision)));
            }
            Ok(Ok((action()?, decision)))
        })?;
        result
    }
}

#[cfg(test)]
mod tests;
