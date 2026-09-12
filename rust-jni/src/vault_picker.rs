#![forbid(unsafe_code)]

use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};

const MAGIC: u32 = 0x3150_4f4d; // MOP1: source index + normalized display metadata.
pub(crate) const MAX_BYTES: usize = 64 * 1024 * 1024;
const MAX_ROWS: usize = 100_000;

struct Row {
    source: i32,
    text: String,
}

/// An immutable, sheet-scoped index. Typing copies only the query across JNI.
/// Kotlin normalizes both text and query with Locale.ROOT, so Unicode matching
/// has exactly the same semantics in the native and fallback implementations.
struct Index {
    rows: Vec<Row>,
    sources: HashMap<i32, Vec<usize>>,
}

impl Index {
    fn parse(bytes: &[u8]) -> Option<Self> {
        if !(8..=MAX_BYTES).contains(&bytes.len()) {
            return None;
        }
        let mut cursor = Cursor(bytes);
        if cursor.u32()? != MAGIC {
            return None;
        }
        let count = cursor.u32()? as usize;
        if count > MAX_ROWS || count > cursor.0.len() / 8 {
            return None;
        }
        let mut rows = Vec::with_capacity(count);
        let mut sources = HashMap::<i32, Vec<usize>>::new();
        for i in 0..count {
            let source = i32::try_from(cursor.u32()?).ok()?;
            let len = cursor.u32()? as usize;
            let text = std::str::from_utf8(cursor.take(len)?).ok()?.to_owned();
            sources.entry(source).or_default().push(i);
            rows.push(Row { source, text });
        }
        cursor.0.is_empty().then_some(Self { rows, sources })
    }

    fn filter(&self, query: &str, source: i32) -> Option<Vec<i32>> {
        if source < -1 {
            return None;
        }
        let matches = |i: usize| self.rows[i].text.contains(query);
        Some(if source == -1 {
            self.rows
                .iter()
                .enumerate()
                .filter(|(_, row)| row.text.contains(query))
                .map(|(i, _)| i as i32)
                .collect()
        } else {
            self.sources
                .get(&source)
                .into_iter()
                .flatten()
                .copied()
                .filter(|&i| matches(i) && self.rows[i].source == source)
                .map(|i| i as i32)
                .collect()
        })
    }
}

struct Cursor<'a>(&'a [u8]);

impl<'a> Cursor<'a> {
    fn take(&mut self, len: usize) -> Option<&'a [u8]> {
        let result = self.0.get(..len)?;
        self.0 = self.0.get(len..)?;
        Some(result)
    }

    fn u32(&mut self) -> Option<u32> {
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

pub(crate) fn filter(handle: i64, query: &str, source: i32) -> Option<Vec<i32>> {
    // Release the registry lock before searching. Closing a sheet can remove
    // its handle while an in-flight search finishes safely with its own Arc.
    let index = registry().lock().ok()?.indices.get(&handle)?.clone();
    index.filter(query, source)
}

pub(crate) fn close(handle: i64) {
    let removed = registry()
        .lock()
        .ok()
        .and_then(|mut state| state.indices.remove(&handle));
    drop(removed); // Free strings outside the registry lock.
}

#[cfg(test)]
mod tests {
    use super::*;

    fn batch(rows: &[(u32, &str)]) -> Vec<u8> {
        let mut bytes = MAGIC.to_le_bytes().to_vec();
        bytes.extend_from_slice(&(rows.len() as u32).to_le_bytes());
        for (source, text) in rows {
            bytes.extend_from_slice(&source.to_le_bytes());
            bytes.extend_from_slice(&(text.len() as u32).to_le_bytes());
            bytes.extend_from_slice(text.as_bytes());
        }
        bytes
    }

    #[test]
    fn searches_bank_account_and_unicode_metadata_in_input_order() {
        let handle = open(&batch(&[
            (0, "日常卡\0招商银行\0•••• 1234"),
            (1, "github\0alice@example.test"),
            (0, "münchen\0алиса\0i\u{307}stanbul"),
            (1, "日常卡\0工商银行\0•••• 1234"),
        ]))
        .unwrap();
        assert_eq!(filter(handle, "1234", -1), Some(vec![0, 3]));
        assert_eq!(filter(handle, "1234", 1), Some(vec![3]));
        assert_eq!(filter(handle, "招商", 0), Some(vec![0]));
        assert_eq!(filter(handle, "alice@", -1), Some(vec![1]));
        assert_eq!(filter(handle, "алиса", -1), Some(vec![2]));
        assert_eq!(filter(handle, "i\u{307}st", -1), Some(vec![2]));
        assert_eq!(filter(handle, "", 0), Some(vec![0, 2]));
        assert_eq!(filter(handle, "", 99), Some(vec![]));
        assert_eq!(filter(handle, "", -2), None);
        close(handle);
    }

    #[test]
    fn rejects_truncation_invalid_lengths_sources_utf8_and_trailing_bytes() {
        let valid = batch(&[(0, "bank")]);
        for len in 0..valid.len() {
            assert!(open(&valid[..len]).is_none());
        }
        for offset in [0, 4, 8, 12, 16] {
            let mut invalid = valid.clone();
            invalid[offset] = 0xff;
            if offset == 8 {
                invalid[11] = 0xff;
            }
            assert!(open(&invalid).is_none(), "offset {offset}");
        }
        let mut invalid = valid;
        invalid.push(0);
        assert!(open(&invalid).is_none());
    }

    #[test]
    fn closing_is_idempotent_and_never_reuses_a_handle() {
        let first = open(&batch(&[(0, "one")])).unwrap();
        let in_flight = registry().lock().unwrap().indices[&first].clone();
        close(first);
        close(first);
        assert_eq!(filter(first, "", -1), None);
        assert_eq!(in_flight.filter("one", 0), Some(vec![0]));
        let second = open(&batch(&[])).unwrap();
        assert_ne!(first, second);
        assert_eq!(filter(second, "", -1), Some(vec![]));
        close(second);
    }
}
