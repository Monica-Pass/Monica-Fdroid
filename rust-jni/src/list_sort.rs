#![forbid(unsafe_code)]

use std::cmp::Reverse;

/// Version 1: [version, favorite, order, id, updated_at, ...]. No secrets or strings.
pub(crate) fn sort_indices(batch: &[i64], tie_by_id: bool) -> Option<Vec<i32>> {
    if batch.first() != Some(&1) || (batch.len() - 1) % 4 != 0 {
        return None;
    }
    let rows = &batch[1..];
    let count = rows.len() / 4;
    if count > i32::MAX as usize || rows.chunks_exact(4).any(|row| !(0..=1).contains(&row[0])) {
        return None;
    }
    let mut indices: Vec<i32> = (0..count as i32).collect();
    // Original index explicitly preserves stable order for equal keys.
    indices.sort_unstable_by_key(|&index| {
        let row = &rows[index as usize * 4..];
        (
            Reverse(row[0]),
            row[1],
            if tie_by_id { row[2] } else { 0 },
            Reverse(row[3]),
            index,
        )
    });
    Some(indices)
}

#[cfg(test)]
mod tests {
    use super::sort_indices;

    #[test]
    fn preserves_favorites_order_and_both_existing_tie_breakers() {
        let batch = [1, 0, 0, 8, 30, 1, 0, 4, 10, 1, 0, 5, 20, 1, -1, 6, 0];
        assert_eq!(sort_indices(&batch, true), Some(vec![3, 1, 2, 0]));
        assert_eq!(sort_indices(&batch, false), Some(vec![3, 2, 1, 0]));
    }

    #[test]
    fn handles_extreme_signed_values_and_stable_ties() {
        let batch = [
            1,
            0,
            0,
            i64::MAX,
            i64::MIN,
            0,
            0,
            i64::MIN,
            i64::MAX,
            0,
            0,
            i64::MIN,
            i64::MAX,
        ];
        assert_eq!(sort_indices(&batch, true), Some(vec![1, 2, 0]));
        assert_eq!(sort_indices(&[1], true), Some(vec![]));
    }

    #[test]
    fn rejects_malformed_frames() {
        for batch in [&[][..], &[2][..], &[1, 0][..], &[1, 2, 0, 0, 0][..]] {
            assert_eq!(sort_indices(batch, true), None);
        }
    }
}
