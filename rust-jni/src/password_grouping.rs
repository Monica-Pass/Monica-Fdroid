#![forbid(unsafe_code)]

use std::cmp::Reverse;

pub(crate) const MAX_ENTRIES: usize = 200_000;
pub(crate) const HEADER: usize = 4;
const WIDTH: usize = 6;
pub(crate) const MAX_BATCH_LEN: usize = HEADER + MAX_ENTRIES * WIDTH;

fn filled<T: Clone>(count: usize, value: T) -> Option<Vec<T>> {
    let mut output = Vec::new();
    output.try_reserve_exact(count).ok()?;
    output.resize(count, value);
    Some(output)
}

/// A snapshot contains numeric metadata only, never credentials or display strings.
/// [version, count, expanded, title_mode, (bucket, info, outer, title_rank, order, favorite)...]
/// Output: [version, group_count, (outer, member_count, source_indices...)...].
pub(crate) fn project(batch: &[i32]) -> Option<Vec<i32>> {
    if batch.len() < HEADER || batch[0] != 1 {
        return None;
    }
    let count = usize::try_from(batch[1]).ok()?;
    if count > MAX_ENTRIES
        || batch.len() != HEADER.checked_add(count.checked_mul(WIDTH)?)?
        || !(0..=1).contains(&batch[2])
        || !(0..=1).contains(&batch[3])
    {
        return None;
    }
    let rows = &batch[HEADER..];
    for row in rows.chunks_exact(WIDTH) {
        if row[..4].iter().any(|&id| id < 0 || id as usize >= count) || !(0..=1).contains(&row[5]) {
            return None;
        }
    }
    let row = |index: usize| &rows[index * WIDTH..(index + 1) * WIDTH];
    let expanded = batch[2] == 1;
    let title_mode = batch[3] == 1;
    let mut sorted = Vec::new();
    sorted.try_reserve_exact(count).ok()?;
    sorted.extend(0..count);
    // Explicit source index retains Kotlin's stable order without sort scratch space.
    sorted.sort_unstable_by_key(|&index| (row(index)[4], index));

    let mut buckets = filled(count, Vec::<usize>::new())?;
    let mut bucket_order = Vec::new();
    bucket_order.try_reserve_exact(count).ok()?;
    let mut seen = filled(count, false)?;
    if expanded {
        // Expanded mode first orders single-entry groups by sortOrder.
        for &index in &sorted {
            bucket_order.push(index);
            buckets[index].try_reserve_exact(1).ok()?;
            buckets[index].push(index);
        }
    } else {
        // Collapsed mode retains each info/manual group's first source appearance.
        for index in 0..count {
            let key = row(index)[0] as usize;
            if !seen[key] {
                seen[key] = true;
                bucket_order.push(key);
            }
        }
        for &index in &sorted {
            let members = &mut buckets[row(index)[0] as usize];
            members.try_reserve(1).ok()?;
            members.push(index);
        }
    }

    let mut outer_groups = filled(count, Vec::<usize>::new())?;
    let mut outer_order = Vec::new();
    outer_order.try_reserve_exact(count).ok()?;
    for bucket in bucket_order {
        let members = &buckets[bucket];
        let key = row(*members.first()?)[2] as usize;
        let group = &mut outer_groups[key];
        if group.is_empty() {
            outer_order.push(key);
        }
        group.try_reserve(members.len()).ok()?;
        group.extend_from_slice(members);
    }

    let mut order_keys = filled(count, (Reverse(0), 0, 0))?;
    for (position, &key) in outer_order.iter().enumerate() {
        let members = &outer_groups[key];
        let first = row(members[0]);
        let has_distinct_info = members.iter().any(|&index| row(index)[1] != first[1]);
        let card_type = if has_distinct_info {
            3
        } else if members.len() > 1 {
            2
        } else {
            1
        };
        let favorite = members.iter().any(|&index| row(index)[5] == 1);
        order_keys[key] = (
            Reverse(card_type + if favorite { 10 } else { 0 }),
            if title_mode { first[3] } else { first[4] },
            position,
        );
    }
    outer_order.sort_unstable_by_key(|&key| order_keys[key]);
    let mut output = Vec::new();
    output
        .try_reserve_exact(count + outer_order.len() * 2 + 2)
        .ok()?;
    output.extend([1, outer_order.len() as i32]);
    for key in outer_order {
        let members = &outer_groups[key];
        output.extend([key as i32, members.len() as i32]);
        output.extend(members.iter().map(|&index| index as i32));
    }
    Some(output)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn batch(rows: &[[i32; 6]], expanded: bool, title: bool) -> Vec<i32> {
        let mut data = vec![1, rows.len() as i32, expanded as i32, title as i32];
        data.extend(rows.iter().flatten());
        data
    }

    #[test]
    fn stable_inner_order_and_first_sorted_member_choose_the_outer_group() {
        let rows = [[0, 0, 0, 0, 8, 0], [1, 1, 1, 1, 4, 0], [0, 0, 2, 2, -1, 0]];
        assert_eq!(
            project(&batch(&rows, false, false)),
            Some(vec![1, 2, 2, 2, 2, 0, 1, 1, 1])
        );
    }

    #[test]
    fn favorite_and_card_type_precede_title_order() {
        let rows = [
            [0, 0, 0, 0, 0, 0],
            [1, 1, 1, 1, 0, 1],
            [2, 2, 2, 2, 0, 0],
            [3, 3, 2, 2, 0, 0],
        ];
        assert_eq!(
            project(&batch(&rows, false, true)),
            Some(vec![1, 3, 1, 1, 1, 2, 2, 2, 3, 0, 1, 0])
        );
    }

    #[test]
    fn expanded_preserves_individual_entries_and_stable_equal_orders() {
        let rows = [[0, 0, 0, 0, 2, 0], [0, 0, 1, 1, 1, 0], [0, 0, 0, 0, 2, 0]];
        assert_eq!(
            project(&batch(&rows, true, false)),
            Some(vec![1, 2, 0, 2, 0, 2, 1, 1, 1])
        );
        assert_eq!(project(&[1, 0, 0, 0]), Some(vec![1, 0]));
    }

    #[test]
    fn malformed_and_oversize_batches_are_rejected_before_allocation() {
        for data in [
            vec![],
            vec![2, 0, 0, 0],
            vec![1, -1, 0, 0],
            vec![1, 200_001, 0, 0],
            vec![1, 0, 2, 0],
            vec![1, 1, 0, 0],
            vec![1, 0, 0, 0, 0],
        ] {
            assert_eq!(project(&data), None);
        }
        for column in [0, 1, 2, 3, 5] {
            let mut data = batch(&[[0; 6]], false, false);
            data[HEADER + column] = -1;
            assert_eq!(project(&data), None);
        }
    }

    #[test]
    fn maximum_batch_is_a_complete_permutation() {
        let rows: Vec<_> = (0..MAX_ENTRIES as i32)
            .map(|i| [i, i, i, i, -i, 0])
            .collect();
        let result = project(&batch(&rows, false, false)).unwrap();
        assert_eq!(result.len(), 2 + MAX_ENTRIES * 3);
        for (index, group) in result[2..].chunks_exact(3).enumerate() {
            let expected = MAX_ENTRIES as i32 - 1 - index as i32;
            assert_eq!(group, [expected, 1, expected]);
        }
    }
}
