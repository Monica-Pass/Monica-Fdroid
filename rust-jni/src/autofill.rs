#![forbid(unsafe_code)]

use std::collections::{BTreeSet, HashMap};
use std::sync::{Arc, Mutex, OnceLock};

pub(crate) const MAX_BYTES: usize = 32 * 1024 * 1024;
const MAGIC: u32 = 0x3146414d; // MAF1. Four length-prefixed string lists per row.
const MAX_FIELD_BYTES: usize = 4096;
const MAX_INDEX_BYTES: usize = 64 * 1024 * 1024;

#[derive(Default)]
struct Index {
    postings: [HashMap<String, Vec<i32>>; 5],
    estimated_bytes: usize,
}

impl Index {
    fn parse(bytes: &[u8]) -> Option<Self> {
        if !(8..=MAX_BYTES).contains(&bytes.len()) {
            return None;
        }
        let mut cursor = Cursor(bytes);
        if cursor.number()? != MAGIC {
            return None;
        }
        let count = cursor.number()? as usize;
        if count > 100_000 || count > cursor.0.len() / 16 {
            return None;
        }
        let mut index = Self::default();
        for row in 0..count {
            for kind in 0..4 {
                let fields = cursor.number()? as usize;
                if fields > cursor.0.len() / 4 {
                    return None;
                }
                for _ in 0..fields {
                    let len = cursor.number()? as usize;
                    if len > MAX_FIELD_BYTES {
                        return None;
                    }
                    let value = std::str::from_utf8(cursor.take(len)?).ok()?;
                    index.add(kind, value, row as i32)?;
                    if kind == 1 {
                        // Descendant lookup. Keep dot boundaries; never match evil-example.com.
                        for (offset, _) in value.match_indices('.') {
                            index.add(4, &value[offset + 1..], row as i32)?;
                        }
                    }
                }
            }
        }
        cursor.0.is_empty().then_some(index)
    }

    fn add(&mut self, kind: usize, value: &str, row: i32) -> Option<()> {
        if value.is_empty() {
            return Some(());
        }
        let postings = &mut self.postings[kind];
        let is_new = match postings.get(value) {
            Some(rows) if rows.last() == Some(&row) => return Some(()),
            Some(_) => false,
            None => true,
        };
        // Include map/vector overhead as well as text. A short encoded input can
        // otherwise expand quadratically through all the suffixes of a long host.
        let added = 8 + if is_new { value.len() + 96 } else { 0 };
        self.estimated_bytes = self.estimated_bytes.checked_add(added)?;
        if self.estimated_bytes > MAX_INDEX_BYTES {
            return None;
        }
        if is_new {
            postings.insert(value.to_owned(), vec![row]);
        } else {
            postings.get_mut(value)?.push(row);
        }
        Some(())
    }

    fn query(&self, package: &str, host: &str, root: &str, label: &str) -> Option<Vec<i32>> {
        if [package, host, root, label]
            .iter()
            .any(|value| value.len() > MAX_FIELD_BYTES)
        {
            return None;
        }
        let mut found = BTreeSet::new();
        let mut include = |kind: usize, value: &str| {
            if let Some(rows) = self.postings[kind].get(value) {
                found.extend(rows.iter().copied());
            }
        };
        if !host.is_empty() {
            include(1, host);
            include(2, root);
            include(4, host);
            for (offset, _) in host.match_indices('.') {
                include(1, &host[offset + 1..]);
            }
        } else {
            include(0, package);
            include(3, label);
        }
        // A conservative superset only. Kotlin applies all policy flags and scoring.
        Some(found.into_iter().collect())
    }
}

struct Cursor<'a>(&'a [u8]);
impl<'a> Cursor<'a> {
    fn take(&mut self, len: usize) -> Option<&'a [u8]> {
        let result = self.0.get(..len)?;
        self.0 = self.0.get(len..)?;
        Some(result)
    }
    fn number(&mut self) -> Option<u32> {
        Some(u32::from_le_bytes(self.take(4)?.try_into().ok()?))
    }
}

#[derive(Default)]
struct Registry {
    next: i64,
    indices: HashMap<i64, Arc<Index>>,
}
static REGISTRY: OnceLock<Mutex<Registry>> = OnceLock::new();
fn registry() -> &'static Mutex<Registry> {
    REGISTRY.get_or_init(|| Mutex::new(Registry::default()))
}

pub(crate) fn open(bytes: &[u8]) -> Option<i64> {
    let index = Arc::new(Index::parse(bytes)?);
    let mut state = registry().lock().ok()?;
    let handle = state.next.checked_add(1)?;
    state.next = handle;
    state.indices.insert(handle, index);
    Some(handle)
}
pub(crate) fn query(
    handle: i64,
    package: &str,
    host: &str,
    root: &str,
    label: &str,
) -> Option<Vec<i32>> {
    let index = registry().lock().ok()?.indices.get(&handle)?.clone();
    index.query(package, host, root, label)
}
pub(crate) fn close(handle: i64) {
    let removed = registry()
        .lock()
        .ok()
        .and_then(|mut state| state.indices.remove(&handle));
    drop(removed);
}

#[cfg(test)]
mod tests {
    use super::*;
    fn batch(rows: &[[Vec<&str>; 4]]) -> Vec<u8> {
        let mut bytes = MAGIC.to_le_bytes().to_vec();
        bytes.extend_from_slice(&(rows.len() as u32).to_le_bytes());
        for row in rows {
            for fields in row {
                bytes.extend_from_slice(&(fields.len() as u32).to_le_bytes());
                for field in fields {
                    bytes.extend_from_slice(&(field.len() as u32).to_le_bytes());
                    bytes.extend_from_slice(field.as_bytes());
                }
            }
        }
        bytes
    }
    #[test]
    fn conservative_candidates_preserve_order_boundaries_and_unicode() {
        let handle = open(&batch(&[
            [
                vec!["com.app"],
                vec!["a.example.com"],
                vec!["example.com"],
                vec!["应用"],
            ],
            [
                vec![],
                vec!["evil-example.com"],
                vec!["evil-example.com"],
                vec![],
            ],
            [
                vec![],
                vec!["example.com"],
                vec!["example.com"],
                vec!["应用"],
            ],
        ]))
        .unwrap();
        assert_eq!(query(handle, "com.app", "", "", "应用"), Some(vec![0, 2]));
        assert_eq!(
            query(handle, "", "example.com", "example.com", ""),
            Some(vec![0, 2])
        );
        assert_eq!(
            query(handle, "", "b.a.example.com", "example.com", ""),
            Some(vec![0, 2])
        );
        assert_eq!(
            query(handle, "com.app", "unknown.test", "unknown.test", "应用"),
            Some(vec![])
        );
        close(handle);
        close(handle);
        assert_eq!(query(handle, "", "", "", ""), None);
    }
    #[test]
    fn malformed_batches_are_rejected() {
        let valid = batch(&[[vec!["app"], vec![], vec![], vec![]]]);
        for end in 0..valid.len() {
            assert!(open(&valid[..end]).is_none());
        }
        let mut invalid = valid.clone();
        invalid.push(0);
        assert!(open(&invalid).is_none());
        let mut invalid = valid;
        invalid[4..8].copy_from_slice(&u32::MAX.to_le_bytes());
        assert!(open(&invalid).is_none());
    }

    #[test]
    fn oversized_fields_queries_and_suffix_expansion_fall_back() {
        let too_long = "a".repeat(MAX_FIELD_BYTES + 1);
        assert!(open(&batch(&[[vec![], vec![&too_long], vec![], vec![]]])).is_none());
        let handle = open(&batch(&[[vec!["app"], vec![], vec![], vec![]]])).unwrap();
        assert!(query(handle, &too_long, "", "", "").is_none());
        close(handle);

        let mut index = Index {
            estimated_bytes: MAX_INDEX_BYTES,
            ..Index::default()
        };
        assert!(index.add(4, "example.com", 0).is_none());
    }

    #[test]
    fn closing_one_index_does_not_affect_another() {
        let bytes = batch(&[[vec!["app"], vec![], vec![], vec![]]]);
        let first = open(&bytes).unwrap();
        let second = open(&bytes).unwrap();
        assert_ne!(first, second);
        close(first);
        assert_eq!(query(first, "app", "", "", ""), None);
        assert_eq!(query(second, "app", "", "", ""), Some(vec![0]));
        close(second);
    }
}
