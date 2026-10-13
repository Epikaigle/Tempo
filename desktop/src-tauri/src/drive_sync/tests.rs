use super::*;
use flate2::{write::GzEncoder, Compression};
use reqwest::Method;
use rusqlite::{params, Connection};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::io::Write;
use uuid::Uuid;

#[test]
fn retry_helper_rejects_ambiguous_drive_mutations() {
    assert!(is_drive_retry_safe(&Method::GET));
    assert!(is_drive_retry_safe(&Method::HEAD));
    assert!(is_drive_retry_safe(&Method::DELETE));
    assert!(!is_drive_retry_safe(&Method::POST));
    assert!(!is_drive_retry_safe(&Method::PATCH));
    assert!(!is_drive_retry_safe(&Method::PUT));
}

#[test]
fn google_reconnect_policy_does_not_confuse_network_failures_with_revocation() {
    assert!(google_reconnect_required(
        "Google Drive request failed (HTTP 401 Unauthorized)"
    ));
    assert!(google_reconnect_required(
        "Google authorization failed (invalid_grant)"
    ));
    assert!(google_reconnect_required(
        "Google Drive needs you to reconnect"
    ));
    assert!(!google_reconnect_required(
        "Google Drive request failed (HTTP 503 Service Unavailable)"
    ));
    assert!(!google_reconnect_required(
        "Google Drive request failed (HTTP 403 Forbidden)"
    ));
}

#[test]
fn unordered_drive_pages_are_sorted_before_cursor_checkpointing() {
    let make = |id: &str, created: &str| DriveFileRecord {
        id: id.to_string(),
        name: "history.json.gz".into(),
        size: None,
        created_time: Some(created.to_string()),
        modified_time: None,
        app_properties: HashMap::new(),
    };
    let mut files = vec![
        make("late", "2026-01-03T00:00:00Z"),
        make("tie-z", "2026-01-02T00:00:00Z"),
        make("early", "2026-01-01T00:00:00Z"),
        make("tie-a", "2026-01-02T00:00:00Z"),
    ];
    sort_batches_oldest_first(&mut files);
    assert_eq!(
        files
            .iter()
            .map(|file| file.id.as_str())
            .collect::<Vec<_>>(),
        vec!["early", "tie-a", "tie-z", "late"]
    );
}

fn history_storage_fixture() -> (std::path::PathBuf, Connection) {
    let directory = std::env::temp_dir().join(format!("tempo-drive-history-{}", Uuid::new_v4()));
    std::fs::create_dir_all(&directory).unwrap();
    drop(crate::db::Database::new(&db_path(&directory)).unwrap());
    let conn = open_sync_db(&directory).unwrap();
    (directory, conn)
}

#[test]
fn cross_account_imports_do_not_merge_same_title_and_timestamp() {
    let (directory, conn) = history_storage_fixture();
    let first = fixture_batch().events[0].clone();
    assert!(
        insert_remote_event_for_account(&conn, "remote-a", &first, "google-account-a").unwrap()
    );
    let mut second = first;
    second.event_id = "b".repeat(64);
    second.timestamp_utc += 500;
    assert!(
        insert_remote_event_for_account(&conn, "remote-b", &second, "google-account-b").unwrap(),
        "an imported play from account A cannot consume B's distinct event"
    );
    let owners: Vec<String> = conn
        .prepare("SELECT owner_account_subject FROM drive_event_state ORDER BY scrobble_id")
        .unwrap()
        .query_map([], |row| row.get(0))
        .unwrap()
        .collect::<Result<_, _>>()
        .unwrap();
    assert_eq!(owners, vec!["google-account-a", "google-account-b"]);
    assert!(
        !insert_remote_event_for_account(&conn, "remote-b", &second, "google-account-b").unwrap()
    );
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn unknown_legacy_import_owner_is_quarantined_and_never_matches_new_account() {
    let (directory, conn) = history_storage_fixture();
    let first = fixture_batch().events[0].clone();
    assert!(
        insert_remote_event_for_account(&conn, "remote-a", &first, "google-account-a").unwrap()
    );
    conn.execute(
        "UPDATE drive_event_state SET owner_account_subject = NULL
         WHERE drive_imported = 1",
        [],
    )
    .unwrap();
    conn.execute(
        "DELETE FROM drive_sync_migrations
         WHERE name = 'legacy_aliases_and_account_v1'",
        [],
    )
    .unwrap();
    drop(conn);
    let conn = open_sync_db(&directory).unwrap();
    let quarantined: String = conn
        .query_row(
            "SELECT owner_account_subject FROM drive_event_state WHERE drive_imported = 1",
            [],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(quarantined, LEGACY_UNVERIFIED_ACCOUNT);
    let mut second = first;
    second.event_id = "c".repeat(64);
    assert!(
        insert_remote_event_for_account(&conn, "remote-b", &second, "google-account-b").unwrap()
    );
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn the_same_producer_id_can_be_imported_into_two_google_accounts() {
    let (directory, conn) = history_storage_fixture();
    let original = fixture_batch().events[0].clone();
    assert!(
        insert_remote_event_for_account(&conn, "source-device", &original, "subject-a").unwrap()
    );
    assert!(
        !insert_remote_event_for_account(&conn, "source-device", &original, "subject-a").unwrap()
    );
    assert!(
        insert_remote_event_for_account(&conn, "source-device", &original, "subject-b").unwrap()
    );
    let state_count: i64 = conn
        .query_row(
            "SELECT count(*) FROM drive_event_state
         WHERE origin_event_id = ?1",
            [&original.event_id],
            |row| row.get(0),
        )
        .unwrap();
    let alias_count: i64 = conn
        .query_row(
            "SELECT count(*) FROM drive_event_aliases
         WHERE origin_event_id = ?1",
            [&original.event_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!((state_count, alias_count), (2, 2));
    assert_eq!(
        conn.query_row(
            "SELECT count(DISTINCT account_subject) FROM drive_event_aliases
         WHERE origin_event_id = ?1",
            [&original.event_id],
            |row| row.get::<_, i64>(0),
        )
        .unwrap(),
        2
    );
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn scrobble_deletion_prunes_origin_rows_without_a_full_reopen_scan() {
    let (directory, conn) = history_storage_fixture();
    let event = fixture_batch().events[0].clone();
    assert!(insert_remote_event(&conn, "device-a", &event).unwrap());
    let id: i64 = conn
        .query_row("SELECT id FROM scrobbles", [], |row| row.get(0))
        .unwrap();
    conn.execute("DELETE FROM scrobbles WHERE id = ?1", [id])
        .unwrap();
    let (states, aliases): (i64, i64) = conn
        .query_row(
            "SELECT (SELECT count(*) FROM drive_event_state),
                (SELECT count(*) FROM drive_event_aliases)",
            [],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .unwrap();
    assert_eq!((states, aliases), (0, 0));
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn alias_backfill_runs_once_and_keeps_legacy_origin_mapping() {
    let (directory, conn) = history_storage_fixture();
    conn.execute(
        "INSERT INTO scrobbles (title, artist, timestamp_utc)
        VALUES ('Track', 'Artist', 1700000000000)",
        [],
    )
    .unwrap();
    let id = conn.last_insert_rowid();
    let origin = "d".repeat(64);
    conn.execute(
        "INSERT INTO drive_event_state
        (scrobble_id, origin_event_id, origin_device_id, drive_imported)
        VALUES (?1, ?2, 'old-desktop', 0)",
        params![id, origin],
    )
    .unwrap();
    conn.execute(
        "DELETE FROM drive_sync_migrations
        WHERE name = 'legacy_aliases_and_account_v1'",
        [],
    )
    .unwrap();
    drop(conn);
    let conn = open_sync_db(&directory).unwrap();
    let mapped: i64 = conn
        .query_row(
            "SELECT scrobble_id FROM drive_event_aliases WHERE origin_event_id = ?1",
            [&origin],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(mapped, id);
    conn.execute(
        "DELETE FROM drive_event_aliases WHERE origin_event_id = ?1",
        [&origin],
    )
    .unwrap();
    drop(conn);
    let conn = open_sync_db(&directory).unwrap();
    let count: i64 = conn
        .query_row(
            "SELECT count(*) FROM drive_event_aliases WHERE origin_event_id = ?1",
            [&origin],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(
        count, 0,
        "backfill must not rescan all history on each open"
    );
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn temporal_dedup_normalizes_accents_and_whitespace_and_skips_other_candidates() {
    let (directory, conn) = history_storage_fixture();
    let mut event = fixture_batch().events[0].clone();
    event.title = "été 🔊".into();
    event.artist = "björk".into();
    conn.execute(
        "INSERT INTO scrobbles (title, artist, timestamp_utc) VALUES ('Other', 'Artist', ?1)",
        [event.timestamp_utc],
    )
    .unwrap();
    conn.execute(
        "INSERT INTO scrobbles (title, artist, timestamp_utc) VALUES ('  ÉTÉ 🔊  ', ' BJÖRK ', ?1)",
        [event.timestamp_utc + 500],
    )
    .unwrap();
    let matching_id = conn.last_insert_rowid();
    assert!(!insert_remote_event(&conn, "remote-device", &event).unwrap());
    let (count, recorded): (i64, i64) = conn.query_row(
        "SELECT (SELECT count(*) FROM scrobbles), scrobble_id FROM drive_event_aliases WHERE origin_event_id = ?1",
        [&event.event_id], |row| Ok((row.get(0)?, row.get(1)?))
    ).unwrap();
    assert_eq!((count, recorded), (2, matching_id));
    assert!(!insert_remote_event(&conn, "remote-device", &event).unwrap());
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn remote_copy_does_not_claim_a_legacy_unpinned_local_capture() {
    let (directory, conn) = history_storage_fixture();
    let event = fixture_batch().events[0].clone();
    conn.execute(
        "INSERT INTO scrobbles (title, artist, timestamp_utc)
         VALUES (?1, ?2, ?3)",
        params![event.title, event.artist, event.timestamp_utc],
    )
    .unwrap();
    let local_id = conn.last_insert_rowid();
    assert!(!insert_remote_event(&conn, "other-producer", &event).unwrap());
    let (imported, own_id, own_device): (i64, String, String) = conn
        .query_row(
            "SELECT drive_imported, origin_event_id, origin_device_id
         FROM drive_event_state WHERE scrobble_id = ?1",
            [local_id],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )
        .unwrap();
    assert_eq!(imported, 0);
    assert_eq!(own_device, load_state(&conn).unwrap().device_id);
    assert_ne!(own_id, event.event_id);
    assert_eq!(pending_local_plays(&conn).unwrap().len(), 1);
    assert!(!insert_remote_event(&conn, "other-producer", &event).unwrap());
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn remote_temporal_match_never_converts_local_capture_into_imported_play() {
    let (directory, conn) = history_storage_fixture();
    let event = fixture_batch().events[0].clone();
    conn.execute(
        "INSERT INTO scrobbles (title, artist, timestamp_utc)
         VALUES (?1, ?2, ?3)",
        params![event.title, event.artist, event.timestamp_utc],
    )
    .unwrap();
    let local_id = conn.last_insert_rowid();
    let device_id = load_state(&conn).unwrap().device_id;
    let own_origin = pin_local_origin(
        &conn,
        &device_id,
        local_id,
        event.timestamp_utc,
        &event.title,
        &event.artist,
    )
    .unwrap();

    assert!(!insert_remote_event(&conn, "other-producer", &event).unwrap());
    let (original_id, is_imported, sent_at): (String, i64, Option<i64>) = conn
        .query_row(
            "SELECT origin_event_id, drive_imported, drive_uploaded_at
             FROM drive_event_state WHERE scrobble_id = ?1",
            [local_id],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )
        .unwrap();
    assert_eq!(original_id, own_origin);
    assert_eq!(
        is_imported, 0,
        "a local capture cannot become a cloud import"
    );
    assert_eq!(sent_at, None);
    assert_eq!(
        pending_local_plays(&conn).unwrap().len(),
        1,
        "the Desktop producer must still be able to publish its own event"
    );
    let alias_id: i64 = conn
        .query_row(
            "SELECT scrobble_id FROM drive_event_aliases WHERE origin_event_id = ?1",
            [&event.event_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(alias_id, local_id);
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn temporally_close_different_versions_are_not_collapsed() {
    let (directory, conn) = history_storage_fixture();
    let original = fixture_batch().events[0].clone();
    let mut remix = original.clone();
    remix.event_id = "c".repeat(64);
    remix.timestamp_utc += 500;
    remix.duration_ms += 30_000;
    assert!(insert_remote_event(&conn, "device-one", &original).unwrap());
    assert!(
        insert_remote_event(&conn, "device-two", &remix).unwrap(),
        "recordings with substantially different known lengths are not the same play"
    );
    let count: i64 = conn
        .query_row("SELECT COUNT(*) FROM scrobbles", [], |row| row.get(0))
        .unwrap();
    assert_eq!(count, 2);
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn independent_cross_device_replay_seven_seconds_later_stays_distinct() {
    let (directory, conn) = history_storage_fixture();
    let original = fixture_batch().events[0].clone();
    let mut replay = original.clone();
    replay.event_id = "a".repeat(64);
    replay.timestamp_utc += 7_000;
    assert!(insert_remote_event(&conn, "device-one", &original).unwrap());
    assert!(
        insert_remote_event(&conn, "device-two", &replay).unwrap(),
        "a 7-second independent replay must not be lost to temporal deduplication"
    );
    let count: i64 = conn
        .query_row("SELECT COUNT(*) FROM scrobbles", [], |row| row.get(0))
        .unwrap();
    assert_eq!(count, 2);
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn separate_capture_app_session_ids_do_not_hide_a_single_play() {
    let (directory, conn) = history_storage_fixture();
    let mut first = fixture_batch().events[0].clone();
    first.session_id = Some("session-one".into());
    let mut second = first.clone();
    second.session_id = Some("session-two".into());
    second.event_id = "a".repeat(64);
    second.timestamp_utc += 500;
    assert!(insert_remote_event(&conn, "device-one", &first).unwrap());
    assert!(
        !insert_remote_event(&conn, "device-two", &second).unwrap(),
        "source-local session IDs are not proof of distinct physical plays"
    );
    let count: i64 = conn
        .query_row("SELECT COUNT(*) FROM scrobbles", [], |row| row.get(0))
        .unwrap();
    assert_eq!(count, 1);
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn different_devices_playing_same_short_track_25_seconds_apart_stay_distinct() {
    let (directory, conn) = history_storage_fixture();
    let original = fixture_batch().events[0].clone();
    let mut second_play = original.clone();
    second_play.event_id = "a".repeat(64);
    second_play.timestamp_utc += 25_000;
    assert!(insert_remote_event(&conn, "device-one", &original).unwrap());
    assert!(
        insert_remote_event(&conn, "device-two", &second_play).unwrap(),
        "a second 25-second replay on another device cannot be deduplicated"
    );
    let count: i64 = conn
        .query_row("SELECT COUNT(*) FROM scrobbles", [], |row| row.get(0))
        .unwrap();
    assert_eq!(count, 2);
    assert!(
        !insert_remote_event(&conn, "device-two", &second_play).unwrap(),
        "the second play must still be idempotent by its exact event ID"
    );
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn every_reconciled_origin_survives_restart_without_collapsing_rapid_replays() {
    let (directory, conn) = history_storage_fixture();
    let first = fixture_batch().events[0].clone();
    let mut second_capture = first.clone();
    second_capture.event_id = "b".repeat(64);
    second_capture.timestamp_utc += 50;
    let mut third_capture = first.clone();
    third_capture.event_id = "c".repeat(64);
    third_capture.timestamp_utc += 100;
    assert!(insert_remote_event(&conn, "device-a", &first).unwrap());
    assert!(!insert_remote_event(&conn, "device-b", &second_capture).unwrap());
    assert!(!insert_remote_event(&conn, "device-c", &third_capture).unwrap());
    drop(conn);
    let conn = open_sync_db(&directory).unwrap();
    let mut replay = first.clone();
    replay.event_id = "d".repeat(64);
    replay.timestamp_utc += 1000;
    let mut replay_b = replay.clone();
    replay_b.event_id = "e".repeat(64);
    replay_b.timestamp_utc += 50;
    let mut replay_c = replay.clone();
    replay_c.event_id = "f".repeat(64);
    replay_c.timestamp_utc += 100;
    assert!(insert_remote_event(&conn, "device-a", &replay).unwrap());
    assert!(!insert_remote_event(&conn, "device-b", &replay_b).unwrap());
    assert!(!insert_remote_event(&conn, "device-c", &replay_c).unwrap());
    for (device, event) in [
        ("device-a", &first),
        ("device-b", &second_capture),
        ("device-c", &third_capture),
        ("device-a", &replay),
        ("device-b", &replay_b),
        ("device-c", &replay_c),
    ] {
        assert!(!insert_remote_event(&conn, device, event).unwrap());
    }
    let counts: (i64, i64) = conn
        .query_row(
            "SELECT (SELECT count(*) FROM scrobbles), (SELECT count(*) FROM drive_event_aliases)",
            [],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .unwrap();
    assert_eq!(counts, (2, 6));
    let primary_origins: Vec<String> = conn
        .prepare("SELECT origin_event_id FROM drive_event_state ORDER BY scrobble_id")
        .unwrap()
        .query_map([], |row| row.get(0))
        .unwrap()
        .collect::<Result<_, _>>()
        .unwrap();
    assert_eq!(primary_origins, vec![first.event_id, replay.event_id]);
    assert!(pending_local_plays(&conn).unwrap().is_empty());
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn legacy_origins_migrate_with_one_rescan_and_deleted_rows_release_their_aliases() {
    let (directory, conn) = history_storage_fixture();
    let event = fixture_batch().events[0].clone();
    assert!(insert_remote_event(&conn, "device-a", &event).unwrap());
    conn.execute_batch(
        "DROP TABLE drive_event_aliases;
        DELETE FROM drive_sync_migrations WHERE name IN
            ('reconciled_origins_v1', 'legacy_aliases_and_account_v1');
        UPDATE drive_sync_state SET download_cursor = 123 WHERE id = 1;",
    )
    .unwrap();
    drop(conn);
    let conn = open_sync_db(&directory).unwrap();
    assert_eq!(load_state(&conn).unwrap().download_cursor, 0);
    let source: String = conn
        .query_row(
            "SELECT source_device_id FROM drive_event_aliases WHERE origin_event_id = ?1",
            [&event.event_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(source, "device-a");
    conn.execute(
        "UPDATE drive_sync_state SET download_cursor = 999 WHERE id = 1",
        [],
    )
    .unwrap();
    drop(conn);
    let conn = open_sync_db(&directory).unwrap();
    assert_eq!(load_state(&conn).unwrap().download_cursor, 999);
    conn.execute("DELETE FROM scrobbles", []).unwrap();
    drop(conn);
    let conn = open_sync_db(&directory).unwrap();
    let counts: (i64, i64) = conn.query_row(
        "SELECT (SELECT count(*) FROM drive_event_state), (SELECT count(*) FROM drive_event_aliases)",
        [], |row| Ok((row.get(0)?, row.get(1)?))
    ).unwrap();
    assert_eq!(counts, (0, 0));
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn origin_alias_failure_rolls_back_the_whole_import_transaction() {
    let (directory, conn) = history_storage_fixture();
    conn.execute_batch(
        "CREATE TRIGGER fail_origin BEFORE INSERT ON drive_event_aliases
        BEGIN SELECT RAISE(ABORT, 'storage failure'); END;",
    )
    .unwrap();
    let transaction = conn.unchecked_transaction().unwrap();
    assert!(insert_remote_event(&transaction, "device-a", &fixture_batch().events[0]).is_err());
    drop(transaction);
    let counts: (i64, i64, i64) = conn.query_row(
        "SELECT (SELECT count(*) FROM scrobbles), (SELECT count(*) FROM drive_event_state), (SELECT count(*) FROM drive_event_aliases)",
        [], |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?))
    ).unwrap();
    assert_eq!(counts, (0, 0, 0));
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn rerecording_an_uploaded_origin_is_idempotent_and_cannot_move_its_identity() {
    let (directory, conn) = history_storage_fixture();
    let event = fixture_batch().events[0].clone();
    assert!(insert_remote_event(&conn, "device-a", &event).unwrap());
    let id: i64 = conn
        .query_row("SELECT id FROM scrobbles", [], |row| row.get(0))
        .unwrap();
    remember_origin(&conn, id, "device-a", &event.event_id).unwrap();
    remember_origin(&conn, id, "device-a", &event.event_id).unwrap();
    assert!(remember_origin(&conn, id + 1, "device-a", &event.event_id).is_err());
    assert!(remember_origin(&conn, id, "device-b", &event.event_id).is_err());
    let recorded: (i64, String) = conn
        .query_row(
            "SELECT scrobble_id, source_device_id FROM drive_event_aliases",
            [],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .unwrap();
    assert_eq!(recorded, (id, "device-a".into()));
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

fn oauth_storage_fixture() -> (std::path::PathBuf, Connection) {
    let directory = std::env::temp_dir().join(format!("tempo-oauth-test-{}", Uuid::new_v4()));
    std::fs::create_dir_all(&directory).unwrap();
    let conn = Connection::open(db_path(&directory)).unwrap();
    conn.execute_batch(
        "CREATE TABLE scrobbles (id INTEGER PRIMARY KEY); INSERT INTO scrobbles VALUES (1), (2);",
    )
    .unwrap();
    drop(conn);
    let conn = open_sync_db(&directory).unwrap();
    conn.execute_batch(
        "UPDATE drive_sync_state SET enabled = 1, account_email = 'old@example.com', account_subject = 'google-stable-123',
         access_token = 'old-access', refresh_token = 'old-refresh', token_expires_at = 9000000000000,
         download_cursor = 123, accepted_disable_version = 100 WHERE id = 1;
         INSERT INTO drive_event_state (scrobble_id, drive_imported, drive_uploaded_at) VALUES (1, 0, 200), (2, 1, 200);"
    ).unwrap();
    (directory, conn)
}

#[test]
fn keyring_credentials_are_never_reused_across_google_subjects() {
    let account_a = serde_json::to_string(&SubjectBoundRefreshToken {
        sub: "google-a".into(),
        refresh_token: "secret-a".into(),
    })
    .unwrap();
    let account_b = serde_json::to_string(&SubjectBoundRefreshToken {
        sub: "google-b".into(),
        refresh_token: "secret-b".into(),
    })
    .unwrap();
    assert_eq!(
        decode_subject_bound_token(&account_a, "google-a").unwrap(),
        "secret-a"
    );
    assert_eq!(
        decode_subject_bound_token(&account_b, "google-b").unwrap(),
        "secret-b"
    );
    assert!(
        decode_subject_bound_token(&account_b, "google-a").is_err(),
        "an interrupted B sign-in must not later refresh account A with B's keyring token"
    );
    assert!(
        decode_subject_bound_token("old-plaintext-refresh-token", "google-a").is_err(),
        "unbound legacy credentials require fresh consent"
    );
}

#[tokio::test]
async fn interrupted_credential_replacement_cannot_refresh_or_use_an_unknown_account() {
    let (directory, conn) = oauth_storage_fixture();
    prepare_oauth_credentials(&conn, true).unwrap();
    let state = load_state(&conn).unwrap();
    assert!(!state.enabled);
    assert!(
        state.account_email.is_none()
            && state.account_subject.is_none()
            && state.access_token.is_none()
            && state.refresh_token.is_none()
    );
    assert_eq!(
        (state.download_cursor, state.accepted_disable_version),
        (0, 0)
    );
    drop(conn);
    assert!(access_token(&directory)
        .await
        .unwrap_err()
        .contains("identity is unavailable"));
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn reconnecting_the_same_account_stops_before_replacement_and_preserves_cursors() {
    let (directory, conn) = oauth_storage_fixture();
    prepare_oauth_credentials(&conn, false).unwrap();
    let state = load_state(&conn).unwrap();
    assert!(!state.enabled && state.account_email.is_none() && state.account_subject.is_none());
    assert_eq!(
        (state.download_cursor, state.accepted_disable_version),
        (123, 100)
    );
    let uploaded: i64 = conn
        .query_row(
            "SELECT drive_uploaded_at FROM drive_event_state WHERE scrobble_id = 1",
            [],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(uploaded, 200);
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn failure_to_prepare_credentials_rolls_back_cursors_before_any_keyring_write() {
    let (directory, conn) = oauth_storage_fixture();
    conn.execute_batch("CREATE TRIGGER fail_oauth_stop BEFORE UPDATE OF enabled ON drive_sync_state BEGIN SELECT RAISE(ABORT, 'storage failure'); END;").unwrap();
    assert!(prepare_oauth_credentials(&conn, true).is_err());
    let state = load_state(&conn).unwrap();
    assert!(state.enabled);
    assert_eq!(state.account_email.as_deref(), Some("old@example.com"));
    assert_eq!(
        (state.download_cursor, state.accepted_disable_version),
        (123, 100)
    );
    let uploaded: i64 = conn
        .query_row(
            "SELECT drive_uploaded_at FROM drive_event_state WHERE scrobble_id = 1",
            [],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(uploaded, 200);
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn switching_google_accounts_does_not_republish_previous_local_history() {
    let (directory, conn) = history_storage_fixture();
    conn.execute(
        "UPDATE drive_sync_state SET account_subject = 'account-a',
        account_email = 'a@example.com', enabled = 1 WHERE id = 1",
        [],
    )
    .unwrap();
    conn.execute(
        "INSERT INTO scrobbles (title, artist, timestamp_utc)
        VALUES ('Old', 'Artist', 1700000000000)",
        [],
    )
    .unwrap();
    let old_id = conn.last_insert_rowid();
    let old_device = load_state(&conn).unwrap().device_id;
    pin_local_origin(
        &conn,
        &old_device,
        old_id,
        1_700_000_000_000,
        "Old",
        "Artist",
    )
    .unwrap();
    assert_eq!(pending_local_plays(&conn).unwrap().len(), 1);
    // The real settings UI disconnects before selecting another account.
    // Keep only the last verified subject (no usable token) until the next
    // sign-in so previously owned tracks remain account-isolated.
    conn.execute(
        "UPDATE drive_sync_state SET enabled = 0, access_token = NULL,
        refresh_token = NULL, token_expires_at = 0 WHERE id = 1",
        [],
    )
    .unwrap();
    assert_eq!(
        load_state(&conn).unwrap().account_subject.as_deref(),
        Some("account-a")
    );
    prepare_oauth_credentials(&conn, true).unwrap();
    conn.execute(
        "UPDATE drive_sync_state SET enabled = 1,
        account_subject = 'account-b', account_email = 'b@example.com' WHERE id = 1",
        [],
    )
    .unwrap();
    assert!(
        pending_local_plays(&conn).unwrap().is_empty(),
        "account A plays must not upload to B"
    );
    conn.execute(
        "INSERT INTO scrobbles (title, artist, timestamp_utc)
        VALUES ('New', 'Artist', 1700000001000)",
        [],
    )
    .unwrap();
    let new_id = conn.last_insert_rowid();
    assert_eq!(pending_local_plays(&conn).unwrap().len(), 1);
    pin_local_origin(
        &conn,
        &old_device,
        new_id,
        1_700_000_001_000,
        "New",
        "Artist",
    )
    .unwrap();
    let owner: String = conn
        .query_row(
            "SELECT owner_account_subject FROM drive_event_state WHERE scrobble_id = ?1",
            [new_id],
            |row| row.get(0),
        )
        .unwrap();
    assert_eq!(owner, "account-b");
    prepare_oauth_credentials(&conn, true).unwrap();
    conn.execute(
        "UPDATE drive_sync_state SET enabled = 1,
        account_subject = 'account-a', account_email = 'a@example.com' WHERE id = 1",
        [],
    )
    .unwrap();
    let old_pending = pending_local_plays(&conn).unwrap();
    assert_eq!(
        old_pending.len(),
        1,
        "returning to A must not upload B plays"
    );
    assert_eq!(old_pending[0].id, old_id);
    drop(conn);
    std::fs::remove_dir_all(directory).unwrap();
}

fn fixture_batch() -> WireBatch {
    let event = WireEvent {
        event_id: "69bd5521a322b3d1aaeca431b7380bd49f3a28e1c1d1b1dc0a754ca37e6a06b4".into(),
        title: "Song".into(),
        artist: "Artist".into(),
        album: None,
        timestamp_utc: 1_700_000_000_000,
        duration_ms: 180_000,
        listened_ms: 170_000,
        source_app: "Spotify".into(),
        source: "desktop:Spotify".into(),
        skipped: false,
        replay_count: 0,
        completion_percentage: 94,
        pause_count: 0,
        seek_count: 0,
        session_id: None,
        site: None,
        content_type: "MUSIC".into(),
        volume_level: Some(50),
        total_pause_duration_ms: 0,
        position_updates_count: 0,
    };
    WireBatch {
        schema_version: 1,
        batch_id: batch_id(std::slice::from_ref(&event)),
        source_device_id: "device-1".into(),
        source_device_name: "Tempo Desktop".into(),
        source_platform: "desktop".into(),
        created_at_utc: 1_700_000_000_000,
        events: vec![event],
    }
}

fn fixture_file(batch: &WireBatch, bytes: &[u8]) -> DriveFileRecord {
    DriveFileRecord {
        id: "file-1".into(),
        name: batch_file_name(42, &batch.source_device_id, &batch.batch_id),
        size: Some(bytes.len().to_string()),
        created_time: None,
        modified_time: None,
        app_properties: HashMap::from([
            ("tempo_kind".into(), "history_batch".into()),
            ("tempo_schema".into(), "1".into()),
            ("source_device_id".into(), batch.source_device_id.clone()),
            ("source_platform".into(), batch.source_platform.clone()),
            (APP_PROPERTY_GENERATION.into(), "42".into()),
            (
                APP_PROPERTY_SHA256.into(),
                hex::encode(Sha256::digest(bytes)),
            ),
        ]),
    }
}

fn compress_json(value: &serde_json::Value) -> Vec<u8> {
    let mut encoder = GzEncoder::new(Vec::new(), Compression::default());
    encoder
        .write_all(&serde_json::to_vec(value).unwrap())
        .unwrap();
    encoder.finish().unwrap()
}

// Group larger cross-platform regressions by responsibility.
mod protocol;
mod accounts;
