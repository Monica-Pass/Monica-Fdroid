use std::cmp::{Ordering, Reverse};

pub(crate) const HEADER: usize = 6;
pub(crate) const WIDTH: usize = 8;
pub(crate) const MAX_ROWS: usize = 200_000;
pub(crate) const MAX_BATCH_LEN: usize = HEADER + WIDTH * MAX_ROWS;
const LIMIT: usize = 8;

#[derive(Clone, Copy)]
struct Ranked {
    index: usize,
    pin: i64,
    count: i64,
    last: i64,
}

impl Ranked {
    fn compare(&self, other: &Self) -> Ordering {
        (
            self.pin,
            Reverse(self.count),
            Reverse(self.last),
            self.index,
        )
            .cmp(&(
                other.pin,
                Reverse(other.count),
                Reverse(other.last),
                other.index,
            ))
    }
}

fn insert_best(best: &mut Vec<Ranked>, candidate: Ranked) {
    let position = best
        .iter()
        .position(|entry| candidate.compare(entry).is_lt())
        .unwrap_or(best.len());
    if position < LIMIT {
        best.insert(position, candidate);
        best.truncate(LIMIT);
    }
}

/// One snapshot batch. No names, account data or credentials enter this projection.
/// Header: version, source count, folder count, selected source (-1 = all), recommendations, type count.
/// Row: source (-1 = inaccessible), type, folder (-1 = none), favorite, pin (-1 = none), opens, last open, wallet card.
pub(crate) fn project(batch: &[i64]) -> Option<Vec<i32>> {
    if batch.len() < HEADER || batch.len() > MAX_BATCH_LEN || batch[0] != 1 {
        return None;
    }
    let rows = batch.get(HEADER..)?;
    if rows.len() % WIDTH != 0 {
        return None;
    }
    let source_count = usize::try_from(batch[1]).ok()?;
    let folder_count = usize::try_from(batch[2]).ok()?;
    let selected = batch[3];
    let recommendations = batch[4];
    let type_count = usize::try_from(batch[5]).ok()?;
    if source_count > 4096
        || folder_count > MAX_ROWS
        || selected < -1
        || selected >= source_count as i64
        || !(0..=3).contains(&recommendations)
        || !(1..=64).contains(&type_count)
    {
        return None;
    }
    let mut types = vec![0; type_count];
    let mut sources = vec![0; source_count];
    let mut folders = vec![0; folder_count];
    let mut visible = Vec::new();
    let mut favorites = Vec::new();
    let mut cards = Vec::with_capacity(LIMIT + 1);
    let mut items = Vec::with_capacity(LIMIT + 1);
    for (index, row) in rows.chunks_exact(WIDTH).enumerate() {
        let source = row[0];
        let kind = usize::try_from(row[1]).ok()?;
        let folder = row[2];
        if source < -1
            || source >= source_count as i64
            || kind >= type_count
            || folder < -1
            || folder >= folder_count as i64
            || !(0..=1).contains(&row[3])
            || row[4] < -1
            || row[5] < 0
            || row[6] < 0
            || !(0..=1).contains(&row[7])
        {
            return None;
        }
        if source == -1 {
            continue;
        }
        sources[source as usize] += 1;
        if selected != -1 && selected != source {
            continue;
        }
        visible.push(index as i32);
        types[kind] += 1;
        if folder != -1 {
            folders[folder as usize] += 1;
        }
        if row[3] == 1 {
            favorites.push(index as i32);
        }
        let card = row[7] == 1;
        let recommend = recommendations & if card { 1 } else { 2 } != 0;
        if row[4] == -1 && (!recommend || row[5] == 0) {
            continue;
        }
        let ranked = Ranked {
            index,
            pin: if row[4] < 0 { i64::MAX } else { row[4] },
            count: row[5],
            last: row[6],
        };
        insert_best(if card { &mut cards } else { &mut items }, ranked);
    }
    let mut output = vec![
        1,
        visible.len() as i32,
        favorites.len() as i32,
        cards.len() as i32,
        items.len() as i32,
    ];
    output.extend(types);
    output.extend(sources);
    output.extend(folders);
    output.extend(visible);
    output.extend(favorites);
    output.extend(cards.into_iter().map(|entry| entry.index as i32));
    output.extend(items.into_iter().map(|entry| entry.index as i32));
    Some(output)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn batch(selected: i64, recommendations: i64, rows: &[[i64; WIDTH]]) -> Vec<i64> {
        let mut data = vec![1, 2, 2, selected, recommendations, 7];
        data.extend(rows.iter().flatten());
        data
    }

    #[test]
    fn empty_database_has_zero_counts() {
        let output = project(&batch(-1, 3, &[])).expect("valid batch");
        assert_eq!(output[0], 1);
        assert!(output[1..].iter().all(|value| *value == 0));
    }

    #[test]
    fn scope_and_inaccessible_sources_are_respected() {
        let output = project(&batch(
            1,
            3,
            &[
                [0, 0, 0, 1, -1, 3, 8, 0],
                [1, 4, 1, 1, -1, 2, 9, 1],
                [-1, 4, 1, 1, 0, 999, 999, 1],
            ],
        ))
        .expect("valid batch");
        assert_eq!(&output[..5], &[1, 1, 1, 1, 0]);
        assert_eq!(&output[12..14], &[1, 1]);
        assert_eq!(&output[14..16], &[0, 1]);
        assert_eq!(&output[16..], &[1, 1, 1]);
    }

    #[test]
    fn card_and_item_pins_have_independent_rankings() {
        let output = project(&batch(
            -1,
            3,
            &[
                [0, 4, -1, 0, -1, 50, 99, 1],
                [0, 0, -1, 0, -1, 100, 99, 0],
                [0, 5, -1, 0, 0, 0, 0, 1],
                [0, 1, -1, 0, 0, 0, 0, 0],
            ],
        ))
        .expect("valid batch");
        assert_eq!(&output[20..], &[2, 0, 3, 1]);
    }

    #[test]
    fn disabling_card_recommendations_keeps_pins_and_item_recommendations() {
        let output = project(&batch(
            -1,
            2,
            &[
                [0, 4, -1, 0, -1, 50, 99, 1],
                [0, 0, -1, 0, -1, 100, 99, 0],
                [0, 5, -1, 0, 0, 0, 0, 1],
            ],
        ))
        .expect("valid batch");
        assert_eq!(&output[..5], &[1, 3, 0, 1, 1]);
        assert_eq!(&output[19..], &[2, 1]);
    }

    #[test]
    fn equal_scores_keep_input_order_and_limit_preview() {
        let rows = vec![[0, 0, -1, 0, -1, 2, 9, 0]; 100];
        let output = project(&batch(-1, 3, &rows)).expect("valid batch");
        assert_eq!(&output[output.len() - 8..], &[0, 1, 2, 3, 4, 5, 6, 7]);
    }

    #[test]
    fn rejects_malformed_batches_and_dimensions() {
        assert!(project(&[]).is_none());
        assert!(project(&[2, 2, 2, -1, 3, 7]).is_none());
        assert!(project(&[1, 2, 2, 2, 3, 7]).is_none());
        assert!(project(&[1, 2, i64::MAX, -1, 3, 7]).is_none());
        assert!(project(&batch(-1, 3, &[[2, 0, -1, 0, -1, 0, 0, 0]])).is_none());
        assert!(project(&batch(-1, 3, &[[0, 7, -1, 0, -1, 0, 0, 0]])).is_none());
        assert!(project(&batch(-1, 3, &[[0, 0, -1, 0, -1, -1, 0, 0]])).is_none());
        let mut truncated = batch(-1, 3, &[[0, 0, -1, 0, -1, 0, 0, 0]]);
        truncated.pop();
        assert!(project(&truncated).is_none());
    }

    #[test]
    fn bounded_ranking_matches_a_complete_sort() {
        let mut seed = 51_u64;
        let rows: Vec<_> = (0..2500)
            .map(|index| {
                seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1);
                [
                    0,
                    0,
                    -1,
                    0,
                    if index % 79 == 0 { index } else { -1 },
                    (seed % 50) as i64,
                    (seed % 1234) as i64,
                    0,
                ]
            })
            .collect();
        let mut expected: Vec<_> = rows
            .iter()
            .enumerate()
            .filter(|(_, row)| row[4] >= 0 || row[5] > 0)
            .map(|(index, row)| Ranked {
                index,
                pin: if row[4] < 0 { i64::MAX } else { row[4] },
                count: row[5],
                last: row[6],
            })
            .collect();
        expected.sort_by(Ranked::compare);
        let output = project(&batch(-1, 3, &rows)).expect("valid batch");
        assert_eq!(
            &output[output.len() - LIMIT..],
            expected[..LIMIT]
                .iter()
                .map(|entry| entry.index as i32)
                .collect::<Vec<_>>()
        );
    }
}
