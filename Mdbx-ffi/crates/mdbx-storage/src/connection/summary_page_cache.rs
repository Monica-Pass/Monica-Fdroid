//! Glitter-only, bounded presentation pages. Never payloads or authorization.
//! SQL version stamps invalidate pages after local/foreign writes, including
//! rolled-back local changes. Transactions always bypass reuse to preserve their
//! own snapshot. The runtime's policy checks still run before every FFI call.
use super::*;
use crate::metadata_cache::MetadataCacheStats;
use mdbx_core::model::ObjectSummaryPage;
use std::collections::VecDeque;
use zeroize::Zeroize;

const BYTE_LIMIT: usize = 32 * 1024 * 1024;
const MAX_PAGES: usize = 256;
const MAX_QUERY_BYTES: usize = 16 * 1024;

#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) struct SummaryPageStamp(i64, i64, uuid::Uuid);

struct CachedPage {
    page: ObjectSummaryPage,
    weight: usize,
}

impl Drop for CachedPage {
    fn drop(&mut self) {
        // Clear retained plaintext before the allocation is released. No page
        // is serialized, logged, or exposed via Debug. Returned copies belong
        // to the caller, with the same lifetime as ordinary uncached reads.
        for item in &mut self.page.items {
            if let Some(title) = item.title.as_mut() {
                title.zeroize();
            }
        }
    }
}

#[derive(Default)]
pub(super) struct SummaryPageCache {
    entries: HashMap<String, CachedPage>,
    order: VecDeque<String>,
    stamp: Option<SummaryPageStamp>,
    deadline: Option<Instant>,
    stats: MetadataCacheStats,
}

impl SummaryPageCache {
    pub(super) fn configure(&mut self, deadline: Option<Instant>) {
        self.clear();
        self.deadline = deadline;
        self.stats.byte_limit = if deadline.is_some() { BYTE_LIMIT } else { 0 };
    }

    fn clear(&mut self) {
        self.entries.clear();
        self.order.clear();
        self.stamp = None;
        self.stats.entries = 0;
        self.stats.retained_bytes = 0;
    }

    fn active(&mut self) -> bool {
        if self.deadline.is_some_and(|end| Instant::now() < end) {
            true
        } else {
            self.configure(None);
            false
        }
    }

    fn observe(&mut self, stamp: SummaryPageStamp) {
        if self.stamp != Some(stamp) {
            self.clear();
            self.stamp = Some(stamp);
        }
    }

    fn get(&mut self, key: &str, stamp: SummaryPageStamp) -> Option<ObjectSummaryPage> {
        if !self.active() || key.len() > MAX_QUERY_BYTES {
            return None;
        }
        self.observe(stamp);
        if let Some(cached) = self.entries.get(key) {
            self.stats.hits = self.stats.hits.saturating_add(1);
            Some(cached.page.clone())
        } else {
            self.stats.misses = self.stats.misses.saturating_add(1);
            None
        }
    }

    fn insert(&mut self, key: String, stamp: SummaryPageStamp, page: &ObjectSummaryPage) {
        if !self.active() || self.stamp != Some(stamp) || key.len() > MAX_QUERY_BYTES {
            return;
        }
        // Includes every allocation in the clone plus a generous per-item/map
        // overhead. Count is bounded separately; oversized pages remain usable
        // through the ordinary SQL path but are never retained.
        let weight = page.items.iter().fold(
            512usize
                .saturating_add(key.len() * 2)
                .saturating_add(page.next_cursor.as_ref().map_or(0, String::len)),
            |sum, item| {
                sum.saturating_add(512)
                    .saturating_add(item.object_id.len())
                    .saturating_add(item.collection_id.len())
                    .saturating_add(item.object_type_id.as_str().len())
                    .saturating_add(item.head_commit_id.len())
                    .saturating_add(item.updated_at.len())
                    .saturating_add(item.title.as_ref().map_or(0, Vec::len))
            },
        );
        if weight > self.stats.byte_limit {
            return;
        }
        if let Some(old) = self.entries.remove(&key) {
            self.stats.retained_bytes -= old.weight;
            self.order.retain(|queued| queued != &key);
        }
        while self.entries.len() >= MAX_PAGES
            || self.stats.retained_bytes + weight > self.stats.byte_limit
        {
            let Some(oldest) = self.order.pop_front() else {
                break;
            };
            if let Some(old) = self.entries.remove(&oldest) {
                self.stats.retained_bytes -= old.weight;
            }
        }
        self.order.push_back(key.clone());
        self.entries.insert(
            key,
            CachedPage {
                page: page.clone(),
                weight,
            },
        );
        self.stats.retained_bytes += weight;
        self.stats.entries = self.entries.len();
    }

    pub(super) fn stats(&mut self) -> MetadataCacheStats {
        self.active();
        self.stats
    }
}

impl VaultConnection {
    /// Both counters belong to this connection. `total_changes()` includes
    /// rolled-back changes; `data_version` observes other connections' commits.
    pub(crate) fn summary_page_stamp(&self) -> StorageResult<Option<SummaryPageStamp>> {
        let allowed = self.glitter_runtime
            && self.keyring.is_some()
            && self
                .glitter_auth_deadline
                .is_some_and(|end| Instant::now() < end)
            && self.active_key_epoch_id.is_some()
            && self.active_session.as_ref().is_some_and(|session| {
                !session.assurance.is_expired(
                    &mdbx_core::tiga::TigaMode::Glitter.policy().session,
                    chrono::Utc::now().timestamp(),
                ) && session.assurance.has_security_key()
                    && session.assurance.factor_count() >= 2
            });
        if !allowed || !self.conn.is_autocommit() {
            self.summary_pages
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .clear();
            return Ok(None);
        }
        let result = self.conn.query_row(
            "SELECT data_version, total_changes() FROM pragma_data_version",
            [],
            |row| {
                Ok(SummaryPageStamp(
                    row.get(0)?,
                    row.get(1)?,
                    self.credential_use_epoch,
                ))
            },
        );
        match result {
            Ok(stamp) => Ok(Some(stamp)),
            Err(error) => {
                self.summary_pages
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                    .clear();
                Err(error.into())
            }
        }
    }

    pub(crate) fn cached_summary_page(
        &self,
        key: &str,
        stamp: SummaryPageStamp,
    ) -> Option<ObjectSummaryPage> {
        self.summary_pages
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .get(key, stamp)
    }

    pub(crate) fn cache_summary_page(
        &self,
        key: String,
        before: SummaryPageStamp,
        page: &ObjectSummaryPage,
    ) -> StorageResult<()> {
        let after = self.summary_page_stamp()?;
        let mut cache = self
            .summary_pages
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if after == Some(before) {
            cache.insert(key, before, page);
        } else {
            cache.clear();
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use mdbx_core::model::{ObjectSummary, ObjectTypeId};

    fn page() -> ObjectSummaryPage {
        ObjectSummaryPage {
            items: vec![ObjectSummary {
                object_id: "id".into(),
                collection_id: "collection".into(),
                object_type_id: ObjectTypeId::Login,
                title: Some(b"private title".to_vec()),
                payload_schema_version: 1,
                head_commit_id: "commit".into(),
                deleted: false,
                updated_at: "2026-10-06".into(),
            }],
            next_cursor: None,
        }
    }

    fn cache() -> SummaryPageCache {
        let mut cache = SummaryPageCache::default();
        cache.configure(Some(Instant::now() + Duration::from_secs(60)));
        cache
    }

    #[test]
    fn glitter_pages_bound_count_bytes_rewrites_and_oversized_pages() {
        let mut cache = cache();
        let stamp = SummaryPageStamp(1, 1, uuid::Uuid::nil());
        let page = page();
        cache.get("first", stamp);
        for index in 0..1000 {
            cache.insert(index.to_string(), stamp, &page);
            assert!(cache.stats().entries <= MAX_PAGES);
            assert!(cache.stats().retained_bytes <= BYTE_LIMIT);
        }
        assert_eq!(cache.stats().entries, MAX_PAGES);
        assert!(cache.get("0", stamp).is_none());
        assert_eq!(cache.get("999", stamp), Some(page.clone()));
        for _ in 0..1000 {
            cache.insert("999".into(), stamp, &page);
        }
        assert_eq!(cache.order.len(), cache.entries.len());

        cache.configure(Some(Instant::now() + Duration::from_secs(60)));
        cache.stats.byte_limit = 2500;
        cache.get("small", stamp);
        for index in 0..100 {
            cache.insert(index.to_string(), stamp, &page);
            assert!(cache.stats().retained_bytes <= 2500);
        }
        assert!(cache.stats().entries < 3);
        let retained = cache.stats().entries;
        let mut oversized = page.clone();
        oversized.items[0].title = Some(vec![7; 2501]);
        cache.insert("oversized".into(), stamp, &oversized);
        cache.insert("x".repeat(MAX_QUERY_BYTES + 1), stamp, &page);
        assert_eq!(cache.stats().entries, retained);
        assert!(cache.get("oversized", stamp).is_none());
    }

    #[test]
    fn glitter_pages_expiry_and_both_sql_counters_clear_retained_pages() {
        let mut cache = cache();
        let first = SummaryPageStamp(1, 1, uuid::Uuid::nil());
        cache.get("page", first);
        cache.insert("page".into(), first, &page());
        assert!(cache
            .get("page", SummaryPageStamp(1, 2, uuid::Uuid::nil()))
            .is_none());
        assert_eq!(cache.stats().retained_bytes, 0);
        cache.insert(
            "page".into(),
            SummaryPageStamp(1, 2, uuid::Uuid::nil()),
            &page(),
        );
        assert!(cache
            .get("page", SummaryPageStamp(2, 2, uuid::Uuid::nil()))
            .is_none());
        assert_eq!(cache.stats().retained_bytes, 0);
        cache.insert(
            "page".into(),
            SummaryPageStamp(2, 2, uuid::Uuid::nil()),
            &page(),
        );
        cache.deadline = Some(Instant::now());
        assert!(cache
            .get("page", SummaryPageStamp(2, 2, uuid::Uuid::nil()))
            .is_none());
        assert_eq!(cache.stats().entries, 0);
        assert_eq!(cache.stats().byte_limit, 0);
    }
}
