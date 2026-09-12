#![forbid(unsafe_code)]

use std::collections::HashMap;

/// Numeric IDs only. Input: [1, card_count, stack_count, card_ids...,
/// member_count, member_ids..., ...]. Output: [1, group_count,
/// stack_index, member_count, card_indices..., ..., single_count, card_indices...].
pub(crate) fn project_indices(batch: &[i64], selection: bool) -> Option<Vec<i32>> {
    if batch.first() != Some(&1) {
        return None;
    }
    let card_count = usize::try_from(*batch.get(1)?).ok()?;
    let stack_count = usize::try_from(*batch.get(2)?).ok()?;
    let mut cursor = 3_usize.checked_add(card_count)?;
    let card_ids = batch.get(3..cursor)?;
    if card_count > i32::MAX as usize
        || stack_count > i32::MAX as usize
        || stack_count > batch.len() - cursor
    {
        return None;
    }

    let mut visible = HashMap::with_capacity(card_count);
    for (index, &id) in card_ids.iter().enumerate() {
        // Invalid snapshots use the existing Kotlin behavior instead.
        if visible.insert(id, index).is_some() {
            return None;
        }
    }
    let minimum_members = if selection { 1 } else { 2 };
    let mut owner = vec![usize::MAX; card_count];
    let mut seen_in_stack = vec![usize::MAX; card_count];
    let mut groups = Vec::with_capacity(stack_count);
    let mut qualified_count = 0;
    for stack_index in 0..stack_count {
        let count = usize::try_from(*batch.get(cursor)?).ok()?;
        cursor += 1;
        let end = cursor.checked_add(count)?;
        let ids = batch.get(cursor..end)?;
        cursor = end;
        let mut members = Vec::with_capacity(count.min(card_count));
        for id in ids {
            if let Some(&index) = visible.get(id) {
                if seen_in_stack[index] != stack_index {
                    seen_in_stack[index] = stack_index;
                    members.push(index as i32);
                }
            }
        }
        if members.len() >= minimum_members {
            for &index in &members {
                if owner[index as usize] != usize::MAX {
                    return None;
                }
                owner[index as usize] = stack_index;
            }
            qualified_count += 1;
        }
        groups.push(members);
    }
    if cursor != batch.len() {
        return None;
    }

    let output_size = card_count
        .checked_add(qualified_count * 2)?
        .checked_add(3)?;
    if output_size > i32::MAX as usize {
        return None;
    }
    let mut output = Vec::with_capacity(output_size);
    output.extend([1, qualified_count as i32]);
    let mut emitted = vec![false; stack_count];
    // First appearance in the sorted, filtered wallet determines group order.
    // Members retain their saved order, independently of the wallet's sort order.
    for &stack_index in &owner {
        if stack_index != usize::MAX && !emitted[stack_index] {
            emitted[stack_index] = true;
            let members = &groups[stack_index];
            output.extend([stack_index as i32, members.len() as i32]);
            output.extend_from_slice(members);
        }
    }
    let single_count_index = output.len();
    output.push(0);
    for (index, &stack_index) in owner.iter().enumerate() {
        if stack_index == usize::MAX {
            output.push(index as i32);
        }
    }
    output[single_count_index] = (output.len() - single_count_index - 1) as i32;
    Some(output)
}

#[cfg(test)]
mod tests {
    use super::project_indices;

    #[test]
    fn pins_groups_in_first_visible_order_and_keeps_saved_member_order() {
        let batch = [1, 8, 2, 8, 5, 3, 7, 2, 1, 4, 6, 2, 2, 1, 2, 4, 3];
        let expected = vec![1, 2, 1, 2, 6, 2, 0, 2, 4, 5, 4, 0, 1, 3, 7];
        assert_eq!(project_indices(&batch, false), Some(expected.clone()));
        assert_eq!(project_indices(&batch, true), Some(expected));
    }

    #[test]
    fn filters_and_deduplicates_members_without_reordering_them() {
        let batch = [1, 3, 1, 3, 1, 9, 6, 1, 1, 2, 3, 4, 3];
        assert_eq!(
            project_indices(&batch, false),
            Some(vec![1, 1, 0, 2, 1, 0, 1, 2])
        );
    }

    #[test]
    fn a_single_visible_member_retains_its_section_only_in_selection() {
        let batch = [1, 2, 2, 9, 2, 3, 1, 2, 3, 2, 5, 6];
        assert_eq!(project_indices(&batch, false), Some(vec![1, 0, 2, 0, 1]));
        assert_eq!(
            project_indices(&batch, true),
            Some(vec![1, 1, 0, 1, 1, 1, 0])
        );
    }

    #[test]
    fn empty_snapshots_and_unstacked_wallets_are_complete_permutations() {
        assert_eq!(project_indices(&[1, 0, 0], false), Some(vec![1, 0, 0]));
        assert_eq!(
            project_indices(&[1, 0, 1, 2, 1, 2], true),
            Some(vec![1, 0, 0])
        );
        assert_eq!(
            project_indices(&[1, 2, 0, 7, 8], true),
            Some(vec![1, 0, 2, 0, 1])
        );
    }

    #[test]
    fn rejects_duplicate_visible_ids_and_overlapping_qualified_groups() {
        assert_eq!(project_indices(&[1, 2, 0, 7, 7], false), None);
        let overlap = [1, 3, 2, 1, 2, 3, 2, 1, 2, 2, 2, 3];
        assert_eq!(project_indices(&overlap, false), None);
        assert_eq!(project_indices(&overlap, true), None);
    }

    #[test]
    fn invisible_and_unqualified_groups_do_not_claim_members() {
        let batch = [1, 2, 2, 2, 1, 2, 1, 9, 2, 1, 2];
        assert_eq!(
            project_indices(&batch, false),
            Some(vec![1, 1, 1, 2, 1, 0, 0])
        );
        assert_eq!(project_indices(&batch, true), None);
    }

    #[test]
    fn never_truncates_large_signed_card_ids() {
        let batch = [1, 3, 1, i64::MIN, 0, i64::MAX, 2, i64::MAX, i64::MIN];
        assert_eq!(
            project_indices(&batch, false),
            Some(vec![1, 1, 0, 2, 2, 0, 1, 1])
        );
    }

    #[test]
    fn rejects_malformed_or_unbounded_frames_before_allocating() {
        let invalid: &[&[i64]] = &[
            &[],
            &[2, 0, 0],
            &[1],
            &[1, 0],
            &[1, -1, 0],
            &[1, 0, -1],
            &[1, i64::MAX, 0],
            &[1, 0, i64::MAX],
            &[1, 2, 0, 1],
            &[1, 0, 0, 9],
            &[1, 0, 1],
            &[1, 0, 1, -1],
            &[1, 0, 1, i64::MAX],
            &[1, 0, 1, 2, 1],
        ];
        for batch in invalid {
            assert_eq!(project_indices(batch, false), None, "{batch:?}");
        }
    }
}
