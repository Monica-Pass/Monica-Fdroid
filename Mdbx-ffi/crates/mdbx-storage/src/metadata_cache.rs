//! Bounded, connection-local reuse of authenticated presentation titles.
//!
//! This is not a payload or authorization cache. Callers still query current
//! rows and must present byte-identical ciphertext under the same keyring.
//! Changes to keys, authentication, lock state, or the monotonic deadline wipe
//! the cache. No contents are serialized, logged, or included in Debug output.

use std::collections::{HashMap, VecDeque};
use std::time::Instant;
use zeroize::Zeroizing;

pub(crate) const GLITTER_TITLE_CACHE_BYTES: usize = 64 * 1024 * 1024;
const MAX_ENTRIES: usize = 32_768;
const MAX_TITLE_BYTES: usize = 64 * 1024;
const MAX_CIPHERTEXT_BYTES: usize = MAX_TITLE_BYTES + 128 * 1024;
const MAX_ID_BYTES: usize = 256;

/// Counters contain no object identities, titles, or secrets. Connection-level
/// diagnostics aggregate title and summary-page caches (including both budgets).
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct MetadataCacheStats {
    pub hits: u64,
    pub misses: u64,
    pub entries: usize,
    /// Retained field/identity bytes plus a conservative per-entry allowance.
    /// Entry count separately bounds collection/allocation overhead.
    pub retained_bytes: usize,
    pub byte_limit: usize,
}

type CacheKey = (u8, String);

struct CachedTitle {
    ciphertext: Vec<u8>,
    plaintext: Zeroizing<Vec<u8>>,
    weight: usize,
}

pub(crate) struct MetadataCache {
    entries: HashMap<CacheKey, CachedTitle>,
    order: VecDeque<CacheKey>,
    deadline: Option<Instant>,
    stats: MetadataCacheStats,
}

impl Default for MetadataCache {
    fn default() -> Self {
        Self {
            entries: HashMap::new(),
            order: VecDeque::new(),
            deadline: None,
            stats: MetadataCacheStats::default(),
        }
    }
}

impl MetadataCache {
    pub(crate) fn configure(&mut self, deadline: Option<Instant>) {
        self.clear();
        self.deadline = deadline;
        self.stats.byte_limit = if deadline.is_some() {
            GLITTER_TITLE_CACHE_BYTES
        } else {
            0
        };
    }

    pub(crate) fn clear(&mut self) {
        // Dropping Zeroizing buffers wipes retained title plaintext.
        self.entries.clear();
        self.order.clear();
        self.stats.entries = 0;
        self.stats.retained_bytes = 0;
    }

    fn active(&mut self) -> bool {
        if self
            .deadline
            .is_some_and(|deadline| Instant::now() < deadline)
        {
            true
        } else {
            self.configure(None);
            false
        }
    }

    fn key(object_type: &str, object_id: &str, field_name: &str) -> Option<CacheKey> {
        if field_name != "title" || object_id.len() > MAX_ID_BYTES {
            return None;
        }
        let kind = match object_type {
            "project" => 0,
            "entry" => 1,
            _ => return None,
        };
        Some((kind, object_id.to_owned()))
    }

    pub(crate) fn get(
        &mut self,
        object_type: &str,
        object_id: &str,
        field_name: &str,
        ciphertext: &[u8],
    ) -> Option<Vec<u8>> {
        if !self.active() {
            return None;
        }
        let key = Self::key(object_type, object_id, field_name)?;
        if let Some(entry) = self.entries.get(&key) {
            // Compare the full authenticated envelope, including nonce and epoch.
            // A version, clock, hash, or object ID alone is not authentication.
            if entry.ciphertext == ciphertext {
                self.stats.hits = self.stats.hits.saturating_add(1);
                return Some(entry.plaintext.to_vec());
            }
        }
        self.stats.misses = self.stats.misses.saturating_add(1);
        None
    }

    pub(crate) fn insert(
        &mut self,
        object_type: &str,
        object_id: &str,
        field_name: &str,
        ciphertext: &[u8],
        plaintext: &[u8],
    ) {
        if !self.active()
            || plaintext.len() > MAX_TITLE_BYTES
            || ciphertext.len() > MAX_CIPHERTEXT_BYTES
        {
            return;
        }
        let Some(key) = Self::key(object_type, object_id, field_name) else {
            return;
        };
        let weight = ciphertext.len() + plaintext.len() + 2 * object_id.len() + 256;
        if weight > self.stats.byte_limit {
            return;
        }
        if let Some(previous) = self.entries.remove(&key) {
            self.stats.retained_bytes -= previous.weight;
            // At most one FIFO entry per object. Rewrites cannot grow the queue.
            self.order.retain(|queued| queued != &key);
        }
        while self.entries.len() >= MAX_ENTRIES
            || self.stats.retained_bytes + weight > self.stats.byte_limit
        {
            let Some(oldest) = self.order.pop_front() else {
                break;
            };
            if let Some(evicted) = self.entries.remove(&oldest) {
                self.stats.retained_bytes -= evicted.weight;
            }
        }
        self.order.push_back(key.clone());
        self.entries.insert(
            key,
            CachedTitle {
                ciphertext: ciphertext.to_vec(),
                plaintext: Zeroizing::new(plaintext.to_vec()),
                weight,
            },
        );
        self.stats.entries = self.entries.len();
        self.stats.retained_bytes += weight;
    }

    pub(crate) fn stats(&mut self) -> MetadataCacheStats {
        self.active();
        self.stats
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    fn cache() -> MetadataCache {
        let mut cache = MetadataCache::default();
        cache.configure(Some(Instant::now() + Duration::from_secs(60)));
        cache
    }

    #[test]
    fn only_exact_title_envelopes_hit_and_payloads_are_never_retained() {
        let mut cache = cache();
        cache.insert("entry", "a", "title", b"authenticated", b"title");
        assert_eq!(
            cache.get("entry", "a", "title", b"authenticated").unwrap(),
            b"title"
        );
        assert!(cache.get("entry", "a", "title", b"tampered").is_none());
        assert!(cache.get("entry", "b", "title", b"authenticated").is_none());
        assert!(cache
            .get("project", "a", "title", b"authenticated")
            .is_none());
        cache.insert("entry", "a", "payload", b"secret envelope", b"secret");
        cache.insert(
            "project",
            "a",
            "summary",
            b"summary envelope",
            b"private summary",
        );
        assert!(cache
            .get("entry", "a", "payload", b"secret envelope")
            .is_none());
        assert_eq!(cache.stats().entries, 1);
    }

    #[test]
    fn byte_budget_eviction_and_rewrites_remain_bounded() {
        let mut cache = cache();
        cache.stats.byte_limit = 1200;
        for id in 0..100 {
            cache.insert("entry", &id.to_string(), "title", &[1; 100], &[2; 100]);
            assert!(cache.stats().retained_bytes <= 1200);
        }
        assert!(cache.get("entry", "0", "title", &[1; 100]).is_none());
        assert!(cache.get("entry", "99", "title", &[1; 100]).is_some());
        for _ in 0..100 {
            cache.insert("entry", "99", "title", &[3; 100], &[4; 100]);
        }
        assert_eq!(cache.order.len(), cache.entries.len());
        assert!(cache.stats().retained_bytes <= 1200);
    }

    #[test]
    fn expiry_disable_and_key_replacement_drop_all_titles() {
        let mut cache = cache();
        cache.insert("entry", "a", "title", b"old", b"old title");
        cache.configure(Some(Instant::now() + Duration::from_secs(60)));
        assert!(cache.get("entry", "a", "title", b"old").is_none());
        cache.insert("entry", "a", "title", b"new", b"new title");
        cache.deadline = Some(Instant::now());
        assert!(cache.get("entry", "a", "title", b"new").is_none());
        assert_eq!(cache.stats().entries, 0);
        assert_eq!(cache.stats().retained_bytes, 0);
        assert_eq!(cache.stats().byte_limit, 0);
    }

    #[test]
    fn oversized_titles_and_identities_are_not_cached() {
        let mut cache = cache();
        cache.insert(
            "entry",
            "a",
            "title",
            b"ciphertext",
            &vec![0; MAX_TITLE_BYTES + 1],
        );
        cache.insert(
            "entry",
            &"a".repeat(MAX_ID_BYTES + 1),
            "title",
            b"ct",
            b"title",
        );
        assert_eq!(cache.stats().entries, 0);
    }
}
