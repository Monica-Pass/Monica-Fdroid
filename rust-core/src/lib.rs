#![forbid(unsafe_code)]

use std::cmp::Ordering;
use std::collections::HashMap;

/// Describes why two rows may represent the same logical credential.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum IdentityKind {
    /// A stable identifier for one external object, such as a Bitwarden cipher
    /// ID or KeePass entry UUID.
    ExternalObject,
    /// A Monica replica group copied to multiple storage targets.
    ReplicaGroup,
}

/// Explicit identity used for conservative display deduplication.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CollapseIdentity {
    pub kind: IdentityKind,
    pub value: String,
}

/// Secret-free metadata required to build Monica's password list.
///
/// This type intentionally has no password field. `secret_fingerprint`, when
/// present, must be a non-reversible keyed digest produced by the Android data
/// layer at write/import time. Never pass plaintext or ciphertext as a
/// fingerprint.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PasswordListRecord {
    pub id: i64,
    pub title: String,
    pub username: String,
    pub website: String,
    pub app_name: String,
    pub app_package_name: String,
    pub notes_preview: String,
    pub collapse_identity: Option<CollapseIdentity>,
    pub storage_target_key: Option<String>,
    pub secret_fingerprint: Option<String>,
    pub is_favorite: bool,
    pub updated_at_millis: i64,
}

/// Stable, secret-free row consumed by the Compose password list.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PasswordListProjection {
    pub id: i64,
    pub title: String,
    pub username: String,
    pub website: String,
    pub app_name: String,
    pub app_package_name: String,
    pub notes_preview: String,
    pub is_favorite: bool,
    pub updated_at_millis: i64,
}

impl From<&PasswordListRecord> for PasswordListProjection {
    fn from(record: &PasswordListRecord) -> Self {
        Self {
            id: record.id,
            title: record.title.clone(),
            username: record.username.clone(),
            website: record.website.clone(),
            app_name: record.app_name.clone(),
            app_package_name: record.app_package_name.clone(),
            notes_preview: record.notes_preview.clone(),
            is_favorite: record.is_favorite,
            updated_at_millis: record.updated_at_millis,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ProjectionOptions {
    pub collapse_known_replicas: bool,
}

impl Default for ProjectionOptions {
    fn default() -> Self {
        Self {
            collapse_known_replicas: true,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct ProjectionStats {
    pub input_count: usize,
    pub matched_count: usize,
    pub filtered_out_count: usize,
    pub collapsed_count: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProjectionResult {
    pub items: Vec<PasswordListProjection>,
    pub stats: ProjectionStats,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
struct NormalizedIdentity {
    kind: IdentityKind,
    value: String,
}

#[derive(Clone, Copy)]
struct Candidate<'a> {
    original_index: usize,
    record: &'a PasswordListRecord,
}

/// Filters and projects one complete password-list snapshot in a single batch.
///
/// Deduplication is deliberately conservative:
///
/// - identity-less local rows are always preserved;
/// - rows with unknown secret fingerprints are preserved rather than guessed;
/// - multiple rows in the same replica group and storage target are treated as
///   intentional sibling credentials and are never collapsed;
/// - only rows with the same explicit identity and the same known fingerprint
///   are eligible for collapsing.
///
/// The result retains the original relative order of the selected rows.
#[must_use]
pub fn project_password_list(
    records: &[PasswordListRecord],
    query: &str,
    options: ProjectionOptions,
) -> ProjectionResult {
    let normalized_query = query.trim().to_lowercase();
    let matched: Vec<Candidate<'_>> = records
        .iter()
        .enumerate()
        .filter(|(_, record)| record_matches_query(record, &normalized_query))
        .map(|(original_index, record)| Candidate {
            original_index,
            record,
        })
        .collect();

    let matched_count = matched.len();
    let mut selected = if options.collapse_known_replicas {
        collapse_candidates(matched)
    } else {
        matched
    };

    selected.sort_by_key(|candidate| candidate.original_index);
    let items = selected
        .into_iter()
        .map(|candidate| PasswordListProjection::from(candidate.record))
        .collect::<Vec<_>>();

    ProjectionResult {
        stats: ProjectionStats {
            input_count: records.len(),
            matched_count,
            filtered_out_count: records.len().saturating_sub(matched_count),
            collapsed_count: matched_count.saturating_sub(items.len()),
        },
        items,
    }
}

fn collapse_candidates<'a>(candidates: Vec<Candidate<'a>>) -> Vec<Candidate<'a>> {
    let mut independent = Vec::new();
    let mut groups: HashMap<NormalizedIdentity, Vec<Candidate<'a>>> = HashMap::new();

    for candidate in candidates {
        let Some(identity) = normalized_identity(candidate.record) else {
            independent.push(candidate);
            continue;
        };
        groups.entry(identity).or_default().push(candidate);
    }

    let mut selected = independent;
    for (identity, group) in groups {
        match identity.kind {
            IdentityKind::ExternalObject => {
                selected.extend(collapse_by_known_fingerprint(group));
            }
            IdentityKind::ReplicaGroup => {
                selected.extend(collapse_replica_group(group));
            }
        }
    }
    selected
}

fn collapse_replica_group<'a>(group: Vec<Candidate<'a>>) -> Vec<Candidate<'a>> {
    let mut by_target: HashMap<String, Vec<Candidate<'a>>> = HashMap::new();
    let mut target_unknown = Vec::new();

    for candidate in group {
        let target = candidate
            .record
            .storage_target_key
            .as_deref()
            .map(normalize_key)
            .filter(|value| !value.is_empty());
        if let Some(target) = target {
            by_target.entry(target).or_default().push(candidate);
        } else {
            target_unknown.push(candidate);
        }
    }

    let mut selected = target_unknown;
    let mut singleton_targets = Vec::new();
    for target_rows in by_target.into_values() {
        if target_rows.len() > 1 {
            // Multiple rows in one target may be intentional sibling passwords.
            selected.extend(target_rows);
        } else {
            singleton_targets.extend(target_rows);
        }
    }
    selected.extend(collapse_by_known_fingerprint(singleton_targets));
    selected
}

fn collapse_by_known_fingerprint<'a>(group: Vec<Candidate<'a>>) -> Vec<Candidate<'a>> {
    let mut selected = Vec::new();
    let mut best_by_fingerprint: HashMap<String, Candidate<'a>> = HashMap::new();

    for candidate in group {
        let fingerprint = candidate
            .record
            .secret_fingerprint
            .as_deref()
            .map(normalize_fingerprint)
            .filter(|value| !value.is_empty());

        let Some(fingerprint) = fingerprint else {
            // Unknown is not proof of equality. Preserve the row.
            selected.push(candidate);
            continue;
        };

        match best_by_fingerprint.get_mut(&fingerprint) {
            Some(best) => {
                if record_quality_cmp(candidate.record, best.record) == Ordering::Greater {
                    *best = candidate;
                }
            }
            None => {
                best_by_fingerprint.insert(fingerprint, candidate);
            }
        }
    }

    selected.extend(best_by_fingerprint.into_values());
    selected
}

fn normalized_identity(record: &PasswordListRecord) -> Option<NormalizedIdentity> {
    let identity = record.collapse_identity.as_ref()?;
    let value = normalize_key(&identity.value);
    if value.is_empty() {
        return None;
    }
    Some(NormalizedIdentity {
        kind: identity.kind,
        value,
    })
}

fn record_matches_query(record: &PasswordListRecord, normalized_query: &str) -> bool {
    if normalized_query.is_empty() {
        return true;
    }

    [
        record.title.as_str(),
        record.username.as_str(),
        record.website.as_str(),
        record.app_name.as_str(),
        record.app_package_name.as_str(),
        record.notes_preview.as_str(),
    ]
    .into_iter()
    .any(|field| contains_case_insensitive(field, normalized_query))
}

fn contains_case_insensitive(value: &str, normalized_query: &str) -> bool {
    if normalized_query.is_empty() {
        return true;
    }

    if value.is_ascii() && normalized_query.is_ascii() {
        let query = normalized_query.as_bytes();
        return value
            .as_bytes()
            .windows(query.len())
            .any(|window| window.eq_ignore_ascii_case(query));
    }

    value.to_lowercase().contains(normalized_query)
}

fn normalize_key(value: &str) -> String {
    value.trim().to_lowercase()
}

fn normalize_fingerprint(value: &str) -> String {
    value.trim().to_owned()
}

fn record_quality_cmp(left: &PasswordListRecord, right: &PasswordListRecord) -> Ordering {
    left.is_favorite
        .cmp(&right.is_favorite)
        .then_with(|| non_empty_field_count(left).cmp(&non_empty_field_count(right)))
        .then_with(|| text_richness(left).cmp(&text_richness(right)))
        .then_with(|| left.updated_at_millis.cmp(&right.updated_at_millis))
        .then_with(|| left.id.cmp(&right.id))
}

fn non_empty_field_count(record: &PasswordListRecord) -> usize {
    [
        record.title.as_str(),
        record.username.as_str(),
        record.website.as_str(),
        record.app_name.as_str(),
        record.app_package_name.as_str(),
        record.notes_preview.as_str(),
    ]
    .into_iter()
    .filter(|value| !value.trim().is_empty())
    .count()
}

fn text_richness(record: &PasswordListRecord) -> usize {
    record.title.len()
        + record.username.len()
        + record.website.len()
        + record.app_name.len()
        + record.app_package_name.len()
        + record.notes_preview.len()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn record(id: i64, title: &str) -> PasswordListRecord {
        PasswordListRecord {
            id,
            title: title.to_owned(),
            username: String::new(),
            website: String::new(),
            app_name: String::new(),
            app_package_name: String::new(),
            notes_preview: String::new(),
            collapse_identity: None,
            storage_target_key: None,
            secret_fingerprint: None,
            is_favorite: false,
            updated_at_millis: 0,
        }
    }

    fn identity(kind: IdentityKind, value: &str) -> CollapseIdentity {
        CollapseIdentity {
            kind,
            value: value.to_owned(),
        }
    }

    fn ids(result: &ProjectionResult) -> Vec<i64> {
        result.items.iter().map(|item| item.id).collect()
    }

    #[test]
    fn blank_query_preserves_order() {
        let records = vec![record(10, "first"), record(20, "second")];

        let result = project_password_list(&records, "  ", ProjectionOptions::default());

        assert_eq!(ids(&result), vec![10, 20]);
        assert_eq!(result.stats.input_count, 2);
        assert_eq!(result.stats.matched_count, 2);
        assert_eq!(result.stats.filtered_out_count, 0);
        assert_eq!(result.stats.collapsed_count, 0);
    }

    #[test]
    fn search_is_case_insensitive_across_display_metadata() {
        let mut github = record(1, "GitHub");
        github.app_package_name = "com.github.android".to_owned();
        let mut bank = record(2, "Bank");
        bank.website = "example.cn".to_owned();
        let records = vec![github, bank];

        let result = project_password_list(&records, "GITHUB", ProjectionOptions::default());

        assert_eq!(ids(&result), vec![1]);
        assert_eq!(result.stats.filtered_out_count, 1);
    }

    #[test]
    fn unicode_search_uses_lowercase_fallback() {
        let records = vec![record(1, "MÜNCHEN"), record(2, "Berlin")];

        let result = project_password_list(&records, "münchen", ProjectionOptions::default());

        assert_eq!(ids(&result), vec![1]);
    }

    #[test]
    fn local_duplicates_are_never_collapsed() {
        let records = vec![record(1, "same"), record(2, "same")];

        let result = project_password_list(&records, "", ProjectionOptions::default());

        assert_eq!(ids(&result), vec![1, 2]);
        assert_eq!(result.stats.collapsed_count, 0);
    }

    #[test]
    fn blank_identity_is_treated_as_independent() {
        let mut first = record(1, "same");
        first.collapse_identity = Some(identity(IdentityKind::ExternalObject, "   "));
        let mut second = record(2, "same");
        second.collapse_identity = Some(identity(IdentityKind::ExternalObject, "   "));

        let result = project_password_list(&[first, second], "", ProjectionOptions::default());

        assert_eq!(ids(&result), vec![1, 2]);
    }

    #[test]
    fn external_rows_with_same_known_fingerprint_collapse() {
        let mut older = record(1, "GitHub");
        older.collapse_identity = Some(identity(IdentityKind::ExternalObject, "bw:vault:cipher"));
        older.secret_fingerprint = Some("hmac:abc".to_owned());
        older.updated_at_millis = 100;

        let mut richer = record(2, "GitHub");
        richer.collapse_identity =
            Some(identity(IdentityKind::ExternalObject, " BW:VAULT:CIPHER "));
        richer.secret_fingerprint = Some(" hmac:abc ".to_owned());
        richer.username = "octocat".to_owned();
        richer.is_favorite = true;
        richer.updated_at_millis = 200;

        let result = project_password_list(&[older, richer], "", ProjectionOptions::default());

        assert_eq!(ids(&result), vec![2]);
        assert_eq!(result.stats.collapsed_count, 1);
    }

    #[test]
    fn external_rows_with_different_fingerprints_remain_distinct() {
        let mut first = record(1, "GitHub");
        first.collapse_identity = Some(identity(IdentityKind::ExternalObject, "bw:vault:cipher"));
        first.secret_fingerprint = Some("hmac:first".to_owned());
        let mut second = record(2, "GitHub");
        second.collapse_identity = Some(identity(IdentityKind::ExternalObject, "bw:vault:cipher"));
        second.secret_fingerprint = Some("hmac:second".to_owned());

        let result = project_password_list(&[first, second], "", ProjectionOptions::default());

        assert_eq!(ids(&result), vec![1, 2]);
    }

    #[test]
    fn unknown_fingerprints_are_not_guessed_equal() {
        let mut first = record(1, "GitHub");
        first.collapse_identity = Some(identity(IdentityKind::ExternalObject, "bw:vault:cipher"));
        let mut second = record(2, "GitHub");
        second.collapse_identity = Some(identity(IdentityKind::ExternalObject, "bw:vault:cipher"));

        let result = project_password_list(&[first, second], "", ProjectionOptions::default());

        assert_eq!(ids(&result), vec![1, 2]);
        assert_eq!(result.stats.collapsed_count, 0);
    }

    #[test]
    fn replica_siblings_in_the_same_target_are_preserved() {
        let mut first = record(1, "GitHub");
        first.collapse_identity = Some(identity(IdentityKind::ReplicaGroup, "replica-1"));
        first.storage_target_key = Some("local".to_owned());
        first.secret_fingerprint = Some("hmac:first".to_owned());
        let mut second = record(2, "GitHub");
        second.collapse_identity = Some(identity(IdentityKind::ReplicaGroup, "replica-1"));
        second.storage_target_key = Some("local".to_owned());
        second.secret_fingerprint = Some("hmac:first".to_owned());

        let result = project_password_list(&[first, second], "", ProjectionOptions::default());

        assert_eq!(ids(&result), vec![1, 2]);
    }

    #[test]
    fn singleton_target_replicas_with_same_fingerprint_collapse() {
        let mut local = record(1, "GitHub");
        local.collapse_identity = Some(identity(IdentityKind::ReplicaGroup, "replica-1"));
        local.storage_target_key = Some("local".to_owned());
        local.secret_fingerprint = Some("hmac:same".to_owned());

        let mut remote = record(2, "GitHub");
        remote.collapse_identity = Some(identity(IdentityKind::ReplicaGroup, "replica-1"));
        remote.storage_target_key = Some("bitwarden:vault".to_owned());
        remote.secret_fingerprint = Some("hmac:same".to_owned());
        remote.is_favorite = true;

        let result = project_password_list(&[local, remote], "", ProjectionOptions::default());

        assert_eq!(ids(&result), vec![2]);
        assert_eq!(result.stats.collapsed_count, 1);
    }

    #[test]
    fn singleton_target_replicas_with_different_fingerprints_remain_distinct() {
        let mut local = record(1, "GitHub");
        local.collapse_identity = Some(identity(IdentityKind::ReplicaGroup, "replica-1"));
        local.storage_target_key = Some("local".to_owned());
        local.secret_fingerprint = Some("hmac:first".to_owned());

        let mut remote = record(2, "GitHub");
        remote.collapse_identity = Some(identity(IdentityKind::ReplicaGroup, "replica-1"));
        remote.storage_target_key = Some("bitwarden:vault".to_owned());
        remote.secret_fingerprint = Some("hmac:second".to_owned());

        let result = project_password_list(&[local, remote], "", ProjectionOptions::default());

        assert_eq!(ids(&result), vec![1, 2]);
    }

    #[test]
    fn collapsing_can_be_disabled() {
        let mut first = record(1, "GitHub");
        first.collapse_identity = Some(identity(IdentityKind::ExternalObject, "bw:vault:cipher"));
        first.secret_fingerprint = Some("hmac:same".to_owned());
        let mut second = record(2, "GitHub");
        second.collapse_identity = Some(identity(IdentityKind::ExternalObject, "bw:vault:cipher"));
        second.secret_fingerprint = Some("hmac:same".to_owned());

        let result = project_password_list(
            &[first, second],
            "",
            ProjectionOptions {
                collapse_known_replicas: false,
            },
        );

        assert_eq!(ids(&result), vec![1, 2]);
        assert_eq!(result.stats.collapsed_count, 0);
    }
}
